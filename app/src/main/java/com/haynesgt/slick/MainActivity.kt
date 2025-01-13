package com.haynesgt.slick

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : AppCompatActivity() {
    private lateinit var whiteboardViewModel: WhiteboardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // load shared preferences
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (sharedPreferences.getBoolean("first_run", true)) {
            // do something the first time the app is launched
            sharedPreferences.edit().putBoolean("first_run", false).apply()
        }
        if (sharedPreferences.getString("current_file", null) == null) {
            sharedPreferences.edit().putString("current_file", "test.svg").apply()
        }

        val whiteboardView = WhiteboardSurfaceView(this)

        val drawingBoardSvgService = DrawingBoardSvgService(this)

        whiteboardViewModel = ViewModelProvider(this)[WhiteboardViewModel::class.java]
        whiteboardViewModel.setFileName(sharedPreferences.getString("current_file", "test.svg")!!)
        whiteboardViewModel.fileName.observe(this) { fileName ->
            sharedPreferences.edit().putString("current_file", fileName).apply()
        }

        whiteboardView.bindViewModel(whiteboardViewModel, this)
        whiteboardView.onTapped = {
            whiteboardViewModel.toggleControlsVisibility()
        }
        whiteboardView.onPenDown = { point ->
            whiteboardViewModel.startNewStrokeAt(point)
        }
        whiteboardView.onPenMove = { point ->
            whiteboardViewModel.addPointToCurrentStroke(point)
        }
        whiteboardView.onPenUp = { point ->
            whiteboardViewModel.completeCurrentStrokeAt(point)
            drawingBoardSvgService.saveStrokesToFile("test.svg", whiteboardViewModel.strokes.value!!)
        }

        try {
            val strokes = drawingBoardSvgService.loadStrokesFromFile("test.svg")
            whiteboardViewModel.setStrokes(strokes)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { whiteboardViewModel.clearStrokes() }
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
            addView(clearButton, 0)
        }

        // Add to a layout
        val layout = FrameLayout(this).apply {
            addView(buttonLayout, 0)
            addView(whiteboardView, 0)
        }

        whiteboardViewModel.controlsVisible.observe(this) { controlsVisible ->
            buttonLayout.visibility =
                if (controlsVisible) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        setContentView(layout)
    }
}
