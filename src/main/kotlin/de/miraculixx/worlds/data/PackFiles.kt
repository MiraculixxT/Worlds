package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * One line of an editor pack list, shared by [WorldDataPacks] and [WorldResourcePacks].
 */
data class PackRow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val deletable: Boolean,
    val group: Int = 0,
)

/** Pack folder plumbing both pack tabs need. */
object PackFiles {

    /** A `.zip` file or a folder holding a `pack.mcmeta`; anything else we don't care */
    fun isPack(path: Path): Boolean =
        (Files.isRegularFile(path) && path.name.endsWith(".zip", ignoreCase = true)) ||
            (Files.isDirectory(path) && Files.isRegularFile(path.resolve("pack.mcmeta")))

    fun list(dir: Path, filter: (Path) -> Boolean = ::isPack): List<Path> = try {
        if (!Files.isDirectory(dir)) emptyList()
        else Files.newDirectoryStream(dir).use { it.filter(filter) }
    } catch (e: Exception) {
        Constants.LOG.warn("Could not list {}: {}", dir, e.message)
        emptyList()
    }

    fun copy(source: Path, dest: Path) {
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

    fun delete(path: Path) {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
