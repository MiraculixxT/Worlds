package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.server.DownloadedPackSource
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.io.path.name

/**
 * Per-world resource packs. A map ships its packs inside `saves/<name>/resourcepacks/`. Vanilla only
 * auto-loads the single `resources.zip` there; [pushWorldPacks] (called from `WorldOpenFlowsMixin`,
 * which fully replaces `WorldOpenFlows#loadBundledResourcePack`) pushes *every* pack in that folder
 * through the same [DownloadedPackSource]. The pushes batch into one reload and are popped
 * automatically on disconnect.
 */
object WorldResourcePacks {

    private fun savesDir(): Path =
        Minecraft.getInstance().gameDirectory.toPath().resolve("saves")

    /** The `resourcepacks/` folder inside an installed save. */
    fun packsDir(saveFolder: String): Path = savesDir().resolve(saveFolder).resolve("resourcepacks")

    /** True for a `.zip` pack file or a directory containing a `pack.mcmeta`. */
    private fun isPack(p: Path): Boolean =
        (Files.isRegularFile(p) && p.name.endsWith(".zip", ignoreCase = true)) ||
            (Files.isDirectory(p) && Files.isRegularFile(p.resolve("pack.mcmeta")))

    /** Names of the packs bundled with an installed save, for the Installed detail list. */
    fun listPackNames(saveFolder: String): List<String> {
        val dir = packsDir(saveFolder)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { isPack(it) }.map { it.name }.sorted()
        }
    }

    /**
     * Push every pack in the opening world's `resourcepacks/` folder onto [packSource].
     * Only the last is checked in vanilla style.
     */
    fun pushWorldPacks(
        packSource: DownloadedPackSource,
        access: LevelStorageSource.LevelStorageAccess,
    ): CompletableFuture<Void> {
        // MAP_RESOURCE_FILE is `<world>/resourcepacks/resources.zip`; its parent is the packs folder.
        val dir = access.getLevelPath(LevelResource.MAP_RESOURCE_FILE).parent
        if (dir == null || !Files.isDirectory(dir)) return CompletableFuture.completedFuture(null)
        val packs = Files.newDirectoryStream(dir).use { stream ->
            stream.filter { isPack(it) }.sorted().toList()
        }
        if (packs.isEmpty()) return CompletableFuture.completedFuture(null)

        packSource.configureForLocalWorld()
        // Push all but the last untracked, then track the final pack as the whole-batch signal.
        for (i in 0 until packs.size - 1) packSource.pushLocalPack(UUID.randomUUID(), packs[i])
        val lastId = UUID.randomUUID()
        val feedback = packSource.waitForPackFeedback(lastId)
        packSource.pushLocalPack(lastId, packs.last())

        Constants.LOG.info("Pushed {} world resource pack(s) from {}", packs.size, dir)
        return feedback
    }
}
