package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.visitors.CollectFields
import net.minecraft.nbt.visitors.FieldSelector
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import kotlin.io.path.name

/** One of the save's dimension folders */
data class WorldDimension(val key: ResourceKey<Level>, val label: String, val dir: Path) {
    val regionDir: Path get() = dir.resolve(SUB_REGION)
}

/** Which of a region's 1024 chunks are on disk */
class RegionIndex(val rx: Int, val rz: Int, val present: BitSet, val bytes: Long) {
    val count: Int get() = present.cardinality()

    operator fun contains(pos: ChunkPos): Boolean =
        present[pos.regionLocalZ * REGION_SIZE + pos.regionLocalX]
}

/** The two per-chunk longs the trim criteria compare against. */
data class ChunkFacts(val inhabitedTime: Long, val lastUpdate: Long)

const val REGION_SIZE = 32
private const val SUB_REGION = "region"
private const val SUB_ENTITIES = "entities"
private const val SUB_POI = "poi"
private const val HEADER_BYTES = 4096

private val REGION_NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

/**
 * The save's chunk storage, read without a server.
 */
object ChunkRegions {

    /**
     * Overworld / Nether / End plus every datapack dimension with a region folder.
     * Vanilla ones are short names, custom ones named `<ns>:<key>`
     */
    fun dimensions(access: LevelStorageSource.LevelStorageAccess): List<WorldDimension> {
        val found = LinkedHashMap<Path, WorldDimension>()
        fun offer(key: ResourceKey<Level>, label: String, dir: Path) {
            val normalized = dir.normalize()
            if (Files.isDirectory(normalized.resolve(SUB_REGION))) {
                found.putIfAbsent(normalized, WorldDimension(key, label, normalized))
            }
        }
        listOf(Level.OVERWORLD to "Overworld", Level.NETHER to "Nether", Level.END to "The End")
            .forEach { (key, label) -> offer(key, label, access.getDimensionPath(key)) }
        val custom = access.getLevelPath(LevelResource.ROOT).resolve("dimensions")
        if (Files.isDirectory(custom)) {
            Files.newDirectoryStream(custom).use { namespaces ->
                namespaces.filter { Files.isDirectory(it) }.forEach { ns ->
                    Files.newDirectoryStream(ns).use { paths ->
                        paths.filter { Files.isDirectory(it) }.forEach { dir ->
                            val id = Identifier.fromNamespaceAndPath(ns.name, dir.name)
                            offer(ResourceKey.create(Registries.DIMENSION, id), id.toString(), dir)
                        }
                    }
                }
            }
        }
        return found.values.toList()
    }

    /** Every `r.<x>.<z>.mca` in the dimension, as region coordinates. */
    fun listRegions(dimension: WorldDimension): List<Pair<Int, Int>> {
        val dir = dimension.regionDir
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { file ->
                REGION_NAME.matchEntire(file.name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            }
        }
    }

    /**
     * The region's chunk bitmap. Only the first 4 KiB are read
     */
    fun readIndex(dimension: WorldDimension, rx: Int, rz: Int): RegionIndex? {
        val file = regionFile(dimension.regionDir, rx, rz)
        if (!Files.isRegularFile(file)) return null
        return try {
            val header = ByteBuffer.allocate(HEADER_BYTES)
            val size = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                var read = 0
                while (read < HEADER_BYTES) {
                    val n = channel.read(header)
                    if (n <= 0) break
                    read += n
                }
                channel.size()
            }
            val present = BitSet(REGION_SIZE * REGION_SIZE)
            header.flip()
            var i = 0
            while (header.remaining() >= 4) {
                if (header.int != 0) present.set(i)
                i++
            }
            RegionIndex(rx, rz, present, size)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read region header {}: {}", file, e.message)
            null
        }
    }

    /**
     * Reads `InhabitedTime` / `LastUpdate` of every generated chunk in [regions]
     */
    suspend fun scanFields(
        dimension: WorldDimension,
        regions: Collection<RegionIndex>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Map<Long, ChunkFacts> = withContext(Dispatchers.IO) {
        val facts = HashMap<Long, ChunkFacts>()
        storage(dimension, SUB_REGION)?.use { store ->
            regions.forEachIndexed { done, region ->
                forEachChunk(region) { pos ->
                    val collector = CollectFields(
                        FieldSelector(LongTag.TYPE, "InhabitedTime"),
                        FieldSelector(LongTag.TYPE, "LastUpdate"),
                    )
                    try {
                        store.scanChunk(pos, collector)
                        val tag = collector.result as? CompoundTag ?: return@forEachChunk
                        facts[pos.pack()] = ChunkFacts(
                            tag.getLongOr("InhabitedTime", 0L),
                            tag.getLongOr("LastUpdate", 0L),
                        )
                    } catch (e: Exception) {
                        Constants.LOG.warn("Failed to scan chunk {}: {}", pos, e.message)
                    }
                }
                onProgress(done + 1, regions.size)
            }
        }
        facts
    }

    /**
     * Deletes [chunks] from the dimension's `region/`, `entities/` and `poi/` stores
     */
    fun deleteChunks(dimension: WorldDimension, chunks: Collection<ChunkPos>): Int {
        if (chunks.isEmpty()) return 0
        var deleted = 0
        listOf(SUB_REGION, SUB_ENTITIES, SUB_POI).forEach { sub ->
            storage(dimension, sub)?.use { store ->
                chunks.forEach { pos ->
                    try {
                        store.write(pos, null) // vanilla treats null as delete
                        if (sub == SUB_REGION) deleted++
                    } catch (e: Exception) {
                        Constants.LOG.warn("Failed to delete chunk {} from {}: {}", pos, sub, e.message)
                    }
                }
            }
        }
        // RegionFile.clear leaves the (now header-only) file behind; drop the empty ones.
        chunks.map { it.regionX to it.regionZ }.distinct().forEach { (rx, rz) ->
            listOf(SUB_REGION, SUB_ENTITIES, SUB_POI).forEach { sub ->
                val file = regionFile(dimension.dir.resolve(sub), rx, rz)
                val index = readIndexAt(file) ?: return@forEach
                if (index.isEmpty) runCatching { Files.deleteIfExists(file) }
            }
        }
        return deleted
    }

    fun regionFile(dir: Path, rx: Int, rz: Int): Path = dir.resolve("r.$rx.$rz.mca")

    inline fun forEachChunk(region: RegionIndex, action: (ChunkPos) -> Unit) {
        var i = region.present.nextSetBit(0)
        while (i >= 0) {
            action(ChunkPos(region.rx * REGION_SIZE + i % REGION_SIZE, region.rz * REGION_SIZE + i / REGION_SIZE))
            i = region.present.nextSetBit(i + 1)
        }
    }

    /**
     * A storage over one of the dimension's three chunk folders, or null when it does not exist
     */
    internal fun storage(dimension: WorldDimension, sub: String): RegionFileStorage? {
        val dir = dimension.dir.resolve(sub)
        if (!Files.isDirectory(dir)) return null
        val type = if (sub == SUB_REGION) "chunk" else sub
        return RegionFileStorage(RegionStorageInfo(dimension.dir.name, dimension.key, type), dir, false)
    }

    private fun readIndexAt(file: Path): BitSet? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val header = ByteBuffer.allocate(HEADER_BYTES)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                while (header.hasRemaining() && channel.read(header) > 0) Unit
            }
            val present = BitSet(REGION_SIZE * REGION_SIZE)
            header.flip()
            var i = 0
            while (header.remaining() >= 4) {
                if (header.int != 0) present.set(i)
                i++
            }
            present
        } catch (e: Exception) {
            null
        }
    }
}
