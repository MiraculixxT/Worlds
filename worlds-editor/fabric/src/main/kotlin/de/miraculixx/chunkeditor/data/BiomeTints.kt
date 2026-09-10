package de.miraculixx.chunkeditor.data

import com.mojang.serialization.Codec
import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.renderer.BiomeColors
import de.miraculixx.common.LevelDat
import net.minecraft.world.level.BlockAndTintGetter
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.Util
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.lighting.LevelLightEngine
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.LevelStorageSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Folds a biome's grass / foliage / water tint into the flat [net.minecraft.world.level.material.MapColor]
 * the chunk map is drawn from.
 */
class BiomeTints private constructor(private val registry: Registry<Biome>, private val reference: Biome) {

    private val biomeCodec: Codec<PalettedContainerRO<Holder<Biome>>> = PalettedContainer.codecRO(
        registry.asHolderIdMap(),
        registry.holderByNameCodec(),
        PalettedContainer.Strategy.SECTION_BIOMES,
        registry.wrapAsHolder(reference),
    )

    /**
     * One render's worth of scratch state
     */
    fun session(): Session = Session()

    inner class Session internal constructor() {
        private val getter = TintGetter()
        private val cursor = BlockPos.MutableBlockPos()

        /**
         * Cached per block state and biome
         */
        private val cache = HashMap<Long, Int>()

        fun biomes(section: CompoundTag): PalettedContainerRO<Holder<Biome>>? {
            if (!section.contains("biomes")) return null
            val tag = section.getCompound("biomes")
            return biomeCodec.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial { if (warned.compareAndSet(false, true)) Constants.LOG.warn("Failed to read biomes: {}", it) }
                .orElse(null)
        }

        /**
         * Shifts vanillas reported plains like tint to the biomes proper tint, based on [mapColor]
         */
        fun tint(state: BlockState, biome: Biome, mapColor: MapColor, x: Int, y: Int, z: Int): Int {
            val key = Block.BLOCK_STATE_REGISTRY.getId(state).toLong() shl 32 or
                (registry.getId(biome).toLong() and 0xFFFFFFFFL)
            cache[key]?.let { return it }
            val result = compute(state, biome, mapColor, x, y, z)
            cache[key] = result
            return result
        }

        private fun compute(state: BlockState, biome: Biome, mapColor: MapColor, x: Int, y: Int, z: Int): Int {
            if (mapColor === MapColor.WATER) {
                return BiomeColors.WATER_COLOR_RESOLVER.getColor(biome, x.toDouble(), z.toDouble())
            }
            cursor.set(x, y, z)
            getter.biome = reference
            val plain = COLORS.getColor(state, getter, cursor, 0)
            if (plain == UNTINTED) return mapColor.col
            getter.biome = biome
            val here = COLORS.getColor(state, getter, cursor, 0)
            return if (plain == here) mapColor.col else scale(mapColor.col, here, plain)
        }
    }

    companion object {
        private val COLORS: BlockColors by lazy { BlockColors.createDefault() }

        /** What [BlockColors.getColor] answers for a block nothing tints */
        private const val UNTINTED = -1

        /** `WorldLoader.InitConfig` takes a plain permission level on 1.21, 2 is a datapack's default */
        private const val FUNCTION_PERMISSION_LEVEL = 2

        /** A broken palette breaks every section of every region, so it is logged once, not per chunk. */
        private val warned = AtomicBoolean(false)

        /**
         * Loads the save's own biome registry through the same datapack pass a world load runs, so
         * datapack biomes resolve too.
         * Costs: Full [WorldLoader] pass (seconds)
         */
        suspend fun load(access: LevelStorageSource.LevelStorageAccess): BiomeTints? =
            withContext(Dispatchers.IO) {
                try {
                    val packConfig = WorldLoader.PackConfig(
                        ServerPacksSource.createPackRepository(access), readDataConfiguration(access), false, false,
                    )
                    val registry = WorldLoader.load(
                        WorldLoader.InitConfig(
                            packConfig, Commands.CommandSelection.INTEGRATED, FUNCTION_PERMISSION_LEVEL,
                        ),
                        { context -> WorldLoader.DataLoadOutput(Unit, context.datapackDimensions()) },
                        { resources, _, registries, _ ->
                            resources.close()
                            registries.compositeAccess().registryOrThrow(Registries.BIOME)
                        },
                        Util.backgroundExecutor(),
                        Minecraft.getInstance(),
                    ).join()
                    val reference = registry.get(Biomes.PLAINS) ?: registry.firstOrNull()
                    if (reference == null) null else BiomeTints(registry, reference)
                } catch (e: Exception) {
                    Constants.LOG.warn("Failed to load biomes of {}", access.levelId, e)
                    null
                }
            }

        /** The save's own packs and feature flags, or a datapack's biomes would never be loaded. */
        private fun readDataConfiguration(access: LevelStorageSource.LevelStorageAccess): WorldDataConfiguration =
            try {
                val data = LevelDat.read(access) ?: return WorldDataConfiguration.DEFAULT
                WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, data).result()
                    .orElse(WorldDataConfiguration.DEFAULT)
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to read data configuration of {}: {}", access.levelId, e.message)
                WorldDataConfiguration.DEFAULT
            }

        private fun scale(base: Int, here: Int, plain: Int): Int {
            fun channel(shift: Int): Int {
                val reference = plain shr shift and 0xFF
                val value = base shr shift and 0xFF
                if (reference == 0) return value
                return (value * (here shr shift and 0xFF) / reference).coerceAtMost(0xFF)
            }
            return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

/**
 * Everything but the biome tint is answered by the empty level.
 *
 * 1.21's `EmptyBlockGetter` is only a `BlockGetter`, so the three light/shade members of
 * [BlockAndTintGetter] are answered here rather than delegated.
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
