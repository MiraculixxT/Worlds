package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.world.level.ChunkPos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** The `.csv` in the selection folder */
class SelectionFile(val path: Path, val chunks: Int, val inverted: Boolean, val bytes: Long, val modified: Long) {
    val name: String get() = path.nameWithoutExtension
}

/** @param [inverted] true: listed chunks are the deselected ones */
class ParsedSelection(val chunks: Set<Long>, val inverted: Boolean)

/**
 * The selection itself, in MCA Selector's CSV format (interopt-ability)
 *
 * - a first line reading `inverted` flips the meaning of everything below it,
 * - `5;-3` is the whole region 5, -3,
 * - `0;-2;3;-36` is the single chunk 3, -36 in region 0, -2.
 *
 * Path: `<gamedir>/chunkclips/_selections`
 */
object SelectionCsv {

    const val FOLDER = "_selections"
    private const val EXTENSION = "csv"
    private const val INVERTED = "inverted"

    fun dir(): Path = ChunkClips.libraryDir().resolve(FOLDER)

    fun list(): List<SelectionFile> {
        val dir = dir()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension.equals(EXTENSION, true) }
                .mapNotNull { describe(it) }
        }.sortedByDescending { it.modified }
    }

    private fun describe(path: Path): SelectionFile? = runCatching {
        val parsed = read(path) ?: return null
        SelectionFile(path, parsed.chunks.size, parsed.inverted, Files.size(path), Files.getLastModifiedTime(path).toMillis())
    }.onFailure { Constants.LOG.warn("Unreadable selection {}: {}", path.name, it.message) }.getOrNull()

    /** Whole regions collapse to one line, the way MCA Selector writes them */
    fun write(name: String, chunks: Collection<ChunkPos>, inverted: Boolean = false): Path {
        val dir = dir()
        Files.createDirectories(dir)
        val byRegion = chunks.groupBy { it.regionX to it.regionZ }
        val lines = ArrayList<String>(chunks.size + 1)
        if (inverted) lines.add(INVERTED)
        byRegion.entries.sortedWith(compareBy({ it.key.second }, { it.key.first })).forEach { (region, inRegion) ->
            val (rx, rz) = region
            if (inRegion.size == REGION_SIZE * REGION_SIZE) {
                lines.add("$rx;$rz")
            } else {
                inRegion.sortedWith(compareBy({ it.z }, { it.x })).forEach { lines.add("$rx;$rz;${it.x};${it.z}") }
            }
        }
        val file = freeFile(dir, name)
        Files.write(file, lines)
        return file
    }

    /** @return null when invalid/empty (bad lines are skipped) */
    fun read(path: Path): ParsedSelection? = runCatching {
        val chunks = HashSet<Long>()
        var inverted = false
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (index == 0 && line.equals(INVERTED, true)) {
                inverted = true
                return@forEachIndexed
            }
            val parts = line.split(';').map { it.trim() }
            val numbers = parts.mapNotNull { it.toIntOrNull() }
            if (numbers.size != parts.size) {
                Constants.LOG.warn("Skipping selection line '{}' in {}", line, path.name)
                return@forEachIndexed
            }
            when (numbers.size) {
                2 -> forEachChunkOfRegion(numbers[0], numbers[1]) { chunks.add(it) }
                4 -> chunks.add(ChunkPos.pack(numbers[2], numbers[3]))
                else -> Constants.LOG.warn("Skipping selection line '{}' in {}", line, path.name)
            }
        }
        ParsedSelection(chunks, inverted)
    }.onFailure { Constants.LOG.warn("Failed to read selection {}: {}", path.name, it.message) }.getOrNull()

    fun delete(file: SelectionFile): Boolean = runCatching { Files.deleteIfExists(file.path) }
        .onFailure { Constants.LOG.warn("Failed to delete selection {}: {}", file.name, it.message) }
        .getOrDefault(false)

    private inline fun forEachChunkOfRegion(rx: Int, rz: Int, action: (Long) -> Unit) {
        for (z in 0 until REGION_SIZE) {
            for (x in 0 until REGION_SIZE) action(ChunkPos.pack(rx * REGION_SIZE + x, rz * REGION_SIZE + z))
        }
    }

    private fun freeFile(dir: Path, name: String): Path {
        val base = ChunkClips.sanitize(name)
        var candidate = dir.resolve("$base.$EXTENSION")
        var i = 2
        while (Files.exists(candidate)) candidate = dir.resolve("$base-${i++}.$EXTENSION")
        return candidate
    }
}
