package de.miraculixx.chunkeditor.data

import net.minecraft.locale.Language
import net.minecraft.util.Mth
import kotlin.math.roundToInt

private const val TICKS_PER_SECOND = 20L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = SECONDS_PER_MINUTE * 60
private const val SECONDS_PER_DAY = SECONDS_PER_HOUR * 24

/** Heatmap stops, cold to hot */
private val GRADIENT = intArrayOf(0x3050FF, 0x30C8E0, 0x40C840, 0xFFD040, 0xFF4040)
private const val OVERLAY_ALPHA = 0xB0

/** Knuth's multiplicative constant for fixed, unique hashes */
private const val HASH_MIX = -0x61c88647

/** What the raw number is counted against */
enum class ValueReference {
    NONE,

    /** Game ticks before `level.dat`'s own `Time` */
    WORLD_TIME,

    /** Epoch seconds before now */
    WALL_CLOCK,
}

enum class ValueFormat { TICKS, SECONDS, NUMBER, BLOCKS }

/**
 * A per-chunk value drawn as a heatmap over the terrain
 * @param categorical the value is an index into [ChunkScan.labels]
 */
enum class ChunkOverlay(
    val labelKey: String,
    val metric: ChunkMetric,
    val format: ValueFormat,
    val reference: ValueReference = ValueReference.NONE,
    val categorical: Boolean = false,
) {
    INHABITED_TIME("chunkeditor.overlay.inhabited", ChunkMetric.INHABITED_TIME, ValueFormat.TICKS),
    LAST_SAVE("chunkeditor.overlay.last_save", ChunkMetric.LAST_UPDATE, ValueFormat.TICKS, ValueReference.WORLD_TIME),
    LAST_MODIFIED("chunkeditor.overlay.last_modified", ChunkMetric.TIMESTAMP, ValueFormat.SECONDS, ValueReference.WALL_CLOCK,),
    DATA_VERSION("chunkeditor.overlay.data_version", ChunkMetric.DATA_VERSION, ValueFormat.NUMBER),
    ENTITIES("chunkeditor.overlay.entities", ChunkMetric.ENTITY_COUNT, ValueFormat.NUMBER),
    BLOCK_ENTITIES("chunkeditor.overlay.block_entities", ChunkMetric.BLOCK_ENTITIES, ValueFormat.NUMBER),
    AVG_HEIGHT("chunkeditor.overlay.avg_height", ChunkMetric.AVG_HEIGHT, ValueFormat.BLOCKS),
    BIOME("chunkeditor.overlay.biome", ChunkMetric.BIOME, ValueFormat.NUMBER, categorical = true),
    BLOCK_COUNT("chunkeditor.overlay.block_count", ChunkMetric.BLOCK_COUNT, ValueFormat.NUMBER),
    /** Whatever number a user-given NBT path resolves to */
    PATH("chunkeditor.overlay.path", ChunkMetric.PATH, ValueFormat.NUMBER);

    val label: String get() = Language.getInstance().getOrDefault(labelKey, labelKey)

    val needsPath: Boolean get() = this == PATH
    val needsBlock: Boolean get() = this == BLOCK_COUNT

    fun value(raw: Long, worldTime: Long, nowSeconds: Long): Long = when (reference) {
        ValueReference.NONE -> raw
        ValueReference.WORLD_TIME -> (worldTime - raw).coerceAtLeast(0L)
        ValueReference.WALL_CLOCK -> (nowSeconds - raw).coerceAtLeast(0L)
    }

    fun format(value: Long): String = when (format) {
        ValueFormat.TICKS -> duration(value / TICKS_PER_SECOND)
        ValueFormat.SECONDS -> duration(value)
        ValueFormat.BLOCKS -> "${value}y"
        ValueFormat.NUMBER -> value.toString()
    }

    private fun duration(seconds: Long): String = when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
        seconds < SECONDS_PER_DAY -> "%.1fh".format(seconds.toDouble() / SECONDS_PER_HOUR)
        else -> "%.1fd".format(seconds.toDouble() / SECONDS_PER_DAY)
    }
}

/**
 * What the overlay screen edits. The range is optional: blank means the gradient spans whatever the
 * dimension holds.
 */
data class OverlaySettings(
    val overlay: ChunkOverlay? = null,
    val path: String = "",
    val block: String = "",
    val min: Long? = null,
    val max: Long? = null,
) {
    /** What we search for */
    val argument: String?
        get() = when {
            overlay?.needsPath == true -> path.takeIf { it.isNotBlank() }
            overlay?.needsBlock == true -> block.takeIf { it.isNotBlank() }
            else -> null
        }

    /**
     * If valid to scan (empty NBT is invalid)
     */
    val scannable: Boolean
        get() = overlay != null && (!overlay.needsPath || path.isNotBlank())
}

object OverlayColors {

    /** [t] 0..1 along the gradient, as the ARGB of one heatmap pixel */
    fun color(t: Double, alpha: Int = OVERLAY_ALPHA): Int {
        val position = t.coerceIn(0.0, 1.0) * (GRADIENT.size - 1)
        val index = position.toInt().coerceAtMost(GRADIENT.size - 2)
        val fraction = position - index
        val from = GRADIENT[index]
        val to = GRADIENT[index + 1]
        return (alpha shl 24) or
            (mix(from shr 16 and 0xFF, to shr 16 and 0xFF, fraction) shl 16) or
            (mix(from shr 8 and 0xFF, to shr 8 and 0xFF, fraction) shl 8) or
            mix(from and 0xFF, to and 0xFF, fraction)
    }

    private fun mix(from: Int, to: Int, fraction: Double) = (from + (to - from) * fraction).roundToInt()

    /**
     * One ARGB per name (stable hased by name)
     */
    fun categorical(name: String, alpha: Int = OVERLAY_ALPHA): Int {
        val hash = name.hashCode() * HASH_MIX
        val hue = (hash ushr 8 and 0xFFFF) / 65536f
        val saturation = 0.5f + (hash ushr 2 and 0x3) * 0.12f
        val value = 0.72f + (hash and 0x3) * 0.08f
        return (alpha shl 24) or (Mth.hsvToArgb(hue, saturation, value, 0) and 0xFFFFFF)
    }

    fun categoryName(id: String): String = Language.getInstance().getOrDefault("biome." + id.replace(':', '.'), id)
}
