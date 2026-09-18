package de.miraculixx.chunkeditor.data

import net.minecraft.resources.Identifier

data class TintKey(val block: Identifier, val biome: String, val mapColor: Int)

/** One region rendered to map colors, with no world, no registry and no GL */
class RegionPixels(
    val size: Int,
    val argb: IntArray,
    val unreadable: Set<Long>,
    val tintPalette: List<TintKey>,
    val tintIndex: ShortArray?,
) {
    /** What holding this costs, for a caller that keeps renders alive against a budget */
    val bytes: Long get() = argb.size * 4L + (tintIndex?.size ?: 0) * 2L
}

/** A pathological region degrades to untinted past this rather than growing an unbounded palette */
const val TINT_CAP = Short.MAX_VALUE.toInt()
