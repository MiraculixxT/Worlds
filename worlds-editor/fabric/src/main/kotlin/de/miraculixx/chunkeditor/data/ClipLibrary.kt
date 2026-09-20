package de.miraculixx.chunkeditor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.world.level.ChunkPos
import java.nio.file.Path

data class ClipEntry(
    val name: String,
    val dimension: String,
    val mcVersion: String,
    val chunks: Int,
    val bytes: Long,
    val modified: Long,
)

data class SelectionEntry(
    val name: String,
    val chunks: Int,
    val inverted: Boolean,
    val bytes: Long,
    val modified: Long,
)

data class ClipFootprint(val origin: ChunkPos, val chunks: List<ChunkPos>)

sealed interface ClipExportResult {
    data class Success(val name: String, val chunks: Int) : ClipExportResult
    data class Failure(val message: String) : ClipExportResult
}

/**
 * The clip and selection library (local or remote)
 */
interface ClipLibrary {

    suspend fun clips(): List<ClipEntry>

    suspend fun selections(): List<SelectionEntry>

    suspend fun footprint(name: String): ClipFootprint?

    suspend fun selection(name: String): ParsedSelection?

    suspend fun deleteClip(name: String): Boolean

    suspend fun deleteSelection(name: String): Boolean

    /** Null when the library is remote */
    val folder: Path?

    val selectionFolder: Path?
}

/** `<gamedir>/chunkclips` */
object LocalLibrary : ClipLibrary {

    override val folder: Path get() = ChunkClips.libraryDir()

    override val selectionFolder: Path get() = SelectionCsv.dir()

    override suspend fun clips(): List<ClipEntry> = withContext(Dispatchers.IO) {
        ChunkClips.list().map { it.entry() }
    }

    override suspend fun selections(): List<SelectionEntry> = withContext(Dispatchers.IO) {
        SelectionCsv.list().map { SelectionEntry(it.name, it.chunks, it.inverted, it.bytes, it.modified) }
    }

    override suspend fun footprint(name: String): ClipFootprint? = withContext(Dispatchers.IO) {
        read(name)?.let { ClipFootprint(it.origin, it.chunks) }
    }

    override suspend fun selection(name: String): ParsedSelection? = withContext(Dispatchers.IO) {
        SelectionCsv.read(SelectionCsv.dir().resolve("$name.csv"))
    }

    override suspend fun deleteClip(name: String): Boolean = withContext(Dispatchers.IO) {
        read(name)?.let { ChunkClips.delete(it) } == true
    }

    override suspend fun deleteSelection(name: String): Boolean = withContext(Dispatchers.IO) {
        SelectionCsv.list().firstOrNull { it.name == name }?.let { SelectionCsv.delete(it) } == true
    }

    /** By name, and only ever inside the library */
    fun read(name: String): ClipInfo? {
        if (!safe(name)) return null
        val dir = ChunkClips.libraryDir().resolve(name).normalize()
        if (!dir.startsWith(ChunkClips.libraryDir().normalize())) return null
        return ChunkClips.read(dir)
    }

    fun safe(name: String): Boolean = name.isNotBlank() && name == ChunkClips.sanitize(name)

    private fun ClipInfo.entry() = ClipEntry(name, dimension, mcVersion, chunks.size, bytes, modified)
}
