package com.haynesgt.slick

import android.content.Context;
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent;
import android.view.SurfaceHolder
import android.view.SurfaceView;
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.Q)
class WhiteboardSurfaceView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs),
    SurfaceHolder.Callback {

        @RequiresApi(Build.VERSION_CODES.Q)
        val lowLatencyWhiteboardFeature = LowLatencyWhiteboardFeature(this)

    inner class DrawingThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        var isRunning = true

        override fun run() {
            while (isRunning) {
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    synchronized(surfaceHolder) {
                        // Your drawing logic here using the canvas
                    }
                } finally {
                    if (canvas != null) {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }
    }

    private var surfaceHolder: SurfaceHolder? = null
    private var drawingThread: DrawingThread? = null

    init {
        surfaceHolder = holder
        surfaceHolder?.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        //TODO("Not yet implemented")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ///TODO("Not yet implemented")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        //TODO("Not yet implemented")
    }

    override fun performClick(): Boolean {
        lowLatencyWhiteboardFeature.commit()
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ignore non-stylus events
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lowLatencyWhiteboardFeature.beginAt(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                lowLatencyWhiteboardFeature.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                performClick()
            }
        }
        return true
    }

    fun clear() {
        lowLatencyWhiteboardFeature.clear()
    }
}
