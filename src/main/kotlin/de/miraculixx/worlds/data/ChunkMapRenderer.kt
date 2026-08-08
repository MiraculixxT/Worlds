package de.miraculixx.worlds.data

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.worlds.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Strategy
import net.minecraft.world.level.material.MapColor

/** One region rendered as map colors, plus which of its chunks could not be read. */
class RegionImage(val image: NativeImage, val unreadable: Set<Long>)

private const val REGION_BLOCKS = REGION_SIZE * 16
private const val NO_COLOR = -1

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
     */
    suspend fun renderRegion(dimension: WorldDimension, rx: Int, rz: Int, step: Int = 1): RegionImage? =
        withContext(Dispatchers.IO) {
            val store = ChunkRegions.storage(dimension, "region") ?: return@withContext null
            val pixels = REGION_BLOCKS / step
            val perChunk = 16 / step
            val colors = IntArray(pixels * pixels) { NO_COLOR }
            val heights = IntArray(pixels * pixels)
            val depths = IntArray(pixels * pixels)
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
                            tag, cx * perChunk, cz * perChunk, step, pixels, colors, heights, depths,
                        )
                        if (!ok) unreadable.add(pos.pack())
                    }
                }
            } finally {
                runCatching { store.close() }
            }
            RegionImage(shade(colors, heights, depths, pixels, step), unreadable)
        }

    /**
     * Walks one chunk's columns top-down and records their surface color
     * @return false when decoding error
     */
    private fun surfaceOf(
        raw: CompoundTag, originX: Int, originZ: Int, step: Int, pixels: Int,
        colors: IntArray, heights: IntArray, depths: IntArray,
    ): Boolean {
        val sections = try {
            upgrade(raw).getListOrEmpty("sections").mapNotNull { entry ->
                val section = entry as? CompoundTag ?: return@mapNotNull null
                val y = section.getByte("Y").orElse(null)?.toInt() ?: return@mapNotNull null
                val states = section.getCompound("block_states").orElse(null) ?: return@mapNotNull null
                if (isAirOnly(states)) null else y to states
            }.sortedByDescending { it.first }
        } catch (e: Exception) {
            return false
        }
        if (sections.isEmpty()) return true

        // Decoding is deferred per section
        val decoded = arrayOfNulls<PalettedContainer<BlockState>>(sections.size)
        fun container(index: Int): PalettedContainer<BlockState>? {
            decoded[index]?.let { return it }
            val parsed = statesCodec.parse(NbtOps.INSTANCE, sections[index].second).result().orElse(null)
            decoded[index] = parsed
            return parsed
        }

        for (pz in 0 until 16 / step) {
            for (px in 0 until 16 / step) {
                val lx = px * step
                val lz = pz * step
                var color = NO_COLOR
                var height = 0
                var depth = 0
                columns@ for (index in sections.indices) {
                    val container = container(index) ?: return false
                    for (ly in 15 downTo 0) {
                        val mapColor = container.get(lx, ly, lz)
                            .getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        if (mapColor === MapColor.NONE) continue
                        if (color == NO_COLOR) {
                            color = mapColor.id
                            height = sections[index].first * 16 + ly
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
            }
        }
        return true
    }

    /** Height-step and water-depth shading, vanilla mirror. */
    private fun shade(colors: IntArray, heights: IntArray, depths: IntArray, pixels: Int, step: Int): NativeImage {
        val image = NativeImage(NativeImage.Format.RGBA, pixels, pixels, false)
        for (z in 0 until pixels) {
            for (x in 0 until pixels) {
                val i = z * pixels + x
                val id = colors[i]
                if (id == NO_COLOR) {
                    image.setPixelABGR(x, z, 0)
                    continue
                }
                val color = MapColor.byId(id)
                val brightness = if (color === MapColor.WATER) {
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
                image.setPixelABGR(x, z, abgr(color.calculateARGBColor(brightness)))
            }
        }
        return image
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
        return DataFixTypes.CHUNK.updateToCurrentVersion(Minecraft.getInstance().fixerUpper, tag, version)
    }

    /** 0xAARRGGBB → the 0xAABBGGRR [NativeImage.setPixelABGR] wants. */
    private fun abgr(argb: Int): Int =
        (argb and -0x1000000) or (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb ushr 16 and 0xFF)
}
