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
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
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
            lifecycleScope.launch {
                googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings"))
                Toast.makeText(this@MainActivity, "Downloaded missing files from Drive", Toast.LENGTH_SHORT).show()
                if (currentDialog?.isShowing == true) {
                    currentDialog?.dismiss()
                    showDocumentsDialog()
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
        syncJob = lifecycleScope.launch(Dispatchers.Default) {
            delay(2000) // Wait for 2 seconds of inactivity
            val fileName = whiteboardViewModel.fileName.value ?: return@launch
            val strokes = whiteboardViewModel.strokes.value?.toList() ?: emptyList()
            val viewPort = whiteboardViewModel.viewPort.value ?: ViewPort(1f, 0f, 0f)

            withContext(Dispatchers.IO) {
                drawingBoardSvgService.saveStrokesToFile(fileName, strokes, viewPort)
            }
            
            // Versioning: save a version to Drive
            val versionFileName = "${fileName.removeSuffix(".svg")}_${System.currentTimeMillis()}.svg"
            val tempVersionFile = File(cacheDir, versionFileName)
            
            try {
                withContext(Dispatchers.IO) {
                    drawingBoardSvgService.saveStrokesToFile(tempVersionFile, strokes, viewPort)
                }

                val file = File(filesDir, "drawings/$fileName")
                if (file.exists()) {
                    googleDriveSyncService.syncFile(file)
                }

                if (tempVersionFile.exists()) {
                    googleDriveSyncService.syncFile(
                        tempVersionFile,
                        folderName = "SlickHistory/${fileName.removeSuffix(".svg")}"
                    )
                }
            } catch (e: CancellationException) {
                // do nothing
            } catch (e: Exception) {
                Log.e("Slick", "Error in saveAndSyncDebounced", e)
            } finally {
                if (tempVersionFile.exists()) tempVersionFile.delete()
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

        lifecycleScope.launch {
            googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings"))
            Toast.makeText(this@MainActivity, "Downloaded missing files from Drive", Toast.LENGTH_SHORT).show()
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
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        whiteboardViewModel.setInvertColors(sharedPreferences.getBoolean("invert_colors", isDarkMode))
        whiteboardViewModel.setControlsLocked(sharedPreferences.getBoolean("controls_locked", true))
        whiteboardViewModel.setShowGrid(sharedPreferences.getBoolean("show_grid", false))
        whiteboardViewModel.setShowGridHorizontal(sharedPreferences.getBoolean("show_grid_horizontal", true))
        whiteboardViewModel.setShowGridVertical(sharedPreferences.getBoolean("show_grid_vertical", false))
        whiteboardViewModel.setGridSpacingX(sharedPreferences.getFloat("grid_spacing_x", 50f))
        whiteboardViewModel.setGridSpacingY(sharedPreferences.getFloat("grid_spacing_y", 50f))
        whiteboardViewModel.setGridOffsetX(sharedPreferences.getFloat("grid_offset_x", 0f))
        whiteboardViewModel.setGridOffsetY(sharedPreferences.getFloat("grid_offset_y", 0f))
        whiteboardViewModel.setGridColor(sharedPreferences.getInt("grid_color", Color.LTGRAY))
        whiteboardViewModel.setGridThickness(sharedPreferences.getFloat("grid_thickness", 1f))
        whiteboardViewModel.setBackgroundColor(sharedPreferences.getInt("background_color", Color.WHITE))
        whiteboardViewModel.setPenSize(sharedPreferences.getFloat("pen_size", 2f))
        whiteboardViewModel.setEraserSize(sharedPreferences.getFloat("eraser_size", 20f))
        whiteboardViewModel.setUsePressure(sharedPreferences.getBoolean("use_pressure", false))
        whiteboardViewModel.setUseStylusOnly(sharedPreferences.getBoolean("use_stylus_only", false))

        whiteboardViewModel.singleFingerPanEnabled.observe(this) { enabled ->
            sharedPreferences.edit { putBoolean("single_finger_pan", enabled) }
        }
        whiteboardViewModel.invertColors.observe(this) { inverted ->
            sharedPreferences.edit { putBoolean("invert_colors", inverted) }
        }
        whiteboardViewModel.controlsLocked.observe(this) { locked ->
            sharedPreferences.edit { putBoolean("controls_locked", locked) }
        }
        whiteboardViewModel.showGrid.observe(this) { show ->
            sharedPreferences.edit { putBoolean("show_grid", show) }
        }
        whiteboardViewModel.showGridHorizontal.observe(this) { show ->
            sharedPreferences.edit { putBoolean("show_grid_horizontal", show) }
        }
        whiteboardViewModel.showGridVertical.observe(this) { show ->
            sharedPreferences.edit { putBoolean("show_grid_vertical", show) }
        }
        whiteboardViewModel.gridSpacingX.observe(this) { spacing ->
            sharedPreferences.edit { putFloat("grid_spacing_x", spacing) }
        }
        whiteboardViewModel.gridSpacingY.observe(this) { spacing ->
            sharedPreferences.edit { putFloat("grid_spacing_y", spacing) }
        }
        whiteboardViewModel.gridOffsetX.observe(this) { offset ->
            sharedPreferences.edit { putFloat("grid_offset_x", offset) }
        }
        whiteboardViewModel.gridOffsetY.observe(this) { offset ->
            sharedPreferences.edit { putFloat("grid_offset_y", offset) }
        }
        whiteboardViewModel.gridColor.observe(this) { color ->
            sharedPreferences.edit { putInt("grid_color", color) }
        }
        whiteboardViewModel.gridThickness.observe(this) { thickness ->
            sharedPreferences.edit { putFloat("grid_thickness", thickness) }
        }
        whiteboardViewModel.backgroundColor.observe(this) { color ->
            sharedPreferences.edit { putInt("background_color", color) }
        }
        whiteboardViewModel.penSize.observe(this) { size ->
            sharedPreferences.edit { putFloat("pen_size", size) }
        }
        whiteboardViewModel.eraserSize.observe(this) { size ->
            sharedPreferences.edit { putFloat("eraser_size", size) }
        }
        whiteboardViewModel.usePressure.observe(this) { use ->
            sharedPreferences.edit { putBoolean("use_pressure", use) }
        }
        whiteboardViewModel.useStylusOnly.observe(this) { use ->
            sharedPreferences.edit { putBoolean("use_stylus_only", use) }
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
        whiteboardView.onDoubleFingerTap = {
            whiteboardViewModel.undo()
        }
        whiteboardView.onThreeFingerTap = {
            whiteboardViewModel.redo()
        }
        whiteboardView.onSwipeFromEdge = {
            whiteboardViewModel.setControlsVisibility(true)
        }
        whiteboardView.onPenDown = { _ ->
            whiteboardViewModel.setCurrentPenPoint(null)
        }
        whiteboardView.onPenMove = { point ->
            whiteboardViewModel.setCurrentPenPoint(point)
        }
        whiteboardView.onPenUp = { _ ->
            whiteboardViewModel.setCurrentPenPoint(null)
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
                val stylusOnlyItem = popup.menu.add("Stylus Only Mode").apply {
                    isCheckable = true
                    isChecked = whiteboardViewModel.useStylusOnly.value ?: false
                }
                val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                val syncItem = if (account != null) {
                    popup.menu.add("Change Account (${account.email})")
                } else {
                    popup.menu.add("Sign in to Google Drive")
                }
                val gridSettingsItem = popup.menu.add("Grid Settings...")
                val bgSettingsItem = popup.menu.add("Background Color...")
                val exportPdfItem = popup.menu.add("Export as PDF...")

                popup.setOnMenuItemClickListener { item ->
                    when (item) {
                        panItem -> whiteboardViewModel.setSingleFingerPanEnabled(!item.isChecked)
                        invertItem -> whiteboardViewModel.setInvertColors(!item.isChecked)
                        lockItem -> whiteboardViewModel.setControlsLocked(!item.isChecked)
                        stylusOnlyItem -> whiteboardViewModel.setUseStylusOnly(!item.isChecked)
                        gridSettingsItem -> showGridSettingsDialog()
                        bgSettingsItem -> showBackgroundSettingsDialog()
                        exportPdfItem -> showExportPdfDialog(whiteboardView)
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

        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val penBtn = Button(this@MainActivity).apply {
                text = "Pen"
                setOnClickListener { whiteboardViewModel.setCurrentTool(Tool.PEN) }
            }
            val highlighterBtn = Button(this@MainActivity).apply {
                text = "Highlighter"
                setOnClickListener { whiteboardViewModel.setCurrentTool(Tool.HIGHLIGHTER) }
            }
            val eraserBtn = Button(this@MainActivity).apply {
                text = "Eraser"
                setOnClickListener { whiteboardViewModel.setCurrentTool(Tool.ERASER) }
            }
            val undoBtn = Button(this@MainActivity).apply {
                text = "Undo"
                setOnClickListener { whiteboardViewModel.undo() }
            }
            val redoBtn = Button(this@MainActivity).apply {
                text = "Redo"
                setOnClickListener { whiteboardViewModel.redo() }
            }
            addView(penBtn)
            addView(highlighterBtn)
            addView(eraserBtn)
            addView(undoBtn)
            addView(redoBtn)

            whiteboardViewModel.currentTool.observe(this@MainActivity) { tool ->
                penBtn.setTypeface(null, if (tool == Tool.PEN) Typeface.BOLD else Typeface.NORMAL)
                highlighterBtn.setTypeface(null, if (tool == Tool.HIGHLIGHTER) Typeface.BOLD else Typeface.NORMAL)
                eraserBtn.setTypeface(null, if (tool == Tool.ERASER) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        val toolOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE

            val eraserOptions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val modeGroup = RadioGroup(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL

                    EraserMode.entries.forEach { mode ->
                        val rb = RadioButton(this@MainActivity).apply {
                            text = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                            setTextColor(Color.WHITE)
                            id = View.generateViewId()
                            tag = mode
                            // Use a ColorStateList to handle checked state color in dark mode
                            val states = arrayOf(
                                intArrayOf(-android.R.attr.state_checked),
                                intArrayOf(android.R.attr.state_checked)
                            )
                            val colors = intArrayOf(Color.GRAY, Color.WHITE)
                            buttonTintList = ColorStateList(states, colors)
                        }
                        addView(rb)
                    }

                    setOnCheckedChangeListener { group, checkedId ->
                        val rb = group.findViewById<RadioButton>(checkedId)
                        val mode = rb?.tag as? EraserMode
                        if (mode != null) {
                            whiteboardViewModel.setEraserMode(mode)
                        }
                    }
                }

                whiteboardViewModel.eraserMode.observe(this@MainActivity) { mode ->
                    for (i in 0 until modeGroup.childCount) {
                        val rb = modeGroup.getChildAt(i) as RadioButton
                        if (rb.tag == mode) {
                            rb.isChecked = true
                            break
                        }
                    }
                }
                addView(modeGroup)
            }
            addView(eraserOptions)

            val sizeContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)

                val sizeLabel = TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE)
                    setPadding(16, 0, 16, 0)
                    minWidth = 200
                }

                val sizeSlider = SeekBar(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT)
                    max = 1000
                    
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                            if (!f) return
                            val tool = whiteboardViewModel.currentTool.value ?: Tool.PEN
                            val isEraser = tool == Tool.ERASER
                            val minVal = if (isEraser) 1f else 0.1f
                            val maxVal = if (isEraser) 500f else 100f
                            // Exponential: min * (max/min)^(p/max_p)
                            val size = minVal * Math.pow((maxVal / minVal).toDouble(), (p / 1000.0)).toFloat()
                            when (tool) {
                                Tool.ERASER -> whiteboardViewModel.setEraserSize(size)
                                Tool.HIGHLIGHTER -> whiteboardViewModel.setHighlighterSize(size)
                                Tool.PEN -> whiteboardViewModel.setPenSize(size)
                            }
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }

                val pressureSwitch = CheckBox(this@MainActivity).apply {
                    text = "Pressure"
                    setTextColor(Color.WHITE)
                    setOnCheckedChangeListener { _, isChecked ->
                        whiteboardViewModel.setUsePressure(isChecked)
                    }
                }

                fun updateSlider(tool: Tool?, size: Float) {
                    val isEraser = tool == Tool.ERASER
                    val minVal = if (isEraser) 1f else 0.1f
                    val maxVal = if (isEraser) 500f else 100f
                    val progress = (1000 * Math.log((size / minVal).toDouble()) / Math.log((maxVal / minVal).toDouble())).toInt()
                    sizeSlider.progress = progress
                    sizeLabel.text = "${tool?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Pen"} Size: %.1f".format(size)
                }

                whiteboardViewModel.currentTool.observe(this@MainActivity) { tool ->
                    eraserOptions.visibility = if (tool == Tool.ERASER) View.VISIBLE else View.GONE
                    pressureSwitch.visibility = if (tool == Tool.ERASER) View.GONE else View.VISIBLE
                    val size = when (tool) {
                        Tool.ERASER -> whiteboardViewModel.eraserSize.value ?: 20f
                        Tool.HIGHLIGHTER -> whiteboardViewModel.highlighterSize.value ?: 10f
                        else -> whiteboardViewModel.penSize.value ?: 2f
                    }
                    updateSlider(tool, size)
                }
                whiteboardViewModel.eraserSize.observe(this@MainActivity) { size ->
                    if (whiteboardViewModel.currentTool.value == Tool.ERASER) updateSlider(Tool.ERASER, size)
                }
                whiteboardViewModel.penSize.observe(this@MainActivity) { size ->
                    if (whiteboardViewModel.currentTool.value == Tool.PEN) updateSlider(Tool.PEN, size)
                }
                whiteboardViewModel.highlighterSize.observe(this@MainActivity) { size ->
                    if (whiteboardViewModel.currentTool.value == Tool.HIGHLIGHTER) updateSlider(Tool.HIGHLIGHTER, size)
                }
                whiteboardViewModel.usePressure.observe(this@MainActivity) { use ->
                    pressureSwitch.isChecked = use
                }

                addView(sizeLabel)
                addView(sizeSlider)
                addView(pressureSwitch)
            }
            addView(sizeContainer)
        }

        whiteboardViewModel.currentTool.observe(this) {
            toolOptionsRow.visibility = View.VISIBLE
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
            val bg = GradientDrawable().apply {
                setColor(Color.argb(150, 0, 0, 0))
                cornerRadius = 16f
            }
            background = bg
            setPadding(24, 16, 24, 16)

            addView(fileNameTextView)
            addView(buttonRow)
            addView(toolRow)
            addView(toolOptionsRow)
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

        layout.addView(TextView(this).apply { text = "\nGrid Thickness" })
        val thicknessSlider = SeekBar(this).apply {
            max = 20
            progress = (whiteboardViewModel.gridThickness.value ?: 1f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    val value = if (p < 1) 1f else p.toFloat()
                    whiteboardViewModel.setGridThickness(value)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        layout.addView(thicknessSlider)

        layout.addView(TextView(this).apply { text = "\nGrid Color" })
        val isInverted = whiteboardViewModel.invertColors.value ?: false
        val currentGridColor = whiteboardViewModel.gridColor.value ?: Color.LTGRAY
        val visualGridColor = if (isInverted) invertColor(currentGridColor) else currentGridColor
        
        val gridHsl = FloatArray(3)
        ColorUtils.colorToHSL(visualGridColor, gridHsl)

        val gridColorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100).apply {
                setMargins(0, 16, 0, 16)
            }
            setBackgroundColor(visualGridColor)
        }
        layout.addView(gridColorPreview)

        fun updateGridUI() {
            val pickedColor = ColorUtils.HSLToColor(gridHsl)
            gridColorPreview.setBackgroundColor(pickedColor)
            val colorToStore = if (whiteboardViewModel.invertColors.value == true) invertColor(pickedColor) else pickedColor
            whiteboardViewModel.setGridColor(colorToStore)
        }

        val (gridHueSlider, gridHueGradient) = createColorSlider(360, gridHsl[0].toInt()) { p ->
            gridHsl[0] = p.toFloat()
            updateGridUI()
        }
        layout.addView(TextView(this).apply { text = "Hue" })
        layout.addView(gridHueSlider.parent as View)
        
        val (gridSatSlider, gridSatGradient) = createColorSlider(100, (gridHsl[1] * 100).toInt()) { p ->
            gridHsl[1] = p / 100f
            updateGridUI()
        }
        layout.addView(TextView(this).apply { text = "Saturation" })
        layout.addView(gridSatSlider.parent as View)

        val (gridLightSlider, gridLightGradient) = createColorSlider(100, (gridHsl[2] * 100).toInt()) { p ->
            gridHsl[2] = p / 100f
            updateGridUI()
        }
        layout.addView(TextView(this).apply { text = "Lightness" })
        layout.addView(gridLightSlider.parent as View)

        // Initial gradient setup for grid sliders
        gridHueGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        ))
        
        val updateGradients = {
            val hStart = floatArrayOf(gridHsl[0], 0f, gridHsl[2])
            val hEnd = floatArrayOf(gridHsl[0], 1f, gridHsl[2])
            gridSatGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                ColorUtils.HSLToColor(hStart), ColorUtils.HSLToColor(hEnd)
            ))
            val lMid = floatArrayOf(gridHsl[0], gridHsl[1], 0.5f)
            gridLightGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.BLACK, ColorUtils.HSLToColor(lMid), Color.WHITE
            ))
        }
        
        // Add listeners to update gradients too
        gridHueSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                gridHsl[0] = p.toFloat()
                updateGridUI()
                updateGradients()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        gridSatSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                gridHsl[1] = p / 100f
                updateGridUI()
                updateGradients()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        gridLightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                gridHsl[2] = p / 100f
                updateGridUI()
                updateGradients()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        updateGradients()

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
        val currentColor = whiteboardViewModel.backgroundColor.value ?: Color.WHITE
        val visualColor = if (isInverted) invertColor(currentColor) else currentColor
        
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(visualColor, hsl)

        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).apply {
                setMargins(0, 0, 0, 24)
            }
            setBackgroundColor(visualColor)
        }
        layout.addView(colorPreview)

        val (hueSlider, hueGradient) = createColorSlider(360, hsl[0].toInt()) { p -> }
        val (satSlider, satGradient) = createColorSlider(100, (hsl[1] * 100).toInt()) { p -> }
        val (lightSlider, lightGradient) = createColorSlider(100, (hsl[2] * 100).toInt()) { p -> }

        fun updateUI() {
            val pickedColor = ColorUtils.HSLToColor(hsl)
            colorPreview.setBackgroundColor(pickedColor)
            
            // Hue gradient (static rainbow)
            hueGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            ))

            // Saturation gradient (gray to full color at current L)
            val hslStart = floatArrayOf(hsl[0], 0f, hsl[2])
            val hslEnd = floatArrayOf(hsl[0], 1f, hsl[2])
            satGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                ColorUtils.HSLToColor(hslStart), ColorUtils.HSLToColor(hslEnd)
            ))

            // Lightness gradient (black to color to white)
            val hslMid = floatArrayOf(hsl[0], hsl[1], 0.5f)
            lightGradient.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.BLACK, ColorUtils.HSLToColor(hslMid), Color.WHITE
            ))

            val colorToStore = if (whiteboardViewModel.invertColors.value == true) invertColor(pickedColor) else pickedColor
            whiteboardViewModel.setBackgroundColor(colorToStore)
        }

        hueSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                hsl[0] = p.toFloat()
                updateUI()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        satSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                hsl[1] = p / 100f
                updateUI()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        lightSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                hsl[2] = p / 100f
                updateUI()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        layout.addView(TextView(this).apply { text = "Hue" })
        layout.addView(hueSlider.parent as View)
        layout.addView(TextView(this).apply { text = "Saturation" })
        layout.addView(satSlider.parent as View)
        layout.addView(TextView(this).apply { text = "Lightness" })
        layout.addView(lightSlider.parent as View)

        updateUI()

        AlertDialog.Builder(this)
            .setTitle("Background Color")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun createColorSlider(max: Int, initialProgress: Int, update: (Int) -> Unit): Pair<SeekBar, View> {
        val container = FrameLayout(this)
        val gradientView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val seekBar = SeekBar(this).apply {
            this.max = max
            this.progress = initialProgress
            // Make the seekbar track transparent so we see the gradient underneath
            progressDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    update(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(gradientView)
        container.addView(seekBar)
        return Pair(seekBar, gradientView)
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
                            lifecycleScope.launch {
                                googleDriveSyncService.downloadMissingFiles(File(filesDir, "drawings"))
                                currentDialog?.dismiss()
                                showDocumentsDialog()
                                Toast.makeText(this@MainActivity, "Sync complete", Toast.LENGTH_SHORT).show()
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
                addView(Button(this@MainActivity).apply {
                    text = "📜"
                    layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        showHistoryDialog(fileName)
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
        // ...
    }

    private fun showHistoryDialog(fileName: String) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(dialogView)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
        }

        val loadingText = TextView(this).apply { text = "Loading history from Drive..." }
        dialogView.addView(loadingText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("History: ${fileName.removeSuffix(".svg")}")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            val historyFiles = googleDriveSyncService.listFilesInFolder("SlickHistory/${fileName.removeSuffix(".svg")}")
            runOnUiThread {
                dialogView.removeView(loadingText)
                if (historyFiles.isEmpty()) {
                    dialogView.addView(TextView(this@MainActivity).apply { text = "No history found on Drive." })
                }
                historyFiles.sortedByDescending { it.name }.forEach { driveFile ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 8, 0, 8)

                        val timestamp = try {
                            val ts = driveFile.name.substringAfterLast("_").substringBefore(".svg").toLong()
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))
                        } catch (e: Exception) {
                            driveFile.name
                        }

                        val nameText = TextView(this@MainActivity).apply {
                            text = timestamp
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            textSize = 16f
                        }
                        addView(nameText)

                        addView(Button(this@MainActivity).apply {
                            text = "Restore"
                            setOnClickListener {
                                dialog.dismiss()
                                restoreFromHistory(fileName, driveFile.id, driveFile.name)
                            }
                        })
                    }
                    dialogView.addView(row)
                }
            }
        }
    }

    private fun restoreFromHistory(originalFileName: String, driveFileId: String, driveFileName: String) {
        // ...
    }

    private fun showExportPdfDialog(whiteboardView: WhiteboardSurfaceView) {
        val options = arrayOf("Current View", "Whole Board")
        AlertDialog.Builder(this)
            .setTitle("Export as PDF")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToPdf(whiteboardView, exportWholeBoard = false)
                    1 -> exportToPdf(whiteboardView, exportWholeBoard = true)
                }
            }
            .show()
    }

    private fun exportToPdf(whiteboardView: WhiteboardSurfaceView, exportWholeBoard: Boolean) {
        val fileName = whiteboardViewModel.fileName.value?.removeSuffix(".svg") ?: "export"
        val pdfFile = File(getExternalFilesDir(null), "$fileName.pdf")
        
        val pdfDocument = PdfDocument()
        
        val strokes = whiteboardViewModel.strokes.value ?: emptyList()
        val backgroundColor = whiteboardViewModel.backgroundColor.value ?: Color.WHITE
        
        val rect = if (exportWholeBoard && strokes.isNotEmpty()) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            strokes.forEach { stroke ->
                stroke.points.forEach { p ->
                    minX = minOf(minX, p.x)
                    minY = minOf(minY, p.y)
                    maxX = maxOf(maxX, p.x)
                    maxY = maxOf(maxY, p.y)
                }
            }
            // Add some padding
            val padding = 50f
            RectF(minX - padding, minY - padding, maxX + padding, maxY + padding)
        } else {
            // Use current view bounds
            val viewPort = whiteboardViewModel.viewPort.value ?: ViewPort(1f, 0f, 0f)
            val left = -viewPort.offsetX / viewPort.scale
            val top = -viewPort.offsetY / viewPort.scale
            val right = left + whiteboardView.width / viewPort.scale
            val bottom = top + whiteboardView.height / viewPort.scale
            RectF(left, top, right, bottom)
        }

        val pageInfo = PdfDocument.PageInfo.Builder(rect.width().toInt(), rect.height().toInt(), 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // Draw background
        canvas.drawColor(backgroundColor)
        
        // Translate to match our bounds
        canvas.translate(-rect.left, -rect.top)
        
        // Draw strokes (similar logic to WhiteboardSurfaceView but direct to canvas)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            if (stroke.isHighlighter) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            } else {
                paint.xfermode = null
            }

            if (stroke.pressures != null && stroke.pressures.size == stroke.points.size) {
                for (i in 0 until stroke.points.size - 1) {
                    val p1 = stroke.points[i]
                    val p2 = stroke.points[i+1]
                    val pr1 = stroke.pressures[i]
                    val pr2 = stroke.pressures[i+1]
                    paint.strokeWidth = stroke.width * (pr1 + pr2) / 2f
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                }
            } else {
                val path = Path()
                if (stroke.points.isNotEmpty()) {
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }

        pdfDocument.finishPage(page)

        try {
            pdfFile.outputStream().use { 
                pdfDocument.writeTo(it)
            }
            Toast.makeText(this, "PDF saved to ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()
            
            // Trigger media scan or share
            val sendThingsService = SendThingsService()
            sendThingsService.sendData(pdfFile, this, this)
            
            // Sync to Google Drive
            lifecycleScope.launch(Dispatchers.IO) {
                googleDriveSyncService.syncFile(pdfFile, "SlickPDFs")
            }
            
        } catch (e: Exception) {
            Log.e("Slick", "Error exporting PDF", e)
            Toast.makeText(this, "Failed to export PDF", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
        }
    }
}
