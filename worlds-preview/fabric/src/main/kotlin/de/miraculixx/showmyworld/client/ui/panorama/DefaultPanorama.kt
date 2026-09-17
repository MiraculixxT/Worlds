package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.showmyworld.Constants
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
         * Every save and server that ships or captured a panorama (blocking)
         */
        fun scan(): List<PanoramaCandidate> =
            scanDir(PanoramaRoots.saves(), ::worldLastPlayed) + scanDir(PanoramaRoots.servers(), ::serverLastPlayed)

        private fun scanDir(parent: Path, lastPlayed: (Path, Path) -> Long): List<PanoramaCandidate> {
            if (!Files.isDirectory(parent)) return emptyList()
            return try {
                Files.newDirectoryStream(parent).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .mapNotNull { root ->
                            WorldPanoramaTexture.resolve(root)?.let { PanoramaCandidate(it, lastPlayed(root, it)) }
                        }
                }
            } catch (e: Exception) {
                Constants.LOG.warn("Could not scan {} for panoramas: {}", parent, e.message)
                emptyList()
            }
        }

        private fun worldLastPlayed(saveDir: Path, panorama: Path): Long = try {
            NbtIo.readCompressed(saveDir.resolve("level.dat"), NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data").getLongOr("LastPlayed", 0L)
        } catch (_: Exception) {
            0L
        }

        /** Stamp from [PanoramaCapture], else the panoramas own age */
        private fun serverLastPlayed(serverDir: Path, panorama: Path): Long = try {
            PanoramaRoots.lastPlayedFile(serverDir).readText().trim().toLong()
        } catch (_: Exception) {
            runCatching { Files.getLastModifiedTime(panorama).toMillis() }.getOrDefault(0L)
        }
    }
}
