package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import de.miraculixx.chunkeditor.data.ChunkClipImport
import de.miraculixx.chunkeditor.data.LocalLibrary
import de.miraculixx.chunkeditor.data.ChunkRegions
import de.miraculixx.chunkeditor.data.ClipImportOptions
import de.miraculixx.chunkeditor.data.ExistingChunks
import de.miraculixx.chunkeditor.data.ImportResult
import de.miraculixx.chunkeditor.data.WorldDimension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.util.datafix.DataFixers
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class JobKind { DELETE, PASTE }

/**
 * Queued edit actions for servers
 * @param dimension dimension id (`minecraft:overworld`)
 * @param ranges [ClipImportOptions.ranges] as `from:to` pairs, since an `IntRange` has no serializer
 */
@Serializable
data class Job(
    val id: String,
    val kind: JobKind,
    val dimension: String,
    val clip: String = "",
    val chunks: List<Long> = emptyList(),
    val originX: Int = 0,
    val originZ: Int = 0,
    val yOffset: Int = 0,
    val ranges: List<String> = emptyList(),
    val existing: ExistingChunks = ExistingChunks.REPLACE,
    val backup: Boolean = true,
    val by: String = "",
    val at: Long = 0L,
) {
    fun options() = ClipImportOptions(yOffset, ranges.mapNotNull(::range), existing)

    private fun range(text: String): IntRange? {
        val split = text.split(':')
        if (split.size != 2) return null
        val from = split[0].toIntOrNull() ?: return null
        val to = split[1].toIntOrNull() ?: return null
        return from..to
    }
}

/**
 * Queue data: `config/chunkeditor/jobs/<id>/job.json` & affected clips in `clip/`
 */
object ServerJobs {

    private const val MANIFEST = "job.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun root(): Path = Loader.configDir.resolve("chunkeditor/jobs")

    fun list(): List<Job> {
        val dir = root()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }.mapNotNull { read(it) }
        }.sortedBy { it.at }
    }

    fun dirOf(id: String): Path = root().resolve(id)

    /** @return the job's own folder */
    fun submit(job: Job): Path {
        val dir = dirOf(job.id)
        dir.createDirectories()
        dir.resolve(MANIFEST).writeText(json.encodeToString(job))
        Constants.LOG.info("Queued {} job {} by {}", job.kind, job.id, job.by)
        return dir
    }

    fun cancel(id: String): Boolean = list().any { it.id == id } && delete(dirOf(id))

    /** One backup covers the whole queue, so the flag is kept equal on every job */
    fun setBackup(backup: Boolean) {
        list().filter { it.backup != backup }.forEach {
            dirOf(it.id).resolve(MANIFEST).writeText(json.encodeToString(it.copy(backup = backup)))
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Runs everything queued, in the order it was asked for */
    fun applyAll(path: Path) {
        val jobs = list()
        if (jobs.isEmpty()) return
        // LevelResource.ROOT is ".", so the server hands over `./world/.`
        val worldRoot = path.toAbsolutePath().normalize()
        val started = System.nanoTime()
        Constants.LOG.info("applying {} queued job(s) to {}", jobs.size, worldRoot)
        if (jobs.any { it.backup }) backup(worldRoot)
        val dimensions = ChunkRegions.dimensions(worldRoot).associateBy { it.key.identifier().toString() }
        jobs.forEach { job ->
            val dimension = dimensions[job.dimension]
            if (dimension == null) {
                Constants.LOG.warn("Job {} names dimension {}, which this world has not", job.id, job.dimension)
                return@forEach
            }
            try {
                val jobStarted = System.nanoTime()
                val done = apply(job, dimension)
                Constants.LOG.info("job {} {}: {} in {} ms", job.kind, job.id, if (done) "applied" else "kept", Constants.ms(jobStarted))
                if (done) delete(dirOf(job.id))
            } catch (e: Exception) {
                Constants.LOG.error("Job {} failed and stays queued", job.id, e)
            }
        }
        Constants.LOG.info("applied {} job(s) in {} ms", jobs.size, Constants.ms(started))
    }

    private fun apply(job: Job, dimension: WorldDimension): Boolean = when (job.kind) {
        // ChunkRegions and ChunkClipImport log the counts and the cost themselves
        JobKind.DELETE -> {
            ChunkRegions.deleteChunks(dimension, job.chunks.map { ChunkPos(it) })
            true
        }

        JobKind.PASTE -> {
            val clip = LocalLibrary.read(job.clip)
            if (clip == null) {
                Constants.LOG.warn("job {} names clip {}, which the library has not", job.id, job.clip)
                false
            } else {
                val result = ChunkClipImport.import(
                    clip, dimension, ChunkPos(job.originX, job.originZ), job.options(),
                ) { _, _ -> }
                if (result is ImportResult.Failure) Constants.LOG.warn("job {} failed: {}", job.id, result.message)
                result is ImportResult.Success
            }
        }
    }

    /**
     * Vanillas own backup zip (server frees, backup locks & freeze again)
     */
    private fun backup(worldRoot: Path) {
        val started = System.nanoTime()
        try {
            val base = worldRoot.parent ?: return
            // Not createDefault to put a proper backup folder
            val source = LevelStorageSource(
                base,
                base.resolve("backups"),
                LevelStorageSource.parseValidator(base.resolve(LevelStorageSource.ALLOWED_SYMLINKS_CONFIG_NAME)),
                DataFixers.getDataFixer(),
            )
            source.createAccess(worldRoot.name).use { access ->
                val bytes = access.makeWorldBackup()
                Constants.LOG.info("backup {}: {} bytes in {} ms", worldRoot.name, bytes, Constants.ms(started))
            }
        } catch (e: Exception) {
            Constants.LOG.error("Could not back up {} — jobs are applied anyway", worldRoot, e)
        }
    }

    private fun read(dir: Path): Job? = try {
        // A folder with no manifest is a clip still being uploaded, not a broken job
        val manifest = dir.resolve(MANIFEST)
        if (!Files.isRegularFile(manifest)) null else json.decodeFromString<Job>(manifest.readText())
    } catch (e: Exception) {
        Constants.LOG.warn("Unreadable job in {}: {}", dir, e.message)
        null
    }

    private fun delete(dir: Path): Boolean = try {
        if (Files.isDirectory(dir)) {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        true
    } catch (e: Exception) {
        Constants.LOG.warn("Could not drop job folder {}: {}", dir, e.message)
        false
    }
}
