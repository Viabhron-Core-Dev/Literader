package com.example.data

import androidx.room.*

@Entity(tableName = "epubs")
data class EpubBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val filePath: String,
    val isParsed: Boolean = false,
    val totalChapters: Int = 0,
    val lastReadChapter: Int = 0,
    val lastReadScrollY: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface EpubDao {
    @Query("SELECT * FROM epubs ORDER BY lastOpenedTimestamp DESC")
    suspend fun getAllBooks(): List<EpubBook>

    @Query("SELECT * FROM epubs ORDER BY addedTimestamp DESC")
    suspend fun getAllBooksByAddedDesc(): List<EpubBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: EpubBook): Long

    @Update
    suspend fun updateBook(book: EpubBook)

    @Query("SELECT * FROM epubs WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): EpubBook?
    
    @Delete
    suspend fun deleteBook(book: EpubBook)
}
