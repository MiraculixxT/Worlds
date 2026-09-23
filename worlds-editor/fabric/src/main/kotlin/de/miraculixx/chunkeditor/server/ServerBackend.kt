package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ChunkClipExport
import de.miraculixx.chunkeditor.data.ChunkFact
import de.miraculixx.chunkeditor.data.ChunkInfo
import de.miraculixx.chunkeditor.data.ChunkClipImport
import de.miraculixx.chunkeditor.data.ClipExportResult
import de.miraculixx.chunkeditor.data.ClipFootprint
import de.miraculixx.chunkeditor.data.ClipLibrary
import de.miraculixx.chunkeditor.data.ExportResult
import de.miraculixx.chunkeditor.data.LocalLibrary
import de.miraculixx.chunkeditor.data.SelectionCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.miraculixx.chunkeditor.data.ChunkFacts
import de.miraculixx.chunkeditor.data.ChunkMapRenderer
import de.miraculixx.chunkeditor.data.ChunkRegions
import de.miraculixx.chunkeditor.data.ChunkScans
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.EditorBackend
import de.miraculixx.chunkeditor.data.EntityMarkers
import de.miraculixx.chunkeditor.data.ImportResult
import de.miraculixx.chunkeditor.data.LevelFacts
import de.miraculixx.chunkeditor.data.PlayerMarkers
import de.miraculixx.chunkeditor.data.RegionIndex
import de.miraculixx.chunkeditor.data.ScanSource
import de.miraculixx.chunkeditor.data.WorldDimension
import net.minecraft.core.Registry
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Path

/**
 * The same `data/` reads a client makes but against a live server world.
 * Every write action can not be performed live, so they are queued for shutdown via [Job]
 */
class ServerBackend(private val server: MinecraftServer, private val by: String) : EditorBackend {

    // LevelResource.ROOT is ".", so the raw path arrives as `…/world/.`
    val root: Path = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()

    /** Rescanned on every editor open */
    @Volatile
    override var dimensions: List<WorldDimension> = ChunkRegions.dimensions(root)
        private set

    @Volatile
    override var facts: LevelFacts = LevelFacts.read(root)
        private set

    fun refresh() {
        dimensions = ChunkRegions.dimensions(root)
        facts = LevelFacts.read(root)
    }

    override val localAccess: LevelStorageSource.LevelStorageAccess? = null

    override val canWrite = true

    override val writesAreQueued = true

    override suspend fun regionList(dimension: WorldDimension) = ChunkRegions.listRegions(dimension)

    override suspend fun index(dimension: WorldDimension, rx: Int, rz: Int) =
        ChunkRegions.readIndex(dimension, rx, rz)

    override suspend fun heightBounds(dimension: WorldDimension, pos: ChunkPos) =
        ChunkRegions.heightBounds(dimension, pos)

    override suspend fun chunkInfo(dimension: WorldDimension, pos: ChunkPos, facts: Set<ChunkFact>, minY: Int): ChunkInfo =
        ChunkFacts.read(dimension, pos, facts, minY)

    override suspend fun render(dimension: WorldDimension, rx: Int, rz: Int, step: Int, maxY: Int?) =
        ChunkMapRenderer.renderRegion(dimension, rx, rz, step, maxY)

    override suspend fun scan(
        dimension: WorldDimension,
        regions: Collection<RegionIndex>,
        source: ScanSource,
        argument: String?,
        minY: Int,
        onProgress: (Int, Int) -> Unit,
    ) = ChunkScans.scan(dimension, null, regions, source, argument, minY, onProgress)

    override suspend fun players() = PlayerMarkers.read(root)

    override suspend fun entities(dimension: WorldDimension, rx: Int, rz: Int) =
        EntityMarkers.read(dimension, rx, rz)

    /** The client holds the registries the server synced on join */
    override suspend fun biomes(): Registry<Biome>? = null

    /** The servers own shelf, next to the world */
    override val library: ClipLibrary = LocalLibrary

    /** Export can run live, it only reads (even so it may read outdate data) */
    override suspend fun exportClip(
        name: String,
        dimension: WorldDimension,
        chunks: Collection<ChunkPos>,
        onProgress: (Int, Int) -> Unit,
    ): ClipExportResult = withContext(Dispatchers.IO) {
        when (val result = ChunkClipExport.export(dimension, chunks, name, onProgress)) {
            is ExportResult.Success -> ClipExportResult.Success(result.dir.fileName.toString(), result.chunks)
            is ExportResult.Failure -> ClipExportResult.Failure(result.message)
        }
    }

    override suspend fun exportSelection(name: String, chunks: Collection<ChunkPos>, inverted: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { SelectionCsv.write(name, chunks, inverted) }
                .onFailure { Constants.LOG.error("Selection export failed", it) }
                .isSuccess
        }

    override suspend fun conflicts(dimension: WorldDimension, clip: ClipFootprint, origin: ChunkPos) =
        ChunkClipImport.conflicts(clip.chunks, clip.origin, dimension, origin)

    override suspend fun delete(dimension: WorldDimension, chunks: Collection<ChunkPos>, backup: Boolean) =
        queueDelete(dimension, chunks, backup, by)

    fun queueDelete(dimension: WorldDimension, chunks: Collection<ChunkPos>, backup: Boolean, by: String): Int {
        ServerJobs.submit(
            Job(
                id = ServerJobs.newId(), kind = JobKind.DELETE,
                dimension = dimension.key.identifier().toString(),
                chunks = chunks.map { it.pack() }, backup = backup, by = by, at = System.currentTimeMillis(),
            ),
        )
        return chunks.size
    }

    override suspend fun paste(
        dimension: WorldDimension,
        clip: String,
        origin: ChunkPos,
        options: ClipImportOptions,
        backup: Boolean,
        onProgress: (Int, Int) -> Unit,
    ) = queuePaste(dimension, clip, origin, options, backup, by)

    fun queuePaste(
        dimension: WorldDimension,
        clip: String,
        origin: ChunkPos,
        options: ClipImportOptions,
        backup: Boolean,
        by: String,
    ): ImportResult {
        val found = LocalLibrary.read(clip) ?: return ImportResult.Failure("chunkeditor.clip.error.read")
        ServerJobs.submit(
            Job(
                id = ServerJobs.newId(),
                kind = JobKind.PASTE,
                dimension = dimension.key.identifier().toString(),
                clip = clip,
                originX = origin.x, originZ = origin.z,
                yOffset = options.yOffset,
                ranges = options.ranges.map { "${it.first}:${it.last}" },
                existing = options.existing,
                backup = backup, by = by, at = System.currentTimeMillis(),
            ),
        )
        return ImportResult.Success(found.chunks.size, 0, 0)
    }

    fun forceSave(by: String) {
        server.executeIfPossible {
            val started = System.nanoTime()
            server.saveEverything(true, false, false)
            Constants.LOG.info("world save forced by {} (editor refresh) in {} ms", by, Constants.ms(started))
        }
    }
}
