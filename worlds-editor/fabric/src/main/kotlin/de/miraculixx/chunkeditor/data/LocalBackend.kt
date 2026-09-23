package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.Registry
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelStorageSource
import java.util.UUID

/**
 * Used by client local worlds & server
 * @param selfId pre-1.16 `level.dat` host fallback, see [PlayerMarkers.read]
 * @param biomes where the tint registry comes from
 */
class LocalBackend(
    private val access: LevelStorageSource.LevelStorageAccess,
    private val selfId: UUID? = null,
    private val biomes: suspend () -> Registry<Biome>? = { null },
) : EditorBackend {

    override val dimensions: List<WorldDimension> = ChunkRegions.dimensions(access)

    override val facts: LevelFacts = LevelFacts.read(access)

    override val localAccess: LevelStorageSource.LevelStorageAccess get() = access

    override val canWrite = true

    override val writesAreQueued = false

    override suspend fun regionList(dimension: WorldDimension) = ChunkRegions.listRegions(dimension)

    override suspend fun index(dimension: WorldDimension, rx: Int, rz: Int) =
        ChunkRegions.readIndex(dimension, rx, rz)

    override suspend fun heightBounds(dimension: WorldDimension, pos: ChunkPos) =
        ChunkRegions.heightBounds(dimension, pos)

    override suspend fun chunkInfo(dimension: WorldDimension, pos: ChunkPos) = ChunkFacts.read(dimension, pos)

    override suspend fun render(dimension: WorldDimension, rx: Int, rz: Int, step: Int, maxY: Int?) =
        ChunkMapRenderer.renderRegion(dimension, rx, rz, step, maxY)

    override suspend fun scan(
        dimension: WorldDimension,
        regions: Collection<RegionIndex>,
        source: ScanSource,
        argument: String?,
        minY: Int,
        onProgress: (Int, Int) -> Unit,
    ) = ChunkScans.scan(dimension, access, regions, source, argument, minY, onProgress)

    override suspend fun players() = PlayerMarkers.read(access, selfId)

    override suspend fun entities(dimension: WorldDimension, rx: Int, rz: Int) =
        EntityMarkers.read(dimension, rx, rz)

    override suspend fun biomes(): Registry<Biome>? = biomes.invoke()

    override val library: ClipLibrary = LocalLibrary

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
        ChunkRegions.deleteChunks(dimension, chunks)

    override suspend fun paste(
        dimension: WorldDimension,
        clip: String,
        origin: ChunkPos,
        options: ClipImportOptions,
        backup: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        val found = LocalLibrary.read(clip) ?: return@withContext ImportResult.Failure("chunkeditor.clip.error.read")
        ChunkClipImport.import(found, dimension, origin, options, onProgress)
    }
}
