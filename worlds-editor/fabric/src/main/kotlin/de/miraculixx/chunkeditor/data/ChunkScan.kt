package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.nbt.CollectionTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.Tag
import net.minecraft.nbt.visitors.CollectFields
import net.minecraft.nbt.visitors.FieldSelector
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import net.minecraft.world.level.storage.LevelStorageSource
import java.util.EnumMap
import java.util.EnumSet

private const val COLUMNS = 16 * 16
private const val BLOCKS_PER_SECTION = 16 * 16 * 16

/**
 * Every metric of a source is filled by that pass, so a second request on the same source is free
 */
enum class ScanSource(val labelKey: String) {
    /** Just file headers */
    HEADER("chunkeditor.overlay.cost.quick"),

    /** Streaming field selectors over `region/` */
    FIELDS("chunkeditor.overlay.cost.quick"),

    /** The whole chunk tag out of `region/` */
    CHUNK("chunkeditor.overlay.cost.chunk"),

    /** The whole chunk tag out of `entities/` */
    ENTITIES("chunkeditor.overlay.cost.entities"),

    /** Every section's block palette, and its packed indices wherever the palette matches */
    BLOCKS("chunkeditor.overlay.cost.chunk"),
}

/**
 * One number per chunk, and the pass that knows how to read it
 * @param needsArgument cache key, when different reparse
 */
enum class ChunkMetric(val source: ScanSource, val needsArgument: Boolean = false) {
    INHABITED_TIME(ScanSource.FIELDS),
    LAST_UPDATE(ScanSource.FIELDS),
    DATA_VERSION(ScanSource.FIELDS),
    TIMESTAMP(ScanSource.HEADER),
    BLOCK_ENTITIES(ScanSource.CHUNK),
    AVG_HEIGHT(ScanSource.CHUNK),
    PATH(ScanSource.CHUNK, needsArgument = true),
    ENTITY_COUNT(ScanSource.ENTITIES),
    BLOCK_COUNT(ScanSource.BLOCKS, needsArgument = true),
}

/** What one pass produced */
class ScanResult(
    val source: ScanSource,
    val argument: String?,
    val values: Map<ChunkMetric, Long2LongOpenHashMap>,
)

/**
 * Saves dimension data, only on request & saved until dim switch
 */
class ChunkScan {
    private val values = EnumMap<ChunkMetric, Long2LongOpenHashMap>(ChunkMetric::class.java)
    private val done = EnumSet.noneOf(ScanSource::class.java)

    /** Cache key (nbt & block) */
    private val arguments = EnumMap<ScanSource, String?>(ScanSource::class.java)

    fun has(source: ScanSource) = source in done

    fun ready(metric: ChunkMetric, argument: String?): Boolean =
        has(metric.source) && (!metric.needsArgument || arguments[metric.source] == argument)

    fun value(metric: ChunkMetric, packed: Long): Long? {
        val map = values[metric] ?: return null
        return if (map.containsKey(packed)) map.get(packed) else null
    }

    fun apply(result: ScanResult) {
        result.values.forEach { (metric, map) -> values[metric] = map }
        done += result.source
        arguments[result.source] = result.argument
    }

    fun clear() {
        values.clear()
        done.clear()
        arguments.clear()
    }
}

object ChunkScans {

    /**
     * @param access only read by [ScanSource.BLOCKS], to resolve a block tag against the save's packs
     * @param minY the dimension's build floor
     * @param argument the key to look for
     */
    suspend fun scan(
        dimension: WorldDimension,
        access: LevelStorageSource.LevelStorageAccess,
        regions: Collection<RegionIndex>,
        source: ScanSource,
        argument: String?,
        minY: Int,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val values = when (source) {
            ScanSource.HEADER -> scanHeader(dimension, regions, onProgress)
            ScanSource.FIELDS -> scanFields(dimension, regions, onProgress)
            ScanSource.CHUNK -> scanChunks(dimension, regions, argument, minY, onProgress)
            ScanSource.ENTITIES -> scanEntities(dimension, regions, onProgress)
            ScanSource.BLOCKS -> scanBlocks(dimension, access, regions, argument, onProgress)
        }
        ScanResult(source, argument, values)
    }

    private fun scanHeader(
        dimension: WorldDimension, regions: Collection<RegionIndex>, onProgress: (Int, Int) -> Unit,
    ): Map<ChunkMetric, Long2LongOpenHashMap> {
        val stamps = Long2LongOpenHashMap()
        regions.forEachIndexed { done, region ->
            val table = ChunkRegions.readTimestamps(dimension.regionDir, region.rx, region.rz)
            if (table != null) {
                ChunkRegions.forEachChunk(region) { pos ->
                    val stamp = table[pos.regionLocalZ * REGION_SIZE + pos.regionLocalX]
                    if (stamp != 0) stamps[pos.pack()] = stamp.toLong()
                }
            }
            onProgress(done + 1, regions.size)
        }
        return mapOf(ChunkMetric.TIMESTAMP to stamps)
    }

    private fun scanFields(
        dimension: WorldDimension, regions: Collection<RegionIndex>, onProgress: (Int, Int) -> Unit,
    ): Map<ChunkMetric, Long2LongOpenHashMap> {
        val inhabited = Long2LongOpenHashMap()
        val lastUpdate = Long2LongOpenHashMap()
        val dataVersion = Long2LongOpenHashMap()
        eachChunk(dimension, SUB_REGION, regions, onProgress) { store, pos ->
            // A streaming visitor, so chunks are not decompressed
            val collector = CollectFields(
                FieldSelector(LongTag.TYPE, "InhabitedTime"),
                FieldSelector(LongTag.TYPE, "LastUpdate"),
                FieldSelector(IntTag.TYPE, "DataVersion"),
            )
            store.scanChunk(pos, collector)
            val tag = collector.result as? CompoundTag ?: return@eachChunk
            val packed = pos.pack()
            tag.getLong("InhabitedTime").ifPresent { inhabited[packed] = it }
            tag.getLong("LastUpdate").ifPresent { lastUpdate[packed] = it }
            tag.getInt("DataVersion").ifPresent { dataVersion[packed] = it.toLong() }
        }
        return mapOf(
            ChunkMetric.INHABITED_TIME to inhabited,
            ChunkMetric.LAST_UPDATE to lastUpdate,
            ChunkMetric.DATA_VERSION to dataVersion,
        )
    }

    private fun scanChunks(
        dimension: WorldDimension, regions: Collection<RegionIndex>, path: String?, minY: Int,
        onProgress: (Int, Int) -> Unit,
    ): Map<ChunkMetric, Long2LongOpenHashMap> {
        val blockEntities = Long2LongOpenHashMap()
        val heights = Long2LongOpenHashMap()
        val paths = Long2LongOpenHashMap()
        val wanted = path?.takeIf { it.isNotBlank() }
        eachChunk(dimension, SUB_REGION, regions, onProgress) { store, pos ->
            val tag = store.read(pos) ?: return@eachChunk
            val packed = pos.pack()
            blockEntities[packed] = tag.getListOrEmpty("block_entities").size.toLong()
            avgHeight(tag, minY)?.let { heights[packed] = it }
            wanted?.let { NbtPaths.number(tag, it) }?.let { paths[packed] = it }
        }
        return mapOf(
            ChunkMetric.BLOCK_ENTITIES to blockEntities,
            ChunkMetric.AVG_HEIGHT to heights,
            ChunkMetric.PATH to paths,
        )
    }

    private fun scanEntities(
        dimension: WorldDimension, regions: Collection<RegionIndex>, onProgress: (Int, Int) -> Unit,
    ): Map<ChunkMetric, Long2LongOpenHashMap> {
        val counts = Long2LongOpenHashMap()
        eachChunk(dimension, SUB_ENTITIES, regions, onProgress) { store, pos ->
            val tag = store.read(pos) ?: return@eachChunk
            counts[pos.pack()] = tag.getListOrEmpty("Entities").size.toLong()
        }
        return mapOf(ChunkMetric.ENTITY_COUNT to counts)
    }

    private fun scanBlocks(
        dimension: WorldDimension, access: LevelStorageSource.LevelStorageAccess,
        regions: Collection<RegionIndex>, block: String?, onProgress: (Int, Int) -> Unit,
    ): Map<ChunkMetric, Long2LongOpenHashMap> {
        val counts = Long2LongOpenHashMap()
        val filter = BlockFilter.of(access, block)
        if (filter.isEmpty) {
            onProgress(regions.size, regions.size)
            return mapOf(ChunkMetric.BLOCK_COUNT to counts)
        }
        eachChunk(dimension, SUB_REGION, regions, onProgress) { store, pos ->
            val tag = store.read(pos) ?: return@eachChunk
            var total = 0L
            tag.getListOrEmpty("sections").forEach { entry ->
                val states = (entry as? CompoundTag)?.getCompound("block_states")?.orElse(null)
                if (states != null) total += countBlocks(states, filter)
            }
            // Also store & display 0 values
            counts[pos.pack()] = total
        }
        return mapOf(ChunkMetric.BLOCK_COUNT to counts)
    }

    /**
     * Pre-check section filter to skip them in counting
     */
    private fun countBlocks(states: CompoundTag, filter: BlockFilter): Int {
        val palette = states.getListOrEmpty("palette")
        if (palette.isEmpty) return 0
        val matching = BooleanArray(palette.size)
        var matches = 0
        palette.forEachIndexed { index, entry ->
            if (filter.matches((entry as? CompoundTag)?.getStringOr("Name", "") ?: "")) {
                matching[index] = true
                matches++
            }
        }
        if (matches == 0) return 0
        // A single-entry palette stores no indices at all
        val data = states.getLongArray("data").orElse(null)
        if (data == null || data.isEmpty()) return if (matching[0]) BLOCKS_PER_SECTION else 0
        val perLong = (BLOCKS_PER_SECTION + data.size - 1) / data.size
        val bits = 64 / perLong
        if (bits <= 0) return 0
        val mask = (1L shl bits) - 1
        var count = 0
        for (i in 0 until BLOCKS_PER_SECTION) {
            val index = ((data[i / perLong] ushr (i % perLong) * bits) and mask).toInt()
            if (index < matching.size && matching[index]) count++
        }
        return count
    }

    private inline fun eachChunk(
        dimension: WorldDimension, sub: String, regions: Collection<RegionIndex>,
        onProgress: (Int, Int) -> Unit, action: (RegionFileStorage, ChunkPos) -> Unit,
    ) {
        ChunkRegions.storage(dimension, sub)?.use { store ->
            regions.forEachIndexed { done, region ->
                ChunkRegions.forEachChunk(region) { pos ->
                    try {
                        action(store, pos)
                    } catch (e: Exception) {
                        Constants.LOG.warn("Failed to scan chunk {}: {}", pos, e.message)
                    }
                }
                onProgress(done + 1, regions.size)
            }
        } ?: onProgress(regions.size, regions.size)
    }

    /**
     * The mean surface height of a chunk using `Heightmaps.MOTION_BLOCKING`
     */
    private fun avgHeight(tag: CompoundTag, minY: Int): Long? {
        val packed = tag.getCompound("Heightmaps").orElse(null)
            ?.getLongArray("MOTION_BLOCKING")?.orElse(null) ?: return null
        if (packed.isEmpty()) return null
        val perLong = (COLUMNS + packed.size - 1) / packed.size
        val bits = 64 / perLong
        if (bits <= 0) return null
        val mask = (1L shl bits) - 1
        var sum = 0L
        var counted = 0
        for (i in 0 until COLUMNS) {
            val word = packed[i / perLong]
            val value = (word ushr (i % perLong) * bits) and mask
            if (value == 0L) continue
            sum += minY + value - 1
            counted++
        }
        return if (counted == 0) null else sum / counted
    }
}

/**
 * A dotted NBT path. List/Array -> size, Numeric -> value, everything else to nothing.
 * E.g: `Heightmaps.MOTION_BLOCKING`, `sections[0].Y`, `structures.starts`
 */
object NbtPaths {

    fun number(root: CompoundTag, path: String): Long? {
        var current: Tag = root
        path.split('.').forEach { raw ->
            val name = raw.substringBefore('[')
            if (name.isNotEmpty()) {
                current = (current as? CompoundTag)?.get(name) ?: return null
            }
            INDEX.findAll(raw).forEach { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return null
                val list = current as? CollectionTag ?: return null
                if (index !in 0 until list.size()) return null
                current = list.get(index)
            }
        }
        return when (val tag = current) {
            is NumericTag -> tag.longValue()
            is CollectionTag -> tag.size().toLong()
            else -> null
        }
    }

    private val INDEX = Regex("""\[(\d+)]""")
}
