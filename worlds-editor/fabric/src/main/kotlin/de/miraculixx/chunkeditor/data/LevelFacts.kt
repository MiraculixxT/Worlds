package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * The two `level.dat` fields the chunk map needs
 */
data class LevelFacts(val gameTime: Long, val spawn: BlockPos) {
    companion object {
        val EMPTY = LevelFacts(0L, BlockPos.ZERO)

        fun read(access: LevelStorageSource.LevelStorageAccess): LevelFacts = try {
            val data = access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag
            LevelFacts(
                gameTime = data.getLongOr("Time", 0L),
                spawn = data.read("spawn", LevelData.RespawnData.CODEC)
                    .orElse(LevelData.RespawnData.DEFAULT).pos(),
            )
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read level.dat of {}", access.levelId, e)
            EMPTY
        }
    }
}
