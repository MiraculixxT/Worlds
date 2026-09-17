package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import de.miraculixx.showmyworld.client.PreviewConfig
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.client.Minecraft
import net.minecraft.world.level.storage.LevelResource

/**
 * Auto captures a panorama on every world *and* server leave IF no manual was set
 * - Manual: <root>/panorama/.png
 * - Autos: <root>/panorama/screenshots/.png (the screenshots comes from MCs capture method)
 *
 * <root> is the save folder for a world and `<config>/showmyworld/servers/<address>` for a
 * server, see [PanoramaRoots]
 */
object PanoramaCapture {
    /**
     * Called by `MinecraftMixin`
     */
    fun onLeaveWorld(minecraft: Minecraft) {
        // Playing a world ends whatever selection led into it
        WorldPanorama.select(null)
        // A leave rewrites LastPlayed, so the default-panorama pick order is stale either way
        WorldPanorama.invalidateLibrary()
        if (minecraft.player == null || minecraft.level == null) return

        val root = rootOf(minecraft) ?: return
        if (PreviewConfig.settings.autoCreate) capture(minecraft, root)
        if (minecraft.singleplayerServer == null) stampServer(root)
    }

    private fun capture(minecraft: Minecraft, root: Path) {
        val dir = WorldPanoramaTexture.manualDir(root)
        if (WorldPanoramaTexture.isComplete(dir)) return // manual present, skip

        try {
            // MC only mkdir(), create parent
            Files.createDirectories(dir)
        } catch (e: Exception) {
            Constants.LOG.warn("Could not create {}: {}", dir, e.message)
            return
        }
        val result = minecraft.grabPanoramixScreenshot(dir.toFile())
        WorldPanorama.invalidate(root)
        Constants.LOG.info("Panorama for {}: {}", root.fileName, result.string)
    }

    /** Manually mark when the last play was */
    private fun stampServer(root: Path) {
        if (!Files.isDirectory(root)) return
        try {
            Files.writeString(PanoramaRoots.lastPlayedFile(root), System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Constants.LOG.warn("Could not stamp {}: {}", root, e.message)
        }
    }

    private fun rootOf(minecraft: Minecraft): Path? {
        val server = minecraft.singleplayerServer
        if (server != null) {
            return try {
                server.getWorldPath(LevelResource.ROOT).normalize() // strip "/." (why is it there bruh)
            } catch (e: Exception) {
                Constants.LOG.warn("Could not locate the save folder for a panorama: {}", e.message)
                null
            }
        }
        // A LAN address is session-lived, so it would key a folder that is never previewed again
        val data = minecraft.currentServer?.takeUnless { it.isLan } ?: return null
        val address = data.ip.takeIf { it.isNotBlank() } ?: return null
        return PanoramaRoots.server(address)
    }
}
