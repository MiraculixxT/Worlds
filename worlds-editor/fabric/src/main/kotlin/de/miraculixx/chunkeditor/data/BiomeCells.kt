package de.miraculixx.chunkeditor.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.CompoundTag as Compound
import net.minecraft.util.Mth

/** Past this a sections biomes are packed against the world registry instead its own palett */
private const val MAX_PALETTE_BITS = 3

/**
 * One sections `biomes` container, read by name (see [MAX_PALETTE_BITS])
 */
internal class BiomeCells private constructor(
    private val palette: List<String>,
    private val data: LongArray?,
    private val bits: Int,
) {

    /** The biome id covering a block local to the section, or null when the cell cannot be read. */
    fun at(lx: Int, ly: Int, lz: Int): String? {
        // 4x4x4 cells indexed (y * 4 + z) * 4 + x
        if (data == null) return palette.firstOrNull()
        val cell = ((ly and 15) shr 2) * 16 + (lz shr 2) * 4 + (lx shr 2)
        val perLong = 64 / bits
        if (cell / perLong >= data.size) return null
        val index = ((data[cell / perLong] ushr (cell % perLong) * bits) and ((1L shl bits) - 1)).toInt()
        return palette.getOrNull(index)
    }

    companion object {
        fun of(section: CompoundTag): BiomeCells? {
            val biomes = section.get("biomes") as? Compound ?: return null
            val raw = biomes.getListOrEmpty("palette")
            if (raw.isEmpty()) return null
            val palette = raw.indices.map { raw.getStringOr(it, "") }
            if (palette.any { it.isEmpty() }) return null
            // A single-entry palette stores no cells
            val data = biomes.getLongArray("data").takeIf { it.isNotEmpty() }
            if (data == null) return BiomeCells(palette, null, 0)
            val bits = Mth.ceillog2(palette.size).coerceAtLeast(1)
            if (bits > MAX_PALETTE_BITS) return null
            return BiomeCells(palette, data, bits)
        }
    }
}
