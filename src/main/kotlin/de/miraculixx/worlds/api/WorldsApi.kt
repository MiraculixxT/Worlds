package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * CurseForge API proxy for now.
 * Documentation sits in backend
 */
object WorldsApi {
    /** Hits per CurseForge page request */
    const val PAGE_SIZE = 50
    const val MAX_INDEX = 10_000

    /** `sortBy` values the proxy accepts. `RELEVANCY` is CurseForge's name-ascending order. */
    enum class Sort(val key: String) { RELEVANCY("relevancy"), DOWNLOADS("downloads"), DATE("date") }

    private const val CURSEFORGE = "${Constants.API_BASE}/curseforge"

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    fun searchCurseForge(
        query: String,
        index: Int,
        limit: Int = PAGE_SIZE,
        sort: Sort = Sort.RELEVANCY,
        categoryId: Int? = null,
        versions: List<String> = emptyList(),
    ): CfSearchResponse? {
        val url = StringBuilder("$CURSEFORGE/search?q=${enc(query)}&index=$index&limit=$limit&sortBy=${sort.key}")
        categoryId?.let { url.append("&filterCategory=$it") }
        if (versions.isNotEmpty()) url.append("&filterVersion=${enc(versions.joinToString(","))}")
        return Http.decode<CfSearchResponse>(Http.getString(url.toString()))
    }

    fun curseForgeDetail(id: String): CfDetailResponse? =
        Http.decode<CfDetailResponse>(Http.getString("$CURSEFORGE/detail/${enc(id)}"))
}
