package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hsv = floatArrayOf(0f, 1f, 1f)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }

    var onColorChanged: ((Int) -> Unit)? = null

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    fun getColor(): Int = Color.HSVToColor(hsv)

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = Math.min(centerX, centerY) * 0.9f
        val innerRadius = radius * 0.7f

        // Draw Hue Ring
        val hueShader = SweepGradient(centerX, centerY, 
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null
        )
        huePaint.shader = hueShader
        huePaint.style = Paint.Style.STROKE
        huePaint.strokeWidth = radius - innerRadius
        canvas.drawCircle(centerX, centerY, (radius + innerRadius) / 2f, huePaint)

        // Draw SV Box (simplified as a gradient in the center)
        val svShader = LinearGradient(
            centerX - innerRadius/1.4f, centerY - innerRadius/1.4f,
            centerX + innerRadius/1.4f, centerY + innerRadius/1.4f,
            intArrayOf(Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Color.BLACK),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        svPaint.shader = svShader
        svPaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, innerRadius * 0.6f, svPaint)
        
        // Draw Hue Thumb
        val angle = Math.toRadians(hsv[0].toDouble())
        val tx = centerX + ((radius + innerRadius) / 2f) * cos(angle).toFloat()
        val ty = centerY + ((radius + innerRadius) / 2f) * sin(angle).toFloat()
        canvas.drawCircle(tx, ty, 15f, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x - width / 2f
        val y = event.y - height / 2f
        val dist = sqrt(x * x + y * y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                updateColor(x, y, dist)
                performClick()
            }
            MotionEvent.ACTION_MOVE -> {
                updateColor(x, y, dist)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateColor(x: Float, y: Float, dist: Float) {
        val angle = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()
        hsv[0] = if (angle < 0) angle + 360f else angle
        
        // If touching center area, adjust Saturation/Value (simplified)
        // This is a bit crude but works for a single-view picker
        if (dist < width * 0.3f) {
            // Inner circle tap: could toggle between white/black or adjust sat
            // For now let's just keep it simple.
        }
        
        onColorChanged?.invoke(getColor())
        invalidate()
    }
}
