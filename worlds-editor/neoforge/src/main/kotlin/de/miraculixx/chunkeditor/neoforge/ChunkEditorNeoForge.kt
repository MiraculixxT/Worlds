package de.miraculixx.chunkeditor.neoforge

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.initChunkEditor
import de.miraculixx.chunkeditor.server.EditorService
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Mod("chunkeditor")
object ChunkEditorNeoForge {
    init {
        initChunkEditor()

        // The payload types go on this mods own bus, the session hooks on the game bus
        ModList.get().getModContainerById(Constants.MOD_ID).ifPresent { container ->
            container.eventBus?.addListener(RegisterPayloadHandlersEvent::class.java, NeoForgeNet::register)
        }
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent::class.java) { EditorService.start(it.server) }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { EditorService.stop() }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent::class.java) {
            (it.entity as? ServerPlayer)?.let(EditorService::onJoin)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) {
            (it.entity as? ServerPlayer)?.let(EditorService::onLeave)
        }

        if (FMLEnvironment.getDist() == Dist.CLIENT) ChunkEditorNeoForgeClient.init()
    }
}
