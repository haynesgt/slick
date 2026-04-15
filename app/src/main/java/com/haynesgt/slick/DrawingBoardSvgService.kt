package com.haynesgt.slick

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.max
import kotlin.math.min

class DrawingBoardSvgService(private val context: Context) {

    private val folderName = "drawings"
    private val archiveFolderName = "archive"

    companion object {
        private val fileLock = Any()
    }

    fun listSvgFiles(): List<String> {
        val folder = File(context.filesDir, folderName)
        if (!folder.exists()) return emptyList()
        return folder.listFiles { file -> file.extension == "svg" }
            ?.map { it.name }
            ?.sortedDescending() ?: emptyList()
    }

    fun listArchivedFiles(): List<String> {
        val folder = File(context.filesDir, archiveFolderName)
        if (!folder.exists()) return emptyList()
        return folder.listFiles { file -> file.extension == "svg" }
            ?.map { it.name }
            ?.sortedDescending() ?: emptyList()
    }

    fun archiveFile(fileName: String): Boolean = synchronized(fileLock) {
        val srcFolder = File(context.filesDir, folderName)
        val destFolder = File(context.filesDir, archiveFolderName)
        if (!destFolder.exists()) destFolder.mkdirs()

        val srcFile = File(srcFolder, fileName)
        val destFile = File(destFolder, fileName)

        if (srcFile.exists()) {
            return srcFile.renameTo(destFile)
        }
        return false
    }

    fun restoreFile(fileName: String): Boolean = synchronized(fileLock) {
        val srcFolder = File(context.filesDir, archiveFolderName)
        val destFolder = File(context.filesDir, folderName)
        if (!destFolder.exists()) destFolder.mkdirs()

        val srcFile = File(srcFolder, fileName)
        val destFile = File(destFolder, fileName)

        if (srcFile.exists()) {
            return srcFile.renameTo(destFile)
        }
        return false
    }

    fun deleteFile(fileName: String, fromArchive: Boolean): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, if (fromArchive) archiveFolderName else folderName)
        val file = File(folder, fileName)
        return file.delete()
    }

    fun renameFile(oldName: String, newName: String, inArchive: Boolean): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, if (inArchive) archiveFolderName else folderName)
        val srcFile = File(folder, oldName)
        val finalNewName = if (newName.endsWith(".svg")) newName else "$newName.svg"
        val destFile = File(folder, finalNewName)

        if (srcFile.exists() && !destFile.exists()) {
            return srcFile.renameTo(destFile)
        }
        return false
    }

    fun importFile(uri: Uri): String? = synchronized(fileLock) {
        try {
            val contentResolver = context.contentResolver
            val originalName = getFileNameFromUri(uri) ?: "imported_${System.currentTimeMillis()}.svg"
            val uniqueName = getUniqueFileName(originalName)
            val folder = File(context.filesDir, folderName)
            if (!folder.exists()) folder.mkdirs()
            val destFile = File(folder, uniqueName)

            contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return uniqueName
        } catch (e: Exception) {
            Log.e("Slick", "Error importing file", e)
            return null
        }
    }

    private fun getUniqueFileName(baseName: String): String {
        val folder = File(context.filesDir, folderName)
        var name = baseName
        var count = 1
        while (File(folder, name).exists()) {
            val extension = baseName.substringAfterLast(".", "svg")
            val nameWithoutExtension = baseName.substringBeforeLast(".")
            name = "$nameWithoutExtension ($count).$extension"
            count++
        }
        return name
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    fun loadStrokesFromFile(file: File): Pair<List<Stroke>, ViewPort> = synchronized(fileLock) {
        if (!file.exists()) {
            return Pair(emptyList(), ViewPort(1f, 0f, 0f))
        }

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()

            return file.inputStream().use { inputStream ->
                parser.setInput(inputStream, "UTF-8")

                val strokes = mutableListOf<Stroke>()
                var viewPort = ViewPort(1f, 0f, 0f)

                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        if ("g" == tagName) {
                            val scale = parser.getAttributeValue("http://slick.app", "scale")?.toFloatOrNull() ?: 1f
                            val offsetX = parser.getAttributeValue("http://slick.app", "offsetX")?.toFloatOrNull() ?: 0f
                            val offsetY = parser.getAttributeValue("http://slick.app", "offsetY")?.toFloatOrNull() ?: 0f
                            viewPort = ViewPort(scale, offsetX, offsetY)
                        } else if ("path" == tagName) {
                            val points = mutableListOf<Vector2D>()
                            val d = parser.getAttributeValue(null, "d") ?: ""
                            val strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 2f
                            
                            val pattern = """([MLQ])([^MLQ]*)""".toRegex()
                            pattern.findAll(d).forEach { matchResult ->
                                val params = matchResult.groupValues[2].trim()
                                    .split("""[\s,]+""".toRegex())
                                    .filter { it.isNotEmpty() }
                                
                                if (params.size >= 2) {
                                    val x = params[params.size - 2].toFloatOrNull() ?: 0f
                                    val y = params[params.size - 1].toFloatOrNull() ?: 0f
                                    points.add(Vector2D(x, y))
                                }
                            }
                            
                            val id = parser.getAttributeValue(null, "id")
                            val colorStr = parser.getAttributeValue(null, "stroke") ?: "#000000"
                            val color = try { android.graphics.Color.parseColor(colorStr) } catch(e: Exception) { android.graphics.Color.BLACK }
                            val isHighlighter = parser.getAttributeValue(null, "slick:isHighlighter") == "true"
                            
                            val pressuresStr = parser.getAttributeValue(null, "slick:pressures")
                            val pressures = pressuresStr?.split(",")?.mapNotNull { it.toFloatOrNull() }

                            strokes.add(Stroke(id, points, strokeWidth, color, pressures, isHighlighter))
                        }
                    }
                    eventType = parser.next()
                }
                Pair(strokes, viewPort)
            }
        } catch (e: Exception) {
            Log.e("Slick", "Error parsing SVG file ${file.name}", e)
            return Pair(emptyList(), ViewPort(1f, 0f, 0f))
        }
    }

    fun loadStrokesFromFile(fileName: String, fromArchive: Boolean = false): Pair<List<Stroke>, ViewPort> {
        val folder = File(context.filesDir, if (fromArchive) archiveFolderName else folderName)
        return loadStrokesFromFile(File(folder, fileName))
    }

    fun saveStrokesToFile(file: File, strokes: List<Stroke>, viewPort: ViewPort) = synchronized(fileLock) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        
        val tempFile = File(file.parent, "${file.name}.tmp")

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.bufferedWriter().use { writer ->
                    var minX = Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE
                    var hasPoints = false

                    for (stroke in strokes) {
                        for (point in stroke.points) {
                            minX = min(minX, point.x)
                            minY = min(minY, point.y)
                            maxX = max(maxX, point.x)
                            maxY = max(maxY, point.y)
                            hasPoints = true
                        }
                    }

                    val padding = 96f
                    val width: Float
                    val height: Float
                    val viewBox: String

                    if (!hasPoints) {
                        width = 100f
                        height = 100f
                        viewBox = "0 0 100 100"
                        minX = 0f
                        minY = 0f
                    } else {
                        val drawingWidth = maxX - minX
                        val drawingHeight = maxY - minY
                        width = drawingWidth + (2 * padding)
                        height = drawingHeight + (2 * padding)
                        viewBox = "${minX - padding} ${minY - padding} $width $height"
                    }

                    writer.write("<svg width=\"$width\" height=\"$height\" viewBox=\"$viewBox\" preserveAspectRatio=\"xMidYMid meet\" " +
                            "xmlns=\"http://www.w3.org/2000/svg\" xmlns:slick=\"http://slick.app\">\n")
                    writer.write("  <!-- Slick ViewPort: scale=${viewPort.scale}, offsetX=${viewPort.offsetX}, offsetY=${viewPort.offsetY} -->\n")
                    writer.write("  <g slick:scale=\"${viewPort.scale}\" slick:offsetX=\"${viewPort.offsetX}\" slick:offsetY=\"${viewPort.offsetY}\" />\n")

                    for (stroke in strokes) {
                        val points = stroke.points
                        if (points.size < 2) continue

                        val d = StringBuilder()
                        d.append("M${points[0].x},${points[0].y}")

                        for (i in 1 until points.size) {
                            val p1 = points[i - 1]
                            val p2 = points[i]
                            val midX = (p1.x + p2.x) / 2
                            val midY = (p1.y + p2.y) / 2

                            if (i == 1) {
                                d.append(" L$midX,$midY")
                            } else {
                                d.append(" Q${p1.x},${p1.y} $midX,$midY")
                            }
                        }
                        d.append(" L${points.last().x},${points.last().y}")

                        val colorStr = String.format("#%06X", (0xFFFFFF and stroke.color))
                        val isHighlighterAttr = if (stroke.isHighlighter) " slick:isHighlighter=\"true\"" else ""
                        val pressuresAttr = if (stroke.pressures != null) " slick:pressures=\"${stroke.pressures.joinToString(",")}\"" else ""

                        writer.write("  <path fill=\"none\" stroke=\"$colorStr\" stroke-width=\"${stroke.width}\" stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"$d\"$isHighlighterAttr$pressuresAttr />\n")
                    }
                    writer.write("</svg>")
                }
            }
            
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                if (!tempFile.renameTo(file)) {
                    Log.e("Slick", "Failed to rename $tempFile to $file")
                }
            }
        } catch (e: Exception) {
            Log.e("Slick", "Error saving strokes to ${file.name}", e)
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun saveStrokesToFile(fileName: String, strokes: List<Stroke>, viewPort: ViewPort) {
        val folder = File(context.filesDir, folderName)
        saveStrokesToFile(File(folder, fileName), strokes, viewPort)
    }
}
