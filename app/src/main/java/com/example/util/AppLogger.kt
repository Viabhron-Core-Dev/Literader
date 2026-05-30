package com.example.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val logs = mutableListOf<String>()

    fun d(tag: String, msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $tag: $msg"
        logs.add(line)
        if (logs.size > 1000) logs.removeAt(0)
        android.util.Log.d(tag, msg)
    }

    fun export(context: Context): File {
        val f = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "litereader_logs.txt")
        f.writeText(logs.joinToString("\n"))
        return f
    }
}
