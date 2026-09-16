package de.miraculixx.chunkeditor.client.net

import de.miraculixx.chunkeditor.Constants
import de.miraculixx.chunkeditor.net.Bodies
import de.miraculixx.chunkeditor.net.EditorPacket
import de.miraculixx.chunkeditor.net.Hello
import de.miraculixx.chunkeditor.net.Net
import de.miraculixx.chunkeditor.net.PROTOCOL_VERSION
import de.miraculixx.chunkeditor.net.Reassembler
import de.miraculixx.chunkeditor.net.S2C
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MS = 120_000L

class RemoteException(val key: String, val argument: String? = null) : Exception(key)

/**
 * The client half of the session. Responses are matched by request id, the client knows what it asked for
 */
object ClientNet {

    @Volatile
    var hello: Hello? = null
        private set

    /** Help i dont speak your language */
    @Volatile
    var protocolMismatch = false
        private set

    private class Pending(val onProgress: ((Int, Int) -> Unit)?) {
        val result = CompletableDeferred<ByteArray>()
    }

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val frames = Reassembler(64L * 1024 * 1024, 16)
    private val counter = AtomicInteger(1)

    val connected: Boolean get() = hello != null

    /** Called from each loader's clientbound handler (packed alr decoded) */
    fun handle(packet: EditorPacket) {
        if (packet.kind == S2C.HELLO) {
            val read = runCatching { Bodies.readHello(packet.body) }.getOrNull() ?: return
            if (read.protocol != PROTOCOL_VERSION) {
                protocolMismatch = true
                Constants.LOG.warn(
                    "Server speaks chunk editor protocol {}, this build speaks {} — remote editing is off",
                    read.protocol, PROTOCOL_VERSION,
                )
                hello = null
                return
            }
            hello = read
            protocolMismatch = false
            return
        }
        val waiting = pending[packet.request] ?: return
        when (packet.kind) {
            S2C.PROGRESS -> {
                val (done, total) = Bodies.readProgress(packet.body)
                waiting.onProgress?.invoke(done, total)
            }

            S2C.ERROR -> {
                val (key, argument) = Bodies.readError(packet.body)
                Constants.LOG.warn("Chunk editor request {} failed on the server: {} {}", packet.request, key, argument ?: "")
                pending.remove(packet.request)
                waiting.result.completeExceptionally(RemoteException(key, argument))
            }

            S2C.RESULT -> {
                val body = frames.accept(packet) {
                    pending.remove(packet.request)?.result?.completeExceptionally(RemoteException("chunkeditor.remote.error.dropped"))
                } ?: return
                pending.remove(packet.request)
                val raw = runCatching { Bodies.inflate(body) }
                if (raw.isSuccess) waiting.result.complete(raw.getOrThrow())
                else waiting.result.completeExceptionally(RemoteException("chunkeditor.remote.error.failed"))
            }
        }
    }

    suspend fun request(kind: Int, body: ByteArray, onProgress: ((Int, Int) -> Unit)? = null): ByteArray {
        val id = counter.getAndIncrement()
        val waiting = Pending(onProgress)
        pending[id] = waiting
        return try {
            Net.toServer(id, kind, body)
            withTimeout(TIMEOUT_MS) { waiting.result.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Fire and forget: the answer is a courtesy, the upload frame is the message */
    fun send(kind: Int, body: ByteArray) = Net.toServer(counter.getAndIncrement(), kind, body)

    fun reset() {
        hello = null
        protocolMismatch = false
        frames.clear()
        pending.values.forEach { it.result.completeExceptionally(RemoteException("chunkeditor.remote.error.disconnected")) }
        pending.clear()
    }
}
