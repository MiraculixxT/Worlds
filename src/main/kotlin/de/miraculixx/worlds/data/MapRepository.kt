package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.ApiMapDetail
import de.miraculixx.worlds.api.ApiMapEntry
import de.miraculixx.worlds.api.Http
import de.miraculixx.worlds.api.ManualIndex
import de.miraculixx.worlds.api.ManualMapEntry
import de.miraculixx.worlds.api.ModrinthApi
import de.miraculixx.worlds.api.ModrinthIndex
import de.miraculixx.worlds.api.MrProject
import de.miraculixx.worlds.api.MrSearchHit
import de.miraculixx.worlds.api.MrVersion
import de.miraculixx.worlds.api.WorldsApi
import net.minecraft.client.Minecraft
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Central store around the three browse sources (not persistent)
 * - Modrinth (queried client-side)
 * - CurseForge (through proxy - thanks shit API-key)
 * - Manual embedded list
 * - Saves directory
 *
 * All network methods block and must run on a background coroutine.
 */
object MapRepository {
    /** One page of browse results. [hasMore] is only ever true for the paged CurseForge source. */
    data class BrowsePage(val entries: List<MapEntry>, val hasMore: Boolean = false)

    // Session caches of the two sources that fetch their whole list at once.
    @Volatile private var modrinthCache: List<MapEntry>? = null
    @Volatile private var manualCache: List<MapEntry>? = null
    private val detailCache = ConcurrentHashMap<String, ApiMapDetail>()

    data class Listing(val version: String?, val dateEpoch: Long)

    /**
     * Newest listing seen for a `source:id` while browsing (only source of update check)
     */
    private val seenListings = ConcurrentHashMap<String, Listing>()

    fun loadBrowse(source: MapSource, query: String = "", force: Boolean = false): BrowsePage = when (source) {
        MapSource.MANUAL -> BrowsePage(manualEntries(force))
        MapSource.MODRINTH -> BrowsePage(modrinthEntries(query, force))
        MapSource.CURSEFORGE -> curseForgePage(query, 0)
        else -> BrowsePage(emptyList())
    }

    /** Next CurseForge page, starting at [index]. Any other source has everything already. */
    fun loadMore(source: MapSource, query: String, index: Int): BrowsePage =
        if (source == MapSource.CURSEFORGE) curseForgePage(query, index) else BrowsePage(emptyList())

    /** The bundled *Other* list. Free to build, so [force] only matters for consistency. */
    private fun manualEntries(force: Boolean): List<MapEntry> {
        manualCache?.let { if (!force) return it }
        val entries = ManualIndex.entries.mapNotNull { manual ->
            val id = manualId(manual) ?: return@mapNotNull null
            MapEntry(
                id = id,
                source = MapSource.MANUAL,
                title = manual.name ?: id,
                description = manual.description ?: "",
                iconUrl = manual.icon,
                mcVersions = manual.mcVersions,
                categories = manual.categories,
                version = manual.version,
                website = manual.website,
                trailerUrl = manual.trailer,
            ).apply { downloadUrl = manual.download }
        }.sortedBy { it.title.lowercase() }
        manualCache = entries
        return entries
    }

    /**
     * Modrinth maps: manual project list + reverse search. A query never widens the set — it runs a
     * second dependency-filtered search so dependents past the base call's 100-hit cap show up.
     */
    private fun modrinthEntries(query: String, force: Boolean): List<MapEntry> {
        val base = modrinthCache?.takeIf { !force } ?: fetchModrinthBase().also { modrinthCache = it }
        if (query.isBlank()) return base
        val known = base.map { it.id }.toSet()
        return base + ModrinthApi.search(query).filter { it.projectId !in known }.map { it.toEntry() }
    }

    private fun fetchModrinthBase(): List<MapEntry> {
        val dependents = ModrinthApi.searchDependents().map { it.toEntry() }
        val known = dependents.map { it.id }.toSet()
        val curated = ModrinthApi.getProjects(ModrinthIndex.slugs)
            .filter { it.id !in known }
            .map { it.toEntry() }
        return (dependents + curated).sortedBy { it.title.lowercase() }
    }

    private fun MrSearchHit.toEntry() = MapEntry(
        id = projectId,
        source = MapSource.MODRINTH,
        title = title,
        description = description,
        iconUrl = iconUrl,
        mcVersions = gameVersions,
        categories = displayCategories.ifEmpty { categories },
        downloads = downloads,
        dateEpoch = parseEpoch(dateModified),
        website = modrinthUrl(projectType, slug ?: projectId),
    ).also(::remember)

    private fun MrProject.toEntry() = MapEntry(
        id = id,
        source = MapSource.MODRINTH,
        title = title,
        description = description,
        iconUrl = iconUrl,
        mcVersions = gameVersions,
        categories = categories,
        downloads = downloads,
        dateEpoch = parseEpoch(updated),
        website = modrinthUrl(projectType, slug ?: id),
    ).also(::remember)

    private fun curseForgePage(query: String, index: Int): BrowsePage {
        val page = WorldsApi.searchCurseForge(query, index) ?: return BrowsePage(emptyList())
        val entries = page.hits.map { it.toEntry() }
        return BrowsePage(entries, index + page.hits.size < page.total && page.hits.isNotEmpty())
    }

    private fun ApiMapEntry.toEntry() = MapEntry(
        id = id,
        source = MapSource.CURSEFORGE,
        title = title,
        description = description,
        iconUrl = icon,
        mcVersions = mcVersions,
        categories = categories,
        version = version,
        downloads = downloads,
        dateEpoch = updated,
        website = website,
        trailerUrl = trailer,
    ).also(::remember)

    private fun remember(entry: MapEntry) {
        seenListings[key(entry.source, entry.id)] = Listing(entry.version, entry.dateEpoch)
    }

    /**
     * Updates for local indexes directly checked, remote maps only when fetched in browse
     */
    fun findUpdate(meta: InstalledMeta): Listing? {
        val latest = if (meta.source == MapSource.MANUAL) {
            manualEntries(false).firstOrNull { it.id == meta.id }?.let { Listing(it.version, it.dateEpoch) }
        } else {
            seenListings[key(meta.source, meta.id)]
        } ?: return null
        return when {
            meta.version != null && latest.version != null -> latest.takeIf { it.version != meta.version }
            meta.updated > 0 && latest.dateEpoch > 0 -> latest.takeIf { it.dateEpoch > meta.updated }
            else -> null
        }
    }

    /** Fill [entry]'s heavy fields (readme, download url, requirements, gallery) */
    fun loadDetail(entry: MapEntry) {
        if (entry.detailLoaded) return
        when (entry.source) {
            MapSource.MANUAL -> loadManualDetail(entry)
            MapSource.MODRINTH -> loadModrinthDetail(entry)
            MapSource.CURSEFORGE -> if (!loadCurseForgeDetail(entry)) return
            else -> {}
        }
        entry.detailLoaded = true
    }

    private fun loadManualDetail(entry: MapEntry) {
        val manual = ManualIndex.entries.firstOrNull { manualId(it) == entry.id } ?: return
        entry.readmeMarkdown = manual.readme ?: manual.description
        entry.downloadUrl = manual.download
        entry.trailerUrl = manual.trailer ?: firstYoutubeLink(manual.readme)
        entry.requiredMods = manual.requiredMods.map { it.toRequirement(RequirementKind.MOD) }
        entry.requiredPacks = manual.requiredPacks.map { it.toRequirement(RequirementKind.RESOURCE_PACK) }
    }

    /** Memoized per session; a failed fetch leaves the entry unloaded so re-selecting retries. */
    private fun loadCurseForgeDetail(entry: MapEntry): Boolean {
        val key = key(entry.source, entry.id)
        val detail = detailCache[key]
            ?: WorldsApi.curseForgeDetail(entry.id)?.also { detailCache[key] = it }
            ?: return false
        entry.readmeMarkdown = detail.readme?.ifBlank { null } ?: entry.description
        entry.downloadUrl = detail.download ?: entry.downloadUrl
        entry.gallery = detail.gallery
        entry.trailerUrl = detail.trailer ?: entry.trailerUrl ?: firstYoutubeLink(detail.readme)
        detail.website?.let { entry.website = it }
        entry.requiredMods = detail.requiredMods.map { it.toRequirement(RequirementKind.MOD) }
        entry.requiredPacks = detail.requiredPacks.map { it.toRequirement(RequirementKind.RESOURCE_PACK) }
        return true
    }

    private fun loadModrinthDetail(entry: MapEntry) {
        val project = ModrinthApi.getProject(entry.id)
        entry.readmeMarkdown = project?.body?.ifBlank { entry.description } ?: entry.description
        entry.gallery = project?.gallery?.sortedByDescending { it.featured }?.map { it.url } ?: emptyList()
        entry.trailerUrl = firstYoutubeLink(project?.body)

        val versions = ModrinthApi.getVersions(entry.id)
        val version = pickVersion(versions)
        entry.downloadUrl = version?.primaryFile()?.url
        // Only known once a concrete version was picked
        entry.version = version?.versionNumber?.ifBlank { null }

        val depTypeById = version?.dependencies
            ?.filter { it.projectId != null && (it.dependencyType == "required" || it.dependencyType == "embedded") }
            ?.associate { it.projectId!! to it.dependencyType }
            ?: emptyMap()
        if (depTypeById.isNotEmpty()) {
            val deps = ModrinthApi.getProjects(depTypeById.keys.toList())
            val reqs = deps.mapNotNull { p ->
                val embedded = depTypeById[p.id] == "embedded"
                val isPack = p.projectType == "resourcepack"
                if (embedded && !isPack) return@mapNotNull null
                MapRequirement(
                    name = p.title,
                    kind = if (isPack) RequirementKind.RESOURCE_PACK else RequirementKind.MOD,
                    projectId = p.id,
                    modId = p.slug,
                    link = modrinthUrl(p.projectType, p.slug ?: p.id),
                    included = embedded,
                )
            }
            entry.requiredMods = reqs.filter { it.kind == RequirementKind.MOD }
            entry.requiredPacks = reqs.filter { it.kind == RequirementKind.RESOURCE_PACK }
        }
    }

    /** Best download URL for a Modrinth project's newest compatible version (used for pack deps). */
    fun resolveModrinthDownload(projectId: String): String? =
        pickVersion(ModrinthApi.getVersions(projectId))?.primaryFile()?.url

    /** Prefer the newest version compatible with the running MC version, else the newest overall. */
    private fun pickVersion(versions: List<MrVersion>): MrVersion? {
        if (versions.isEmpty()) return null
        val mc = Minecraft.getInstance().launchedVersion
        val sorted = versions.sortedByDescending { it.datePublished ?: "" }
        return sorted.firstOrNull { mc in it.gameVersions } ?: sorted.first()
    }

    /**
     * Scan `saves/` for worlds (anything with a `level.dat`). Folders carrying our
     * [InstalledMeta.FILE_NAME] marker were installed by this mod and keep their listing metadata;
     * the rest are worlds the player made or dropped in, reported with a null `meta`.
     */
    fun scanInstalled(): List<InstalledMap> {
        val savesDir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves")
        if (!Files.isDirectory(savesDir)) return emptyList()
        val result = ArrayList<InstalledMap>()
        Files.newDirectoryStream(savesDir).use { stream ->
            for (dir in stream) {
                if (!Files.isDirectory(dir)) continue
                if (!Files.isRegularFile(dir.resolve("level.dat"))) continue
                val marker = dir.resolve(InstalledMeta.FILE_NAME)
                val meta = if (Files.isRegularFile(marker)) {
                    try {
                        Http.json.decodeFromString<InstalledMeta>(Files.readString(marker))
                    } catch (e: Exception) {
                        Constants.LOG.warn("Bad {} in {}: {}", InstalledMeta.FILE_NAME, dir, e.message)
                        null
                    }
                } else null
                val iconFile = dir.resolve("icon.png")
                val localIcon = if (Files.isRegularFile(iconFile)) iconFile.toString() else null
                val folder = dir.fileName.toString()
                val level = readLevelData(dir)
                result.add(
                    InstalledMap(
                        saveFolder = folder,
                        meta = meta,
                        localIcon = localIcon,
                        levelName = level?.name ?: folder,
                        info = level?.info,
                    )
                )
            }
        }
        return result.sortedBy { it.title.lowercase() }
    }

    /**
     * Total bytes of a save folder. Bad files are skipped
     */
    fun worldSize(saveFolder: String): Long {
        val dir = Minecraft.getInstance().gameDirectory.toPath().resolve("saves").resolve(saveFolder)
        if (!Files.isDirectory(dir)) return 0
        return try {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .mapToLong { try { Files.size(it) } catch (_: Exception) { 0L } }
                    .sum()
            }
        } catch (e: Exception) {
            Constants.LOG.warn("Could not size {}: {}", dir, e.message)
            0L
        }
    }

    private data class LevelData(val name: String?, val info: WorldInfo)

    /**
     * Read the `Data` compound of a save's `level.dat`, or null when it is unreadable. 26.2 keeps
     * difficulty in `difficulty_settings` (`difficulty`/`hardcore`/`locked`) rather than a plain byte.
     */
    private fun readLevelData(dir: java.nio.file.Path): LevelData? = try {
        val data = NbtIo.readCompressed(dir.resolve("level.dat"), NbtAccounter.unlimitedHeap())
            .getCompoundOrEmpty("Data")
        val difficulty = data.getCompoundOrEmpty("difficulty_settings")
        val enabledPacks = data.getCompoundOrEmpty("DataPacks").getListOrEmpty("Enabled")
        LevelData(
            name = data.getString("LevelName").orElse(null)?.takeIf { it.isNotBlank() },
            info = WorldInfo(
                mcVersion = data.getCompoundOrEmpty("Version").getString("Name").orElse(null)
                    ?.takeIf { it.isNotBlank() },
                lastPlayed = data.getLongOr("LastPlayed", 0L),
                playTicks = data.getLongOr("Time", 0L),
                difficulty = difficulty.getString("difficulty").orElse(null),
                hardcore = difficulty.getBooleanOr("hardcore", false),
                allowCommands = data.getBooleanOr("allowCommands", false),
                dataPacks = enabledPacks.indices
                    .map { enabledPacks.getStringOr(it, "") }
                    .filter { it.isNotBlank() && it != "vanilla" }
                    .map { it.removePrefix("file/") },
            ),
        )
    } catch (e: Exception) {
        Constants.LOG.warn("Unreadable level.dat in {}: {}", dir, e.message)
        null
    }

    /** Drop the session caches so the next browse load queries the sources again. */
    fun invalidate() {
        modrinthCache = null
        manualCache = null
    }

    private fun key(source: MapSource, id: String) = "${source.key}:$id"

    private fun manualId(manual: ManualMapEntry): String? =
        manual.id ?: manual.name?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")

    private val YOUTUBE_REGEX = Regex(
        """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?[\w=&-]*v=|embed/|shorts/)|youtu\.be/)([\w-]{11})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * First YouTube URL found in a readme body, normalized to watch link
     * (embed/shorts/youtu.be all collapse to `https://www.youtube.com/watch?v=<id>`).
     * Only used when the source didn't supply a trailer of its own.
     */
    private fun firstYoutubeLink(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val id = YOUTUBE_REGEX.find(body)?.groupValues?.get(1) ?: return null
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun modrinthUrl(projectType: String?, slug: String): String =
        "https://modrinth.com/${projectType ?: "project"}/$slug"

    /** ISO-8601 timestamp → epoch millis; 0 when absent/unparseable (sorts last on Date). */
    private fun parseEpoch(iso: String?): Long =
        if (iso.isNullOrBlank()) 0L
        else try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }
}
