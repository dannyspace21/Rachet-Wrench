// ManualScreen.kt — Specs → "Fill from owner's manual".
//
// The manual is read on the computer (the Ratchet Wrench server reads PDF
// text in a locked-down helper; the phone can't).  The server PROPOSES the
// oil, coolant, transmission and brake fluid types and capacities it found,
// each with the page and line it came from.  The owner picks / edits / skips
// each one; approved values are saved "From owner's manual" and synced.
// Nothing is sent anywhere except the owner's own server.
package com.ratchetwrench.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A value found in the manual: what it says, on which page, in which line. */
data class ManualOption(val value: String, val page: Int, val snippet: String)

/** One spec on the review screen. */
class ManualProposal(val key: String, val label: String, val current: String?, val options: List<ManualOption>) {
    var chosen by mutableStateOf(0)
    var value by mutableStateOf(options.firstOrNull()?.value ?: "")
    var keep by mutableStateOf(current.isNullOrBlank())
}

object ManualResult {
    /** Parse the server's /manual/scan answer. */
    fun parse(body: String): Triple<Int, Int, List<ManualProposal>> {
        val o = JsonFormat.parseToJsonElement(body).jsonObject
        val specs = o["specs"]?.jsonArray.orEmpty().map { el ->
            val s = el.jsonObject
            ManualProposal(
                key = s["key"]!!.jsonPrimitive.content,
                label = s["label"]?.jsonPrimitive?.contentOrNull ?: "",
                current = s["current"]?.jsonPrimitive?.contentOrNull,
                options = s["options"]?.jsonArray.orEmpty().map { op ->
                    val x = op.jsonObject
                    ManualOption(x["value"]!!.jsonPrimitive.content, x["page"]?.jsonPrimitive?.intOrNull ?: 0, x["snippet"]?.jsonPrimitive?.contentOrNull ?: "")
                })
        }.filter { it.options.isNotEmpty() }
        return Triple(o["pages"]?.jsonPrimitive?.intOrNull ?: 0, o["readable_pages"]?.jsonPrimitive?.intOrNull ?: 0, specs)
    }
}

private data class SavedManual(val id: String, val title: String)

@Composable
fun ManualScreen(nav: NavHostController, vehicleId: String) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = repo.store.mode == "server"
    val manuals = remember { mutableStateListOf<SavedManual>() }
    var picked by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    val proposals = remember { mutableStateListOf<ManualProposal>() }

    LaunchedEffect(server) {
        if (!server) return@LaunchedEffect
        try {
            val body = withContext(Dispatchers.IO) { Api(repo.store).get("/api/attachments?vehicle_id=$vehicleId") }
            manuals.clear()
            JsonFormat.parseToJsonElement(body).jsonArray.map { it.jsonObject }
                .filter { it["category"]?.jsonPrimitive?.contentOrNull == "manual" && it["has_file"]?.jsonPrimitive?.booleanOrNull == true }
                .forEach { a -> manuals += SavedManual(a["id"]!!.jsonPrimitive.content,
                    (a["caption"]?.jsonPrimitive?.contentOrNull ?: a["original_name"]?.jsonPrimitive?.contentOrNull)?.ifBlank { null } ?: "Owner's manual") }
            picked = manuals.firstOrNull()?.id
        } catch (e: Exception) { status = "Couldn't reach your computer: ${e.message}" }
        loaded = true
    }

    fun read(fields: Map<String, String>, file: File?) {
        busy = true; status = "Reading the manual on your computer… a long manual can take a minute or two."
        scope.launch {
            try {
                val body = withContext(Dispatchers.IO) { Api(repo.store).postForm("/api/vehicles/$vehicleId/manual/scan", fields, file) }
                val (pages, readable, specs) = ManualResult.parse(body)
                proposals.clear(); proposals.addAll(specs)
                summary = "$readable of $pages pages read"
                status = when {
                    specs.isNotEmpty() -> null
                    readable == 0 -> "This PDF has no readable text (it may be a scan of paper pages), so nothing could be found."
                    else -> "No fluid specs were found in this PDF. Look for the Specifications or Capacities pages and enter them by hand."
                }
            } catch (e: Exception) { status = "Couldn't read the manual: ${e.message}" } finally { busy = false; file?.delete() }
        }
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    File(ctx.cacheDir, "manual-upload.pdf").also { out -> ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } } }
                }.getOrNull()
            }
            if (f == null) status = "Couldn't open that file." else read(emptyMap(), f)
        }
    }

    Scaffold(topBar = { TopBar("Fill from owner's manual", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Finds the oil, coolant, transmission and brake fluid types and capacities in your owner's manual. You pick and confirm each one.",
                color = LocalExtra.current.muted)
            if (!server) {
                SectionCard {
                    Text("Needs your computer", fontWeight = FontWeight.SemiBold)
                    Text("The manual is read by Ratchet Wrench on your computer (phones can't read PDF text). Connect this phone to your computer in Settings, or use the web app's Specs tab → Fill from owner's manual.",
                        color = LocalExtra.current.muted)
                }
            } else if (proposals.isEmpty()) {
                SectionCard {
                    if (manuals.isNotEmpty()) {
                        Text("Saved manuals", fontWeight = FontWeight.SemiBold)
                        manuals.forEach { m ->
                            Row(Modifier.fillMaxWidth().clickable { picked = m.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(picked == m.id, { picked = m.id }); Text(m.title)
                            }
                        }
                        PrimaryButton("Read manual", "book", Modifier.fillMaxWidth(), enabled = !busy && picked != null) { read(mapOf("attachment_id" to picked!!), null) }
                    } else if (loaded) {
                        Text("No owner's manual saved for this vehicle yet.", color = LocalExtra.current.muted)
                    }
                    SecondaryButton("Choose a PDF…", "upload", Modifier.fillMaxWidth(), enabled = !busy) { pickPdf.launch(arrayOf("application/pdf")) }
                    Text("The PDF is read on your own computer — nothing is sent anywhere else. A new PDF is kept with the vehicle's documents.",
                        style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                }
            }
            status?.let { Text(it, color = LocalExtra.current.muted) }
            if (proposals.isNotEmpty()) {
                Text("Review what was found" + (summary?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Each value shows the page and line it came from. Pick the right one and fix it if needed. Specs that already have a value are unticked.",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
                proposals.forEach { p -> ProposalCard(p, Catalog.specLabel(ctx, p.key).takeIf { it != p.key } ?: p.label) }
                PrimaryButton("Save selected", "check", Modifier.fillMaxWidth(), enabled = !busy) {
                    val chosen = proposals.filter { it.keep && it.value.isNotBlank() }
                    if (chosen.isEmpty()) { status = "Nothing selected to save."; return@PrimaryButton }
                    scope.launch {
                        chosen.forEach { repo.saveSpec(vehicleId, it.key, it.value.trim(), "manual") }
                        nav.popBackStack()
                    }
                }
                SecondaryButton("Cancel", modifier = Modifier.fillMaxWidth()) { nav.popBackStack() }
            }
        }
    }
}

@Composable
private fun ProposalCard(p: ManualProposal, label: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(p.keep, { p.keep = it })
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                p.current?.takeIf { it.isNotBlank() }?.let { Text("Saved now: $it", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted) }
            }
        }
        p.options.forEachIndexed { i, o ->
            Row(Modifier.fillMaxWidth().clickable { p.chosen = i; p.value = o.value; p.keep = true }, verticalAlignment = Alignment.Top) {
                RadioButton(p.chosen == i, { p.chosen = i; p.value = o.value; p.keep = true })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("${o.value} · page ${o.page}", fontWeight = FontWeight.SemiBold)
                    Text(o.snippet, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = LocalExtra.current.muted)
                }
            }
        }
        Field("Value to save", p.value, { p.value = it })
    }
}
