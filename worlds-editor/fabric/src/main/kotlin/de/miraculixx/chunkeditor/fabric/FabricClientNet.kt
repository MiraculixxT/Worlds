package de.miraculixx.chunkeditor.fabric

import de.miraculixx.chunkeditor.client.net.ClientNet
import de.miraculixx.chunkeditor.net.ClientTransport
import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Net
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Client part of [FabricNet] and counterpart of [NeoForgeNet]
 */
object FabricClientNet : ClientTransport {

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(EditorPacket.S2C_TYPE) { packet, _ -> ClientNet.handle(packet) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ClientNet.reset() }
        Net.clientTransport = this
    }

    override fun toServer(packet: EditorPacket) = ClientPlayNetworking.send(packet)
}
