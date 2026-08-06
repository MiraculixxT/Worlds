package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.FileUtil
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.level.gamerules.GameRuleMap
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * The per-save state 26.2 keeps *outside* `level.dat`, in `data/<namespace>/<path>.dat`.
 */
object WorldRules {

    fun readSeed(access: LevelStorageSource.LevelStorageAccess): Long? {
        val file = savedDataFile(access, WorldGenSettings.TYPE)
        if (!Files.isRegularFile(file)) return null
        return try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("data").getLong("seed").orElse(null)
        } catch (e: Exception) {
            Constants.LOG.warn("Failed to read seed of {}: {}", access.levelId, e.message)
            null
        }
    }

    fun readGameRules(access: LevelStorageSource.LevelStorageAccess, features: FeatureFlagSet): GameRules {
        val file = savedDataFile(access, GameRuleMap.TYPE)
        if (!Files.isRegularFile(file)) return GameRules(features)
        return try {
            val root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val fixed = DataFixTypes.SAVED_DATA_GAME_RULES.updateToCurrentVersion(
                Minecraft.getInstance().fixerUpper, root, NbtUtils.getDataVersion(root, 0),
            )
            GameRules.codec(features).parse(NbtOps.INSTANCE, fixed.getCompoundOrEmpty("data"))
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
        val root = CompoundTag()
        root.put("data", GameRules.codec(features).encodeStart(NbtOps.INSTANCE, rules).getOrThrow())
        NbtUtils.addCurrentDataVersion(root)
        val file = savedDataFile(access, GameRuleMap.TYPE)
        FileUtil.createDirectoriesSafe(file.parent)
        NbtIo.writeCompressed(root, file)
        true
    } catch (e: Exception) {
        Constants.LOG.error("Failed to write game rules of {}", access.levelId, e)
        false
    }

    private fun savedDataFile(access: LevelStorageSource.LevelStorageAccess, type: SavedDataType<*>): Path =
        type.id().withSuffix(".dat").resolveAgainst(access.getLevelPath(LevelResource.DATA))
}
