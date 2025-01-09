package com.haynesgt.slick

import android.view.SurfaceView
import java.io.File

import com.caverock.androidsvg.SVG;

class DrawingBoardSvgService {

    fun listSvgFiles(): List<String> {
        val directory = File("drawings")
        return directory.listFiles()
            ?.filter { it.isFile && it.extension == "svg" }
            ?.map { it.name }
            ?: emptyList()
    }

    fun loadSvg(filename: String): String {
        val file = File(filename)
        return file.readText()
    }

    fun parseSvg(svg: String): SVG {

    }
}