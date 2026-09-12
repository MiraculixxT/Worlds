package de.miraculixx.chunkeditor.data

import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.ChunkPos
import java.util.UUID

/**
 * Rewrites a chunk tag for a move by whole chunks. One entry point per chunk folder, since the three
 * carry positions in completely different shapes.
 * Y is never shifted: sections outside the target's range are dropped instead ([clipSections]).
 */
object ChunkRelocate {

    fun region(tag: CompoundTag, dx: Int, dz: Int) {
        if (dx != 0 || dz != 0) {
            tag.putInt("xPos", tag.getIntOr("xPos", 0) + dx)
            tag.putInt("zPos", tag.getIntOr("zPos", 0) + dz)
            shiftBlockXZ(tag.list("block_entities"), dx, dz)
            shiftBlockXZ(tag.list("block_ticks"), dx, dz)
            shiftBlockXZ(tag.list("fluid_ticks"), dx, dz)
            tag.compound("structures")?.let { structures(it, dx, dz) }
        }
        // neighbors change, so we cant know what light is correct
        tag.putBoolean("isLightOn", false)
    }

    /**
     * Drops the sections the target dimension has no room for
     */
    fun clipSections(tag: CompoundTag, minSection: Int, maxSection: Int): Boolean {
        val sections = tag.list("sections") ?: return true
        val kept = ListTag()
        sections.indices.forEach { i ->
            val section = sections.getCompoundOrEmpty(i)
            val y = section.getIntOr("Y", section.getByteOr("Y", 0).toInt())
            if (y in minSection..maxSection) kept.add(section)
        }
        if (kept.isEmpty) return false
        if (kept.size != sections.size) {
            tag.put("sections", kept)
            // Both are derived from the column, which just lost part of itself (poor things, leave a like to support em)
            tag.remove("Heightmaps")
            tag.remove("PostProcessing")
        }
        return true
    }

    /** `entities/`: the chunk position, every entity's own position, and their identities. */
    fun entities(tag: CompoundTag, dx: Int, dz: Int) {
        if (dx == 0 && dz == 0) return
        tag.getIntArray("Position").orElse(null)?.let { pos ->
            if (pos.size == 2) tag.put("Position", IntArrayTag(intArrayOf(pos[0] + dx, pos[1] + dz)))
        }
        tag.list("Entities")?.let { list ->
            list.indices.forEach { i -> entity(list.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    /** `poi/`: every record's block position. */
    fun poi(tag: CompoundTag, dx: Int, dz: Int) {
        if (dx == 0 && dz == 0) return
        val sections = tag.compound("Sections") ?: return
        sections.keySet().toList().forEach { key ->
            val records = sections.getCompoundOrEmpty(key).list("Records") ?: return@forEach
            records.indices.forEach { i ->
                val record = records.getCompoundOrEmpty(i)
                record.getIntArray("pos").orElse(null)?.let { pos ->
                    if (pos.size == 3) {
                        record.put("pos", IntArrayTag(intArrayOf(pos[0] + dx * 16, pos[1], pos[2] + dz * 16)))
                    }
                }
            }
        }
    }

    /**
     * If clip is from older version, upgrade it
     */
    fun datafix(type: DataFixTypes, tag: CompoundTag): CompoundTag {
        val version = tag.getIntOr(SharedConstants.DATA_VERSION_TAG, 0)
        if (version >= ChunkClips.currentDataVersion) return tag
        return type.updateToCurrentVersion(Minecraft.getInstance().fixerUpper, tag, version)
    }

    //
    // Pieces
    //

    private fun entity(entity: CompoundTag, dx: Int, dz: Int) {
        entity.list("Pos")?.let { pos ->
            if (pos.size == 3) {
                pos.setTag(0, net.minecraft.nbt.DoubleTag.valueOf(pos.getDoubleOr(0, 0.0) + dx * 16.0))
                pos.setTag(2, net.minecraft.nbt.DoubleTag.valueOf(pos.getDoubleOr(2, 0.0) + dz * 16.0))
            }
        }
        // A paste back into the source world would otherwise hold every uuid twice.
        entity.getIntArray("UUID").orElse(null)?.let { entity.put("UUID", uuid()) }
        // Home, job site and meeting point are absolute positions the villager walks back to.
        entity.compound("Brain")?.remove("memories")
        listOf("home_pos", "sleeping_pos", "wander_target").forEach { key ->
            entity.getIntArray(key).orElse(null)?.let { pos ->
                if (pos.size == 3) entity.put(key, IntArrayTag(intArrayOf(pos[0] + dx * 16, pos[1], pos[2] + dz * 16)))
            }
        }
        entity.compound("leash")?.let { leash ->
            leash.getInt("X").orElse(null)?.let { leash.putInt("X", it + dx * 16) }
            leash.getInt("Z").orElse(null)?.let { leash.putInt("Z", it + dz * 16) }
        }
        entity.list("Passengers")?.let { list ->
            list.indices.forEach { i -> entity(list.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    /** `References` are packed chunk positions, `starts` carry chunk coords and block bounding boxes. */
    private fun structures(structures: CompoundTag, dx: Int, dz: Int) {
        structures.compound("References")?.let { references ->
            references.keySet().toList().forEach { key ->
                val packed = references.getLongArray(key).orElse(null) ?: return@forEach
                references.putLongArray(key, LongArray(packed.size) { i ->
                    val pos = ChunkPos.unpack(packed[i])
                    ChunkPos.pack(pos.x + dx, pos.z + dz)
                })
            }
        }
        structures.compound("starts")?.let { starts ->
            starts.keySet().toList().forEach { key -> start(starts.getCompoundOrEmpty(key), dx, dz) }
        }
    }

    private fun start(start: CompoundTag, dx: Int, dz: Int) {
        if (start.getStringOr("id", "") == "INVALID") return
        start.getInt("ChunkX").orElse(null)?.let { start.putInt("ChunkX", it + dx) }
        start.getInt("ChunkZ").orElse(null)?.let { start.putInt("ChunkZ", it + dz) }
        start.list("Children")?.let { children ->
            children.indices.forEach { i -> piece(children.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    /** A structure piece: its own box, plus whatever child boxes and positions it carries. */
    private fun piece(piece: CompoundTag, dx: Int, dz: Int) {
        boundingBox(piece, "BB", dx, dz)
        piece.getInt("PosX").orElse(null)?.let { piece.putInt("PosX", it + dx * 16) }
        piece.getInt("PosZ").orElse(null)?.let { piece.putInt("PosZ", it + dz * 16) }
        piece.list("Entrances")?.let { list ->
            list.indices.forEach { i -> boundingBoxAt(list, i, dx, dz) }
        }
        piece.list("junctions")?.let { list ->
            list.indices.forEach { i ->
                val junction = list.getCompoundOrEmpty(i)
                junction.getInt("source_x").orElse(null)?.let { junction.putInt("source_x", it + dx * 16) }
                junction.getInt("source_z").orElse(null)?.let { junction.putInt("source_z", it + dz * 16) }
            }
        }
        piece.list("Children")?.let { children ->
            children.indices.forEach { i -> piece(children.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    private fun boundingBox(holder: CompoundTag, key: String, dx: Int, dz: Int) {
        val box = holder.getIntArray(key).orElse(null) ?: return
        if (box.size != 6) return
        holder.put(key, IntArrayTag(intArrayOf(
            box[0] + dx * 16, box[1], box[2] + dz * 16,
            box[3] + dx * 16, box[4], box[5] + dz * 16,
        )))
    }

    private fun boundingBoxAt(list: ListTag, index: Int, dx: Int, dz: Int) {
        val box = list.getIntArray(index).orElse(null) ?: return
        if (box.size != 6) return
        list.setTag(index, IntArrayTag(intArrayOf(
            box[0] + dx * 16, box[1], box[2] + dz * 16,
            box[3] + dx * 16, box[4], box[5] + dz * 16,
        )))
    }

    /** Every entry of a list of compounds carrying absolute `x`/`z` block coordinates. */
    private fun shiftBlockXZ(list: ListTag?, dx: Int, dz: Int) {
        list ?: return
        list.indices.forEach { i ->
            val entry = list.getCompoundOrEmpty(i)
            entry.getInt("x").orElse(null)?.let { entry.putInt("x", it + dx * 16) }
            entry.getInt("z").orElse(null)?.let { entry.putInt("z", it + dz * 16) }
        }
    }

    private fun uuid(): IntArrayTag {
        val uuid = UUID.randomUUID()
        val most = uuid.mostSignificantBits
        val least = uuid.leastSignificantBits
        return IntArrayTag(intArrayOf(
            (most shr 32).toInt(), most.toInt(), (least shr 32).toInt(), least.toInt(),
        ))
    }

    // null-able instead vanillas silent creation
    private fun CompoundTag.compound(key: String): CompoundTag? = getCompound(key).orElse(null)

    private fun CompoundTag.list(key: String): ListTag? = getList(key).orElse(null)
}
