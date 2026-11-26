// SyncEngine.kt — keeps the phone and the server in step.
//
// One sync run does three things, in this order:
//   1. PUSH  — send every record changed on the phone (dirty) to
//              POST /api/sync.  The server applies "newest edit wins" and
//              returns its final copy of each record, which we store.
//   2. UPLOAD — upload photos taken on the phone (POST /api/attachments with
//              the phone-made ID, so a retry after a dropped connection can
//              never create a duplicate).
//   3. PULL  — GET /api/sync?since=<bookmark> for everything changed on the
//              server (including deletions) and store it, unless the phone
//              has a newer unsent edit of that record.
package com.ratchetwrench.app.sync

import android.content.Context
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.AppDatabase
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.JsonFormat
import com.ratchetwrench.app.data.OdometerReading
import com.ratchetwrench.app.data.Part
import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.SecureStore
import com.ratchetwrench.app.data.Spec
import com.ratchetwrench.app.data.SyncPull
import com.ratchetwrench.app.data.Synced
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.data.nowIso
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

class SyncEngine(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val store = SecureStore(context)
    private val api = Api(store)
    private val json = JsonFormat

    // Fields the server accepts for each kind (it rejects anything else).
    private val allowed = mapOf(
        "vehicles" to setOf("id", "updated_at", "deleted_at", "kind", "year", "make", "model", "trim", "nickname", "vin",
            "current_mileage", "mileage_unit", "photo_attachment_id", "parts_url", "owners_url", "notes"),
        "specs" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "key", "value", "source"),
        "entries" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "kind", "type_key", "custom_type", "date", "mileage",
            "description", "parts_used", "cost_cents", "performed_by", "shop_name", "notes", "source"),
        "reminders" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "type_key", "title", "interval_miles", "interval_months",
            "last_done_date", "last_done_mileage", "enabled", "notes"),
        "odometer" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "date", "mileage", "note"),
        "parts" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "type_key", "name", "part_number", "brand", "notes"),
        "attachments" to setOf("id", "updated_at", "deleted_at", "vehicle_id", "entry_id", "category", "caption"),
    )

    private fun <T> toPush(kind: String, ser: KSerializer<T>, item: T): JsonObject {
        val full = json.encodeToJsonElement(ser, item).jsonObject
        return JsonObject(full.filterKeys { it in allowed.getValue(kind) })
    }

    /** Run a full sync.  Throws on network/auth errors (the worker retries). */
    fun run() {
        // Phone-only mode: nothing to sync, nothing leaves the phone.
        if (store.mode != "server" || store.token == null || store.serverUrl.isBlank()) return
        push()
        uploadPhotos()
        pull()
        store.lastSyncOk = nowIso()
    }

    // ---------------------------------------------------------------- PUSH
    private fun push() = kotlinx.coroutines.runBlocking {
        val v = db.vehicles().dirty()
        val s = db.specs().dirty()
        val e = db.entries().dirty()
        val r = db.reminders().dirty()
        val o = db.odometer().dirty()
        val p = db.parts().dirty()
        // Attachments are only pushed (caption/delete) once their file is on the server.
        val a = db.attachments().dirty().filter { it.has_file || it.deleted_at != null }
        if (listOf(v, s, e, r, o, p, a).all { it.isEmpty() }) return@runBlocking
        val body = JsonObject(mapOf(
            "vehicles" to JsonArray(v.map { toPush("vehicles", Vehicle.serializer(), it) }),
            "specs" to JsonArray(s.map { toPush("specs", Spec.serializer(), it) }),
            "entries" to JsonArray(e.map { toPush("entries", Entry.serializer(), it) }),
            "reminders" to JsonArray(r.map { toPush("reminders", Reminder.serializer(), it) }),
            "odometer" to JsonArray(o.map { toPush("odometer", OdometerReading.serializer(), it) }),
            "parts" to JsonArray(p.map { toPush("parts", Part.serializer(), it) }),
            "attachments" to JsonArray(a.map { toPush("attachments", Attachment.serializer(), it) }),
        ))
        val results = json.parseToJsonElement(api.send("POST", "/api/sync", body)).jsonObject["results"]!!.jsonObject
        applyResults(results["vehicles"], Vehicle.serializer(), { db.vehicles().upsert(it) }, { db.vehicles().remove(it) }, { db.vehicles().get(it) }) { it.copy(dirty = false) }
        applyResults(results["specs"], Spec.serializer(), { db.specs().upsert(it) }, { db.specs().remove(it) }, { db.specs().get(it) }) { it.copy(dirty = false) }
        applyResults(results["entries"], Entry.serializer(), { db.entries().upsert(it) }, { db.entries().remove(it) }, { db.entries().get(it) }) { it.copy(dirty = false) }
        applyResults(results["reminders"], Reminder.serializer(), { db.reminders().upsert(it) }, { db.reminders().remove(it) }, { db.reminders().get(it) }) { it.copy(dirty = false) }
        applyResults(results["odometer"], OdometerReading.serializer(), { db.odometer().upsert(it) }, { db.odometer().remove(it) }, { db.odometer().get(it) }) { it.copy(dirty = false) }
        applyResults(results["parts"], Part.serializer(), { db.parts().upsert(it) }, { db.parts().remove(it) }, { db.parts().get(it) }) { it.copy(dirty = false) }
        applyResults(results["attachments"], Attachment.serializer(), { db.attachments().upsert(it) }, { db.attachments().remove(it) }, { db.attachments().get(it) }) { it.copy(dirty = false) }
    }

    /**
     * Store the server's answer for each pushed record:
     *  applied / kept_server_copy -> save the server's copy, clear "dirty"
     *  rejected                   -> clear "dirty" so it isn't retried forever
     * If the server merged our record into an existing one (e.g. a spec for
     * the same field), its ID differs: drop our copy and keep the server's.
     */
    private suspend fun <T : Synced> applyResults(
        results: JsonElement?, ser: KSerializer<T>, upsert: suspend (T) -> Unit, remove: suspend (String) -> Unit,
        get: suspend (String) -> T?, clean: (T) -> T,
    ) {
        for (el in results?.jsonArray ?: return) {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val status = obj["status"]?.jsonPrimitive?.content
            val rec = obj["record"]
            if (status != "rejected" && rec != null && rec !is kotlinx.serialization.json.JsonNull) {
                val server = json.decodeFromJsonElement(ser, rec)
                if (server.id != id) remove(id)
                val local = get(server.id)
                // Keep the phone-only photo path when the server copy comes back.
                upsert(clean(mergeLocal(server, local)))
            } else {
                get(id)?.let { upsert(clean(it)) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> mergeLocal(server: T, local: T?): T =
        if (server is Attachment && local is Attachment) server.copy(localPath = local.localPath) as T else server

    // ---------------------------------------------------------------- UPLOAD
    private fun uploadPhotos() = kotlinx.coroutines.runBlocking {
        for (a in db.attachments().pendingUploads()) {
            val file = a.localPath?.let { File(it) }
            if (file == null || !file.exists()) { db.attachments().remove(a.id); continue }
            // The vehicle and entry must exist on the server first (pushed above).
            if (a.entry_id != null && db.entries().get(a.entry_id)?.dirty == true) continue
            if (a.vehicle_id != null && db.vehicles().get(a.vehicle_id)?.dirty == true) continue
            val fields = buildMap {
                put("id", a.id)
                put("vehicle_id", a.vehicle_id ?: return@runBlocking)
                put("category", a.category)
                put("caption", a.caption.take(255))
                a.entry_id?.let { put("entry_id", it) }
            }
            // One photo the server refuses must not stop the whole sync:
            // skip it (it stays queued and is retried next time).
            val res = try { api.upload("/api/attachments", file, a.content_type, fields) }
                catch (e: com.ratchetwrench.app.data.ApiException) { if (e.status in 400..499) continue else throw e }
            val server = json.decodeFromString(Attachment.serializer(), res)
            db.attachments().upsert(server.copy(localPath = a.localPath, dirty = false))
        }
    }

    // ---------------------------------------------------------------- PULL
    private fun pull() = kotlinx.coroutines.runBlocking {
        val since = store.lastSince
        val path = if (since == null) "/api/sync" else "/api/sync?since=" + java.net.URLEncoder.encode(since, "UTF-8")
        val data = json.decodeFromString(SyncPull.serializer(), api.get(path))
        suspend fun <T : Synced> merge(items: List<T>, get: suspend (String) -> T?, upsert: suspend (T) -> Unit) {
            for (item in items) {
                val local = get(item.id)
                // Don't overwrite an unsent local edit that is newer.
                if (local != null && local.dirty && newer(local.updated_at, item.updated_at)) continue
                upsert(item)
            }
        }
        merge(data.vehicles, { db.vehicles().get(it) }) { db.vehicles().upsert(it) }
        merge(data.specs, { db.specs().get(it) }) { db.specs().upsert(it) }
        merge(data.entries, { db.entries().get(it) }) { db.entries().upsert(it) }
        merge(data.reminders, { db.reminders().get(it) }) { db.reminders().upsert(it) }
        merge(data.odometer, { db.odometer().get(it) }) { db.odometer().upsert(it) }
        merge(data.parts, { db.parts().get(it) }) { db.parts().upsert(it) }
        merge(data.attachments, { db.attachments().get(it) }) { server ->
            val local = db.attachments().get(server.id)
            db.attachments().upsert(server.copy(localPath = local?.localPath))
        }
        data.settings?.let { s ->
            store.theme = s.theme
            store.distanceUnit = s.distance_unit
            store.currency = s.currency
        }
        store.lastSince = data.next_since
    }

    private fun newer(a: String, b: String): Boolean = try { Instant.parse(a).isAfter(Instant.parse(b)) } catch (e: Exception) { false }
}
