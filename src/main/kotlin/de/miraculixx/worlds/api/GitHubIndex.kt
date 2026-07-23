package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Curated map index hosted as a JSON array in the GitHub repo. Each entry is either a pointer to
 * a Modrinth project (`source = "modrinth"`, only `slug` required) or a fully inline manual map
 * (`source = "manual"`).
 */
@Serializable
data class GhMapEntry(
    val source: String = "manual",
    val slug: String? = null,

    // Manual-map fields:
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val readme: String? = null,
    val icon: String? = null,
    val download: String? = null,
    @SerialName("mc") val mcVersions: List<String> = emptyList(),
    val theme: String? = null,
    val categories: List<String> = emptyList(),
    val website: String? = null,
    val requiredMods: List<GhRequirement> = emptyList(),
    val requiredPacks: List<GhRequirement> = emptyList(),
)

@Serializable
data class GhRequirement(
    val name: String,
    val id: String? = null,
    val link: String? = null,
    val download: String? = null,
)

object GitHubIndex {
    /** Fetch and parse the manual index. Returns an empty list if unreachable or malformed. */
    fun fetch(url: String = Constants.MAPS_JSON_URL): List<GhMapEntry> =
        Http.decode<List<GhMapEntry>>(Http.getString(url)) ?: emptyList()
}
