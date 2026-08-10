package de.miraculixx.worlds.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.SharedConstants
import net.minecraft.client.resources.language.I18n


val mcVersion: String get() = SharedConstants.getCurrentVersion().name()

/**
 * Where a listing came from
 */
@Serializable(with = MapSource.Serializer::class)
enum class MapSource(val key: String, private val brand: String? = null) {
    /** Queried straight from the Modrinth v2 API by the client. */
    MODRINTH("modrinth", "Modrinth"),
    /** Proxied through the backend (API key force) */
    CURSEFORGE("curseforge", "CurseForge"),
    /** Curated list bundled with the mod, for maps hosted anywhere else (mainly minecraftmaps.com scrape) */
    MANUAL("manual"),
    /** A save this mod did not install (never browsable) */
    LOCAL("local"),
    UNKNOWN("unknown");

    /** Platform names are proper nouns and stay as they are; the rest are translated. */
    val label: String get() = brand ?: I18n.get("worlds.source.$key")

    companion object {
        fun of(key: String?): MapSource = entries.firstOrNull { it.key.equals(key, true) } ?: UNKNOWN

        val BROWSABLE = listOf(MODRINTH, CURSEFORGE, MANUAL)
    }

    object Serializer : KSerializer<MapSource> {
        override val descriptor = PrimitiveSerialDescriptor("MapSource", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: MapSource) = encoder.encodeString(value.key)
        override fun deserialize(decoder: Decoder) = of(decoder.decodeString())
    }
}

/**
 * Compare dotted numeric versions ("26.2" vs "26.1"); a part's leading digits count, the rest is
 * dropped ("26.2-rc1" == "26.2"), a part with no leading digit counts as 0.
 */
fun compareMcVersions(a: String, b: String): Int {
    val pa = a.split('.'); val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val na = pa.getOrNull(i).versionPart()
        val nb = pb.getOrNull(i).versionPart()
        if (na != nb) return na.compareTo(nb)
    }
    return 0
}

private fun String?.versionPart(): Int =
    this?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

enum class RequirementKind { MOD, RESOURCE_PACK }

/** A required mod or resource pack for a map. */
@Serializable
data class MapRequirement(
    val name: String,
    val kind: RequirementKind,
    /** Modrinth project id, so a pack's download can be resolved at install time. */
    @SerialName("project_id") val projectId: String? = null,
    /** Mod id (as declared in fabric.mod.json) used for the installed-mod check. */
    val modId: String? = null,
    val link: String? = null,
    val download: String? = null,

    val included: Boolean = false,
)

/**
 * A browsable/installable map from one of the three [MapSource]s.
 * Heavy fields like readme are loaded lazy
 * @see MapRepository.loadDetail
 */
class MapEntry(
    val id: String,
    val source: MapSource,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val mcVersions: List<String>,
    val categories: List<String>,
    var version: String? = null,
    /** Total downloads on the source platform; 0 when it reports none (sorts last). */
    val downloads: Long = 0,
    /** Last-updated epoch millis in Browse, last-played in Installed; 0 when unknown. */
    val dateEpoch: Long = 0,
    var website: String? = null,
    var trailerUrl: String? = null,
) {
    @Volatile var detailLoaded: Boolean = false
    @Volatile var readmeMarkdown: String? = null
    @Volatile var downloadUrl: String? = null
    @Volatile var gallery: List<String> = emptyList()
    @Volatile var requiredMods: List<MapRequirement> = emptyList()
    @Volatile var requiredPacks: List<MapRequirement> = emptyList()

    @Volatile var installedFolder: String? = null

    /** Installed entries only: a newer release than this save's marker is known. */
    @Volatile var updateAvailable: Boolean = false

    /** Newest published version when it is known */
    @Volatile var latestVersion: String? = null

    /** `level.dat` facts of the matching save; only set for Installed entries. */
    @Volatile var worldInfo: WorldInfo? = null

    /** Save size on disk, filled in the background when the entry is opened; -1 while unknown. */
    @Volatile var worldSizeBytes: Long = -1

    /** Newest supported MC version, shown on the list row's info line. */
    val displayVersion: String? get() = mcVersions.maxWithOrNull(::compareMcVersions)
}

/**
 * Persisted marker written into a save folder as `worlds.meta.json` on install, so the Installed
 * tab and the join-time hooks (pack toggle / mod check) work fully offline.
 */
@Serializable
data class InstalledMeta(
    val id: String,
    val source: MapSource,
    val title: String,
    val version: String? = null,
    val updated: Long = 0,
    val description: String? = null,
    val readme: String? = null,
    val icon: String? = null,
    val categories: List<String> = emptyList(),
    val downloads: Long = 0,
    val website: String? = null,
    val trailer: String? = null,
    val requiredMods: List<MapRequirement> = emptyList(),
    val requiredPacks: List<MapRequirement> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val FILE_NAME = "worlds.meta.json"
    }
}

/** Data from `level.dat` */
data class WorldInfo(
    val mcVersion: String? = null,
    val lastPlayed: Long = 0,
    val playTicks: Long = 0,
    val difficulty: String? = null,
    val hardcore: Boolean = false,
    val allowCommands: Boolean = false,
    /** Enabled data pack ids, `vanilla` dropped and the `file/` prefix stripped. */
    val dataPacks: List<String> = emptyList(),
)

/**
 * A world discovered by scanning the saves directory.
 * Manual created/added worlds get tagged as well with data from the level.dat
 */
data class InstalledMap(
    val saveFolder: String,
    /**
     * The `worlds.meta.json` marker, or `null` for a world this mod did not install
     */
    val meta: InstalledMeta?,
    /**
     * Absolute path to the save's own `icon.png` when present. Preferred over [InstalledMeta.icon]
     */
    val localIcon: String? = null,
    val levelName: String = saveFolder,
    val info: WorldInfo? = null,
) {
    /** Display title: the map's own title for managed worlds, else the world's name. */
    val title: String get() = meta?.title ?: levelName
    val mcVersion: String? get() = info?.mcVersion
    val lastPlayed: Long get() = info?.lastPlayed ?: 0

    companion object {
        const val MANUAL_CATEGORY = "manual"
    }
}
