package com.haynesgt.slick

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlin.random.Random

class WhiteboardViewModel : ViewModel() {
    private val _strokes = mutableListOf<Stroke>()

    val controlsVisible: MutableLiveData<Boolean> = MutableLiveData(true)

    fun addStroke(stroke: Stroke) {
        _strokes.add(stroke)
    }

    fun getStrokes(): List<Stroke> {
        return _strokes.toList()
    }

    fun clearStrokes() {
        _strokes.clear()
    }

    fun undoStroke() {
        if (_strokes.isNotEmpty()) {
            _strokes.removeAt(_strokes.size - 1)
        }
    }

    fun startNewStrokeAt(point: Vector2D): Stroke {
        val id = Random.nextLong().toString()
        val stroke = Stroke(id, mutableListOf(point))
        _strokes.add(stroke)
        return stroke
    }

    fun addPointToCurrentStroke(point: Vector2D) {
        if (_strokes.isEmpty()) {
            startNewStrokeAt(point)
        } else {
            val currentStroke = _strokes.last()
            currentStroke.points.add(point)
        }
    }

    fun toggleControlsVisibility() {
        val currentVisibility = (controlsVisible.value ?: false).not()
        controlsVisible.value = currentVisibility
    }
}
