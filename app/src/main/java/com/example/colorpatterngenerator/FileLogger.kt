package com.example.colorpatterngenerator

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null)
        if (dir != null) {
            logFile = File(dir, "app_logs.txt")
            log("FileLogger", "Log file path: ${logFile?.absolutePath}")
        } else {
            Log.e("FileLogger", "External Files Dir is null")
        }
    }

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        write("INFO", tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e(tag, message, tr)
        val msg = if (tr != null) "$message\n${Log.getStackTraceString(tr)}" else message
        write("ERROR", tag, msg)
    }

    private fun write(level: String, tag: String, message: String) {
        try {
            logFile?.let { file ->
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileWriter(file, true).use { writer ->
                    writer.append("[$timestamp] [$level] $tag: $message\n")
                }
            }
        } catch (e: Exception) {
            // Avoid infinite loops if logging fails
        }
    }
}
