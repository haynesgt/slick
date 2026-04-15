package com.haynesgt.slick.model

import android.graphics.Point
import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TypeConverters {
    @TypeConverter
    fun fromPointList(value: List<Point>): String {
        val list = value.map { listOf(it.x, it.y) }
        return Json.encodeToString(list)
    }

    @TypeConverter
    fun toPointList(value: String): List<Point> {
        val list = Json.decodeFromString<List<List<Int>>>(value)
        return list.map { Point(it[0], it[1]) }
    }
}
