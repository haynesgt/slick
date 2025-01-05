package com.haynesgt.slick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager

class LowLatencyDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val drawingFeature: DrawingFeature = createDrawingFeature(context)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        drawingFeature.render(event)
        if (event.action == MotionEvent.ACTION_UP) {
            drawingFeature.commit()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // Fallback drawing logic for older systems (if needed)
        if (drawingFeature is LegacyFeature) {
            (drawingFeature as LegacyFeature).drawFallback(canvas)
        }
    }

    // Create the appropriate drawing feature based on Android version
    private fun createDrawingFeature(context: Context): DrawingFeature {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            //com.haynesgt.slick.LowLatencyFeature(context)
            LegacyFeature()
        } else {
            LegacyFeature()
        }
    }
}

// Abstraction for drawing features
interface DrawingFeature {
    fun render(event: MotionEvent)
    fun commit() {}
}

// Implementation for devices supporting Low Latency Graphics
@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyFeature(context: SurfaceView) : DrawingFeature {
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
                // draw circle at that point:

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

// Fallback implementation for older Android versions
class LegacyFeature : DrawingFeature {
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val paths = mutableListOf<Pair<Float, Float>>()

    override fun render(event: MotionEvent) {
        paths.add(event.x to event.y)
    }

    fun drawFallback(canvas: Canvas) {
        for ((x, y) in paths) {
            canvas.drawCircle(x, y, 5f, paint)
        }
    }
}
