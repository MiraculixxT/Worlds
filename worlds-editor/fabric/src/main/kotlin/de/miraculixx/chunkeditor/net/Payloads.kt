package de.miraculixx.chunkeditor.net

import de.miraculixx.chunkeditor.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

const val PROTOCOL_VERSION = 7

/**
 * The one packet handling all communication to avoid having 100 registered packets
 * @param seq / [total] fragment position, `0 / 1` for everything that fits in one packet
 */
class EditorPacket(
    val request: Int,
    val kind: Int,
    val seq: Int,
    val total: Int,
    val body: ByteArray,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<EditorPacket> = if (clientbound) S2C_TYPE else C2S_TYPE

    /** Set by whichever codec decoded it */
    var clientbound: Boolean = false
        private set

    constructor(request: Int, kind: Int, seq: Int, total: Int, body: ByteArray, clientbound: Boolean) :
        this(request, kind, seq, total, body) {
        this.clientbound = clientbound
    }

    companion object {
        val C2S_ID: Identifier = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "c2s")
        val S2C_ID: Identifier = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "s2c")

        val C2S_TYPE: CustomPacketPayload.Type<EditorPacket> = CustomPacketPayload.Type(C2S_ID)
        val S2C_TYPE: CustomPacketPayload.Type<EditorPacket> = CustomPacketPayload.Type(S2C_ID)

        val C2S_CODEC: StreamCodec<ByteBuf, EditorPacket> = codec(false)
        val S2C_CODEC: StreamCodec<ByteBuf, EditorPacket> = codec(true)

        private fun codec(clientbound: Boolean): StreamCodec<ByteBuf, EditorPacket> =
            StreamCodec.of(
                { buf, packet ->
                    buf.writeInt(packet.request)
                    buf.writeByte(packet.kind)
                    buf.writeShort(packet.seq)
                    buf.writeShort(packet.total)
                    buf.writeInt(packet.body.size)
                    buf.writeBytes(packet.body)
                },
                { buf ->
                    val request = buf.readInt()
                    val kind = buf.readUnsignedByte().toInt()
                    val seq = buf.readUnsignedShort()
                    val total = buf.readUnsignedShort()
                    val size = buf.readInt()
                    require(size in 0..MAX_BODY) { "Editor packet body of $size bytes" }
                    val body = ByteArray(size)
                    buf.readBytes(body)
                    EditorPacket(request, kind, seq, total, body, clientbound)
                },
            )

        /** A body can never exceed what one fragment holds, so a bogus length is rejected before it allocates */
        private const val MAX_BODY = 1 shl 20
    }
}

/** Serverbound kinds */
object C2S {
    const val REGION_LIST = 1
    const val INDEX = 2
    const val HEIGHT_BOUNDS = 3
    const val RENDER = 4
    const val SCAN = 5
    const val PLAYERS = 6
    const val CONFLICTS = 7
    const val CLIP_UPLOAD = 8
    const val JOB_DELETE = 9
    const val JOB_PASTE = 10
    const val FORCE_SAVE = 11
    const val CANCEL = 12
    /** Answers a fresh [Hello] (dimensions and facts rescanned) */
    const val OPEN = 13
    const val CLIP_LIST = 14
    const val SELECTION_LIST = 15
    const val CLIP_FOOTPRINT = 16
    const val SELECTION_READ = 17
    const val CLIP_DELETE = 18
    const val SELECTION_DELETE = 19
    const val CLIP_EXPORT = 20
    const val SELECTION_EXPORT = 21
    /** Ends a [CLIP_UPLOAD] run and answers the name the library actually gave it */
    const val CLIP_UPLOAD_END = 22
    /** Announces one file of an upload (clip name, path, size) */
    const val CLIP_UPLOAD_OPEN = 23
    /** The queue answers [JobQueue] */
    const val JOB_LIST = 24
    const val JOB_CANCEL = 25
    const val JOB_BACKUP = 26
    const val CLIP_FILES = 27
    const val CLIP_DOWNLOAD = 28
}

/** Clientbound kinds */
object S2C {
    const val HELLO = 1
    const val RESULT = 2
    const val PROGRESS = 3
    const val ERROR = 4
}

/**
 * Limits the minecraft packet layer creates
 */
const val S2C_FRAGMENT = 512 * 1024
const val C2S_FRAGMENT = 24 * 1024

/** File bytes per upload frame: the frame is a fragment minus its id, index and length varints */
const val UPLOAD_PIECE = C2S_FRAGMENT - 16

/** Both sides refuse one that would not fit the reassembly budget */
const val MAX_CLIP_FILE = 32 * 1024 * 1024
