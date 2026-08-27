package de.miraculixx.worlds.data

import de.miraculixx.common.LevelDat
import de.miraculixx.worlds.Constants
import net.minecraft.nbt.NbtOps
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * The per-save state 1.21 keeps *inside* `level.dat`'s `Data` compound, the seed, under
 * `WorldGenSettings`, and the game rules under `game_rules`.
 *
 * 26.x moved both out into `data/<namespace>/<path>.dat` SavedData files, so this is the one place
 * that reads noticeably different between the two eras.
 */
object WorldRules {

    fun readSeed(access: LevelStorageSource.LevelStorageAccess): Long? = try {
        LevelDat.read(access)
            ?.getCompoundOrEmpty(WORLD_GEN_SETTINGS)
            ?.getLong(SEED)?.orElse(null)
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to read seed of {}: {}", access.levelId, e.message)
        null
    }

    fun readGameRules(access: LevelStorageSource.LevelStorageAccess, features: FeatureFlagSet): GameRules {
        val data = LevelDat.read(access) ?: return GameRules(features)
        return try {
            val tag = data.get(GAME_RULES) ?: return GameRules(features)
            GameRules.codec(features).parse(NbtOps.INSTANCE, tag)
                .result().orElseGet { GameRules(features) }
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read game rules of {}: {}", access.levelId, e.message)
            GameRules(features)
        }
    }

    fun writeGameRules(
        access: LevelStorageSource.LevelStorageAccess,
        features: FeatureFlagSet,
        rules: GameRules,
    ): Boolean = try {
        val encoded = GameRules.codec(features).encodeStart(NbtOps.INSTANCE, rules).getOrThrow()
        LevelDat.modify(access) { it.put(GAME_RULES, encoded) }
        true
    } catch (e: Exception) {
        Constants.LOG.error("Failed to write game rules of {}", access.levelId, e)
        false
    }

    private const val WORLD_GEN_SETTINGS = "WorldGenSettings"
    private const val GAME_RULES = "game_rules"
    private const val SEED = "seed"
}
