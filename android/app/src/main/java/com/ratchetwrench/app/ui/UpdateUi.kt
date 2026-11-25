// UpdateUi.kt — the app-update banner (Garage screen) and the
// "App updates" section in Settings.  The work itself is in update/Updater.kt.
package com.ratchetwrench.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratchetwrench.app.update.UpdateState
import com.ratchetwrench.app.update.Updater
import kotlinx.coroutines.launch

/** Opens Android's "Install unknown apps" switch for Ratchet Wrench. */
fun openInstallPermission(context: android.content.Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Slim banner shown on the Garage screen only while an update needs attention. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val (text, action) = when (val s = state) {
        is UpdateState.Available -> "Update ${s.version} is available." to ("Update now" to { scope.launch { Updater.downloadAndInstall(ctx) }; Unit })
        is UpdateState.Downloading -> "Downloading update ${s.version}… ${s.percent}%" to null
        is UpdateState.Installing -> "Installing update ${s.version}… tap “Update” if Android asks." to null
        is UpdateState.NeedsPermission -> "An update is ready. Allow Ratchet Wrench to install its updates (one time)." to
            ("Allow" to { openInstallPermission(ctx) })
        is UpdateState.Failed -> s.message to ("Try again" to { scope.launch { Updater.check(ctx, userAsked = true) }; Unit })
        else -> return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UiIcon("download", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
            action?.let { (label, onClick) -> SecondaryButton(label) { onClick() } }
        }
        val s = state
        if (s is UpdateState.Downloading) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

/** "App updates" card for the Settings screen. */
@Composable
fun UpdateSettingsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    var auto by remember { mutableStateOf(Updater.autoEnabled(ctx)) }
    SectionCard {
        Text("App updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Installed version: ${Updater.currentVersion()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Update automatically when I open the app (downloads on Wi-Fi)", Modifier.weight(1f))
            Switch(auto, { auto = it; Updater.setAutoEnabled(ctx, it) })
        }
        SecondaryButton("Check for updates now", "sync") { scope.launch { Updater.check(ctx, userAsked = true) } }
        when (val s = state) {
            is UpdateState.UpToDate -> Text("You have the latest version.", color = LocalExtra.current.ok)
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is UpdateState.NeedsPermission -> SecondaryButton("Allow app updates (Android setting)") { openInstallPermission(ctx) }
            is UpdateState.Downloading -> Text("Downloading ${s.version}… ${s.percent}%")
            is UpdateState.Installing -> Text("Installing ${s.version}…")
            is UpdateState.Available -> Text("Version ${s.version} is available.")
            else -> {}
        }
        Text("Updates come from this project's GitHub Releases and are installed only if they're signed with the app's own key.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
