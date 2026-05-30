package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.AppDatabase
import com.example.data.EpubBook
import com.example.data.LibraryRepository
import com.example.service.FloatingReaderService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = LibraryRepository(this)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibraryScreen(repository) { bookId ->
                        openFloatingReader(bookId)
                    }
                }
            }
        }
    }

    private fun openFloatingReader(bookId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, FloatingReaderService::class.java).apply {
            putExtra("BOOK_ID", bookId)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 150) // Delay main activity exit so floating reader takes over cleanly
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(repository: LibraryRepository, onOpenBook: (Int) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var books by remember { mutableStateOf(emptyList<EpubBook>()) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Recent", "Folders")

    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        coroutineScope.launch {
            for (uri in uris) {
                repository.importBook(uri)
            }
            books = repository.getBooks()
        }
    }

    LaunchedEffect(Unit) {
        books = repository.getBooks()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("LiteReader Library") })
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (books.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { onOpenBook(books.first().id) },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Continue") },
                        text = { Text("Continue") }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                FloatingActionButton(onClick = { multiPicker.launch("application/epub+zip") }) {
                    Icon(Icons.Default.Add, "Import EPUBs")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        text = { Text(title) },
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index }
                    )
                }
            }

            if (selectedTabIndex == 0) {
                LibraryList(
                    books = books, 
                    onOpen = onOpenBook,
                    onDelete = {
                        coroutineScope.launch {
                            repository.deleteBook(it)
                            books = repository.getBooks()
                        }
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Folders View - Coming Soon (Lightweight)")
                }
            }
        }
    }
}

@Composable
fun LibraryList(books: List<EpubBook>, onOpen: (Int) -> Unit, onDelete: (EpubBook) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(books) { book ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { onOpen(book.id) },
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        val status = if (book.isParsed) "Parsed • Ch ${book.lastReadChapter + 1}/${book.totalChapters}" else "Parsing/Pending..."
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(book) }) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            }
        }
    }
}
