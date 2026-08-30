package dev.omniand.hub.tunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TunnelProtocolTest {
    @Test
    fun goldenVectorsCoverEveryFrame() {
        val vectors =
            listOf(
                TunnelFrame.Open(1) to "01000000000100000000",
                TunnelFrame.Data(0x01020304, byteArrayOf(0xaa.toByte(), 0xbb.toByte())) to
                    "02000102030400000002aabb",
                TunnelFrame.Fin(2) to "03000000000200000000",
                TunnelFrame.Reset(3) to "04000000000300000000",
                TunnelFrame.WindowUpdate(4, 16_384) to "0500000000040000000400004000",
                TunnelFrame.Ping(byteArrayOf(1, 2)) to "060000000000000000020102",
                TunnelFrame.Pong(byteArrayOf(3)) to "0700000000000000000103",
            )
        vectors.forEach { (frame, encoded) ->
            assertEquals(encoded, TunnelProtocol.encode(frame).hex())
            assertEquals(frame, TunnelProtocol.decode(encoded.bytes()))
        }
        assertEquals(
            "4f4d4e49414e4400010a706f632d646576696365",
            TunnelProtocol.hello().hex(),
        )
    }

    @Test
    fun unsignedStreamIdsRoundTrip() {
        val encoded = TunnelProtocol.encode(TunnelFrame.Fin(UInt.MAX_VALUE.toLong()))
        assertEquals(TunnelFrame.Fin(UInt.MAX_VALUE.toLong()), TunnelProtocol.decode(encoded))
    }

    @Test
    fun malformedFramesAndOversizedPayloadsAreRejected() {
        listOf(
                byteArrayOf(),
                ByteArray(9),
                "01010000000100000000".bytes(),
                "01000000000000000000".bytes(),
                "02000000000100000000".bytes(),
                "0500000000010000000400000000".bytes(),
                "06000000000100000000".bytes(),
            )
            .forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    TunnelProtocol.decode(malformed)
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelProtocol.encode(TunnelFrame.Data(1, ByteArray(TUNNEL_MAX_DATA + 1)))
        }
        assertThrows(IllegalStateException::class.java) {
            TunnelProtocol.decode("ff000000000000000000".bytes())
        }
    }

    @Test
    fun helloHasFixedVersionAndIdentification() {
        val expected =
            byteArrayOf(0x4f, 0x4d, 0x4e, 0x49, 0x41, 0x4e, 0x44, 0, 1, 10) +
                "poc-device".toByteArray()
        assertArrayEquals(expected, TunnelProtocol.hello())
        TunnelProtocol.validateHello(expected)
        val unsupported = expected.copyOf().also { it[8] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelProtocol.validateHello(unsupported)
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
