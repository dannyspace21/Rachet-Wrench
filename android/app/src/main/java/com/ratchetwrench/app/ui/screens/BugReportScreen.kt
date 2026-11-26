// BugReportScreen.kt — "Report a bug" inside the app.  The person types what
// went wrong, sees the technical details that will be attached (and can
// leave them out), and presses Send: the report goes straight into the
// project's GitHub Issues (data/BugReports.kt).  If this build can't send
// (no key, e.g. a local test build) or sending fails, they can open the same
// report on GitHub instead.
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.BugReports
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.onMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BugReportScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val details = remember { bugDetails(ctx) }
    var summary by rememberSaveable { mutableStateOf("") }
    var what by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var expected by rememberSaveable { mutableStateOf("") }
    var include by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentAs by rememberSaveable { mutableStateOf<Int?>(null) }
    fun openOnGitHub() {
        val text = listOf(summary, what, steps).filter { it.isNotBlank() }.joinToString("\n\n")
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(bugReportUrl(ctx, text)))) }
    }

    Scaffold(topBar = { TopBar("Report a bug", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The owner's reminder (asked for): this is the temporary set-up.
            Text("Reminder for the owner: bug reports currently use a GitHub key built into the app. " +
                "Move them to the relay service (option 1) — see docs/BUG-REPORTS.md.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(10.dp))
            sentAs?.let { n ->
                Text("Thanks! Your report was sent (#$n).", fontWeight = FontWeight.Bold, color = LocalExtra.current.ok)
                PrimaryButton("Done", "check", Modifier.fillMaxWidth()) { nav.popBackStack() }
                return@Column
            }
            Field("What's the problem? (one line)", summary, { summary = it.take(200); error = null })
            Field("What happened?", what, { what = it.take(4000) }, singleLine = false)
            Field("What were you doing? (steps)", steps, { steps = it.take(4000) }, singleLine = false)
            Field("What did you expect? (optional)", expected, { expected = it.take(2000) }, singleLine = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include these technical details", Modifier.weight(1f))
                Switch(include, { include = it })
            }
            if (include) Text(details, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp))
            Text("Reports are public on GitHub. Don't include personal information (names, addresses, VINs, plates).",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (BugReports.enabled) {
                PrimaryButton(if (sending) "Sending…" else "Send report", "check", Modifier.fillMaxWidth(), enabled = !sending) {
                    if (summary.trim().length < 5) { error = "Please describe the problem in a few words."; return@PrimaryButton }
                    BugReports.tooSoon(repo.store.bugReportTimes)?.let { error = it; return@PrimaryButton }
                    sending = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) { BugReports.send(summary.trim(), what, steps, expected, if (include) details else "") }
                        }
                        onMain {
                            sending = false
                            result.onSuccess { n -> repo.store.bugReportTimes = repo.store.bugReportTimes + System.currentTimeMillis(); sentAs = n }
                                .onFailure { error = "Couldn't send the report (${it.message ?: "no connection"}). Try again, or open it on GitHub below." }
                        }
                    }
                }
                if (error != null) SecondaryButton("Open on GitHub instead", "external", Modifier.fillMaxWidth()) { openOnGitHub() }
            } else {
                Text("Sending from inside the app isn't set up in this version, so this opens the report on GitHub (a free account is needed).",
                    style = MaterialTheme.typography.bodySmall)
                PrimaryButton("Continue on GitHub", "external", Modifier.fillMaxWidth()) {
                    if (summary.trim().length < 5) { error = "Please describe the problem in a few words."; return@PrimaryButton }
                    openOnGitHub()
                }
            }
        }
    }
}
