package com.haynesgt.slick

import android.graphics.Color
import android.graphics.RectF
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class Tool {
    PEN, ERASER
}

enum class EraserMode {
    STROKE, POINT, RECTANGLE
}

class WhiteboardViewModel : ViewModel() {
    private val _strokes: MutableLiveData<List<Stroke>> = MutableLiveData(listOf())
    val strokes: LiveData<List<Stroke>> get() = _strokes

    private val undoStack = mutableListOf<List<Stroke>>()
    private val redoStack = mutableListOf<List<Stroke>>()

    private fun saveToUndoStack() {
        _strokes.value?.let {
            undoStack.add(it.toList())
            if (undoStack.size > 50) {
                undoStack.removeAt(0)
            }
        }
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val currentState = _strokes.value ?: listOf()
            redoStack.add(currentState)
            _strokes.value = undoStack.removeAt(undoStack.size - 1)
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val currentState = _strokes.value ?: listOf()
            undoStack.add(currentState)
            _strokes.value = redoStack.removeAt(redoStack.size - 1)
        }
    }

    private val _currentTool: MutableLiveData<Tool> = MutableLiveData(Tool.PEN)
    val currentTool: LiveData<Tool> get() = _currentTool

    private val _eraserMode: MutableLiveData<EraserMode> = MutableLiveData(EraserMode.STROKE)
    val eraserMode: LiveData<EraserMode> get() = _eraserMode

    private val _eraserRect: MutableLiveData<RectF?> = MutableLiveData(null)
    val eraserRect: LiveData<RectF?> get() = _eraserRect

    private val _eraserSize: MutableLiveData<Float> = MutableLiveData(20f)
    val eraserSize: LiveData<Float> get() = _eraserSize

    private val _penSize: MutableLiveData<Float> = MutableLiveData(2f)
    val penSize: LiveData<Float> get() = _penSize

    private val _hoverPoint: MutableLiveData<Vector2D?> = MutableLiveData(null)
    val hoverPoint: LiveData<Vector2D?> get() = _hoverPoint

    private val _currentPenPoint: MutableLiveData<Vector2D?> = MutableLiveData(null)
    val currentPenPoint: LiveData<Vector2D?> get() = _currentPenPoint

    private val _controlsVisible: MutableLiveData<Boolean> = MutableLiveData(false)
    val controlsVisible: LiveData<Boolean> get() = _controlsVisible

    private val _currentStroke: MutableLiveData<Stroke?> = MutableLiveData(null)
    val currentStroke: LiveData<Stroke?> get() = _currentStroke

    private val _lastStrokeCompleteAt: MutableLiveData<Long> = MutableLiveData(0)
    val lastStrokeCompleteAt: LiveData<Long> get() = _lastStrokeCompleteAt

    private val _fileName: MutableLiveData<String> = MutableLiveData(
        "slick_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())}.svg"
    )
    val fileName: LiveData<String> get() = _fileName

    private val _viewPort: MutableLiveData<ViewPort> = MutableLiveData(ViewPort(1f, 0f, 0f))
    val viewPort: LiveData<ViewPort> get() = _viewPort

    private val _singleFingerPanEnabled: MutableLiveData<Boolean> = MutableLiveData(true)
    val singleFingerPanEnabled: LiveData<Boolean> get() = _singleFingerPanEnabled

    private val _invertColors: MutableLiveData<Boolean> = MutableLiveData(false)
    val invertColors: LiveData<Boolean> get() = _invertColors

    private val _controlsLocked: MutableLiveData<Boolean> = MutableLiveData(false)
    val controlsLocked: LiveData<Boolean> get() = _controlsLocked

    private val _showGrid: MutableLiveData<Boolean> = MutableLiveData(false)
    val showGrid: LiveData<Boolean> get() = _showGrid

    private val _showGridHorizontal: MutableLiveData<Boolean> = MutableLiveData(true)
    val showGridHorizontal: LiveData<Boolean> get() = _showGridHorizontal

    private val _showGridVertical: MutableLiveData<Boolean> = MutableLiveData(false)
    val showGridVertical: LiveData<Boolean> get() = _showGridVertical

    private val _gridSpacingX: MutableLiveData<Float> = MutableLiveData(50f)
    val gridSpacingX: LiveData<Float> get() = _gridSpacingX

    val gridSpacing: LiveData<Float> get() = _gridSpacingX

    private val _gridSpacingY: MutableLiveData<Float> = MutableLiveData(50f)
    val gridSpacingY: LiveData<Float> get() = _gridSpacingY

    private val _gridOffsetX: MutableLiveData<Float> = MutableLiveData(0f)
    val gridOffsetX: LiveData<Float> get() = _gridOffsetX

    private val _gridOffsetY: MutableLiveData<Float> = MutableLiveData(0f)
    val gridOffsetY: LiveData<Float> get() = _gridOffsetY

    private val _gridColor: MutableLiveData<Int> = MutableLiveData(Color.LTGRAY)
    val gridColor: LiveData<Int> get() = _gridColor

    private val _gridThickness: MutableLiveData<Float> = MutableLiveData(1f)
    val gridThickness: LiveData<Float> get() = _gridThickness

    private val _backgroundColor: MutableLiveData<Int> = MutableLiveData(Color.WHITE)
    val backgroundColor: LiveData<Int> get() = _backgroundColor

    private fun addStroke(stroke: Stroke) {
        saveToUndoStack()
        _strokes.value = _strokes.value?.plus(stroke)
    }

    fun clearStrokes() {
        saveToUndoStack()
        _strokes.value = listOf()
    }

    fun undoStroke() {
        undo()
    }

    fun startNewStrokeAt(point: Vector2D): Stroke {
        val id = Random.nextLong().toString()
        if (_currentStroke.value != null) {
            completeCurrentStroke()
        }
        val stroke = Stroke(id, listOf(point), _penSize.value ?: 2f)
        _currentStroke.value = stroke
        return stroke
    }

    fun addPointToCurrentStroke(point: Vector2D) {
        val currentStroke = _currentStroke.value
        if (currentStroke != null) {
            _currentStroke.value = Stroke(currentStroke.id, currentStroke.points.plus(point), currentStroke.width)
        } else {
            startNewStrokeAt(point)
        }
    }

    fun completeCurrentStrokeAt(point: Vector2D) {
        val currentStroke = _currentStroke.value
        if (currentStroke != null) {
            addPointToCurrentStroke(point)
            completeCurrentStroke()
        } else {
            startNewStrokeAt(point)
            completeCurrentStroke()
        }
    }

    fun completeCurrentStroke() {
        if (_currentStroke.value != null) {
            addStroke(_currentStroke.value!!)
            _currentStroke.value = null
            _lastStrokeCompleteAt.value = System.currentTimeMillis()
        }
    }

    fun toggleControlsVisibility() {
        val currentVisibility = (controlsVisible.value ?: false).not()
        _controlsVisible.value = currentVisibility
    }

    fun setControlsVisibility(visible: Boolean) {
        _controlsVisible.value = visible
    }

    fun setStrokes(strokes: List<Stroke>) {
        _strokes.value = strokes
    }

    fun setFileName(string: String) {
        _fileName.value = string
    }

    fun setViewPort(viewPort: ViewPort) {
        _viewPort.value = viewPort
    }

    fun createNewDocument() {
        val newName = "slick_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())}.svg"
        _fileName.value = newName
        _strokes.value = listOf()
        _viewPort.value = ViewPort(1f, 0f, 0f)
    }

    fun setSingleFingerPanEnabled(enabled: Boolean) {
        _singleFingerPanEnabled.value = enabled
    }

    fun setInvertColors(invert: Boolean) {
        _invertColors.value = invert
    }

    fun setControlsLocked(locked: Boolean) {
        _controlsLocked.value = locked
        if (locked) {
            _controlsVisible.value = true
        }
    }

    fun setShowGrid(show: Boolean) {
        _showGrid.value = show
    }

    fun setShowGridHorizontal(show: Boolean) {
        _showGridHorizontal.value = show
    }

    fun setShowGridVertical(show: Boolean) {
        _showGridVertical.value = show
    }

    fun setGridSpacingX(spacing: Float) {
        _gridSpacingX.value = spacing
    }

    fun setGridSpacingY(spacing: Float) {
        _gridSpacingY.value = spacing
    }

    fun setGridSpacing(spacing: Float) {
        _gridSpacingX.value = spacing
        _gridSpacingY.value = spacing
    }

    fun setGridOffsetX(offset: Float) {
        _gridOffsetX.value = offset
    }

    fun setGridOffsetY(offset: Float) {
        _gridOffsetY.value = offset
    }

    fun setGridColor(color: Int) {
        _gridColor.value = color
    }

    fun setGridThickness(thickness: Float) {
        _gridThickness.value = thickness
    }

    fun setBackgroundColor(color: Int) {
        _backgroundColor.value = color
    }

    fun setCurrentTool(tool: Tool) {
        _currentTool.value = tool
    }

    fun setEraserMode(mode: EraserMode) {
        _eraserMode.value = mode
    }

    fun eraseAt(point: Vector2D) {
        val mode = _eraserMode.value ?: EraserMode.STROKE
        val currentStrokes = _strokes.value ?: return
        val size = _eraserSize.value ?: 20f
        
        when (mode) {
            EraserMode.STROKE -> {
                val threshold = size / (viewPort.value?.scale ?: 1f)
                val newStrokes = currentStrokes.filter { stroke ->
                    stroke.points.none { p -> p.distanceTo(point) < threshold }
                }
                if (newStrokes.size != currentStrokes.size) {
                    saveToUndoStack()
                    _strokes.value = newStrokes
                }
            }
            EraserMode.POINT -> {
                val threshold = size / (viewPort.value?.scale ?: 1f)
                val newStrokes = mutableListOf<Stroke>()
                var changed = false
                
                for (stroke in currentStrokes) {
                    val segments = mutableListOf<MutableList<Vector2D>>()
                    var currentSegment = mutableListOf<Vector2D>()
                    
                    for (p in stroke.points) {
                        if (p.distanceTo(point) < threshold) {
                            if (currentSegment.isNotEmpty()) {
                                segments.add(currentSegment)
                                currentSegment = mutableListOf()
                            }
                            changed = true
                        } else {
                            currentSegment.add(p)
                        }
                    }
                    if (currentSegment.isNotEmpty()) {
                        segments.add(currentSegment)
                    }
                    
                    for (segment in segments) {
                        if (segment.size >= 2) {
                            newStrokes.add(Stroke(Random.nextLong().toString(), segment, stroke.width))
                        } else if (segment.size == 1) {
                            // Keep single points? Maybe not for now.
                        }
                    }
                }
                
                if (changed) {
                    saveToUndoStack()
                    _strokes.value = newStrokes
                }
            }
            EraserMode.RECTANGLE -> {
                // Handled by completeEraserRect
            }
        }
    }

    fun startEraserRect(point: Vector2D) {
        _eraserRect.value = RectF(point.x, point.y, point.x, point.y)
    }

    fun updateEraserRect(point: Vector2D) {
        val rect = _eraserRect.value ?: return
        _eraserRect.value = RectF(
            minOf(rect.left, point.x),
            minOf(rect.top, point.y),
            maxOf(rect.right, point.x),
            maxOf(rect.bottom, point.y)
        )
    }

    fun completeEraserRect() {
        val rect = _eraserRect.value ?: return
        val currentStrokes = _strokes.value ?: return
        
        val newStrokes = mutableListOf<Stroke>()
        var changed = false
        
        for (stroke in currentStrokes) {
            val segments = mutableListOf<MutableList<Vector2D>>()
            var currentSegment = mutableListOf<Vector2D>()
            
            for (p in stroke.points) {
                if (rect.contains(p.x, p.y)) {
                    if (currentSegment.isNotEmpty()) {
                        segments.add(currentSegment)
                        currentSegment = mutableListOf()
                    }
                    changed = true
                } else {
                    currentSegment.add(p)
                }
            }
            if (currentSegment.isNotEmpty()) {
                segments.add(currentSegment)
            }
            
            for (segment in segments) {
                if (segment.size >= 2) {
                    newStrokes.add(Stroke(java.util.UUID.randomUUID().toString(), segment, stroke.width))
                }
            }
        }
        
        if (changed) {
            saveToUndoStack()
            _strokes.value = newStrokes
        }
        _eraserRect.value = null
    }

    fun setEraserSize(size: Float) {
        _eraserSize.value = size
    }

    fun setPenSize(size: Float) {
        _penSize.value = size
    }

    fun setHoverPoint(point: Vector2D?) {
        _hoverPoint.value = point
    }

    fun setCurrentPenPoint(point: Vector2D?) {
        _currentPenPoint.value = point
    }
}
