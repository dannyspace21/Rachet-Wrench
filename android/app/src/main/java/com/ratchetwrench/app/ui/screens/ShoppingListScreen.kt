// ShoppingListScreen.kt — the Shopping List: everything to buy before doing
// the work, from reminders ("Add to shopping list"), saved parts, or typed in.
//
// Each line: description, quantity, OEM part number, spec, notes, vehicle and
// the reminder it's for.  Lines are synced records (like parts), so the list
// is the same on the phone and the computer.
//
// Deliberately NOT here: a cart or payment ("Search" only opens the
// retailer's own app/site); ticking a line off never marks maintenance done
// and never creates an expense — the job is recorded in the history as usual.
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.ShoppingItem
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.ShopContext
import com.ratchetwrench.app.domain.ShopItem
import com.ratchetwrench.app.domain.Shopping
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.ShopIconButton
import com.ratchetwrench.app.ui.ShoppingDialog
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.TypeBadge
import com.ratchetwrench.app.ui.UiIcon
import com.ratchetwrench.app.ui.onMain
import com.ratchetwrench.app.ui.shoppingOn
import kotlinx.coroutines.launch

private const val ALL = "all"
private const val GENERAL = "general"

@Composable
fun ShoppingListScreen(nav: NavHostController) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by repo.db.shopping().observeAll().collectAsState(initial = null)
    val vehicles by repo.db.vehicles().observeAll().collectAsState(initial = emptyList())
    val reminders by repo.db.reminders().observeAll().collectAsState(initial = emptyList())
    val byId = vehicles.associateBy { it.id }
    val remById = reminders.associateBy { it.id }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var searching by remember { mutableStateOf<Pair<String, List<ShopItem>>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shopOn = shoppingOn()
    val list = items ?: return
    // Lines for a deleted vehicle are hidden (they're removed with it).
    val live = list.filter { it.vehicle_id == null || byId.containsKey(it.vehicle_id) }
    val shown = live.filter { filter == ALL || (if (filter == GENERAL) it.vehicle_id == null else it.vehicle_id == filter) }
    val bought = live.count { it.purchased }

    Scaffold(
        topBar = {
            TopBar("Shopping Cart", onBack = { nav.popBackStack() }) {
                if (bought > 0) TextButton({ confirmClear = true }) { Text("Remove bought") }
            }
        },
        floatingActionButton = {
            val target = if (filter == ALL || filter == GENERAL) "none" else filter
            ExtendedFloatingActionButton(onClick = { nav.navigate("shopping-item/new?vehicle=$target") },
                modifier = Modifier.semantics { contentDescription = "Add item" },
                icon = { UiIcon("plus", tint = MaterialTheme.colorScheme.onPrimaryContainer) }, text = { Text("Add item") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Everything you need before doing the work.", color = LocalExtra.current.muted)
            }
            if (vehicles.isNotEmpty() && live.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == ALL, { filter = ALL }, label = { Text("All") })
                    vehicles.forEach { v -> FilterChip(filter == v.id, { filter = v.id }, label = { Text(v.displayName()) }) }
                    FilterChip(filter == GENERAL, { filter = GENERAL }, label = { Text("No vehicle") })
                }
            }
            if (live.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiIcon("cart", Modifier.size(40.dp), LocalExtra.current.muted)
                    Text("Your shopping list is empty", fontWeight = FontWeight.Bold)
                    Text("Open a reminder and tap “Add to shopping list”, add a saved part from a vehicle's Parts tab, or tap Add item.",
                        color = LocalExtra.current.muted)
                }
            } else if (shown.isEmpty()) item { Text("Nothing on the list here.", color = LocalExtra.current.muted) }
            // Grouped by vehicle; lines still to buy come first (database order).
            shown.groupBy { it.vehicle_id }.forEach { (vid, rows) ->
                item(key = "h-$vid") {
                    Text(vid?.let { byId[it]?.displayName() } ?: "Not for a vehicle", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(rows, key = { it.id }) { i ->
                    ShoppingRow(i, remById[i.reminder_id], shopOn,
                        onToggle = { scope.launch { repo.setPurchased(i.id, it) } },
                        onOpen = { nav.navigate("shopping-item/${i.id}") },
                        onSearch = {
                            val v = i.vehicle_id?.let { byId[it] }
                            val (q, exact) = Shopping.listItemQuery(i.description, i.part_number, i.spec,
                                v?.let { ShopContext(it.year, it.make, it.model, emptyMap(), emptyList()) })
                            searching = "Find Products — ${i.description}" to listOf(ShopItem(i.description, q, exact, null))
                        },
                        onRemove = { scope.launch { repo.deleteShoppingItem(i.id) } })
                }
            }
            item {
                Text("Ticking an item off doesn't mark the maintenance as done or add an expense — record the job in the vehicle's history when you do it.",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    searching?.let { (title, its) -> ShoppingDialog(title, its) { searching = null } }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Remove bought items?") },
        text = { Text("$bought item${if (bought == 1) "" else "s"} you ticked off will be removed from the list.") },
        confirmButton = { TextButton({ confirmClear = false; scope.launch { repo.removeBought() } }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun ShoppingRow(i: ShoppingItem, rem: Reminder?, shopOn: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit,
                        onSearch: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    ListRow(onClick = onOpen) {
        Checkbox(i.purchased, onToggle, modifier = Modifier.semantics { contentDescription = "Bought ${i.description}" })
        Column(Modifier.weight(1f)) {
            Text((if (i.quantity > 1) "${i.quantity} × " else "") + i.description, fontWeight = FontWeight.Bold,
                textDecoration = if (i.purchased) TextDecoration.LineThrough else null,
                color = if (i.purchased) LocalExtra.current.muted else MaterialTheme.colorScheme.onSurface)
            if (i.part_number.isNotBlank()) Text("OEM ${i.part_number}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            if (i.spec.isNotBlank()) Text(i.spec, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            rem?.let { Text("For: " + it.title.ifBlank { Catalog.typeLabel(ctx, it.type_key) }, color = LocalExtra.current.muted,
                style = MaterialTheme.typography.bodySmall) }
            if (i.notes.isNotBlank()) Text(i.notes, color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (shopOn) ShopIconButton("Find Products for ${i.description}", onSearch)
        IconButton(onRemove) { UiIcon("trash", Modifier.size(20.dp), LocalExtra.current.muted, description = "Remove ${i.description}") }
    }
}

/** A labelled drop-down (vehicle, reminder). */
@Composable
private fun Picker(label: String, selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second ?: "", Modifier.weight(1f))
                UiIcon("chevron_down", Modifier.size(18.dp))
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (k, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(k); open = false }) }
            }
        }
    }
}

/** Add or edit one line (a full screen, like the other forms). */
@Composable
fun ShoppingItemScreen(nav: NavHostController, id: String?, vehicleId: String?) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicles by repo.db.vehicles().observeAll().collectAsState(initial = emptyList())
    val reminders by repo.db.reminders().observeAll().collectAsState(initial = emptyList())
    var loaded by remember { mutableStateOf(id == null) }
    var original by remember { mutableStateOf<ShoppingItem?>(null) }
    var desc by rememberSaveable { mutableStateOf("") }
    var pn by rememberSaveable { mutableStateOf("") }
    var spec by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("1") }
    var notes by rememberSaveable { mutableStateOf("") }
    var vehicle by rememberSaveable { mutableStateOf(vehicleId.orEmpty()) }
    var reminder by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != null && !loaded) {
            repo.db.shopping().get(id)?.let { i ->
                original = i; desc = i.description; pn = i.part_number; spec = i.spec; qty = i.quantity.toString()
                notes = i.notes; vehicle = i.vehicle_id.orEmpty(); reminder = i.reminder_id.orEmpty()
            }
            loaded = true
        }
    }
    if (!loaded) return
    val vehicleReminders = reminders.filter { it.vehicle_id == vehicle }
    Scaffold(topBar = {
        TopBar(if (id == null) "Add to shopping list" else "Edit item", onBack = { nav.popBackStack() }) {
            if (id != null) IconButton({ confirmDelete = true }) { UiIcon("trash", description = "Remove item") }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Item", desc, { desc = it.take(200); error = null }, hint = "e.g. Oil filter")
            Field("Quantity", qty, { qty = it.filter(Char::isDigit).take(3) }, numeric = true)
            Field("OEM part number (optional)", pn, { pn = it.take(64) })
            Field("Specification (optional)", spec, { spec = it.take(200) }, hint = "e.g. 0W-20 full synthetic")
            Picker("Vehicle", vehicle, listOf("" to "Not for a vehicle") + vehicles.map { it.id to it.displayName() }) {
                vehicle = it
                if (reminders.none { r -> r.id == reminder && r.vehicle_id == it }) reminder = ""
            }
            if (vehicleReminders.isNotEmpty()) Picker("For reminder (optional)", reminder,
                listOf("" to "None") + vehicleReminders.map { it.id to it.title.ifBlank { Catalog.typeLabel(ctx, it.type_key) } }) { reminder = it }
            Field("Notes (optional)", notes, { notes = it.take(5000) }, singleLine = false)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save item", "check", Modifier.fillMaxWidth()) {
                if (desc.isBlank()) { error = "Describe the item."; return@PrimaryButton }
                val base = original ?: ShoppingItem()
                scope.launch {
                    repo.saveShoppingItem(base.copy(description = desc, part_number = pn, spec = spec,
                        quantity = qty.toIntOrNull() ?: 1, notes = notes, vehicle_id = vehicle.ifBlank { null },
                        reminder_id = reminder.ifBlank { null }))
                    onMain { nav.popBackStack() }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Remove from the list?") },
        confirmButton = { TextButton({
            confirmDelete = false
            scope.launch { id?.let { repo.deleteShoppingItem(it) }; onMain { nav.popBackStack() } }
        }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

/** The lines a reminder adds to the list (its supplies, with known part numbers/specs). */
fun reminderLines(v: Vehicle, r: Reminder, name: String, shopCtx: ShopContext, catalog: com.ratchetwrench.app.domain.CatalogData) =
    Shopping.listItemsFor(r.type_key, name, shopCtx, catalog).map {
        ShoppingItem(vehicle_id = v.id, reminder_id = r.id, type_key = it.type_key, description = it.description,
            part_number = it.part_number, spec = it.spec)
    }
