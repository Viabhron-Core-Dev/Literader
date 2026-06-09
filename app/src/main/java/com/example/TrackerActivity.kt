package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.TrackerBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                TrackerScreen(
                    onBack = { finish() },
                    db = AppDatabase.getDatabase(this)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(onBack: () -> Unit, db: AppDatabase) {
    val coroutineScope = rememberCoroutineScope()
    var books by remember { mutableStateOf(emptyList<TrackerBook>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedBook by remember { mutableStateOf<TrackerBook?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var filterGenre by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        books = withContext(Dispatchers.IO) { db.trackerDao().getAllBooks() }
    }

    val filteredBooks = books.filter {
        (searchQuery.isEmpty() || it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true)) &&
        (filterGenre.isEmpty() || it.genres.contains(filterGenre, ignoreCase = true))
    }

    val reloadBooks: () -> Unit = {
        coroutineScope.launch {
            books = withContext(Dispatchers.IO) { db.trackerDao().getAllBooks() }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val moonImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Scanning Moon+ backup (.mrstd)...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    var updated = 0
                    val existingTrackerBooks = db.trackerDao().getAllBooks()
                    
                    val tempFile = java.io.File(context.cacheDir, "moon_import_tmp")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(tempFile).use { it.write(input.readBytes()) }
                    }

                    // Attempt to parse as Zip (mrstd is usually a flattened zip of .po files and .db)
                    val extractDir = java.io.File(context.cacheDir, "moon_extracted")
                    extractDir.deleteRecursively()
                    extractDir.mkdirs()
                    
                    var isZip = false
                    try {
                        java.util.zip.ZipInputStream(java.io.FileInputStream(tempFile)).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                isZip = true
                                val outFile = java.io.File(extractDir, entry.name.replace("/", "_"))
                                java.io.FileOutputStream(outFile).use { zis.copyTo(it) }
                                entry = zis.nextEntry
                            }
                        }
                    } catch (e: Exception) { }

                    val filesToScan = if (isZip) extractDir.listFiles()?.toList() ?: emptyList() else listOf(tempFile)
                    
                    for (file in filesToScan) {
                        try {
                            // Attempt SQLite read (Moon+ usually has a books.db or moonreader.db inside)
                            val moonDb = android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                            // Look for 'books' table
                            val cursor = moonDb.rawQuery("SELECT * FROM sqlite_master WHERE type='table' AND name LIKE '%book%'", null)
                            var tableName: String? = null
                            if (cursor.moveToFirst()) {
                                tableName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                            }
                            cursor.close()
                            
                            if (tableName != null) {
                                val booksCursor = moonDb.rawQuery("SELECT * FROM $tableName", null)
                                val cols = booksCursor.columnNames
                                val titleIdx = cols.indexOfFirst { it.equals("title", true) || it.equals("name", true) || it.contains("book", true) }
                                val authorIdx = cols.indexOfFirst { it.equals("author", true) }
                                val totalIdx = cols.indexOfFirst { it.equals("total", true) || it.equals("total_chapters", true) || it.equals("chapters", true) }
                                val readIdx = cols.indexOfFirst { it.equals("last_chapter", true) || it.equals("current_chapter", true) || it.equals("read", true) || it.equals("progress", true) || it.equals("chapter", true) }
                                
                                while (booksCursor.moveToNext()) {
                                    if (titleIdx != -1) {
                                        val title = booksCursor.getString(titleIdx) ?: continue
                                        val author = if (authorIdx != -1) booksCursor.getString(authorIdx) else "Unknown"
                                        val totalCh = if (totalIdx != -1) booksCursor.getInt(totalIdx) else 0
                                        val readCh = if (readIdx != -1) booksCursor.getInt(readIdx) else 0
                                        
                                        val existing = existingTrackerBooks.find { it.title.equals(title, true) }
                                        if (existing != null) {
                                            db.trackerDao().insertBook(existing.copy(
                                                totalChapters = if (totalCh > existing.totalChapters) totalCh else existing.totalChapters,
                                                readChapters = if (readCh > existing.readChapters) readCh else existing.readChapters,
                                                lastUpdatedTimestamp = System.currentTimeMillis()
                                            ))
                                            updated++
                                        } else {
                                            db.trackerDao().insertBook(TrackerBook(
                                                title = title, author = author ?: "Unknown",
                                                readChapters = readCh, totalChapters = totalCh,
                                                genres = "Moon+ Backup", addedTimestamp = System.currentTimeMillis(),
                                                lastUpdatedTimestamp = System.currentTimeMillis()
                                            ))
                                            updated++
                                        }
                                    }
                                }
                                booksCursor.close()
                            }
                            moonDb.close()
                        } catch (e: Exception) {
                            // Not a typical SQLite DB, maybe a .po file?
                            if (file.name.endsWith(".po") || file.name.endsWith(".txt") || file.name.endsWith(".mrstd")) {
                                val content = file.bufferedReader(Charsets.UTF_8).use { reader ->
                                    val buffer = CharArray(2000)
                                    val count = reader.read(buffer)
                                    if (count > 0) String(buffer, 0, count) else ""
                                } // read head
                                
                                var title = Regex("(?i)title[=:]\\s*([^\\n\\r]+)").find(content)?.groupValues?.get(1)?.trim()
                                if (title == null) {
                                    title = file.name.replace(Regex("(?i)\\.(po|mrstd|txt|epub|pdf|mobi)$"), "").replace(Regex("(?i)\\.(po|mrstd|txt|epub|pdf|mobi)$"), "").trim()
                                }
                                
                                val readChMatch = Regex("(?i)(?:current_chapter|last_chapter|chapter|read)[=:]\\s*(\\d+)").find(content)
                                val totChMatch = Regex("(?i)(?:total_chapters|total)[=:]\\s*(\\d+)").find(content)
                                val readCh = readChMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                val totCh = totChMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                
                                if (title.isNotEmpty()) {
                                    val existing = existingTrackerBooks.find { it.title.equals(title, true) || title.contains(it.title, true) }
                                    if (existing != null) {
                                        db.trackerDao().insertBook(existing.copy(
                                            totalChapters = if (totCh > existing.totalChapters) totCh else existing.totalChapters,
                                            readChapters = if (readCh > existing.readChapters) readCh else existing.readChapters,
                                            lastUpdatedTimestamp = System.currentTimeMillis()
                                        ))
                                        updated++
                                    } else {
                                        db.trackerDao().insertBook(TrackerBook(
                                            title = title, author = "Unknown", readChapters = readCh, totalChapters = totCh,
                                            genres = "Moon+ Backup", addedTimestamp = System.currentTimeMillis(),
                                            lastUpdatedTimestamp = System.currentTimeMillis()
                                        ))
                                        updated++
                                    }
                                }
                            }
                        }
                    }
                    
                    if (updated == 0) {
                        try {
                            // Fallback 2: Read it as a plain CSV or TSV (like our own backups) if it's not a ZIP
                            val content = tempFile.readText(Charsets.UTF_8)
                            val lines = content.lines()
                            for (line in lines.drop(1)) { // skip header
                                val parts = line.split('\t')
                                if (parts.size >= 4) {
                                    db.trackerDao().insertBook(TrackerBook(
                                        title = parts[0], author = parts[1],
                                        totalChapters = parts[2].toIntOrNull() ?: 0,
                                        readChapters = parts[3].toIntOrNull() ?: 0,
                                        isFinished = if (parts.size > 4) parts[4].toBoolean() else false,
                                        isWebNovel = if (parts.size > 5) parts[5].toBoolean() else false,
                                        genres = if (parts.size > 6) parts[6] else "",
                                        rating = if (parts.size > 7) parts[7].toFloatOrNull() ?: 0f else 0f,
                                        addedTimestamp = System.currentTimeMillis(),
                                        lastUpdatedTimestamp = System.currentTimeMillis()
                                    ))
                                    updated++
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    
                    reloadBooks()
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Moon+ Backup parsed! Imported/Updated $updated items.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Failed to parse backup", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading Tracker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        moonImportLauncher.launch(arrayOf("*/*"))
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Import Moon+ Backup")
                    }
                    IconButton(onClick = { 
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val allTrackerBooks = db.trackerDao().getAllBooks()
                                val sb = StringBuilder()
                                sb.append("title\tauthor\ttotalChapters\treadChapters\tisFinished\tisWebNovel\tgenres\trating\tcomment\n")
                                for (b in allTrackerBooks) {
                                    sb.append("${b.title}\t${b.author}\t${b.totalChapters}\t${b.readChapters}\t${b.isFinished}\t${b.isWebNovel}\t${b.genres}\t${b.rating}\t${b.comment}\n")
                                }
                                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                if (downloadsDir != null && downloadsDir.exists()) {
                                    val backupFile = java.io.File(downloadsDir, "LiteReader_TrackerBackup_$timestamp.tsv")
                                    java.io.FileWriter(backupFile).use { it.write(sb.toString()) }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Tracker backed up to Downloads", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Backup to Downloads")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Book")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by title or author") },
                singleLine = true
            )
            
            OutlinedTextField(
                value = filterGenre,
                onValueChange = { filterGenre = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
                placeholder = { Text("Filter by tag/genre") },
                singleLine = true
            )

            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(filteredBooks) { book ->
                    TrackerBookItem(
                        book = book,
                        onClick = {
                            selectedBook = book
                            showAddDialog = true
                        },
                        onDelete = {
                            coroutineScope.launch(Dispatchers.IO) {
                                db.trackerDao().deleteBook(book)
                                reloadBooks()
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        TrackerBookDialog(
            book = selectedBook,
            onDismiss = {
                showAddDialog = false
                selectedBook = null
            },
            onSave = { updatedBook ->
                coroutineScope.launch(Dispatchers.IO) {
                    db.trackerDao().insertBook(updatedBook)
                    reloadBooks()
                }
                showAddDialog = false
                selectedBook = null
            }
        )
    }
}

@Composable
fun TrackerBookItem(book: TrackerBook, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            if (book.author.isNotEmpty()) {
                Text("by ${book.author}", style = MaterialTheme.typography.bodyMedium)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val readStatus = if (book.isFinished) {
                "Finished"
            } else {
                "Chap ${book.readChapters} / ${if (book.totalChapters > 0) book.totalChapters.toString() else "?"}"
            }
            Text(readStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            
            if (book.genres.isNotEmpty()) {
                Text("Tags: ${book.genres}", style = MaterialTheme.typography.bodySmall)
            }
            
            if (book.rating > 0) {
                Text("Rating: ${book.rating} ⭐", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerBookDialog(book: TrackerBook?, onDismiss: () -> Unit, onSave: (TrackerBook) -> Unit) {
    var title by remember { mutableStateOf(book?.title ?: "") }
    var author by remember { mutableStateOf(book?.author ?: "") }
    var readChapters by remember { mutableStateOf(book?.readChapters?.toString() ?: "0") }
    var totalChapters by remember { mutableStateOf(book?.totalChapters?.toString() ?: "0") }
    var genres by remember { mutableStateOf(book?.genres ?: "") }
    var comment by remember { mutableStateOf(book?.comment ?: "") }
    var isFinished by remember { mutableStateOf(book?.isFinished ?: false) }
    var isWebNovel by remember { mutableStateOf(book?.isWebNovel ?: false) }
    var rating by remember { mutableStateOf(book?.rating?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (book == null) "Add Book" else "Edit Book") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Author") }, singleLine = true)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = readChapters, onValueChange = { readChapters = it }, label = { Text("Read Chap") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = totalChapters, onValueChange = { totalChapters = it }, label = { Text("Total Chap") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = genres, onValueChange = { genres = it }, label = { Text("Genre Tags (comma seq)") }, singleLine = true)
                OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("Comment") })
                OutlinedTextField(value = rating, onValueChange = { rating = it }, label = { Text("Rating (1-5)") }, singleLine = true)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isFinished, onCheckedChange = { isFinished = it })
                    Text("Finished")
                    Spacer(modifier = Modifier.width(16.dp))
                    Checkbox(checked = isWebNovel, onCheckedChange = { isWebNovel = it })
                    Text("Web Novel")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = TrackerBook(
                    id = book?.id ?: 0,
                    title = title,
                    author = author,
                    readChapters = readChapters.toIntOrNull() ?: 0,
                    totalChapters = totalChapters.toIntOrNull() ?: 0,
                    genres = genres,
                    comment = comment,
                    isFinished = isFinished,
                    isWebNovel = isWebNovel,
                    rating = rating.toFloatOrNull() ?: 0f,
                    addedTimestamp = book?.addedTimestamp ?: System.currentTimeMillis(),
                    lastUpdatedTimestamp = System.currentTimeMillis()
                )
                onSave(updated)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
