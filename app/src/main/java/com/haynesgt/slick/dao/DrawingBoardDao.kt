package com.haynesgt.slick.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.haynesgt.slick.model.DrawingBoard

@Dao
interface DrawingDao {
    @Insert
    fun insertBoard(board: DrawingBoard): Long

    @Query("SELECT * FROM DrawingBoard WHERE id = :id")
    fun getBoard(id: Int): DrawingBoard

    @Query("SELECT * FROM DrawingBoard")
    fun getBoards(): List<DrawingBoard>
}
