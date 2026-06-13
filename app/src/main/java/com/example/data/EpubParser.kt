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
                var opfPath = ""
                val containerEntry = zip.getEntry("META-INF/container.xml")
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).bufferedReader().readText()
                    val match = Regex("full-path=\"([^\"]+)\"").find(containerXml)
                    if (match != null) {
                        opfPath = match.groupValues[1]
                    }
                }
                
                val chapterHrefs = mutableListOf<String>()
                if (opfPath.isNotEmpty()) {
                    val opfEntry = zip.getEntry(opfPath)
                    if (opfEntry != null) {
                        val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()
                        
                        // Parse manifest
                        val manifestMap = mutableMapOf<String, String>()
                        val itemRegex = Regex("<item\\s+([^>]+)>")
                        itemRegex.findAll(opfContent).forEach { match ->
                            val attrs = match.groupValues[1]
                            val idMatch = Regex("id=\"([^\"]+)\"").find(attrs)
                            val hrefMatch = Regex("href=\"([^\"]+)\"").find(attrs)
                            if (idMatch != null && hrefMatch != null) {
                                manifestMap[idMatch.groupValues[1]] = hrefMatch.groupValues[1]
                            }
                        }

                        // Parse spine
                        val spineMatch = Regex("<spine.*?</spine>", RegexOption.DOT_MATCHES_ALL).find(opfContent)
                        if (spineMatch != null) {
                            val itemrefRegex = Regex("<itemref[^>]+idref=\"([^\"]+)\"")
                            itemrefRegex.findAll(spineMatch.value).forEach { match ->
                                val idref = match.groupValues[1]
                                val href = manifestMap[idref]
                                if (href != null) {
                                    chapterHrefs.add(href)
                                }
                            }
                        }
                    }
                }

                val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

                val validHrefs = chapterHrefs
                    .map { java.net.URLDecoder.decode(it, "UTF-8") }
                    .filter { it.endsWith(".html", true) || it.endsWith(".xhtml", true) || it.endsWith(".htm", true) }

                val entries = if (validHrefs.isNotEmpty()) {
                    validHrefs.mapNotNull { href -> 
                        val fullPath = opfDir + href
                        zip.getEntry(fullPath) ?: zip.getEntry(href) ?: zip.entries().asSequence().find { it.name.endsWith(href) }
                    }
                } else {
                    zip.entries().asSequence()
                        .filter { it.name.endsWith(".html", true) || it.name.endsWith(".xhtml", true) || it.name.endsWith(".htm", true) }
                        .sortedBy { it.name }
                        .toList()
                }
                
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
            com.example.LogKeeper.writeLog("EpubParser", android.util.Log.getStackTraceString(e))
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
            com.example.LogKeeper.writeLog("EpubParserTxt", android.util.Log.getStackTraceString(e))
            e.printStackTrace()
            return@withContext -1
        }
    }
}
