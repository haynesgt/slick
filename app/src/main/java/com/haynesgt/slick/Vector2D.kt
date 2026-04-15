package com.haynesgt.slick


import kotlin.math.sqrt

data class Vector2D(val x: Float, val y: Float) {
    fun distanceTo(other: Vector2D): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}
