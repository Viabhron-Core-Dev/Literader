package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LibraryRepository(private val epubDao: EpubDao, private val context: Context) {
    val allBooks: Flow<List<EpubBook>> = epubDao.getAllBooks()
    val recentBooks: Flow<List<EpubBook>> = epubDao.getRecentBooks()

    fun searchBooks(query: String): Flow<List<EpubBook>> = epubDao.searchBooks(query)

    suspend fun importEpub(uri: Uri): EpubBook? = withContext(Dispatchers.IO) {
        try {
            var title = "Unknown Book"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val fallbackTitle = cursor.getString(nameIndex)
                        title = fallbackTitle.replace(".epub", "", ignoreCase = true)
                    }
                }
            }
            
            val fileName = "book_${System.currentTimeMillis()}.epub"
            val file = File(context.filesDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            
            val book = EpubBook(
                title = title,
                filePath = file.absolutePath,
                isParsed = false
            )
            val id = epubDao.insertBook(book)
            val finalBook = book.copy(id = id.toInt())
            
            // Trigger parsing
            val totalChapters = EpubParser.parseEpub(context, finalBook.id, finalBook.filePath)
            if (totalChapters > 0) {
                epubDao.insertBook(finalBook.copy(totalChapters = totalChapters, isParsed = true))
            } else {
                Log.e("LibraryRepository", "Failed to parse EPUB into chapters")
            }
            finalBook
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun deleteBook(book: EpubBook) = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        if (file.exists()) {
            file.delete()
        }
        val bookDir = File(context.filesDir, "book_${book.id}")
        if (bookDir.exists()) {
            bookDir.deleteRecursively()
        }
        epubDao.deleteBook(book)
    }
}
