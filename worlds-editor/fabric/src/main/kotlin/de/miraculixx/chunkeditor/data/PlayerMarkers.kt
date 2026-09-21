package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.LevelDat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.util.datafix.DataFixers
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name

data class PlayerMarker(val id: UUID, val pos: Vec3, val dimension: ResourceLocation)

/**
 * The save's players as map markers.
 * A world with no `playerdata/` still has its host in `level.dat`
 */
object PlayerMarkers {

    /**
     * @param selfId who a pre-1.16 `level.dat` host with no uuid of its own is taken to be
     */
    suspend fun read(
        access: LevelStorageSource.LevelStorageAccess,
        selfId: UUID? = null,
    ): List<PlayerMarker> =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val found = LinkedHashMap<UUID, PlayerMarker>()
            val dir = access.getLevelPath(LevelResource.PLAYER_DATA_DIR)
            if (Files.isDirectory(dir)) {
                // 1.21's own loader wants a live player, so the file is read and fixed here
                readFolder(dir, access.levelId) { id -> readPlayerFile(dir.resolve("$id.dat")) }
                    .forEach { found[it.id] = it }
            }
            host(LevelDat.read(access), selfId)
                ?.let { found.putIfAbsent(it.id, it) }
            Constants.LOG.info("players {}: {} in {} ms", access.levelId, found.size, Constants.ms(started))
            found.values.toList()
        }

    /** The same read off the save root, for a caller that holds no access (a live server) */
    suspend fun read(root: Path, selfId: UUID? = null): List<PlayerMarker> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val found = LinkedHashMap<UUID, PlayerMarker>()
        val dir = root.resolve(LevelResource.PLAYER_DATA_DIR.id)
        if (Files.isDirectory(dir)) {
            readFolder(dir, root.name) { id -> readPlayerFile(dir.resolve("$id.dat")) }
                .forEach { found[it.id] = it }
        }
        host(levelTag(root), selfId)?.let { found.putIfAbsent(it.id, it) }
        Constants.LOG.info("players {}: {} in {} ms", root.name, found.size, Constants.ms(started))
        found.values.toList()
    }

    private inline fun readFolder(
        dir: Path, name: String, load: (UUID) -> CompoundTag?,
    ): List<PlayerMarker> = try {
        Files.newDirectoryStream(dir, "*.dat").use { stream ->
            stream.mapNotNull { file ->
                val id = uuidOf(file.name.removeSuffix(".dat")) ?: return@mapNotNull null
                load(id)?.let { marker(id, it) }
            }
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Could not list player data of {}: {}", name, e.message)
        emptyList()
    }

    private fun readPlayerFile(file: Path): CompoundTag? = try {
        val raw = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        val version = NbtUtils.getDataVersion(raw, 0)
        if (version >= currentDataVersion) raw
        else DataFixTypes.PLAYER.updateToCurrentVersion(DataFixers.getDataFixer(), raw, version)
    } catch (e: Exception) {
        Constants.LOG.warn("Unreadable player file {}: {}", file.name, e.message)
        null
    }

    private fun levelTag(root: Path): CompoundTag? = try {
        val file = root.resolve("level.dat")
        if (Files.isRegularFile(file)) {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data")
        } else null
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read level.dat of {}: {}", root, e.message)
        null
    }

    /**
     * `level.dat` own copy of the host. It is not datafixed by [LevelFacts] read, and a legacy
     * `Dimension` is an int there, so it goes through the player fixer first.
     */
    private fun host(data: CompoundTag?, selfId: UUID?): PlayerMarker? = try {
        val raw = data?.get("Player") as? CompoundTag
        if (raw == null) null else {
            val version = data.getIntOr("DataVersion", 0)
            val tag = if (version >= currentDataVersion) raw else {
                DataFixTypes.PLAYER.updateToCurrentVersion(DataFixers.getDataFixer(), raw, version)
            }
            // A pre-1.16 tag carries no uuid at all
            val id = tag.get("UUID")?.let { runCatching { NbtUtils.loadUUID(it) }.getOrNull() } ?: selfId
            if (id == null) null else marker(id, tag)
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read the host player: {}", e.message)
        null
    }

    private fun marker(id: UUID, tag: CompoundTag): PlayerMarker? {
        val pos = readPos(tag) ?: return null
        val dimension = ResourceLocation.tryParse(tag.getStringOr("Dimension", "")) ?: return null
        return PlayerMarker(id, pos, dimension)
    }

    private fun uuidOf(name: String): UUID? = try {
        UUID.fromString(name)
    } catch (_: IllegalArgumentException) {
        null
    }

    private val currentDataVersion by lazy { SharedConstants.getCurrentVersion().dataVersion.version }
}
