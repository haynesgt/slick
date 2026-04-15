package com.haynesgt.slick

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.io.FileNotFoundException

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
            sharedPreferences.edit().putBoolean("first_run", false).apply()
        }

        val whiteboardView = WhiteboardSurfaceView(this)

        val sendThingsService = SendThingsService()
        val drawingBoardSvgService = DrawingBoardSvgService(this)

        whiteboardViewModel = ViewModelProvider(this)[WhiteboardViewModel::class.java]
        whiteboardViewModel.fileName.observe(this) { fileName ->
            sharedPreferences.edit().putString("current_file", fileName).apply()
            try {
                val strokes = drawingBoardSvgService.loadStrokesFromFile(fileName)
                whiteboardViewModel.setStrokes(strokes)
            } catch (e: FileNotFoundException) {
                whiteboardViewModel.setStrokes(emptyList())
                drawingBoardSvgService.saveStrokesToFile(fileName, emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        whiteboardView.bindViewModel(whiteboardViewModel, this)

        whiteboardViewModel.setFileName(sharedPreferences.getString("current_file", "test.svg")!!)

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
            Thread {
                drawingBoardSvgService.saveStrokesToFile(whiteboardViewModel.fileName.value!!, whiteboardViewModel.strokes.value!!)
            }.start()
        }

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { whiteboardViewModel.clearStrokes() }
        }

        val previousPageButton = Button(this).apply {
            text = "Previous Page"
            setOnClickListener {
                // find the number before the dot in the fileName and decrement it, or add one
                // if it is not there
                if (whiteboardViewModel.fileName.value!!.contains(".")) {
                    val fileName = whiteboardViewModel.fileName.value!!.split(".")[0]
                    val pageNumberRegex = Regex("\\d+")
                    val pageNumberMatch = pageNumberRegex.find(fileName)

                    if (pageNumberMatch != null) {
                        val pageNumber = 1.coerceAtLeast(pageNumberMatch.value.toInt() - 1)
                        val newFileName = fileName.replace(pageNumberMatch.value, pageNumber.toString())
                        whiteboardViewModel.setFileName("$newFileName.svg")
                    } else {
                        whiteboardViewModel.setFileName("${whiteboardViewModel.fileName.value!!.split(".")[0]}-1.svg")
                    }
                }
            }
        }

        val nextPageButton = Button(this).apply {
            text = "Next Page"
            setOnClickListener {
                // find the number before the dot in the fileName and increment it, or add one
                // if it is not there
                if (whiteboardViewModel.fileName.value!!.contains(".")) {
                    val fileName = whiteboardViewModel.fileName.value!!.split(".")[0]
                    val pageNumberRegex = Regex("\\d+")
                    val pageNumberMatch = pageNumberRegex.find(fileName)

                    if (pageNumberMatch != null) {
                        val pageNumber = pageNumberMatch.value.toInt()
                        val newFileName =
                            fileName.replace(pageNumberMatch.value, (pageNumber + 1).toString())
                        whiteboardViewModel.setFileName("$newFileName.svg")
                    } else {
                        whiteboardViewModel.setFileName(
                            "${
                                whiteboardViewModel.fileName.value!!.split(
                                    "."
                                )[0]
                            }-1.svg"
                        )
                    }
                }
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

        /*
        val insetsController = window.insetsController
        if (isInImmersiveMode) {
            insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            isInImmersiveMode = false
        } else {
            insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            isInImmersiveMode = true
        }
         */

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            var buttonI = 0
            addView(previousPageButton, buttonI++)
            addView(nextPageButton, buttonI++)
            addView(sendButton, buttonI++)
            addView(optionsButton, buttonI++)
            addView(closeButton, buttonI++)
            assert(buttonI>1)
            //addView(clearButton, 2)
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
