package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.players.NameAndId
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.PlayerDataStorage
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.name

data class PlayerMarker(val id: UUID, val pos: Vec3, val dimension: Identifier)

/**
 * The save's players as map markers.
 * A world with no `playerdata/` still has its host in `level.dat`
 */
object PlayerMarkers {

    suspend fun read(access: LevelStorageSource.LevelStorageAccess): List<PlayerMarker> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<UUID, PlayerMarker>()
            val dir = access.getLevelPath(LevelResource.PLAYER_DATA_DIR)
            if (Files.isDirectory(dir)) {
                // Vanilla's own loader, so the tag is datafixed the way a join would fix it
                val storage = PlayerDataStorage(access, Minecraft.getInstance().fixerUpper)
                try {
                    Files.newDirectoryStream(dir, "*.dat").use { stream ->
                        stream.forEach { file ->
                            val id = uuidOf(file.name.removeSuffix(".dat")) ?: return@forEach
                            val tag = storage.load(NameAndId(id, id.toString())).orElse(null) ?: return@forEach
                            marker(id, tag)?.let { found[id] = it }
                        }
                    }
                } catch (e: Exception) {
                    Constants.LOG.warn("Could not list player data of {}: {}", access.levelId, e.message)
                }
            }
            host(access)?.let { found.putIfAbsent(it.id, it) }
            found.values.toList()
        }

    /**
     * `level.dat` own copy of the host. It is not datafixed by [LevelFacts] read, and a legacy
     * `Dimension` is an int there, so it goes through the player fixer first.
     */
    private fun host(access: LevelStorageSource.LevelStorageAccess): PlayerMarker? = try {
        val data = access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag
        val raw = data.getCompound("Player").orElse(null)
        if (raw == null) null else {
            val version = data.getIntOr("DataVersion", 0)
            val tag = if (version >= currentDataVersion) raw else {
                DataFixTypes.PLAYER.updateToCurrentVersion(Minecraft.getInstance().fixerUpper, raw, version)
            }
            // A pre-1.16 tag carries no uuid at all
            val id = tag.read("UUID", UUIDUtil.LENIENT_CODEC).orElse(null)
                ?: Minecraft.getInstance().user.profileId
            marker(id, tag)
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read the host player of {}: {}", access.levelId, e.message)
        null
    }

    private fun marker(id: UUID, tag: CompoundTag): PlayerMarker? {
        val pos = tag.read("Pos", Vec3.CODEC).orElse(null) ?: return null
        val dimension = Identifier.tryParse(tag.getStringOr("Dimension", "")) ?: return null
        return PlayerMarker(id, pos, dimension)
    }

    private fun uuidOf(name: String): UUID? = try {
        UUID.fromString(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    private val currentDataVersion by lazy { SharedConstants.getCurrentVersion().dataVersion().version() }
}
