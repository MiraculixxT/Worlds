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
)

/**
 * A browsable/installable map, merged from Modrinth search hits and the GitHub manual index.
 * Heavy fields ([readmeMarkdown], [downloadUrl], requirements, [gallery]) are filled lazily when
 * the entry is first opened in the detail panel — see MapRepository#loadDetail.
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
    var website: String? = null,
    var sourceUrl: String? = null,
) {
    @Volatile var detailLoaded: Boolean = false
    @Volatile var readmeMarkdown: String? = null
    @Volatile var downloadUrl: String? = null
    @Volatile var gallery: List<String> = emptyList()
    @Volatile var requiredMods: List<MapRequirement> = emptyList()
    @Volatile var requiredPacks: List<MapRequirement> = emptyList()

    /** Non-null when this entry represents an already-installed save (Installed tab). */
    @Volatile var installedFolder: String? = null

    /** Theme label shown in the list — first category, if any. */
    val theme: String? get() = categories.firstOrNull()
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
    val icon: String? = null,
    val website: String? = null,
    val requiredMods: List<MapRequirement> = emptyList(),
    val requiredPacks: List<MapRequirement> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val FILE_NAME = "worlds.meta.json"
    }
}

/** An installed map discovered by scanning the saves directory. */
data class InstalledMap(
    val saveFolder: String,
    val meta: InstalledMeta,
)
