package dev.omniand.hub.camera

import java.nio.ByteBuffer

data class YuvPlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

data class I420Image(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)

/** Converts cropped YUV_420_888 planes into tightly packed, physically rotated I420. */
object Yuv420Converter {
    fun convert(
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane,
    ): I420Image {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(cropWidth > 0 && cropHeight > 0)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropLeft + cropWidth <= sourceWidth && cropTop + cropHeight <= sourceHeight)
        require(cropLeft % 2 == 0 && cropTop % 2 == 0)
        require(cropWidth % 2 == 0 && cropHeight % 2 == 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))

        val packedY = copyPlane(y, cropLeft, cropTop, cropWidth, cropHeight)
        val chromaWidth = cropWidth / 2
        val chromaHeight = cropHeight / 2
        val packedU = copyPlane(u, cropLeft / 2, cropTop / 2, chromaWidth, chromaHeight)
        val packedV = copyPlane(v, cropLeft / 2, cropTop / 2, chromaWidth, chromaHeight)
        val rotatedY = rotate(packedY, cropWidth, cropHeight, rotationDegrees)
        val rotatedU = rotate(packedU, chromaWidth, chromaHeight, rotationDegrees)
        val rotatedV = rotate(packedV, chromaWidth, chromaHeight, rotationDegrees)
        return I420Image(
            if (rotationDegrees % 180 == 0) cropWidth else cropHeight,
            if (rotationDegrees % 180 == 0) cropHeight else cropWidth,
            rotatedY,
            rotatedU,
            rotatedV,
        )
    }

    fun bytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun copyPlane(
        plane: YuvPlane,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        require(plane.rowStride > 0 && plane.pixelStride > 0)
        val result = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val source = (top + row) * plane.rowStride + (left + column) * plane.pixelStride
                require(source in plane.bytes.indices) { "YUV plane is shorter than its strides" }
                result[row * width + column] = plane.bytes[source]
            }
        }
        return result
    }

    private fun rotate(
        source: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
    ): ByteArray {
        if (rotation == 0) return source
        val outputWidth = if (rotation % 180 == 0) width else height
        val result = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (targetX, targetY) =
                    when (rotation) {
                        90 -> height - 1 - y to x
                        180 -> width - 1 - x to height - 1 - y
                        else -> y to width - 1 - x
                    }
                result[targetY * outputWidth + targetX] = source[y * width + x]
            }
        }
        return result
    }
}
