package de.miraculixx.chunkeditor.client.net

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.CLIP_FILE
import de.miraculixx.chunkeditor.data.ChunkClips
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ClipInfo
import de.miraculixx.chunkeditor.data.EditorBackend
import de.miraculixx.chunkeditor.data.EntityMarker
import de.miraculixx.chunkeditor.data.ImportResult
import de.miraculixx.chunkeditor.data.LevelFacts
import de.miraculixx.chunkeditor.data.PlayerMarker
import de.miraculixx.chunkeditor.data.RegionIndex
import de.miraculixx.chunkeditor.data.RegionPixels
import de.miraculixx.chunkeditor.data.ScanResult
import de.miraculixx.chunkeditor.data.ScanSource
import de.miraculixx.chunkeditor.data.WorldDimension
import de.miraculixx.chunkeditor.data.ClipExportResult
import de.miraculixx.chunkeditor.data.ClipFootprint
import de.miraculixx.chunkeditor.data.ClipLibrary
import de.miraculixx.chunkeditor.net.Bodies
import de.miraculixx.chunkeditor.net.LibraryBodies
import de.miraculixx.chunkeditor.net.C2S
import de.miraculixx.chunkeditor.net.MAX_CLIP_FILE
import de.miraculixx.chunkeditor.net.UPLOAD_PIECE
import de.miraculixx.chunkeditor.net.Hello
import de.miraculixx.chunkeditor.net.JobQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import kotlin.io.path.createDirectories

private const val UPLOAD_WINDOW = 32

/**
 * The editor over a remote world, working like normal, just that the server does all
 */
class RemoteBackend(hello: Hello) : EditorBackend {

    override val dimensions: List<WorldDimension> = hello.dimensions

    override val facts: LevelFacts = hello.facts

    /** No save on this machine, things are queued */
    override val localAccess: LevelStorageSource.LevelStorageAccess? = null

    override val canWrite: Boolean = hello.canWrite

    override val writesAreQueued = true

    val worldName: String = hello.worldName

    override suspend fun regionList(dimension: WorldDimension): List<Pair<Int, Int>> =
        Bodies.readRegionList(ClientNet.request(C2S.REGION_LIST, Bodies.dimensionRequest(dimension)))

    override suspend fun index(dimension: WorldDimension, rx: Int, rz: Int): RegionIndex? =
        Bodies.readIndex(ClientNet.request(C2S.INDEX, Bodies.indexRequest(dimension, rx, rz)))

    override suspend fun heightBounds(dimension: WorldDimension, pos: ChunkPos): IntRange? =
        Bodies.readRange(ClientNet.request(C2S.HEIGHT_BOUNDS, Bodies.heightRequest(dimension, pos)))

    override suspend fun render(dimension: WorldDimension, rx: Int, rz: Int, step: Int, maxY: Int?): RegionPixels? =
        Bodies.readPixels(ClientNet.request(C2S.RENDER, Bodies.renderRequest(dimension, rx, rz, step, maxY)))

    override suspend fun scan(
        dimension: WorldDimension,
        regions: Collection<RegionIndex>,
        source: ScanSource,
        argument: String?,
        minY: Int,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = Bodies.readScan(
        ClientNet.request(C2S.SCAN, Bodies.scanRequest(dimension, source, argument, minY, regions), onProgress),
    )

    override suspend fun players(): List<PlayerMarker> =
        Bodies.readPlayers(ClientNet.request(C2S.PLAYERS, ByteArray(0)))

    override suspend fun entities(dimension: WorldDimension, rx: Int, rz: Int): List<EntityMarker> =
        Bodies.readEntities(ClientNet.request(C2S.ENTITIES, Bodies.indexRequest(dimension, rx, rz)))

    /** The server synced its registries on join, datapack biomes included */
    override suspend fun biomes(): Registry<Biome>? =
        Minecraft.getInstance().connection?.registryAccess()?.lookup(Registries.BIOME)?.orElse(null)

    override val library: ClipLibrary = RemoteLibrary

    override suspend fun exportClip(
        name: String,
        dimension: WorldDimension,
        chunks: Collection<ChunkPos>,
        onProgress: (Int, Int) -> Unit,
    ): ClipExportResult = LibraryBodies.readExport(
        ClientNet.request(C2S.CLIP_EXPORT, LibraryBodies.exportRequest(name, dimension, chunks), onProgress),
    )

    override suspend fun exportSelection(name: String, chunks: Collection<ChunkPos>, inverted: Boolean): Boolean =
        LibraryBodies.readBoolean(
            ClientNet.request(C2S.SELECTION_EXPORT, LibraryBodies.selectionExportRequest(name, chunks, inverted)),
        )

    override suspend fun conflicts(dimension: WorldDimension, clip: ClipFootprint, origin: ChunkPos): Int =
        Bodies.readInt(
            ClientNet.request(
                C2S.CONFLICTS, Bodies.conflictsRequest(dimension, clip.chunks, clip.origin, origin),
            ),
        )

    override suspend fun delete(dimension: WorldDimension, chunks: Collection<ChunkPos>, backup: Boolean): Int =
        Bodies.readInt(ClientNet.request(C2S.JOB_DELETE, Bodies.deleteRequest(dimension, chunks, backup)))

    /** Just transfer the name, not clip */
    override suspend fun paste(
        dimension: WorldDimension,
        clip: String,
        origin: ChunkPos,
        options: ClipImportOptions,
        backup: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult {
        val queued = Bodies.readInt(
            ClientNet.request(
                C2S.JOB_PASTE,
                LibraryBodies.pasteRequest(
                    dimension, clip, origin, options.yOffset, options.ranges, options.existing, backup,
                ),
            ),
        )
        return ImportResult.Success(queued, 0, 0)
    }

    suspend fun jobs(): JobQueue = Bodies.readJobQueue(ClientNet.request(C2S.JOB_LIST, ByteArray(0)))

    suspend fun cancelJob(id: String): JobQueue =
        Bodies.readJobQueue(ClientNet.request(C2S.JOB_CANCEL, Bodies.write { it.writeUtf(id) }))

    suspend fun setJobsBackup(backup: Boolean): JobQueue =
        Bodies.readJobQueue(ClientNet.request(C2S.JOB_BACKUP, Bodies.write { it.writeBoolean(backup) }))

    suspend fun forceSave() {
        ClientNet.request(C2S.FORCE_SAVE, ByteArray(0))
    }

    /**
     * Import clips from client to server, transferred via many many packets, file by file
     */
    suspend fun upload(clip: ClipInfo, onProgress: (Int, Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val wanted = clip.name
        val files = ChunkClips.files(clip.dir)
        if (files.isEmpty()) return@withContext null
        val window = Semaphore(UPLOAD_WINDOW)
        try {
            var done = 0
            files.forEach { (name, path) ->
                val bytes = Files.readAllBytes(path)
                val id = Bodies.readInt(
                    ClientNet.request(C2S.CLIP_UPLOAD_OPEN, Bodies.uploadOpen(wanted, name, bytes.size.toLong())),
                )
                val count = maxOf(1, (bytes.size + UPLOAD_PIECE - 1) / UPLOAD_PIECE)
                coroutineScope {
                    for (index in 0 until count) {
                        window.acquire()
                        launch {
                            try {
                                val from = index * UPLOAD_PIECE
                                val part = bytes.copyOfRange(from, minOf(from + UPLOAD_PIECE, bytes.size))
                                ClientNet.request(C2S.CLIP_UPLOAD, Bodies.uploadFrame(id, index, part))
                            } finally {
                                window.release()
                            }
                        }
                    }
                }
                onProgress(++done, files.size)
            }
            LibraryBodies.readName(ClientNet.request(C2S.CLIP_UPLOAD_END, LibraryBodies.name(wanted)))
        } catch (e: Exception) {
            Constants.LOG.warn("Could not upload clip {}", clip.name, e)
            null
        }
    }

    /**
     * @return the name the local library gave it (different on collision)
     */
    suspend fun download(name: String, onProgress: (Int, Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val files = LibraryBodies.readFiles(ClientNet.request(C2S.CLIP_FILES, LibraryBodies.name(name)))
        // The server names these, so they only ever are what a clip is made of and never grow past the budget
        if (files.isEmpty() || files.any { !CLIP_FILE.matches(it.first) || it.second !in 0..MAX_CLIP_FILE }) {
            Constants.LOG.warn("Server offered clip {} as {} unusable file(s)", name, files.size)
            return@withContext null
        }
        val dir = ChunkClips.freeDir(name)
        val started = System.currentTimeMillis()
        try {
            files.forEachIndexed { index, (file, _) ->
                val target = ChunkClips.file(dir, file) ?: throw IllegalArgumentException(file)
                target.parent.createDirectories()
                Files.write(
                    target,
                    LibraryBodies.readFile(ClientNet.request(C2S.CLIP_DOWNLOAD, LibraryBodies.fileRequest(name, file))),
                )
                onProgress(index + 1, files.size)
            }
            check(ChunkClips.read(dir) != null) { "no region data" }
            val landed = dir.fileName.toString()
            Constants.LOG.info(
                "clip {} downloaded from {} as {} ({} file(s)) in {} ms",
                name, worldName, landed, files.size, System.currentTimeMillis() - started,
            )
            landed
        } catch (e: Exception) {
            Constants.LOG.warn("Could not download clip {}", name, e)
            // Remove fragments on error
            runCatching { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            null
        }
    }
}
