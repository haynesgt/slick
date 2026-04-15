package com.haynesgt.slick

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import java.io.File
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : AppCompatActivity() {
    private lateinit var whiteboardViewModel: WhiteboardViewModel
    private lateinit var drawingBoardSvgService: DrawingBoardSvgService
    private lateinit var fileNameTextView: TextView

    private fun abbreviateFileName(name: String): String {
        val cleanName = name.removeSuffix(".svg")
        return if (cleanName.length > 30) {
            val firstPart = cleanName.substring(0, 14)
            val lastPart = cleanName.substring(cleanName.length - 13)
            "$firstPart...$lastPart"
        } else {
            cleanName
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val importedFileName = drawingBoardSvgService.importFile(it)
            if (importedFileName != null) {
                whiteboardViewModel.setFileName(importedFileName)
                val (strokes, viewPort) = drawingBoardSvgService.loadStrokesFromFile(importedFileName)
                whiteboardViewModel.setStrokes(strokes)
                whiteboardViewModel.setViewPort(viewPort)
                Toast.makeText(this, "Imported $importedFileName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to import file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val whiteboardView = WhiteboardSurfaceView(this)
        val sendThingsService = SendThingsService()
        drawingBoardSvgService = DrawingBoardSvgService(this)

        var isInitialLoading = false

        whiteboardViewModel = ViewModelProvider(this)[WhiteboardViewModel::class.java]
        whiteboardViewModel.fileName.observe(this) { fileName ->
            sharedPreferences.edit { putString("current_file", fileName) }
            if (::fileNameTextView.isInitialized) {
                fileNameTextView.text = abbreviateFileName(fileName)
            }
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
                showDocumentsDialog()
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
                        panItem -> whiteboardViewModel.setSingleFingerPanEnabled(!item.isChecked)
                        invertItem -> whiteboardViewModel.setInvertColors(!item.isChecked)
                        lockItem -> whiteboardViewModel.setControlsLocked(!item.isChecked)
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

        fileNameTextView = TextView(this).apply {
            setPadding(16, 0, 16, 0)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            text = whiteboardViewModel.fileName.value?.let { abbreviateFileName(it) }
        }

        val buttonLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(16, 16, 16, 16)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(documentsButton)
            addView(fileNameTextView)
            addView(sendButton)
            addView(optionsButton)
            addView(closeButton)
        }

        val layout = FrameLayout(this).apply {
            addView(whiteboardView)
            addView(buttonLayout)
        }

        whiteboardViewModel.controlsVisible.observe(this) { controlsVisible ->
            buttonLayout.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }

        setContentView(layout)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, layout).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showDocumentsDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(dialogView)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            
            val bottomBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(Button(this@MainActivity).apply {
                    text = "New"
                    setOnClickListener {
                        whiteboardViewModel.createNewDocument()
                        currentDialog?.dismiss()
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Import"
                    setOnClickListener {
                        importLauncher.launch("image/svg+xml")
                        currentDialog?.dismiss()
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = "📦 Archive"
                    setOnClickListener {
                        val d = currentDialog
                        showArchiveDialog()
                        d?.dismiss()
                    }
                })
            }
            addView(bottomBar)
        }

        val currentFile = whiteboardViewModel.fileName.value
        val files = drawingBoardSvgService.listSvgFiles()
        files.forEach { fileName ->
            val isCurrent = fileName == currentFile
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 8, 16, 8)
                if (isCurrent) {
                    setBackgroundColor(Color.argb(40, 0, 0, 0)) // Soft highlight
                }

                val nameText = TextView(this@MainActivity).apply {
                    text = fileName.removeSuffix(".svg")
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 18f
                    if (isCurrent) {
                        setTypeface(null, Typeface.BOLD)
                    }
                    setOnClickListener {
                        whiteboardViewModel.setFileName(fileName)
                        currentDialog?.dismiss()
                    }
                }
                addView(nameText)

                addView(Button(this@MainActivity).apply {
                    text = "✏️"
                    layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { showRenameDialog(fileName) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "📦"
                    layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        if (drawingBoardSvgService.archiveFile(fileName)) {
                            if (whiteboardViewModel.fileName.value == fileName) {
                                whiteboardViewModel.createNewDocument()
                            }
                            currentDialog?.dismiss()
                            showDocumentsDialog()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to archive", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
            dialogView.addView(row)
        }

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Documents")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showArchiveDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val scroll = ScrollView(this).apply {
            addView(dialogView)
        }

        val files = drawingBoardSvgService.listArchivedFiles()
        if (files.isEmpty()) {
            dialogView.addView(TextView(this).apply { text = "No archived documents" })
        }

        files.forEach { fileName ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)

                val nameText = TextView(this@MainActivity).apply {
                    text = fileName.removeSuffix(".svg")
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    textSize = 16f
                    setTextColor(Color.GRAY)
                }
                addView(nameText)

                addView(Button(this@MainActivity).apply {
                    text = "Restore"
                    setOnClickListener {
                        if (drawingBoardSvgService.restoreFile(fileName)) {
                            currentDialog?.dismiss()
                            showDocumentsDialog()
                        }
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = "🗑️"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Delete $fileName permanently?")
                            .setPositiveButton("Delete") { _, _ ->
                                if (drawingBoardSvgService.deleteFile(fileName, fromArchive = true)) {
                                    currentDialog?.dismiss()
                                    showArchiveDialog()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
            dialogView.addView(row)
        }

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Archived Documents")
            .setView(scroll)
            .setPositiveButton("Back") { _, _ -> showDocumentsDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private var currentDialog: AlertDialog? = null

    private fun showRenameDialog(oldName: String, inArchive: Boolean = false) {
        val cleanName = oldName.removeSuffix(".svg")
        val input = EditText(this).apply {
            setText(cleanName)
            setSelection(cleanName.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Document")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotBlank() && newName != cleanName) {
                    if (drawingBoardSvgService.renameFile(oldName, newName, inArchive)) {
                        val finalName = if (newName.endsWith(".svg")) newName else "$newName.svg"
                        if (whiteboardViewModel.fileName.value == oldName && !inArchive) {
                            whiteboardViewModel.setFileName(finalName)
                        }
                        currentDialog?.dismiss()
                        if (inArchive) showArchiveDialog() else showDocumentsDialog()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to rename", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
