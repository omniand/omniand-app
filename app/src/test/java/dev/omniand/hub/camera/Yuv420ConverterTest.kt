package dev.omniand.hub.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Yuv420ConverterTest {
    @Test
    fun `respects row stride pixel stride and crop`() {
        val y = ByteArray(32) { it.toByte() }
        val u = ByteArray(16) { (100 + it).toByte() }
        val v = ByteArray(16) { (120 + it).toByte() }
        val image =
            Yuv420Converter.convert(
                6,
                4,
                2,
                0,
                4,
                2,
                0,
                YuvPlane(y, 8, 1),
                YuvPlane(u, 8, 2),
                YuvPlane(v, 8, 2),
            )
        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 10, 11, 12, 13), image.y)
        assertArrayEquals(byteArrayOf(102, 104), image.u)
        assertArrayEquals(byteArrayOf(122, 124), image.v)
    }

    @Test
    fun `rotates all supported orientations`() {
        val expected =
            mapOf(
                0 to byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
                90 to byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3),
                180 to byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0),
                270 to byteArrayOf(3, 7, 2, 6, 1, 5, 0, 4),
            )
        for ((rotation, pixels) in expected) {
            val image =
                Yuv420Converter.convert(
                    4,
                    2,
                    0,
                    0,
                    4,
                    2,
                    rotation,
                    YuvPlane(ByteArray(8) { it.toByte() }, 4, 1),
                    YuvPlane(byteArrayOf(10, 11), 2, 1),
                    YuvPlane(byteArrayOf(20, 21), 2, 1),
                )
            assertArrayEquals("rotation $rotation", pixels, image.y)
            assertEquals(if (rotation % 180 == 0) 4 else 2, image.width)
            assertEquals(if (rotation % 180 == 0) 2 else 4, image.height)
        }
    }
}
