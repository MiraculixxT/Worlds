package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ChunkClips
import de.miraculixx.chunkeditor.data.ClipFootprint
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ExistingChunks
import de.miraculixx.chunkeditor.data.ScanSource
import de.miraculixx.chunkeditor.data.WorldDimension
import de.miraculixx.chunkeditor.data.ImportResult
import de.miraculixx.chunkeditor.data.LocalLibrary
import de.miraculixx.chunkeditor.net.Bodies
import de.miraculixx.chunkeditor.net.LibraryBodies
import de.miraculixx.chunkeditor.net.C2S
import de.miraculixx.chunkeditor.net.UPLOAD_PIECE
import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Hello
import de.miraculixx.chunkeditor.net.Net
import de.miraculixx.chunkeditor.net.PROTOCOL_VERSION
import de.miraculixx.chunkeditor.net.Reassembler
import de.miraculixx.chunkeditor.net.S2C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories

private const val MAX_QUEUED = 64
private const val MAX_RENDERS = 4
private const val MAX_SCANS = 1

/** How much of one message may sit half-arrived */
private const val REASSEMBLY_BYTES = 8L * 1024 * 1024
private const val REASSEMBLY_OPEN = 8

private val CLIP_FILE = Regex("""(clip\.json|(region|entities|poi)/r\.-?\d+\.-?\d+\.mca)""")

/**
 * One session per admin, the reads they ask for, and a cache for shared results (prevent double scans)
 */
object EditorService {

    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var backend: ServerBackend? = null

    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val renderCache = ByteCache(Perms.config.renderCacheMb.toLong() * 1024 * 1024)
    private val indexCache = ByteCache(8L * 1024 * 1024)
    private val scanCache = ByteCache(64L * 1024 * 1024)

    /** One thread per dimension */
    private val dispatchers = ConcurrentHashMap<String, kotlinx.coroutines.CoroutineDispatcher>()

    fun start(server: MinecraftServer) {
        this.server = server
        this.backend = null
        // A loader that never delivered its registration event leaves no transport and no way to notice
        Constants.LOG.info(
            "Chunk editor service ready ({} job(s) queued, networking {})",
            ServerJobs.list().size, if (Net.transport == null) "UNAVAILABLE" else "ready",
        )
    }

    fun stop() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        renderCache.clear()
        indexCache.clear()
        scanCache.clear()
        server = null
        backend = null
    }

    /** Tell compat clients that they have no perms */
    fun onJoin(player: ServerPlayer) {
        val transport = Net.transport ?: return
        if (!transport.canReach(player)) return
        val canRead = Perms.canRead(player)
        val canWrite = canRead && Perms.canWrite(player)
        val backend = backend() ?: return
        if (canRead) sessions[player.uuid] = Session(player.uuid)
        Net.toClient(
            player, 0, S2C.HELLO,
            Bodies.writeHello(
                Hello(
                    PROTOCOL_VERSION, canRead, canWrite, backend.root.fileName?.toString() ?: "world",
                    backend.dimensions, backend.facts, ServerJobs.list().size,
                ),
            ),
        )
    }

    fun onLeave(player: ServerPlayer) {
        sessions.remove(player.uuid)?.close()
    }

    fun receive(player: ServerPlayer, packet: EditorPacket) {
        val session = sessions[player.uuid] ?: return
        val body = session.frames.accept(packet) { fail(player, packet.request, "chunkeditor.remote.error.dropped") }
            ?: return
        if (packet.kind == C2S.OPEN) {
            Constants.LOG.info(
                "editor opened by {} ({}) on {}",
                player.name.string, if (Perms.canWrite(player)) "read+write" else "read only",
                backend()?.root?.fileName ?: "?",
            )
            return
        }
        if (packet.kind == C2S.CANCEL) {
            session.cancel(Bodies.readInt(body))
            return
        }
        if (session.queued.get() > MAX_QUEUED) {
            fail(player, packet.request, "chunkeditor.remote.error.busy")
            return
        }
        // check perms on EVERY (!!!) request
        if (!Perms.canRead(player)) {
            fail(player, packet.request, "chunkeditor.remote.error.denied")
            return
        }
        val writes = packet.kind in WRITE_KINDS
        if (writes && !Perms.canWrite(player)) {
            fail(player, packet.request, "chunkeditor.remote.error.denied")
            return
        }
        session.start(packet.request) { handle(player, session, packet.request, packet.kind, body) }
    }

    private suspend fun handle(player: ServerPlayer, session: Session, request: Int, kind: Int, body: ByteArray) {
        val backend = backend() ?: return fail(player, request, "chunkeditor.remote.error.unavailable")
        try {
            when (kind) {
                C2S.REGION_LIST -> {
                    val dimension = dimension(backend, body) ?: return fail(player, request, DIMENSION_GONE)
                    onDimension(dimension) {
                        reply(player, request, Bodies.writeRegionList(backend.regionList(dimension)))
                    }
                }

                C2S.INDEX -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val rx = buf.readInt()
                    val rz = buf.readInt()
                    val key = "${Bodies.dimensionId(dimension)}|$rx|$rz"
                    val stamp = stampOf(dimension, rx, rz)
                    indexCache[key, stamp]?.let { return@read reply(player, request, it) }
                    onDimension(dimension) {
                        val encoded = Bodies.writeIndex(backend.index(dimension, rx, rz))
                        indexCache.put(key, stamp, encoded)
                        reply(player, request, encoded)
                    }
                }

                C2S.HEIGHT_BOUNDS -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val pos = ChunkPos(buf.readInt(), buf.readInt())
                    onDimension(dimension) {
                        reply(player, request, Bodies.writeRange(backend.heightBounds(dimension, pos)))
                    }
                }

                C2S.RENDER -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val rx = buf.readInt()
                    val rz = buf.readInt()
                    val step = buf.readVarInt()
                    val maxY = if (buf.readBoolean()) buf.readInt() else null
                    val key = "${Bodies.dimensionId(dimension)}|$rx|$rz|$step|$maxY"
                    val stamp = stampOf(dimension, rx, rz)
                    renderCache[key, stamp]?.let { return@read reply(player, request, it) }
                    session.renders.withPermit {
                        onDimension(dimension) {
                            val encoded = Bodies.writePixels(backend.render(dimension, rx, rz, step, maxY))
                            renderCache.put(key, stamp, encoded)
                            reply(player, request, encoded)
                        }
                    }
                }

                C2S.SCAN -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val source = ScanSource.entries[buf.readVarInt()]
                    val argument = if (buf.readBoolean()) buf.readUtf() else null
                    val minY = buf.readInt()
                    val wanted = (0 until buf.readVarInt()).map { buf.readInt() to buf.readInt() }
                    session.scans.withPermit { runScan(player, backend, session, request, dimension, source, argument, minY, wanted) }
                }

                C2S.PLAYERS -> reply(player, request, Bodies.writePlayers(backend.players()))

                C2S.CONFLICTS -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val clipOrigin = ChunkPos(buf.readInt(), buf.readInt())
                    val origin = ChunkPos(buf.readInt(), buf.readInt())
                    val chunks = (0 until buf.readVarInt()).map { ChunkPos.unpack(buf.readLong()) }
                    onDimension(dimension) {
                        val footprint = ClipFootprint(clipOrigin, chunks)
                        reply(player, request, Bodies.writeInt(backend.conflicts(dimension, footprint, origin)))
                    }
                }

                C2S.CLIP_UPLOAD_OPEN -> Bodies.read(body) { buf ->
                    val id = session.openUpload(buf.readUtf(), buf.readUtf(), buf.readVarLong())
                        ?: return@read fail(player, request, "chunkeditor.remote.error.upload")
                    reply(player, request, Bodies.writeInt(id))
                }

                C2S.CLIP_UPLOAD -> Bodies.read(body) { buf ->
                    val id = buf.readVarInt()
                    val index = buf.readVarInt()
                    if (!session.stage(id, index, buf.readByteArray(UPLOAD_PIECE))) {
                        return@read fail(player, request, "chunkeditor.remote.error.upload")
                    }
                    reply(player, request, Bodies.writeInt(index))
                }

                C2S.CLIP_UPLOAD_END -> {
                    val landed = session.finishUpload(LibraryBodies.readName(body)) ?:
                        return fail(player, request, "chunkeditor.remote.error.upload")
                    Constants.LOG.info("clip {} uploaded to the library by {}", landed, player.name.string)
                    reply(player, request, LibraryBodies.name(landed))
                }

                C2S.CLIP_LIST -> reply(player, request, LibraryBodies.writeClips(backend.library.clips()))

                C2S.SELECTION_LIST ->
                    reply(player, request, LibraryBodies.writeSelections(backend.library.selections()))

                C2S.CLIP_FOOTPRINT ->
                    reply(player, request, LibraryBodies.writeFootprint(backend.library.footprint(LibraryBodies.readName(body))))

                C2S.SELECTION_READ ->
                    reply(player, request, LibraryBodies.writeSelection(backend.library.selection(LibraryBodies.readName(body))))

                C2S.CLIP_DELETE ->
                    reply(player, request, LibraryBodies.writeBoolean(backend.library.deleteClip(LibraryBodies.readName(body))))

                C2S.SELECTION_DELETE ->
                    reply(player, request, LibraryBodies.writeBoolean(backend.library.deleteSelection(LibraryBodies.readName(body))))

                C2S.CLIP_EXPORT -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val name = buf.readUtf()
                    val chunks = (0 until buf.readVarInt()).map { ChunkPos.unpack(buf.readLong()) }
                    onDimension(dimension) {
                        val result = backend.exportClip(name, dimension, chunks) { done, total ->
                            Net.toClient(player, request, S2C.PROGRESS, Bodies.writeProgress(done, total))
                        }
                        reply(player, request, LibraryBodies.writeExport(result))
                    }
                }

                C2S.SELECTION_EXPORT -> Bodies.read(body) { buf ->
                    val name = buf.readUtf()
                    val inverted = buf.readBoolean()
                    val chunks = (0 until buf.readVarInt()).map { ChunkPos.unpack(buf.readLong()) }
                    reply(player, request, LibraryBodies.writeBoolean(backend.exportSelection(name, chunks, inverted)))
                }

                C2S.JOB_DELETE -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val backup = buf.readBoolean()
                    val chunks = (0 until buf.readVarInt()).map { ChunkPos.unpack(buf.readLong()) }
                    reply(player, request, Bodies.writeInt(backend.delete(dimension, chunks, backup)))
                }

                C2S.JOB_PASTE -> Bodies.read(body) { buf ->
                    val dimension = backend.dimension(buf.readUtf()) ?: return@read fail(player, request, DIMENSION_GONE)
                    val clip = buf.readUtf()
                    val origin = ChunkPos(buf.readInt(), buf.readInt())
                    val yOffset = buf.readInt()
                    val ranges = (0 until buf.readVarInt()).map { buf.readInt()..buf.readInt() }
                    val existing = ExistingChunks.entries[buf.readVarInt()]
                    val backup = buf.readBoolean()
                    val result = backend.paste(
                        dimension, clip, origin, ClipImportOptions(yOffset, ranges, existing), backup,
                    ) { _, _ -> }
                    when (result) {
                        is ImportResult.Success -> {
                            Constants.LOG.info("queued paste of {} by {}: {} chunks", clip, player.name.string, result.written)
                            reply(player, request, Bodies.writeInt(result.written))
                        }

                        is ImportResult.Failure -> fail(player, request, result.message)
                    }
                }

                C2S.FORCE_SAVE -> {
                    backend.forceSave()
                    invalidate()
                    reply(player, request, Bodies.writeInt(1))
                }

                else -> fail(player, request, "chunkeditor.remote.error.unknown")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Constants.LOG.warn("Editor request {} from {} failed", kind, player.name.string, e)
            fail(player, request, "chunkeditor.remote.error.failed")
        } finally {
            session.finish(request)
        }
    }

    private suspend fun runScan(
        player: ServerPlayer, backend: ServerBackend, session: Session, request: Int,
        dimension: WorldDimension, source: ScanSource, argument: String?, minY: Int, wanted: List<Pair<Int, Int>>,
    ) {
        val regions = wanted.mapNotNull { (rx, rz) -> backend.index(dimension, rx, rz) }
        val key = "${Bodies.dimensionId(dimension)}|${source.ordinal}|$argument|$minY|${wanted.size}|${wanted.hashCode()}"
        val stamp = regions.sumOf { stampOf(dimension, it.rx, it.rz) }
        scanCache[key, stamp]?.let { return reply(player, request, it) }
        onDimension(dimension) {
            val result = backend.scan(dimension, regions, source, argument, minY) { done, total ->
                Net.toClient(player, request, S2C.PROGRESS, Bodies.writeProgress(done, total))
            }
            val encoded = Bodies.writeScan(result)
            scanCache.put(key, stamp, encoded)
            reply(player, request, encoded)
        }
    }

    private fun backend(): ServerBackend? {
        backend?.let { return it }
        val server = server ?: return null
        val fresh = ServerBackend(server, "server")
        backend = fresh
        return fresh
    }

    private fun ServerBackend.dimension(id: String): WorldDimension? =
        dimensions.firstOrNull { Bodies.dimensionId(it) == id }

    private fun dimension(backend: ServerBackend, body: ByteArray): WorldDimension? =
        backend.dimension(Bodies.read(body) { it.readUtf() })

    private suspend inline fun <T> onDimension(dimension: WorldDimension, crossinline block: suspend () -> T): T {
        val dispatcher = dispatchers.computeIfAbsent(Bodies.dimensionId(dimension)) {
            Dispatchers.IO.limitedParallelism(1)
        }
        return kotlinx.coroutines.withContext(dispatcher) { block() }
    }

    private fun reply(player: ServerPlayer, request: Int, raw: ByteArray) =
        Net.toClient(player, request, S2C.RESULT, Bodies.deflate(raw))

    private fun fail(player: ServerPlayer, request: Int, key: String) =
        Net.toClient(player, request, S2C.ERROR, Bodies.writeError(key, null))

    private fun invalidate() {
        renderCache.clear()
        indexCache.clear()
        scanCache.clear()
    }

    private fun stampOf(dimension: WorldDimension, rx: Int, rz: Int): Long = try {
        val file = dimension.regionDir.resolve("r.$rx.$rz.mca")
        val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
        attributes.lastModifiedTime().toMillis() * 31 + attributes.size()
    } catch (_: Exception) {
        0L
    }

    private const val DIMENSION_GONE = "chunkeditor.remote.error.dimension"

    /** Everything that changes the world, queue or lib */
    private val WRITE_KINDS = setOf(
        C2S.JOB_DELETE, C2S.JOB_PASTE, C2S.FORCE_SAVE,
        C2S.CLIP_UPLOAD_OPEN, C2S.CLIP_UPLOAD, C2S.CLIP_UPLOAD_END, C2S.CLIP_EXPORT, C2S.SELECTION_EXPORT,
        C2S.CLIP_DELETE, C2S.SELECTION_DELETE,
    )

    /**
     * One open map, managed and limited by the session (to avoid spam)
     */
    private class Session(val uuid: UUID) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val frames = Reassembler(REASSEMBLY_BYTES, REASSEMBLY_OPEN)
        val renders = Semaphore(MAX_RENDERS)
        val scans = Semaphore(MAX_SCANS)
        val queued = java.util.concurrent.atomic.AtomicInteger()

        private val running = ConcurrentHashMap<Int, CoroutineJob>()
        private class Upload(val name: String, val channel: FileChannel, val size: Long)

        private val uploads = ConcurrentHashMap<Int, Upload>()
        private val nextUpload = java.util.concurrent.atomic.AtomicInteger()

        /** Declared bytes per requested clip name, against the upload limit */
        private val held = ConcurrentHashMap<String, Long>()

        /** Requested name to path (in lib) */
        private val targets = ConcurrentHashMap<String, Path>()

        fun start(request: Int, block: suspend () -> Unit) {
            queued.incrementAndGet()
            running[request] = scope.launch { block() }
        }

        fun finish(request: Int) {
            running.remove(request)
            queued.decrementAndGet()
        }

        fun cancel(request: Int) {
            running.remove(request)?.cancel()
        }

        /** Opens one file of an upload in the lib and answers the id its frames carry */
        fun openUpload(name: String, file: String, size: Long): Int? {
            if (!LocalLibrary.safe(name) || !CLIP_FILE.matches(file) || size < 0) return null
            if (held.merge(name, size, Long::plus)!! > Perms.config.maxUploadMb.toLong() * 1024 * 1024) {
                Constants.LOG.warn("upload {} is over the size limit, dropping it", name)
                return null
            }
            val dir = targets.computeIfAbsent(name) { ChunkClips.freeDir(it) }
            val target = dir.resolve(file).normalize()
            if (!target.startsWith(dir.normalize())) return null
            return try {
                target.parent.createDirectories()
                val channel = FileChannel.open(
                    target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                )
                nextUpload.incrementAndGet().also { uploads[it] = Upload(name, channel, size) }
            } catch (e: Exception) {
                Constants.LOG.warn("could not stage {} of upload {}: {}", file, name, e.message)
                null
            }
        }

        /** Writes one frame at its own offset (frames can come in any order) */
        fun stage(id: Int, index: Int, bytes: ByteArray): Boolean {
            val upload = uploads[id] ?: return false
            var at = index.toLong() * UPLOAD_PIECE
            return !(index < 0 || at + bytes.size > upload.size) && try {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) at += upload.channel.write(buffer, at)
                true
            } catch (e: Exception) {
                Constants.LOG.warn("could not write upload {}: {}", upload.name, e.message)
                false
            }
        }

        /** @return the name the lib gave the clip, which is not the requested one on a collision */
        fun finishUpload(name: String): String? {
            held.remove(name)
            closeUploads { it.name == name }
            val dir = targets.remove(name) ?: return null
            return if (ChunkClips.read(dir) != null) dir.fileName.toString() else null
        }

        private fun closeUploads(filter: (Upload) -> Boolean) {
            uploads.entries.filter { filter(it.value) }.forEach { (id, _) ->
                uploads.remove(id)?.let { runCatching { it.channel.close() } }
            }
        }

        fun close() {
            closeUploads { true }
            // An upload that never reported its end is half a clip, so it does not stay on the shelf
            targets.values.forEach { dir ->
                runCatching { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            }
            targets.clear()
            held.clear()
            frames.clear()
            scope.cancel()
        }
    }

    /**
     * A byte-budgeted LRU whose entries are only valid while the file they were read from has not been rewritten.
     */
    private class ByteCache(private val budget: Long) {
        private val entries = object : LinkedHashMap<String, Pair<Long, ByteArray>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ByteArray>>): Boolean {
                if (held <= budget) return false
                held -= eldest.value.second.size
                return true
            }
        }
        private var held = 0L

        @Synchronized
        operator fun get(key: String, stamp: Long): ByteArray? {
            val found = entries[key] ?: return null
            if (found.first != stamp) {
                entries.remove(key)
                held -= found.second.size
                return null
            }
            return found.second
        }

        @Synchronized
        fun put(key: String, stamp: Long, bytes: ByteArray) {
            if (bytes.size > budget) return
            entries.put(key, stamp to bytes)?.let { held -= it.second.size }
            held += bytes.size
            // The oldest is only tested on insert, so one put can leave more than one entry over budget
            while (held > budget && entries.isNotEmpty()) {
                val eldest = entries.entries.iterator()
                val first = eldest.next()
                eldest.remove()
                held -= first.value.second.size
            }
        }

        @Synchronized
        fun clear() {
            entries.clear()
            held = 0
        }
    }
}
