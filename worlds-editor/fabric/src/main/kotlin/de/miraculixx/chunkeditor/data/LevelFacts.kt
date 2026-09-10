package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.LevelDat
import net.minecraft.core.BlockPos
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * The two `level.dat` fields the chunk map needs
 */
data class LevelFacts(val gameTime: Long, val spawn: BlockPos) {
    companion object {
        val EMPTY = LevelFacts(0L, BlockPos.ZERO)

        fun read(access: LevelStorageSource.LevelStorageAccess): LevelFacts = try {
            val data = LevelDat.read(access) ?: return EMPTY
            LevelFacts(
                gameTime = data.getLong("Time"),
                // 1.21 keeps the spawn as three ints, not a `RespawnData` compound
                spawn = BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ")),
            )
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read level.dat of {}", access.levelId, e)
            EMPTY
        }
    }
}
