package com.haynesgt.slick

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyWhiteboardFeature(context: SurfaceView) {

    private val paint: Paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private var strokes = mutableListOf<List<Vector2D>>()
    private var lastPoint = Vector2D(0f, 0f)
    private var currentStroke = mutableListOf<Vector2D>()

    private val renderer: CanvasFrontBufferedRenderer<Pair<Vector2D, Vector2D>> = CanvasFrontBufferedRenderer(
        context,
        object : CanvasFrontBufferedRenderer.Callback<Pair<Vector2D, Vector2D>> {
            override fun onDrawFrontBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                param: Pair<Vector2D, Vector2D>
            ) {
                canvas.drawLine(param.first.x, param.first.y, param.second.x, param.second.y, paint)
            }

            override fun onDrawMultiBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                params: Collection<Pair<Vector2D, Vector2D>>
            ) {
                canvas.drawColor(Color.rgb(49,49,49))

                for (stroke in strokes)
                {
                    if (stroke.size < 2)
                        continue
                    val path = android.graphics.Path()
                    path.moveTo(stroke.first().x, stroke.first().y)
                    for (point in stroke) {
                        path.lineTo(point.x, point.y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    )

    fun beginAt(x: Float, y: Float) {
        lastPoint = Vector2D(x, y)
        currentStroke = mutableListOf(lastPoint)
    }

    fun moveTo(x: Float, y: Float) {
        val newPoint = Vector2D(x, y)
        renderer.renderFrontBufferedLayer(Pair(lastPoint, newPoint))
        lastPoint = newPoint
        currentStroke.add(newPoint)
    }

    fun commit() {
        strokes.add(currentStroke)
        currentStroke = mutableListOf()
        renderer.commit()
    }

    fun clear() {
        strokes.clear()
        renderer.clear()
        renderer.commit()
    }
}
