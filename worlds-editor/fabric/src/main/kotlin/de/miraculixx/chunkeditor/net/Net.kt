package de.miraculixx.chunkeditor.net

import de.miraculixx.chunkeditor.Constants
import net.minecraft.server.level.ServerPlayer

/**
 * Loader independent managing of packets to avoid doing everything twice
 */
interface Transport {
    fun toClient(player: ServerPlayer, packet: EditorPacket)

    fun canReach(player: ServerPlayer): Boolean
}

/** The serverbound half, installed by the client entrypoint (dedi servers have none) */
interface ClientTransport {
    fun toServer(packet: EditorPacket)
}

/**
 * Sending and the fragmentation [Reassembler] both directions share, as one request is often too large.
 * C2S limited to: 32 767 bytes
 * S2C limited to: ~1 MiB
 */
object Net {

    @Volatile
    var transport: Transport? = null

    @Volatile
    var clientTransport: ClientTransport? = null

    fun toClient(player: ServerPlayer, request: Int, kind: Int, body: ByteArray) {
        val transport = transport ?: return
        if (!transport.canReach(player)) return
        fragment(body, S2C_FRAGMENT) { seq, total, part ->
            transport.toClient(player, EditorPacket(request, kind, seq, total, part, true))
        }
    }

    fun toServer(request: Int, kind: Int, body: ByteArray) {
        val transport = clientTransport ?: return
        fragment(body, C2S_FRAGMENT) { seq, total, part ->
            transport.toServer(EditorPacket(request, kind, seq, total, part, false))
        }
    }

    private inline fun fragment(body: ByteArray, limit: Int, send: (Int, Int, ByteArray) -> Unit) {
        if (body.size <= limit) {
            send(0, 1, body)
            return
        }
        val total = (body.size + limit - 1) / limit
        require(total <= MAX_FRAGMENTS) { "Editor body of ${body.size} bytes needs $total fragments" }
        for (seq in 0 until total) {
            val from = seq * limit
            send(seq, total, body.copyOfRange(from, minOf(from + limit, body.size)))
        }
    }

    /** `seq` and `total` are shorts on the wire */
    private const val MAX_FRAGMENTS = 4096
}

/**
 * Puts a fragmented body back together, per `(request, kind)`
 */
class Reassembler(private val maxBytes: Long, private val maxOpen: Int) {

    private class Partial(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var have = 0
        var bytes = 0L
    }

    private val open = HashMap<Long, Partial>()
    private val dropped = LinkedHashSet<Long>()
    private var heldBytes = 0L

    /** The complete body, or null while fragments are still missing or the message was dropped. */
    fun accept(packet: EditorPacket, onDrop: () -> Unit): ByteArray? {
        if (packet.total <= 1) return packet.body
        val key = packet.request.toLong() shl 32 or (packet.kind.toLong() and 0xFFFFFFFFL)
        if (key in dropped) return null
        val partial = open[key] ?: run {
            if (open.size >= maxOpen) {
                Constants.LOG.warn("Dropping editor message {}: too many partial messages", packet.request)
                drop(key, onDrop)
                return null
            }
            Partial(packet.total).also { open[key] = it }
        }
        if (packet.total != partial.total || packet.seq >= partial.total || partial.parts[packet.seq] != null) {
            Constants.LOG.warn("Dropping editor message {}: inconsistent fragments", packet.request)
            drop(key, onDrop)
            return null
        }
        partial.parts[packet.seq] = packet.body
        partial.have++
        partial.bytes += packet.body.size
        heldBytes += packet.body.size
        if (partial.bytes > maxBytes || heldBytes > maxBytes) {
            Constants.LOG.warn("Dropping editor message {}: over the reassembly budget", packet.request)
            drop(key, onDrop)
            return null
        }
        if (partial.have < partial.total) return null
        release(key)
        val out = ByteArray(partial.parts.sumOf { it!!.size })
        var at = 0
        partial.parts.forEach {
            it!!.copyInto(out, at)
            at += it.size
        }
        return out
    }

    fun clear() {
        open.clear()
        dropped.clear()
        heldBytes = 0
    }

    private fun drop(key: Long, onDrop: () -> Unit) {
        release(key)
        dropped += key
        if (dropped.size > MAX_DROPPED) dropped.remove(dropped.first())
        onDrop()
    }

    private fun release(key: Long) {
        open.remove(key)?.let { heldBytes -= it.bytes }
    }

    private companion object {
        const val MAX_DROPPED = 256
    }
}
