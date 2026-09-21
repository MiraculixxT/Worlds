package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.client.resources.language.I18n

class PanoramaCandidate(val dir: Path)

/**
 * What [WorldPanorama] shows while no world is selected
 */
@Serializable
enum class DefaultPanorama {
    @SerialName("vanilla") VANILLA,
    @SerialName("last_played") LAST_PLAYED,
    @SerialName("random") RANDOM;

    val label: String get() = I18n.get("showmyworld.settings.default.${name.lowercase()}")

    fun pick(candidates: List<PanoramaCandidate>): Path? = when (this) {
        VANILLA -> null
        LAST_PLAYED -> lastPlayed()
        RANDOM -> candidates.randomOrNull()?.dir
    }

    private fun lastPlayed(): Path? = LastPlayed.get()?.let { WorldPanoramaTexture.resolve(it.root) }

    companion object {
        /**
         * Every save and server that ships or captured a panorama (blocking), only [RANDOM] needs it
         */
        fun scan(): List<PanoramaCandidate> = scanDir(PanoramaRoots.saves()) + scanDir(PanoramaRoots.servers())

        private fun scanDir(parent: Path): List<PanoramaCandidate> {
            if (!Files.isDirectory(parent)) return emptyList()
            return try {
                Files.newDirectoryStream(parent).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .mapNotNull { root -> WorldPanoramaTexture.resolve(root)?.let(::PanoramaCandidate) }
                }
            } catch (e: Exception) {
                Constants.LOG.warn("Could not scan {} for panoramas: {}", parent, e.message)
                emptyList()
            }
        }
    }
}
