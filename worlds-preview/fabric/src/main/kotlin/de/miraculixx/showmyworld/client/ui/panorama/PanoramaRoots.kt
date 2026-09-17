package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import java.nio.file.Path
import de.miraculixx.common.Loader
import net.minecraft.client.Minecraft

object PanoramaRoots {
    private const val SERVERS = "servers"
    private const val LAST_PLAYED = "last_played"

    /** `<gamedir>/saves/<folder>` */
    fun world(saveFolder: String): Path = saves().resolve(saveFolder)

    /** `<gamedir>/saves` */
    fun saves(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("saves")

    /** `<config>/showmyworld/servers` */
    fun servers(): Path = Loader.configDir.resolve(Constants.MOD_ID).resolve(SERVERS)

    /** `<config>/showmyworld/servers/<address>` */
    fun server(address: String): Path = servers().resolve(sanitize(address))

    /** Epoch millis of the last leave, servers have no `level.dat` to hold it */
    fun lastPlayedFile(serverRoot: Path): Path = serverRoot.resolve(LAST_PLAYED)

    /**
     * Sanitize IP, primarily for windows because the file system sucks
     */
    private fun sanitize(address: String): String =
        address.trim().lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "unknown" }
}
