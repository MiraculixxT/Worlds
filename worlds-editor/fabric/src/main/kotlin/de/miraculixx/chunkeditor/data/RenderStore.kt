package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/**
 * Rendered regions on disk, valid until region change (height cuts are never cached).
 * One folder per dimension, one file per region and step ([FORMAT], stamp, deflated pixels body)
 */
class RenderStore(val root: Path) {

    fun read(dimension: WorldDimension, rx: Int, rz: Int, step: Int): Pair<Long, ByteArray>? {
        val file = file(dimension, rx, rz, step)
        if (!Files.isRegularFile(file)) return null
        return try {
            DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readInt() != FORMAT) return null
                input.readLong() to input.readAllBytes()
            }
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read cached render {}: {}", file, e.message)
            null
        }
    }

    fun write(dimension: WorldDimension, rx: Int, rz: Int, step: Int, stamp: Long, packed: ByteArray) {
        val file = file(dimension, rx, rz, step)
        var temp: Path? = null
        try {
            file.parent.createDirectories()
            temp = Files.createTempFile(file.parent, file.name, ".tmp")
            DataOutputStream(Files.newOutputStream(temp).buffered()).use {
                it.writeInt(FORMAT)
                it.writeLong(stamp)
                it.write(packed)
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            temp = null
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to cache render {}: {}", file, e.message)
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Drops the folder of every dimension that is not in [dimensions] anymore */
    fun prune(dimensions: Collection<WorldDimension>) {
        if (!Files.isDirectory(root)) return
        val keep = dimensions.mapTo(HashSet(), ::folder)
        val gone = Files.list(root).use { list -> list.filter { Files.isDirectory(it) && it.name !in keep }.toList() }
        gone.forEach {
            delete(it)
            Constants.LOG.info("render cache of removed dimension {} dropped", it.name)
        }
    }

    private fun file(dimension: WorldDimension, rx: Int, rz: Int, step: Int): Path =
        root.resolve(folder(dimension)).resolve("r.$rx.$rz.$step.bin")

    companion object {
        private const val FORMAT = 1

        /** Server caches in `worlds/<save folder>`, clients in `servers/<address>/<world>` */
        val ROOT: Path get() = Loader.gameDir.resolve("${Constants.MOD_ID}/cache")

        fun world(folder: String) = RenderStore(ROOT.resolve("worlds").resolve(safe(folder)))

        fun server(address: String, world: String) =
            RenderStore(ROOT.resolve("servers").resolve(safe(address)).resolve(safe(world)))

        /** Always holds the `~` for the namespace colon, so it can never be `.` or `..` */
        private fun folder(dimension: WorldDimension): String =
            dimension.key.identifier().toString().replace(':', '~').replace('/', '~')

        /** Peer sent names too, so nothing but one plain path segment survives */
        private fun safe(name: String): String =
            name.trim().lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .takeUnless { it.isEmpty() || it.all { c -> c == '.' } } ?: "_"

        fun size(dir: Path = ROOT): Long {
            if (!Files.isDirectory(dir)) return 0
            return Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum()
            }
        }

        fun delete(dir: Path = ROOT) {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
        }
    }
}
