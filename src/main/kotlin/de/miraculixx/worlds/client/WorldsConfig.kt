package de.miraculixx.worlds.client

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.client.ui.SortMode
import de.miraculixx.worlds.client.ui.VersionMode
import de.miraculixx.worlds.data.MapSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Filter + sort selection of one tab, persisted per tab. */
@Serializable
class FilterSettings {
    var category: String? = null
    var version = VersionMode.ALL
    var sort = SortMode.AZ
    var reverse = false

    val isActive: Boolean
        get() = category != null || version != VersionMode.ALL || sort != SortMode.AZ || reverse
}

@Serializable
data class WorldsSettings(
    /** Left pane share of the split view */
    var ratio: Float = 0.42f,
    /** Browse source the switcher last stood on */
    var browseSource: MapSource = MapSource.MODRINTH,
    val installedFilter: FilterSettings = FilterSettings(),
    val browseFilter: FilterSettings = FilterSettings(),
)

object WorldsConfig {
    private val file = FabricLoader.getInstance().configDir.resolve("worlds/settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: WorldsSettings by lazy {
        runCatching { json.decodeFromString<WorldsSettings>(file.readText()) }
            .onFailure { if (it !is java.nio.file.NoSuchFileException) Constants.LOG.warn("Failed to read $file, using defaults", it) }
            .getOrDefault(WorldsSettings())
    }

    @Synchronized
    fun save() {
        runCatching {
            file.createParentDirectories()
            file.writeText(json.encodeToString(settings))
        }.onFailure { Constants.LOG.warn("Failed to write $file", it) }
    }
}