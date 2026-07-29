package de.miraculixx.worlds.api

import de.miraculixx.worlds.Constants
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The Worlds backend — a **stateless CurseForge proxy**, nothing else. Modrinth is queried by the
 * client directly ([ModrinthApi]) and the *Other* source ships inside the jar ([ManualIndex]);
 * CurseForge needs an API key, so those two calls go through here. The backend stores no catalogue:
 * every request is forwarded to CurseForge and its answer normalized into the models below.
 *
 * ## `GET /worlds/curseforge/search?q=<query>&index=<offset>&limit=<n>` — one page of maps
 *
 * `q` may be empty, which means "the most popular worlds"; `index` is the offset of the first hit
 * and `limit` the page size (the client asks for [PAGE_SIZE]). Answers an [ApiSearchPage]:
 *
 * ```json
 * {
 *   "index": 0,
 *   "limit": 50,
 *   "total": 1234,
 *   "hits": [
 *     {
 *       "id": "123456",
 *       "title": "The Dropper",
 *       "description": "One-line summary, shown on the list row and detail header.",
 *       "icon": "https://cdn.example/icon.webp",
 *       "mc": ["1.21.4", "26.2"],
 *       "categories": ["parkour", "adventure"],
 *       "version": "1.4.2",
 *       "downloads": 123456,
 *       "updated": 1753000000000,
 *       "website": "https://www.curseforge.com/minecraft/worlds/the-dropper",
 *       "trailer": "https://www.youtube.com/watch?v=xxxxxxxxxxx"
 *     }
 *   ]
 * }
 * ```
 *
 * - `id` is the CurseForge mod id and addresses the detail endpoint. It must stay stable — an
 *   installed world stores it in its marker file.
 * - `total` is what tells the client another page exists; it may be capped, as long as it stops
 *   growing only when the results do.
 * - `categories` should come from `CategoryBadge.CATEGORIES` (`adventure parkour survival puzzle
 *   horror minigames build`); the first known one renders as the colored pill. Unknown values are
 *   kept for search but draw no pill.
 * - `mc` drives the version filter and the row's `Version:` label — plain dotted MC versions.
 * - `updated` and `downloads` are sort keys. Emit `0` when CurseForge reports none; those entries
 *   sort last rather than wrong.
 * - `version` is the newest published file's version, compared by **equality only**, never ordered.
 *   Any stable string works as long as it changes exactly when the downloadable file does; it must
 *   describe the same release the detail endpoint's `download` points at, or the update hint never
 *   clears after an install.
 *
 * ## `GET /worlds/curseforge/detail/<id>` — one map
 *
 * Requested lazily when a map is opened in the detail panel and cached in memory for the session.
 *
 * ```json
 * {
 *   "readme": "# The Dropper\n\nFull markdown…",
 *   "download": "https://cdn.example/the-dropper-1.4.zip",
 *   "gallery": ["https://cdn.example/shot1.png"],
 *   "requiredMods": [
 *     { "name": "Fabric API", "modId": "fabric-api", "link": "https://modrinth.com/mod/fabric-api" }
 *   ],
 *   "requiredPacks": [
 *     { "name": "Dropper Textures", "download": "https://cdn.example/pack.zip" },
 *     { "name": "Bundled Pack", "included": true }
 *   ]
 * }
 * ```
 *
 * - `download` must be a **direct** link to the world zip (or world-gen datapack zip) for the newest
 *   file compatible with the running game, followed by the newest overall. The client cannot resolve
 *   CurseForge file lists itself, so a project page URL here means the map can't install.
 * - `readme` is rendered as markdown; a missing one falls back to the search hit's `description`.
 *   When `trailer` is absent, the client scans the readme for the first YouTube link.
 * - `requiredMods[].modId` is the **fabric mod id** — the missing-mod check compares it against the
 *   loaded mods. Omit it when it can't be determined and the check is skipped for that entry (better
 *   than a false "missing mod" warning). `link` is the Download button's target.
 * - `requiredPacks[].download` must also be a direct URL — the pack is fetched into the save's own
 *   `resourcepacks/` folder at install time. `"included": true` means the pack ships inside the
 *   world zip: no `download` needed, the entry only records that the map uses a pack.
 *
 * Any non-2xx keeps the previous state: a failed page load leaves the list as it was, a failed
 * detail fetch is retried the next time the map is selected.
 */
object WorldsApi {
    /** Hits per CurseForge page request — one screenful of scrolling, several times over. */
    const val PAGE_SIZE = 50

    private const val CURSEFORGE = "${Constants.API_BASE}/curseforge"

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    fun searchCurseForge(query: String, index: Int, limit: Int = PAGE_SIZE): ApiSearchPage? =
        Http.decode<ApiSearchPage>(
            Http.getString("$CURSEFORGE/search?q=${enc(query)}&index=$index&limit=$limit")
        )

    fun curseForgeDetail(id: String): ApiMapDetail? =
        Http.decode<ApiMapDetail>(Http.getString("$CURSEFORGE/detail/${enc(id)}"))
}
