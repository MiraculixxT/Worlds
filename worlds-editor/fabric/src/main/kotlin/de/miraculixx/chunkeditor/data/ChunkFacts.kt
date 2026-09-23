package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.visitors.CollectFields
import net.minecraft.nbt.visitors.FieldSelector
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import java.util.EnumMap

/**
 * One line of the clicked chunks info box, picked in the settings
 * @param overlay where label, format and the reading pass come from
 */
enum class ChunkFact(val overlay: ChunkOverlay, val title: String, val default: Boolean = false) {
    INHABITED_TIME(ChunkOverlay.INHABITED_TIME, "Inhabited Time", true),
    LAST_SAVE(ChunkOverlay.LAST_SAVE, "Last Save"),
    LAST_MODIFIED(ChunkOverlay.LAST_MODIFIED, "Last Modified", true),
    ENTITIES(ChunkOverlay.ENTITIES, "Entities", true),
    BLOCK_ENTITIES(ChunkOverlay.BLOCK_ENTITIES, "Block Entities"),
    AVG_HEIGHT(ChunkOverlay.AVG_HEIGHT, "Average Height"),
    BIOME(ChunkOverlay.BIOME, "Biome", true),
    DATA_VERSION(ChunkOverlay.DATA_VERSION, "Data Version");

    val metric: ChunkMetric get() = overlay.metric
    val label: String get() = overlay.label

    companion object {
        val DEFAULTS: Set<ChunkFact> = entries.filterTo(HashSet()) { it.default }

        fun of(names: Collection<String>): Set<ChunkFact> =
            entries.filterTo(LinkedHashSet()) { it.name in names }
    }
}

/** Only holds what we asked for */
class ChunkInfo(
    val pos: ChunkPos,
    val numbers: Map<ChunkFact, Long>,
    val biome: String?,
)

/**
 * The single-chunk counterpart of [ChunkScans]
 */
object ChunkFacts {

    suspend fun read(
        dimension: WorldDimension, pos: ChunkPos, facts: Set<ChunkFact>, minY: Int,
    ): ChunkInfo = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val numbers = EnumMap<ChunkFact, Long>(ChunkFact::class.java)
        if (ChunkFact.LAST_MODIFIED in facts) written(dimension, pos)?.let { numbers[ChunkFact.LAST_MODIFIED] = it }
        if (ChunkFact.ENTITIES in facts) entities(dimension, pos)?.let { numbers[ChunkFact.ENTITIES] = it }
        val biome = region(dimension, pos, facts, minY, numbers)
        Constants.LOG.info(
            "chunk info {} {},{} in {} ms", dimension.dir.fileName, pos.x, pos.z, Constants.ms(started),
        )
        ChunkInfo(pos, numbers, biome)
    }

    /**
     * One pass over `region/`, streamed while nothing needs the whole tag
     * @return biome or null
     */
    private fun region(
        dimension: WorldDimension, pos: ChunkPos, facts: Set<ChunkFact>, minY: Int,
        into: MutableMap<ChunkFact, Long>,
    ): String? = try {
        val whole = facts.any { it.metric.source == ScanSource.CHUNK }
        val streamed = facts.any { it.metric.source == ScanSource.FIELDS }
        if (!whole && !streamed) null else ChunkRegions.storage(dimension, SUB_REGION)?.use { store ->
            val tag = (if (whole) store.read(pos) else fields(store, pos, facts)) ?: return null
            if (ChunkFact.INHABITED_TIME in facts) tag.getLong("InhabitedTime").ifPresent { into[ChunkFact.INHABITED_TIME] = it }
            if (ChunkFact.LAST_SAVE in facts) tag.getLong("LastUpdate").ifPresent { into[ChunkFact.LAST_SAVE] = it }
            if (ChunkFact.DATA_VERSION in facts) tag.getInt("DataVersion").ifPresent { into[ChunkFact.DATA_VERSION] = it.toLong() }
            if (!whole) return@use null
            if (ChunkFact.BLOCK_ENTITIES in facts) into[ChunkFact.BLOCK_ENTITIES] = tag.getListOrEmpty("block_entities").size.toLong()
            if (ChunkFact.AVG_HEIGHT in facts) ChunkScans.avgHeight(tag, minY)?.let { into[ChunkFact.AVG_HEIGHT] = it }
            if (ChunkFact.BIOME in facts) ChunkScans.surfaceBiome(tag, minY) else null
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read chunk {}: {}", pos, e.message)
        null
    }

    /** Field selectors (avoid unpacking) */
    private fun fields(store: RegionFileStorage, pos: ChunkPos, facts: Set<ChunkFact>): CompoundTag? {
        val selectors = ArrayList<FieldSelector>(3)
        if (ChunkFact.INHABITED_TIME in facts) selectors += FieldSelector(LongTag.TYPE, "InhabitedTime")
        if (ChunkFact.LAST_SAVE in facts) selectors += FieldSelector(LongTag.TYPE, "LastUpdate")
        if (ChunkFact.DATA_VERSION in facts) selectors += FieldSelector(IntTag.TYPE, "DataVersion")
        val collector = CollectFields(*selectors.toTypedArray())
        store.scanChunk(pos, collector)
        return collector.result as? CompoundTag
    }

    private fun written(dimension: WorldDimension, pos: ChunkPos): Long? =
        ChunkRegions.readTimestamps(dimension.regionDir, pos.regionX, pos.regionZ)
            ?.get(pos.regionLocalZ * REGION_SIZE + pos.regionLocalX)
            ?.takeIf { it != 0 }
            ?.toLong()

    private fun entities(dimension: WorldDimension, pos: ChunkPos): Long? = try {
        ChunkRegions.storage(dimension, SUB_ENTITIES)?.use { store ->
            store.read(pos)?.getListOrEmpty("Entities")?.size?.toLong()
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read entities of chunk {}: {}", pos, e.message)
        null
    }
}
