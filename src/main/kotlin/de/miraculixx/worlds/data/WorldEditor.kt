package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import de.miraculixx.worlds.api.Http
import de.miraculixx.worlds.client.ui.MapTextures
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.Difficulty
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import com.mojang.serialization.Dynamic
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Edit actions not covered by vanilla edits screen utils.
 * Extra data is also stored in the worlds `worlds.meta.json` file
 */
object WorldEditor {

    /** The subset of `level.dat` this screen edits. */
    data class LevelFacts(val name: String, val difficulty: Difficulty, val hardcore: Boolean)

    fun readFacts(access: LevelStorageSource.LevelStorageAccess): LevelFacts {
        val data = readData(access)
        val settings = data.getCompoundOrEmpty("difficulty_settings")
        return LevelFacts(
            name = data.getString("LevelName").orElse("").ifBlank { access.levelId },
            difficulty = settings.getString("difficulty").orElse(null)
                ?.let { Difficulty.byName(it) } ?: Difficulty.NORMAL,
            hardcore = settings.getBooleanOr("hardcore", false),
        )
    }

    fun setDifficulty(access: LevelStorageSource.LevelStorageAccess, difficulty: Difficulty) =
        modifySettings(access) { it.putString("difficulty", difficulty.serializedName) }

    fun setHardcore(access: LevelStorageSource.LevelStorageAccess, hardcore: Boolean) =
        modifySettings(access) { it.putBoolean("hardcore", hardcore) }

    /** Rename the world itself. The save folder is left alone, as in vanilla. */
    fun rename(access: LevelStorageSource.LevelStorageAccess, name: String) {
        try {
            access.renameLevel(name.trim())
        } catch (e: Exception) {
            Constants.LOG.error("Failed to rename world {}", access.levelId, e)
        }
    }

    private fun modifySettings(access: LevelStorageSource.LevelStorageAccess, updater: (CompoundTag) -> Unit) {
        try {
            val data = readData(access)
            // getCompoundOrEmpty hands back a detached tag when the key is missing, so re-put it.
            val settings = data.getCompound("difficulty_settings").orElseGet { CompoundTag() }
            updater(settings)
            data.put("difficulty_settings", settings)
            access.saveLevelData(Dynamic(NbtOps.INSTANCE, data))
        } catch (e: Exception) {
            Constants.LOG.error("Failed to write level.dat of {}", access.levelId, e)
        }
    }

    private fun readData(access: LevelStorageSource.LevelStorageAccess): CompoundTag =
        access.getUnfixedDataTag(false).convert(NbtOps.INSTANCE).value as CompoundTag

    fun readMeta(dir: Path): InstalledMeta? {
        val file = dir.resolve(InstalledMeta.FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        return try {
            Http.json.decodeFromString<InstalledMeta>(Files.readString(file))
        } catch (e: Exception) {
            Constants.LOG.warn("Bad {} in {}: {}", InstalledMeta.FILE_NAME, dir, e.message)
            null
        }
    }

    /**
     * Update the marker's [title] / [description] / [categories], creating a [MapSource.LOCAL] one
     * for a save this mod did not install. [levelName] seeds the title of a freshly created marker.
     */
    fun updateMeta(
        dir: Path,
        levelName: String,
        title: String? = null,
        description: String? = null,
        categories: List<String>? = null,
    ) {
        val folder = dir.fileName.toString()
        val current = readMeta(dir) ?: InstalledMeta(
            id = "local:$folder",
            source = MapSource.LOCAL,
            title = levelName,
            categories = listOf(InstalledMap.MANUAL_CATEGORY),
        )
        val meta = current.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
            description = description?.trim() ?: current.description,
            categories = categories ?: current.categories,
        )
        try {
            Files.writeString(dir.resolve(InstalledMeta.FILE_NAME), Http.json.encodeToString(meta))
        } catch (e: Exception) {
            Constants.LOG.error("Failed to write {} in {}", InstalledMeta.FILE_NAME, dir, e)
        }
    }

    fun saveDir(access: LevelStorageSource.LevelStorageAccess): Path = access.getLevelPath(LevelResource.ROOT)

    /**
     * Re-encode [bytes] as the 64x64 PNG vanilla's world list expects. It reads `icon.png` through
     * STB, so anything ImageIO can decode (incl. WebP via the bundled plugin) has to be converted.
     */
    fun writeIcon(dest: Path, bytes: ByteArray): Boolean = try {
        val source = MapTextures.readBuffered(bytes)
        if (source == null) false else {
            val icon = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            icon.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, 0, 0, 64, 64, null)
                dispose()
            }
            ImageIO.write(icon, "png", dest.toFile())
            true
        }
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to write world icon {}: {}", dest, e.message)
        false
    }

    fun resetIcon(dest: Path): Boolean = try {
        Files.deleteIfExists(dest)
    } catch (e: Exception) {
        Constants.LOG.warn("Failed to delete world icon {}: {}", dest, e.message)
        false
    }
}
