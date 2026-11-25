// Database.kt — the on-phone database (Room / SQLite) and its queries.
//
// Screens observe these queries as live "Flows", so the moment a record is
// saved (by you, or by a sync from the server) every screen updates itself.
// Deleted records stay in the table with deleted_at set (soft delete) until
// the server has been told; screens simply filter them out.
package com.ratchetwrench.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE vehicles SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM vehicles WHERE deleted_at IS NULL ORDER BY created_at")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observe(id: String): Flow<Vehicle?>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: String): Vehicle?

    @Query("SELECT * FROM vehicles WHERE deleted_at IS NULL")
    suspend fun all(): List<Vehicle>

    @Query("SELECT * FROM vehicles WHERE dirty = 1")
    suspend fun dirty(): List<Vehicle>

    @Upsert suspend fun upsert(item: Vehicle)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface SpecDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE specs SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM specs WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    fun observeFor(vehicleId: String): Flow<List<Spec>>

    @Query("SELECT * FROM specs WHERE vehicle_id = :vehicleId AND `key` = :key AND deleted_at IS NULL LIMIT 1")
    suspend fun find(vehicleId: String, key: String): Spec?

    @Query("SELECT * FROM specs WHERE id = :id")
    suspend fun get(id: String): Spec?

    @Query("SELECT * FROM specs WHERE dirty = 1")
    suspend fun dirty(): List<Spec>

    @Upsert suspend fun upsert(item: Spec)

    @Query("DELETE FROM specs WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface EntryDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE entries SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM entries WHERE vehicle_id = :vehicleId AND deleted_at IS NULL ORDER BY date DESC, mileage DESC")
    fun observeFor(vehicleId: String): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE deleted_at IS NULL ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observe(id: String): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: String): Entry?

    @Query("SELECT * FROM entries WHERE dirty = 1")
    suspend fun dirty(): List<Entry>

    @Upsert suspend fun upsert(item: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface AttachmentDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE attachments SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM attachments WHERE entry_id = :entryId AND deleted_at IS NULL")
    fun observeForEntry(entryId: String): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    fun observeForVehicle(vehicleId: String): Flow<List<Attachment>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): Attachment?

    /** Photos taken on the phone that still need uploading. */
    @Query("SELECT * FROM attachments WHERE localPath IS NOT NULL AND has_file = 0 AND deleted_at IS NULL")
    suspend fun pendingUploads(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE dirty = 1")
    suspend fun dirty(): List<Attachment>

    @Upsert suspend fun upsert(item: Attachment)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface ReminderDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE reminders SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM reminders WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    fun observeFor(vehicleId: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE deleted_at IS NULL")
    suspend fun all(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE vehicle_id = :vehicleId AND type_key = :typeKey AND deleted_at IS NULL")
    suspend fun forType(vehicleId: String, typeKey: String): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): Reminder?

    @Query("SELECT * FROM reminders WHERE dirty = 1")
    suspend fun dirty(): List<Reminder>

    @Upsert suspend fun upsert(item: Reminder)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface OdometerDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE odometer SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM odometer WHERE vehicle_id = :vehicleId AND deleted_at IS NULL ORDER BY date DESC")
    fun observeFor(vehicleId: String): Flow<List<OdometerReading>>

    @Query("SELECT * FROM odometer WHERE id = :id")
    suspend fun get(id: String): OdometerReading?

    @Query("SELECT * FROM odometer WHERE dirty = 1")
    suspend fun dirty(): List<OdometerReading>

    @Upsert suspend fun upsert(item: OdometerReading)

    @Query("DELETE FROM odometer WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface PartDao {
    /** Mark every record as "needs sending" (used when connecting for the first time). */
    @Query("UPDATE parts SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM parts WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    fun observeFor(vehicleId: String): Flow<List<Part>>

    @Query("SELECT * FROM parts WHERE id = :id")
    suspend fun get(id: String): Part?

    @Query("SELECT * FROM parts WHERE dirty = 1")
    suspend fun dirty(): List<Part>

    @Upsert suspend fun upsert(item: Part)

    @Query("DELETE FROM parts WHERE id = :id")
    suspend fun remove(id: String)
}

@Database(
    entities = [Vehicle::class, Spec::class, Entry::class, Attachment::class, Reminder::class, OdometerReading::class, Part::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun specs(): SpecDao
    abstract fun entries(): EntryDao
    abstract fun attachments(): AttachmentDao
    abstract fun reminders(): ReminderDao
    abstract fun odometer(): OdometerDao
    abstract fun parts(): PartDao

    /** Before the first sync with a server, queue everything made on this
     *  phone so it is uploaded (photos taken here are queued separately). */
    suspend fun markEverythingForUpload() {
        vehicles().markAllDirty(); specs().markAllDirty(); entries().markAllDirty(); reminders().markAllDirty()
        odometer().markAllDirty(); parts().markAllDirty()
    }

    /** Remove every record (used when you sign out). */
    suspend fun wipe() = clearAllTables()

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ratchet-wrench.db")
                .build().also { instance = it }
        }

        /** Tests only: close the database so the next test starts fresh. */
        @androidx.annotation.VisibleForTesting
        fun resetForTests() = synchronized(this) { instance?.close(); instance = null }
    }
}
