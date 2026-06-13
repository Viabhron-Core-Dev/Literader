package com.example.util

import android.content.Context
import java.io.File
import com.example.LogKeeper

object AppLogger {
    fun d(tag: String, msg: String) {
        LogKeeper.writeLog(tag, msg)
        android.util.Log.d(tag, msg)
    }

    fun export(context: Context): File {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val f = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "litereader_logs_export_$ts.txt")
        f.writeText("Exporting from AppLogger.\nPlease check LiteReader_Log_...txt in Downloads for full logs.")
        return f
    }
}
