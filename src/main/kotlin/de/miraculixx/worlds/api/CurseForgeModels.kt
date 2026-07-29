package de.miraculixx.worlds.api

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


@Serializable
data class CfSearchResponse(
    val data: List<CfMod> = emptyList(),
    val pagination: CfPagination = CfPagination(),
)

@Serializable
data class CfPagination(
    val index: Int = 0,
    val pageSize: Int = 0,
    /** Hits in *this* page. */
    val resultCount: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class CfDetailResponse(
    val data: CfMod? = null,
    /** Rendered project description, HTML. */
    val description: String? = null,
)

@Serializable
data class CfMod(
    val id: Long,
    val name: String = "",
    val slug: String? = null,
    /** One-line blurb; the list row's description and the detail header. */
    val summary: String = "",
    /** CurseForge sends this as a plain number, occasionally in exponent form → [Double]. */
    val downloadCount: Double = 0.0,
    /** ISO-8601; parsed into the `Date` sort key. */
    val dateModified: String? = null,
    val primaryCategoryId: Int = 0,
    val categories: List<CfCategory> = emptyList(),
    val links: CfLinks? = null,
    val logo: CfLogo? = null,
    /** The few most recent files; the newest compatible one supplies the entry's version string. */
    val latestFiles: List<CfFile> = emptyList(),
    /** One entry per (game version, loader) the latest files cover */
    val latestFilesIndexes: List<CfFileIndex> = emptyList(),
    /** Detail only; the gallery. */
    val screenshots: List<CfScreenshot> = emptyList(),
)

@Serializable
data class CfScreenshot(
    val title: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class CfCategory(val id: Int = 0, val name: String = "", val slug: String? = null)

@Serializable
data class CfLinks(val websiteUrl: String? = null)

@Serializable
data class CfLogo(val url: String? = null, val thumbnailUrl: String? = null)

@Serializable
data class CfFile(
    val id: Long = 0,
    val displayName: String = "",
    val fileName: String = "",
    val fileDate: String? = null,
    /** Direct zip link. Only the files in `latestFiles` carry one. */
    val downloadUrl: String? = null,
    /** Mixes MC versions with loader/java names */
    val gameVersions: List<String> = emptyList(),
)

@Serializable
data class CfFileIndex(val gameVersion: String = "", val fileId: Long = 0)

/**
 * Converter between CF IDs
 */
object CurseForgeCategories {
    private val TO_TAXONOMY = mapOf(
        "adventure" to "adventure",
        "creation" to "build",
        "game-map" to "minigames",
        "parkour" to "parkour",
        "puzzle" to "puzzle",
        "survival" to "survival",
        "modded-world" to "build",
    )

    private val TO_ID = mapOf(
        "adventure" to 248,
        "build" to 249,
        "minigames" to 250,
        "parkour" to 251,
        "puzzle" to 252,
        "survival" to 253,
    )

    /** Taxonomy name for a CurseForge category slug, or null when it has no equivalent. */
    fun taxonomy(slug: String?): String? = slug?.lowercase()?.let(TO_TAXONOMY::get)

    /** CurseForge `categoryId` for a taxonomy name, or null when it can't be filtered server-side. */
    fun idOf(category: String?): Int? = category?.lowercase()?.let(TO_ID::get)
}

/** Link rewriting for the HTML descriptions, which are written for the CurseForge site, not a client. */
object CurseForgeLinks {
    private const val HOST = "https://www.curseforge.com"
    private val LINKOUT = Regex("""^/linkout\?remoteUrl=(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Outgoing links are wrapped in `/linkout?remoteUrl=<twice-encoded target>`; unwrap those and
     * make anything else site-relative absolute. Anchors and `javascript:` are dropped (null).
     */
    fun resolve(href: String): String? {
        val url = href.trim()
        if (url.isEmpty() || url.startsWith("#") || url.startsWith("javascript:", true)) return null
        LINKOUT.find(url)?.let { m ->
            val target = runCatching {
                URLDecoder.decode(URLDecoder.decode(m.groupValues[1], StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            }.getOrNull()
            if (!target.isNullOrBlank()) return target
        }
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> HOST + url
            "://" in url || url.startsWith("mailto:", true) -> url
            else -> "$HOST/$url"
        }
    }
}
