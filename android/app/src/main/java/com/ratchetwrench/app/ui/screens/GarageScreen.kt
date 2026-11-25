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
        navigationIcon = { if (onBack != null) IconButton(onBack) { UiIcon("chevron_left") } },
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
    val recent by repo.db.entries().observeRecent(8).collectAsState(initial = emptyList())
    var refreshing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    val byId = vehicles.associateBy { it.id }

    suspend fun refresh() {
        refreshing = true
        syncError = try { withContext(Dispatchers.IO) { SyncEngine(ctx).run() }; null } catch (e: Exception) { "Offline — showing saved data. (${e.message})" }
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

    LaunchedEffect(Unit) {
        Updater.autoCheck(ctx) // app updates (at most every 6 hours)
        refresh()
    }

    val due = reminders.mapNotNull { r -> byId[r.vehicle_id]?.let { v -> Triple(r, v, Reminders.status(r, v.current_mileage)) } }
        .filter { it.third.status == "overdue" || it.third.status == "due_soon" }
        .sortedBy { if (it.third.status == "overdue") 0 else 1 }

    Scaffold(
        topBar = {
            TopBarImpl("Garage", null) {
                IconButton({ nav.navigate("settings") }) { UiIcon("settings") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("vehicle-edit/new") }, icon = { UiIcon("plus", tint = MaterialTheme.colorScheme.onPrimaryContainer) },
                text = { Text("Add vehicle") })
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refresh() } }, modifier = Modifier.padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { UpdateBanner() }
                syncError?.let { item { Text(it, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall) } }
                if (vehicles.isEmpty()) item {
                    SectionCard {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppLogo(64.dp)
                            Text("No vehicles yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Tap “Add vehicle” — you can scan the VIN barcode.", color = LocalExtra.current.muted)
                        }
                    }
                }
                if (due.isNotEmpty()) {
                    item { SectionTitle("Coming up") }
                    items(due.take(6), key = { "due-" + it.first.id }) { (r, v, st) ->
                        ListRow(onClick = { nav.navigate("vehicle/${v.id}") }) {
                            TypeBadge(r.type_key, "neutral")
                            Column(Modifier.weight(1f)) {
                                Text(r.title.ifBlank { Catalog.typeLabel(ctx, r.type_key) }, fontWeight = FontWeight.Bold)
                                Text("${v.displayName()} · ${st.describe(v.mileage_unit)}", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            StatusPill(st.status)
                        }
                    }
                }
                if (vehicles.isNotEmpty()) item { SectionTitle("Your vehicles") }
                items(vehicles, key = { it.id }) { v ->
                    val dueCount = reminders.count { it.vehicle_id == v.id && Reminders.status(it, v.current_mileage).status in setOf("overdue", "due_soon") }
                    ListRow(onClick = { nav.navigate("vehicle/${v.id}") }) {
                        TypeBadge(if (v.kind == "motorcycle") "chain_lube" else "detailing", "neutral", 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(v.displayName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(v.fullName().ifBlank { if (v.kind == "motorcycle") "Motorcycle" else "Car" }, color = LocalExtra.current.muted)
                            Text(miles(v.current_mileage, v.mileage_unit), fontWeight = FontWeight.SemiBold)
                        }
                        if (dueCount > 0) StatusPill("due_soon") else StatusPill("ok")
                    }
                }
                if (recent.isNotEmpty()) {
                    item { SectionTitle("Recent work") }
                    items(recent, key = { "e-" + it.id }) { e ->
                        ListRow(onClick = { nav.navigate("entry/${e.id}") }) {
                            TypeBadge(e.type_key, e.kind)
                            Column(Modifier.weight(1f)) {
                                Text(e.custom_type.ifBlank { Catalog.typeLabel(ctx, e.type_key) }, fontWeight = FontWeight.Bold)
                                Text("${byId[e.vehicle_id]?.displayName() ?: ""} · ${prettyDate(e.date)}", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) { KindPill(e.kind); Text(money(e.cost_cents, repo.store.currency), style = MaterialTheme.typography.bodySmall) }
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
