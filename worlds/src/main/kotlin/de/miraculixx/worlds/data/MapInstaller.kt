package de.miraculixx.worlds.data

import com.mojang.serialization.Lifecycle
import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.commands.Commands
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.WorldLoader
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.util.Util
import net.minecraft.world.Difficulty
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.PrimaryLevelData
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Result of an install attempt. */
sealed interface InstallResult {
    data class Success(val saveFolder: String) : InstallResult
    data class Failure(val message: String) : InstallResult
}

/**
 * Downloads a map's world file and unpacks it into the `saves/` directory, then writes an
 * [InstalledMeta] marker so the map is recognised offline.
 */
object MapInstaller {

    /**
     * The archive is streamed to a temporary file next to `saves/`
     */
    fun install(entry: MapEntry): InstallResult {
        MapRepository.loadDetail(entry)
        val url = entry.downloadUrl
            ?: return InstallResult.Failure(I18n.get("worlds.install.no_file", entry.title))

        val gameDir = Minecraft.getInstance().gameDirectory.toPath()
        val savesDir = gameDir.resolve("saves")
        Files.createDirectories(savesDir)

        val archive = Files.createTempFile(gameDir, "worlds-download", ".zip")
        return try {
            if (!Http.download(url, archive)) InstallResult.Failure(I18n.get("worlds.install.download_failed", entry.title))
            else unpack(entry, archive, savesDir)
        } catch (e: Exception) {
            Constants.LOG.error("Install failed for {}", entry.title, e)
            InstallResult.Failure(I18n.get("worlds.install.write_failed", e.message.orEmpty()))
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    private fun unpack(entry: MapEntry, archive: Path, savesDir: Path): InstallResult {
        val zip = try {
            ZipFile(archive.toFile())
        } catch (e: Exception) {
            return InstallResult.Failure(I18n.get("worlds.install.invalid_archive", e.message.orEmpty()))
        }
        zip.use {
            val entries = Collections.list(zip.entries()).filter { !it.isDirectory }
            // Locate the world root by the shallowest level.dat
            val levelEntry = entries.map { it.name }
                .filter { it == "level.dat" || it.endsWith("/level.dat") }
                .minByOrNull { it.count { c -> c == '/' } }
                ?: return if (isDatapack(entries)) installDatapack(entry, archive, savesDir)
                else InstallResult.Failure(I18n.get("worlds.install.not_a_world", entry.title))
            val prefix = levelEntry.removeSuffix("level.dat") // "" or "world/" or "overrides/saves/world/"

            val target = uniqueFolder(savesDir, entry.title)
            try {
                extract(zip, entries, prefix, target)
                writeMarker(target, entry)
                downloadExternalPacks(target, entry)
            } catch (e: Exception) {
                Constants.LOG.error("Install failed for {}", entry.title, e)
                return InstallResult.Failure(I18n.get("worlds.install.write_failed", e.message.orEmpty()))
            }
            return InstallResult.Success(target.fileName.toString())
        }
    }

    /**
     * Stream every entry under [prefix] into [target]. A zip's declared entry sizes are written by
     * whoever built it, so the budget is spent against the bytes actually inflated.
     */
    private fun extract(zip: ZipFile, entries: List<ZipEntry>, prefix: String, target: Path) {
        var budget = Http.MAX_DOWNLOAD_BYTES
        for (zipEntry in entries) {
            if (!zipEntry.name.startsWith(prefix)) continue
            val relative = zipEntry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val dest = target.resolve(relative).normalize()
            if (!dest.startsWith(target)) continue // zip-slip guard
            Files.createDirectories(dest.parent)
            zip.getInputStream(zipEntry).use { input ->
                Files.newOutputStream(dest).use { out -> budget -= Http.copyCapped(input, out, budget) }
            }
        }
    }

    private fun isDatapack(entries: List<ZipEntry>) =
        entries.any { it.name == "pack.mcmeta" } && entries.any { it.name.startsWith("data/") }

    /**
     * The download is a world-generation datapack, not a world -> create new world
     * - random seed, no cheats, normal difficulty
     */
    private fun installDatapack(entry: MapEntry, archive: Path, savesDir: Path): InstallResult {
        val target = uniqueFolder(savesDir, entry.title)
        val packName = "${target.fileName}.zip"
        try {
            val packsDir = target.resolve("datapacks")
            Files.createDirectories(packsDir)
            Files.copy(archive, packsDir.resolve(packName))

            val dataConfig = WorldDataConfiguration(
                DataPackConfig(listOf("vanilla", "file/$packName"), emptyList()), FeatureFlags.DEFAULT_FLAGS
            )
            val settings = LevelSettings(
                entry.title,
                GameType.SURVIVAL,
                LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
                false,
                dataConfig,
            )
            Minecraft.getInstance().levelSource.createAccess(target.fileName.toString()).use { access ->
                writeWorldGenSettings(target, access, dataConfig)
                access.saveDataTag(PrimaryLevelData(settings, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable()))
            }
            downloadIcon(target, entry)
            writeMarker(target, entry)
            downloadExternalPacks(target, entry)
        } catch (e: Exception) {
            Constants.LOG.error("Datapack world creation failed for {}", entry.title, e)
            return InstallResult.Failure(I18n.get("worlds.install.create_failed", e.message.orEmpty()))
        }
        return InstallResult.Success(target.fileName.toString())
    }

    /**
     * Prepare a new random world with datapack context, for map creation
     */
    private fun writeWorldGenSettings(target: Path, access: LevelStorageSource.LevelStorageAccess, dataConfig: WorldDataConfiguration) {
        val packConfig = WorldLoader.PackConfig(ServerPacksSource.createPackRepository(access), dataConfig, false, false)
        WorldLoader.load(
            WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.GAMEMASTER),
            { context ->
                WorldLoader.DataLoadOutput(
                    WorldGenSettings(
                        WorldOptions.defaultWithRandomSeed(), WorldPresets.createNormalWorldDimensions(context.datapackWorldgen())
                    ),
                    context.datapackDimensions()
                )
            },
            { resources, _, registries, genSettings ->
                resources.close()
                val ops = registries.compositeAccess().createSerializationContext(NbtOps.INSTANCE)
                val root = CompoundTag()
                root.put("data", WorldGenSettings.CODEC.encodeStart(ops, genSettings).getOrThrow())
                NbtUtils.addCurrentDataVersion(root)
                val file = WorldGenSettings.TYPE.id().withSuffix(".dat").resolveAgainst(target.resolve("data"))
                Files.createDirectories(file.parent)
                NbtIo.writeCompressed(root, file)
            },
            Util.backgroundExecutor(),
            Minecraft.getInstance(),
        ).join()
    }

    /**
     * Use datapack projects icon as world icon, re-encodes image into 64x64 png if possible
     */
    private fun downloadIcon(target: Path, entry: MapEntry) {
        val bytes = entry.iconUrl?.let { Http.getBytes(it) } ?: return
        WorldEditor.writeIcon(target.resolve("icon.png"), bytes)
    }

    private fun writeMarker(target: Path, entry: MapEntry) {
        val meta = InstalledMeta(
            id = entry.id,
            source = entry.source,
            title = entry.title,
            // Both are the update check's comparison keys
            version = entry.version,
            updated = entry.dateEpoch,
            description = entry.description,
            readme = entry.readmeMarkdown,
            icon = entry.iconUrl,
            categories = entry.categories,
            downloads = entry.downloads,
            website = entry.website,
            trailer = entry.trailerUrl,
            requiredMods = entry.requiredMods,
            requiredPacks = entry.requiredPacks,
        )
        Files.writeString(target.resolve(InstalledMeta.FILE_NAME), Http.json.encodeToString(meta))
    }

    /**
     * Download every *external* required resource pack into the save's own `resourcepacks/` folder so [WorldResourcePacks]
     * can enable them on join. Unresolvable packs are just skipped
     */
    private fun downloadExternalPacks(target: Path, entry: MapEntry) {
        val external = entry.requiredPacks.filter { !it.included }
        if (external.isEmpty()) return
        val packsDir = target.resolve("resourcepacks")
        for (pack in external) {
            val url = pack.download?.takeIf { it.isNotBlank() }
                ?: pack.projectId?.let { MapRepository.resolveModrinthDownload(it) }
            if (url == null) {
                Constants.LOG.warn("No download URL for required pack '{}'", pack.name)
                continue
            }
            val dest = packsDir.resolve(packFilename(pack, url))
            try {
                Files.createDirectories(packsDir)
                if (!Http.download(url, dest)) {
                    Constants.LOG.warn("Failed to download required pack '{}' from {}", pack.name, url)
                    Files.deleteIfExists(dest)
                }
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
