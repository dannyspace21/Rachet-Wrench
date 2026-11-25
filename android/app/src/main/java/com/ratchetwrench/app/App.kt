// App.kt — the app's starting points.
//
//  RatchetApp   — runs once when the app process starts: creates the
//                 notification channel and schedules background sync and
//                 the offline reminder check.
//  MainActivity — the single screen container; all screens are Jetpack
//                 Compose functions shown inside it (see ui/Navigation.kt).
package com.ratchetwrench.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ratchetwrench.app.sync.ReminderWorker
import com.ratchetwrench.app.sync.SyncWorker
import com.ratchetwrench.app.ui.AppRoot

class RatchetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ReminderWorker.createChannel(this)
        ReminderWorker.schedule(this)
        SyncWorker.schedulePeriodic(this)
    }
}

/**
 * If the app ever crashes, the error details are saved ON THE PHONE
 * (never sent anywhere).  The next time you open the app it offers to copy
 * them, so you can share the exact error when reporting a problem.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = "Ratchet Wrench ${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}, " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n\n" + error.stackTraceToString().take(20_000)
                java.io.File(app.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: android.content.Context): String? =
        java.io.File(context.filesDir, FILE).takeIf { it.isFile }?.readText()

    fun clear(context: android.content.Context) {
        java.io.File(context.filesDir, FILE).delete()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
