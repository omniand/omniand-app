package dev.omniand.hub.pairing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Draws an unobstructed square finder with high-contrast corners over the camera preview. */
internal class QrScannerOverlay(context: Context) : View(context) {
    private val shade = Paint().apply { color = Color.argb(145, 0, 0, 0) }
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeWidth = 4 * resources.displayMetrics.density
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameSize = min(width * 0.78f, height * 0.52f)
        val left = (width - frameSize) / 2f
        val top = (height - frameSize) * 0.42f
        val frame = RectF(left, top, left + frameSize, top + frameSize)

        canvas.save()
        canvas.clipOutRect(frame)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.restore()

        val corner = frameSize * 0.18f
        drawCorner(canvas, frame.left, frame.top, corner, 1f, 1f)
        drawCorner(canvas, frame.right, frame.top, corner, -1f, 1f)
        drawCorner(canvas, frame.left, frame.bottom, corner, 1f, -1f)
        drawCorner(canvas, frame.right, frame.bottom, corner, -1f, -1f)
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        length: Float,
        horizontalDirection: Float,
        verticalDirection: Float,
    ) {
        canvas.drawLine(x, y, x + length * horizontalDirection, y, border)
        canvas.drawLine(x, y, x, y + length * verticalDirection, border)
    }
}
