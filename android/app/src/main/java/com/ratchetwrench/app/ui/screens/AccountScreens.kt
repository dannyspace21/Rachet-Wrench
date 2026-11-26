// AccountScreens.kt — sign-in and Settings.
package com.ratchetwrench.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.UrlPolicy
import com.ratchetwrench.app.data.ApiException
import com.ratchetwrench.app.sync.ReminderWorker
import com.ratchetwrench.app.sync.SyncEngine
import com.ratchetwrench.app.sync.SyncWorker
import com.ratchetwrench.app.ui.AppLogo
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.LocalSetTheme
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.Segmented
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.UpdateSettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** First launch: use the app on this phone only, or connect to a computer/server. */
@Composable
fun WelcomeScreen(onPhoneOnly: () -> Unit, onConnect: () -> Unit) {
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            AppLogo(96.dp)
            Text("Ratchet Wrench", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Your vehicles' maintenance & repair log", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard {
                    Text("Use on this phone only", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("No computer or server needed. Everything is stored privately on this phone and works offline. " +
                        "You can connect to your computer later — everything you've added will be copied over.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton("Start on this phone", "check", Modifier.fillMaxWidth()) { onPhoneOnly() }
                }
                SectionCard {
                    Text("Connect to my computer or home server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Sync with the Ratchet Wrench desktop app on your computer, or with your own home server, " +
                        "so the phone and the computer show the same records.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryButton("Connect", "sync", Modifier.fillMaxWidth()) { onConnect() }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit, onBack: (() -> Unit)? = null, onScanQr: (() -> Unit)? = null, pairPayload: String? = null) {
    val repo = LocalRepo.current
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(repo.store.serverUrl) }
    var user by remember { mutableStateOf(repo.store.username) }
    var pass by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(repo.store.allowHttpLocal) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A home-network address that answers in plain HTTP: ask before using it.
    var askHttp by remember { mutableStateOf<String?>(null) }
    // One-time code from a scanned "connect your phone" QR code (instead of a password).
    var pairCode by remember { mutableStateOf<String?>(null) }

    /** Sign in to [url] (already checked against the connection rules). */
    fun attempt(url: String) {
        busy = true; error = null
        scope.launch {
            try {
                val syncProblem = withContext(Dispatchers.IO) {
                    val previousUrl = repo.store.serverUrl
                    repo.store.serverUrl = url
                    repo.store.allowHttpLocal = allowHttp
                    repo.store.token = null
                    val api = Api(repo.store)
                    // Make sure this really is a Ratchet Wrench server first.
                    if (!api.isRatchetWrench()) throw ApiException(404, "Not a Ratchet Wrench server.")
                    val code = pairCode
                    if (code != null) {
                        // QR code: a one-time code instead of username + password.
                        val (token, name) = api.claimPairing(code)
                        repo.store.token = token
                        repo.store.username = name
                        pairCode = null
                    } else {
                        repo.store.token = api.login(user.trim(), pass)
                        repo.store.username = user.trim()
                    }
                    val me = api.me()
                    repo.store.serverAiVision = me["server"]?.jsonObject?.get("ai_vision_available")?.jsonPrimitive?.content == "true"
                    me["settings"]?.jsonObject?.get("theme")?.jsonPrimitive?.content?.let { repo.store.theme = it }
                    repo.store.lastSince = null
                    // Coming from phone-only mode, or signing in to a different
                    // computer/server: send everything this phone has, so the
                    // two sides end up with the same data.  (Harmless when it's
                    // the same server: the server keeps whichever copy is newest.)
                    if (repo.store.mode == "local" || previousUrl != url) repo.db.markEverythingForUpload()
                    repo.store.mode = "server"
                    // First full upload + download.  Signed in either way: if
                    // this fails, background sync simply tries again later.
                    runCatching { SyncEngine(appContext).run() }.exceptionOrNull()
                }
                pass = ""
                if (syncProblem != null) android.widget.Toast.makeText(appContext,
                    "Signed in. The first sync didn't finish (${syncProblem.message}); it will retry automatically.",
                    android.widget.Toast.LENGTH_LONG).show()
                com.ratchetwrench.app.ui.onMain { onSignedIn() }
            } catch (e: Exception) {
                // "https://" to a computer that speaks plain HTTP (the desktop
                // app / Docker at home): offer the http:// address instead.
                val http = if (e is javax.net.ssl.SSLException) UrlPolicy.httpVersion(url) else null
                if (http != null) askHttp = http else error = UrlPolicy.explain(e, url)
            } finally { busy = false }
        }
    }

    /** Use a scanned QR code: it carries the server address and a one-time code. */
    fun usePairing(payload: String) {
        val uri = android.net.Uri.parse(payload)
        val srv = uri.getQueryParameter("server").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        if (srv.isBlank() || code.isBlank()) { error = "That QR code isn't complete. Show a new one on your computer."; return }
        val url = UrlPolicy.complete(srv)
        server = url
        pairCode = code
        if (UrlPolicy.isLocalHttp(url) && !allowHttp) { askHttp = url; return }   // ask first, as always
        UrlPolicy.problem(url, allowHttp)?.let { error = it; pairCode = null; return }
        attempt(url)
    }
    LaunchedEffect(pairPayload) { pairPayload?.let { usePairing(it) } }

    Scaffold(topBar = { if (onBack != null) TopBar("Connect", onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            AppLogo(84.dp)
            Text("Ratchet Wrench", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Connect to your computer or home server", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Desktop app: tick “Allow my phone to sync” in its window and type the address it shows, " +
                "like 192.168.1.20:8077.  Docker server: the computer's address with port 8000, like 192.168.1.20:8000.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onScanQr != null) {
                    // Easiest: scan the QR code on the computer — nothing to type.
                    PrimaryButton("Scan QR code", "scan", Modifier.fillMaxWidth(), enabled = !busy) { error = null; onScanQr() }
                    Text("Desktop app: click “Connect a phone (QR code)”.  Web app: Settings → Connect a phone.  Or type the details below.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Field("Computer or server address", server, { server = it.trim() }, hint = "e.g. 192.168.1.20:8077 or https://garage.your-tailnet.ts.net")
                Field("Username", user, { user = it })
                Field("Password", pass, { pass = it }, password = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowHttp, { allowHttp = it })
                    Text("Allow plain HTTP on my home network (needed for the desktop app and Docker at home; only for private addresses like 192.168.x.x)",
                        style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton(if (busy) "Signing in…" else "Sign in", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    pairCode = null
                    val url = UrlPolicy.complete(server)
                    server = url
                    // Plain HTTP at home but not allowed yet: ask, don't just fail.
                    if (UrlPolicy.isLocalHttp(url) && !allowHttp) { askHttp = url; return@PrimaryButton }
                    val problem = UrlPolicy.problem(url, allowHttp)
                    if (problem != null) { error = problem; return@PrimaryButton }
                    attempt(url)
                }
            }
        }
    }
    askHttp?.let { url ->
        AlertDialog(onDismissRequest = { askHttp = null; pairCode = null },
            title = { Text("Connect with plain HTTP?") },
            text = {
                Text("$url uses plain HTTP (not encrypted). That's normal for the Ratchet Wrench desktop app and Docker server " +
                    "on your home network. Only do this on your own Wi-Fi — never on public Wi-Fi.")
            },
            confirmButton = {
                TextButton({ askHttp = null; allowHttp = true; server = url; attempt(url) }) { Text("Yes, connect") }
            },
            dismissButton = { TextButton({ askHttp = null; pairCode = null }) { Text("Cancel") } })
    }
}

@Composable
fun SettingsScreen(nav: NavHostController, onSignedOut: () -> Unit) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val setTheme = LocalSetTheme.current
    var theme by remember { mutableStateOf(repo.store.theme) }
    var unit by remember { mutableStateOf(repo.store.distanceUnit) }
    var notify by remember { mutableStateOf(repo.store.notificationsEnabled) }
    var message by remember { mutableStateOf<String?>(null) }
    // "Check reminders now": notifies every due reminder right away and says exactly what happened.
    suspend fun checkNow() {
        val due = withContext(Dispatchers.IO) { ReminderWorker.dueCount(ctx) }
        val n = withContext(Dispatchers.IO) { ReminderWorker.checkAndNotify(ctx, force = true) }
        message = when {
            due == 0 -> "Nothing is due right now — you'll get a notification when something is."
            n > 0 -> "$n reminder notification${if (n == 1) "" else "s"} shown."
            else -> "$due reminder${if (due == 1) " is" else "s are"} due, but notifications are blocked. Tap “Allow notifications”."
        }
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { checkNow() } else message = "Notifications are blocked. Tap “Allow notifications” to turn them on in Android settings."
    }
    var canNotify by remember { mutableStateOf(ReminderWorker.canNotify(ctx)) }

    /** Save a preference on the server too (best effort; works offline later). */
    fun pushSetting(key: String, value: String) = scope.launch(Dispatchers.IO) {
        runCatching {
            Api(repo.store).send("PUT", "/api/auth/settings", kotlinx.serialization.json.JsonObject(mapOf(key to kotlinx.serialization.json.JsonPrimitive(value))))
        }
    }

    Scaffold(topBar = { TopBar("Settings", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Segmented(listOf("light" to "Light", "dark" to "Dark", "system" to "System"), theme) { theme = it; setTheme(it); pushSetting("theme", it) }
                Segmented(listOf("mi" to "Miles", "km" to "Kilometers"), unit) { unit = it; repo.store.distanceUnit = it; pushSetting("distance_unit", it) }
            }
            SectionCard {
                Text("Reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Notify me when maintenance is due (works offline)", modifier = Modifier.weight(1f))
                    Switch(notify, {
                        notify = it; repo.store.notificationsEnabled = it
                        if (it && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                SecondaryButton("Check reminders now", "bell") {
                    when {
                        !notify -> message = "Turn on “Notify me when maintenance is due” first."
                        !ReminderWorker.canNotify(ctx) && Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> scope.launch { checkNow(); canNotify = ReminderWorker.canNotify(ctx) }
                    }
                }
                // Blocked in Android settings: one tap to fix it.
                if (notify && !canNotify) SecondaryButton("Allow notifications", "settings") {
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                    }
                }
            }
            SectionCard {
                Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (repo.store.mode == "server") {
                    var serverUrl by remember { mutableStateOf(repo.store.serverUrl) }
                    var lastOk by remember { mutableStateOf(repo.store.lastSyncOk) }
                    var lastError by remember { mutableStateOf(repo.store.lastSyncError) }
                    var syncing by remember { mutableStateOf(false) }
                    var changing by remember { mutableStateOf(false) }
                    Text("Connected to: $serverUrl", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last successful sync: ${lastOk?.let { com.ratchetwrench.app.ui.prettyDateTime(it) } ?: "never"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lastError?.let { Text("Last sync problem: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    // One full-width button per line (side by side they got squeezed on phones).
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(if (syncing) "Syncing…" else "Sync now", "sync", Modifier.fillMaxWidth(), enabled = !syncing) {
                            syncing = true
                            scope.launch {
                                message = try { withContext(Dispatchers.IO) { SyncEngine(ctx).run() }; "Synced." }
                                    catch (e: Exception) { "Sync failed: ${repo.store.lastSyncError ?: e.message}" }
                                lastOk = repo.store.lastSyncOk; lastError = repo.store.lastSyncError; syncing = false
                            }
                        }
                        SecondaryButton("Change address", "edit", Modifier.fillMaxWidth()) { changing = true }
                        SecondaryButton("Reconnect with QR code", "scan", Modifier.fillMaxWidth()) { nav.navigate("login") }
                        SecondaryButton("Sign in again", "key", Modifier.fillMaxWidth()) { nav.navigate("login") }
                    }
                    Text("Tip: if your computer restarted, its address may have changed — check the desktop app's window and use “Change address”.",
                        color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                    if (changing) ChangeAddressDialog(onClose = { changing = false }) { url, note ->
                        serverUrl = url; message = note; changing = false
                    }
                } else {
                    Text("This phone only — your data stays on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Connect to the desktop app on your computer (or a home server) to sync. Everything you've added is copied over.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton("Connect to my computer or server", "sync") { nav.navigate("login") }
                }
            }
            UpdateSettingsCard()
            message?.let { Text(it, color = LocalExtra.current.muted) }
            ShoppingSettingsCard()
            com.ratchetwrench.app.ui.ReleaseNotesCard()
            SectionCard {
                Text("Report a bug", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Tell us what went wrong. Your report goes straight to the project's bug list — no account needed. " +
                    "You'll see everything that's sent before you send it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                SecondaryButton("Report a bug", "warning") { nav.navigate("report-bug") }
            }
            AboutCard()
            if (repo.store.mode == "server") SectionCard {
                Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Signed in as ${repo.store.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryButton("Disconnect — keep my data on this phone", "logout") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { SyncEngine(ctx).run() } // send last changes first
                            Api(repo.store).logout()
                            repo.store.disconnectKeepData()
                        }
                        message = "Disconnected. You're now using this phone only."
                    }
                }
                SecondaryButton("Sign out and remove data from this phone", "trash") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Api(repo.store).logout()
                            repo.db.wipe()
                            repo.store.signOut()
                        }
                        com.ratchetwrench.app.ui.onMain { onSignedOut() }
                    }
                }
            }
        }
    }
}

/**
 * About — what this app is, which version is installed, and exactly what it
 * connects to.  Links open in the phone's browser (nothing is fetched here).
 */
@Composable
fun AboutCard() {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.ratchetwrench.app.ui.AppLogo(48.dp)
            Column {
                Text("About Ratchet Wrench", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Version ${com.ratchetwrench.app.BuildConfig.VERSION_NAME} (${com.ratchetwrench.app.BuildConfig.VERSION_CODE})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("A self-hosted maintenance and repair log for your cars and motorcycles. Free and open source.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Privacy: no ads, no tracking, no analytics. The app only connects to: your own computer or server (if you " +
            "connect one), the U.S. government NHTSA service (to decode a VIN, when you ask), and GitHub (to check for " +
            "app updates). Crash details are kept on this phone only.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        SecondaryButton("Project page and help", "external") { open("https://github.com/dannyspace21/Ratchet-Wrench") }
        SecondaryButton("Security and privacy details", "book") { open("https://github.com/dannyspace21/Ratchet-Wrench/blob/main/SECURITY.md") }
    }
}

/**
 * Change the computer/server address WITHOUT signing out (e.g. the
 * computer got a new IP address after a restart).  The new address is
 * checked first: it must be a Ratchet Wrench server, and the login must
 * still work there.  Plain HTTP at home needs the same explicit "allow" tick
 * as at sign-in.
 */
@Composable
fun ChangeAddressDialog(onClose: () -> Unit, onSaved: (String, String) -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(repo.store.serverUrl) }
    var allowHttp by remember { mutableStateOf(repo.store.allowHttpLocal) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val url = UrlPolicy.complete(input)
    val needsHttpOk = UrlPolicy.isLocalHttp(url)
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Computer or server address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Address", input, { input = it.trim(); problem = null }, hint = "e.g. 192.168.1.20:8077")
                if (needsHttpOk) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowHttp, { allowHttp = it })
                    Text("Allow plain HTTP on my home network (normal for the desktop app and Docker at home; never on public Wi-Fi)",
                        style = MaterialTheme.typography.bodySmall)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({
                UrlPolicy.problem(url, allowHttp)?.let { problem = it; return@TextButton }
                busy = true
                scope.launch {
                    val oldUrl = repo.store.serverUrl
                    val oldHttp = repo.store.allowHttpLocal
                    val result = withContext(Dispatchers.IO) {
                        repo.store.serverUrl = url
                        repo.store.allowHttpLocal = allowHttp
                        try {
                            val api = Api(repo.store)
                            if (!api.isRatchetWrench()) throw ApiException(404, "Not a Ratchet Wrench server.")
                            api.me() // the login must still be valid there
                            null
                        } catch (e: Exception) { e }
                    }
                    busy = false
                    when {
                        result == null -> onSaved(url, "Address saved. Syncing will use $url.")
                        result is ApiException && result.status == 401 ->
                            // Right server, but the login isn't valid there: keep the address, sign in again.
                            onSaved(url, "Address saved, but you need to sign in again (Settings → Sign in again).")
                        else -> {
                            repo.store.serverUrl = oldUrl; repo.store.allowHttpLocal = oldHttp
                            problem = UrlPolicy.explain(result, url)
                        }
                    }
                }
            }, enabled = !busy && input.isNotBlank() && (!needsHttpOk || allowHttp)) { Text(if (busy) "Checking…" else "Check and save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

/**
 * A pre-filled bug report on the project's GitHub Issues page.  Nothing is
 * sent from the app: the browser opens the form, the person reviews it (and
 * can delete anything) and presses Submit themselves.
 */
fun bugReportUrl(context: android.content.Context, text: String = ""): String {
    val details = (if (text.isNotBlank()) text.trim() + "\n\n" else "") + bugDetails(context)
    return android.net.Uri.parse("https://github.com/dannyspace21/Ratchet-Wrench/issues/new").buildUpon()
        .appendQueryParameter("template", "bug_report.yml")
        .appendQueryParameter("labels", "bug")
        .appendQueryParameter("platform", "Android")
        .appendQueryParameter("details", details.trim())
        .build().toString()
}

/** App version, phone model, mode, last sync problem and last crash (shown before sending). */
fun bugDetails(context: android.content.Context): String {
    val store = com.ratchetwrench.app.data.SecureStore(context)
    return buildString {
        appendLine("Ratchet Wrench ${com.ratchetwrench.app.BuildConfig.VERSION_NAME} (${com.ratchetwrench.app.BuildConfig.VERSION_CODE})")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Mode: " + when (store.mode) { "server" -> "connected to a computer/server"; "local" -> "this phone only"; else -> "not set up" })
        store.lastSyncError?.let { appendLine("Last sync problem: $it") }
        com.ratchetwrench.app.CrashLog.read(context)?.let { appendLine("Last crash (first lines):"); appendLine(it.take(1500)) }
    }.trim()
}

/** Smart Shopping settings (this phone only): on/off, retailers, preferred retailer. */
@Composable
fun ShoppingSettingsCard() {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val retailers = com.ratchetwrench.app.domain.Catalog.get(ctx).retailers.filter { it.enabled }
    var on by remember { mutableStateOf(repo.store.shoppingEnabled) }
    var disabled by remember { mutableStateOf(repo.store.shoppingDisabled) }
    var preferred by remember { mutableStateOf(repo.store.shoppingPreferred) }
    SectionCard {
        Text("Smart Shopping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Optional “Find supplies” and shop buttons on reminders and parts. They open a search in the retailer's app or website using your vehicle's specs and part numbers. You never have to buy anything, and nothing is sent from Ratchet Wrench.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Show shopping buttons", modifier = Modifier.weight(1f))
            Switch(on, { on = it; repo.store.shoppingEnabled = it })
        }
        if (on) {
            retailers.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(r.name, modifier = Modifier.weight(1f))
                    Switch(r.key !in disabled, { enabled ->
                        disabled = if (enabled) disabled - r.key else disabled + r.key
                        repo.store.shoppingDisabled = disabled
                    })
                }
            }
            Text("Preferred retailer (listed first)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<Pair<String?, String>>(null to "No preference") + retailers.map { it.key to it.name }).forEach { (key, name) ->
                    androidx.compose.material3.FilterChip(selected = preferred == key, onClick = { preferred = key; repo.store.shoppingPreferred = key },
                        label = { Text(name) })
                }
            }
            // Affiliate links: only offered when an approved program is set up for Android.
            val programs = retailers.filter { com.ratchetwrench.app.domain.Shopping.affiliateOn(it, "android", true) }
            if (programs.isNotEmpty()) {
                var aff by remember { mutableStateOf(repo.store.shoppingAffiliate) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use affiliate links (${programs.joinToString { it.name }})", Modifier.weight(1f))
                    androidx.compose.material3.Switch(aff, { aff = it; repo.store.shoppingAffiliate = it })
                }
                Text(programs.joinToString(" ") { it.affiliate.disclosure } +
                    " Prices and search results are the same either way, and commissions never affect what's suggested.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
