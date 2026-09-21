package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import java.nio.file.Files

data class EntityMarker(val type: String, val pos: Vec3)

/** A region with more is cut off, drawing them would be unreadable anyway */
const val MAX_ENTITIES_PER_REGION = 4096

private const val PLAYER_TYPE = "minecraft:player"

/**
 * The `entities/` store as map markers, one region at a time
 */
object EntityMarkers {

    suspend fun read(dimension: WorldDimension, rx: Int, rz: Int): List<EntityMarker> =
        withContext(Dispatchers.IO) {
            val dir = dimension.dir.resolve(SUB_ENTITIES)
            if (!Files.isRegularFile(ChunkRegions.regionFile(dir, rx, rz))) return@withContext emptyList()
            val index = ChunkRegions.readIndex(dir, rx, rz) ?: return@withContext emptyList()
            val found = ArrayList<EntityMarker>()
            try {
                ChunkRegions.storage(dimension, SUB_ENTITIES)?.use { store ->
                    ChunkRegions.forEachChunk(index) { pos ->
                        if (found.size >= MAX_ENTITIES_PER_REGION) return@forEachChunk
                        val tag = try {
                            store.read(pos)
                        } catch (e: Exception) {
                            Constants.LOG.warn("Unreadable entity chunk {}: {}", pos, e.message)
                            null
                        } ?: return@forEachChunk
                        tag.getListOrEmpty("Entities").forEach { entry ->
                            if (found.size >= MAX_ENTITIES_PER_REGION) return@forEach
                            marker(entry as? CompoundTag ?: return@forEach)?.let(found::add)
                        }
                    }
                }
            } catch (e: Exception) {
                Constants.LOG.warn("Failed to read entities of r.{}.{}: {}", rx, rz, e.message)
            }
            found
        }

    private fun marker(tag: CompoundTag): EntityMarker? {
        val type = tag.getStringOr("id", "")
        if (type.isEmpty() || type == PLAYER_TYPE || EntityMarkerConfig.hidden(type)) return null
        val pos = readPos(tag) ?: return null
        return EntityMarker(type, pos)
    }
}

/** 1.21 has no codec read on a tag, and `Pos` is a plain list of three doubles */
internal fun readPos(tag: CompoundTag): Vec3? {
    val pos = tag.getListOrEmpty("Pos")
    if (pos.size != 3) return null
    return Vec3(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2))
}
