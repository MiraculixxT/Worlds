package de.miraculixx.chunkeditor.neoforge

import de.miraculixx.chunkeditor.client.net.ClientNet
import de.miraculixx.chunkeditor.net.ClientTransport
import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Net
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * Client part of [NeoForgeNet] and counterpart of [de.miraculixx.chunkeditor.fabric.FabricClientNet]
 */
internal object NeoForgeClientNet : ClientTransport {

    fun register(registrar: PayloadRegistrar) {
        registrar.playToClient(EditorPacket.S2C_TYPE, EditorPacket.S2C_CODEC) { packet, _ ->
            ClientNet.handle(packet)
        }
        Net.clientTransport = this
    }

    override fun toServer(packet: EditorPacket) = PacketDistributor.sendToServer(packet)
}
