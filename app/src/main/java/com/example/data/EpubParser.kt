package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object EpubParser {
    suspend fun parseEpubToText(context: Context, bookId: Int, epubFile: File): Int = withContext(Dispatchers.IO) {
        try {
            var chapterCount = 0
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            ZipFile(epubFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { it.name.endsWith(".html", true) || it.name.endsWith(".xhtml", true) || it.name.endsWith(".htm", true) }
                    .sortedBy { it.name }
                    .toList()
                
                for ((index, entry) in entries.withIndex()) {
                    val rawHtml = zip.getInputStream(entry).bufferedReader().readText()
                    val bodyStart = rawHtml.indexOf("<body", ignoreCase = true)
                    val bodyStr = if (bodyStart != -1) rawHtml.substring(bodyStart) else rawHtml
                    
                    val textContent = bodyStr
                        .replace(Regex("<p.*?>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("</p>|<br\\s*/?>|</div>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&nbsp;", " ")
                        .replace(Regex("(?m)^[ \\t]*\\r?\\n"), "") // remove empty lines
                        .replace(Regex("\\n{3,}"), "\n\n") // max two newlines
                        .trim()

                    if (textContent.isNotBlank()) {
                        val chapterFile = File(bookDir, "chapter_$chapterCount.txt")
                        chapterFile.writeText(textContent)
                        chapterCount++
                    }
                }
            }
            return@withContext chapterCount
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext -1
        }
    }

    suspend fun parseTxtToText(context: Context, bookId: Int, txtFile: File): Int = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) bookDir.mkdirs()

            var chapterCount = 0
            val sb = java.lang.StringBuilder()
            
            txtFile.forEachLine { line ->
                sb.append(line).append("\n")
                if (sb.length > 15000) {
                    val chapterFile = File(bookDir, "chapter_$chapterCount.txt")
                    chapterFile.writeText(sb.toString().trim())
                    chapterCount++
                    sb.clear()
                }
            }
            if (sb.isNotEmpty() || chapterCount == 0) {
                val chapterFile = File(bookDir, "chapter_$chapterCount.txt")
                chapterFile.writeText(if (sb.isEmpty()) "Empty book." else sb.toString().trim())
                chapterCount++
            }
            return@withContext chapterCount
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext -1
        }
    }
}
