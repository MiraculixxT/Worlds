package de.miraculixx.worlds.api

import de.miraculixx.worlds.data.MapRequirement
import de.miraculixx.worlds.data.RequirementKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format of the CurseForge proxy. See [WorldsApi] for the full contract. */
@Serializable
data class ApiSearchPage(
    /** Offset of the first hit, echoing the request. */
    val index: Int = 0,
    val limit: Int = 0,
    /** Total hits the query has, so the client knows whether another page exists. */
    val total: Int = 0,
    val hits: List<ApiMapEntry> = emptyList(),
)

@Serializable
data class ApiMapEntry(
    val id: String,
    val title: String = "",
    val description: String = "",
    val icon: String? = null,
    @SerialName("mc") val mcVersions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    /** Newest published version on CurseForge; drives the installed-world update hint. */
    val version: String? = null,
    val downloads: Long = 0,
    /** Last update, epoch millis; 0 when unknown (sorts last). */
    val updated: Long = 0,
    val website: String? = null,
    val trailer: String? = null,
)

@Serializable
data class ApiMapDetail(
    val readme: String? = null,
    /** Direct download URL of the world (or world-gen datapack) zip. */
    val download: String? = null,
    val website: String? = null,
    val trailer: String? = null,
    val gallery: List<String> = emptyList(),
    val requiredMods: List<ApiRequirement> = emptyList(),
    val requiredPacks: List<ApiRequirement> = emptyList(),
)

@Serializable
data class ApiRequirement(
    val name: String,
    /** Fabric mod id, for the missing-mod check */
    val modId: String? = null,
    /** Page to open from the Download button */
    val link: String? = null,
    /** Direct download URL. Required for a non-[included] resource pack */
    val download: String? = null,
    /** Resource pack shipping inside the world zip */
    val included: Boolean = false,
) {
    fun toRequirement(kind: RequirementKind) = MapRequirement(
        name = name,
        kind = kind,
        modId = modId,
        link = link,
        download = download,
        included = included && kind == RequirementKind.RESOURCE_PACK,
    )
}
