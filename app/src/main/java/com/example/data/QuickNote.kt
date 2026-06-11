package com.example.data

import androidx.room.*

@Entity(tableName = "quick_notes")
data class QuickNote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface QuickNoteDao {
    @Query("SELECT * FROM quick_notes ORDER BY timestamp DESC")
    fun getAllNotes(): kotlinx.coroutines.flow.Flow<List<QuickNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: QuickNote): Long

    @Update
    suspend fun updateNote(note: QuickNote)

    @Delete
    suspend fun deleteNote(note: QuickNote)

    @Delete
    suspend fun deleteNotes(notes: List<QuickNote>)
}
