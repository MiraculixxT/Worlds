package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.data.TintKey
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor

private const val SHIFT = 16
private const val ONE = 1 shl SHIFT

/**
 * The biome tint of one region, resolved once per [TintKey] the renderer recorded
 */
internal class RegionTint private constructor(private val ratios: Array<IntArray?>) {

    /** [index] is the pixels `tintIndex`, e.g. a palette position plus one or 0 for untinted */
    fun apply(argb: Int, index: Int): Int {
        val ratio = ratios.getOrNull(index - 1) ?: return argb
        return (argb and -0x1000000) or
            (channel(argb shr 16 and 0xFF, ratio[0]) shl 16) or
            (channel(argb shr 8 and 0xFF, ratio[1]) shl 8) or
            channel(argb and 0xFF, ratio[2])
    }

    private fun channel(value: Int, ratio: Int): Int = (value * ratio shr SHIFT).coerceAtMost(0xFF)

    companion object {
        private val COLORS: BlockColors by lazy { BlockColors.createDefault() }

        /** What [BlockColors.getColor] answers for a block nothing tints */
        private const val UNTINTED = -1

        /** @return null when nothing in the region can be tinted (skip) */
        fun of(palette: List<TintKey>, biomes: Registry<Biome>): RegionTint? {
            if (palette.isEmpty()) return null
            val plains = biomes.get(Biomes.PLAINS) ?: biomes.firstOrNull() ?: return null
            val getter = TintGetter()
            val cursor = BlockPos.ZERO
            var any = false
            val ratios = arrayOfNulls<IntArray>(palette.size)
            palette.forEachIndexed { index, key ->
                val biome = biomes.get(biomeKey(key.biome) ?: return@forEachIndexed) ?: return@forEachIndexed
                val mapColor = MapColor.byId(key.mapColor)
                val target = if (mapColor === MapColor.WATER) {
                    // Water has no tint source, go through particles
                    BiomeColors.WATER_COLOR_RESOLVER.getColor(biome, 0.0, 0.0)
                } else {
                    val state = blockState(key.block) ?: return@forEachIndexed
                    getter.biome = biome
                    val here = COLORS.getColor(state, getter, cursor, 0)
                    if (here == UNTINTED) return@forEachIndexed
                    getter.biome = plains
                    val plain = COLORS.getColor(state, getter, cursor, 0)
                    if (here == plain) return@forEachIndexed
                    ratios[index] = ratio(here, plain)
                    any = true
                    return@forEachIndexed
                }
                ratios[index] = ratio(target, mapColor.col)
                any = true
            }
            return if (any) RegionTint(ratios) else null
        }

        private fun ratio(here: Int, reference: Int): IntArray = IntArray(3) { channel ->
            val shift = 16 - channel * 8
            val base = reference shr shift and 0xFF
            if (base == 0) ONE else ((here shr shift and 0xFF) shl SHIFT) / base
        }

        private fun blockState(id: ResourceLocation): BlockState? =
            BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.defaultBlockState()

        private fun biomeKey(id: String): ResourceKey<Biome>? =
            ResourceLocation.tryParse(id)?.let { ResourceKey.create(Registries.BIOME, it) }
    }
}

/**
 * Everything but the biome tint is from the empty level.
 *
 * 1.21's `EmptyBlockGetter` is only a `BlockGetter`, so [BlockAndTintGetter]'s three light/shade
 * members are answered here rather than delegated.
 */
private class TintGetter : BlockAndTintGetter {
    var biome: Biome? = null

    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        val value = biome ?: return -1
        return resolver.getColor(value, pos.x.toDouble(), pos.z.toDouble())
    }

    override fun getShade(direction: Direction, shade: Boolean): Float = 1f

    override fun getLightEngine(): LevelLightEngine? = null

    override fun getBrightness(layer: LightLayer, pos: BlockPos): Int = 0

    override fun getRawBrightness(pos: BlockPos, amount: Int): Int = 0

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = EmptyBlockGetter.INSTANCE.getBlockEntity(pos)

    override fun getBlockState(pos: BlockPos): BlockState = EmptyBlockGetter.INSTANCE.getBlockState(pos)

    override fun getFluidState(pos: BlockPos): FluidState = EmptyBlockGetter.INSTANCE.getFluidState(pos)

    override fun getHeight(): Int = EmptyBlockGetter.INSTANCE.height

    override fun getMinBuildHeight(): Int = EmptyBlockGetter.INSTANCE.minBuildHeight
}
