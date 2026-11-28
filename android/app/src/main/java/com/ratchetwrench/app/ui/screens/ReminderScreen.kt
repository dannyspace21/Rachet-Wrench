// ReminderScreen.kt — one maintenance reminder.
//
// Tapping a reminder opens this page instead of jumping straight into
// editing its schedule.  The main action is "Mark as done": date (today)
// and mileage (the vehicle's current mileage) are filled in, and "Also add
// it to the history" is on, so one tap
//   * sets the reminder's last-done date AND mileage (so a "whichever comes
//     first" reminder can't stay overdue on the mileage side),
//   * adds the matching record to the vehicle's history,
//   * and the status (Overdue → OK) updates right away.
// "Edit schedule" changes the frequency; "Find supplies" is Smart Shopping.
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.Reminders
import com.ratchetwrench.app.domain.ShopItem
import com.ratchetwrench.app.domain.Shopping
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.ShoppingDialog
import com.ratchetwrench.app.ui.StatusPill
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.TypeBadge
import com.ratchetwrench.app.ui.UiIcon
import com.ratchetwrench.app.ui.miles
import com.ratchetwrench.app.ui.prettyDate
import com.ratchetwrench.app.ui.rememberShopContext
import com.ratchetwrench.app.ui.shoppingOn
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ReminderScreen(nav: NavHostController, id: String) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reminder by repo.db.reminders().observe(id).collectAsState(initial = null)
    val r = reminder?.takeIf { it.deleted_at == null } ?: return
    val vehicle by repo.db.vehicles().observe(r.vehicle_id).collectAsState(initial = null)
    val v = vehicle ?: return
    val name = r.title.ifBlank { Catalog.typeLabel(ctx, r.type_key) }
    val st = Reminders.status(r, v.current_mileage)

    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var mileage by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(v.current_mileage) { if (mileage.isBlank()) mileage = v.current_mileage?.toString() ?: "" }
    var addToHistory by rememberSaveable { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var listMessage by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var shopping by remember { mutableStateOf<List<ShopItem>?>(null) }
    val shopCtx = rememberShopContext(v)
    val shopItems = if (shoppingOn()) Shopping.itemsFor(r.type_key, shopCtx, Catalog.get(ctx)) else emptyList()

    Scaffold(topBar = {
        TopBar(name, onBack = { nav.popBackStack() }) {
            IconButton({ editing = true }) { UiIcon("edit", description = "Edit schedule") }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Where it stands.
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TypeBadge(r.type_key, "neutral", 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(v.displayName(), color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    StatusPill(st.status)
                }
                Text(st.describe(v.mileage_unit))
                val every = listOfNotNull(r.interval_miles?.let { "every %,d %s".format(it, v.mileage_unit) }, r.interval_months?.let { "every $it months" })
                if (every.isNotEmpty()) Text(every.joinToString(" or ") + if (every.size == 2) ", whichever comes first" else "",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                Text("Last done: " + (r.last_done_date?.let { prettyDate(it) } ?: "not yet") +
                    (r.last_done_mileage?.let { " · " + miles(it, v.mileage_unit) } ?: ""),
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            }

            // The main action.
            SectionCard {
                Text("Did it? Log it as done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Field("Done on (YYYY-MM-DD)", date, { date = it.trim(); error = null })
                Field("Mileage (${v.mileage_unit})", mileage, { mileage = it.filter { c -> c.isDigit() }; error = null }, numeric = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Also add it to the vehicle's history", modifier = Modifier.weight(1f))
                    Switch(addToHistory, { addToHistory = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton("Mark as done", "check", Modifier.fillMaxWidth()) {
                    if (runCatching { LocalDate.parse(date) }.isFailure) { error = "Enter the date as YYYY-MM-DD."; return@PrimaryButton }
                    scope.launch {
                        repo.markReminderDone(r, date, mileage.toIntOrNull(), addToHistory)
                        message = if (addToHistory) "Marked as done and added to the history." else "Marked as done."
                    }
                }
                message?.let { Text(it, color = LocalExtra.current.ok, fontWeight = FontWeight.SemiBold) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Edit schedule", "edit") { editing = true }
                if (shopItems.isNotEmpty()) SecondaryButton("Find Products", "search") { shopping = shopItems }
            }
            // Collect what this job needs on the shopping list (doesn't mark it done).
            SecondaryButton("Add to List", "list", Modifier.fillMaxWidth()) {
                scope.launch {
                    val added = repo.addToShoppingList(reminderLines(v, r, name, shopCtx, Catalog.get(ctx)))
                    listMessage = if (added == 0) "Already on your shopping list." else "Added $added item${if (added == 1) "" else "s"} to the shopping list."
                }
            }
            listMessage?.let { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m, Modifier.weight(1f), color = LocalExtra.current.muted)
                    TextButton({ nav.navigate("shopping") }) { Text("View list") }
                }
            }
            TextButton({ confirmDelete = true }) { Text("Delete reminder", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (editing) ReminderEditor(v, r) { editing = false }
    shopping?.let { items -> ShoppingDialog("Find supplies — $name", items) { shopping = null } }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete reminder?") }, text = { Text("Your history isn't affected.") },
        confirmButton = { TextButton({
            confirmDelete = false
            scope.launch { repo.deleteReminder(r.id); com.ratchetwrench.app.ui.onMain { nav.popBackStack() } }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
