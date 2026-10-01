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
import de.miraculixx.chunkeditor.data.RenderStore
import de.miraculixx.chunkeditor.data.ScanSource
import de.miraculixx.chunkeditor.data.WorldDimension
import net.minecraft.core.Registry
import net.minecraft.world.level.chunk.storage.IOWorker
import de.miraculixx.chunkeditor.mixin.EntitySectionManagerAccess
import de.miraculixx.chunkeditor.mixin.EntityStorageAccess
import de.miraculixx.chunkeditor.mixin.SectionStorageAccess
import de.miraculixx.chunkeditor.mixin.ServerLevelAccess
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val WRITE_WAIT_MS = 30_000.milliseconds

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

    /** Renders outlive the server, so the next open (by anyone) skips them */
    val renders = RenderStore.world(root.fileName?.toString() ?: "world")

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

    override suspend fun indexes(dimension: WorldDimension, onProgress: (Int, Int) -> Unit) =
        ChunkRegions.readIndexes(dimension, onProgress)

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
                dimension = dimension.key.location().toString(),
                chunks = chunks.map { it.toLong() }, backup = backup, by = by, at = System.currentTimeMillis(),
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
                dimension = dimension.key.location().toString(),
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

    /** Saves and waits until the save is on disk */
    suspend fun forceSave(by: String) {
        val started = System.nanoTime()
        val saved = withTimeoutOrNull(WRITE_WAIT_MS) { server.submit { server.saveEverything(true, false, false) }.await(); true }
        if (saved == null) Constants.LOG.warn("world save forced by {} did not run within {}", by, WRITE_WAIT_MS)
        else Constants.LOG.info("world save forced by {} (editor refresh) in {} ms", by, Constants.ms(started))
        awaitWrites()
    }

    /** Waits for every chunk, entity and POI write the game has queued to reach the region files */
    suspend fun awaitWrites() {
        val started = System.nanoTime()
        val pending = server.allLevels.flatMap { level ->
            val entities = ((level as ServerLevelAccess).chunkeditorEntityManager() as EntitySectionManagerAccess)
                .chunkeditorPermanentStorage() as? EntityStorageAccess
            // 1.21's ChunkMap only reaches its IOWorker through chunkScanner()
            listOfNotNull(
                (level.chunkSource.chunkMap.chunkScanner() as IOWorker).synchronize(false),
                (level.chunkSource.poiManager as SectionStorageAccess).chunkeditorRegionStorage().synchronize(false),
                entities?.chunkeditorRegionStorage()?.synchronize(false),
            )
        }
        val done = withTimeoutOrNull(WRITE_WAIT_MS) { CompletableFuture.allOf(*pending.toTypedArray()).await(); true }
        if (done == null) Constants.LOG.warn("pending world writes not done within {}, reading anyway", WRITE_WAIT_MS)
        else Constants.LOG.info("pending world writes done in {} ms", Constants.ms(started))
    }
}
