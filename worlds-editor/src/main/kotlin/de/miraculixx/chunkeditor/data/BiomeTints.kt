package de.miraculixx.chunkeditor.data

import com.mojang.serialization.Codec
import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.util.Util
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.chunk.Strategy
import net.minecraft.world.level.storage.LevelStorageSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Folds a biome's grass / foliage / water tint into the flat [net.minecraft.world.level.material.MapColor]
 * the chunk map is drawn from.
 */
class BiomeTints private constructor(private val registry: Registry<Biome>, private val reference: Biome) {

    private val biomeCodec: Codec<PalettedContainerRO<Holder<Biome>>> = PalettedContainer.codecRO(
        registry.holderByNameCodec(),
        Strategy.createForBiomes(registry.asHolderIdMap()),
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
            val tag = section.getCompound("biomes").orElse(null) ?: return null
            return biomeCodec.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial { if (warned.compareAndSet(false, true)) Constants.LOG.warn("Failed to read biomes: {}", it) }
                .orElse(null)
        }

        /**
         * Shifts vanillas reported plains like tint to the biomes proper tint, based on [base]
         */
        fun tint(state: BlockState, biome: Biome, base: Int, x: Int, y: Int, z: Int): Int {
            val source = COLORS.getTintSource(state, 0) ?: return base
            val key = Block.BLOCK_STATE_REGISTRY.getId(state).toLong() shl 32 or
                (registry.getId(biome).toLong() and 0xFFFFFFFFL)
            cache[key]?.let { return it }
            cursor.set(x, y, z)
            getter.biome = reference
            val plain = source.colorInWorld(state, getter, cursor)
            getter.biome = biome
            val here = source.colorInWorld(state, getter, cursor)
            val result = if (plain == here) base else scale(base, here, plain)
            cache[key] = result
            return result
        }
    }

    companion object {
        private val COLORS: BlockColors by lazy { BlockColors.createDefault() }

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
                            packConfig, Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.GAMEMASTER,
                        ),
                        { context -> WorldLoader.DataLoadOutput(Unit, context.datapackDimensions()) },
                        { resources, _, registries, _ ->
                            resources.close()
                            registries.compositeAccess().lookup(Registries.BIOME).orElseThrow()
                        },
                        Util.backgroundExecutor(),
                        Minecraft.getInstance(),
                    ).join()
                    val reference = registry.getValue(Biomes.PLAINS) ?: registry.firstOrNull()
                    if (reference == null) null else BiomeTints(registry, reference)
                } catch (e: Exception) {
                    Constants.LOG.warn("Failed to load biomes of {}", access.levelId, e)
                    null
                }
            }

        /** The save's own packs and feature flags, or a datapack's biomes would never be loaded. */
        private fun readDataConfiguration(access: LevelStorageSource.LevelStorageAccess): WorldDataConfiguration =
            try {
                val data = access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag
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

/** Everything but the biome tint is answered by the empty level. */
private class TintGetter : BlockAndTintGetter by BlockAndTintGetter.EMPTY {
    var biome: Biome? = null

    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int {
        val value = biome ?: return -1
        return resolver.getColor(value, pos.x.toDouble(), pos.z.toDouble())
    }
}
