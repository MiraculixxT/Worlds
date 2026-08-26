package de.miraculixx.showmyworld.client

import de.miraculixx.showmyworld.Constants
import de.miraculixx.showmyworld.client.ui.panorama.DefaultPanorama
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import de.miraculixx.common.Loader
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class PreviewSettings(
    var show: Boolean = true,
    @SerialName("auto_create") var autoCreate: Boolean = true,
    var fade: Long = 250,
    var default: DefaultPanorama = DefaultPanorama.VANILLA,
)

object PreviewConfig {
    private val file = Loader.configDir.resolve("${Constants.MOD_ID}/settings.json")

    /** `coerceInputValues`: an enum name this build no longer knows falls back to the default. */
    private val json = Json {
        prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true
    }

    val settings: PreviewSettings by lazy {
        runCatching { json.decodeFromString<PreviewSettings>(file.readText()) }
            .onFailure { if (it !is java.nio.file.NoSuchFileException) Constants.LOG.warn("Failed to read $file, using defaults", it) }
            .getOrDefault(PreviewSettings())
    }

    @Synchronized
    fun save() {
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(settings))
        }.onFailure { Constants.LOG.warn("Failed to write $file", it) }
    }
}
