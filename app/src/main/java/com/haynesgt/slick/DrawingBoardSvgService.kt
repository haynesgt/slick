package com.haynesgt.slick

import androidx.core.text.isDigitsOnly
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

data class Stroke (
    val id: String?,
    // val style: StrokeStyle,
    val points: MutableList<Vector2D>)

class DrawingBoardSvgService {

    private val folderName = "drawings"

    fun listSvgFiles(): List<String> {
        val directory = File(folderName)
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "svg" }
            ?.map { it.name }
            ?: emptyList()
    }

    fun loadStrokesFromFile(fileName: String): List<List<Vector2D>> {
        val file = File(folderName, fileName)
        // use xml pull parser
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()

        val inputStream = file.inputStream()
        parser.setInput(inputStream, "UTF-8")

        val strokes = mutableListOf<List<Vector2D>>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                if ("path" == tagName) {
                    val points = mutableListOf<Vector2D>()
                    val d = parser.getAttributeValue(null, "d")
                    // parse the path. only parse M followed by L command
                    val path = d.split(" ").flatMap({ it.split(",") }).filter({ it.isNotBlank() })
                    val pathDequeue = ArrayDeque(path)
                    var command = ""
                    while (pathDequeue.isNotEmpty()) {
                        val next = pathDequeue.removeFirst()
                        if (next.isDigitsOnly()) {
                            pathDequeue.addFirst(next)
                        } else {
                            command = next
                        }
                        if (command == "M") {
                            if (points.isNotEmpty()) throw Exception("First point should not be M")
                            val x = pathDequeue.removeFirst().toFloat()
                            assert(pathDequeue.removeFirst() == ",")
                            val y = pathDequeue.removeFirst().toFloat()
                            points.add(Vector2D(x, y))
                        } else if (command == "L") {
                            val x = pathDequeue.removeFirst().toFloat()
                            assert(pathDequeue.removeFirst() == ",")
                            val y = pathDequeue.removeFirst().toFloat()
                            points.add(Vector2D(x, y))
                        } else {
                            throw Exception("Unknown command $command")
                        }
                    }
                    strokes.add(points)
                }
            }
        }
        return strokes
    }

    fun saveStrokesToFile(fileName: String, strokes: List<List<Vector2D>>) {
        val file = File(folderName, fileName)
        file.parentFile?.mkdirs()
        file.createNewFile()
        val writer = file.bufferedWriter()

        writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\">\n")
        for (stroke in strokes) {
            writer.write("  <path d=\"M${stroke[0].x},${stroke[0].y}")
            for (point in stroke) {
                writer.write(" L${point.x},${point.y}")
            }
            writer.write("\" />\n")
        }
        writer.write("</svg>")
        writer.close()
    }
}
