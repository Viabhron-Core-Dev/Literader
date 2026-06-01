package com.example

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogKeeper.initialize(this)
    }
}

object LogKeeper {
    var isEnabled = true
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: android.content.Context? = null

    fun initialize(context: android.content.Context) {
        if (!isEnabled || defaultHandler != null) return
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            
            // Try to write to public Downloads directly (Allowed on Android 10+ for app-created files without permission)
            var logFile: File? = null
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null && downloadsDir.exists()) {
                logFile = File(downloadsDir, "LiteReader_CrashLog_$timestamp.txt")
            }
            
            // Fallback to app's external files dir
            if (logFile == null) {
                val fallbackDir = appContext?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (fallbackDir != null && fallbackDir.exists()) {
                    logFile = File(fallbackDir, "LiteReader_CrashLog_$timestamp.txt")
                }
            }
            
            logFile?.let {
                val writer = FileWriter(it)
                writer.appendLine("--- LiteReader Crash Log ---")
                writer.appendLine("Timestamp: $timestamp")
                writer.appendLine("Thread: ${thread.name}")
                writer.appendLine("Message: ${throwable.message}")
                writer.appendLine("Stack Trace:")
                writer.appendLine(Log.getStackTraceString(throwable))
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
