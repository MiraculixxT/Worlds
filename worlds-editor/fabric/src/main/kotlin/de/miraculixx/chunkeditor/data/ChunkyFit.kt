package de.miraculixx.chunkeditor.data

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.world.level.ChunkPos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

const val MAX_PREVIEW_CHUNKS = 500_000L

/**
 * Shapes that are checked for best fit, CSV is always [SQUARE]
 */
enum class FitShape(val chunky: String, val pattern: String) {
    SQUARE("square", "concentric"),
    RECTANGLE("rectangle", "loop"),
    CIRCLE("circle", "concentric"),
    ELLIPSE("ellipse", "loop");

    val labelKey get() = "chunkeditor.pregen.shape.${name.lowercase()}"
}

/**
 * A selection as Chunky will rebuild it. Shapes are chunk aligned so every axis covers an odd
 * number of chunks and all of this counts in chunks. Only [centerX] and [radiusX] convert back.
 */
class ChunkyTask(
    val shape: FitShape,
    val centerChunkX: Int,
    val centerChunkZ: Int,
    val radiusChunksX: Int,
    val radiusChunksZ: Int,
) {
    val centerX get() = centerChunkX * 16.0 + 8.0
    val centerZ get() = centerChunkZ * 16.0 + 8.0

    /** Chunky derives its aligned radius from `ceil(radiusX / 16)` */
    val radiusX get() = radiusChunksX * 16.0
    val radiusZ get() = radiusChunksZ * 16.0

    private val boundX = radiusChunksX + 0.5
    private val boundZ = radiusChunksZ + 0.5

    val covered: Long = when (shape) {
        FitShape.SQUARE, FitShape.RECTANGLE -> (2L * radiusChunksX + 1) * (2L * radiusChunksZ + 1)
        FitShape.CIRCLE, FitShape.ELLIPSE -> {
            var total = 0L
            for (dz in -radiusChunksZ..radiusChunksZ) {
                val inner = 1.0 - (dz.toDouble() * dz) / (boundZ * boundZ)
                if (inner < 0.0) continue
                total += 2L * floor(boundX * sqrt(inner)).toLong() + 1
            }
            total
        }
    }

    fun covers(x: Int, z: Int): Boolean {
        val dx = (x - centerChunkX).toDouble()
        val dz = (z - centerChunkZ).toDouble()
        return when (shape) {
            FitShape.SQUARE, FitShape.RECTANGLE -> abs(dx) <= radiusChunksX && abs(dz) <= radiusChunksZ
            FitShape.CIRCLE -> dx * dx + dz * dz <= boundX * boundX
            FitShape.ELLIPSE -> (dx * dx) / (boundX * boundX) + (dz * dz) / (boundZ * boundZ) <= 1.0
        }
    }

    fun covers(packed: Long) = covers(ChunkPos.getX(packed), ChunkPos.getZ(packed))

    inline fun forEachChunk(block: (Int, Int) -> Unit) {
        for (z in centerChunkZ - radiusChunksZ..centerChunkZ + radiusChunksZ) {
            for (x in centerChunkX - radiusChunksX..centerChunkX + radiusChunksX) {
                if (covers(x, z)) block(x, z)
            }
        }
    }
}

/** What the server answers a pre-generation request with */
sealed interface GenerateOutcome {
    class Started(val chunks: Long) : GenerateOutcome

    /** @param key Error translation key */
    class Failed(val key: String) : GenerateOutcome
}

/**
 * Fits a selection into the cheapest Chunky shape holding all of it (superset, 0=equal)
 */
object ChunkyFit {

    fun best(chunks: LongOpenHashSet): ChunkyTask? {
        val box = bounds(chunks) ?: return null
        var best: ChunkyTask? = null
        centers(box.minX, box.maxX).forEach { cx ->
            centers(box.minZ, box.maxZ).forEach { cz ->
                candidates(chunks, box, cx, cz).forEach { task ->
                    val current = best
                    if (current == null || task.covered < current.covered) best = task
                }
            }
        }
        return best
    }

    /** The smallest square holding everything, the list itself is the bound */
    fun boundingSquare(chunks: LongOpenHashSet): ChunkyTask? {
        val box = bounds(chunks) ?: return null
        val cx = floorHalf(box.minX + box.maxX)
        val cz = floorHalf(box.minZ + box.maxZ)
        val radius = max(radius(box.minX, box.maxX, cx), radius(box.minZ, box.maxZ, cz))
        return ChunkyTask(FitShape.SQUARE, cx, cz, radius, radius)
    }

    private class Box(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int)

    private fun bounds(chunks: LongOpenHashSet): Box? {
        if (chunks.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        val iterator = chunks.iterator()
        while (iterator.hasNext()) {
            val packed = iterator.nextLong()
            val x = ChunkPos.getX(packed)
            val z = ChunkPos.getZ(packed)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
        }
        return Box(minX, maxX, minZ, maxZ)
    }

    /** An even span has no center chunk, so both halves are tried */
    private fun centers(min: Int, max: Int): List<Int> {
        val floor = floorHalf(min + max)
        val ceil = -floorHalf(-(min + max))
        return if (floor == ceil) listOf(floor) else listOf(floor, ceil)
    }

    private fun floorHalf(value: Int) = Math.floorDiv(value, 2)

    private fun radius(min: Int, max: Int, center: Int) = max(max - center, center - min)

    private fun candidates(chunks: LongOpenHashSet, box: Box, cx: Int, cz: Int): List<ChunkyTask> {
        val rx = radius(box.minX, box.maxX, cx)
        val rz = radius(box.minZ, box.maxZ, cz)
        val out = ArrayList<ChunkyTask>(4)
        out.add(ChunkyTask(FitShape.RECTANGLE, cx, cz, rx, rz))
        val side = max(rx, rz)
        out.add(ChunkyTask(FitShape.SQUARE, cx, cz, side, side))

        val boundX = rx + 0.5
        val boundZ = rz + 0.5
        var maxDistance = 0.0
        var maxRatio = 0.0
        val iterator = chunks.iterator()
        while (iterator.hasNext()) {
            val packed = iterator.nextLong()
            val dx = (ChunkPos.getX(packed) - cx).toDouble()
            val dz = (ChunkPos.getZ(packed) - cz).toDouble()
            maxDistance = max(maxDistance, dx * dx + dz * dz)
            if (rx > 0 && rz > 0) {
                maxRatio = max(maxRatio, (dx * dx) / (boundX * boundX) + (dz * dz) / (boundZ * boundZ))
            }
        }

        var circle = max(0, ceil(sqrt(maxDistance) - 0.5).toInt())
        while ((circle + 0.5) * (circle + 0.5) < maxDistance) circle++
        out.add(ChunkyTask(FitShape.CIRCLE, cx, cz, circle, circle))

        if (rx > 0 && rz > 0) {
            val scale = sqrt(maxRatio)
            var ellipseX = rx
            var ellipseZ = rz
            if (scale > 1.0) {
                ellipseX = max(rx, ceil(boundX * scale - 0.5).toInt())
                ellipseZ = max(rz, ceil(boundZ * scale - 0.5).toInt())
                // Both axes grew by at least `scale`, which is what pulls every ratio back to <= 1
                while ((ellipseX + 0.5) < boundX * scale) ellipseX++
                while ((ellipseZ + 0.5) < boundZ * scale) ellipseZ++
            }
            out.add(ChunkyTask(FitShape.ELLIPSE, cx, cz, ellipseX, ellipseZ))
        }
        return out
    }
}
