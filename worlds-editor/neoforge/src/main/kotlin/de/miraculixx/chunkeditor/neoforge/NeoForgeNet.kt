package de.miraculixx.chunkeditor.neoforge

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Net
import de.miraculixx.chunkeditor.net.Transport
import de.miraculixx.chunkeditor.server.EditorService
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * Neos half of networks, called from `ChunkEditorMod` (Fabric has [de.miraculixx.chunkeditor.fabric.FabricNet])
 */
object NeoForgeNet : Transport {

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar(Constants.MOD_ID).optional()
        registrar.playToServer(EditorPacket.C2S_TYPE, EditorPacket.C2S_CODEC) { packet, context ->
            val player = context.player()
            if (player is ServerPlayer) EditorService.receive(player, packet)
        }
        // The clientbound handler names a client class, so it is reached only through a separate class
        if (FMLEnvironment.getDist() == Dist.CLIENT) NeoForgeClientNet.register(registrar)
        else registrar.playToClient(EditorPacket.S2C_TYPE, EditorPacket.S2C_CODEC)
        Net.transport = this
    }

    override fun toClient(player: ServerPlayer, packet: EditorPacket) = PacketDistributor.sendToPlayer(player, packet)

    override fun canReach(player: ServerPlayer): Boolean =
        player.connection.hasChannel(EditorPacket.S2C_TYPE)
}
