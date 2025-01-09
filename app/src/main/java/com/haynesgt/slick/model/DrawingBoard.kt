package com.haynesgt.slick.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class DrawingBoard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val backgroundColor: Int,
    val createdAt: Long = System.currentTimeMillis()
)
