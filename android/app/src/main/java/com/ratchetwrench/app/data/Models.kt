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
    val settings: UserSettings? = null,
)
