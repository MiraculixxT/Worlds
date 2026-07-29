package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper over the Modrinth v2 REST API (only async)
 */
object ModrinthApi {
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    /**
     * Reverse-dependency search: every published project (datapack/modpack/resourcepack)
     * that lists [worldsId] as a compatible dependency
     */
    fun searchDependents(worldsId: String = Constants.WORLDS_MODRINTH_ID): List<MrSearchHit> {
        val url = "${Constants.MODRINTH_API}/search?new_filters=${enc(dependentFilter(worldsId))}" +
            "&limit=100&index=downloads"
        val res = Http.decode<MrSearchResponse>(Http.getString(url))
        return res?.hits ?: emptyList()
    }

    /**
     * Free-text search **inside** the reverse-dependency list, used when the search box is non-empty
     */
    fun search(query: String, limit: Int = 100, worldsId: String = Constants.WORLDS_MODRINTH_ID): List<MrSearchHit> {
        if (query.isBlank()) return emptyList()
        val url = "${Constants.MODRINTH_API}/search?query=${enc(query)}" +
            "&new_filters=${enc(dependentFilter(worldsId))}&limit=$limit&index=relevance"
        return Http.decode<MrSearchResponse>(Http.getString(url))?.hits ?: emptyList()
    }

    private fun dependentFilter(worldsId: String) =
        """project_types IN ["datapack","modpack","resourcepack"] """ +
            """AND compatible_dependency_project_ids = "$worldsId""""

    fun getProject(idOrSlug: String): MrProject? =
        Http.decode<MrProject>(Http.getString("${Constants.MODRINTH_API}/project/${enc(idOrSlug)}"))

    /** Batched project lookup via GET /v2/projects?ids=[...]. */
    fun getProjects(ids: Collection<String>): List<MrProject> {
        if (ids.isEmpty()) return emptyList()
        val idsParam = ids.joinToString(",", "[", "]") { "\"$it\"" }
        val url = "${Constants.MODRINTH_API}/projects?ids=${enc(idsParam)}"
        return Http.decode<List<MrProject>>(Http.getString(url)) ?: emptyList()
    }

    fun getVersions(idOrSlug: String): List<MrVersion> =
        Http.decode<List<MrVersion>>(
            Http.getString("${Constants.MODRINTH_API}/project/${enc(idOrSlug)}/version")
        ) ?: emptyList()
}
