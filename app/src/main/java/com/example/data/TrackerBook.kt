package com.example.data

import androidx.room.*

@Entity(tableName = "tracker_books")
data class TrackerBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String = "",
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
    val isFinished: Boolean = false,
    val isWebNovel: Boolean = false,
    val genres: String = "",
    val rating: Float = 0f,
    val comment: String = "",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM tracker_books ORDER BY lastUpdatedTimestamp DESC")
    suspend fun getAllBooks(): List<TrackerBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: TrackerBook): Long

    @Update
    suspend fun updateBook(book: TrackerBook)

    @Delete
    suspend fun deleteBook(book: TrackerBook)
    
    @Query("SELECT * FROM tracker_books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): TrackerBook?
}
