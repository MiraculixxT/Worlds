package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.*
import net.minecraft.client.Minecraft
import java.nio.file.Files

/**
 * Central store that merges the two discovery sources (Modrinth reverse-dependency search + the
 * curated GitHub index), caches the results, resolves per-map detail lazily, and scans the saves
 * directory for installed maps. All network methods block and must run on a background coroutine.
 */
object MapRepository {
    @Volatile private var browseCache: List<MapEntry>? = null
    // Keeps the raw manual entries so their inline detail can be filled without a network call.
    private val manualById = HashMap<String, GhMapEntry>()

    /** Merged Browse list. Cached after the first successful load; pass [force] to refresh. */
    fun loadBrowse(force: Boolean = false): List<MapEntry> {
        browseCache?.let { if (!force) return it }

        val github = GitHubIndex.fetch()
        val manual = github.filter { it.source.equals("manual", true) }
        val ghModrinthSlugs = github.filter { it.source.equals("modrinth", true) }
            .mapNotNull { it.slug }

        manualById.clear()
        val manualEntries = manual.mapNotNull { gh ->
            val id = gh.id ?: gh.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-") ?: return@mapNotNull null
            manualById[id] = gh
            MapEntry(
                id = id,
                source = MapSource.MANUAL,
                slug = null,
                title = gh.name ?: id,
                description = gh.description ?: "",
                iconUrl = gh.icon,
                mcVersions = gh.mcVersions,
                categories = if (gh.theme != null) listOf(gh.theme) + gh.categories else gh.categories,
                website = gh.website,
            )
        }

        val hits = ModrinthApi.searchDependents()
        val hitIds = hits.map { it.projectId }.toSet()
        // Curated modrinth pointers not already surfaced by the reverse-dependency search.
        val extraProjects = ModrinthApi.getProjects(ghModrinthSlugs)
            .filter { it.id !in hitIds }

        val modrinthEntries = hits.map { hit ->
            MapEntry(
                id = hit.projectId,
                source = MapSource.MODRINTH,
                slug = hit.slug,
                title = hit.title,
                description = hit.description,
                iconUrl = hit.iconUrl,
                mcVersions = hit.gameVersions,
                categories = hit.displayCategories.ifEmpty { hit.categories },
            ).also { it.sourceUrl = modrinthUrl(hit.projectType, hit.slug ?: hit.projectId) }
        } + extraProjects.map { p ->
            MapEntry(
                id = p.id,
                source = MapSource.MODRINTH,
                slug = p.slug,
                title = p.title,
                description = p.description,
                iconUrl = p.iconUrl,
                mcVersions = p.gameVersions,
                categories = p.categories,
            ).also { it.sourceUrl = modrinthUrl(p.projectType, p.slug ?: p.id) }
        }

        val merged = (manualEntries + modrinthEntries)
            .distinctBy { "${it.source}:${it.id}" }
            .sortedBy { it.title.lowercase() }
        browseCache = merged
        return merged
    }

    /** Fill [entry]'s heavy fields (readme, download url, requirements, gallery). Idempotent. */
    fun loadDetail(entry: MapEntry) {
        if (entry.detailLoaded) return
        when (entry.source) {
            MapSource.MANUAL -> loadManualDetail(entry)
            MapSource.MODRINTH -> loadModrinthDetail(entry)
        }
        entry.detailLoaded = true
    }

    private fun loadManualDetail(entry: MapEntry) {
        val gh = manualById[entry.id] ?: return
        entry.readmeMarkdown = gh.readme ?: gh.description
        entry.downloadUrl = gh.download
        entry.requiredMods = gh.requiredMods.map { it.toRequirement(RequirementKind.MOD) }
        entry.requiredPacks = gh.requiredPacks.map { it.toRequirement(RequirementKind.RESOURCE_PACK) }
    }

    private fun loadModrinthDetail(entry: MapEntry) {
        val project = ModrinthApi.getProject(entry.id)
        entry.readmeMarkdown = project?.body?.ifBlank { entry.description } ?: entry.description
        entry.gallery = project?.gallery?.sortedByDescending { it.featured }?.map { it.url } ?: emptyList()

        val versions = ModrinthApi.getVersions(entry.id)
        val version = pickVersion(versions)
        entry.downloadUrl = version?.primaryFile()?.url

        val depIds = version?.dependencies
            ?.filter { it.dependencyType == "required" && it.projectId != null }
            ?.mapNotNull { it.projectId }
            ?.distinct()
            ?: emptyList()
        if (depIds.isNotEmpty()) {
            val deps = ModrinthApi.getProjects(depIds)
            val reqs = deps.map { p ->
                val kind = if (p.projectType == "resourcepack") RequirementKind.RESOURCE_PACK else RequirementKind.MOD
                MapRequirement(
                    name = p.title,
                    kind = kind,
                    projectId = p.id,
                    modId = p.slug,
                    link = modrinthUrl(p.projectType, p.slug ?: p.id),
                )
            }
            entry.requiredMods = reqs.filter { it.kind == RequirementKind.MOD }
            entry.requiredPacks = reqs.filter { it.kind == RequirementKind.RESOURCE_PACK }
        }
    }

    /** Prefer the newest version compatible with the running MC version, else the newest overall. */
    private fun pickVersion(versions: List<MrVersion>): MrVersion? {
        if (versions.isEmpty()) return null
        val mc = Minecraft.getInstance().launchedVersion
        val sorted = versions.sortedByDescending { it.datePublished ?: "" }
        return sorted.firstOrNull { mc in it.gameVersions } ?: sorted.first()
    }

    /** Scan `saves/` for folders carrying our [InstalledMeta.FILE_NAME] marker. */
    fun scanInstalled(): List<InstalledMap> {
        val savesDir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves")
        if (!Files.isDirectory(savesDir)) return emptyList()
        val result = ArrayList<InstalledMap>()
        Files.newDirectoryStream(savesDir).use { stream ->
            for (dir in stream) {
                if (!Files.isDirectory(dir)) continue
                val marker = dir.resolve(InstalledMeta.FILE_NAME)
                if (!Files.isRegularFile(marker)) continue
                val meta = try {
                    Http.json.decodeFromString<InstalledMeta>(Files.readString(marker))
                } catch (e: Exception) {
                    Constants.LOG.warn("Bad {} in {}: {}", InstalledMeta.FILE_NAME, dir, e.message)
                    continue
                }
                result.add(InstalledMap(dir.fileName.toString(), meta))
            }
        }
        return result.sortedBy { it.meta.title.lowercase() }
    }

    fun invalidate() {
        browseCache = null
    }

    private fun modrinthUrl(projectType: String?, slug: String): String =
        "https://modrinth.com/${projectType ?: "project"}/$slug"

    private fun GhRequirement.toRequirement(kind: RequirementKind) = MapRequirement(
        name = name,
        kind = kind,
        projectId = null,
        modId = id,
        link = link,
        download = download,
    )
}
