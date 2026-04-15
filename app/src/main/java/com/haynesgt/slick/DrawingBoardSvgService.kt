package com.haynesgt.slick

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder
import kotlin.math.max
import kotlin.math.min

data class Stroke(
    val id: String?,
    val points: List<Vector2D>
)

class DrawingBoardSvgService(private val context: Context) {

    private val folderName = "drawings"
    private val archiveFolderName = "archived"

    companion object {
        private val fileLock = Any()
    }

    fun listSvgFiles(): List<String> {
        val folder = File(context.filesDir, folderName)
        if (!folder.exists()) return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.extension == "svg" }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun listArchivedFiles(): List<String> {
        val folder = File(context.filesDir, archiveFolderName)
        if (!folder.exists()) return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.extension == "svg" }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun archiveFile(fileName: String): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, folderName)
        val archiveFolder = File(context.filesDir, archiveFolderName)
        if (!archiveFolder.exists()) archiveFolder.mkdirs()

        val file = File(folder, fileName)
        val archiveFile = File(archiveFolder, fileName)
        return if (file.exists()) {
            file.renameTo(archiveFile)
        } else {
            false
        }
    }

    fun restoreFile(fileName: String): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, folderName)
        val archiveFolder = File(context.filesDir, archiveFolderName)
        if (!folder.exists()) folder.mkdirs()

        val file = File(folder, fileName)
        val archiveFile = File(archiveFolder, fileName)
        return if (archiveFile.exists()) {
            archiveFile.renameTo(file)
        } else {
            false
        }
    }

    fun deleteFile(fileName: String, fromArchive: Boolean = false): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, if (fromArchive) archiveFolderName else folderName)
        val file = File(folder, fileName)
        return if (file.exists()) file.delete() else false
    }

    fun renameFile(oldName: String, newName: String, inArchive: Boolean = false): Boolean = synchronized(fileLock) {
        val folder = File(context.filesDir, if (inArchive) archiveFolderName else folderName)
        val oldFile = File(folder, oldName)
        val newFile = File(folder, if (newName.endsWith(".svg")) newName else "$newName.svg")
        return if (oldFile.exists() && !newFile.exists()) {
            oldFile.renameTo(newFile)
        } else {
            false
        }
    }

    fun importFile(uri: android.net.Uri): String? = synchronized(fileLock) {
        val folder = File(context.filesDir, folderName)
        if (!folder.exists()) folder.mkdirs()

        val fileName = getFileNameFromUri(uri) ?: "imported_${System.currentTimeMillis()}.svg"
        val targetFile = File(folder, fileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                targetFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return fileName
        } catch (e: Exception) {
            Log.e("Slick", "Error importing file from $uri", e)
            return null
        }
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String? {
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

    fun loadStrokesFromFile(fileName: String, fromArchive: Boolean = false): Pair<List<Stroke>, ViewPort> = synchronized(fileLock) {
        val folder = File(context.filesDir, if (fromArchive) archiveFolderName else folderName)
        val file = File(folder, fileName)

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
                            
                            // Simple SVG path parsing (M, L, Q)
                            val pattern = """([MLQ])([^MLQ]*)""".toRegex()
                            pattern.findAll(d).forEach { matchResult ->
                                val command = matchResult.groupValues[1]
                                val params = matchResult.groupValues[2].trim()
                                    .split("""[\s,]+""".toRegex())
                                    .filter { it.isNotEmpty() }
                                
                                if (params.size >= 2) {
                                    // For M, L, Q, the last two numbers are the end point
                                    val x = params[params.size - 2].toFloatOrNull() ?: 0f
                                    val y = params[params.size - 1].toFloatOrNull() ?: 0f
                                    points.add(Vector2D(x, y))
                                }
                            }
                            
                            val id = parser.getAttributeValue(null, "id")
                            strokes.add(Stroke(id, points))
                        }
                    }
                    eventType = parser.next()
                }
                Pair(strokes, viewPort)
            }
        } catch (e: Exception) {
            Log.e("Slick", "Error parsing SVG file $fileName", e)
            try {
                val content = file.readText()
                Log.e("Slick", "Full file content of $fileName (size ${file.length()}):\n$content")
            } catch (readEx: Exception) {
                Log.e("Slick", "Could not read file content for $fileName", readEx)
            }
            return Pair(emptyList(), ViewPort(1f, 0f, 0f))
        }
    }

    fun saveStrokesToFile(fileName: String, strokes: List<Stroke>, viewPort: ViewPort) = synchronized(fileLock) {
        val folder = File(context.filesDir, folderName)
        if (!folder.exists()) folder.mkdirs()
        
        val targetFile = File(folder, fileName)
        val tempFile = File(folder, "$fileName.tmp")

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

                        writer.write("  <path fill=\"none\" stroke=\"black\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" d=\"$d\" />\n")
                    }
                    writer.write("</svg>")
                }
            }
            
            // Atomic swap
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    Log.e("Slick", "Failed to rename $tempFile to $targetFile")
                }
            }
        } catch (e: Exception) {
            Log.e("Slick", "Error saving strokes to $fileName", e)
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
