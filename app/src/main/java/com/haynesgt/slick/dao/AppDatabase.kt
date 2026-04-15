package com.haynesgt.slick.dao

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.haynesgt.slick.model.DrawingBoard
import com.haynesgt.slick.model.DrawingStroke

@Database(entities = [DrawingBoard::class, DrawingStroke::class], version = 1)
@TypeConverters(com.haynesgt.slick.model.TypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun drawingDao(): DrawingDao
}
