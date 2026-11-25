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
        requestSync()
    }

    /** Save a spec value, reusing the existing row for that field. */
    suspend fun saveSpec(vehicleId: String, key: String, value: String, source: String) {
        val existing = db.specs().find(vehicleId, key)
        val spec = existing?.copy(value = value, source = source) ?: Spec(vehicle_id = vehicleId, key = key, value = value, source = source)
        db.specs().upsert(spec.copy(updated_at = nowIso(), dirty = true))
        requestSync()
    }

    suspend fun deleteSpec(id: String) {
        val s = db.specs().get(id) ?: return
        db.specs().upsert(s.copy(deleted_at = nowIso(), updated_at = nowIso(), dirty = true))
        requestSync()
    }

    suspend fun saveEntry(e: Entry, isNew: Boolean) {
        db.entries().upsert(e.copy(updated_at = nowIso(), dirty = true))
        bumpMileage(e.vehicle_id, e.mileage)
        if (isNew && e.kind == "maintenance") markRemindersDone(e)
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
