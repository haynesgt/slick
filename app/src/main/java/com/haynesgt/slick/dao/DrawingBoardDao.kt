package com.haynesgt.slick.dao

import androidx.room.Dao
import androidx.room.Insert
import com.haynesgt.slick.model.DrawingBoard

@Dao
interface DrawingDao {
    @Insert
    suspend fun insertBoard(board: DrawingBoard): Long

    @Insert
    suspend fun getBoard(id: Int): DrawingBoard

    @Insert
    suspend fun getBoards(): List<DrawingBoard>
}
