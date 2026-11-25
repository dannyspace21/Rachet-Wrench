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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun LoginScreen(onSignedIn: () -> Unit, onBack: (() -> Unit)? = null) {
    val repo = LocalRepo.current
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(repo.store.serverUrl.ifBlank { "https://" }) }
    var user by remember { mutableStateOf(repo.store.username) }
    var pass by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(repo.store.allowHttpLocal) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { if (onBack != null) TopBar("Connect", onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            AppLogo(84.dp)
            Text("Ratchet Wrench", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Connect to your computer or home server", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Desktop app: tick “Allow my phone to sync” in its window and type the address it shows " +
                "(like http://192.168.1.20:8077), then tick “Allow plain HTTP on my home network” below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Computer or server address", server, { server = it.trim() }, hint = "e.g. http://192.168.1.20:8077 or https://garage.your-tailnet.ts.net")
                Field("Username", user, { user = it })
                Field("Password", pass, { pass = it }, password = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowHttp, { allowHttp = it })
                    Text("Allow plain HTTP on my home network (not recommended; only for private addresses like 192.168.x.x)",
                        style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton(if (busy) "Signing in…" else "Sign in", enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    val url = server.trim().trimEnd('/')
                    val problem = UrlPolicy.problem(url, allowHttp)
                    if (problem != null) { error = problem; return@PrimaryButton }
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                repo.store.serverUrl = url
                                repo.store.allowHttpLocal = allowHttp
                                val api = Api(repo.store)
                                repo.store.token = api.login(user.trim(), pass)
                                repo.store.username = user.trim()
                                val me = api.me()
                                repo.store.serverAiVision = me["server"]?.jsonObject?.get("ai_vision_available")?.jsonPrimitive?.content == "true"
                                me["settings"]?.jsonObject?.get("theme")?.jsonPrimitive?.content?.let { repo.store.theme = it }
                                repo.store.lastSince = null
                                // Coming from phone-only mode: upload everything made on this phone.
                                if (repo.store.mode == "local") repo.db.markEverythingForUpload()
                                repo.store.mode = "server"
                                SyncEngine(appContext).run() // first full upload + download
                            }
                            pass = ""
                            onSignedIn()
                        } catch (e: Exception) {
                            error = e.message ?: "Sign-in failed."
                        } finally { busy = false }
                    }
                }
            }
        }
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
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "Reminder notifications are on." else "Notifications are blocked in Android settings."
    }

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
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { ReminderWorker.checkAndNotify(ctx) }
                        message = if (n == 0) "Nothing new is due (or notifications are off)." else "$n reminder notification(s) shown."
                    }
                }
            }
            SectionCard {
                Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (repo.store.mode == "server") {
                    Text("Connected to: ${repo.store.serverUrl}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last successful sync: ${repo.store.lastSyncOk ?: "never"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondaryButton("Sync now", "sync") {
                        scope.launch {
                            message = try { withContext(Dispatchers.IO) { SyncEngine(ctx).run() }; "Synced." } catch (e: Exception) { "Sync failed: ${e.message}" }
                        }
                    }
                } else {
                    Text("This phone only — your data stays on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Connect to the desktop app on your computer (or a home server) to sync. Everything you've added is copied over.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton("Connect to my computer or server", "sync") { nav.navigate("login") }
                }
            }
            message?.let { Text(it, color = LocalExtra.current.muted) }
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
                        onSignedOut()
                    }
                }
            }
        }
    }
}
