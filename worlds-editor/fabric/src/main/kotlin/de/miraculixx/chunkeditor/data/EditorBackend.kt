package de.miraculixx.chunkeditor.data

import net.minecraft.core.Registry
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * Everything the chunk map needs from a save, communication layer between client & disk/server
 */
interface EditorBackend {

    /** Resolved up front, so the screen opens with no loading state. */
    val dimensions: List<WorldDimension>

    val facts: LevelFacts

    /** Direct access (local) for instant backups, ... */
    val localAccess: LevelStorageSource.LevelStorageAccess?

    /** If allowed to modify anything */
    val canWrite: Boolean

    /** Only for servers, where actions not instant */
    val writesAreQueued: Boolean

    suspend fun regionList(dimension: WorldDimension): List<Pair<Int, Int>>

    suspend fun index(dimension: WorldDimension, rx: Int, rz: Int): RegionIndex?

    suspend fun heightBounds(dimension: WorldDimension, pos: ChunkPos): IntRange?

    suspend fun render(dimension: WorldDimension, rx: Int, rz: Int, step: Int, maxY: Int?): RegionPixels?

    suspend fun scan(
        dimension: WorldDimension,
        regions: Collection<RegionIndex>,
        source: ScanSource,
        argument: String?,
        minY: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ScanResult

    suspend fun players(): List<PlayerMarker>

    suspend fun biomes(): Registry<Biome>?

    val library: ClipLibrary

    suspend fun exportClip(
        name: String,
        dimension: WorldDimension,
        chunks: Collection<ChunkPos>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ClipExportResult

    suspend fun exportSelection(name: String, chunks: Collection<ChunkPos>): Boolean

    suspend fun conflicts(dimension: WorldDimension, clip: ClipFootprint, origin: ChunkPos): Int

    /** @param backup only relevant when [writesAreQueued] */
    suspend fun delete(dimension: WorldDimension, chunks: Collection<ChunkPos>, backup: Boolean): Int

    /** @param clip a name in [library] not a path (backend resolves path) */
    suspend fun paste(
        dimension: WorldDimension,
        clip: String,
        origin: ChunkPos,
        options: ClipImportOptions,
        backup: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportResult
}
