// Repository.kt — every change you make goes through here.
//
// Offline-first: a change is written to the phone's database straight away
// (marked dirty = "not yet on the server") and a background sync is
// requested.  The screens update instantly, with or without a connection.
//
// It also mirrors two server rules so the phone is correct while offline:
//   * logging maintenance moves matching reminders forward
//   * a higher mileage on an entry/odometer reading updates the vehicle
package com.ratchetwrench.app.data

import android.content.Context
import com.ratchetwrench.app.sync.SyncWorker
import java.time.LocalDate

class Repository(private val context: Context) {
    val db = AppDatabase.get(context)
    val store = SecureStore(context)

    private fun requestSync() = SyncWorker.runSoon(context)

    suspend fun saveVehicle(v: Vehicle) {
        db.vehicles().upsert(v.copy(updated_at = nowIso(), dirty = true))
        requestSync()
    }

    suspend fun deleteVehicle(id: String) {
        val v = db.vehicles().get(id) ?: return
        val now = nowIso()
        // The server cascades the delete to everything that belongs to it.
        db.vehicles().upsert(v.copy(deleted_at = now, updated_at = now, dirty = true))
        // Its shopping-list lines go too (the server does the same).
        for (i in db.shopping().all().filter { it.vehicle_id == id }) {
            db.shopping().upsert(i.copy(deleted_at = now, updated_at = now, dirty = true))
        }
        requestSync()
    }

    /** Save a spec value, reusing the existing row for that field. */
    suspend fun saveSpec(vehicleId: String, key: String, value: String, source: String) {
        val existing = db.specs().find(vehicleId, key)
        val spec = existing?.copy(value = value, source = source) ?: Spec(vehicle_id = vehicleId, key = key, value = value, source = source)
        db.specs().upsert(spec.copy(updated_at = nowIso(), dirty = true))
        applySpecPart(vehicleId, key, value)
        requestSync()
    }

    /** The fixed ID of the part made from a spec — the same one the server uses (spec_parts.py). */
    fun specPartId(vehicleId: String, key: String): String =
        java.util.UUID.nameUUIDFromBytes("ratchet-wrench:spec-part:$vehicleId:$key".toByteArray(Charsets.UTF_8)).toString()

    /**
     * Specs that are also parts (oil type, oil filter number, tire sizes…):
     * filling one in adds/updates the part on the Parts tab; clearing it
     * removes the part.  Same rules as the server, same part ID, so syncing
     * never makes a duplicate.  A part you deleted stays deleted unless the
     * spec changes.
     */
    private suspend fun applySpecPart(vehicleId: String, key: String, value: String?) {
        val m = com.ratchetwrench.app.domain.Catalog.get(context).spec_parts.firstOrNull { it.spec == key } ?: return
        val id = specPartId(vehicleId, key)
        val part = db.parts().get(id)
        val v = value?.trim().orEmpty()
        val now = nowIso()
        if (v.isEmpty()) {
            if (part != null && part.deleted_at == null) db.parts().upsert(part.copy(deleted_at = now, updated_at = now, dirty = true))
            return
        }
        val number = m.format.replace("{v}", v).take(64)
        when {
            // Already saved by hand (same kind of part, same number)? Don't add it twice.
            part == null && db.parts().forVehicle(vehicleId).any { it.type_key == m.type_key && it.part_number == number } -> Unit
            part == null -> db.parts().upsert(Part(id = id, vehicle_id = vehicleId, type_key = m.type_key, name = m.name,
                part_number = number, notes = "Added from Specs", updated_at = now, dirty = true))
            part.part_number != number -> db.parts().upsert(part.copy(part_number = number, deleted_at = null, updated_at = now, dirty = true))
        }
    }

    /** Once: parts for specs saved before "specs are also parts" existed (never re-adds a deleted one). */
    suspend fun backfillSpecParts() {
        val keys = com.ratchetwrench.app.domain.Catalog.get(context).spec_parts.map { it.spec }.toSet()
        var added = false
        for (s in db.specs().allLive().filter { it.key in keys && it.value.isNotBlank() }) {
            if (db.parts().get(specPartId(s.vehicle_id, s.key)) == null) { applySpecPart(s.vehicle_id, s.key, s.value); added = true }
        }
        if (added) requestSync()
    }

    suspend fun deleteSpec(id: String) {
        val s = db.specs().get(id) ?: return
        db.specs().upsert(s.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true))
        applySpecPart(s.vehicle_id, s.key, null)
        requestSync()
    }

    suspend fun saveEntry(e: Entry, isNew: Boolean) {
        db.entries().upsert(e.copy(updated_at = nowIso(), dirty = true))
        bumpMileage(e.vehicle_id, e.mileage)
        // Any new record of this type (maintenance OR repair, e.g. a brake job
        // logged as a repair) moves matching reminders' "last done" forward.
        if (isNew) markRemindersDone(e)
        requestSync()
    }

    suspend fun deleteEntry(id: String) {
        val e = db.entries().get(id) ?: return
        val now = nowIso()
        db.entries().upsert(e.copy(deleted_at = now, updated_at = now, dirty = true))
        requestSync()
    }

    /** Keep a photo (already copied into app storage) for upload later. */
    suspend fun addPhoto(vehicleId: String, entryId: String?, category: String, localPath: String, mime: String, caption: String) {
        db.attachments().upsert(
            Attachment(vehicle_id = vehicleId, entry_id = entryId, category = category, content_type = mime,
                original_name = caption, caption = caption, localPath = localPath, has_file = false),
        )
        requestSync()
    }

    suspend fun deleteAttachment(id: String) {
        val a = db.attachments().get(id) ?: return
        if (!a.has_file && a.localPath != null) {
            // Never reached the server: just forget it.
            java.io.File(a.localPath).delete()
            db.attachments().remove(id)
        } else {
            db.attachments().upsert(a.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true))
        }
        requestSync()
    }

    /**
     * "Mark as done" from a reminder: sets when (and at what mileage) it was
     * last done — both, so a "whichever comes first" reminder can't stay
     * overdue on the mileage side — and, if asked, adds the matching record to
     * the vehicle's history.  The vehicle's mileage goes up if this is higher.
     */
    suspend fun markReminderDone(r: Reminder, date: String, mileage: Int?, addToHistory: Boolean, note: String = "") {
        if (addToHistory) {
            saveEntry(Entry(vehicle_id = r.vehicle_id, date = date, type_key = r.type_key, kind = "maintenance",
                mileage = mileage, description = note.ifBlank { r.title }), isNew = true)
        } else {
            bumpMileage(r.vehicle_id, mileage)
        }
        // Exactly what was entered (even an earlier date, to correct a mistake).
        val fresh = db.reminders().get(r.id) ?: r
        saveReminder(fresh.copy(last_done_date = date, last_done_mileage = mileage ?: fresh.last_done_mileage))
    }

    suspend fun saveReminder(r: Reminder) { db.reminders().upsert(r.copy(updated_at = nowIso(), dirty = true)); requestSync() }
    suspend fun deleteReminder(id: String) {
        val r = db.reminders().get(id) ?: return
        db.reminders().upsert(r.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true)); requestSync()
    }

    suspend fun savePart(p: Part) { db.parts().upsert(p.copy(updated_at = nowIso(), dirty = true)); requestSync() }
    suspend fun deletePart(id: String) {
        val p = db.parts().get(id) ?: return
        db.parts().upsert(p.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true)); requestSync()
    }

    // ------------------------------------------------------------ shopping list
    suspend fun saveShoppingItem(i: ShoppingItem) {
        db.shopping().upsert(i.copy(description = i.description.trim().take(200), part_number = i.part_number.trim().take(64),
            spec = i.spec.trim().take(200), quantity = i.quantity.coerceIn(1, 999), updated_at = nowIso(), dirty = true))
        requestSync()
    }
    suspend fun deleteShoppingItem(id: String) {
        val i = db.shopping().get(id) ?: return
        db.shopping().upsert(i.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true)); requestSync()
    }
    suspend fun setPurchased(id: String, purchased: Boolean) {
        val i = db.shopping().get(id) ?: return
        saveShoppingItem(i.copy(purchased = purchased))
    }
    /** Remove every ticked-off line; returns how many. */
    suspend fun removeBought(): Int {
        val bought = db.shopping().all().filter { it.purchased }
        bought.forEach { deleteShoppingItem(it.id) }
        return bought.size
    }
    /** Add lines (e.g. a reminder's supplies), skipping ones already waiting
     *  on the list for the same reminder/part.  Returns how many were added. */
    suspend fun addToShoppingList(lines: List<ShoppingItem>): Int {
        val open = db.shopping().all().filter { !it.purchased }
        var added = 0
        for (l in lines) {
            val dup = open.any { o ->
                (l.part_id != null && o.part_id == l.part_id) ||
                    (l.reminder_id != null && o.reminder_id == l.reminder_id && o.description.equals(l.description, ignoreCase = true))
            }
            if (!dup) { saveShoppingItem(l); added++ }
        }
        return added
    }

    suspend fun addOdometer(vehicleId: String, date: String, mileage: Int) {
        db.odometer().upsert(OdometerReading(vehicle_id = vehicleId, date = date, mileage = mileage, dirty = true))
        bumpMileage(vehicleId, mileage)
        requestSync()
    }

    suspend fun deleteOdometer(id: String) {
        val o = db.odometer().get(id) ?: return
        db.odometer().upsert(o.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true)); requestSync()
    }

    private suspend fun bumpMileage(vehicleId: String, mileage: Int?) {
        if (mileage == null) return
        val v = db.vehicles().get(vehicleId) ?: return
        if (v.current_mileage == null || mileage > v.current_mileage) {
            db.vehicles().upsert(v.copy(current_mileage = mileage, updated_at = nowIso(), dirty = true))
        }
    }

    private suspend fun markRemindersDone(e: Entry) {
        val date = LocalDate.parse(e.date)
        for (r in db.reminders().forType(e.vehicle_id, e.type_key)) {
            if (r.last_done_date == null || !date.isBefore(LocalDate.parse(r.last_done_date))) {
                db.reminders().upsert(r.copy(last_done_date = e.date, last_done_mileage = e.mileage ?: r.last_done_mileage,
                    updated_at = nowIso(), dirty = true))
            }
        }
    }
}
