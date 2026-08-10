package de.miraculixx.worlds.client

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.ModrinthApi
import de.miraculixx.worlds.data.mcVersion
import kotlinx.coroutines.launch
import net.fabricmc.loader.api.FabricLoader


object ModUpdate {
    private const val LOADER = "fabric"

    val installedVersion: String? = FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
        .map { it.metadata.version.friendlyString }.orElse(null)

    @Volatile
    var latestVersion: String? = null
        private set

    val available: Boolean get() = latestVersion != null

    val url: String?
        get() = latestVersion?.let { "https://modrinth.com/mod/${Constants.WORLDS_MODRINTH_ID}/version/$it" }

    /** Runs the check off the render thread; safe to call once, further calls re-check. */
    fun check() {
        val installed = installedVersion
        Constants.SCOPE.launch {
            val mc = mcVersion
            val latest = ModrinthApi.latestVersion(Constants.WORLDS_MODRINTH_ID, LOADER, mc)
            when {
                latest == null -> Constants.LOG.warn("No {} release published for {} {}", Constants.MOD_ID, LOADER, mc)
                installed == null -> Constants.LOG.warn("Own mod version unknown, skipping update check")
                latest.versionNumber == installed -> Constants.LOG.info("{} is up to date ({})", Constants.MOD_ID, installed)
                else -> {
                    latestVersion = latest.versionNumber
                    Constants.LOG.warn(
                        "{} is outdated on {}. Installed: {} -> Latest: {}",
                        Constants.MOD_ID, mc, installed, latest.versionNumber
                    )
                }
            }
        }
    }
}
