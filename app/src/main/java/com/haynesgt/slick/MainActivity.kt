package com.haynesgt.slick

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import java.io.File
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : AppCompatActivity() {
    private lateinit var whiteboardViewModel: WhiteboardViewModel

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // load shared preferences
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (sharedPreferences.getBoolean("first_run", true)) {
            // do something the first time the app is launched
            sharedPreferences.edit { putBoolean("first_run", false) }
        }

        val whiteboardView = WhiteboardSurfaceView(this)

        val sendThingsService = SendThingsService()
        val drawingBoardSvgService = DrawingBoardSvgService(this)

        var isInitialLoading = false

        whiteboardViewModel = ViewModelProvider(this)[WhiteboardViewModel::class.java]
        whiteboardViewModel.fileName.observe(this) { fileName ->
            sharedPreferences.edit { putString("current_file", fileName) }
            isInitialLoading = true
            try {
                val (strokes, viewPort) = drawingBoardSvgService.loadStrokesFromFile(fileName)
                whiteboardViewModel.setStrokes(strokes)
                whiteboardViewModel.setViewPort(viewPort)
            } catch (e: Exception) {
                Log.e("Slick", "Error loading file $fileName", e)
            } finally {
                isInitialLoading = false
            }
        }

        whiteboardViewModel.strokes.observe(this) { strokes ->
            if (isInitialLoading) return@observe
            Thread {
                drawingBoardSvgService.saveStrokesToFile(
                    whiteboardViewModel.fileName.value!!,
                    strokes,
                    whiteboardViewModel.viewPort.value ?: ViewPort(1f, 0f, 0f)
                )
            }.start()
        }
        
        whiteboardViewModel.viewPort.observe(this) { viewPort ->
            if (isInitialLoading) return@observe
            Thread {
                drawingBoardSvgService.saveStrokesToFile(
                    whiteboardViewModel.fileName.value!!,
                    whiteboardViewModel.strokes.value ?: emptyList(),
                    viewPort
                )
            }.start()
        }

        whiteboardView.bindViewModel(whiteboardViewModel, this)

        sharedPreferences.getString("current_file", null)?.let { savedFile ->
            whiteboardViewModel.setFileName(savedFile)
        }

        // Load and persist settings
        whiteboardViewModel.setSingleFingerPanEnabled(sharedPreferences.getBoolean("single_finger_pan", true))
        whiteboardViewModel.setInvertColors(sharedPreferences.getBoolean("invert_colors", false))
        whiteboardViewModel.setControlsLocked(sharedPreferences.getBoolean("controls_locked", false))

        whiteboardViewModel.singleFingerPanEnabled.observe(this) { enabled ->
            sharedPreferences.edit { putBoolean("single_finger_pan", enabled) }
        }
        whiteboardViewModel.invertColors.observe(this) { inverted ->
            sharedPreferences.edit { putBoolean("invert_colors", inverted) }
        }
        whiteboardViewModel.controlsLocked.observe(this) { locked ->
            sharedPreferences.edit { putBoolean("controls_locked", locked) }
        }

        whiteboardView.onTapped = {
            // No longer needed for hiding since we use onDown
        }
        whiteboardView.onDown = {
            if (whiteboardViewModel.controlsLocked.value != true) {
                whiteboardViewModel.setControlsVisibility(false)
            }
        }
        whiteboardView.onDoubleTapped = {
            whiteboardViewModel.setControlsVisibility(true)
        }
        whiteboardView.onSwipeFromEdge = {
            whiteboardViewModel.setControlsVisibility(true)
        }
        whiteboardView.onPenDown = { point ->
            whiteboardViewModel.startNewStrokeAt(point)
        }
        whiteboardView.onPenMove = { point ->
            whiteboardViewModel.addPointToCurrentStroke(point)
        }
        whiteboardView.onPenUp = { point ->
            whiteboardViewModel.completeCurrentStrokeAt(point)
        }

        val documentsButton = Button(this).apply {
            text = "Documents"
            setOnClickListener {
                val files = drawingBoardSvgService.listSvgFiles()
                val popup = PopupMenu(this@MainActivity, this)
                
                // Add "New" as a special entry at the top
                popup.menu.add(Menu.NONE, 0, 0, "New Document")
                
                files.forEachIndexed { index, fileName ->
                    // Offset index by 1 because 0 is "New Document"
                    popup.menu.add(Menu.NONE, index + 1, index + 1, fileName)
                }
                
                popup.setOnMenuItemClickListener { item ->
                    if (item.itemId == 0) {
                        whiteboardViewModel.createNewDocument()
                    } else {
                        val fileName = item.title.toString()
                        whiteboardViewModel.setFileName(fileName)
                        val (strokes, viewPort) = drawingBoardSvgService.loadStrokesFromFile(fileName)
                        whiteboardViewModel.setStrokes(strokes)
                        whiteboardViewModel.setViewPort(viewPort)
                    }
                    true
                }
                popup.show()
            }
        }

        val optionsButton = Button(this).apply {
            text = "Options"
            setOnClickListener {
                val popup = PopupMenu(this@MainActivity, this)
                val panItem = popup.menu.add("Single Finger Pan").apply {
                    isCheckable = true
                    isChecked = whiteboardViewModel.singleFingerPanEnabled.value ?: true
                }
                val invertItem = popup.menu.add("Invert Colors").apply {
                    isCheckable = true
                    isChecked = whiteboardViewModel.invertColors.value ?: false
                }
                val lockItem = popup.menu.add("Lock Toolbar").apply {
                    isCheckable = true
                    isChecked = whiteboardViewModel.controlsLocked.value ?: false
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item) {
                        panItem -> {
                            val newValue = !item.isChecked
                            whiteboardViewModel.setSingleFingerPanEnabled(newValue)
                        }
                        invertItem -> {
                            val newValue = !item.isChecked
                            whiteboardViewModel.setInvertColors(newValue)
                        }
                        lockItem -> {
                            val newValue = !item.isChecked
                            whiteboardViewModel.setControlsLocked(newValue)
                        }
                    }
                    true
                }
                popup.show()
            }
        }

        val closeButton = Button(this).apply {
            text = "X"
            setOnClickListener {
                whiteboardViewModel.setControlsVisibility(false)
            }
        }

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                sendThingsService.sendData(
                    File(filesDir, "drawings/" + whiteboardViewModel.fileName.value!!),
                    this@MainActivity,
                    this@MainActivity
                )
            }
        }

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(documentsButton)
            addView(sendButton)
            addView(optionsButton)
            addView(closeButton)
        }

        // Add to a layout
        val layout = FrameLayout(this).apply {
            addView(whiteboardView)
            addView(buttonLayout)
        }

        whiteboardViewModel.controlsVisible.observe(this) { controlsVisible ->
            buttonLayout.visibility =
                if (controlsVisible) View.VISIBLE else View.GONE
        }

        setContentView(layout)

        // Hide system bars for immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, layout).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
