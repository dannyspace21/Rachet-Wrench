// ReleaseNotesUi.kt — the "What's new" pop-up shown once after an update,
// and the Release notes section in Settings.
package com.ratchetwrench.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ratchetwrench.app.BuildConfig
import com.ratchetwrench.app.data.Release
import com.ratchetwrench.app.data.ReleaseNotes

/** One release: "New", "Improved", "Fixed" bullet lists (empty ones are skipped). */
@Composable
fun ReleaseBody(r: Release) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((title, items) in listOf("New" to r.new, "Improved" to r.improved, "Fixed" to r.fixed)) {
            if (items.isEmpty()) continue
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            items.forEach { Text("•  $it") }
        }
    }
}

/**
 * Shown the first time the app opens after an update (once per version).
 * Fresh installs don't see it (see AppRoot), and nothing shows if this
 * version has no notes (e.g. a local test build).
 */
@Composable
fun WhatsNewDialog(versionName: String = BuildConfig.VERSION_NAME, versionCode: Int = BuildConfig.VERSION_CODE) {
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    // This version's notes plus any versions skipped since the last ones seen.
    val list = remember(versionName) { ReleaseNotes.since(ctx, versionName, repo.store.notesSeenCode) }
    val notes = list.firstOrNull { it.version == versionName }
    var show by remember { mutableStateOf(notes != null && repo.store.notesSeenCode < versionCode) }
    if (!show || notes == null) return
    val close = { repo.store.notesSeenCode = versionCode; show = false }
    AlertDialog(onDismissRequest = close,
        title = { Text("What's new in ${notes.version}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            ReleaseBody(notes)
            list.filter { it !== notes }.forEach { r ->
                Text("Also new (from ${r.version})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                ReleaseBody(r)
            }
        } },
        confirmButton = { TextButton(close) { Text("Got it") } })
}

/** Settings: this version's notes, with every earlier version behind a button. */
@Composable
fun ReleaseNotesCard() {
    val ctx = LocalContext.current
    val all = remember { ReleaseNotes.all(ctx) }
    var showAll by remember { mutableStateOf(false) }
    if (all.isEmpty()) return
    val current = all.firstOrNull { it.version == BuildConfig.VERSION_NAME } ?: all.first()
    SectionCard {
        Text("Release notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Version ${current.version}" + if (current.date.isNotBlank()) " · ${prettyDate(current.date)}" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReleaseBody(current)
        val older = all.filter { it !== current }
        if (older.isNotEmpty()) {
            SecondaryButton(if (showAll) "Hide earlier versions" else "Show earlier versions", "list") { showAll = !showAll }
            if (showAll) older.forEach { r ->
                Text("Version ${r.version}" + if (r.date.isNotBlank()) " · ${prettyDate(r.date)}" else "", fontWeight = FontWeight.Bold)
                ReleaseBody(r)
            }
        }
    }
}
