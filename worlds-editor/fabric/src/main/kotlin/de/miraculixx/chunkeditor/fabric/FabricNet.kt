package de.miraculixx.chunkeditor.fabric

import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Net
import de.miraculixx.chunkeditor.net.Transport
import de.miraculixx.chunkeditor.server.EditorService
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.level.ServerPlayer

/**
 * Fabrics half of networks, called from `ChunkEditorMod` (Neo has [NeoForgeNet])
 */
object FabricNet : Transport {

    fun register() {
        PayloadTypeRegistry.serverboundPlay().register(EditorPacket.C2S_TYPE, EditorPacket.C2S_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorPacket.S2C_TYPE, EditorPacket.S2C_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(EditorPacket.C2S_TYPE) { packet, context ->
            EditorService.receive(context.player(), packet)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> EditorService.onJoin(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> EditorService.onLeave(handler.player) }
        ServerLifecycleEvents.SERVER_STARTED.register(EditorService::start)
        ServerLifecycleEvents.SERVER_STOPPING.register { EditorService.stop() }

        Net.transport = this
    }

    override fun toClient(player: ServerPlayer, packet: EditorPacket) = ServerPlayNetworking.send(player, packet)

    override fun canReach(player: ServerPlayer): Boolean =
        ServerPlayNetworking.canSend(player, EditorPacket.S2C_TYPE)
}
