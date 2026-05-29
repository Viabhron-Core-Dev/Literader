package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.data.AppDatabase
import com.example.data.LibraryRepository
import com.example.ui.LibraryScreen
import com.example.ui.LibraryViewModel
import com.example.ui.LibraryViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val database by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { LibraryRepository(database.epubDao(), this) }
    
    private val viewModel: LibraryViewModel by viewModels {
        LibraryViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // The LibraryScreen manages its own Scaffold padding internally
                LibraryScreen(viewModel = viewModel)
            }
        }
    }
}
