package de.miraculixx.chunkeditor.data

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.LevelDat
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The two `level.dat` fields the chunk map needs
 */
data class LevelFacts(val gameTime: Long, val spawn: BlockPos) {
    companion object {
        val EMPTY = LevelFacts(0L, BlockPos.ZERO)

        fun read(access: LevelStorageSource.LevelStorageAccess): LevelFacts {
            val data = LevelDat.read(access) ?: return EMPTY
            return of(data, access.levelId)
        }

        /** The same two fields off the save root, for a caller that holds no access (a live server). */
        fun read(root: Path): LevelFacts = try {
            val file = root.resolve("level.dat")
            if (!Files.isRegularFile(file)) EMPTY
            else of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data"), root.name)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read level.dat of {}: {}", root, e.message)
            EMPTY
        }

        private fun of(data: CompoundTag, name: String): LevelFacts = try {
            LevelFacts(
                gameTime = data.getLongOr("Time", 0L),
                spawn = data.read("spawn", LevelData.RespawnData.CODEC)
                    .orElse(LevelData.RespawnData.DEFAULT).pos(),
            )
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read level.dat of {}", name, e)
            EMPTY
        }
    }
}
