package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import net.minecraft.client.Minecraft

/** Result of an install attempt. */
sealed interface InstallResult {
    data class Success(val saveFolder: String) : InstallResult
    data class Failure(val message: String) : InstallResult
}

/**
 * Downloads a map's world file and unpacks it into the `saves/` directory, then writes an
 * [InstalledMeta] marker so the map is recognised offline. Blocking — call from a coroutine.
 */
object MapInstaller {

    fun install(entry: MapEntry): InstallResult {
        MapRepository.loadDetail(entry)
        val url = entry.downloadUrl
            ?: return InstallResult.Failure("No downloadable world file found for '${entry.title}'.")

        val bytes = Http.getBytes(url)
            ?: return InstallResult.Failure("Download failed for '${entry.title}'.")

        val files = try {
            readZip(bytes)
        } catch (e: Exception) {
            return InstallResult.Failure("Not a valid archive: ${e.message}")
        }

        // Locate the world root by the shallowest level.dat.
        val levelEntry = files.keys
            .filter { it == "level.dat" || it.endsWith("/level.dat") }
            .minByOrNull { it.count { c -> c == '/' } }
            ?: return InstallResult.Failure("Archive contains no world (no level.dat found).")
        val prefix = levelEntry.removeSuffix("level.dat") // "" or "world/" or "overrides/saves/world/"

        val savesDir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves")
        Files.createDirectories(savesDir)
        val target = uniqueFolder(savesDir, entry.title)

        try {
            for ((name, content) in files) {
                if (!name.startsWith(prefix)) continue
                val relative = name.removePrefix(prefix)
                if (relative.isEmpty() || name.endsWith("/")) continue
                val dest = target.resolve(relative).normalize()
                if (!dest.startsWith(target)) continue // zip-slip guard
                Files.createDirectories(dest.parent)
                Files.write(dest, content)
            }
            writeMarker(target, entry)
        } catch (e: Exception) {
            Constants.LOG.error("Install failed for {}", entry.title, e)
            return InstallResult.Failure("Failed to write world: ${e.message}")
        }

        return InstallResult.Success(target.fileName.toString())
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        return out
    }

    private fun writeMarker(target: Path, entry: MapEntry) {
        val meta = InstalledMeta(
            id = entry.id,
            source = entry.source,
            title = entry.title,
            icon = entry.iconUrl,
            website = entry.sourceUrl ?: entry.website,
            requiredMods = entry.requiredMods,
            requiredPacks = entry.requiredPacks,
        )
        Files.writeString(target.resolve(InstalledMeta.FILE_NAME), Http.json.encodeToString(meta))
    }

    private fun uniqueFolder(savesDir: Path, title: String): Path {
        val base = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Map" }
        var candidate = savesDir.resolve(base)
        var i = 1
        while (Files.exists(candidate)) {
            candidate = savesDir.resolve("$base ($i)")
            i++
        }
        return candidate
    }
}
