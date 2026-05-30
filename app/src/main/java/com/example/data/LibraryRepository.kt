package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LibraryRepository(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).epubDao()

    suspend fun getBooks() = dao.getAllBooks()

    suspend fun getBook(id: Int) = dao.getBookById(id)

    suspend fun updateBook(book: EpubBook) = dao.updateBook(book)
    
    suspend fun deleteBook(book: EpubBook) {
        dao.deleteBook(book)
        val bookDir = File(context.filesDir, "book_${book.id}")
        bookDir.deleteRecursively()
        val origFile = File(book.filePath)
        if (origFile.exists()) origFile.delete()
    }

    suspend fun importBook(uri: Uri): EpubBook? = withContext(Dispatchers.IO) {
        val title = getFileName(uri) ?: "Unknown Book"
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val destFile = File(context.filesDir, "imported_${System.currentTimeMillis()}.epub")
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output)
            }
            
            val book = EpubBook(title = title, filePath = destFile.absolutePath)
            val id = dao.insertBook(book).toInt()
            val finalBook = book.copy(id = id)
            
            // Trigger parsing
            val totalChapters = EpubParser.parseEpubToText(context, id, destFile)
            if (totalChapters > 0) {
                dao.updateBook(finalBook.copy(totalChapters = totalChapters, isParsed = true))
            }
            return@withContext finalBook
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = it.getString(idx)
            }
        }
        return name?.removeSuffix(".epub")
    }
}
