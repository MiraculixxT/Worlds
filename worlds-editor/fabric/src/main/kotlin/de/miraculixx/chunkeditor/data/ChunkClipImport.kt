package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.ChunkPos
import kotlin.io.path.name

sealed interface ImportResult {
    data class Success(val written: Int, val skipped: Int, val clipped: Int) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Load clip into world
 */
object ChunkClipImport {

    private val FIXES = mapOf(
        SUB_REGION to DataFixTypes.CHUNK,
        SUB_ENTITIES to DataFixTypes.ENTITY_CHUNK,
        SUB_POI to DataFixTypes.POI_CHUNK,
    )

    /**
     * @param [target] the dimension the map is currently showing
     * @param [origin] where the clip's own origin lands
     */
    fun import(
        clip: ClipInfo,
        target: WorldDimension,
        origin: ChunkPos,
        overwrite: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult {
        val source = clip.chunks
        if (source.isEmpty()) return ImportResult.Failure("chunkeditor.clip.error.empty")
        val clipVersion = clip.manifest?.dataVersion ?: 0
        if (clipVersion > ChunkClips.currentDataVersion) return ImportResult.Failure("chunkeditor.clip.error.newer")

        val dx = origin.x - clip.origin.x
        val dz = origin.z - clip.origin.z
        val range = sectionRange(target)
        val occupied = if (overwrite) emptySet() else existingChunks(target)

        var written = 0
        var skipped = 0
        var clipped = 0

        return try {
            // region/ decides which chunks land, poi & entity just follow
            val placed = HashSet<ChunkPos>(source.size)
            copy(clip, target, SUB_REGION, source, dx, dz) { pos, tag ->
                val to = ChunkPos(pos.x + dx, pos.z + dz)
                when {
                    to in occupied -> {
                        skipped++
                        false
                    }

                    !ChunkRelocate.clipSections(tag, range.first, range.second) -> {
                        clipped++
                        false
                    }

                    else -> {
                        ChunkRelocate.region(tag, dx, dz)
                        placed.add(pos)
                        written++
                        onProgress(written + skipped + clipped, source.size)
                        true
                    }
                }
            }
            copy(clip, target, SUB_ENTITIES, placed, dx, dz) { _, tag ->
                ChunkRelocate.entities(tag, dx, dz)
                true
            }
            copy(clip, target, SUB_POI, placed, dx, dz) { _, tag ->
                ChunkRelocate.poi(tag, dx, dz)
                true
            }
            ImportResult.Success(written, skipped, clipped)
        } catch (e: Exception) {
            Constants.LOG.error("Clip import failed", e)
            ImportResult.Failure("chunkeditor.clip.error.write")
        }
    }

    /** @return amt of override chunks */
    fun conflicts(clip: ClipInfo, target: WorldDimension, origin: ChunkPos): Int {
        val existing = existingChunks(target)
        val dx = origin.x - clip.origin.x
        val dz = origin.z - clip.origin.z
        return clip.chunks.count { ChunkPos(it.x + dx, it.z + dz) in existing }
    }

    private fun copy(
        clip: ClipInfo,
        target: WorldDimension,
        sub: String,
        chunks: Collection<ChunkPos>,
        dx: Int,
        dz: Int,
        transform: (ChunkPos, CompoundTag) -> Boolean,
    ) {
        if (chunks.isEmpty()) return
        val from = ChunkClips.storage(clip.dir, sub, target.key, false) ?: return
        from.use { source ->
            ChunkRegions.storage(target.dir.resolve(sub), target.dir.name, target.key, sub, true)!!.use { to ->
                chunks.forEach { pos ->
                    val raw = try {
                        source.read(pos)
                    } catch (e: Exception) {
                        Constants.LOG.warn("Failed to read chunk {} from clip {}: {}", pos, sub, e.message)
                        null
                    } ?: return@forEach
                    val tag = ChunkRelocate.datafix(FIXES.getValue(sub), raw)
                    if (!transform(pos, tag)) return@forEach
                    to.write(ChunkPos(pos.x + dx, pos.z + dz), tag)
                }
            }
        }
    }

    /**
     * The target's section range, probed off a chunk it already holds
     */
    private fun sectionRange(target: WorldDimension): Pair<Int, Int> {
        val store = ChunkRegions.storage(target, SUB_REGION) ?: return WIDE_OPEN
        store.use { source ->
            ChunkRegions.listRegions(target).forEach { (rx, rz) ->
                val index = ChunkRegions.readIndex(target, rx, rz) ?: return@forEach
                ChunkRegions.forEachChunk(index) { pos ->
                    val tag = runCatching { source.read(pos) }.getOrNull() ?: return@forEachChunk
                    val min = tag.getIntOr("yPos", Int.MIN_VALUE)
                    val sections = tag.getList("sections").orElse(null)?.size ?: 0
                    if (min != Int.MIN_VALUE && sections > 0) return min to min + sections - 1
                }
            }
        }
        return WIDE_OPEN
    }

    private fun existingChunks(target: WorldDimension): Set<Long> = buildSet {
        ChunkRegions.listRegions(target).forEach { (rx, rz) ->
            val index = ChunkRegions.readIndex(target, rx, rz) ?: return@forEach
            ChunkRegions.forEachChunk(index) { pos -> add(pos.pack()) }
        }
    }

    private operator fun Set<Long>.contains(pos: ChunkPos) = contains(pos.pack())

    private val WIDE_OPEN = Int.MIN_VALUE to Int.MAX_VALUE
}
