package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo

class PanoramaCandidate(val dir: Path, val lastPlayed: Long)

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
        LAST_PLAYED -> candidates.maxByOrNull { it.lastPlayed }?.dir
        RANDOM -> candidates.randomOrNull()?.dir
    }

    companion object {
        /**
         * Every save that ships or captured a panorama (blocking)
         */
        fun scan(): List<PanoramaCandidate> {
            val saves = Minecraft.getInstance().gameDirectory.toPath().resolve("saves")
            if (!Files.isDirectory(saves)) return emptyList()
            return try {
                Files.newDirectoryStream(saves).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .mapNotNull { save ->
                            WorldPanoramaTexture.resolve(save)?.let { PanoramaCandidate(it, lastPlayed(save)) }
                        }
                }
            } catch (e: Exception) {
                Constants.LOG.warn("Could not scan saves/ for panoramas: {}", e.message)
                emptyList()
            }
        }

        private fun lastPlayed(saveDir: Path): Long = try {
            NbtIo.readCompressed(saveDir.resolve("level.dat"), NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data").getLongOr("LastPlayed", 0L)
        } catch (_: Exception) {
            0L
        }
    }
}
