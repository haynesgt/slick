package com.haynesgt.slick

import androidx.core.text.isDigitsOnly
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class Stroke (
    val id: String?,
    // val style: StrokeStyle,
    val points: List<Vector2D>)

class DrawingBoardSvgService(private val mainActivity: MainActivity) {

    private val folderName = "drawings"

    fun listSvgFiles(): List<String> {
        val directory = File(folderName)
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "svg" }
            ?.map { it.name }
            ?: emptyList()
    }

    fun loadStrokesFromFile(fileName: String): List<Stroke> {
        val folder = File(mainActivity.filesDir, folderName)
        val file = File(folder, fileName)
        // use xml pull parser
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()

        val inputStream = file.inputStream()

        parser.setInput(inputStream, "UTF-8")

        val strokes = mutableListOf<Stroke>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                if ("path" == tagName) {
                    val points = mutableListOf<Vector2D>()
                    val d = parser.getAttributeValue(null, "d")
                    val pattern = """([MLHVCSQTAZ])([^MLHVCSQTAZ]*)""".toRegex()
                    val tokens = pattern.findAll(d).map { matchResult ->
                        val command = matchResult.groupValues[1]
                        val parameters = matchResult.groupValues[2].trim().split("\\s*,\\s*|\\s+".toRegex())
                        Pair(command, parameters)
                    }.toList()
                    for (token in tokens) {
                        val command = token.first
                        val parameters = token.second
                        if (command == "M" || command == "L" || command == "Q") {
                            // For Q, we take the last pair of parameters as the point. 
                            // This is a simplification but works for our specific Q command usage.
                            val x = parameters.takeLast(2)[0].toFloat()
                            val y = parameters.takeLast(2)[1].toFloat()
                            points.add(Vector2D(x, y))
                        } else {
                            // log warning
                            println("Unknown command: $command")
                        }
                    }
                    val id = parser.getAttributeValue(null, "id")
                    strokes.add(Stroke(id, points))
                }
            }
        }
        return strokes
    }

    fun saveStrokesToFile(fileName: String, strokes: List<Stroke>) {
        try {
            val folder = File(mainActivity.filesDir, folderName)
            val file = File(folder, fileName)
            folder.mkdirs()
            file.createNewFile()
            val writer = file.bufferedWriter()

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

            val padding = 96f // 1 inch in standard SVG pixels (96 DPI)
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

            writer.write("<svg width=\"$width\" height=\"$height\" viewBox=\"$viewBox\" preserveAspectRatio=\"xMidYMid meet\" xmlns=\"http://www.w3.org/2000/svg\">\n")
            
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
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
