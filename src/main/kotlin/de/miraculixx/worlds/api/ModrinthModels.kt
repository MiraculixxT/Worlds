package de.miraculixx.worlds.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of GET /v2/search. */
@Serializable
data class MrSearchResponse(
    val hits: List<MrSearchHit> = emptyList(),
    @SerialName("total_hits") val totalHits: Int = 0,
)

/** A single search hit. Gallery here is a list of raw image URLs. */
@Serializable
data class MrSearchHit(
    @SerialName("project_id") val projectId: String,
    val slug: String? = null,
    val title: String = "",
    val description: String = "",
    val author: String? = null,
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("display_categories") val displayCategories: List<String> = emptyList(),
    @SerialName("versions") val gameVersions: List<String> = emptyList(),
    val gallery: List<String> = emptyList(),
)

/** Response of GET /v2/project/{id}. `body` is the full markdown readme. */
@Serializable
data class MrProject(
    val id: String,
    val slug: String? = null,
    val title: String = "",
    val description: String = "",
    val body: String = "",
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val gallery: List<MrGalleryImage> = emptyList(),
)

@Serializable
data class MrGalleryImage(
    val url: String,
    val featured: Boolean = false,
    val title: String? = null,
)

@Serializable
data class MrVersion(
    val id: String,
    val name: String = "",
    @SerialName("version_number") val versionNumber: String = "",
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerialName("date_published") val datePublished: String? = null,
    val files: List<MrFile> = emptyList(),
    val dependencies: List<MrDependency> = emptyList(),
) {
    /** The primary downloadable file, falling back to the first file. */
    fun primaryFile(): MrFile? = files.firstOrNull { it.primary } ?: files.firstOrNull()
}

@Serializable
data class MrFile(
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long = 0,
)

@Serializable
data class MrDependency(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("dependency_type") val dependencyType: String? = null,
)
