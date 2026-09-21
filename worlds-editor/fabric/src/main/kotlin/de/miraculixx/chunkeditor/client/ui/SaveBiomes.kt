package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.LevelDat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.server.RegistryLayer
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * The save's own biome registry, for [RegionTint]
 */
internal object SaveBiomes {

    suspend fun load(access: LevelStorageSource.LevelStorageAccess): Registry<Biome>? =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val packConfig = WorldLoader.PackConfig(
                    ServerPacksSource.createPackRepository(access), readDataConfiguration(access), false, false,
                )
                val resources = packConfig.createResourceManager().second
                val registry = resources.use { resources ->
                    // 1.21's loader takes the layer itself, there is no separate static tag pass
                    val layers = RegistryLayer.createRegistryAccess()
                    RegistryDataLoader.load(
                        resources, layers.getAccessForLoading(RegistryLayer.WORLDGEN),
                        RegistryDataLoader.WORLDGEN_REGISTRIES,
                    ).registryOrThrow(Registries.BIOME)
                }
                Constants.LOG.info("biomes {}: {} entries in {} ms", access.levelId, registry.size(), Constants.ms(started))
                registry
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to load the biomes of {}", access.levelId, e)
                null
            }
        }

    /** The save's own packs and feature flags, out of the `Data` compound of `level.dat` */
    private fun readDataConfiguration(access: LevelStorageSource.LevelStorageAccess): WorldDataConfiguration {
        return try {
            val data = LevelDat.read(access) ?: return WorldDataConfiguration.DEFAULT
            WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, data).result()
                .orElse(WorldDataConfiguration.DEFAULT)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read data configuration of {}: {}", access.levelId, e.message)
            WorldDataConfiguration.DEFAULT
        }
    }
}
