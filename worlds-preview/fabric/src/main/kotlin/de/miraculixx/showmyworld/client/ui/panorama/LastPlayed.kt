package de.miraculixx.showmyworld.client.ui.panorama

import de.miraculixx.common.Loader
import de.miraculixx.showmyworld.Constants
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class LastPlayedKind {
    @SerialName("world") WORLD,
    @SerialName("server") SERVER,
}

@Serializable
data class LastPlayedRecord(val kind: LastPlayedKind, val id: String) {
    /** `id` is the save folder for a world and the sanitized address for a server, see [PanoramaRoots] */
    val root: Path
        get() = when (kind) {
            LastPlayedKind.WORLD -> PanoramaRoots.world(id)
            LastPlayedKind.SERVER -> PanoramaRoots.servers().resolve(id)
        }
}

/**
 * What was left last, written by [PanoramaCapture] on every leave
 */
object LastPlayed {
    private val file = Loader.configDir.resolve("${Constants.MOD_ID}/last_played.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private var record: LastPlayedRecord? = null
    private var loaded = false

    @Synchronized
    fun get(): LastPlayedRecord? {
        if (!loaded) {
            loaded = true
            record = runCatching { json.decodeFromString<LastPlayedRecord>(file.readText()) }
                .onFailure { if (it !is NoSuchFileException) Constants.LOG.warn("Failed to read $file", it) }
                .getOrNull()
        }
        return record
    }

    @Synchronized
    fun set(value: LastPlayedRecord) {
        record = value
        loaded = true
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(value))
        }.onFailure { Constants.LOG.warn("Failed to write $file", it) }
    }
}
