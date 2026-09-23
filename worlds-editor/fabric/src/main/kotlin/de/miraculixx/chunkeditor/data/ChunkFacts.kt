package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.visitors.CollectFields
import net.minecraft.nbt.visitors.FieldSelector
import net.minecraft.world.level.ChunkPos

/**
 * What a chunks hold (atm hardcoded, planned for custom)
 */
class ChunkInfo(
    val pos: ChunkPos,
    val inhabitedTicks: Long?,
    val lastWritten: Long?,
    val entities: Int?,
)

/**
 * The single-chunk counterpart of [ChunkScans]
 */
object ChunkFacts {

    suspend fun read(dimension: WorldDimension, pos: ChunkPos): ChunkInfo = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val info = ChunkInfo(pos, inhabited(dimension, pos), written(dimension, pos), entities(dimension, pos))
        Constants.LOG.info(
            "chunk info {} {},{} in {} ms", dimension.dir.fileName, pos.x, pos.z, Constants.ms(started),
        )
        info
    }

    /** Streamed, so the chunk is never decompressed */
    private fun inhabited(dimension: WorldDimension, pos: ChunkPos): Long? = try {
        ChunkRegions.storage(dimension, SUB_REGION)?.use { store ->
            val collector = CollectFields(FieldSelector(LongTag.TYPE, "InhabitedTime"))
            store.scanChunk(pos, collector)
            (collector.result as? CompoundTag)?.getLong("InhabitedTime")?.orElse(null)
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read chunk {}: {}", pos, e.message)
        null
    }

    private fun written(dimension: WorldDimension, pos: ChunkPos): Long? =
        ChunkRegions.readTimestamps(dimension.regionDir, pos.regionX, pos.regionZ)
            ?.get(pos.regionLocalZ * REGION_SIZE + pos.regionLocalX)
            ?.takeIf { it != 0 }
            ?.toLong()

    private fun entities(dimension: WorldDimension, pos: ChunkPos): Int? = try {
        ChunkRegions.storage(dimension, SUB_ENTITIES)?.use { store ->
            store.read(pos)?.getListOrEmpty("Entities")?.size
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read entities of chunk {}: {}", pos, e.message)
        null
    }
}
