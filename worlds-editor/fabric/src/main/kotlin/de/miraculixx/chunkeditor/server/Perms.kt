package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.server.level.ServerPlayer
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class EditorServerConfig(
    val readLevel: String = "gamemasters",
    val writeLevel: String = "admins",
    val renderCacheMb: Int = 256,
    val maxUploadMb: Int = 256,
)

/**
 * Who may look and who may change anything.
 *
 * 1.21 has no permission atoms, so the command level of `server.json` is the whole test - a
 * permission mod has to grant the level itself.
 */
object Perms {

    /** Vanilla's own ladder, the names `server.json` is written in */
    private val LEVELS = mapOf(
        "all" to 0, "moderators" to 1, "gamemasters" to 2, "admins" to 3, "owners" to 4,
    )

    private const val GAMEMASTERS = 2
    private const val ADMINS = 3

    val config: EditorServerConfig by lazy { load() }

    fun canRead(player: ServerPlayer): Boolean = player.hasPermissions(level(config.readLevel, GAMEMASTERS))

    fun canWrite(player: ServerPlayer): Boolean = player.hasPermissions(level(config.writeLevel, ADMINS))

    private fun level(name: String, fallback: Int): Int =
        LEVELS.entries.firstOrNull { it.key.equals(name, true) }?.value ?: name.toIntOrNull() ?: fallback

    private fun load(): EditorServerConfig {
        val file = Loader.configDir.resolve("${Constants.MOD_ID}/server.json")
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        return try {
            if (!java.nio.file.Files.isRegularFile(file)) {
                val fresh = EditorServerConfig()
                file.createParentDirectories()
                file.writeText(json.encodeToString(fresh))
                fresh
            } else {
                json.decodeFromString<EditorServerConfig>(file.readText())
            }
        } catch (e: Exception) {
            Constants.LOG.warn("Unreadable {}, using the defaults: {}", file, e.message)
            EditorServerConfig()
        }
    }
}
