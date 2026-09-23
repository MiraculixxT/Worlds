package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class EditorSettings(
    /** Blank falls back to `<gamedir>/chunkclips` */
    @SerialName("library_dir") var libraryDir: String = "",
    /** Opens the remote editor without the live/local warning */
    @SerialName("skip_open_warning") var skipOpenWarning: Boolean = false,
    @SerialName("chunk_info") var chunkInfo: List<String> = ChunkFact.DEFAULTS.map { it.name },
)

/**
 * `config/chunkeditor/settings.json` read on both sides (server ignores irrelevant)
 */
object EditorConfig {
    private val file = Loader.configDir.resolve("${Constants.MOD_ID}/settings.json")

    private val json = Json {
        prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true
    }

    val settings: EditorSettings by lazy {
        runCatching { json.decodeFromString<EditorSettings>(file.readText()) }
            .onFailure { if (it !is java.nio.file.NoSuchFileException) Constants.LOG.warn("Failed to read $file, using defaults", it) }
            .getOrDefault(EditorSettings())
    }

    fun chunkFacts(): Set<ChunkFact> = ChunkFact.of(settings.chunkInfo)

    val defaultLibraryDir: Path get() = Loader.gameDir.resolve("chunkclips")

    /** Falls back to the default whenever the configured path is blank or not a usable folder */
    fun libraryDir(): Path {
        val configured = settings.libraryDir.trim()
        if (configured.isEmpty()) return defaultLibraryDir
        val path = runCatching { Path.of(configured) }.getOrNull() ?: return defaultLibraryDir
        return if (Files.isDirectory(path)) path else defaultLibraryDir
    }

    /** Creates the folder and writes a probe file (check for sandboxes) */
    fun writable(path: Path): Boolean = runCatching {
        Files.createDirectories(path)
        val probe = Files.createTempFile(path, ".chunkeditor", ".tmp")
        Files.deleteIfExists(probe)
        true
    }.onFailure { Constants.LOG.warn("Library folder {} is not writable: {}", path, it.message) }.getOrDefault(false)

    @Synchronized
    fun save() {
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(settings))
        }.onFailure { Constants.LOG.warn("Failed to write $file", it) }
    }
}
