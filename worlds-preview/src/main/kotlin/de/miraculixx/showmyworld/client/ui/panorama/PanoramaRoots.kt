package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

object PanoramaRoots {
    private const val SERVERS = "servers"

    /** `<gamedir>/saves/<folder>` */
    fun world(saveFolder: String): Path =
        Minecraft.getInstance().gameDirectory.toPath().resolve("saves").resolve(saveFolder)

    /** `<config>/showmyworld/servers/<address>` */
    fun server(address: String): Path =
        FabricLoader.getInstance().configDir.resolve(Constants.MOD_ID).resolve(SERVERS).resolve(sanitize(address))

    /**
     * Sanitize IP, primarily for windows because the file system sucks
     */
    private fun sanitize(address: String): String =
        address.trim().lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "unknown" }
}
