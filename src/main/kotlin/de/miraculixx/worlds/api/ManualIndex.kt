package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.data.MapRequirement
import de.miraculixx.worlds.data.RequirementKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Maps from "other" sources that are too fancy to provide an API
 *
 * ```json
 * [
 *   {
 *     "id": "the-dropper",
 *     "name": "The Dropper",
 *     "description": "One-line summary shown on the row and in the detail header.",
 *     "readme": "# The Dropper\n\nFull markdown…",
 *     "icon": "https://example.com/icon.png",
 *     "download": "https://example.com/the-dropper-1.4.zip",
 *     "version": "1.4",
 *     "mc": ["26.2"],
 *     "categories": ["parkour"],
 *     "website": "https://example.com/dropper",
 *     "trailer": "https://www.youtube.com/watch?v=xxxxxxxxxxx",
 *     "requiredMods": [{ "name": "Fabric API", "id": "fabric-api", "link": "https://…" }],
 *     "requiredPacks": [{ "name": "Dropper Textures", "download": "https://…/pack.zip" }]
 *   }
 * ]
 * ```
 *
 * - [id] must stay stable
 * - [download] must be a **direct** link to the world (or world-gen datapack) zip
 * - [version] is compared by equality only and drives the update pill on installed worlds
 * - [mcVersions] feeds the version filter and the row's `Version:` label.
 */
@Serializable
data class ManualMapEntry(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val readme: String? = null,
    val icon: String? = null,
    val download: String? = null,
    val version: String? = null,
    @SerialName("mc") val mcVersions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val website: String? = null,
    val trailer: String? = null,
    val requiredMods: List<ManualRequirement> = emptyList(),
    val requiredPacks: List<ManualRequirement> = emptyList(),
)

@Serializable
data class ManualRequirement(
    val name: String,
    /** Fabric mod id, for the missing-mod check. */
    val id: String? = null,
    val link: String? = null,
    val download: String? = null,
    /** For resource packs: bundled inside the map download */
    val included: Boolean = false,
) {
    fun toRequirement(kind: RequirementKind) = MapRequirement(
        name = name,
        kind = kind,
        modId = id,
        link = link,
        download = download,
        included = included && kind == RequirementKind.RESOURCE_PACK,
    )
}

object ManualIndex {
    val entries: List<ManualMapEntry> by lazy { read() }

    private fun read(): List<ManualMapEntry> {
        val body = ManualIndex::class.java.getResourceAsStream(Constants.MANUAL_INDEX)?.use {
            it.readAllBytes().decodeToString()
        }
        if (body == null) {
            Constants.LOG.warn("Bundled map list {} is missing", Constants.MANUAL_INDEX)
            return emptyList()
        }
        return Http.decode<List<ManualMapEntry>>(body) ?: emptyList()
    }
}
