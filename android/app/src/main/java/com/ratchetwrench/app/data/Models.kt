// Models.kt — the records the app stores on the phone.
//
// Each class is BOTH a database table on the phone (Room @Entity) and the
// JSON shape the server sends (kotlinx @Serializable), so data flows between
// them without hand-copying fields.
//
// Field names match the server exactly (snake_case).  Dates are ISO text
// ("2025-03-15"), timestamps are ISO UTC text ("2025-03-15T10:00:00Z").
//
// Phone-only bookkeeping fields are marked @Transient (never sent):
//   dirty      = "changed on this phone, not yet sent to the server"
//   localPath  = where a photo taken offline is kept until it is uploaded
package com.ratchetwrench.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.util.UUID

/** A brand-new random ID (UUID) — safe to create offline. */
fun newId(): String = UUID.randomUUID().toString()

/** "Now" as an ISO UTC timestamp. */
fun nowIso(): String = Instant.now().toString()

/** Common fields every synced record has. */
interface Synced {
    val id: String
    val updated_at: String
    val deleted_at: String?
    val dirty: Boolean
}

@Serializable
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey override val id: String = newId(),
    val kind: String = "car",
    val year: Int? = null,
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val nickname: String = "",
    val vin: String = "",
    val current_mileage: Int? = null,
    val mileage_unit: String = "mi",
    val photo_attachment_id: String? = null,
    val parts_url: String = "",
    val owners_url: String = "",
    val notes: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced {
    /** Display name: nickname, else "year make model". */
    fun displayName(): String = nickname.ifBlank { listOfNotNull(year?.toString(), make, model).filter { it.isNotBlank() }.joinToString(" ") }.ifBlank { "Vehicle" }
    fun fullName(): String = listOfNotNull(year?.toString(), make, model, trim).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
@Entity(tableName = "specs", indices = [Index("vehicle_id")])
data class Spec(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String,
    val key: String,
    val value: String = "",
    val source: String = "user",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "entries", indices = [Index("vehicle_id")])
data class Entry(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String,
    val kind: String = "maintenance",
    val type_key: String = "custom",
    val custom_type: String = "",
    val date: String,
    val mileage: Int? = null,
    val description: String = "",
    val parts_used: String = "",
    val cost_cents: Int? = null,
    val performed_by: String = "diy",
    val shop_name: String = "",
    val notes: String = "",
    val source: String = "manual",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "attachments", indices = [Index("vehicle_id"), Index("entry_id")])
data class Attachment(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String? = null,
    val entry_id: String? = null,
    val category: String = "photo",
    val content_type: String = "image/jpeg",
    val original_name: String = "",
    val caption: String = "",
    val size_bytes: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val page_count: Int? = null,
    val has_file: Boolean = false,
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
    /** Path of a photo taken on this phone that still needs uploading. */
    @Transient val localPath: String? = null,
) : Synced

@Serializable
@Entity(tableName = "reminders", indices = [Index("vehicle_id")])
data class Reminder(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String,
    val type_key: String = "custom",
    val title: String = "",
    val interval_miles: Int? = null,
    val interval_months: Int? = null,
    val last_done_date: String? = null,
    val last_done_mileage: Int? = null,
    val enabled: Boolean = true,
    val notes: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "odometer", indices = [Index("vehicle_id")])
data class OdometerReading(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String,
    val date: String,
    val mileage: Int,
    val note: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

@Serializable
@Entity(tableName = "parts", indices = [Index("vehicle_id")])
data class Part(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String,
    val type_key: String = "custom",
    val name: String = "",
    val part_number: String = "",
    val brand: String = "",
    val notes: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

/**
 * One line of the shopping list: something to buy before doing the work.
 * vehicle_id is optional (a general item); reminder_id / part_id say where
 * it came from.  Never an expense, and ticking it off never marks
 * maintenance done.
 */
@Serializable
@Entity(tableName = "shopping_items", indices = [Index("vehicle_id")])
data class ShoppingItem(
    @PrimaryKey override val id: String = newId(),
    val vehicle_id: String? = null,
    val reminder_id: String? = null,
    val part_id: String? = null,
    val type_key: String = "custom",
    val description: String = "",
    val part_number: String = "",
    val spec: String = "",
    val quantity: Int = 1,
    val purchased: Boolean = false,
    val notes: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = nowIso(),
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

/**
 * A safety recall NHTSA lists for this vehicle's year/make/model, and what
 * the owner knows about it.  The id is fixed per vehicle + campaign (same as
 * the server), and rows added by a lookup carry updated_at = 1970 so any edit
 * by the owner always wins when syncing.
 */
@Serializable
@Entity(tableName = "vehicle_recalls", indices = [Index("vehicle_id")])
data class VehicleRecall(
    @PrimaryKey override val id: String,
    val vehicle_id: String,
    val campaign: String,
    val component: String = "",
    val summary: String = "",
    val consequence: String = "",
    val remedy: String = "",
    val report_date: String? = null,
    val park_it: Boolean = false,
    val status: String = "open",          // open | done | not_applicable
    val done_date: String? = null,
    val entry_id: String? = null,
    val notes: String = "",
    val created_at: String = nowIso(),
    override val updated_at: String = LOOKUP_TIME,
    override val deleted_at: String? = null,
    @Transient override val dirty: Boolean = false,
) : Synced

/** Edit time of rows added by a lookup (older than any real edit). */
const val LOOKUP_TIME = "1970-01-01T00:00:00Z"

/** Per-user settings from the server (theme, units, currency). */
@Serializable
data class UserSettings(
    val theme: String = "system",
    val distance_unit: String = "mi",
    val currency: String = "USD",
    val ntfy_enabled: Boolean = false,
    val ntfy_topic: String = "",
)

/** The server's answer to GET /api/sync. */
@Serializable
data class SyncPull(
    val server_time: String,
    val next_since: String,
    val vehicles: List<Vehicle> = emptyList(),
    val specs: List<Spec> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val odometer: List<OdometerReading> = emptyList(),
    val parts: List<Part> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val shopping: List<ShoppingItem> = emptyList(),
    val recalls: List<VehicleRecall> = emptyList(),
    val settings: UserSettings? = null,
)
