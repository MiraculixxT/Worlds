package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.data.ChunkyTask
import de.miraculixx.chunkeditor.data.GenerateOutcome
import de.miraculixx.common.Loader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import org.popcraft.chunky.ChunkyProvider
import org.popcraft.chunky.util.Input
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name

private const val CHUNKY = "chunky"
private const val CSV_PREFIX = "chunkeditor-"
private const val CSV_SUFFIX = ".csv"

/** Files older than that are ignored */
private const val STALE_CSV_DAYS = 30L

/**
 * Pre-generation over Chunkys API
 */
object ChunkyBridge {

    private val impl: Calls? by lazy {
        if (!Loader.isModLoaded(CHUNKY)) return@lazy null
        runCatching { ChunkyCalls() }
            .onFailure { Constants.LOG.warn("Chunky is installed but its api did not load", it) }
            .getOrNull()
    }

    val available: Boolean get() = impl != null

    interface Calls {
        fun isRunning(world: String): Boolean

        /** @param chunks the exact list, `null` runs the shape itself */
        fun start(task: ChunkyTask, world: String, chunks: Collection<ChunkPos>?): GenerateOutcome
    }

    /**
     * @param world the dimension id
     */
    fun start(
        server: MinecraftServer,
        world: String,
        task: ChunkyTask,
        chunks: Collection<ChunkPos>?,
    ): GenerateOutcome {
        val calls = impl ?: return GenerateOutcome.Failed("chunkeditor.pregen.error.missing")
        return try {
            // Its api walks the running levels, so the call belongs on the thread that owns them
            CompletableFuture.supplyAsync({ calls.start(task, world, chunks) }, server).join()
        } catch (e: Exception) {
            Constants.LOG.warn("Pre-generation of {} failed to start", world, e)
            GenerateOutcome.Failed("chunkeditor.pregen.error.failed")
        }
    }

    fun isRunning(world: String): Boolean = impl?.isRunning(world) == true
}

private class ChunkyCalls : ChunkyBridge.Calls {

    /** Which csv a world task is reading, so nothing deletes it while that task can still resume */
    private val running = ConcurrentHashMap<String, Path>()

    init {
        ChunkyProvider.get().api.onGenerationComplete { event -> onComplete(event.world()) }
        sweep()
    }

    override fun isRunning(world: String) = runCatching { ChunkyProvider.get().api.isRunning(world) }.getOrDefault(false)

    override fun start(task: ChunkyTask, world: String, chunks: Collection<ChunkPos>?): GenerateOutcome {
        val chunky = ChunkyProvider.get()
        if (Input.tryWorld(chunky, world).isEmpty) return GenerateOutcome.Failed("chunkeditor.pregen.error.world")
        if (chunky.api.isRunning(world)) return GenerateOutcome.Failed("chunkeditor.pregen.error.busy")

        val pattern = if (chunks == null) task.shape.pattern else {
            val file = write(chunky.config.directory, world, chunks)
                ?: return GenerateOutcome.Failed("chunkeditor.pregen.error.failed")
            running[world] = file
            "csv=${file.name.removeSuffix(CSV_SUFFIX)}"
        }
        val started = chunky.api.startTask(
            world, task.shape.chunky, task.centerX, task.centerZ, task.radiusX, task.radiusZ, pattern,
        )
        if (!started) {
            release(world)
            return GenerateOutcome.Failed("chunkeditor.pregen.error.failed")
        }
        val total = chunks?.size?.toLong() ?: task.covered
        Constants.LOG.info("chunky task for {} started, pattern {}", world, pattern)
        return GenerateOutcome.Started(total)
    }

    /** Chunky fires this on pause as well, and a paused task re-reads the csv when it continues */
    private fun onComplete(world: String) {
        val chunky = ChunkyProvider.get()
        val resumable = Input.tryWorld(chunky, world)
            .flatMap { chunky.taskLoader.loadTask(it) }
            .map { !it.isCancelled }
            .orElse(false) ?: false
        if (!resumable) release(world)
    }

    private fun release(world: String) {
        val file = running.remove(world) ?: return
        runCatching { file.deleteIfExists() }
            .onFailure { Constants.LOG.warn("Could not remove {}", file.name, it) }
    }

    /** One chunk per line as `x,z` ([org.popcraft.chunky.iterator.CsvChunkIterator]) */
    private fun write(directory: Path, world: String, chunks: Collection<ChunkPos>): Path? = runCatching {
        Files.createDirectories(directory)
        val file = directory.resolve("$CSV_PREFIX${slug(world)}-${System.currentTimeMillis()}$CSV_SUFFIX")
        Files.newBufferedWriter(file).use { out ->
            chunks.forEach { pos ->
                out.write(pos.x.toString())
                out.write(",")
                out.write(pos.z.toString())
                out.newLine()
            }
        }
        file
    }.onFailure { Constants.LOG.warn("Could not write the chunk list for {}", world, it) }.getOrNull()

    private fun slug(world: String) = world.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /** Leftovers of runs that never reached [onComplete], from a server that went down mid-task */
    private fun sweep() {
        val directory = runCatching { ChunkyProvider.get().config.directory }.getOrNull() ?: return
        if (!Files.isDirectory(directory)) return
        val cutoff = System.currentTimeMillis() - STALE_CSV_DAYS * 24 * 60 * 60 * 1000
        runCatching {
            Files.newDirectoryStream(directory, "$CSV_PREFIX*$CSV_SUFFIX").use { stream ->
                stream.filter { Files.getLastModifiedTime(it).toMillis() < cutoff }
                    .forEach { it.deleteIfExists() }
            }
        }.onFailure { Constants.LOG.warn("Could not sweep old chunk lists", it) }
    }
}
