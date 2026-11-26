// EntryScreens.kt — add/edit a maintenance or repair entry (with photos),
// and view one entry.  Works fully offline: photos are kept in the app's
// private storage and uploaded by the background sync later.
package com.ratchetwrench.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.KindPill
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PhotoThumb
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.Segmented
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.TypeBadge
import com.ratchetwrench.app.ui.UiIcon
import com.ratchetwrench.app.ui.miles
import com.ratchetwrench.app.ui.money
import com.ratchetwrench.app.ui.parseMoney
import com.ratchetwrench.app.ui.prettyDate
import com.ratchetwrench.app.ui.takeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Copy a picked gallery image into the app's private photo folder. */
fun copyIntoApp(context: Context, uri: Uri): String? = try {
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val ext = when { mime.contains("png") -> "png"; mime.contains("hei") -> "heic"; mime.contains("pdf") -> "pdf"; else -> "jpg" }
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val out = File(dir, "${UUID.randomUUID()}.$ext")
    context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
    com.ratchetwrench.app.camera.Photos.uprightJpeg(out) // turn sideways photos upright
    out.absolutePath
} catch (e: Exception) { null }

fun mimeFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
    "png" -> "image/png"; "heic", "heif" -> "image/heic"; "pdf" -> "application/pdf"; else -> "image/jpeg"
}

@Composable
fun EntryEditScreen(nav: NavHostController, vehicleId: String, entryId: String?) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicle by repo.db.vehicles().observe(vehicleId).collectAsState(initial = null)
    var existing by remember { mutableStateOf<Entry?>(null) }
    var kind by rememberSaveable { mutableStateOf("maintenance") }
    var type by rememberSaveable { mutableStateOf("oil_change") }
    var custom by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var mileage by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var parts by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var by by rememberSaveable { mutableStateOf("diy") }
    var shop by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    // Saveable: attached photos survive turning the phone.
    val photos = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entryId) {
        if (entryId != null && existing == null) repo.db.entries().get(entryId)?.let { e ->
            existing = e; kind = e.kind; type = e.type_key; custom = e.custom_type; date = e.date; mileage = e.mileage?.toString() ?: ""
            desc = e.description; parts = e.parts_used; cost = e.cost_cents?.let { "%.2f".format(it / 100.0) } ?: ""; by = e.performed_by
            shop = e.shop_name; notes = e.notes
        } else if (entryId == null && mileage.isEmpty()) mileage = repo.db.vehicles().get(vehicleId)?.current_mileage?.toString() ?: ""
    }
    nav.takeResult<ArrayList<String>>("photos")?.let { photos.addAll(it) }
    val pickGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch { uris.forEach { u -> withContext(Dispatchers.IO) { copyIntoApp(ctx, u) }?.let { photos.add(it) } } }
    }
    val v = vehicle ?: return
    val types = Catalog.typesFor(ctx, v.kind).filter { search.isBlank() || it.label.contains(search, true) || it.category.contains(search, true) }

    Scaffold(topBar = { TopBar(if (entryId == null) "Add entry" else "Edit entry", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(v.displayName(), color = LocalExtra.current.muted)
            Segmented(listOf("maintenance" to "Maintenance", "repair" to "Repair"), kind) { kind = it }
            Text("Type", fontWeight = FontWeight.Bold)
            Field("Search types", search, { search = it })
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                LazyVerticalGrid(GridCells.Adaptive(96.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types, key = { it.key }) { t ->
                        val on = t.key == type
                        Column(Modifier.clip(RoundedCornerShape(14.dp))
                            .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                            .clickable { type = t.key }.heightIn(min = 96.dp).padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TypeBadge(t.key, if (on) kind else "neutral", 40.dp)
                            Text(t.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
            if (type == "custom") Field("Custom type name", custom, { custom = it })
            Field("Date (YYYY-MM-DD)", date, { date = it })
            Field("Mileage (${v.mileage_unit})", mileage, { mileage = it.filter { c -> c.isDigit() } }, numeric = true)
            Field("Description", desc, { desc = it })
            Field("Total cost", cost, { cost = it }, numeric = false, hint = "e.g. 89.95")
            Segmented(listOf("diy" to "I did it (DIY)", "shop" to "A shop did it"), by) { by = it }
            if (by == "shop") Field("Shop name", shop, { shop = it })
            Field("Parts used", parts, { parts = it }, singleLine = false)
            Field("Notes", notes, { notes = it }, singleLine = false)
            Text("Photos & receipts", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Camera", "camera") { nav.navigate("capture") }
                SecondaryButton("Gallery", "image") { pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            if (photos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { p ->
                    Box {
                        PhotoThumb(Attachment(id = p, localPath = p, content_type = mimeFor(p)), 88.dp)
                        IconButton({ photos.remove(p); File(p).delete() }, Modifier.align(Alignment.TopEnd)) { UiIcon("x", description = "Remove photo") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Save entry", "check", Modifier.fillMaxWidth()) {
                if (runCatching { LocalDate.parse(date.trim()) }.isFailure) { error = "Enter the date as YYYY-MM-DD."; return@PrimaryButton }
                scope.launch {
                    val e = (existing ?: Entry(vehicle_id = v.id, date = date.trim())).copy(
                        kind = kind, type_key = type, custom_type = if (type == "custom") custom.trim() else "", date = date.trim(),
                        mileage = mileage.toIntOrNull(), description = desc.trim(), parts_used = parts, cost_cents = parseMoney(cost),
                        performed_by = by, shop_name = if (by == "shop") shop.trim() else "", notes = notes)
                    repo.saveEntry(e, isNew = existing == null)
                    for (p in photos) repo.addPhoto(v.id, e.id, if (mimeFor(p) == "application/pdf") "receipt" else "photo", p, mimeFor(p), File(p).nameWithoutExtension.take(40))
                    com.ratchetwrench.app.ui.onMain { nav.popBackStack() }
                }
            }
        }
    }
}

@Composable
fun EntryDetailScreen(nav: NavHostController, id: String) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entry by repo.db.entries().observe(id).collectAsState(initial = null)
    val atts by repo.db.attachments().observeForEntry(id).collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf(false) }
    val e = entry ?: return
    val vehicle by repo.db.vehicles().observe(e.vehicle_id).collectAsState(initial = null)
    val unit = vehicle?.mileage_unit ?: "mi"

    Scaffold(topBar = {
        TopBar(e.custom_type.ifBlank { Catalog.typeLabel(ctx, e.type_key) }, onBack = { nav.popBackStack() }) {
            IconButton({ nav.navigate("entry-edit/${e.vehicle_id}/${e.id}") }) { UiIcon("edit", description = "Edit entry") }
            IconButton({ confirm = true }) { UiIcon("trash", description = "Delete entry") }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TypeBadge(e.type_key, e.kind, 56.dp)
                Column { Text(vehicle?.displayName() ?: "", color = LocalExtra.current.muted); KindPill(e.kind) }
            }
            SectionCard {
                listOf("Date" to prettyDate(e.date), "Mileage" to miles(e.mileage, unit), "Cost" to money(e.cost_cents, repo.store.currency),
                    "Done by" to (if (e.performed_by == "shop") e.shop_name.ifBlank { "Shop" } else "DIY"),
                    "Source" to (if (e.source == "carfax") "Imported from Carfax" else "Entered by me")).forEach { (k, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(k, color = LocalExtra.current.muted); Text(value, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (e.description.isNotBlank() || e.parts_used.isNotBlank() || e.notes.isNotBlank()) SectionCard {
                if (e.description.isNotBlank()) { Text("Description", fontWeight = FontWeight.Bold); Text(e.description) }
                if (e.parts_used.isNotBlank()) { Text("Parts used", fontWeight = FontWeight.Bold); Text(e.parts_used) }
                if (e.notes.isNotBlank()) { Text("Notes", fontWeight = FontWeight.Bold); Text(e.notes) }
            }
            Text("Photos & receipts (${atts.size})", fontWeight = FontWeight.Bold)
            if (atts.isEmpty()) Text("None yet — tap Edit to add some.", color = LocalExtra.current.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                atts.forEach { PhotoThumb(it, 104.dp) }
            }
            if (atts.any { !it.has_file }) Text("Photos marked with ↑ will upload when you're online.", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete entry?") }, text = { Text("The entry and its photos will be deleted.") },
        confirmButton = { TextButton({
            // Delete FIRST, then leave: leaving the screen cancels its scope,
            // which could otherwise cancel the delete before it runs.
            confirm = false
            scope.launch { repo.deleteEntry(e.id); com.ratchetwrench.app.ui.onMain { nav.popBackStack() } }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
