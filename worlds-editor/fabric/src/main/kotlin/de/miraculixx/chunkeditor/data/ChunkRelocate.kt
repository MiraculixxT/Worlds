package de.miraculixx.chunkeditor.data

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.util.datafix.DataFixers
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
     * Keeps only the sections [ranges] names (empty = all), shifts what survives by [yOffset] sections, and drags the block-level lists along
     * @return false if empty
     */
    fun selectSections(tag: CompoundTag, ranges: List<IntRange>, yOffset: Int): Boolean {
        if (ranges.isEmpty() && yOffset == 0) return true
        val sections = tag.list("sections")
        if (sections != null) {
            val kept = ListTag()
            sections.indices.forEach { i ->
                val section = sections.getCompoundOrEmpty(i)
                val y = sectionY(section)
                if (ranges.isEmpty() || ranges.any { y in it }) {
                    section.putInt("Y", y + yOffset)
                    kept.add(section)
                }
            }
            if (kept.isEmpty()) return false
            tag.put("sections", kept)
            tag.putInt("yPos", kept.indices.minOf { sectionY(kept.getCompoundOrEmpty(it)) })
        }
        listOf("block_entities", "block_ticks", "fluid_ticks").forEach { key ->
            tag.list(key)?.let { tag.put(key, filterAndShiftY(it, ranges, yOffset)) }
        }
        // Both describe a column that just changed under them
        tag.remove("Heightmaps")
        tag.remove("PostProcessing")
        tag.remove("structures")
        return true
    }

    /** The same vertical pass for an `entities/` chunk: entities outside [ranges] are left behind */
    fun selectEntities(tag: CompoundTag, ranges: List<IntRange>, yOffset: Int) {
        if (ranges.isEmpty() && yOffset == 0) return
        val entities = tag.list("Entities") ?: return
        val kept = ListTag()
        entities.indices.forEach { i ->
            val entity = entities.getCompoundOrEmpty(i)
            val pos = entity.list("Pos")?.takeIf { it.size == 3 } ?: return@forEach
            val y = pos.getDoubleOr(1, 0.0)
            if (ranges.isNotEmpty() && ranges.none { Math.floorDiv(y.toInt(), 16) in it }) return@forEach
            if (yOffset != 0) pos.setTag(1, DoubleTag.valueOf(y + yOffset * 16.0))
            kept.add(entity)
        }
        tag.put("Entities", kept)
    }

    /** The same vertical pass for a `poi/` chunk, which is already keyed by section */
    fun selectPoi(tag: CompoundTag, ranges: List<IntRange>, yOffset: Int) {
        if (ranges.isEmpty() && yOffset == 0) return
        val sections = tag.compound("Sections") ?: return
        val moved = CompoundTag()
        sections.allKeys.toList().forEach { key ->
            val y = key.toIntOrNull() ?: return@forEach
            if (ranges.isNotEmpty() && ranges.none { y in it }) return@forEach
            val section = sections.getCompoundOrEmpty(key)
            section.list("Records")?.let { records ->
                records.indices.forEach { i ->
                    val record = records.getCompoundOrEmpty(i)
                    record.getIntArray("pos").takeIf { it.isNotEmpty() }?.let { pos ->
                        if (pos.size == 3) {
                            record.put("pos", IntArrayTag(intArrayOf(pos[0], pos[1] + yOffset * 16, pos[2])))
                        }
                    }
                }
            }
            moved.put((y + yOffset).toString(), section)
        }
        tag.put("Sections", moved)
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
        if (kept.isEmpty()) return false
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
        tag.getIntArray("Position").takeIf { it.isNotEmpty() }?.let { pos ->
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
        sections.allKeys.toList().forEach { key ->
            val records = sections.getCompoundOrEmpty(key).list("Records") ?: return@forEach
            records.indices.forEach { i ->
                val record = records.getCompoundOrEmpty(i)
                record.getIntArray("pos").takeIf { it.isNotEmpty() }?.let { pos ->
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
        return type.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version)
    }

    fun mergeRegion(source: CompoundTag, destination: CompoundTag): CompoundTag {
        val incoming = sectionYs(source)
        mergeSections(source, destination)
        listOf("block_entities", "block_ticks", "fluid_ticks").forEach { key ->
            mergeByY(source, destination, key, incoming)
        }
        // The clip's own structures are gone by now (see selectSections)
        destination.putBoolean("isLightOn", false)
        destination.remove("Heightmaps")
        destination.remove("PostProcessing")
        return destination
    }

    fun mergeEntities(source: CompoundTag, destination: CompoundTag): CompoundTag {
        val kept = destination.list("Entities") ?: ListTag()
        source.list("Entities")?.let { incoming -> incoming.indices.forEach { kept.add(incoming[it]) } }
        destination.put("Entities", kept)
        return destination
    }

    fun mergePoi(source: CompoundTag, destination: CompoundTag): CompoundTag {
        val incoming = source.compound("Sections") ?: return destination
        val target = destination.compound("Sections") ?: CompoundTag().also { destination.put("Sections", it) }
        // A section the clip carries replaces the target's outright
        incoming.allKeys.toList().forEach { key -> target.put(key, incoming.getCompoundOrEmpty(key)) }
        return destination
    }

    private fun mergeSections(source: CompoundTag, destination: CompoundTag) {
        val incoming = source.list("sections") ?: return
        val existing = destination.list("sections") ?: ListTag()
        val byY = LinkedHashMap<Int, CompoundTag>()
        existing.indices.forEach { i -> byY[sectionY(existing.getCompoundOrEmpty(i))] = existing.getCompoundOrEmpty(i) }
        incoming.indices.forEach { i -> byY[sectionY(incoming.getCompoundOrEmpty(i))] = incoming.getCompoundOrEmpty(i) }
        val merged = ListTag()
        byY.keys.sorted().forEach { merged.add(byY.getValue(it)) }
        destination.put("sections", merged)
        destination.putInt("yPos", byY.keys.minOrNull() ?: destination.getIntOr("yPos", 0))
    }

    /** Target entries inside an imported section are replaced by the clip's, the rest stays */
    private fun mergeByY(source: CompoundTag, destination: CompoundTag, key: String, incoming: Set<Int>) {
        val kept = ListTag()
        destination.list(key)?.let { existing ->
            existing.indices.forEach { i ->
                val entry = existing.getCompoundOrEmpty(i)
                if (Math.floorDiv(entry.getIntOr("y", 0), 16) !in incoming) kept.add(entry)
            }
        }
        source.list(key)?.let { list -> list.indices.forEach { kept.add(list[it]) } }
        if (kept.isEmpty()) destination.remove(key) else destination.put(key, kept)
    }

    private fun sectionYs(tag: CompoundTag): Set<Int> {
        val sections = tag.list("sections") ?: return emptySet()
        return sections.indices.map { sectionY(sections.getCompoundOrEmpty(it)) }.toSet()
    }

    private fun sectionY(section: CompoundTag): Int =
        section.getIntOr("Y", section.getByteOr("Y", 0).toInt())

    private fun filterAndShiftY(list: ListTag, ranges: List<IntRange>, yOffset: Int): ListTag {
        val kept = ListTag()
        list.indices.forEach { i ->
            val entry = list.getCompoundOrEmpty(i)
            val y = entry.getIntOr("y", 0)
            if (ranges.isNotEmpty() && ranges.none { Math.floorDiv(y, 16) in it }) return@forEach
            if (yOffset != 0) entry.putInt("y", y + yOffset * 16)
            kept.add(entry)
        }
        return kept
    }

    //
    // Pieces
    //

    private fun entity(entity: CompoundTag, dx: Int, dz: Int) {
        entity.list("Pos")?.let { pos ->
            if (pos.size == 3) {
                pos.setTag(0, DoubleTag.valueOf(pos.getDoubleOr(0, 0.0) + dx * 16.0))
                pos.setTag(2, DoubleTag.valueOf(pos.getDoubleOr(2, 0.0) + dz * 16.0))
            }
        }
        // A paste back into the source world would otherwise hold every uuid twice.
        entity.getIntArray("UUID").takeIf { it.isNotEmpty() }?.let { entity.put("UUID", uuid()) }
        // Home, job site and meeting point are absolute positions the villager walks back to.
        entity.compound("Brain")?.remove("memories")
        listOf("home_pos", "sleeping_pos", "wander_target").forEach { key ->
            entity.getIntArray(key).takeIf { it.isNotEmpty() }?.let { pos ->
                if (pos.size == 3) entity.put(key, IntArrayTag(intArrayOf(pos[0] + dx * 16, pos[1], pos[2] + dz * 16)))
            }
        }
        entity.compound("leash")?.let { leash ->
            leash.intOrNull("X")?.let { leash.putInt("X", it + dx * 16) }
            leash.intOrNull("Z")?.let { leash.putInt("Z", it + dz * 16) }
        }
        entity.list("Passengers")?.let { list ->
            list.indices.forEach { i -> entity(list.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    /** `References` are packed chunk positions, `starts` carry chunk coords and block bounding boxes. */
    private fun structures(structures: CompoundTag, dx: Int, dz: Int) {
        structures.compound("References")?.let { references ->
            references.allKeys.toList().forEach { key ->
                val packed = references.getLongArray(key).takeIf { it.isNotEmpty() } ?: return@forEach
                references.putLongArray(key, LongArray(packed.size) { i ->
                    val pos = ChunkPos(packed[i])
                    ChunkPos.asLong(pos.x + dx, pos.z + dz)
                })
            }
        }
        structures.compound("starts")?.let { starts ->
            starts.allKeys.toList().forEach { key -> start(starts.getCompoundOrEmpty(key), dx, dz) }
        }
    }

    private fun start(start: CompoundTag, dx: Int, dz: Int) {
        if (start.getStringOr("id", "") == "INVALID") return
        start.intOrNull("ChunkX")?.let { start.putInt("ChunkX", it + dx) }
        start.intOrNull("ChunkZ")?.let { start.putInt("ChunkZ", it + dz) }
        start.list("Children")?.let { children ->
            children.indices.forEach { i -> piece(children.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    /** A structure piece: its own box, plus whatever child boxes and positions it carries. */
    private fun piece(piece: CompoundTag, dx: Int, dz: Int) {
        boundingBox(piece, "BB", dx, dz)
        piece.intOrNull("PosX")?.let { piece.putInt("PosX", it + dx * 16) }
        piece.intOrNull("PosZ")?.let { piece.putInt("PosZ", it + dz * 16) }
        piece.list("Entrances")?.let { list ->
            list.indices.forEach { i -> boundingBoxAt(list, i, dx, dz) }
        }
        piece.list("junctions")?.let { list ->
            list.indices.forEach { i ->
                val junction = list.getCompoundOrEmpty(i)
                junction.intOrNull("source_x")?.let { junction.putInt("source_x", it + dx * 16) }
                junction.intOrNull("source_z")?.let { junction.putInt("source_z", it + dz * 16) }
            }
        }
        piece.list("Children")?.let { children ->
            children.indices.forEach { i -> piece(children.getCompoundOrEmpty(i), dx, dz) }
        }
    }

    private fun boundingBox(holder: CompoundTag, key: String, dx: Int, dz: Int) {
        val box = holder.getIntArray(key).takeIf { it.isNotEmpty() } ?: return
        if (box.size != 6) return
        holder.put(key, IntArrayTag(intArrayOf(
            box[0] + dx * 16, box[1], box[2] + dz * 16,
            box[3] + dx * 16, box[4], box[5] + dz * 16,
        )))
    }

    private fun boundingBoxAt(list: ListTag, index: Int, dx: Int, dz: Int) {
        val box = list.getIntArray(index).takeIf { it.isNotEmpty() } ?: return
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
            entry.intOrNull("x")?.let { entry.putInt("x", it + dx * 16) }
            entry.intOrNull("z")?.let { entry.putInt("z", it + dz * 16) }
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
    private fun CompoundTag.compound(key: String): CompoundTag? = get(key) as? CompoundTag

    private fun CompoundTag.list(key: String): ListTag? = get(key) as? ListTag
}
