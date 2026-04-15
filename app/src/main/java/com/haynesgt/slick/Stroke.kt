package com.haynesgt.slick

import android.graphics.Color

data class Stroke(
    val id: String?,
    val points: List<Vector2D>,
    val width: Float,
    val color: Int = Color.BLACK,
    val pressures: List<Float>? = null,
    val isHighlighter: Boolean = false
)
