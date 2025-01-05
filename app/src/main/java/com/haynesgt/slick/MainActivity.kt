package com.haynesgt.slick

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create com.haynesgt.slick.WhiteboardView and Button
        //val whiteboardView = WhiteboardView(this)
        val whiteboardView = WhiteboardSurfaceView(this)
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { whiteboardView.clear() }
        }

        // Add to a layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(whiteboardView, 0)
            addView(clearButton, 0)
        }

        setContentView(layout)
    }
}
