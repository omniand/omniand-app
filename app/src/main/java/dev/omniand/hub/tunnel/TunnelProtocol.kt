package dev.omniand.hub.tunnel

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val TUNNEL_PROTOCOL_VERSION = 1
internal const val TUNNEL_MAX_DATA = 16 * 1024
internal const val TUNNEL_INITIAL_WINDOW = 256 * 1024
internal const val TUNNEL_MAX_STREAMS = 32

internal sealed interface TunnelFrame {
    val streamId: Long

    data class Open(override val streamId: Long) : TunnelFrame

    data class Data(override val streamId: Long, val payload: ByteArray) : TunnelFrame {
        override fun equals(other: Any?): Boolean =
            other is Data && streamId == other.streamId && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * streamId.hashCode() + payload.contentHashCode()
    }

    data class Fin(override val streamId: Long) : TunnelFrame

    data class Reset(override val streamId: Long) : TunnelFrame

    data class WindowUpdate(override val streamId: Long, val credit: Int) : TunnelFrame

    data class Ping(val payload: ByteArray) : TunnelFrame {
        override val streamId: Long = 0

        override fun equals(other: Any?): Boolean =
            other is Ping && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()
    }

    data class Pong(val payload: ByteArray) : TunnelFrame {
        override val streamId: Long = 0

        override fun equals(other: Any?): Boolean =
            other is Pong && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()
    }
}

/** Strictly encodes and validates the shared version-one binary tunnel wire format. */
internal object TunnelProtocol {
    private val helloMagic = byteArrayOf(0x4f, 0x4d, 0x4e, 0x49, 0x41, 0x4e, 0x44, 0)
    private const val HEADER_LENGTH = 10

    fun hello(deviceId: String): ByteArray {
        val identity = deviceId.toByteArray(Charsets.UTF_8)
        require(identity.isNotEmpty() && identity.size <= 128) { "invalid device ID" }
        return ByteBuffer.allocate(10 + identity.size)
            .put(helloMagic)
            .put(TUNNEL_PROTOCOL_VERSION.toByte())
            .put(identity.size.toByte())
            .put(identity)
            .array()
    }

    fun validateHello(hello: ByteArray, expectedDeviceId: String) {
        require(hello.size >= 10) { "hello is truncated" }
        require(hello.copyOfRange(0, 8).contentEquals(helloMagic)) { "hello magic is invalid" }
        require((hello[8].toInt() and 0xff) == TUNNEL_PROTOCOL_VERSION) {
            "unsupported protocol version"
        }
        val identityLength = hello[9].toInt() and 0xff
        require(hello.size == 10 + identityLength) { "hello length mismatch" }
        require(hello.copyOfRange(10, hello.size).contentEquals(expectedDeviceId.toByteArray())) {
            "HELLO device ID mismatch"
        }
    }

    fun encode(frame: TunnelFrame): ByteArray {
        val type: Int
        val payload: ByteArray
        when (frame) {
            is TunnelFrame.Open -> {
                type = 1
                payload = byteArrayOf()
            }
            is TunnelFrame.Data -> {
                require(frame.payload.isNotEmpty() && frame.payload.size <= TUNNEL_MAX_DATA)
                type = 2
                payload = frame.payload
            }
            is TunnelFrame.Fin -> {
                type = 3
                payload = byteArrayOf()
            }
            is TunnelFrame.Reset -> {
                type = 4
                payload = byteArrayOf()
            }
            is TunnelFrame.WindowUpdate -> {
                require(frame.credit in 1..TUNNEL_INITIAL_WINDOW)
                type = 5
                payload = ByteBuffer.allocate(4).putInt(frame.credit).array()
            }
            is TunnelFrame.Ping -> {
                require(frame.payload.size <= 125)
                type = 6
                payload = frame.payload
            }
            is TunnelFrame.Pong -> {
                require(frame.payload.size <= 125)
                type = 7
                payload = frame.payload
            }
        }
        validateStreamId(type, frame.streamId)
        return ByteBuffer.allocate(HEADER_LENGTH + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(type.toByte())
            .put(0)
            .putInt(frame.streamId.toUInt().toInt())
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun decode(encoded: ByteArray): TunnelFrame {
        require(encoded.size >= HEADER_LENGTH) { "frame header is truncated" }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val type = buffer.get().toInt() and 0xff
        require(buffer.get().toInt() == 0) { "reserved frame flags are non-zero" }
        val streamId = buffer.int.toUInt().toLong()
        val length = buffer.int.toUInt().toLong()
        require(length <= Int.MAX_VALUE && encoded.size == HEADER_LENGTH + length.toInt()) {
            "frame length mismatch"
        }
        val payload = ByteArray(length.toInt()).also(buffer::get)
        validateStreamId(type, streamId)
        return when (type) {
            1 -> TunnelFrame.Open(streamId).also { require(payload.isEmpty()) }
            2 ->
                TunnelFrame.Data(streamId, payload).also {
                    require(payload.isNotEmpty() && payload.size <= TUNNEL_MAX_DATA)
                }
            3 -> TunnelFrame.Fin(streamId).also { require(payload.isEmpty()) }
            4 -> TunnelFrame.Reset(streamId).also { require(payload.isEmpty()) }
            5 -> {
                require(payload.size == 4) { "WINDOW_UPDATE length is not four" }
                val credit = ByteBuffer.wrap(payload).int
                require(credit in 1..TUNNEL_INITIAL_WINDOW) { "invalid WINDOW_UPDATE credit" }
                TunnelFrame.WindowUpdate(streamId, credit)
            }
            6 -> TunnelFrame.Ping(payload).also { require(payload.size <= 125) }
            7 -> TunnelFrame.Pong(payload).also { require(payload.size <= 125) }
            else -> error("unknown frame type")
        }
    }

    private fun validateStreamId(type: Int, streamId: Long) {
        require(streamId in 0..UInt.MAX_VALUE.toLong()) { "stream ID is outside uint32" }
        if (type in 1..5) require(streamId != 0L) { "stream frame ID is zero" }
        if (type in 6..7) require(streamId == 0L) { "connection frame has a stream ID" }
    }
}
