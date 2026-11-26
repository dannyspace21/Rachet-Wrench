// RecallsUi.kt — History tab → "Recalls" on the phone: every safety recall
// NHTSA lists for the vehicle's year/make/model, with what's known about each
// (open / done / doesn't apply), "Mark as done" (adds it to the history) and a
// button to check this car's VIN on NHTSA's website.  Same rules as the web
// app (domain/Recalls.kt).
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.data.VehicleRecall
import com.ratchetwrench.app.domain.RecallState
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.onMain
import com.ratchetwrench.app.ui.prettyDate
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun titleCase(s: String) = s.lowercase().split(Regex("(?<=[\\s:/,-])")).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

/** The recalls list, added to the History tab's list when "Recalls" is chosen. */
fun LazyListScope.recallItems(nav: NavHostController, v: Vehicle, rows: List<Pair<VehicleRecall, RecallState>>, repo: Repository,
                              status: String?, onRefresh: () -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (v.year != null && v.make.isNotBlank() && v.model.isNotBlank()) "Safety recalls for the ${v.year} ${v.make} ${v.model}, from NHTSA."
                else "Add the year, make and model (Edit) to look up recalls.", color = LocalExtra.current.muted)
            Text("NHTSA lists recalls for every car of this model. Whether one applies to your exact car — and whether it was already fixed — " +
                "is known by the manufacturer: check your VIN on NHTSA or ask a dealer, then mark it below.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.vin.isNotBlank()) SecondaryButton("Check my VIN", "external") { openLink(nav.context, "https://www.nhtsa.gov/recalls?vin=${v.vin}") }
                SecondaryButton("Check for new", "sync") { onRefresh() }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
        }
    }
    if (rows.isEmpty()) item { Text(if (status == null) "Checking NHTSA for recalls…" else "No recalls listed for this model.", color = LocalExtra.current.muted) }
    items(rows, key = { "rc" + it.first.id }) { (r, s) -> RecallCard(nav, r, s, repo) }
}

@Composable
private fun RecallCard(nav: NavHostController, r: VehicleRecall, s: RecallState, repo: Repository) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val (label, bg) = when (s.status) { "open" -> "Open" to MaterialTheme.colorScheme.error; "done" -> "Done" to LocalExtra.current.ok; else -> "Doesn't apply" to LocalExtra.current.muted }
    ListRow(onClick = { expanded = !expanded }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleCase(r.component).ifBlank { "Recall" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (s.isNew) Text("New  ", color = LocalExtra.current.warn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(bg, RoundedCornerShape(99.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text("NHTSA campaign ${r.campaign}" + (r.report_date?.let { " · ${prettyDate(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                color = LocalExtra.current.muted)
            if (r.park_it && s.status == "open") Text("⚠ NHTSA advises not to drive this vehicle, or to park it outside, until it's fixed.",
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Text(r.summary, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                if (r.consequence.isNotBlank()) Text("Risk: ${r.consequence}", style = MaterialTheme.typography.bodySmall)
                if (r.remedy.isNotBlank()) Text("Fix: ${r.remedy}", style = MaterialTheme.typography.bodySmall)
            } else Text("Tap for the risk and the fix", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted)
            if (s.status == "done") (s.entry?.let { e ->
                TextButton({ nav.navigate("entry/${e.id}") }) { Text("Done ${prettyDate(e.date)} — see the history record") }
            } ?: Text("Marked done" + (r.done_date?.let { " ${prettyDate(it)}" } ?: "") + ".", style = MaterialTheme.typography.bodySmall))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.status == "open") {
                    PrimaryButton("Mark as done", "check") { nav.navigate("recall-done/${r.id}") }
                    SecondaryButton("Doesn't apply") { scope.launch { repo.setRecallStatus(r.id, "not_applicable") } }
                } else if (r.status != "open") TextButton({ scope.launch { repo.setRecallStatus(r.id, "open") } }) { Text("Mark as open again") }
            }
        }
    }
}

/** "Mark recall as done": date, mileage, dealer -> history record + status done. */
@Composable
fun RecallDoneScreen(nav: NavHostController, id: String) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    val recall by repo.db.recalls().observe(id).collectAsState(initial = null)
    val r = recall ?: return
    val vehicle by repo.db.vehicles().observe(r.vehicle_id).collectAsState(initial = null)
    val v = vehicle ?: return
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var mileage by rememberSaveable { mutableStateOf(v.current_mileage?.toString() ?: "") }
    var shop by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopBar("Mark recall as done", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(titleCase(r.component), fontWeight = FontWeight.Bold)
            Text("Adds “Recall ${r.campaign}” to the history (no cost — recalls are free).", color = LocalExtra.current.muted)
            Field("Done on (YYYY-MM-DD)", date, { date = it.trim(); error = null })
            Field("Mileage (${v.mileage_unit})", mileage, { mileage = it.filter(Char::isDigit) }, numeric = true)
            Field("Dealer / shop (optional)", shop, { shop = it.take(128) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Mark as done", "check", Modifier.fillMaxWidth()) {
                if (runCatching { LocalDate.parse(date) }.isFailure) { error = "Enter the date as YYYY-MM-DD."; return@PrimaryButton }
                scope.launch { repo.markRecallDone(r, date, mileage.toIntOrNull(), shop); onMain { nav.popBackStack() } }
            }
        }
    }
}

