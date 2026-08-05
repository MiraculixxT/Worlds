package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.FolderRepositorySource
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * DataPack utilities.
 *
 * Any edits through the vanilla picker only can *add*, never remove.
 * The datapack store lives in `<instance>/datapacks/`, sometimes used by Modrinth and other launchers?
 */
object WorldDataPacks {

    private const val FILE_PREFIX = "file/"
    const val VANILLA = "vanilla"

    /** One line of the editor's data pack list. [VANILLA] is [locked] */
    data class PackRow(val id: String, val name: String, val enabled: Boolean) {
        val locked: Boolean get() = id == VANILLA
    }

    private fun isManaged(id: String) = id == VANILLA || id.startsWith(FILE_PREFIX)

    fun libraryDir(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("datapacks")

    fun worldDir(access: LevelStorageSource.LevelStorageAccess): Path =
        access.getLevelPath(LevelResource.DATAPACK_DIR)

    /**
     * Built-ins + the shared lib + the world's own folder.
     */
    fun createRepository(access: LevelStorageSource.LevelStorageAccess): PackRepository {
        val validator = Minecraft.getInstance().directoryValidator()
        return PackRepository(
            ServerPacksSource(validator),
            FolderRepositorySource(libraryDir(), PackType.SERVER_DATA, PackSource.WORLD, validator),
            FolderRepositorySource(
                worldDir(access), PackType.SERVER_DATA, PackSource.WORLD, access.parent().worldDirValidator,
            ),
        )
    }

    /**
     * Ordered the way `level.dat` enables them (later = higher prio) with anything it does not name appended alphabetically.
     */
    fun listRows(access: LevelStorageSource.LevelStorageAccess): List<PackRow> {
        val order = WorldEditor.readEnabledPacks(access)
        val disabled = WorldEditor.readDisabledPacks(access).toSet()
        val names = folderPacks(worldDir(access)).map { it.name }.sortedWith(
            compareBy({ order.indexOf(FILE_PREFIX + it).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }, { it })
        )
        return listOf(PackRow(VANILLA, VANILLA, true)) +
            names.map { PackRow(FILE_PREFIX + it, it, FILE_PREFIX + it !in disabled) }
    }

    fun writeRows(access: LevelStorageSource.LevelStorageAccess, rows: List<PackRow>) {
        val foreign = WorldEditor.readEnabledPacks(access).filterNot(::isManaged)
        val (base, rest) = rows.filter { it.enabled }.map { it.id }.partition { it == VANILLA }
        WorldEditor.setPackLists(
            access,
            base + foreign + rest,
            WorldEditor.readDisabledPacks(access).filterNot(::isManaged) +
                rows.filterNot { it.enabled }.map { it.id },
        )
    }

    /** Delete a pack from the save. */
    fun deletePack(access: LevelStorageSource.LevelStorageAccess, row: PackRow): Boolean {
        if (row.locked) return false
        return try {
            deleteTree(worldDir(access).resolve(row.name))
            true
        } catch (e: Exception) {
            Constants.LOG.warn("Could not delete data pack {}: {}", row.name, e.message)
            false
        }
    }

    /**
     * Copy in whatever [repository]'s selection added and record the selection in `level.dat`.
     * Nothing is removed, only added! Packs in the save that are moved out just get disabled.
     */
    fun apply(access: LevelStorageSource.LevelStorageAccess, repository: PackRepository) {
        val dir = worldDir(access)
        val selected = repository.selectedIds.toList()
        val selectedNames = selected.filter { it.startsWith(FILE_PREFIX) }.map { it.removePrefix(FILE_PREFIX) }
        importPacks(dir, selectedNames)

        val present = folderPacks(dir).map { it.name }
        val known = repository.availableIds.toSet()
        val foreign = WorldEditor.readEnabledPacks(access).filterNot { isManaged(it) || it in known }
        val (base, others) = selected.partition { it == VANILLA }
        val enabled = others.filter { !it.startsWith(FILE_PREFIX) || it.removePrefix(FILE_PREFIX) in present }
        WorldEditor.setPackLists(
            access,
            base + foreign + enabled,
            WorldEditor.readDisabledPacks(access).filterNot(::isManaged) +
                present.filterNot { it in selectedNames }.sorted().map { FILE_PREFIX + it },
        )
    }

    private fun isPack(p: Path): Boolean =
        (Files.isRegularFile(p) && p.name.endsWith(".zip", ignoreCase = true)) ||
            (Files.isDirectory(p) && Files.isRegularFile(p.resolve("pack.mcmeta")))

    private fun folderPacks(dir: Path): List<Path> = try {
        if (!Files.isDirectory(dir)) emptyList()
        else Files.newDirectoryStream(dir).use { it.filter(::isPack) }
    } catch (e: Exception) {
        Constants.LOG.warn("Could not list {}: {}", dir, e.message)
        emptyList()
    }

    private fun importPacks(worldDir: Path, wanted: List<String>) {
        val library = libraryDir()
        try {
            Files.createDirectories(worldDir)
            Files.createDirectories(library)
        } catch (e: Exception) {
            Constants.LOG.error("Could not prepare data pack folders", e)
            return
        }

        val present = folderPacks(worldDir).map { it.name }.toSet()
        for (name in wanted) {
            if (name in present) continue
            val source = library.resolve(name)
            if (!Files.exists(source)) {
                Constants.LOG.warn("Data pack {} vanished from {}", name, library)
                continue
            }
            try {
                copyTree(source, worldDir.resolve(name))
            } catch (e: Exception) {
                Constants.LOG.warn("Could not copy data pack {}: {}", name, e.message)
            }
        }
    }

    private fun copyTree(source: Path, dest: Path) {
        if (!Files.isDirectory(source)) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val target = dest.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
