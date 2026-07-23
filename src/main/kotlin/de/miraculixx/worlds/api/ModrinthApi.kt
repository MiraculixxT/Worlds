package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper over the Modrinth v2 REST API. All methods are blocking and are expected to be
 * called from a background coroutine.
 */
object ModrinthApi {
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    /**
     * Reverse-dependency search: every published project (datapack/modpack/resourcepack/mod)
     * that lists [worldsId] as a compatible dependency. Uses Modrinth's `new_filters` grammar.
     */
    fun searchDependents(worldsId: String = Constants.WORLDS_MODRINTH_ID): List<MrSearchHit> {
        val filter = """project_types IN ["datapack","modpack","resourcepack","mod"] """ +
            """AND compatible_dependency_project_ids = "$worldsId""""
        val url = "${Constants.MODRINTH_API}/search?new_filters=${enc(filter)}&limit=100&index=downloads"
        val res = Http.decode<MrSearchResponse>(Http.getString(url))
        return res?.hits ?: emptyList()
    }

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
