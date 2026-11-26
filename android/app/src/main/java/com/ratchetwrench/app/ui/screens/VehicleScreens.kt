// VehicleScreens.kt — one vehicle (tabs) and the add/edit vehicle form.
package com.ratchetwrench.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.JsonFormat
import com.ratchetwrench.app.data.Nhtsa
import com.ratchetwrench.app.data.Part
import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.Spec
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.report.LocalReport
import com.ratchetwrench.app.domain.Reminders
import com.ratchetwrench.app.domain.Vin
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.KindPill
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.Pill
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.Segmented
import com.ratchetwrench.app.ui.StatusPill
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.TypeBadge
import com.ratchetwrench.app.ui.UiIcon
import com.ratchetwrench.app.ui.miles
import com.ratchetwrench.app.ui.money
import com.ratchetwrench.app.ui.prettyDate
import com.ratchetwrench.app.ui.takeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

// In the order you use them: what's due, what fits, what was done, the specs.
// (Mileage is updated with the "Mileage" button; no separate tab.)
private val TABS = listOf("Reminders", "Parts", "History", "Metrics", "Specs")

/** Opens a web link in the phone's browser. */
fun openLink(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Download the history report PDF into Downloads and open it. */
suspend fun downloadReport(context: Context, v: Vehicle): String = withContext(Dispatchers.IO) {
    val bytes = Api(com.ratchetwrench.app.data.SecureStore(context)).bytes("/api/vehicles/${v.id}/report.pdf")
    val name = (v.fullName().ifBlank { v.displayName() }).replace(Regex("[^A-Za-z0-9]+"), "-") + "-history.pdf"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save the file.")
    resolver.openOutputStream(uri)!!.use { it.write(bytes) }
    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    withContext(Dispatchers.Main) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    "Saved to Downloads: $name"
}

@Composable
fun VehicleScreen(nav: NavHostController, id: String) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val vehicle by repo.db.vehicles().observe(id).collectAsState(initial = null)
    var tab by rememberSaveable { mutableStateOf(0) }
    var showMileage by remember { mutableStateOf(false) }
    val photos by repo.db.attachments().observeVehiclePhotos().collectAsState(initial = emptyList())
    val v = vehicle ?: return
    val links = Catalog.links(ctx, v.make, v.kind)
    val ownersUrl = v.owners_url.ifBlank { links.owners ?: "" }

    Scaffold(
        topBar = { TopBar(v.displayName(), onBack = { nav.popBackStack() }) { IconButton({ nav.navigate("vehicle-edit/$id") }) { UiIcon("edit", description = "Edit vehicle") } } },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Compact header: photo, mileage and name on one line, small
            // action chips underneath, then what's coming up for THIS vehicle.
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.ratchetwrench.app.ui.VehicleAvatar(v, photos, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(miles(v.current_mileage, v.mileage_unit), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        // The name is already in the top bar: only show what adds something.
                        val sub = listOfNotNull(v.fullName().takeIf { it.isNotBlank() && it != v.displayName() },
                            v.vin.ifBlank { null }?.let { "VIN $it" }).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    androidx.compose.material3.Button({ nav.navigate("entry-edit/$id/new") },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        UiIcon("plus", androidx.compose.ui.Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                        Text("Add entry", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionChip("Mileage", "gauge") { showMileage = true }
                    ActionChip("Door sticker", "sticker") { nav.navigate("sticker/$id") }
                    ActionChip("Report PDF", "download") {
                        scope.launch {
                            snack.showSnackbar(try {
                                // Connected: the computer/server builds it (with all photos).
                                // Phone only: build it right here on the phone.
                                if (repo.store.mode == "server") downloadReport(ctx, v)
                                else LocalReport.createAndSave(ctx, v, repo.store.currency)
                            } catch (e: Exception) { "Could not create the report: ${e.message}" })
                        }
                    }
                    // (The manufacturer's parts site is on the Parts tab.)
                    if (ownersUrl.isNotBlank()) ActionChip("Owner portal", "book") { openLink(ctx, ownersUrl) }
                }
                // (No "Coming up" card: Reminders is the first tab and lists the same things.)
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = MaterialTheme.colorScheme.background) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.SemiBold) }) }
            }
            when (tab) {
                0 -> RemindersTab(nav, v)
                1 -> PartsTab(v)
                2 -> HistoryTab(nav, v)
                3 -> MetricsTab(nav, v)
                else -> SpecsTab(nav, v)
            }
        }
    }
    if (showMileage) {
        var text by remember { mutableStateOf(v.current_mileage?.toString() ?: "") }
        AlertDialog(onDismissRequest = { showMileage = false }, title = { Text("Update mileage") },
            text = { Field("Current mileage (${v.mileage_unit})", text, { text = it }, numeric = true) },
            confirmButton = { TextButton({
                text.filter { it.isDigit() }.toIntOrNull()?.let { m -> scope.launch { repo.addOdometer(v.id, LocalDate.now().toString(), m) } }
                showMileage = false
            }) { Text("Save") } },
            dismissButton = { TextButton({ showMileage = false }) { Text("Cancel") } })
    }
}

@Composable
private fun HistoryTab(nav: NavHostController, v: Vehicle) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val entries by repo.db.entries().observeFor(v.id).collectAsState(initial = emptyList())
    val recalls by repo.db.recalls().observeFor(v.id).collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf("all") }
    val scope = rememberCoroutineScope()
    val shown = entries.filter { filter == "all" || it.kind == filter }
    val recallRows = com.ratchetwrench.app.domain.Recalls.sorted(recalls, entries, LocalDate.now())
    val openRecalls = recallRows.count { it.second.status == "open" }
    var recallStatus by remember { mutableStateOf<String?>(null) }
    fun refreshRecalls() {
        recallStatus = "Checking NHTSA for recalls…"
        scope.launch {
            val msg = runCatching { repo.refreshRecalls(v) }.fold(
                { n -> if (n > 0) "$n new recall${if (n == 1) "" else "s"} found." else "Checked NHTSA just now." },
                { it.message ?: "Couldn't reach NHTSA." })
            com.ratchetwrench.app.ui.onMain { recallStatus = msg }
        }
    }
    // Look up recalls once when the vehicle is opened (only year/make/model are sent).
    androidx.compose.runtime.LaunchedEffect(v.id, v.year, v.make, v.model) { if (v.year != null && v.make.isNotBlank() && v.model.isNotBlank()) refreshRecalls() }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                Segmented(listOf("all" to "All", "maintenance" to "Maintenance", "repair" to "Repairs",
                    "recalls" to if (openRecalls > 0) "Recalls ($openRecalls)" else "Recalls"), filter) { filter = it }
            }
        }
        if (filter == "recalls") { recallItems(nav, v, recallRows, repo, recallStatus) { refreshRecalls() }; return@LazyColumn }
        item {
            val sum = { k: String -> entries.filter { it.kind == k }.sumOf { it.cost_cents ?: 0 } }
            Text("Maintenance ${money(sum("maintenance"), repo.store.currency)} · Repairs ${money(sum("repair"), repo.store.currency)}",
                color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (shown.isEmpty()) item { Text("No records yet. Tap “Add entry”.", color = LocalExtra.current.muted) }
        items(shown, key = { it.id }) { e ->
            ListRow(onClick = { nav.navigate("entry/${e.id}") }) {
                TypeBadge(e.type_key, e.kind)
                Column(Modifier.weight(1f)) {
                    Text(e.custom_type.ifBlank { if (e.type_key == "custom" && e.description.isNotBlank()) e.description else Catalog.typeLabel(ctx, e.type_key) }, fontWeight = FontWeight.Bold)
                    if (e.description.isNotBlank()) Text(e.description, maxLines = 2)
                    Text(listOfNotNull(prettyDate(e.date), e.mileage?.let { miles(it, v.mileage_unit) }, if (e.performed_by == "shop") e.shop_name.ifBlank { "Shop" } else "DIY")
                        .joinToString(" · "), color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) { KindPill(e.kind); Text(money(e.cost_cents, repo.store.currency), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Colour + text for a spec's source label. */
@Composable
fun SourcePill(source: String) {
    val x = LocalExtra.current
    // Labels come from the shared catalog, so they match the web app.
    val label = Catalog.get(LocalContext.current).spec_sources[source] ?: "Unverified"
    when (source) {
        "vin", "provider", "carfax" -> Pill(label, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
        "sticker" -> Pill(label, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        "manual" -> Pill(label, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        "user" -> Pill(label, x.okSoft, x.ok)
        else -> Pill(label, x.warnSoft, x.warn)
    }
}

@Composable
private fun SpecsTab(nav: NavHostController, v: Vehicle) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val specs by repo.db.specs().observeFor(v.id).collectAsState(initial = emptyList())
    val byKey = specs.associateBy { it.key }
    var editing by remember { mutableStateOf<Pair<String, Spec?>?>(null) }
    val carOnly = setOf("power_steering_fluid_type", "differential_fluid_type", "seating_capacity", "wiper_size_driver", "wiper_size_passenger", "tire_size_spare", "tire_pressure_spare")
    val bikeOnly = setOf("fork_oil_type", "chain_size", "sprocket_front", "sprocket_rear", "chain_slack")
    val defs = Catalog.get(ctx).specs.filter { byKey.containsKey(it.key) || (if (v.kind == "motorcycle") it.key !in carOnly else it.key !in bikeOnly) }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Specs are never guessed. Each value shows where it came from. Blank means unknown — tap a row to confirm or edit.",
                color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("Owner's manual", "book") { nav.navigate("manual/${v.id}") }
                ActionChip("Door sticker", "sticker") { nav.navigate("sticker/${v.id}") }
            }
        }
        // Paint code known but no colour name: suggest one from the curated table.
        val code = byKey["paint_code"]?.value
        val colour = if (!code.isNullOrBlank() && byKey["paint_color"]?.value.isNullOrBlank())
            com.ratchetwrench.app.domain.Paint.name(Catalog.get(ctx).paint_codes, v.make, code) else null
        if (colour != null) item {
            SectionCard {
                Text("Paint code $code on a ${v.make} is $colour.")
                SecondaryButton("Use it", "check") { scope.launch { repo.saveSpec(v.id, "paint_color", colour, "user") } }
            }
        }
        defs.groupBy { it.group }.forEach { (group, list) ->
            item { Text(group.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, modifier = Modifier.padding(top = 10.dp)) }
            items(list, key = { it.key }) { d ->
                val s = byKey[d.key]
                ListRow(onClick = { editing = d.key to s }) {
                    Column(Modifier.weight(1f)) {
                        Text(d.label, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                        if (s?.value.isNullOrBlank()) Text("Unknown", fontStyle = FontStyle.Italic, color = LocalExtra.current.muted)
                        else Text(s.value, fontWeight = FontWeight.SemiBold)
                    }
                    if (s != null && s.value.isNotBlank()) SourcePill(s.source)
                }
            }
        }
    }
    editing?.let { (key, s) ->
        var value by remember(key) { mutableStateOf(s?.value ?: "") }
        var unverified by remember(key) { mutableStateOf(s?.source == "unverified") }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(Catalog.specLabel(ctx, key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Value", value, { value = it }, hint = Catalog.get(ctx).specs.firstOrNull { it.key == key }?.hint?.ifBlank { null })
                    Segmented(listOf("user" to "Entered by me", "unverified" to "Unverified"), if (unverified) "unverified" else "user") { unverified = it == "unverified" }
                }
            },
            confirmButton = { TextButton({
                scope.launch { if (value.isBlank() && s != null) repo.deleteSpec(s.id) else if (value.isNotBlank()) repo.saveSpec(v.id, key, value.trim(), if (unverified) "unverified" else "user") }
                editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RemindersTab(nav: NavHostController, v: Vehicle) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rems by repo.db.reminders().observeFor(v.id).collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    val shopCtx = com.ratchetwrench.app.ui.rememberShopContext(v)
    val shopOn = com.ratchetwrench.app.ui.shoppingOn()
    var shopping by remember { mutableStateOf<Pair<String, List<com.ratchetwrench.app.domain.ShopItem>>?>(null) }
    fun shopItems(r: Reminder) = if (shopOn) com.ratchetwrench.app.domain.Shopping.itemsFor(r.type_key, shopCtx, Catalog.get(ctx)) else emptyList()
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PrimaryButton("Add reminder", "plus") { creating = true } }
        if (rems.isEmpty()) item { Text("Example: oil change every 5,000 ${v.mileage_unit} or 6 months, whichever comes first.", color = LocalExtra.current.muted) }
        // Overdue first, then due soon, then everything in good standing.
        items(Reminders.byUrgency(rems, v.current_mileage), key = { it.id }) { r ->
            val st = Reminders.status(r, v.current_mileage)
            // Tap: the reminder's page (Mark as done, Edit schedule, Find supplies).
            ListRow(onClick = { nav.navigate("reminder/${r.id}") }) {
                TypeBadge(r.type_key, "neutral")
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifBlank { Catalog.typeLabel(ctx, r.type_key) }, fontWeight = FontWeight.Bold)
                    Text(st.describe(v.mileage_unit), color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                    Text(listOfNotNull(r.interval_miles?.let { "every %,d %s".format(it, v.mileage_unit) }, r.interval_months?.let { "every $it months" }).joinToString(" or "),
                        color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                }
                // Smart Shopping: only reminders with something to buy get the button.
                val items = shopItems(r)
                val name = r.title.ifBlank { Catalog.typeLabel(ctx, r.type_key) }
                if (items.isNotEmpty()) com.ratchetwrench.app.ui.ShopIconButton("Find supplies for $name") { shopping = "Find supplies — $name" to items }
                StatusPill(st.status)
            }
        }
    }
    if (creating) ReminderEditor(v, null) { creating = false }
    shopping?.let { (title, items) -> com.ratchetwrench.app.ui.ShoppingDialog(title, items) { shopping = null } }
}

/**
 * Add or edit a reminder's schedule ("every N miles or M months") and when it
 * was last done.  Used for "Add reminder" and by the reminder page's
 * "Edit schedule".
 */
@Composable
fun ReminderEditor(v: Vehicle, r: Reminder?, onClose: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var type by remember(r) { mutableStateOf(r?.type_key ?: "oil_change") }
    var miles by remember(r) { mutableStateOf(r?.interval_miles?.toString() ?: "") }
    var months by remember(r) { mutableStateOf(r?.interval_months?.toString() ?: "") }
    var lastDate by remember(r) { mutableStateOf(r?.last_done_date ?: "") }
    var lastMiles by remember(r) { mutableStateOf(r?.last_done_mileage?.toString() ?: "") }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (r == null) "New reminder" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeDropdown(v.kind, type) { type = it }
                Field("Every … ${v.mileage_unit}", miles, { miles = it }, numeric = true)
                Field("Every … months", months, { months = it }, numeric = true)
                Field("Last done (YYYY-MM-DD)", lastDate, { lastDate = it })
                Field("Last done (${v.mileage_unit})", lastMiles, { lastMiles = it }, numeric = true)
            }
        },
        confirmButton = { TextButton({
            val date = lastDate.trim().takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
            val base = r ?: Reminder(vehicle_id = v.id)
            scope.launch {
                repo.saveReminder(base.copy(type_key = type, interval_miles = miles.filter { it.isDigit() }.toIntOrNull(),
                    interval_months = months.filter { it.isDigit() }.toIntOrNull(), last_done_date = date, last_done_mileage = lastMiles.filter { it.isDigit() }.toIntOrNull()))
            }
            onClose()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

/** A small, low-profile action button (used in the vehicle header). */
@Composable
private fun ActionChip(text: String, icon: String, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(onClick = onClick, label = { Text(text) },
        leadingIcon = { UiIcon(icon, Modifier.size(18.dp)) })
}

/** A simple type chooser (dropdown) used in dialogs. */
@Composable
fun TypeDropdown(kind: String, selected: String, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column {
        SecondaryButton(Catalog.typeLabel(ctx, selected), "chevron_down", Modifier.fillMaxWidth()) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            Catalog.typesFor(ctx, kind).forEach { t ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(t.label) }, leadingIcon = { TypeBadge(t.key, "neutral", 32.dp) },
                    onClick = { onSelect(t.key); open = false })
            }
        }
    }
}

@Composable
private fun PartsTab(v: Vehicle) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val parts by repo.db.parts().observeFor(v.id).collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Part?>(null) }
    val partsUrl = v.parts_url.ifBlank { Catalog.links(ctx, v.make, v.kind).parts ?: "" }
    val shopCtx = com.ratchetwrench.app.ui.rememberShopContext(v)
    val shopOn = com.ratchetwrench.app.ui.shoppingOn()
    var shopping by remember { mutableStateOf<Pair<String, List<com.ratchetwrench.app.domain.ShopItem>>?>(null) }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Add part", "plus") { editing = Part(vehicle_id = v.id, type_key = "oil_filter") }
                if (partsUrl.isNotBlank()) SecondaryButton("${v.make.ifBlank { "Official" }} parts", "external") { openLink(ctx, partsUrl) }
            }
        }
        items(parts, key = { it.id }) { p ->
            ListRow(onClick = { editing = p }) {
                TypeBadge(p.type_key, "neutral")
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { Catalog.typeLabel(ctx, p.type_key) }, fontWeight = FontWeight.Bold)
                    Text(p.part_number.ifBlank { "No part number yet" }, fontWeight = FontWeight.SemiBold)
                    if (p.notes.isNotBlank()) Text(p.notes, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                }
                // Smart Shopping: search this part (its exact part number when saved).
                if (shopOn) {
                    val name = p.name.ifBlank { Catalog.typeLabel(ctx, p.type_key) }
                    com.ratchetwrench.app.ui.ShopIconButton("Shop for $name") {
                        val (q, exact) = com.ratchetwrench.app.domain.Shopping.partQuery(p.part_number, p.name, Catalog.typeLabel(ctx, p.type_key), shopCtx)
                        shopping = "Shop — $name" to listOf(com.ratchetwrench.app.domain.ShopItem(name, q, exact, null))
                    }
                }
                // Put it on the shopping list (once).
                val partName = p.name.ifBlank { Catalog.typeLabel(ctx, p.type_key) }
                IconButton({
                    scope.launch {
                        val added = repo.addToShoppingList(listOf(com.ratchetwrench.app.data.ShoppingItem(vehicle_id = v.id, part_id = p.id,
                            type_key = p.type_key, description = partName, part_number = p.part_number)))
                        com.ratchetwrench.app.ui.onMain {
                            android.widget.Toast.makeText(ctx, if (added == 0) "Already on your shopping list" else "Added to the shopping list",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { UiIcon("list", Modifier.size(22.dp), MaterialTheme.colorScheme.primary, description = "Add $partName to the shopping list") }
            }
        }
    }
    shopping?.let { (title, items) -> com.ratchetwrench.app.ui.ShoppingDialog(title, items) { shopping = null } }
    editing?.let { p ->
        var type by remember(p.id) { mutableStateOf(p.type_key) }
        var name by remember(p.id) { mutableStateOf(p.name) }
        var number by remember(p.id) { mutableStateOf(p.part_number) }
        var brand by remember(p.id) { mutableStateOf(p.brand) }
        var notes by remember(p.id) { mutableStateOf(p.notes) }
        val exists = parts.any { it.id == p.id }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (exists) "Edit part" else "Add part") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeDropdown(v.kind, type) { type = it }
                    Field("Name", name, { name = it })
                    Field("OEM part number", number, { number = it })
                    Field("Brand", brand, { brand = it })
                    Field("Notes", notes, { notes = it }, singleLine = false)
                }
            },
            confirmButton = { TextButton({ scope.launch { repo.savePart(p.copy(type_key = type, name = name, part_number = number, brand = brand, notes = notes)) }; editing = null }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (exists) TextButton({ scope.launch { repo.deletePart(p.id) }; editing = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton({ editing = null }) { Text("Cancel") }
                }
            })
    }
}

// ===========================================================================
// Add / edit vehicle, with VIN barcode scanning and VIN decoding
// ===========================================================================
@Composable
fun VehicleEditScreen(nav: NavHostController, id: String?) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    // Saveable: going to the barcode scanner or camera and back must NEVER
    // reload the saved vehicle over what you typed, or drop VIN-decoded specs.
    var loaded by rememberSaveable { mutableStateOf(id == null) }
    var base by remember { mutableStateOf(Vehicle(mileage_unit = repo.store.distanceUnit)) }
    var kind by rememberSaveable { mutableStateOf("car") }
    var vin by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var make by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var trim by rememberSaveable { mutableStateOf("") }
    var nick by rememberSaveable { mutableStateOf("") }
    var mileage by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(repo.store.distanceUnit) }
    var decodedRaw by rememberSaveable { mutableStateOf(ArrayList<String>()) }  // "key\u0001value"
    val decodedSpecs: List<Pair<String, String>> = decodedRaw.map { it.substringBefore('\u0001') to it.substringAfter('\u0001') }
    fun setDecoded(list: List<Pair<String, String>>) { decodedRaw = ArrayList(list.map { "${it.first}\u0001${it.second}" }) }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { withContext(Dispatchers.IO) { copyIntoApp(ctx, uri) }?.let { photoPath = it } }
    }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null && !loaded) {
            repo.db.vehicles().get(id)?.let { v ->
                base = v; kind = v.kind; vin = v.vin; year = v.year?.toString() ?: ""; make = v.make; model = v.model; trim = v.trim
                nick = v.nickname; mileage = v.current_mileage?.toString() ?: ""; unit = v.mileage_unit
            }
            loaded = true
        }
    }
    // A VIN coming back from the barcode scanner, or a photo from the camera.
    nav.takeResult<String>("vin")?.let { vin = it }
    nav.takeResult<ArrayList<String>>("photos")?.firstOrNull()?.let { photoPath = it }
    // Model suggestions for the chosen make (and year), straight from NHTSA.
    LaunchedEffect(make, year, kind) {
        kotlinx.coroutines.delay(500)
        models = if (make.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            runCatching { Nhtsa.models(make, year.toIntOrNull()?.takeIf { it in 1900..2100 }, kind) }.getOrDefault(emptyList())
        }
    }

    Scaffold(topBar = { TopBar(if (id == null) "Add vehicle" else "Edit vehicle", onBack = { nav.popBackStack() }) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf("car" to "Car", "motorcycle" to "Motorcycle"), kind) { kind = it }
            Field("VIN", vin, { vin = it.uppercase().take(17) }, hint = if (vin.length == 17 && !Vin.isValid(Vin.normalize(vin))) "Check digit doesn't match — double-check (normal for some non-US vehicles)" else "17 characters")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Scan barcode", "barcode") { nav.navigate("scan-vin") }
                SecondaryButton("Decode VIN", "search") {
                    scope.launch {
                        try {
                            // Connected: ask the computer/server first (it also checks a paid spec
                            // service if one is set up).  If the computer can't be reached (away from
                            // home, computer off), decode directly with NHTSA instead (issue #3).
                            val res = if (repo.store.mode != "server") null else withContext(Dispatchers.IO) {
                                runCatching {
                                    JsonFormat.parseToJsonElement(Api(repo.store).send("POST", "/api/vin/decode", JsonObject(mapOf("vin" to JsonPrimitive(vin))))).jsonObject
                                }.getOrElse { e -> if (e is com.ratchetwrench.app.data.ApiException && e.status in 400..499 && e.status != 401) throw e else null }
                            }
                            if (res != null) {
                                val veh = res["vehicle"]!!.jsonObject
                                veh["year"]?.jsonPrimitive?.content?.let { year = it }
                                veh["make"]?.jsonPrimitive?.content?.let { make = it }
                                veh["model"]?.jsonPrimitive?.content?.let { model = it }
                                veh["trim"]?.jsonPrimitive?.content?.let { trim = it }
                                veh["kind"]?.jsonPrimitive?.content?.let { kind = it }
                                setDecoded(res["specs"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content })
                            } else {
                                // Phone only, or the computer didn't answer: ask NHTSA directly (only the VIN is sent).
                                val r = withContext(Dispatchers.IO) { Nhtsa.decode(vin) }
                                r.year?.let { year = it.toString() }
                                r.make?.let { make = it }
                                r.model?.let { model = it }
                                r.trim?.let { trim = it }
                                r.kind?.let { kind = it }
                                setDecoded(r.specs.toList())
                                r.warning?.let { snack.showSnackbar(it) }
                            }
                            snack.showSnackbar("Filled from VIN. ${decodedSpecs.size} spec(s) will be saved as “From VIN”. Check them before saving.")
                        } catch (e: Exception) {
                            snack.showSnackbar(if (e is com.ratchetwrench.app.data.ApiException) "Couldn't decode this VIN: ${e.message}"
                                else "Couldn't reach the NHTSA VIN service — check the phone's internet connection. (${e.message})")
                        }
                    }
                }
            }
            Field("Year", year, { year = it.filter { c -> c.isDigit() }.take(4) }, numeric = true)
            com.ratchetwrench.app.ui.SuggestField("Make", make, { make = it }, Catalog.makeNames(ctx, kind))
            com.ratchetwrench.app.ui.SuggestField("Model", model, { model = it }, models,
                hint = if (make.isNotBlank() && models.isEmpty()) "Type the model (suggestions need the internet)" else null)
            Field("Trim", trim, { trim = it })
            Field("Nickname (optional)", nick, { nick = it })
            Field("Current mileage", mileage, { mileage = it.filter { c -> c.isDigit() } }, numeric = true)
            Segmented(listOf("mi" to "Miles", "km" to "Kilometers"), unit) { unit = it }
            // Vehicle photo: taking or choosing one never clears anything you typed.
            Text("Vehicle photo", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photoPath?.let { com.ratchetwrench.app.ui.PhotoThumb(com.ratchetwrench.app.data.Attachment(id = it, localPath = it), 64.dp) }
                SecondaryButton("Camera", "camera") { nav.navigate("capture") }
                SecondaryButton("Gallery", "image") {
                    pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            PrimaryButton("Save vehicle", "check", Modifier.fillMaxWidth(), enabled = make.isNotBlank() || nick.isNotBlank()) {
                scope.launch {
                    // Editing: start from the saved vehicle (never a blank one).
                    val start = if (id != null) repo.db.vehicles().get(id) ?: base else base
                    val v = start.copy(kind = kind, vin = Vin.normalize(vin).take(17), year = year.toIntOrNull(), make = make.trim(), model = model.trim(),
                        trim = trim.trim(), nickname = nick.trim(), current_mileage = mileage.toIntOrNull(), mileage_unit = unit)
                    repo.saveVehicle(v)
                    for ((k, value) in decodedSpecs) repo.saveSpec(v.id, k, value, "vin")
                    photoPath?.let { repo.addPhoto(v.id, null, "vehicle_photo", it, mimeFor(it), "Vehicle photo") }
                    com.ratchetwrench.app.ui.onMain {
                        nav.popBackStack()
                        if (id == null) nav.navigate("vehicle/${v.id}")
                    }
                }
            }
            if (id != null) SecondaryButton("Delete vehicle", "trash", Modifier.fillMaxWidth()) { confirmDelete = true }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete vehicle?") },
        text = { Text("This deletes the vehicle and all of its records and photos.") },
        confirmButton = { TextButton({ scope.launch { repo.deleteVehicle(id!!); com.ratchetwrench.app.ui.onMain { nav.popBackStack("garage", false) } } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
