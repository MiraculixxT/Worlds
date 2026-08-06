package de.miraculixx.worlds.data

import com.google.gson.JsonObject
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.server.players.NameAndId
import net.minecraft.stats.Stat
import net.minecraft.stats.StatType
import net.minecraft.stats.StatsCounter
import net.minecraft.util.StrictJsonParser
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.PlayerDataStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The save's own `playerdata/` and `stats/` folders, read on a *closed* world
 */
object WorldPlayers {

    /** One `playerdata/<uuid>.dat` file. */
    data class PlayerData(
        val id: UUID,
        val gameType: GameType,
        val xpLevel: Int,
        val health: Float,
        val hasStats: Boolean,
    ) {
        /** `Survival · Lvl 12 · 20❤` */
        fun summary(): String =
            "${gameType.shortDisplayName.string} · Lvl $xpLevel · ${health.roundToInt()}❤"
    }

    /** Session-wide uuid → name cache. Nothing in a save carries a name. */
    private val names = ConcurrentHashMap<UUID, String>()
    private val requested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var userCacheRead = false

    private const val PROFILE_API = "https://sessionserver.mojang.com/session/minecraft/profile/"
    private const val USER_CACHE = "usercache.json"

    @Serializable
    private data class CachedProfile(val uuid: String? = null, val name: String? = null)

    private const val LEGACY_STATS_VERSION = 1343

    @Serializable
    private data class MojangProfile(val name: String? = null)

    fun list(access: LevelStorageSource.LevelStorageAccess): List<PlayerData> {
        val dir = access.getLevelPath(LevelResource.PLAYER_DATA_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        readUserCache()
        val storage = PlayerDataStorage(access, Minecraft.getInstance().fixerUpper)
        val files = try {
            Files.newDirectoryStream(dir, "*.dat").use { it.toList() }
        } catch (e: Exception) {
            Constants.LOG.warn("Could not list player data of {}: {}", access.levelId, e.message)
            return emptyList()
        }
        return files.mapNotNull { read(access, storage, it) }.sortedBy { displayName(it.id).lowercase() }
    }

    /** Read through vanilla's own loader, so the tag is datafixed the way a join would fix it. */
    private fun read(
        access: LevelStorageSource.LevelStorageAccess,
        storage: PlayerDataStorage,
        file: Path,
    ): PlayerData? {
        val name = file.fileName.toString().removeSuffix(".dat")
        val id = try {
            UUID.fromString(name)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val tag = storage.load(NameAndId(id, displayName(id))).orElse(null) ?: return null
        return PlayerData(
            id = id,
            gameType = GameType.byId(tag.getIntOr("playerGameType", 0)),
            xpLevel = tag.getIntOr("XpLevel", 0),
            health = tag.getFloatOr("Health", 20f),
            hasStats = Files.isRegularFile(statsFile(access, id)),
        )
    }

    /** The resolved name, the client's own where the uuid matches, else a shortened uuid. */
    fun displayName(id: UUID): String {
        val user = Minecraft.getInstance().user
        if (user.profileId == id) return user.name
        return names[id] ?: id.toString().substringBefore('-')
    }

    private fun readUserCache() {
        if (userCacheRead) return
        userCacheRead = true
        val file = Minecraft.getInstance().gameDirectory.toPath().resolve(USER_CACHE)
        if (!Files.isRegularFile(file)) return
        val body = try {
            Files.readString(file)
        } catch (e: Exception) {
            Constants.LOG.warn("Could not read {}: {}", USER_CACHE, e.message)
            return
        }
        Http.decode<List<CachedProfile>>(body)?.forEach { profile ->
            val id = profile.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@forEach
            names.putIfAbsent(id, profile.name ?: return@forEach)
        }
    }

    /**
     * Look the still-unknown uuids up at Mojang, once per session each
     */
    fun resolveNames(ids: List<UUID>, onResolved: () -> Unit) {
        val own = Minecraft.getInstance().user.profileId
        ids.filter {
            it != own && it.version() == 4 && !names.containsKey(it) && requested.add(it)
        }.forEach { id ->
            Constants.SCOPE.launch {
                val body = Http.getString(PROFILE_API + id.toString().replace("-", ""))
                val name = Http.decode<MojangProfile>(body)?.name ?: return@launch
                names[id] = name
                Minecraft.getInstance().execute { onResolved() }
            }
        }
    }

    private fun statsFile(access: LevelStorageSource.LevelStorageAccess, id: UUID): Path =
        access.getLevelPath(LevelResource.PLAYER_STATS_DIR).resolve("$id.json")

    fun readStats(access: LevelStorageSource.LevelStorageAccess, id: UUID): StatsCounter {
        val counter = LocalStatsCounter()
        val file = statsFile(access, id)
        if (!Files.isRegularFile(file)) return counter
        try {
            val root = Files.newBufferedReader(file).use { StrictJsonParser.parse(it) }
            var dynamic = Dynamic(JsonOps.INSTANCE, root)
            dynamic = DataFixTypes.STATS.updateToCurrentVersion(
                Minecraft.getInstance().fixerUpper, dynamic, NbtUtils.getDataVersion(dynamic, LEGACY_STATS_VERSION),
            )
            val stats = (dynamic.value as? JsonObject)?.getAsJsonObject("stats") ?: return counter
            stats.entrySet().forEach { (typeId, values) ->
                val type = Identifier.tryParse(typeId)?.let { BuiltInRegistries.STAT_TYPE.getValue(it) }
                if (type == null || !values.isJsonObject) return@forEach
                values.asJsonObject.entrySet().forEach { (key, value) -> put(counter, type, key, value.asInt) }
            }
        } catch (e: Exception) {
            Constants.LOG.warn("Could not read stats of {}: {}", id, e.message)
        }
        return counter
    }

    /** A stat is its type plus an entry of that type's registry */
    @Suppress("UNCHECKED_CAST")
    private fun put(counter: LocalStatsCounter, type: StatType<*>, key: String, value: Int) {
        val entry = Identifier.tryParse(key)?.let { type.registry.getValue(it) } ?: return
        counter.put((type as StatType<Any>).get(entry), value)
    }

    /** `StatsCounter.setValue` takes the player its base implementation then ignores, and wants it non-null. */
    private class LocalStatsCounter : StatsCounter() {
        fun put(stat: Stat<*>, value: Int) {
            stats.put(stat, value)
        }
    }
}
