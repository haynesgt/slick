package com.haynesgt.slick;

import android.content.Context;
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent;
import android.view.SurfaceHolder
import android.view.SurfaceView;
import androidx.annotation.RequiresApi

class WhiteboardSurfaceView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs),
    SurfaceHolder.Callback {

        @RequiresApi(Build.VERSION_CODES.Q)
        val lowLatencyFeature = LowLatencyWhiteboardFeature(this)

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

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Handle touch down event
            }
            MotionEvent.ACTION_MOVE -> {
                // Handle touch move event
                lowLatencyFeature.render(event)
            }
            MotionEvent.ACTION_UP -> {
                // Handle touch up event
            }
        }
        return true
    }

    fun clear() {
        // Clear the whiteboard canvas
    }
}
