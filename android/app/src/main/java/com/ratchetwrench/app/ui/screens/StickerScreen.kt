// StickerScreen.kt — door-jamb sticker scan.
//
//  1. Take photos of the stickers (or pick them from the gallery).
//  2. ML Kit reads the text ON THE PHONE (works offline, nothing uploaded).
//  3. StickerParser pulls out VIN, tire sizes, pressures, paint code, ...
//  4. REVIEW: every field can be kept, edited or rejected.
//  5. Save: approved values go to Specs labelled "From door sticker", and the
//     photos are attached to the vehicle (uploaded when online).
//
// Optional AI vision (only if the server owner enabled it): sends the photos
// to the server, which forwards them to Anthropic's Claude API.  A warning
// must be accepted first because that is a third party.
package com.ratchetwrench.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.data.JsonFormat
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.StickerParser
import com.ratchetwrench.app.domain.Vin
import com.ratchetwrench.app.ui.Field
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.Pill
import com.ratchetwrench.app.ui.PhotoThumb
import com.ratchetwrench.app.ui.PrimaryButton
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.SectionCard
import com.ratchetwrench.app.ui.TopBar
import com.ratchetwrench.app.ui.takeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Wait for an ML Kit task to finish (without an extra library). */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** One proposed field on the review screen. */
private class Proposal(val key: String, value: String) {
    var value by mutableStateOf(value)
    var keep by mutableStateOf(true)
}

@Composable
fun StickerScreen(nav: NavHostController, vehicleId: String) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicle by repo.db.vehicles().observe(vehicleId).collectAsState(initial = null)
    // Saveable: attached photos survive turning the phone.
    val photos = rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val proposals = remember { mutableStateListOf<Proposal>() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askAi by remember { mutableStateOf(false) }
    var aiUsed by remember { mutableStateOf(false) }

    nav.takeResult<ArrayList<String>>("photos")?.let { photos.addAll(it) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        scope.launch { uris.forEach { u -> withContext(Dispatchers.IO) { copyIntoApp(ctx, u) }?.let { photos.add(it) } } }
    }

    fun show(fields: Map<String, String>) {
        proposals.clear()
        fields.forEach { (k, v) -> proposals.add(Proposal(k, v)) }
        status = if (fields.isEmpty()) "No fields could be read. Try a sharper, straight-on photo in good light." else null
    }

    /** On-device text recognition (ML Kit, bundled model, offline). */
    fun readOnPhone() = scope.launch {
        busy = true; status = "Reading…"
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val texts = photos.map { p -> recognizer.process(InputImage.fromFilePath(ctx, android.net.Uri.fromFile(File(p)))).awaitResult().text }
            show(StickerParser.parse(texts.joinToString("\n")))
        } catch (e: Exception) { status = "Text recognition failed: ${e.message}" } finally { recognizer.close(); busy = false }
    }

    /** Optional AI vision on the server (third party).  Needs a connection. */
    fun readWithAi() = scope.launch {
        busy = true; status = "Sending photos for AI reading…"
        try {
            val merged = linkedMapOf<String, String>()
            withContext(Dispatchers.IO) {
                for (p in photos) {
                    val res = Api(repo.store).upload("/api/vehicles/$vehicleId/sticker/scan", File(p), "image/jpeg", mapOf("use_ai" to "true"))
                    JsonFormat.parseToJsonElement(res).jsonObject["fields"]!!.jsonArray.forEach {
                        merged.putIfAbsent(it.jsonObject["key"]!!.jsonPrimitive.content, it.jsonObject["value"]!!.jsonPrimitive.content)
                    }
                }
            }
            aiUsed = true // the server already stored the photos with the vehicle
            show(merged)
        } catch (e: Exception) { status = "AI vision failed: ${e.message}" } finally { busy = false }
    }

    val v = vehicle ?: return
    Scaffold(topBar = { TopBar("Scan door sticker", onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Photograph the tire & loading placard and the certification label inside the driver's door jamb.", color = LocalExtra.current.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Camera", "camera") { nav.navigate("capture") }
                SecondaryButton("Gallery", "image") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            if (photos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { PhotoThumb(Attachment(id = it, localPath = it), 88.dp) }
            }
            if (photos.isNotEmpty()) {
                PrimaryButton("Read stickers (on this phone)", "scan", Modifier.fillMaxWidth(), enabled = !busy) { readOnPhone() }
                if (repo.store.mode == "server" && repo.store.serverAiVision) SecondaryButton("Try AI vision instead…", "warning", Modifier.fillMaxWidth(), enabled = !busy) { askAi = true }
            }
            status?.let { Text(it, color = LocalExtra.current.muted) }

            if (proposals.isNotEmpty()) SectionCard {
                Text("Review what was read", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Check each value against the sticker. Untick anything wrong, or fix it. Nothing is saved until you press Save.",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                proposals.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.keep, { p.keep = it })
                        Column(Modifier.weight(1f)) {
                            Field(Catalog.specLabel(ctx, p.key), p.value, { p.value = it })
                            Pill("From door sticker", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                PrimaryButton("Save approved fields", "check", Modifier.fillMaxWidth()) {
                    scope.launch {
                        for (p in proposals.filter { it.keep && it.value.isNotBlank() }) {
                            if (p.key == "vin") {
                                val vin = Vin.normalize(p.value)
                                if (vin.length == 17) repo.saveVehicle(v.copy(vin = vin))
                            } else repo.saveSpec(v.id, p.key, p.value.trim(), "sticker")
                        }
                        // Keep the sticker photos with the vehicle (unless AI mode already uploaded them).
                        if (!aiUsed) photos.forEach { repo.addPhoto(v.id, null, "sticker", it, "image/jpeg", "Door sticker") }
                        com.ratchetwrench.app.ui.onMain { nav.popBackStack() }
                    }
                }
                SecondaryButton("Reject all", modifier = Modifier.fillMaxWidth()) { proposals.clear() }
            }
        }
    }
    if (askAi) AlertDialog(onDismissRequest = { askAi = false },
        title = { Text("Send photos to a third party?") },
        text = { Text("AI vision uploads these sticker photos to your server, which sends them to Anthropic's Claude API over the internet to be read. Only use it if you're comfortable with that. On-phone reading keeps photos private.") },
        confirmButton = { TextButton({ askAi = false; readWithAi() }) { Text("Send for AI reading") } },
        dismissButton = { TextButton({ askAi = false }) { Text("Cancel") } })
}
