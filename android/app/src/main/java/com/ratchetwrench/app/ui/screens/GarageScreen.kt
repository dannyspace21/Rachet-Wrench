// GarageScreen.kt — the home screen: what's due, your vehicles, recent work.
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.CrashLog
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.Reminders
import com.ratchetwrench.app.sync.SyncEngine
import com.ratchetwrench.app.ui.AppLogo
import com.ratchetwrench.app.ui.KindPill
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.StatusPill
import com.ratchetwrench.app.ui.TypeBadge
import com.ratchetwrench.app.ui.UiIcon
import com.ratchetwrench.app.ui.UpdateBanner
import com.ratchetwrench.app.update.Updater
import com.ratchetwrench.app.ui.miles
import com.ratchetwrench.app.ui.money
import com.ratchetwrench.app.ui.prettyDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standard top bar with an optional back arrow and action buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarImpl(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1) },
        navigationIcon = { if (onBack != null) IconButton(onBack) { UiIcon("chevron_left", description = "Back") } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(nav: NavHostController) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicles by repo.db.vehicles().observeAll().collectAsState(initial = emptyList())
    val reminders by repo.db.reminders().observeAll().collectAsState(initial = emptyList())
    var refreshing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        refreshing = true
        syncError = try { withContext(Dispatchers.IO) { SyncEngine(ctx).run() }; null }
            catch (e: Exception) { repo.store.lastSyncError ?: (e.message ?: "Couldn't sync.") }
        refreshing = false
    }
    // Crash details saved last time (if the app closed unexpectedly).
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }
    crash?.let { details ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { CrashLog.clear(ctx); crash = null },
            title = { Text("Ratchet Wrench closed unexpectedly") },
            text = { Text("Sorry about that. The error details were saved on this phone only. Tap “Copy details” and paste them to whoever helps you fix it.") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    val clip = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("Ratchet Wrench crash", details))
                    CrashLog.clear(ctx); crash = null
                }) { Text("Copy details") }
            },
            dismissButton = { androidx.compose.material3.TextButton({ CrashLog.clear(ctx); crash = null }) { Text("Dismiss") } },
        )
    }

    // After an update: "What's new" (once per version; not while the crash notice shows).
    if (crash == null) com.ratchetwrench.app.ui.WhatsNewDialog()

    LaunchedEffect(Unit) {
        Updater.autoCheck(ctx) // app updates (at most every 6 hours)
        // Specs saved before "specs are also parts" get their parts (the
        // computer does this itself when the phone syncs with one).
        if (repo.store.mode != "server") repo.backfillSpecParts()
        refresh()
    }

    val photos by repo.db.attachments().observeVehiclePhotos().collectAsState(initial = emptyList())

    // The phone's Garage is kept simple: your vehicles and "Add vehicle".
    // What's due, and the work done, are on each vehicle's own screen.
    Scaffold(
        topBar = {
            TopBarImpl("Garage", null) {
                // The shopping list (with how many things are still to buy).
                val toBuy by repo.db.shopping().observeAll().collectAsState(initial = emptyList())
                val count = toBuy.count { !it.purchased }
                androidx.compose.material3.TextButton({ nav.navigate("shopping") },
                    modifier = Modifier.semantics { contentDescription = "Shopping list" + if (count > 0) ", $count to buy" else "" }) {
                    UiIcon("cart", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    if (count > 0) { Spacer(Modifier.size(4.dp)); Text("$count") }
                }
                // Labelled (not just a gear) so Settings & About are easy to find.
                androidx.compose.material3.TextButton({ nav.navigate("settings") }) {
                    UiIcon("settings", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text("Settings")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("vehicle-edit/new") },
                // Material 3 doesn't pass the button's text on to screen readers; say it explicitly.
                modifier = Modifier.semantics { contentDescription = "Add vehicle" },
                icon = { UiIcon("plus", tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                text = { Text("Add vehicle") })
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refresh() } }, modifier = Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { UpdateBanner() }
                syncError?.let { msg ->
                    item {
                        // Not hidden in small grey text: say clearly that sync failed, why, and where to fix it.
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                UiIcon("warning", tint = LocalExtra.current.warn)
                                Text("Couldn't sync with your computer", fontWeight = FontWeight.Bold)
                            }
                            Text(msg, style = MaterialTheme.typography.bodySmall)
                            Text("Showing what's saved on this phone. Last successful sync: ${repo.store.lastSyncOk?.let { com.ratchetwrench.app.ui.prettyDateTime(it) } ?: "never"}.",
                                color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.ratchetwrench.app.ui.SecondaryButton("Try again", "sync") { scope.launch { refresh() } }
                                com.ratchetwrench.app.ui.SecondaryButton("Fix in Settings", "settings") { nav.navigate("settings") }
                            }
                        }
                    }
                }
                if (vehicles.isEmpty()) item {
                    SectionCard {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppLogo(64.dp)
                            Text("No vehicles yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Tap “Add vehicle” — you can scan the VIN barcode.", color = LocalExtra.current.muted)
                        }
                    }
                }
                items(vehicles, key = { it.id }) { v ->
                    val statuses = reminders.filter { it.vehicle_id == v.id }.map { Reminders.status(it, v.current_mileage).status }
                    ListRow(onClick = { nav.navigate("vehicle/${v.id}") }) {
                        com.ratchetwrench.app.ui.VehicleAvatar(v, photos, 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(v.displayName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val sub = v.fullName().takeIf { it.isNotBlank() && it != v.displayName() }
                            if (sub != null) Text(sub, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                            Text(miles(v.current_mileage, v.mileage_unit), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        when {
                            "overdue" in statuses -> StatusPill("overdue")
                            "due_soon" in statuses -> StatusPill("due_soon")
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/** A tappable card row (min 64dp tall). */
@Composable
fun ListRow(onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
