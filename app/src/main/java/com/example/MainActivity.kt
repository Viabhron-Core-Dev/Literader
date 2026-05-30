package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.data.LibraryRepository
import com.example.service.FloatingReaderService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val multiPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val repository = LibraryRepository(this)
        lifecycleScope.launch {
            for (uri in uris) {
                repository.importBook(uri)
            }
            // re-launch service to library
            val intent = Intent(this@MainActivity, FloatingReaderService::class.java).apply {
                putExtra("OPEN_FROM_LAUNCHER", true)
            }
            androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, intent)
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val permIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(permIntent)
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            val repository = LibraryRepository(this)
            lifecycleScope.launch {
                val book = repository.importBook(uri)
                if (book != null) {
                    val svcIntent = Intent(this@MainActivity, FloatingReaderService::class.java).apply {
                        putExtra("BOOK_ID", book.id)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to import book", Toast.LENGTH_SHORT).show()
                }
                finishAndRemoveTask()
            }
            return
        }

        if (intent.getBooleanExtra("PICK_EPUB", false)) {
            multiPicker.launch("application/epub+zip")
            return
        }

        val serviceIntent = Intent(this, FloatingReaderService::class.java).apply {
            putExtra("OPEN_FROM_LAUNCHER", true)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        finishAndRemoveTask()
    }
}
