package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import net.minecraft.client.Minecraft
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

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
            downloadExternalPacks(target, entry)
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
            description = entry.description,
            icon = entry.iconUrl,
            categories = entry.categories,
            downloads = entry.downloads,
            website = entry.sourceUrl ?: entry.website,
            trailer = entry.trailerUrl,
            requiredMods = entry.requiredMods,
            requiredPacks = entry.requiredPacks,
        )
        Files.writeString(target.resolve(InstalledMeta.FILE_NAME), Http.json.encodeToString(meta))
    }

    /**
     * Download every *external* required resource pack (not `included` in the world zip) into the
     * save's own `resourcepacks/` folder so [WorldResourcePacks] can enable them on join. Best-effort:
     * a pack that can't be resolved or fetched is logged and skipped, never failing the install.
     */
    private fun downloadExternalPacks(target: Path, entry: MapEntry) {
        val external = entry.requiredPacks.filter { !it.included }
        if (external.isEmpty()) return
        val packsDir = target.resolve("resourcepacks")
        for (pack in external) {
            val url = pack.download ?: pack.projectId?.let { MapRepository.resolveModrinthDownload(it) }
            if (url == null) {
                Constants.LOG.warn("No download URL for required pack '{}'", pack.name)
                continue
            }
            val bytes = Http.getBytes(url)
            if (bytes == null) {
                Constants.LOG.warn("Failed to download required pack '{}' from {}", pack.name, url)
                continue
            }
            try {
                Files.createDirectories(packsDir)
                Files.write(packsDir.resolve(packFilename(pack, url)), bytes)
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to save required pack '{}': {}", pack.name, e.message)
            }
        }
    }

    /** A `.zip` filename for a downloaded pack: prefer the URL's own name, else derive from title. */
    private fun packFilename(pack: MapRequirement, url: String): String {
        val fromUrl = url.substringAfterLast('/').substringBefore('?')
        if (fromUrl.endsWith(".zip", ignoreCase = true)) return fromUrl
        val base = pack.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "pack" }
        return "$base.zip"
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
