package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.StreamTagVisitor
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileVersion
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val SECTOR = 4096
private const val MAX_OPEN = 64

/**
 * Read-only chunk access to one chunk folder (`region/`, `entities/`, `poi/`) the game may be writing to.
 * Vanillas [net.minecraft.world.level.chunk.storage.RegionFile] opens w+r & adds a padding on close (can corrupt on async)
 */
class RegionReader(private val dir: Path, private val label: String) : AutoCloseable {

    /** Listed chunks that stayed unreadable after the retry */
    val failed = HashSet<Long>()

    private class Region(val channel: FileChannel?) {
        var offsets: IntBuffer? = null
    }

    private val regions = object : LinkedHashMap<Long, Region>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Region>): Boolean {
            if (size <= MAX_OPEN) return false
            runCatching { eldest.value.channel?.close() }
            return true
        }
    }

    fun read(pos: ChunkPos): CompoundTag? = retried(pos) { NbtIo.read(it) }

    /** Streams the chunk into [visitor], `false` when it is not there */
    fun scan(pos: ChunkPos, visitor: StreamTagVisitor): Boolean =
        retried(pos) { NbtIo.parse(it, visitor, NbtAccounter.unlimitedHeap()) } != null

    private fun <T : Any> retried(pos: ChunkPos, block: (DataInputStream) -> T): T? {
        val first = try {
            return attempt(pos, block)
        } catch (e: Exception) {
            e
        }
        region(pos).offsets = null
        return try {
            attempt(pos, block).also {
                if (it != null) Constants.LOG.info("chunk {} of {} read on the second try (its region was being written)", pos, label)
            }
        } catch (e: Exception) {
            failed.add(pos.pack())
            Constants.LOG.warn(
                "chunk {} of {} is listed in its region but could not be read: {} (retry: {})",
                pos, label, first.message, e.message,
            )
            null
        }
    }

    /** @return `null` when the header does not list the chunk, throws when it does but the data is broken */
    private fun <T : Any> attempt(pos: ChunkPos, block: (DataInputStream) -> T): T? {
        val region = region(pos)
        val channel = region.channel ?: return null
        val offsets = region.offsets ?: readHeader(channel).also { region.offsets = it }
        val location = offsets.get(pos.regionLocalZ * REGION_SIZE + pos.regionLocalX)
        if (location == 0) return null
        val sector = location ushr 8
        val count = location and 0xFF
        if (sector < 2 || count == 0) throw IOException("location $sector+$count overlaps the header")
        val buffer = ByteBuffer.allocate(count * SECTOR)
        var at = sector.toLong() * SECTOR
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n <= 0) break
            at += n
        }
        buffer.flip()
        if (buffer.remaining() < 5) throw IOException("truncated to ${buffer.remaining()} bytes")
        val length = buffer.int
        val versionId = buffer.get().toInt()
        if (length <= 0) throw IOException("declared size $length")
        val external = versionId and 0x80 != 0
        val raw: InputStream = if (external) {
            val file = dir.resolve("c.${pos.x}.${pos.z}.mcc")
            if (!Files.isRegularFile(file)) throw IOException("external file ${file.fileName} is missing")
            Files.newInputStream(file)
        } else {
            val streamLength = length - 1
            if (streamLength > buffer.remaining()) throw IOException("stream of $streamLength bytes, ${buffer.remaining()} present")
            ByteArrayInputStream(buffer.array(), buffer.position(), streamLength)
        }
        val version = RegionFileVersion.fromId(versionId and 0x7F)
        if (version == null || version == RegionFileVersion.VERSION_CUSTOM) {
            raw.close()
            throw IOException("unsupported compression $versionId")
        }
        return DataInputStream(version.wrap(raw)).use(block)
    }

    private fun readHeader(channel: FileChannel): IntBuffer {
        val header = ByteBuffer.allocate(SECTOR)
        var at = 0L
        while (header.hasRemaining()) {
            val n = channel.read(header, at)
            if (n <= 0) break
            at += n
        }
        header.flip()
        // A short header lists nothing past what arrived
        return IntBuffer.allocate(REGION_SIZE * REGION_SIZE).apply {
            while (header.remaining() >= 4) put(header.int)
            clear()
        }
    }

    private fun region(pos: ChunkPos): Region = regions.getOrPut(ChunkPos.pack(pos.regionX, pos.regionZ)) {
        val file = ChunkRegions.regionFile(dir, pos.regionX, pos.regionZ)
        val channel = if (Files.isRegularFile(file)) {
            runCatching { FileChannel.open(file, StandardOpenOption.READ) }
                .onFailure { Constants.LOG.warn("Failed to open {}: {}", file, it.message) }
                .getOrNull()
        } else null
        Region(channel)
    }

    override fun close() {
        regions.values.forEach { runCatching { it.channel?.close() } }
        regions.clear()
    }
}
