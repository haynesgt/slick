package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Matrix
import android.graphics.RectF
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
        color = Color.BLACK
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val invertPaint = Paint()

    private fun updateInvertFilter() {
        if (viewModel.invertColors.value == true) {
            // Invert filter:
            // -1,  0,  0, 0, 255
            //  0, -1,  0, 0, 255
            //  0,  0, -1, 0, 255
            //  0,  0,  0, 1, 0
            val invertMatrix = ColorMatrix(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f, 0f
            ))

            // Proper Hue Rotation (180 degrees) matrix to preserve color identities
            // while inverting luminance.
            val hueRotate180 = ColorMatrix(floatArrayOf(
                -0.574f,  1.430f,  0.144f, 0f, 0f,
                 0.426f,  0.430f,  0.144f, 0f, 0f,
                 0.426f,  1.430f, -0.856f, 0f, 0f,
                 0f,      0f,      0f,     1f, 0f
            ))

            invertMatrix.postConcat(hueRotate180)
            invertPaint.colorFilter = ColorMatrixColorFilter(invertMatrix)
        } else {
            invertPaint.colorFilter = null
        }
    }

    private lateinit var viewModel: WhiteboardViewModel

    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val gridPaint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val renderer: CanvasFrontBufferedRenderer<Stroke> = CanvasFrontBufferedRenderer(
        this,
        object : CanvasFrontBufferedRenderer.Callback<Stroke> {
            override fun onDrawFrontBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                param: Stroke
            ) {
                val saveCount = canvas.save()
                try {
                    // Clear the front buffer to prevent artifacts (jagged lines) from previous segments
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    val isInverted = if (::viewModel.isInitialized) viewModel.invertColors.value == true else false
                    if (isInverted) {
                        canvas.saveLayer(null, invertPaint)
                    }
                    canvas.save()
                    canvas.concat(drawMatrix)
                    paint.strokeWidth = param.width
                    drawSingleStroke(canvas, param.points)
                } finally {
                    canvas.restoreToCount(saveCount)
                }
            }

            override fun onDrawMultiBufferedLayer(
                canvas: Canvas,
                bufferWidth: Int,
                bufferHeight: Int,
                params: Collection<Stroke>
            ) {
                val strokes = viewModel.strokes.value
                if (strokes != null) {
                    drawStrokes(canvas, strokes)
                }
            }
        }
    )

    var onTapped: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onDoubleTapped: (() -> Unit)? = null
    var onDoubleFingerTap: (() -> Unit)? = null
    var onSwipeFromEdge: (() -> Unit)? = null
    var onPenDown: ((Vector2D) -> Unit)? = null
    var onPenMove: ((Vector2D) -> Unit)? = null
    var onPenUp: ((Vector2D) -> Unit)? = null

    fun bindViewModel(viewModel: WhiteboardViewModel, owner: LifecycleOwner) {
        this.viewModel = viewModel
        viewModel.currentStroke.observe(owner) { stroke ->
            if (stroke != null) {
                // Render the entire current stroke to the front buffer for real-time smoothing
                this.renderer.renderFrontBufferedLayer(stroke)
            }
        }
        viewModel.eraserRect.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.strokes.observe(owner) { strokes ->
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.viewPort.observe(owner) { viewPort ->
            scaleFactor = viewPort.scale
            offsetX = viewPort.offsetX
            offsetY = viewPort.offsetY
            updateMatrices()
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.lastStrokeCompleteAt.observe(owner) { lastStrokeCompleteAt ->
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.invertColors.observe(owner) {
            updateInvertFilter()
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.showGrid.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.showGridHorizontal.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.showGridVertical.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridSpacingX.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridSpacingY.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridOffsetX.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridOffsetY.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridColor.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.gridThickness.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.backgroundColor.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.penSize.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.hoverPoint.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.currentPenPoint.observe(owner) {
            if (this.holder.surface.isValid) {
                renderer.commit()
            }
        }
        viewModel.eraserSize.observe(owner) {
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
            onDoubleTapped?.invoke()
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
                viewModel.setViewPort(ViewPort(scaleFactor, offsetX, offsetY))
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
            viewModel.setViewPort(ViewPort(scaleFactor, offsetX, offsetY))
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

    private var lastTwoFingerDownTime = 0L

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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onDown?.invoke()
        }
        
        // Double finger tap detection
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
            val time = System.currentTimeMillis()
            if (time - lastTwoFingerDownTime < 300) {
                onDoubleFingerTap?.invoke()
            }
            lastTwoFingerDownTime = time
        }

        scaleGestureDetector.onTouchEvent(event)
        if (gestureDetector.onTouchEvent(event)) { return true }
        
        // stylus events for drawing
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            val canvasPoint = screenToCanvas(event.x, event.y)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onPenDown?.invoke(canvasPoint)
                    setHoverPoint(null)
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

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            val canvasPoint = screenToCanvas(event.x, event.y)
            when (event.action) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    setHoverPoint(canvasPoint)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    setHoverPoint(null)
                }
            }
            return true
        }
        return super.onHoverEvent(event)
    }

    private fun setHoverPoint(point: Vector2D?) {
        if (::viewModel.isInitialized) {
            viewModel.setHoverPoint(point)
        }
    }

    private fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        val saveCount = canvas.save()
        try {
            val isInverted = if (::viewModel.isInitialized) viewModel.invertColors.value == true else false
            if (isInverted) {
                canvas.saveLayer(null, invertPaint)
            }
            canvas.drawColor(if (::viewModel.isInitialized) viewModel.backgroundColor.value ?: Color.WHITE else Color.WHITE)
            canvas.save()
            canvas.concat(drawMatrix)

            if (::viewModel.isInitialized && viewModel.showGrid.value == true) {
                drawGrid(canvas)
            }

            for (stroke in strokes) {
                paint.strokeWidth = stroke.width
                drawSingleStroke(canvas, stroke.points)
            }
            // Also draw the active stroke if it exists, so it stays visible during zoom/pan
            if (::viewModel.isInitialized) {
                viewModel.currentStroke.value?.let { current ->
                    paint.strokeWidth = current.width
                    drawSingleStroke(canvas, current.points)
                }
            }

            if (::viewModel.isInitialized) {
                viewModel.eraserRect.value?.let { rect ->
                    val eraserPaint = Paint().apply {
                        color = Color.RED
                        style = Paint.Style.STROKE
                        strokeWidth = 2f / scaleFactor
                        pathEffect = DashPathEffect(floatArrayOf(10f / scaleFactor, 10f / scaleFactor), 0f)
                    }
                    canvas.drawRect(rect, eraserPaint)
                }

                val indicatorPoint = viewModel.hoverPoint.value ?: viewModel.currentPenPoint.value
                if (indicatorPoint != null) {
                    if (viewModel.currentTool.value == Tool.ERASER && viewModel.eraserMode.value != EraserMode.RECTANGLE) {
                        val size = viewModel.eraserSize.value ?: 20f
                        val hoverPaint = Paint().apply {
                            color = Color.RED
                            style = Paint.Style.STROKE
                            strokeWidth = 1f / scaleFactor
                            alpha = 128
                        }
                        canvas.drawCircle(indicatorPoint.x, indicatorPoint.y, size / scaleFactor, hoverPaint)
                    } else if (viewModel.currentTool.value == Tool.PEN) {
                        val size = (viewModel.penSize.value ?: 2f) / 2f
                        val hoverPaint = Paint().apply {
                            color = Color.BLUE
                            style = Paint.Style.STROKE
                            strokeWidth = 1f / scaleFactor
                            alpha = 128
                        }
                        canvas.drawCircle(indicatorPoint.x, indicatorPoint.y, size, hoverPaint)
                    }
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val spacingX = viewModel.gridSpacingX.value ?: 50f
        val spacingY = viewModel.gridSpacingY.value ?: 50f
        val gridOffX = viewModel.gridOffsetX.value ?: 0f
        val gridOffY = viewModel.gridOffsetY.value ?: 0f
        val showHorizontal = viewModel.showGridHorizontal.value ?: true
        val showVertical = viewModel.showGridVertical.value ?: false
        val color = viewModel.gridColor.value ?: Color.LTGRAY
        val thickness = viewModel.gridThickness.value ?: 1f

        gridPaint.color = color
        gridPaint.strokeWidth = thickness

        // Get visible bounds in canvas coordinates
        val pts = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        inverseMatrix.mapPoints(pts)
        val left = pts[0]
        val top = pts[1]
        val right = pts[2]
        val bottom = pts[3]

        if (showVertical) {
            val startX = (Math.floor(((left - gridOffX) / spacingX).toDouble()) * spacingX + gridOffX).toFloat()
            var x = startX
            while (x <= right) {
                canvas.drawLine(x, top, x, bottom, gridPaint)
                x += spacingX
            }
        }

        if (showHorizontal) {
            val startY = (Math.floor(((top - gridOffY) / spacingY).toDouble()) * spacingY + gridOffY).toFloat()
            var y = startY
            while (y <= bottom) {
                canvas.drawLine(left, y, right, y, gridPaint)
                y += spacingY
            }
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
