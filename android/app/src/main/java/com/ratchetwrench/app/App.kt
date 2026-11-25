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
        ReminderWorker.createChannel(this)
        ReminderWorker.schedule(this)
        SyncWorker.schedulePeriodic(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
