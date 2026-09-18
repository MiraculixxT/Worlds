package de.miraculixx.chunkeditor

import de.miraculixx.chunkeditor.client.net.ClientNet
import de.miraculixx.chunkeditor.net.Bodies
import de.miraculixx.chunkeditor.net.Hello
import de.miraculixx.chunkeditor.net.C2S
import de.miraculixx.chunkeditor.client.net.RemoteBackend
import de.miraculixx.chunkeditor.client.ui.ChunkMapScreen
import de.miraculixx.chunkeditor.client.ui.SaveBiomes
import de.miraculixx.chunkeditor.data.EditorConfig
import de.miraculixx.chunkeditor.data.LocalBackend
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelStorageSource
import kotlinx.coroutines.launch

/**
 * Quick access for other mods
 */
object ChunkEditor {
    fun open(parent: Screen, access: LevelStorageSource.LevelStorageAccess) {
        Constants.LOG.info("editor opened: local world {}", access.levelId)
        val backend = LocalBackend(access, Minecraft.getInstance().user.profileId) { SaveBiomes.load(access) }
        Minecraft.getInstance().gui.setScreen(ChunkMapScreen(parent, backend))
    }

    /** Requires handshake before, then open remote UI (same with file shares) */
    fun openRemote(parent: Screen) {
        val hello = ClientNet.hello ?: return
        if (!hello.canRead) return
        val minecraft = Minecraft.getInstance()
        val local = minecraft.hasSingleplayerServer()
        val message = if (local) {
            Component.translatable("chunkeditor.remote.warn.local.message")
        } else {
            Component.translatable("chunkeditor.remote.warn.live.message", hello.worldName)
        }
        if (EditorConfig.settings.skipOpenWarning) return openConfirmed(parent, hello, local)
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (!confirmed) return@ConfirmScreen minecraft.gui.setScreen(parent)
                    openConfirmed(parent, hello, local)
                },
                Component.translatable(
                    if (local) "chunkeditor.remote.warn.local.title" else "chunkeditor.remote.warn.live.title",
                ),
                message,
                Component.translatable("chunkeditor.remote.warn.open"),
                CommonComponents.GUI_CANCEL,
            ),
        )
    }

    /** Asks the server for a fresh [Hello] (dimensions/facts) and opens the map */
    private fun openConfirmed(parent: Screen, hello: Hello, local: Boolean) {
        val minecraft = Minecraft.getInstance()
        val where = if (local) "integrated server" else minecraft.currentServer?.ip ?: "server"
        Constants.LOG.info(
            "editor opened: {} on {} ({})",
            hello.worldName, where, if (hello.canWrite) "read+write" else "read only",
        )
        Constants.SCOPE.launch {
            val fresh = runCatching { Bodies.readHello(ClientNet.request(C2S.OPEN, ByteArray(0))) }
                .onFailure { Constants.LOG.warn("Chunk editor open failed, using join handshake: {}", it.message) }
                .getOrDefault(hello)
            minecraft.execute { minecraft.gui.setScreen(ChunkMapScreen(parent, RemoteBackend(fresh))) }
        }
    }

    /** Why the remote entry is open or not */
    enum class RemoteState { READY, NO_PERMISSION, WRONG_VERSION, NOT_ON_SERVER }

    fun remoteState(): RemoteState {
        val hello = ClientNet.hello
        return when {
            hello == null -> if (ClientNet.protocolMismatch) RemoteState.WRONG_VERSION else RemoteState.NOT_ON_SERVER
            hello.canRead -> RemoteState.READY
            else -> RemoteState.NO_PERMISSION
        }
    }

    fun remoteAvailable(): Boolean = remoteState() == RemoteState.READY
}
