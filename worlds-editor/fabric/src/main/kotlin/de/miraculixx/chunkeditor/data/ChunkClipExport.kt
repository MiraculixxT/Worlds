package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.SharedConstants
import net.minecraft.world.level.ChunkPos
import java.nio.file.Path

sealed interface ExportResult {
    data class Success(val dir: Path, val chunks: Int) : ExportResult
    data class Failure(val message: String) : ExportResult
}

/**
 * Export selection into clip folder.
 * Raw copy of all region, POI and entity data
 */
object ChunkClipExport {

    fun export(
        dimension: WorldDimension,
        chunks: Collection<ChunkPos>,
        name: String,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult {
        if (chunks.isEmpty()) return ExportResult.Failure("chunkeditor.clip.error.empty")
        val dir = ChunkClips.freeDir(name)
        val ordered = chunks.sortedWith(compareBy({ it.z }, { it.x }))
        val written = ArrayList<ChunkPos>(ordered.size)

        return try {
            CHUNK_SUBS.forEach { sub ->
                val source = ChunkRegions.storage(dimension, sub) ?: return@forEach
                source.use { from ->
                    // Created only for a folder the save actually has, so an empty poi/ in a clip means the source had none.
                    ChunkClips.storage(dir, sub, dimension.key, true)!!.use { to ->
                        ordered.forEachIndexed { index, pos ->
                            val tag = try {
                                from.read(pos)
                            } catch (e: Exception) {
                                Constants.LOG.warn("Failed to read chunk {} from {}: {}", pos, sub, e.message)
                                null
                            }
                            if (tag != null) {
                                to.write(pos, tag)
                                if (sub == SUB_REGION) written.add(pos)
                            }
                            if (sub == SUB_REGION) onProgress(index + 1, ordered.size)
                        }
                    }
                }
            }
            if (written.isEmpty()) {
                ChunkClips.delete(ClipInfo(dir, null, emptyList(), 0, 0))
                return ExportResult.Failure("chunkeditor.clip.error.empty")
            }
            ChunkClips.write(
                dir, ClipManifest(
                    dataVersion = ChunkClips.currentDataVersion,
                    mcVersion = SharedConstants.getCurrentVersion().name(),
                    dimension = dimension.key.identifier().toString(),
                    originX = written.minOf { it.x },
                    originZ = written.minOf { it.z },
                    chunks = written.map { listOf(it.x, it.z) },
                    created = System.currentTimeMillis(),
                )
            )
            ExportResult.Success(dir, written.size)
        } catch (e: Exception) {
            Constants.LOG.error("Clip export failed", e)
            ExportResult.Failure("chunkeditor.clip.error.write")
        }
    }
}
