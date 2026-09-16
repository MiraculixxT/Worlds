package de.miraculixx.chunkeditor.client.net

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.CHUNK_SUBS
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ClipInfo
import de.miraculixx.chunkeditor.data.EditorBackend
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
import de.miraculixx.chunkeditor.net.UPLOAD_PIECE
import de.miraculixx.chunkeditor.net.Hello
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

    override suspend fun exportSelection(name: String, chunks: Collection<ChunkPos>): Boolean =
        LibraryBodies.readBoolean(
            ClientNet.request(C2S.SELECTION_EXPORT, LibraryBodies.selectionExportRequest(name, chunks)),
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

    suspend fun forceSave() {
        ClientNet.request(C2S.FORCE_SAVE, ByteArray(0))
    }

    /**
     * Import clips from client to server, transferred via many many packets, file by file
     */
    suspend fun upload(clip: ClipInfo, onProgress: (Int, Int) -> Unit): String? = withContext(Dispatchers.IO) {
        val wanted = clip.name
        val files = buildList {
            val manifest = clip.dir.resolve("clip.json")
            if (Files.isRegularFile(manifest)) add("clip.json" to manifest)
            CHUNK_SUBS.forEach { sub ->
                val dir = clip.dir.resolve(sub)
                if (!Files.isDirectory(dir)) return@forEach
                Files.newDirectoryStream(dir, "*.mca").use { stream ->
                    stream.forEach { add("$sub/${it.fileName}" to it) }
                }
            }
        }
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
}
