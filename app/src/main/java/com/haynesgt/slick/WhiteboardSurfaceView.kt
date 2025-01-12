package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.lifecycle.LifecycleOwner

@RequiresApi(Build.VERSION_CODES.Q)
class WhiteboardSurfaceView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs),
    SurfaceHolder.Callback {

    private val paint: Paint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val renderer: CanvasFrontBufferedRenderer<Pair<Vector2D, Vector2D>> = CanvasFrontBufferedRenderer(
        this,
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

            }
        }
    )

    var onTapped: (() -> Unit)? = null

    var onPenDown: ((Vector2D) -> Unit)? = null
    var onPenMove: ((Vector2D) -> Unit)? = null
    var onPenUp: ((Vector2D) -> Unit)? = null

    fun bindViewModel(viewModel: WhiteboardViewModel, owner: LifecycleOwner) {
        viewModel.currentStroke.observe(owner) { stroke ->
            if (stroke != null) {
                drawLastLinesOfCurrentStroke(stroke.points)
            }
        }
        viewModel.strokes.observe(owner) { strokes ->
            drawStrokes(strokes)
        }
    }

    private val gestureDetector: GestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }
        override fun  onDoubleTap(e: MotionEvent): Boolean {
            if  (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                return false
            }
            onTapped?.invoke()
            return true
        }
    })

    init {
        holder?.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        //TODO("Not yet implemented")
        // draw gray
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            canvas.drawColor(Color.rgb(49, 49, 49))
        } finally {
            if (canvas != null) {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ///TODO("Not yet implemented")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        //TODO("Not yet implemented")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) { return true }
        // ignore non-stylus events
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onPenDown?.invoke(Vector2D(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                onPenMove?.invoke(Vector2D(event.x, event.y))
            }
            MotionEvent.ACTION_UP -> {
                onPenUp?.invoke(Vector2D(event.x, event.y))
            }
        }
        return true
    }

    private fun drawLastLinesOfCurrentStroke(points: List<Vector2D>) {
        if (points.size < 2) {
            return
        }
        this.renderer.renderFrontBufferedLayer(Pair(points[points.size - 2], points[points.size - 1]))
    }

    private fun drawStrokes(strokes: List<Stroke>) {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas == null) {
                return
            }
            canvas.drawColor(Color.rgb(49, 49, 49))
            for (stroke in strokes) {
                if (stroke.points.size < 2) {
                    continue
                }
                val path = Path()
                path.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (point in stroke.points) {
                    path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, paint)
            }
        } finally {
            if (canvas != null) {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
