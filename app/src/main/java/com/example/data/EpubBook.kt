package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epub_books")
data class EpubBook(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val filePath: String, // Internal storage path
    val addedTime: Long = System.currentTimeMillis(),
    val lastReadTime: Long = 0L,
    val lastReadChapter: Int = 0,
    val lastReadProgress: Int = 0,
    val totalChapters: Int = 0,
    val isParsed: Boolean = false
)
