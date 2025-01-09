package com.haynesgt.slick

import android.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
class WhiteboardOptions {
    var defaultBackgroundColor: Int = Color.WHITE
    var defaultStrokeColor: Int = Color.BLACK
    var drawWithoutStylus: Boolean = false
}
