package de.miraculixx.chunkeditor.server

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.common.Loader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permission
import net.minecraft.server.permissions.PermissionLevel
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
 * Who may look and who may change anything (`chunkeditor:read` & `chunkeditor:write`)
 */
object Perms {

    val READ: Permission = Permission.Atom.create(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "read"))
    val WRITE: Permission = Permission.Atom.create(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "write"))

    val config: EditorServerConfig by lazy { load() }

    fun canRead(player: ServerPlayer): Boolean = has(player, READ, level(config.readLevel, PermissionLevel.GAMEMASTERS))

    fun canWrite(player: ServerPlayer): Boolean = has(player, WRITE, level(config.writeLevel, PermissionLevel.ADMINS))

    private fun has(player: ServerPlayer, atom: Permission, level: PermissionLevel): Boolean {
        val permissions = player.permissions()
        return permissions.hasPermission(atom) || permissions.hasPermission(Permission.HasCommandLevel(level))
    }

    private fun level(name: String, fallback: PermissionLevel): PermissionLevel =
        PermissionLevel.entries.firstOrNull { it.serializedName.equals(name, true) || it.name.equals(name, true) }
            ?: fallback

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
