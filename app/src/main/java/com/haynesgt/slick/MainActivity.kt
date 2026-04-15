package com.haynesgt.slick

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.WindowManager
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : AppCompatActivity() {
    private lateinit var whiteboardViewModel: WhiteboardViewModel
    private lateinit var drawingBoardSvgService: DrawingBoardSvgService
    private lateinit var googleDriveSyncService: GoogleDriveSyncService
    private lateinit var fileNameTextView: TextView
    private lateinit var syncStatusTextView: TextView
    private var syncJob: Job? = null

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            googleDriveSyncService.reset()
            Toast.makeText(this, "Signed in to Google Drive", Toast.LENGTH_SHORT).show()
            googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings")) {
                runOnUiThread {
                    Toast.makeText(this, "Downloaded missing files from Drive", Toast.LENGTH_SHORT).show()
                    if (currentDialog?.isShowing == true) {
                        currentDialog?.dismiss()
                        showDocumentsDialog()
                    }
                }
            }
        }
    }

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

    private fun saveAndSyncDebounced() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(2000) // Wait for 2 seconds of inactivity
            val fileName = whiteboardViewModel.fileName.value ?: return@launch
            val strokes = whiteboardViewModel.strokes.value ?: emptyList()
            val viewPort = whiteboardViewModel.viewPort.value ?: ViewPort(1f, 0f, 0f)

            drawingBoardSvgService.saveStrokesToFile(fileName, strokes, viewPort)
            
            val file = File(filesDir, "drawings/$fileName")
            if (file.exists()) {
                googleDriveSyncService.syncFile(file)
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
        googleDriveSyncService = GoogleDriveSyncService(this)

        googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings")) {
            runOnUiThread {
                Toast.makeText(this, "Downloaded missing files from Drive", Toast.LENGTH_SHORT).show()
            }
        }

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
            saveAndSyncDebounced()
        }
        
        whiteboardViewModel.viewPort.observe(this) { viewPort ->
            if (isInitialLoading) return@observe
            saveAndSyncDebounced()
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

        syncStatusTextView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            setTextColor(Color.LTGRAY)
            textSize = 12f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                googleDriveSyncService.syncStatus.collect { status ->
                    syncStatusTextView.text = when (status) {
                        SyncStatus.Idle -> ""
                        SyncStatus.Syncing -> "Syncing..."
                        SyncStatus.Success -> "Synced"
                        is SyncStatus.Error -> "Sync failed: ${status.message}"
                    }
                }
            }
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
                val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                val syncItem = if (account != null) {
                    popup.menu.add("Change Account (${account.email})")
                } else {
                    popup.menu.add("Sign in to Google Drive")
                }
                val gridSettingsItem = popup.menu.add("Grid Settings...")
                val bgSettingsItem = popup.menu.add("Background Color...")

                popup.setOnMenuItemClickListener { item ->
                    when (item) {
                        panItem -> whiteboardViewModel.setSingleFingerPanEnabled(!item.isChecked)
                        invertItem -> whiteboardViewModel.setInvertColors(!item.isChecked)
                        lockItem -> whiteboardViewModel.setControlsLocked(!item.isChecked)
                        gridSettingsItem -> showGridSettingsDialog()
                        bgSettingsItem -> showBackgroundSettingsDialog()
                        syncItem -> {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                                .build()
                            val client = GoogleSignIn.getClient(this@MainActivity, gso)
                            googleSignInLauncher.launch(client.signInIntent)
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

        fileNameTextView = TextView(this).apply {
            setPadding(8, 8, 8, 8)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 20f
            text = whiteboardViewModel.fileName.value?.let { abbreviateFileName(it) }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(documentsButton)
            addView(sendButton)
            addView(optionsButton)
            addView(closeButton)
        }

        val toolbarLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(16, 16, 16, 16)
            }
            orientation = LinearLayout.VERTICAL
            addView(fileNameTextView)
            addView(buttonRow)
        }

        val layout = FrameLayout(this).apply {
            addView(whiteboardView)
            addView(toolbarLayout)
            addView(syncStatusTextView)
        }

        whiteboardViewModel.controlsVisible.observe(this) { controlsVisible ->
            val visibility = if (controlsVisible) View.VISIBLE else View.GONE
            toolbarLayout.visibility = visibility
            syncStatusTextView.visibility = visibility
        }

        setContentView(layout)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, layout).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun invertColor(color: Int): Int {
        val r = 255 - Color.red(color)
        val g = 255 - Color.green(color)
        val b = 255 - Color.blue(color)

        val r1 = r.toFloat()
        val g1 = g.toFloat()
        val b1 = b.toFloat()

        val nr = ((-0.574f * r1 + 1.430f * g1 + 0.144f * b1).toInt()).coerceIn(0, 255)
        val ng = ((0.426f * r1 + 0.430f * g1 + 0.144f * b1).toInt()).coerceIn(0, 255)
        val nb = ((0.426f * r1 + 1.430f * g1 - 0.856f * b1).toInt()).coerceIn(0, 255)

        return Color.rgb(nr, ng, nb)
    }

    private fun showGridSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val showGridCheck = CheckBox(this).apply {
            text = "Show Grid"
            isChecked = whiteboardViewModel.showGrid.value ?: false
            setOnCheckedChangeListener { _, isChecked -> whiteboardViewModel.setShowGrid(isChecked) }
        }
        layout.addView(showGridCheck)

        val showHorizontalCheck = CheckBox(this).apply {
            text = "Horizontal Lines"
            isChecked = whiteboardViewModel.showGridHorizontal.value ?: true
            setOnCheckedChangeListener { _, isChecked -> whiteboardViewModel.setShowGridHorizontal(isChecked) }
        }
        layout.addView(showHorizontalCheck)

        val showVerticalCheck = CheckBox(this).apply {
            text = "Vertical Lines"
            isChecked = whiteboardViewModel.showGridVertical.value ?: false
            setOnCheckedChangeListener { _, isChecked -> whiteboardViewModel.setShowGridVertical(isChecked) }
        }
        layout.addView(showVerticalCheck)

        layout.addView(TextView(this).apply { text = "\nHorizontal Line Spacing" })
        val spacingYSlider = SeekBar(this).apply {
            max = 200
            progress = (whiteboardViewModel.gridSpacingY.value ?: 50f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val value = if (p < 10) 10f else p.toFloat()
                    whiteboardViewModel.setGridSpacingY(value)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        layout.addView(spacingYSlider)

        layout.addView(TextView(this).apply { text = "Vertical Line Spacing" })
        val spacingXSlider = SeekBar(this).apply {
            max = 200
            progress = (whiteboardViewModel.gridSpacingX.value ?: 50f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val value = if (p < 10) 10f else p.toFloat()
                    whiteboardViewModel.setGridSpacingX(value)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        layout.addView(spacingXSlider)

        layout.addView(TextView(this).apply { text = "\nOffset X" })
        val offsetXSlider = SeekBar(this).apply {
            max = 400
            progress = ((whiteboardViewModel.gridOffsetX.value ?: 0f) + 200).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    whiteboardViewModel.setGridOffsetX((p - 200).toFloat())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        layout.addView(offsetXSlider)

        layout.addView(TextView(this).apply { text = "Offset Y" })
        val offsetYSlider = SeekBar(this).apply {
            max = 400
            progress = ((whiteboardViewModel.gridOffsetY.value ?: 0f) + 200).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    whiteboardViewModel.setGridOffsetY((p - 200).toFloat())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        layout.addView(offsetYSlider)

        AlertDialog.Builder(this)
            .setTitle("Grid Settings")
            .setView(layout)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showBackgroundSettingsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val isInverted = whiteboardViewModel.invertColors.value ?: false
        var currentColor = whiteboardViewModel.backgroundColor.value ?: Color.WHITE
        
        // Use a wrapper to handle intelligent inversion
        // If we want it to LOOK like C on screen, we store C if not inverted, or Invert(C) if inverted.
        // Let's assume the sliders represent the VISUAL color.
        
        var visualColor = if (isInverted) invertColor(currentColor) else currentColor

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply {
                setMargins(0, 0, 0, 24)
            }
            setBackgroundColor(visualColor)
        }
        layout.addView(colorPreview)

        val currentValues = intArrayOf(Color.red(visualColor), Color.green(visualColor), Color.blue(visualColor))
        val components = arrayOf("Red", "Green", "Blue")

        components.forEachIndexed { index, name ->
            layout.addView(TextView(this).apply { text = name })
            val slider = SeekBar(this).apply {
                max = 255
                progress = currentValues[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        currentValues[index] = p
                        val pickedColor = Color.rgb(currentValues[0], currentValues[1], currentValues[2])
                        colorPreview.setBackgroundColor(pickedColor)
                        
                        // Intelligent inversion: store color that will look like pickedColor on screen
                        val colorToStore = if (whiteboardViewModel.invertColors.value == true) invertColor(pickedColor) else pickedColor
                        whiteboardViewModel.setBackgroundColor(colorToStore)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(slider)
        }

        AlertDialog.Builder(this)
            .setTitle("Background Color")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
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
                addView(Button(this@MainActivity).apply {
                    text = "🔄 Sync"
                    setOnClickListener {
                        val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                        val driveScope = Scope(DriveScopes.DRIVE_FILE)
                        if (account == null || !GoogleSignIn.hasPermissions(account, driveScope)) {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(driveScope)
                                .build()
                            val client = GoogleSignIn.getClient(this@MainActivity, gso)
                            googleSignInLauncher.launch(client.signInIntent)
                        } else {
                            googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings")) {
                                runOnUiThread {
                                    currentDialog?.dismiss()
                                    showDocumentsDialog()
                                    Toast.makeText(this@MainActivity, "Sync complete", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
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
            selectAll()
            requestFocus()
        }

        val container = FrameLayout(this).apply {
            setPadding(48, 16, 48, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename $cleanName")
            .setView(container)
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
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
