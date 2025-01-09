package com.haynesgt.slick.model

import android.graphics.Point
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "drawing_strokes",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = DrawingBoard::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class DrawingStroke(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val boardId: Int,
    val points: List<Point>,
    val color: Int,
    val strokeWidth: Float
)