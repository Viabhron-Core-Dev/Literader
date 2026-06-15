package com.example.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupHelper {
    suspend fun backupData(context: Context, includeBooks: Boolean): Result<String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val suffix = if (includeBooks) "Full" else "NoBooks"
            val fileName = "LiteReader_Backup_${suffix}_$timestamp.zip"
            
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            
            val destFile = File(downloadsDir, fileName)
            
            ZipOutputStream(FileOutputStream(destFile)).use { zos ->
                // Backup Databases
                val dbFile = context.getDatabasePath("litereader_db")
                if (dbFile != null) {
                    val dbDir = dbFile.parentFile
                    if (dbDir != null && dbDir.exists()) {
                        dbDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                addFileToZip(file, "databases/${file.name}", zos)
                            }
                        }
                    }
                }
                
                // Backup Shared Prefs
                val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                if (prefsDir.exists()) {
                    prefsDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(file, "shared_prefs/${file.name}", zos)
                        }
                    }
                }
                
                // Backup Files (Books)
                if (includeBooks) {
                    val filesDir = context.filesDir
                    if (filesDir.exists()) {
                        zipDirectory(filesDir, "files", zos)
                    }
                }
            }
            
            Result.success(destFile.absolutePath)
        } catch (e: Exception) {
            Log.e("BackupHelper", "Backup failed", e)
            Result.failure(e)
        }
    }
    
    private fun zipDirectory(dir: File, path: String, zos: ZipOutputStream) {
        dir.listFiles()?.forEach { file ->
            val childPath = if (path.isEmpty()) file.name else "$path/${file.name}"
            if (file.isDirectory) {
                // To preserve empty directories, one could add an entry ending with '/'
                zipDirectory(file, childPath, zos)
            } else {
                addFileToZip(file, childPath, zos)
            }
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        try {
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            FileInputStream(file).use { fis ->
                fis.copyTo(zos)
            }
            zos.closeEntry()
        } catch (e: Exception) {
            Log.e("BackupHelper", "Failed to add $entryName to zip", e)
        }
    }
}
