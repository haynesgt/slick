package com.haynesgt.slick

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

data class Vector2D(val x: Float, val y: Float)

@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyWhiteboardFeature(context: SurfaceView) : DrawingFeature {

    private val paint: Paint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 10f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val renderer: CanvasFrontBufferedRenderer<Vector2D> = CanvasFrontBufferedRenderer(
        context,
        object : CanvasFrontBufferedRenderer.Callback<Vector2D> {
            override fun onDrawFrontBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                param: Vector2D
            ) {
                // Draw a triangle at the given position
                drawTriangleAtPosition(canvas, param.x, param.y)
            }

            override fun onDrawMultiBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                params: Collection<Vector2D>
            ) {
                // Redraw all finalized strokes
                for (param in params) {
                    drawTriangleAtPosition(canvas, param.x, param.y)
                }
            }
        }
    )

    private fun drawTriangleAtPosition(canvas: Canvas, x: Float, y: Float) {
        // Define triangle vertices relative to the point (x, y)
        val size = 50f // Size of the triangle
        val halfSize = size / 2
        val path = android.graphics.Path().apply {
            moveTo(x, y - halfSize) // Top
            lineTo(x - halfSize, y + halfSize) // Bottom left
            lineTo(x + halfSize, y + halfSize) // Bottom right
            close()
        }

        // Draw the triangle
        canvas.drawPath(path, paint)
    }

    override fun render(event: MotionEvent) {
        val x = event.x
        val y = event.y
        renderer.renderFrontBufferedLayer(Vector2D(x, y))
    }

    override fun commit() {
        renderer.commit()
    }

    fun clear() {
        renderer.clear()
    }
}
