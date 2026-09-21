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
    /** What vanilla's own F3+F12 renders each face at */
    private const val CAPTURE_SIZE = 1024

    /**
     * Called by `MinecraftMixin`
     */
    fun onLeaveWorld(minecraft: Minecraft) {
        val record = if (minecraft.player != null && minecraft.level != null) recordOf(minecraft) else null
        val root = record?.root
        val autoCreate = PreviewConfig.settings.autoCreate
        // Before any rescan: the library scan reads what a leave writes
        if (record != null && (autoCreate || WorldPanoramaTexture.resolve(root!!) != null)) LastPlayed.set(record)
        // Playing a world ends whatever selection led into it
        WorldPanorama.releaseJoin()
        WorldPanorama.select(null)
        WorldPanorama.invalidateLibrary()

        if (root == null || !autoCreate) return
        capture(minecraft, root)
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
        val result = minecraft.grabPanoramixScreenshot(dir.toFile(), CAPTURE_SIZE, CAPTURE_SIZE)
        // The PNGs are written async, so the folder is not readable yet
        WorldPanorama.awaitCapture(root)
        Constants.LOG.info("Panorama for {}: {}", root.fileName, result.string)
    }

    private fun recordOf(minecraft: Minecraft): LastPlayedRecord? {
        val server = minecraft.singleplayerServer
        if (server != null) {
            return try {
                // getWorldPath ends in "/." (why is it there bruh)
                val folder = server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString()
                LastPlayedRecord(LastPlayedKind.WORLD, folder)
            } catch (e: Exception) {
                Constants.LOG.warn("Could not locate the save folder for a panorama: {}", e.message)
                null
            }
        }
        // A LAN address is session-lived, so it would key a folder that is never previewed again
        val data = minecraft.currentServer?.takeUnless { it.isLan } ?: return null
        val address = data.ip.takeIf { it.isNotBlank() } ?: return null
        return LastPlayedRecord(LastPlayedKind.SERVER, PanoramaRoots.server(address).fileName.toString())
    }
}
