package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.server.DownloadedPackSource
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.FolderRepositorySource
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.validation.DirectoryValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.io.path.name

/**
 * Per-world resource packs. A map ships its packs inside `saves/<name>/resourcepacks/`.
 * [pushWorldPacks] (from `WorldOpenFlowsMixin`) pushes *every* pack in that folder
 * through the same [DownloadedPackSource].
 * Disabled packs are suffixed with ".disabled".
 * Pack library:`<gamedir>/resourcepacks`.
 */
object WorldResourcePacks {

    const val DISABLED_SUFFIX = ".disabled"
    private const val FILE_PREFIX = "file/"

    /** What `FolderRepositorySource` gives a pack it discovered. */
    private val SELECTION_CONFIG = PackSelectionConfig(false, Pack.Position.TOP, false)

    private fun savesDir(): Path =
        Minecraft.getInstance().gameDirectory.toPath().resolve("saves")

    /** The `resourcepacks/` folder inside an installed save. */
    fun packsDir(saveFolder: String): Path = savesDir().resolve(saveFolder).resolve("resourcepacks")

    /** MAP_RESOURCE_FILE is `<world>/resourcepacks/resources.zip`; its parent is the packs folder. */
    fun worldDir(access: LevelStorageSource.LevelStorageAccess): Path =
        access.getLevelPath(LevelResource.MAP_RESOURCE_FILE).parent

    fun libraryDir(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("resourcepacks")

    private fun packName(path: Path) = path.name.removeSuffix(DISABLED_SUFFIX)

    private fun isEnabled(path: Path) = !path.name.endsWith(DISABLED_SUFFIX)

    /** Like [PackFiles.isPack], but a renamed `.disabled` pack still counts as one. */
    private fun isPack(path: Path): Boolean =
        (Files.isRegularFile(path) && packName(path).endsWith(".zip", ignoreCase = true)) ||
            (Files.isDirectory(path) && Files.isRegularFile(path.resolve("pack.mcmeta")))

    private fun entries(dir: Path) = PackFiles.list(dir, ::isPack)

    fun listPackNames(saveFolder: String): List<String> =
        entries(packsDir(saveFolder)).filter(::isEnabled).map { it.name }.sorted()

    fun listRows(access: LevelStorageSource.LevelStorageAccess): List<PackRow> =
        entries(worldDir(access))
            .map { PackRow(it.name, packName(it), isEnabled(it), true) }
            .sortedBy { it.name.lowercase() }

    /** Rename the pack in place. Its [PackRow.id] is the file name it currently carries. */
    fun setEnabled(access: LevelStorageSource.LevelStorageAccess, row: PackRow, enabled: Boolean): Boolean {
        if (row.enabled == enabled) return true
        val dir = worldDir(access)
        return rename(dir.resolve(row.id), dir.resolve(if (enabled) row.name else row.name + DISABLED_SUFFIX))
    }

    fun deletePack(access: LevelStorageSource.LevelStorageAccess, row: PackRow): Boolean = try {
        PackFiles.delete(worldDir(access).resolve(row.id))
        true
    } catch (e: Exception) {
        Constants.LOG.warn("Could not delete resource pack {}: {}", row.id, e.message)
        false
    }

    /** The shared library plus the save's own folder */
    fun createRepository(access: LevelStorageSource.LevelStorageAccess): PackRepository {
        val validator = Minecraft.getInstance().directoryValidator()
        return PackRepository(
            FolderRepositorySource(libraryDir(), PackType.CLIENT_RESOURCES, PackSource.DEFAULT, validator),
            WorldPackSource(worldDir(access), access.parent().worldDirValidator),
        )
    }

    /** The ids [createRepository] mints for the packs the save currently loads. */
    fun selectedIds(access: LevelStorageSource.LevelStorageAccess): List<String> =
        entries(worldDir(access)).filter(::isEnabled).map { FILE_PREFIX + it.name }.sorted()

    /**
     * Adds packs from the lib and toggles active/disabled
     */
    fun apply(access: LevelStorageSource.LevelStorageAccess, repository: PackRepository) {
        val dir = worldDir(access)
        val selected = repository.selectedIds
            .filter { it.startsWith(FILE_PREFIX) }
            .map { it.removePrefix(FILE_PREFIX) }
            .toSet()

        val present = entries(dir).associateBy { packName(it) }
        importPacks(dir, selected - present.keys)
        for ((name, path) in present) {
            val wanted = name in selected
            if (isEnabled(path) == wanted) continue
            rename(path, dir.resolve(if (wanted) name else name + DISABLED_SUFFIX))
        }
    }

    private fun importPacks(worldDir: Path, wanted: Set<String>) {
        if (wanted.isEmpty()) return
        val library = libraryDir()
        try {
            Files.createDirectories(worldDir)
        } catch (e: Exception) {
            Constants.LOG.error("Could not prepare {}", worldDir, e)
            return
        }
        for (name in wanted) {
            val source = library.resolve(name)
            if (!Files.exists(source)) {
                Constants.LOG.warn("Resource pack {} vanished from {}", name, library)
                continue
            }
            try {
                PackFiles.copy(source, worldDir.resolve(name))
            } catch (e: Exception) {
                Constants.LOG.warn("Could not copy resource pack {}: {}", name, e.message)
            }
        }
    }

    private fun rename(from: Path, to: Path): Boolean = try {
        Files.move(from, to)
        true
    } catch (e: Exception) {
        Constants.LOG.warn("Could not rename {} to {}: {}", from, to.name, e.message)
        false
    }

    /**
     * The save's own folder as a picker source.
     */
    private class WorldPackSource(
        private val folder: Path,
        private val validator: DirectoryValidator,
    ) : RepositorySource {
        override fun loadPacks(result: Consumer<Pack>) {
            if (!Files.isDirectory(folder)) return
            try {
                FolderRepositorySource.discoverPacks(folder, validator) { path, resources ->
                    val name = path.name
                    if (!name.endsWith(DISABLED_SUFFIX)) {
                        val info = PackLocationInfo(
                            FILE_PREFIX + name, Component.literal(name), PackSource.WORLD, Optional.empty(),
                        )
                        Pack.readMetaAndCreate(info, resources, PackType.CLIENT_RESOURCES, SELECTION_CONFIG)
                            ?.let(result::accept)
                    }
                }
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to list resource packs in {}: {}", folder, e.message)
            }
        }
    }

    /**
     * Push every enabled pack in the opening world's `resourcepacks/` folder onto [packSource].
     * Only the last is checked in vanilla style.
     */
    fun pushWorldPacks(
        packSource: DownloadedPackSource,
        access: LevelStorageSource.LevelStorageAccess,
    ): CompletableFuture<Void> {
        val dir = worldDir(access)
        val packs = entries(dir).filter(::isEnabled).sorted()
        if (packs.isEmpty()) return CompletableFuture.completedFuture(null)

        packSource.configureForLocalWorld()
        // Push all but the last untracked, then track the final pack as the whole-batch signal.
        for (i in 0 until packs.size - 1) packSource.pushLocalPack(UUID.randomUUID(), packs[i])
        val lastId = UUID.randomUUID()
        val feedback = packSource.waitForPackFeedback(lastId)
        packSource.pushLocalPack(lastId, packs.last())

        Constants.LOG.info("Pushed {} world resource pack(s) from {}", packs.size, dir)
        return feedback
    }
}
