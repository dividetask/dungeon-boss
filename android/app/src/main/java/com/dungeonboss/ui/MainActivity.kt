package com.dungeonboss.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Single-activity Compose host. All game UI lives in [GameScreen]; all rules
 * live in the engine under com.dungeonboss.game.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Diagnostics: write a file the user can upload, and capture any crash.
        DebugLog.init(applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.error("uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        DebugLog.log("MainActivity.onCreate")

        setContent {
            DungeonBossTheme {
                GameScreen()
            }
        }
    }
}
