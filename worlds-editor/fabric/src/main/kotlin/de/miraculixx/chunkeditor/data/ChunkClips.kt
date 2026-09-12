package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

const val CLIP_FORMAT = 1
private const val MANIFEST = "clip.json"

@Serializable
data class ClipManifest(
    val format: Int = CLIP_FORMAT,
    val dataVersion: Int = 0,
    val mcVersion: String = "",
    val dimension: String = "",
    val originX: Int = 0,
    val originZ: Int = 0,
    val chunks: List<List<Int>> = emptyList(),
    val created: Long = 0L,
)

/** One folder in the clip library, with whatever could be learned about it. */
class ClipInfo(
    val dir: Path,
    val manifest: ClipManifest?,
    val chunks: List<ChunkPos>,
    val bytes: Long,
    val modified: Long,
) {
    val name: String get() = dir.name
    val dimension: String get() = manifest?.dimension?.ifBlank { null } ?: "?"
    val mcVersion: String get() = manifest?.mcVersion?.ifBlank { null } ?: "?"

    /** Lowest chunk of the footprint */
    val origin: ChunkPos
        get() = manifest?.let { ChunkPos(it.originX, it.originZ) }
            ?: chunks.minWithOrNull(compareBy({ it.z }, { it.x }))
            ?: ChunkPos(0, 0)
}

/**
 * A clip persists out of raw region/ poi/ entity/ folders, storing a part world
 */
object ChunkClips {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    val currentDataVersion: Int get() = SharedConstants.getCurrentVersion().dataVersion().version()

    fun libraryDir(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("chunkclips")

    fun list(): List<ClipInfo> {
        val dir = libraryDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            // _selections is the CSV folder
            stream.filter { Files.isDirectory(it) && it.name != SelectionCsv.FOLDER }.mapNotNull { read(it) }
        }.sortedByDescending { it.modified }
    }

    /** Null when the folder holds no `region/` at all, e.g. it is not a clip. */
    fun read(dir: Path): ClipInfo? {
        val regionDir = dir.resolve(SUB_REGION)
        if (!Files.isDirectory(regionDir)) return null
        val manifest = runCatching { json.decodeFromString<ClipManifest>(dir.resolve(MANIFEST).readText()) }
            .onFailure { if (Files.exists(dir.resolve(MANIFEST))) Constants.LOG.warn("Unreadable {} in {}", MANIFEST, dir) }
            .getOrNull()
        val indices = ChunkRegions.listRegions(regionDir).mapNotNull { (rx, rz) -> ChunkRegions.readIndex(regionDir, rx, rz) }
        val chunks = manifest?.chunks?.mapNotNull { if (it.size == 2) ChunkPos(it[0], it[1]) else null }
            ?.takeIf { it.isNotEmpty() }
            ?: buildList { indices.forEach { ChunkRegions.forEachChunk(it) { pos -> add(pos) } } }
        val modified = runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)
        return ClipInfo(dir, manifest, chunks, indices.sumOf { it.bytes }, modified)
    }

    fun write(dir: Path, manifest: ClipManifest) {
        dir.resolve(MANIFEST).writeText(json.encodeToString(manifest))
    }

    fun delete(clip: ClipInfo): Boolean = runCatching {
        Files.walk(clip.dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        true
    }.onFailure { Constants.LOG.warn("Failed to delete clip {}: {}", clip.name, it.message) }.getOrDefault(false)

    /** `<library>/<name>`, `<name>-2`, … so an export never silently merges into an older clip. */
    fun freeDir(name: String): Path {
        val base = sanitize(name)
        val library = libraryDir()
        var candidate = library.resolve(base)
        var i = 2
        while (Files.exists(candidate)) candidate = library.resolve("$base-${i++}")
        return candidate
    }

    fun sanitize(name: String): String = name.trim()
        .map { if (it.isLetterOrDigit() || it in "-_. ") it else '_' }
        .joinToString("").replace(' ', '_').trimStart('_').ifBlank { "clip" }

    fun storage(dir: Path, sub: String, dimension: ResourceKey<Level>, create: Boolean): RegionFileStorage? =
        ChunkRegions.storage(dir.resolve(sub), dir.name, dimension, sub, create)

    fun dimensionKey(id: String): ResourceKey<Level> =
        Identifier.tryParse(id)?.let { ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, it) }
            ?: Level.OVERWORLD
}
