package de.miraculixx.chunkeditor.client.ui

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.permissions.LevelBasedPermissionSet
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
                Constants.LOG.info("biomes {}: {} entries in {} ms", access.levelId, registry.size(), Constants.ms(started))
                registry
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to load the biomes of {}", access.levelId, e)
                null
            }
        }

    /** The save's own packs and feature flags */
    private fun readDataConfiguration(access: LevelStorageSource.LevelStorageAccess): WorldDataConfiguration =
        try {
            val data = access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag
            WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, data).result()
                .orElse(WorldDataConfiguration.DEFAULT)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read data configuration of {}: {}", access.levelId, e.message)
            WorldDataConfiguration.DEFAULT
        }
}
