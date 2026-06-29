package com.dungeonboss.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny append-only diagnostic log written to a file the user can upload.
 *
 * The file lives in the app's external files dir, e.g.
 *   /sdcard/Android/data/com.dungeonboss/files/dungeon-boss-log.txt
 * which is reachable with a file manager or `adb pull`, and can be sent
 * straight out via the "Share log" button (see GameScreen / FileProvider).
 *
 * Also tees every line to logcat under the tag "DungeonBoss".
 */
object DebugLog {
    private const val TAG = "DungeonBoss"
    private const val FILE_NAME = "dungeon-boss-log.txt"
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        logFile = file
        // Start a fresh log each launch so uploads stay small and relevant.
        runCatching { file.writeText("==== Dungeon Boss session ${Date()} · UI build $UI_BUILD ====\n") }
    }

    fun path(): String = logFile?.absolutePath ?: "(log not started)"

    fun file(): File? = logFile

    @Synchronized
    fun log(message: String) {
        Log.d(TAG, message)
        append("${stamp.format(Date())}  $message")
    }

    @Synchronized
    fun error(message: String, t: Throwable) {
        Log.e(TAG, message, t)
        append("${stamp.format(Date())}  ERROR: $message")
        append(Log.getStackTraceString(t))
    }

    private fun append(line: String) {
        val file = logFile ?: return
        runCatching { file.appendText(line + "\n") }
    }
}
