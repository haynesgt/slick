package com.haynesgt.slick

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : AppCompatActivity() {
    private lateinit var whiteboardViewModel: WhiteboardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var drawingBoardSvgService = DrawingBoardSvgService()

        whiteboardViewModel = ViewModelProvider(this)[WhiteboardViewModel::class.java]

        val whiteboardView = WhiteboardSurfaceView(this)
        whiteboardView.onTapped = {
            whiteboardViewModel.toggleControlsVisibility()
        }

        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { whiteboardView.clear() }
        }

        val previousPageButton = Button(this).apply {
            text = "Previous Page"
            setOnClickListener {
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                startActivity(intent)
            }
        }

        val nextPageButton = Button(this).apply {
            text = "Next Page"
            setOnClickListener {
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                startActivity(intent)
            }
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(previousPageButton, 0)
            addView(nextPageButton, 0)
        }

        // Add to a layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(whiteboardView, 0)
            addView(buttonLayout, 0)
        }

        whiteboardViewModel.controlsVisible.observe(this) { controlsVisible ->
            buttonLayout.visibility =
                if (controlsVisible) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        setContentView(layout)
    }
}
