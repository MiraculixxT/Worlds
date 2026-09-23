package de.miraculixx.chunkeditor.net

import de.miraculixx.chunkeditor.data.ChunkInfo
import de.miraculixx.chunkeditor.data.ChunkMetric
import de.miraculixx.chunkeditor.data.ClipEntry
import de.miraculixx.chunkeditor.data.ClipExportResult
import de.miraculixx.chunkeditor.data.ClipFootprint
import de.miraculixx.chunkeditor.data.EntityMarker
import de.miraculixx.chunkeditor.data.MAX_ENTITIES_PER_REGION
import de.miraculixx.chunkeditor.data.ParsedSelection
import de.miraculixx.chunkeditor.data.SelectionEntry
import de.miraculixx.chunkeditor.data.ExistingChunks
import de.miraculixx.chunkeditor.data.LevelFacts
import de.miraculixx.chunkeditor.data.PlayerMarker
import de.miraculixx.chunkeditor.data.RegionIndex
import de.miraculixx.chunkeditor.data.RegionPixels
import de.miraculixx.chunkeditor.data.ScanResult
import de.miraculixx.chunkeditor.data.ScanSource
import de.miraculixx.chunkeditor.data.TintKey
import de.miraculixx.chunkeditor.data.WorldDimension
import de.miraculixx.chunkeditor.server.JobKind
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.nio.file.Path
import java.util.BitSet
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/** What the server tells a joining client about itself, before any screen exists. */
class Hello(
    val protocol: Int,
    val canRead: Boolean,
    val canWrite: Boolean,
    val worldName: String,
    val dimensions: List<WorldDimension>,
    val facts: LevelFacts,
    val pendingJobs: Int,
)

/** A queued [de.miraculixx.chunkeditor.server.Job] without its chunk list */
class JobSummary(
    val id: String,
    val kind: JobKind,
    val dimension: String,
    val chunks: Int,
    val clip: String,
    val originX: Int,
    val originZ: Int,
    val by: String,
    val at: Long,
)

/** The queue in apply order */
class JobQueue(val jobs: List<JobSummary>, val backup: Boolean)

/**
 * The body encodings for both sides (own encoding to avoid 100 packets)
 */
object Bodies {

    inline fun write(block: (FriendlyByteBuf) -> Unit): ByteArray {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            block(buf)
            val out = ByteArray(buf.readableBytes())
            buf.readBytes(out)
            return out
        } finally {
            buf.release()
        }
    }

    inline fun <T> read(bytes: ByteArray, block: (FriendlyByteBuf) -> T): T {
        val buf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        return try {
            block(buf)
        } finally {
            buf.release()
        }
    }

    //
    // Handshake
    //

    fun writeHello(hello: Hello): ByteArray = write { buf ->
        buf.writeVarInt(hello.protocol)
        buf.writeBoolean(hello.canRead)
        buf.writeBoolean(hello.canWrite)
        buf.writeUtf(hello.worldName)
        buf.writeVarInt(hello.dimensions.size)
        hello.dimensions.forEach {
            buf.writeUtf(it.key.identifier().toString())
            buf.writeUtf(it.labelKey)
        }
        writeFacts(buf, hello.facts)
        buf.writeVarInt(hello.pendingJobs)
    }

    fun readHello(bytes: ByteArray): Hello = read(bytes) { buf ->
        val protocol = buf.readVarInt()
        val canRead = buf.readBoolean()
        val canWrite = buf.readBoolean()
        val world = buf.readUtf()
        val dimensions = (0 until buf.readVarInt()).map {
            val id = buf.readUtf()
            val labelKey = buf.readUtf()
            remoteDimension(id, labelKey)
        }
        Hello(protocol, canRead, canWrite, world, dimensions, readFacts(buf), buf.readVarInt())
    }

    /**
     * A dimension the client only knows by name. Its [WorldDimension.dir] is **empty** — the folder is
     * the server's and every read goes back over the wire, so a client-side path would be a lie. Only
     * flows that hold a real save may touch it, which is what `EditorBackend.localAccess` gates.
     */
    fun remoteDimension(id: String, labelKey: String): WorldDimension {
        val key = ResourceKey.create(Registries.DIMENSION, Identifier.tryParse(id) ?: Level.OVERWORLD.identifier())
        return WorldDimension(key, labelKey, Path.of(""))
    }

    private fun writeFacts(buf: FriendlyByteBuf, facts: LevelFacts) {
        buf.writeLong(facts.gameTime)
        buf.writeInt(facts.spawn.x)
        buf.writeInt(facts.spawn.y)
        buf.writeInt(facts.spawn.z)
    }

    private fun readFacts(buf: FriendlyByteBuf) =
        LevelFacts(buf.readLong(), BlockPos(buf.readInt(), buf.readInt(), buf.readInt()))

    //
    // Requests
    //

    fun dimensionRequest(dimension: WorldDimension): ByteArray = write { it.writeUtf(dimensionId(dimension)) }

    fun dimensionId(dimension: WorldDimension): String = dimension.key.identifier().toString()

    fun indexRequest(dimension: WorldDimension, rx: Int, rz: Int): ByteArray = write { buf ->
        buf.writeUtf(dimensionId(dimension))
        buf.writeInt(rx)
        buf.writeInt(rz)
    }

    fun chunkRequest(dimension: WorldDimension, pos: ChunkPos): ByteArray = write { buf ->
        buf.writeUtf(dimensionId(dimension))
        buf.writeInt(pos.x)
        buf.writeInt(pos.z)
    }

    fun renderRequest(dimension: WorldDimension, rx: Int, rz: Int, step: Int, maxY: Int?): ByteArray = write { buf ->
        buf.writeUtf(dimensionId(dimension))
        buf.writeInt(rx)
        buf.writeInt(rz)
        buf.writeVarInt(step)
        buf.writeBoolean(maxY != null)
        if (maxY != null) buf.writeInt(maxY)
    }

    fun scanRequest(
        dimension: WorldDimension, source: ScanSource, argument: String?, minY: Int, regions: Collection<RegionIndex>,
    ): ByteArray = write { buf ->
        buf.writeUtf(dimensionId(dimension))
        buf.writeVarInt(source.ordinal)
        buf.writeBoolean(argument != null)
        if (argument != null) buf.writeUtf(argument)
        buf.writeInt(minY)
        // Only the coordinates: the server holds the very region files these were read from
        buf.writeVarInt(regions.size)
        regions.forEach {
            buf.writeInt(it.rx)
            buf.writeInt(it.rz)
        }
    }

    fun conflictsRequest(
        dimension: WorldDimension, chunks: Collection<ChunkPos>, clipOrigin: ChunkPos, origin: ChunkPos,
    ): ByteArray = write { buf ->
        buf.writeUtf(dimensionId(dimension))
        buf.writeInt(clipOrigin.x)
        buf.writeInt(clipOrigin.z)
        buf.writeInt(origin.x)
        buf.writeInt(origin.z)
        buf.writeVarInt(chunks.size)
        chunks.forEach { buf.writeLong(it.pack()) }
    }

    fun uploadOpen(name: String, path: String, size: Long): ByteArray = write { buf ->
        buf.writeUtf(name)
        buf.writeUtf(path)
        buf.writeVarLong(size)
    }

    fun uploadFrame(id: Int, index: Int, bytes: ByteArray): ByteArray = write { buf ->
        buf.writeVarInt(id)
        buf.writeVarInt(index)
        buf.writeByteArray(bytes)
    }

    fun deleteRequest(dimension: WorldDimension, chunks: Collection<ChunkPos>, backup: Boolean): ByteArray =
        write { buf ->
            buf.writeUtf(dimensionId(dimension))
            buf.writeBoolean(backup)
            buf.writeVarInt(chunks.size)
            chunks.forEach { buf.writeLong(it.pack()) }
        }

    //
    // Results
    //

    fun writeRegionList(regions: List<Pair<Int, Int>>): ByteArray = write { buf ->
        buf.writeVarInt(regions.size)
        regions.forEach {
            buf.writeInt(it.first)
            buf.writeInt(it.second)
        }
    }

    fun readRegionList(bytes: ByteArray): List<Pair<Int, Int>> = read(bytes) { buf ->
        (0 until buf.readVarInt()).map { buf.readInt() to buf.readInt() }
    }

    fun writeIndex(index: RegionIndex?): ByteArray = write { buf ->
        buf.writeBoolean(index != null)
        if (index == null) return@write
        buf.writeInt(index.rx)
        buf.writeInt(index.rz)
        buf.writeLong(index.bytes)
        buf.writeLongArray(index.present.toLongArray())
    }

    fun readIndex(bytes: ByteArray): RegionIndex? = read(bytes) { buf ->
        if (!buf.readBoolean()) return@read null
        val rx = buf.readInt()
        val rz = buf.readInt()
        val size = buf.readLong()
        RegionIndex(rx, rz, BitSet.valueOf(buf.readLongArray()), size)
    }

    fun writeChunkInfo(info: ChunkInfo): ByteArray = write { buf ->
        writeOptional(buf, info.inhabitedTicks)
        writeOptional(buf, info.lastWritten)
        writeOptional(buf, info.entities?.toLong())
    }

    /** @param pos what was asked for, the answer only carries the numbers */
    fun readChunkInfo(bytes: ByteArray, pos: ChunkPos): ChunkInfo = read(bytes) { buf ->
        ChunkInfo(pos, readOptional(buf), readOptional(buf), readOptional(buf)?.toInt())
    }

    private fun writeOptional(buf: FriendlyByteBuf, value: Long?) {
        buf.writeBoolean(value != null)
        if (value != null) buf.writeLong(value)
    }

    private fun readOptional(buf: FriendlyByteBuf): Long? = if (buf.readBoolean()) buf.readLong() else null

    fun writeRange(range: IntRange?): ByteArray = write { buf ->
        buf.writeBoolean(range != null)
        if (range != null) {
            buf.writeInt(range.first)
            buf.writeInt(range.last)
        }
    }

    fun readRange(bytes: ByteArray): IntRange? = read(bytes) { buf ->
        if (buf.readBoolean()) buf.readInt()..buf.readInt() else null
    }

    fun writeJobQueue(queue: JobQueue): ByteArray = write { buf ->
        buf.writeBoolean(queue.backup)
        buf.writeVarInt(queue.jobs.size)
        queue.jobs.forEach {
            buf.writeUtf(it.id)
            buf.writeVarInt(it.kind.ordinal)
            buf.writeUtf(it.dimension)
            buf.writeVarInt(it.chunks)
            buf.writeUtf(it.clip)
            buf.writeInt(it.originX)
            buf.writeInt(it.originZ)
            buf.writeUtf(it.by)
            buf.writeLong(it.at)
        }
    }

    fun readJobQueue(bytes: ByteArray): JobQueue = read(bytes) { buf ->
        val backup = buf.readBoolean()
        val jobs = (0 until buf.readVarInt()).map {
            JobSummary(
                buf.readUtf(), JobKind.entries[buf.readVarInt()], buf.readUtf(), buf.readVarInt(), buf.readUtf(),
                buf.readInt(), buf.readInt(), buf.readUtf(), buf.readLong(),
            )
        }
        JobQueue(jobs, backup)
    }

    fun writeInt(value: Int): ByteArray = write { it.writeInt(value) }

    fun readInt(bytes: ByteArray): Int = read(bytes) { it.readInt() }

    fun writePixels(pixels: RegionPixels?): ByteArray = write { buf ->
        buf.writeBoolean(pixels != null)
        if (pixels == null) return@write
        buf.writeVarInt(pixels.size)
        pixels.argb.forEach(buf::writeInt)
        buf.writeVarInt(pixels.unreadable.size)
        pixels.unreadable.forEach(buf::writeLong)
        buf.writeVarInt(pixels.tintPalette.size)
        pixels.tintPalette.forEach {
            buf.writeUtf(it.block.toString())
            buf.writeUtf(it.biome)
            buf.writeVarInt(it.mapColor)
        }
        buf.writeBoolean(pixels.tintIndex != null)
        pixels.tintIndex?.forEach { buf.writeShort(it.toInt()) }
    }

    fun readPixels(bytes: ByteArray): RegionPixels? = read(bytes) { buf ->
        if (!buf.readBoolean()) return@read null
        val size = buf.readVarInt()
        val argb = IntArray(size * size) { buf.readInt() }
        val unreadable = HashSet<Long>()
        repeat(buf.readVarInt()) { unreadable.add(buf.readLong()) }
        val palette = (0 until buf.readVarInt()).map {
            TintKey(Identifier.parse(buf.readUtf()), buf.readUtf(), buf.readVarInt())
        }
        val tints = if (buf.readBoolean()) ShortArray(size * size) { buf.readShort() } else null
        RegionPixels(size, argb, unreadable, palette, tints)
    }

    fun writeScan(result: ScanResult): ByteArray = write { buf ->
        buf.writeVarInt(result.source.ordinal)
        buf.writeBoolean(result.argument != null)
        result.argument?.let(buf::writeUtf)
        buf.writeVarInt(result.labels.size)
        result.labels.forEach(buf::writeUtf)
        buf.writeVarInt(result.values.size)
        result.values.forEach { (metric, values) ->
            buf.writeVarInt(metric.ordinal)
            buf.writeVarInt(values.size)
            values.long2LongEntrySet().forEach { entry ->
                buf.writeLong(entry.longKey)
                buf.writeLong(entry.longValue)
            }
        }
    }

    fun readScan(bytes: ByteArray): ScanResult = read(bytes) { buf ->
        val source = ScanSource.entries[buf.readVarInt()]
        val argument = if (buf.readBoolean()) buf.readUtf() else null
        val labels = (0 until buf.readVarInt()).map { buf.readUtf() }
        val values = HashMap<ChunkMetric, Long2LongOpenHashMap>()
        repeat(buf.readVarInt()) {
            val metric = ChunkMetric.entries[buf.readVarInt()]
            val size = buf.readVarInt()
            val map = Long2LongOpenHashMap(size)
            repeat(size) { map[buf.readLong()] = buf.readLong() }
            values[metric] = map
        }
        ScanResult(source, argument, values, labels)
    }

    fun writePlayers(markers: List<PlayerMarker>): ByteArray = write { buf ->
        buf.writeVarInt(markers.size)
        markers.forEach {
            buf.writeUUID(it.id)
            buf.writeDouble(it.pos.x)
            buf.writeDouble(it.pos.y)
            buf.writeDouble(it.pos.z)
            buf.writeUtf(it.dimension.toString())
        }
    }

    fun readPlayers(bytes: ByteArray): List<PlayerMarker> = read(bytes) { buf ->
        (0 until buf.readVarInt()).map {
            val id: UUID = buf.readUUID()
            val pos = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            PlayerMarker(id, pos, Identifier.parse(buf.readUtf()))
        }
    }

    fun writeEntities(markers: List<EntityMarker>): ByteArray = write { buf ->
        buf.writeVarInt(markers.size)
        markers.forEach {
            buf.writeUtf(it.type)
            buf.writeDouble(it.pos.x)
            buf.writeDouble(it.pos.y)
            buf.writeDouble(it.pos.z)
        }
    }

    fun readEntities(bytes: ByteArray): List<EntityMarker> = read(bytes) { buf ->
        val size = buf.readVarInt()
        require(size in 0..MAX_ENTITIES_PER_REGION) { "Entity marker count of $size" }
        (0 until size).map {
            EntityMarker(buf.readUtf(), Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()))
        }
    }

    fun writeError(key: String, argument: String?): ByteArray = write { buf ->
        buf.writeUtf(key)
        buf.writeBoolean(argument != null)
        argument?.let(buf::writeUtf)
    }

    fun readError(bytes: ByteArray): Pair<String, String?> = read(bytes) { buf ->
        buf.readUtf() to if (buf.readBoolean()) buf.readUtf() else null
    }

    fun writeProgress(done: Int, total: Int): ByteArray = write { buf ->
        buf.writeVarInt(done)
        buf.writeVarInt(total)
    }

    fun readProgress(bytes: ByteArray): Pair<Int, Int> = read(bytes) { it.readVarInt() to it.readVarInt() }

    //
    // Compression
    //

    /** Results are deflated whatever their size to avoid unnessary communication */
    fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(raw.size / 4 + 64)
            out.write(intBytes(raw.size))
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                if (n > 0) out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun inflate(packed: ByteArray): ByteArray {
        require(packed.size >= 4) { "Truncated editor body" }
        val size = ((packed[0].toInt() and 0xFF) shl 24) or ((packed[1].toInt() and 0xFF) shl 16) or
            ((packed[2].toInt() and 0xFF) shl 8) or (packed[3].toInt() and 0xFF)
        require(size in 0..MAX_INFLATED) { "Editor body claims $size bytes" }
        val out = ByteArray(size)
        val inflater = Inflater()
        try {
            inflater.setInput(packed, 4, packed.size - 4)
            var at = 0
            while (at < size) {
                val n = inflater.inflate(out, at, size - at)
                if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                at += n
            }
            require(at == size) { "Editor body was $at bytes, not the $size it claimed" }
            return out
        } finally {
            inflater.end()
        }
    }

    /** A declared length is the senders claim, so cap it before allocating */
    private const val MAX_INFLATED = 64 * 1024 * 1024

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )
}

/**
 * The library's own encodings, kept apart from [Bodies] only for length
 */
object LibraryBodies {

    fun writeClips(clips: List<ClipEntry>): ByteArray = Bodies.write { buf ->
        buf.writeVarInt(clips.size)
        clips.forEach {
            buf.writeUtf(it.name)
            buf.writeUtf(it.dimension)
            buf.writeUtf(it.mcVersion)
            buf.writeVarInt(it.chunks)
            buf.writeLong(it.bytes)
            buf.writeLong(it.modified)
        }
    }

    fun readClips(bytes: ByteArray): List<ClipEntry> = Bodies.read(bytes) { buf ->
        (0 until buf.readVarInt()).map {
            ClipEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readLong(), buf.readLong())
        }
    }

    fun writeSelections(selections: List<SelectionEntry>): ByteArray = Bodies.write { buf ->
        buf.writeVarInt(selections.size)
        selections.forEach {
            buf.writeUtf(it.name)
            buf.writeVarInt(it.chunks)
            buf.writeBoolean(it.inverted)
            buf.writeLong(it.bytes)
            buf.writeLong(it.modified)
        }
    }

    fun readSelections(bytes: ByteArray): List<SelectionEntry> = Bodies.read(bytes) { buf ->
        (0 until buf.readVarInt()).map {
            SelectionEntry(buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readLong(), buf.readLong())
        }
    }

    fun writeFootprint(footprint: ClipFootprint?): ByteArray = Bodies.write { buf ->
        buf.writeBoolean(footprint != null)
        if (footprint == null) return@write
        buf.writeLong(footprint.origin.pack())
        buf.writeVarInt(footprint.chunks.size)
        footprint.chunks.forEach { buf.writeLong(it.pack()) }
    }

    fun readFootprint(bytes: ByteArray): ClipFootprint? = Bodies.read(bytes) { buf ->
        if (!buf.readBoolean()) return@read null
        val origin = ChunkPos.unpack(buf.readLong())
        ClipFootprint(origin, (0 until buf.readVarInt()).map { ChunkPos.unpack(buf.readLong()) })
    }

    fun writeSelection(selection: ParsedSelection?): ByteArray = Bodies.write { buf ->
        buf.writeBoolean(selection != null)
        if (selection == null) return@write
        buf.writeBoolean(selection.inverted)
        buf.writeVarInt(selection.chunks.size)
        selection.chunks.forEach(buf::writeLong)
    }

    fun readSelection(bytes: ByteArray): ParsedSelection? = Bodies.read(bytes) { buf ->
        if (!buf.readBoolean()) return@read null
        val inverted = buf.readBoolean()
        val chunks = HashSet<Long>()
        repeat(buf.readVarInt()) { chunks.add(buf.readLong()) }
        ParsedSelection(chunks, inverted)
    }

    fun writeExport(result: ClipExportResult): ByteArray = Bodies.write { buf ->
        when (result) {
            is ClipExportResult.Success -> {
                buf.writeBoolean(true)
                buf.writeUtf(result.name)
                buf.writeVarInt(result.chunks)
            }

            is ClipExportResult.Failure -> {
                buf.writeBoolean(false)
                buf.writeUtf(result.message)
            }
        }
    }

    fun readExport(bytes: ByteArray): ClipExportResult = Bodies.read(bytes) { buf ->
        if (buf.readBoolean()) ClipExportResult.Success(buf.readUtf(), buf.readVarInt())
        else ClipExportResult.Failure(buf.readUtf())
    }

    fun exportRequest(name: String, dimension: WorldDimension, chunks: Collection<ChunkPos>): ByteArray =
        Bodies.write { buf ->
            buf.writeUtf(Bodies.dimensionId(dimension))
            buf.writeUtf(name)
            buf.writeVarInt(chunks.size)
            chunks.forEach { buf.writeLong(it.pack()) }
        }

    fun selectionExportRequest(name: String, chunks: Collection<ChunkPos>, inverted: Boolean): ByteArray = Bodies.write { buf ->
        buf.writeUtf(name)
        buf.writeBoolean(inverted)
        buf.writeVarInt(chunks.size)
        chunks.forEach { buf.writeLong(it.pack()) }
    }

    fun writeFiles(files: List<Pair<String, Long>>): ByteArray = Bodies.write { buf ->
        buf.writeVarInt(files.size)
        files.forEach {
            buf.writeUtf(it.first)
            buf.writeVarLong(it.second)
        }
    }

    fun readFiles(bytes: ByteArray): List<Pair<String, Long>> = Bodies.read(bytes) { buf ->
        (0 until buf.readVarInt()).map { buf.readUtf() to buf.readVarLong() }
    }

    fun fileRequest(name: String, file: String): ByteArray = Bodies.write { buf ->
        buf.writeUtf(name)
        buf.writeUtf(file)
    }

    fun writeFile(bytes: ByteArray): ByteArray = Bodies.write { it.writeByteArray(bytes) }

    fun readFile(bytes: ByteArray): ByteArray = Bodies.read(bytes) { it.readByteArray(MAX_CLIP_FILE) }

    fun name(name: String): ByteArray = Bodies.write { it.writeUtf(name) }

    fun readName(bytes: ByteArray): String = Bodies.read(bytes) { it.readUtf() }

    fun writeBoolean(value: Boolean): ByteArray = Bodies.write { it.writeBoolean(value) }

    fun readBoolean(bytes: ByteArray): Boolean = Bodies.read(bytes) { it.readBoolean() }

    fun pasteRequest(
        dimension: WorldDimension, clip: String, origin: ChunkPos,
        yOffset: Int, ranges: List<IntRange>, existing: ExistingChunks, backup: Boolean,
    ): ByteArray = Bodies.write { buf ->
        buf.writeUtf(Bodies.dimensionId(dimension))
        buf.writeUtf(clip)
        buf.writeInt(origin.x)
        buf.writeInt(origin.z)
        buf.writeInt(yOffset)
        buf.writeVarInt(ranges.size)
        ranges.forEach {
            buf.writeInt(it.first)
            buf.writeInt(it.last)
        }
        buf.writeVarInt(existing.ordinal)
        buf.writeBoolean(backup)
    }
}
