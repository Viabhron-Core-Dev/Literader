package com.example.data

import android.content.Context
import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {
    suspend fun parseEpub(context: Context, bookId: Int, epubFilePath: String): Int = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(context.filesDir, "book_$bookId")
            if (!bookDir.exists()) {
                bookDir.mkdirs()
            }

            ZipFile(epubFilePath).use { zip ->
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isValidating = false
                    isNamespaceAware = false
                    setFeature("http://xml.org/sax/features/namespaces", false)
                    setFeature("http://xml.org/sax/features/validation", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }

                val containerEntry = zip.getEntry("META-INF/container.xml") ?: throw Exception("META-INF/container.xml missing")
                val containerDoc = factory.newDocumentBuilder().parse(zip.getInputStream(containerEntry))
                val rootFiles = containerDoc.getElementsByTagName("rootfile")
                if (rootFiles.length == 0) throw Exception("No rootfile in container.xml")
                val opfPath = (rootFiles.item(0) as Element).getAttribute("full-path")

                val opfEntry = zip.getEntry(opfPath) ?: throw Exception("OPF file $opfPath not found")
                val opfDoc = factory.newDocumentBuilder().parse(zip.getInputStream(opfEntry))

                val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

                val manifestMap = mutableMapOf<String, String>()
                val manifestItems = opfDoc.getElementsByTagName("item")
                for (i in 0 until manifestItems.length) {
                    val item = manifestItems.item(i) as Element
                    val id = item.getAttribute("id")
                    val href = item.getAttribute("href")
                    manifestMap[id] = URLDecoder.decode(href, "UTF-8")
                }

                val spineItems = opfDoc.getElementsByTagName("itemref")
                val chapterFiles = mutableListOf<String>()
                for (i in 0 until spineItems.length) {
                    val itemref = spineItems.item(i) as Element
                    val idref = itemref.getAttribute("idref")
                    val href = manifestMap[idref]
                    if (href != null) {
                        chapterFiles.add(href)
                    }
                }

                var chapterCount = 0
                for ((index, chapterHref) in chapterFiles.withIndex()) {
                    val chapterZipPath = opfDir + chapterHref
                    val chapterEntry = zip.getEntry(chapterZipPath)
                    
                    val textContent = if (chapterEntry != null) {
                        val rawHtml = zip.getInputStream(chapterEntry).bufferedReader().readText()
                        val bodyStart = rawHtml.indexOf("<body", ignoreCase = true)
                        val bodyStr = if (bodyStart != -1) rawHtml.substring(bodyStart) else rawHtml
                        
                        bodyStr.replace(Regex("<p.*?>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("</p>|<br\\s*/?>|</div>", RegexOption.IGNORE_CASE), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&nbsp;", " ")
                            .replace(Regex("(?m)^[ \\t]*\\r?\\n"), "") // remove empty lines
                            .replace(Regex("\\n{3,}"), "\n\n") // max two newlines
                            .trim()
                    } else {
                        "Chapter content missing: $chapterHref"
                    }
                    
                    val chapterFile = File(bookDir, "chapter_$index.txt")
                    chapterFile.writeText(textContent)
                    chapterCount++
                }

                Log.d("EpubParser", "Parsed $epubFilePath into $chapterCount chapters.")
                return@withContext chapterCount
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext -1
        }
    }
}
