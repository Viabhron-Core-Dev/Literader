package com.example.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogDropper {
    fun log(context: Context, message: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logFile = File(downloadsDir, "LiteReader_Log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$timestamp] $message\n")
            Log.d("LogDropper", "Appended to log: $message")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("LogDropper", "Failed to write log: ${e.message}")
        }
    }
}
