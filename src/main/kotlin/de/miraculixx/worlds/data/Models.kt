package de.miraculixx.worlds.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MapSource { MODRINTH, MANUAL }

enum class RequirementKind { MOD, RESOURCE_PACK }

/** A required mod or resource pack for a map. */
@Serializable
data class MapRequirement(
    val name: String,
    val kind: RequirementKind,
    @SerialName("project_id") val projectId: String? = null,
    /** Mod id (as declared in fabric.mod.json) used for the installed-mod check. */
    val modId: String? = null,
    val link: String? = null,
    val download: String? = null,
    /**
     * True when the resource pack ships *inside* the map download (Modrinth `embedded` dep or manual
     * `included: true`). No separate download or pack-toggle is needed — the entry is kept only to
     * mark that the map uses a resource pack.
     */
    val included: Boolean = false,
)

/**
 * A browsable/installable map, merged from Modrinth search hits and the GitHub manual index.
 * Heavy fields ([readmeMarkdown], [downloadUrl], requirements, [gallery]) are filled lazily when
 * the entry is first opened in the detail panel
 * @see MapRepository.loadDetail
 */
class MapEntry(
    val id: String,
    val source: MapSource,
    val slug: String?,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val mcVersions: List<String>,
    val categories: List<String>,
    /** Total downloads (Modrinth). Any other sources (manual, GH-external) have 0 */
    val downloads: Long = 0,
    /** Last-updated/played epoch millis. Any other sources have 0 */
    val dateEpoch: Long = 0,
    var website: String? = null,
    var sourceUrl: String? = null,
    var trailerUrl: String? = null,
) {
    @Volatile var detailLoaded: Boolean = false
    @Volatile var readmeMarkdown: String? = null
    @Volatile var downloadUrl: String? = null
    @Volatile var gallery: List<String> = emptyList()
    @Volatile var requiredMods: List<MapRequirement> = emptyList()
    @Volatile var requiredPacks: List<MapRequirement> = emptyList()

    @Volatile var installedFolder: String? = null
}

/**
 * Persisted marker written into a save folder as `worlds.meta.json` on install, so the Installed
 * tab and the join-time hooks (pack toggle / mod check) work fully offline.
 */
@Serializable
data class InstalledMeta(
    val id: String,
    val source: MapSource,
    val title: String,
    val description: String? = null,
    val icon: String? = null,
    val categories: List<String> = emptyList(),
    /** Listing download count at install time */
    val downloads: Long = 0,
    val website: String? = null,
    val trailer: String? = null,
    val requiredMods: List<MapRequirement> = emptyList(),
    val requiredPacks: List<MapRequirement> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val FILE_NAME = "worlds.meta.json"
    }
}

/** A world discovered by scanning the saves directory.
 * Manual created/added worlds get tagged as well with data from the level.dat
 */
data class InstalledMap(
    val saveFolder: String,
    /**
     * The `worlds.meta.json` marker, or `null` for a world this mod did not install (created
     * in-game, or dropped into `saves/` by hand). Those are tagged [MANUAL_CATEGORY] instead.
     */
    val meta: InstalledMeta?,
    /**
     * Absolute path to the save's own `icon.png` when present. Preferred over [InstalledMeta.icon]
     */
    val localIcon: String? = null,
    val levelName: String = saveFolder,
    val mcVersion: String? = null,
    val lastPlayed: Long = 0,
) {
    /** Display title: the map's own title for managed worlds, else the world's name. */
    val title: String get() = meta?.title ?: levelName

    companion object {
        /** Pseudo-category marking a world this mod does not manage (no `worlds.meta.json`). */
        const val MANUAL_CATEGORY = "manual"
    }
}
