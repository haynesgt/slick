package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
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

    private lateinit var viewModel: WhiteboardViewModel

    private val renderer: CanvasFrontBufferedRenderer<List<Vector2D>> = CanvasFrontBufferedRenderer(
        this,
        object : CanvasFrontBufferedRenderer.Callback<List<Vector2D>> {
            override fun onDrawFrontBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                param: List<Vector2D>
            ) {
                // Clear the front buffer to prevent artifacts (jagged lines) from previous segments
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawSingleStroke(canvas, param)
            }

            override fun onDrawMultiBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                params: Collection<List<Vector2D>>
            ) {
                val strokes = viewModel.strokes.value
                if (strokes != null) {
                    drawStrokes(canvas, strokes)
                }
            }
        }
    )

    var onTapped: (() -> Unit)? = null
    var onPenDown: ((Vector2D) -> Unit)? = null
    var onPenMove: ((Vector2D) -> Unit)? = null
    var onPenUp: ((Vector2D) -> Unit)? = null

    fun bindViewModel(viewModel: WhiteboardViewModel, owner: LifecycleOwner) {
        this.viewModel = viewModel
        viewModel.currentStroke.observe(owner) { stroke ->
            if (stroke != null) {
                // Render the entire current stroke to the front buffer for real-time smoothing
                this.renderer.renderFrontBufferedLayer(stroke.points)
            }
        }
        viewModel.strokes.observe(owner) { strokes ->
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.lastStrokeCompleteAt.observe(owner) { lastStrokeCompleteAt ->
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
    }

    private val gestureDetector: GestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if  (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                return false
            }
            onTapped?.invoke()
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

    private fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        canvas.drawColor(Color.rgb(49, 49, 49))
        for (stroke in strokes) {
            drawSingleStroke(canvas, stroke.points)
        }
    }

    private fun drawSingleStroke(canvas: Canvas, points: List<Vector2D>) {
        if (points.size < 2) {
            return
        }
        val path = Path()
        path.moveTo(points[0].x, points[0].y)

        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]

            // Use a quadratic Bezier curve for smoothing between midpoints
            val midX = (p1.x + p2.x) / 2
            val midY = (p1.y + p2.y) / 2

            if (i == 1) {
                path.lineTo(midX, midY)
            } else {
                path.quadTo(p1.x, p1.y, midX, midY)
            }
        }

        // Connect to the final point
        path.lineTo(points.last().x, points.last().y)

        canvas.drawPath(path, paint)
    }
}
