package com.haynesgt.slick

import androidx.core.text.isDigitsOnly
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

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
                        if (command == "M" || command == "L") {
                            val x = parameters[0].toFloat()
                            val y = parameters[1].toFloat()
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

            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\">\n")
            for (stroke in strokes) {
                val points = stroke.points
                writer.write("  <path style=\"fill:none;stroke:black;stroke-width:0.2\" d=\"M${points[0].x},${points[0].y}")
                for (point in points) {
                    writer.write(" L${point.x},${point.y}")
                }
                writer.write("\" />\n")
            }
            writer.write("</svg>")
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
