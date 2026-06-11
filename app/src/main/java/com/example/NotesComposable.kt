package com.example

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AppDatabase
import com.example.data.QuickNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(db: AppDatabase, onClose: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var notes by remember { mutableStateOf(emptyList<QuickNote>()) }
    var selectedNotes by remember { mutableStateOf(setOf<QuickNote>()) }
    var showEditDialog by remember { mutableStateOf<QuickNote?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        db.quickNoteDao().getAllNotes().collect {
            notes = it
        }
    }

    val isSelectionMode = selectedNotes.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isSelectionMode) Text("${selectedNotes.size} Selected") 
                    else Text("Quick Notes") 
                },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (isSelectionMode) selectedNotes = emptySet()
                        else onClose() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back or Clear Selection")
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        if (selectedNotes.size == 1) {
                            IconButton(onClick = { 
                                showEditDialog = selectedNotes.first()
                                selectedNotes = emptySet()
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                        }
                        IconButton(onClick = { 
                            val toDelete = selectedNotes.toList()
                            coroutineScope.launch(Dispatchers.IO) {
                                db.quickNoteDao().deleteNotes(toDelete)
                            }
                            selectedNotes = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Note")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notes, key = { it.id }) { note ->
                val isSelected = selectedNotes.contains(note)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (isSelectionMode) {
                                    selectedNotes = if (isSelected) selectedNotes - note else selectedNotes + note
                                } else {
                                    showEditDialog = note
                                }
                            },
                            onLongClick = {
                                selectedNotes = if (isSelected) selectedNotes - note else selectedNotes + note
                            }
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (note.title.isNotEmpty()) {
                            Text(note.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NoteDialog(
            note = null,
            onDismiss = { showAddDialog = false },
            onSave = { title, text ->
                coroutineScope.launch(Dispatchers.IO) {
                    db.quickNoteDao().insertNote(QuickNote(title = title, text = text))
                }
                showAddDialog = false
            }
        )
    }

    if (showEditDialog != null) {
        NoteDialog(
            note = showEditDialog,
            onDismiss = { showEditDialog = null },
            onSave = { title, text ->
                coroutineScope.launch(Dispatchers.IO) {
                    db.quickNoteDao().updateNote(showEditDialog!!.copy(title = title, text = text))
                }
                showEditDialog = null
            }
        )
    }
}

@Composable
fun NoteDialog(note: QuickNote?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var text by remember { mutableStateOf(note?.text ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "New Note" else "Edit Note") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 250.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank() || title.isNotBlank()) onSave(title, text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
