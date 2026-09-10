package de.miraculixx.worlds.data

import com.mojang.serialization.Dynamic
import de.miraculixx.common.LevelDat
import de.miraculixx.worlds.Constants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * The per-save state 1.21.1 keeps *inside* `level.dat`'s `Data` compound: the seed under
 * `WorldGenSettings`, the game rules under `GameRules`. No codec available here
 */
object WorldRules {

    fun readSeed(access: LevelStorageSource.LevelStorageAccess): Long? = try {
        LevelDat.read(access)
            ?.getCompound(WORLD_GEN_SETTINGS)
            ?.takeIf { it.contains(SEED) }
            ?.getLong(SEED)
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read seed of {}: {}", access.levelId, e.message)
        null
    }

    fun readGameRules(access: LevelStorageSource.LevelStorageAccess): GameRules {
        val data = LevelDat.read(access) ?: return GameRules()
        return try {
            GameRules(Dynamic(NbtOps.INSTANCE, data.getCompound(GAME_RULES)))
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read game rules of {}: {}", access.levelId, e.message)
            GameRules()
        }
    }

    fun writeGameRules(access: LevelStorageSource.LevelStorageAccess, rules: GameRules): Boolean = try {
        val encoded: CompoundTag = rules.createTag()
        LevelDat.modify(access) { it.put(GAME_RULES, encoded) }
        true
    } catch (e: Exception) {
        Constants.LOG.error("Failed to write game rules of {}", access.levelId, e)
        false
    }

    private const val WORLD_GEN_SETTINGS = "WorldGenSettings"
    private const val GAME_RULES = "GameRules"
    private const val SEED = "seed"
}
