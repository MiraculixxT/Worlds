package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
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

/**
 * One of the save's dimension folders
 * @param labelKey a translation key for the vanilla three, the dimension id itself for a custom one
 */
data class WorldDimension(val key: ResourceKey<Level>, val labelKey: String, val dir: Path) {
    val regionDir: Path get() = dir.resolve(SUB_REGION)
}

/** Which of a region's 1024 chunks are on disk */
class RegionIndex(val rx: Int, val rz: Int, val present: BitSet, val bytes: Long) {
    val count: Int get() = present.cardinality()

    operator fun contains(pos: ChunkPos): Boolean =
        present[pos.regionLocalZ * REGION_SIZE + pos.regionLocalX]
}

const val REGION_SIZE = 32
const val SUB_REGION = "region"
const val SUB_ENTITIES = "entities"
const val SUB_POI = "poi"

val CHUNK_SUBS = listOf(SUB_REGION, SUB_ENTITIES, SUB_POI)
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
    fun dimensions(access: LevelStorageSource.LevelStorageAccess): List<WorldDimension> =
        dimensions(access.getLevelPath(LevelResource.ROOT))

    /**
     * The same scan off the save root alone
     */
    fun dimensions(root: Path): List<WorldDimension> {
        val found = LinkedHashMap<Path, WorldDimension>()
        fun offer(key: ResourceKey<Level>, labelKey: String, dir: Path) {
            val normalized = dir.normalize()
            if (Files.isDirectory(normalized.resolve(SUB_REGION))) {
                found.putIfAbsent(normalized, WorldDimension(key, labelKey, normalized))
            }
        }
        listOf(
            Level.OVERWORLD to "chunkeditor.dimension.overworld",
            Level.NETHER to "chunkeditor.dimension.nether",
            Level.END to "chunkeditor.dimension.end",
        ).forEach { (key, labelKey) -> offer(key, labelKey, DimensionType.getStorageFolder(key, root)) }
        val custom = root.resolve("dimensions")
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
    fun listRegions(dimension: WorldDimension): List<Pair<Int, Int>> = listRegions(dimension.regionDir)

    fun listRegions(dir: Path): List<Pair<Int, Int>> {
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
    fun readIndex(dimension: WorldDimension, rx: Int, rz: Int): RegionIndex? =
        readIndex(dimension.regionDir, rx, rz)

    fun readIndex(dir: Path, rx: Int, rz: Int): RegionIndex? {
        val file = regionFile(dir, rx, rz)
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
     * Read a chunks max & min world height
     */
    suspend fun heightBounds(dimension: WorldDimension, pos: ChunkPos): IntRange? = withContext(Dispatchers.IO) {
        val store = storage(dimension, SUB_REGION) ?: return@withContext null
        try {
            val tag = store.read(pos) ?: return@withContext null
            val sections = tag.getListOrEmpty("sections")
                .mapNotNull { (it as? CompoundTag)?.getByte("Y")?.orElse(null)?.toInt() }
            if (sections.isEmpty()) return@withContext null
            sections.min() * 16..sections.max() * 16 + 15
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read height bounds of {}: {}", pos, e.message)
            null
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Quick scan timestamps of each chunk via header
     */
    fun readTimestamps(dir: Path, rx: Int, rz: Int): IntArray? {
        val file = regionFile(dir, rx, rz)
        if (!Files.isRegularFile(file)) return null
        return try {
            val header = ByteBuffer.allocate(HEADER_BYTES * 2)
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                while (header.hasRemaining() && channel.read(header) > 0) Unit
            }
            if (header.position() < HEADER_BYTES * 2) return null
            header.flip().position(HEADER_BYTES)
            IntArray(REGION_SIZE * REGION_SIZE) { header.int }
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read region timestamps {}: {}", file, e.message)
            null
        }
    }

    /**
     * Deletes [chunks] from the dimension's `region/`, `entities/` and `poi/` stores
     */
    fun deleteChunks(dimension: WorldDimension, chunks: Collection<ChunkPos>): Int {
        if (chunks.isEmpty()) return 0
        var deleted = 0
        CHUNK_SUBS.forEach { sub ->
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
            CHUNK_SUBS.forEach { sub ->
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
    internal fun storage(dimension: WorldDimension, sub: String): RegionFileStorage? =
        storage(dimension.dir.resolve(sub), dimension.dir.name, dimension.key, sub, false)

    /**
     * A store over one of the three chunk folders (save or clip)
     */
    internal fun storage(
        dir: Path, level: String, key: ResourceKey<Level>, sub: String, create: Boolean,
    ): RegionFileStorage? {
        if (create) Files.createDirectories(dir) else if (!Files.isDirectory(dir)) return null
        val type = if (sub == SUB_REGION) "chunk" else sub
        return RegionFileStorage(RegionStorageInfo(level, key, type), dir, false)
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
        } catch (_: Exception) {
            null
        }
    }
}
