package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.util.datafix.DataFixers
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Strategy
import net.minecraft.world.level.material.MapColor
import java.util.IdentityHashMap
import kotlin.math.min

private const val REGION_BLOCKS = REGION_SIZE * 16
private const val NO_COLOR = -1

/** A section that holds something other than air, kept whole so its biomes stay reachable. */
private class Section(val y: Int, val states: CompoundTag, val tag: CompoundTag)

/**
 * Renders the map via minecrafts item-map colors.
 * Data chunked by region, images cached by chunk.
 */
object ChunkMapRenderer {

    /**
     * Vanilla mirror to avoid data loading
     */
    private val statesCodec by lazy {
        PalettedContainer.codecRW(
            BlockState.CODEC,
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
            Blocks.AIR.defaultBlockState(),
        )
    }

    private val currentDataVersion by lazy { SharedConstants.getCurrentVersion().dataVersion().version() }

    /**
     * One region as a square image, or null when the region file is gone.
     * @param step Quality state of the image
     * @param tints Biome tinting, when the save's biome registry has been loaded
     * @param maxY Highest block the column walk may start at, or null for the whole chunk
     */
    suspend fun renderRegion(
        dimension: WorldDimension, rx: Int, rz: Int, step: Int = 1, maxY: Int? = null,
    ): RegionPixels? =
        withContext(Dispatchers.IO) {
            val store = ChunkRegions.storage(dimension, "region") ?: return@withContext null
            val pixels = REGION_BLOCKS / step
            val perChunk = 16 / step
            val colors = IntArray(pixels * pixels) { NO_COLOR }
            val heights = IntArray(pixels * pixels)
            val depths = IntArray(pixels * pixels)
            val tints = ShortArray(pixels * pixels)
            val palette = LinkedHashMap<TintKey, Int>()
            val tintCache = IdentityHashMap<BlockState, MutableMap<String, Int>>()
            val unreadable = HashSet<Long>()
            try {
                for (cz in 0 until REGION_SIZE) {
                    for (cx in 0 until REGION_SIZE) {
                        val pos = ChunkPos(rx * REGION_SIZE + cx, rz * REGION_SIZE + cz)
                        val tag = try {
                            store.read(pos)
                        } catch (e: Exception) {
                            Constants.LOG.warn("Failed to read chunk {}: {}", pos, e.message)
                            null
                        } ?: continue
                        val ok = surfaceOf(
                            tag, cx * perChunk, cz * perChunk, step, pixels,
                            colors, heights, depths, tints, palette, tintCache, maxY,
                        )
                        if (!ok) unreadable.add(pos.pack())
                    }
                }
            } finally {
                runCatching { store.close() }
            }
            RegionPixels(
                pixels, shade(colors, heights, depths, pixels, step), unreadable,
                palette.keys.toList(), if (palette.isEmpty()) null else tints,
            )
        }

    /**
     * Walks one chunk's columns top-down and records their surface color
     * @return false when decoding error
     */
    private fun surfaceOf(
        raw: CompoundTag, originX: Int, originZ: Int, step: Int, pixels: Int,
        colors: IntArray, heights: IntArray, depths: IntArray, tints: ShortArray,
        palette: MutableMap<TintKey, Int>, tintCache: MutableMap<BlockState, MutableMap<String, Int>>, maxY: Int?,
    ): Boolean {
        val sections = try {
            upgrade(raw).getListOrEmpty("sections").mapNotNull { entry ->
                val section = entry as? CompoundTag ?: return@mapNotNull null
                val y = section.getByte("Y").orElse(null)?.toInt() ?: return@mapNotNull null
                if (maxY != null && y * 16 > maxY) return@mapNotNull null
                val states = section.getCompound("block_states").orElse(null) ?: return@mapNotNull null
                if (isAirOnly(states)) null else Section(y, states, section)
            }.sortedByDescending { it.y }
        } catch (e: Exception) {
            return false
        }
        if (sections.isEmpty()) return true

        // Decoding is deferred per section
        val decoded = arrayOfNulls<PalettedContainer<BlockState>>(sections.size)
        fun container(index: Int): PalettedContainer<BlockState>? {
            decoded[index]?.let { return it }
            val parsed = statesCodec.parse(NbtOps.INSTANCE, sections[index].states).result().orElse(null)
            decoded[index] = parsed
            return parsed
        }

        // Only a surface block ever asks for these, so most sections never read their biomes.
        val biomes = arrayOfNulls<BiomeCells>(sections.size)
        val biomesRead = BooleanArray(sections.size)
        fun biomeAt(index: Int, lx: Int, ly: Int, lz: Int): String? {
            if (!biomesRead[index]) {
                biomesRead[index] = true
                biomes[index] = BiomeCells.of(sections[index].tag)
            }
            return biomes[index]?.at(lx, ly, lz)
        }

        for (pz in 0 until 16 / step) {
            for (px in 0 until 16 / step) {
                val lx = px * step
                val lz = pz * step
                var color = NO_COLOR
                var height = 0
                var depth = 0
                var tint = 0
                columns@ for (index in sections.indices) {
                    val container = container(index) ?: return false
                    // The cut lands mid-section for the highest one it keeps
                    val from = if (maxY == null) 15 else min(15, maxY - sections[index].y * 16)
                    for (ly in from downTo 0) {
                        val state = container.get(lx, ly, lz)
                        val mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        if (mapColor === MapColor.NONE) continue
                        if (color == NO_COLOR) {
                            color = mapColor.id
                            height = sections[index].y * 16 + ly
                            tint = tintOf(state, biomeAt(index, lx, ly, lz), mapColor, palette, tintCache)
                            if (mapColor !== MapColor.WATER) break@columns
                        }
                        // Keep descending through water so the depth ramp can shade the ocean.
                        if (mapColor !== MapColor.WATER) break@columns
                        depth++
                    }
                }
                val i = (originZ + pz) * pixels + originX + px
                colors[i] = color
                heights[i] = height
                depths[i] = depth
                tints[i] = tint.toShort()
            }
        }
        return true
    }

    /**
     * The pixel's [TintKey] as a palette index plus one, or 0 when there is nothing to key on
     */
    private fun tintOf(
        state: BlockState, biome: String?, mapColor: MapColor, palette: MutableMap<TintKey, Int>,
        cache: MutableMap<BlockState, MutableMap<String, Int>>,
    ): Int {
        if (biome == null) return 0
        val perBiome = cache.getOrPut(state) { HashMap(4) }
        perBiome[biome]?.let { return it }
        if (palette.size >= TINT_CAP) return 0
        val key = TintKey(BuiltInRegistries.BLOCK.getKey(state.block), biome, mapColor.id)
        val index = palette.getOrPut(key) { palette.size + 1 }
        perBiome[biome] = index
        return index
    }

    /** Height-step and water-depth shading, vanilla mirror (tinting moved off to caller) */
    private fun shade(colors: IntArray, heights: IntArray, depths: IntArray, pixels: Int, step: Int): IntArray {
        val out = IntArray(pixels * pixels)
        for (z in 0 until pixels) {
            for (x in 0 until pixels) {
                val i = z * pixels + x
                val id = colors[i]
                if (id == NO_COLOR) continue
                val mapColor = MapColor.byId(id)
                val brightness = if (mapColor === MapColor.WATER) {
                    val d = depths[i] * 0.1 + (x + z and 1) * 0.2
                    when {
                        d < 0.5 -> MapColor.Brightness.HIGH
                        d > 0.9 -> MapColor.Brightness.LOW
                        else -> MapColor.Brightness.NORMAL
                    }
                } else {
                    val north = if (z > 0) heights[i - pixels] else heights[i]
                    // Divided by the sampling step: neighboring pixels are that many blocks apart, and
                    // without it a coarse pass would read every slope as a cliff.
                    val delta = (heights[i] - north) * 4.0 / 5.0 / step + ((x + z and 1) - 0.5) * 0.4
                    when {
                        delta > 0.6 -> MapColor.Brightness.HIGH
                        delta < -0.6 -> MapColor.Brightness.LOW
                        else -> MapColor.Brightness.NORMAL
                    }
                }
                // What MapColor.calculateARGBColor does
                out[i] = ARGB.scaleRGB(ARGB.opaque(mapColor.col), brightness.modifier)
            }
        }
        return out
    }

    /** A section whose whole palette is one of the air blocks contributes nothing to the surface. */
    private fun isAirOnly(states: CompoundTag): Boolean {
        val palette = states.getListOrEmpty("palette")
        if (palette.size != 1) return false
        val name = (palette[0] as? CompoundTag)?.getStringOr("Name", "") ?: return false
        return name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air"
    }

    /** Chunks written by an older game still decode once vanilla's own chunk fixer has run. */
    private fun upgrade(tag: CompoundTag): CompoundTag {
        val version = NbtUtils.getDataVersion(tag, 0)
        if (version >= currentDataVersion) return tag
        return DataFixTypes.CHUNK.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version)
    }
}
