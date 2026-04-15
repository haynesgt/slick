package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Matrix
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.lifecycle.LifecycleOwner
import kotlin.math.max
import kotlin.math.min

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

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

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
                canvas.save()
                canvas.concat(drawMatrix)
                drawSingleStroke(canvas, param)
                canvas.restore()
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
    var onSwipeFromEdge: (() -> Unit)? = null
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

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 != null) {
                val edgeThreshold = 30
                val isFromLeft = e1.x < edgeThreshold
                val isFromRight = e1.x > width - edgeThreshold
                val isFromTop = e1.y < edgeThreshold
                val isFromBottom = e1.y > height - edgeThreshold

                if (isFromLeft || isFromRight || isFromTop || isFromBottom) {
                    onSwipeFromEdge?.invoke()
                    return true
                }
            }

            val isSingleFinger = e2.pointerCount == 1
            val isStylus = e2.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
            val canPanWithSingleFinger = viewModel.singleFingerPanEnabled.value ?: true

            if (e2.pointerCount > 1 || (isSingleFinger && !isStylus && canPanWithSingleFinger)) {
                offsetX -= distanceX
                offsetY -= distanceY
                updateMatrices()
                if (holder.surface.isValid) {
                    renderer.commit()
                }
                return true
            }
            return false
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.1f, min(scaleFactor, 10.0f))

            // Calculate the actual ratio used after clamping to prevent "jumping" at limits
            val actualRatio = scaleFactor / oldScale

            // Zoom around the pivot point
            val focusX = detector.focusX
            val focusY = detector.focusY
            
            offsetX -= (focusX - offsetX) * (actualRatio - 1)
            offsetY -= (focusY - offsetY) * (actualRatio - 1)

            updateMatrices()
            if (holder.surface.isValid) {
                renderer.commit()
            }
            return true
        }
    })

    private fun updateMatrices() {
        drawMatrix.reset()
        drawMatrix.postScale(scaleFactor, scaleFactor)
        drawMatrix.postTranslate(offsetX, offsetY)
        
        drawMatrix.invert(inverseMatrix)
    }

    private fun screenToCanvas(x: Float, y: Float): Vector2D {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return Vector2D(pts[0], pts[1])
    }

    init {
        holder?.addCallback(this)
        updateMatrices()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // draw background
        redrawAll()
    }

    private fun redrawAll() {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                drawStrokes(canvas, viewModel.strokes.value ?: emptyList())
            }
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
        scaleGestureDetector.onTouchEvent(event)
        if (gestureDetector.onTouchEvent(event)) { return true }
        
        // stylus events for drawing
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            val canvasPoint = screenToCanvas(event.x, event.y)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onPenDown?.invoke(canvasPoint)
                }
                MotionEvent.ACTION_MOVE -> {
                    onPenMove?.invoke(canvasPoint)
                }
                MotionEvent.ACTION_UP -> {
                    onPenUp?.invoke(canvasPoint)
                }
            }
            return true
        }
        return true
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        canvas.drawColor(Color.rgb(49, 49, 49))
        canvas.save()
        canvas.concat(drawMatrix)
        for (stroke in strokes) {
            drawSingleStroke(canvas, stroke.points)
        }
        // Also draw the active stroke if it exists, so it stays visible during zoom/pan
        if (::viewModel.isInitialized) {
            viewModel.currentStroke.value?.let { current ->
                drawSingleStroke(canvas, current.points)
            }
        }
        canvas.restore()
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
