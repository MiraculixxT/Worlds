package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.server.RegistryLayer
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.tags.TagLoader
import net.minecraft.util.Util
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
                    val layers = RegistryLayer.createRegistryAccess()
                    val staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC))
                    val context = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), staticTags)
                    RegistryDataLoader.load(resources, context, RegistryDataLoader.WORLD_REGISTRIES, Util.backgroundExecutor())
                        .join().lookup(Registries.BIOME).orElseThrow()
                }
                Constants.LOG.info("biomes {}: {} entries in {} ms", access.levelId, registry.size(), Constants.ms(started))
                registry
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to load the biomes of {}", access.levelId, e)
                null
            }
        }

    /** The save's own packs and feature flags, out of the `Data` compound of `level.dat` */
    private fun readDataConfiguration(access: LevelStorageSource.LevelStorageAccess): WorldDataConfiguration =
        try {
            val root = access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag
            WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, root.getCompoundOrEmpty("Data")).result()
                .orElse(WorldDataConfiguration.DEFAULT)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read data configuration of {}: {}", access.levelId, e.message)
            WorldDataConfiguration.DEFAULT
        }
}
