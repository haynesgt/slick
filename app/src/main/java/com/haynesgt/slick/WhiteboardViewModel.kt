package com.haynesgt.slick

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class WhiteboardViewModel : ViewModel() {
    private val _strokes: MutableLiveData<List<Stroke>> = MutableLiveData(listOf())
    val strokes: LiveData<List<Stroke>> get() = _strokes

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

    private val _singleFingerPanEnabled: MutableLiveData<Boolean> = MutableLiveData(true)
    val singleFingerPanEnabled: LiveData<Boolean> get() = _singleFingerPanEnabled

    private val _invertColors: MutableLiveData<Boolean> = MutableLiveData(false)
    val invertColors: LiveData<Boolean> get() = _invertColors

    private val _controlsLocked: MutableLiveData<Boolean> = MutableLiveData(false)
    val controlsLocked: LiveData<Boolean> get() = _controlsLocked

    private fun addStroke(stroke: Stroke) {
        _strokes.value = _strokes.value?.plus(stroke)
    }

    fun clearStrokes() {
        _strokes.value = listOf()
    }

    fun undoStroke() {
        if (_strokes.value?.isNotEmpty() == true) {
            _strokes.value = _strokes.value?.dropLast(1)
        }
    }

    fun startNewStrokeAt(point: Vector2D): Stroke {
        val id = Random.nextLong().toString()
        if (_currentStroke.value != null) {
            completeCurrentStroke()
        }
        val stroke = Stroke(id, listOf(point))
        _currentStroke.value = stroke
        return stroke
    }

    fun addPointToCurrentStroke(point: Vector2D) {
        val currentStroke = _currentStroke.value
        if (currentStroke != null) {
            _currentStroke.value = Stroke(currentStroke.id, currentStroke.points.plus(point))
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
}
