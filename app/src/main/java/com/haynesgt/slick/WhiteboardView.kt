package com.haynesgt.slick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager



@RequiresApi(Build.VERSION_CODES.Q)
class WhiteboardLowLatencyFeature(context: SurfaceView) : DrawingFeature {
    private val renderer: GLFrontBufferedRenderer<MotionEvent> = GLFrontBufferedRenderer(
        context,
        object : GLFrontBufferedRenderer.Callback<MotionEvent> {
            override fun onDrawFrontBufferedLayer(
                eglManager: EGLManager,
                width: Int,
                height: Int,
                bufferInfo: BufferInfo,
                transform: FloatArray,
                param: MotionEvent
            ) {
            }

            override fun onDrawMultiBufferedLayer(
                eglManager: EGLManager,
                width: Int,
                height: Int,
                bufferInfo: BufferInfo,
                transform: FloatArray,
                params: Collection<MotionEvent>
            ) {
                //inputs.forEach { drawStroke(canvas, it) }
            }
        }
    )

    override fun render(event: MotionEvent) {
        renderer.renderFrontBufferedLayer(event)
    }

    override fun commit() {
        renderer.commit()
    }

    private fun drawStroke(canvas: Canvas, event: MotionEvent) {
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(event.x, event.y, 5f, paint)
    }
}

class WhiteboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val path = Path()
    private val paths = mutableListOf<Pair<Path, Paint>>()

    init {
        // Set background color to white
        setBackgroundColor(Color.WHITE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw all saved paths
        for ((savedPath, savedPaint) in paths) {
            canvas.drawPath(savedPath, savedPaint)
        }

        // Draw the current path
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Start a new path
                path.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                // Continue the path
                path.lineTo(event.x, event.y)
                invalidate() // Redraw the view
            }
            MotionEvent.ACTION_UP -> {
                // Save the current path
                paths.add(Pair(Path(path), Paint(paint)))
                path.reset()
            }
        }
        return true
    }

    fun clear() {
        // Clear all paths and redraw
        paths.clear()
        path.reset()
        invalidate()
    }
}
