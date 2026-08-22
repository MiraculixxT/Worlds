package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import de.miraculixx.showmyworld.client.PreviewConfig
import java.nio.file.Files
import net.minecraft.client.Minecraft
import net.minecraft.world.level.storage.LevelResource

/**
 * Auto captures a panorama on every world leave IF no manual was set
 * - Manual: <world>/panorama/.png
 * - Autos: <world>/panorama/screenshots/.png (the screenshots comes from MCs capture method)
 */
object PanoramaCapture {
    /**
     * Called by `MinecraftMixin`
     */
    fun onLeaveWorld(minecraft: Minecraft) {
        // A leave rewrites LastPlayed, so the default-panorama pick order is stale either way
        WorldPanorama.invalidateLibrary()
        if (!PreviewConfig.settings.autoCreate) return
        val server = minecraft.singleplayerServer ?: return
        if (minecraft.player == null || minecraft.level == null) return

        val saveDir = try {
            server.getWorldPath(LevelResource.ROOT).normalize() // strip "/." (why is it there bruh)
        } catch (e: Exception) {
            Constants.LOG.warn("Could not locate the save folder for a panorama: {}", e.message)
            return
        }
        val dir = WorldPanoramaTexture.manualDir(saveDir)
        if (WorldPanoramaTexture.isComplete(dir)) return // manual present, skip

        try {
            // MC only mkdir(), create parent
            Files.createDirectories(dir)
        } catch (e: Exception) {
            Constants.LOG.warn("Could not create {}: {}", dir, e.message)
            return
        }
        val result = minecraft.grabPanoramixScreenshot(dir.toFile())
        WorldPanorama.invalidate(saveDir)
        Constants.LOG.info("Panorama for {}: {}", saveDir.fileName, result.string)
    }
}
