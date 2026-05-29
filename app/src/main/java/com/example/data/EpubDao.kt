package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpubDao {
    @Query("SELECT * FROM epub_books ORDER BY addedTime DESC")
    fun getAllBooks(): Flow<List<EpubBook>>

    @Query("SELECT * FROM epub_books ORDER BY lastReadTime DESC LIMIT 5")
    fun getRecentBooks(): Flow<List<EpubBook>>

    @Query("SELECT * FROM epub_books WHERE title LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<EpubBook>>

    @Query("SELECT * FROM epub_books WHERE id = :id")
    suspend fun getBookById(id: Int): EpubBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: EpubBook): Long

    @Delete
    suspend fun deleteBook(book: EpubBook)
}
