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

    @Query("SELECT * FROM specs WHERE deleted_at IS NULL")
    suspend fun allLive(): List<Spec>

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

    /** Every vehicle photo, newest first (for the garage list and vehicle header). */
    @Query("SELECT * FROM attachments WHERE category = 'vehicle_photo' AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeVehiclePhotos(): Flow<List<Attachment>>

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

    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observe(id: String): Flow<Reminder?>

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

    @Query("SELECT * FROM parts WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    suspend fun forVehicle(vehicleId: String): List<Part>

    @Upsert suspend fun upsert(item: Part)

    @Query("DELETE FROM parts WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface ShoppingDao {
    @Query("UPDATE shopping_items SET dirty = 1")
    suspend fun markAllDirty()

    /** The list: still to buy first, then oldest first (same order as the server). */
    @Query("SELECT * FROM shopping_items WHERE deleted_at IS NULL ORDER BY purchased, created_at")
    fun observeAll(): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM shopping_items WHERE deleted_at IS NULL")
    suspend fun all(): List<ShoppingItem>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    fun observe(id: String): Flow<ShoppingItem?>

    @Query("SELECT * FROM shopping_items WHERE id = :id")
    suspend fun get(id: String): ShoppingItem?

    @Query("SELECT * FROM shopping_items WHERE dirty = 1")
    suspend fun dirty(): List<ShoppingItem>

    @Upsert suspend fun upsert(item: ShoppingItem)

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface RecallDao {
    @Query("UPDATE vehicle_recalls SET dirty = 1")
    suspend fun markAllDirty()

    @Query("SELECT * FROM vehicle_recalls WHERE vehicle_id = :vehicleId AND deleted_at IS NULL")
    fun observeFor(vehicleId: String): Flow<List<VehicleRecall>>

    @Query("SELECT * FROM vehicle_recalls WHERE id = :id")
    fun observe(id: String): Flow<VehicleRecall?>

    @Query("SELECT * FROM vehicle_recalls WHERE id = :id")
    suspend fun get(id: String): VehicleRecall?

    @Query("SELECT * FROM vehicle_recalls WHERE dirty = 1")
    suspend fun dirty(): List<VehicleRecall>

    @Upsert suspend fun upsert(item: VehicleRecall)

    @Query("DELETE FROM vehicle_recalls WHERE id = :id")
    suspend fun remove(id: String)
}

// Version 2 added the shopping list table (an automatic migration: new
// table only, nothing existing changes, so no data is touched).
@Database(
    entities = [Vehicle::class, Spec::class, Entry::class, Attachment::class, Reminder::class, OdometerReading::class, Part::class,
        ShoppingItem::class, VehicleRecall::class],
    version = 3,
    exportSchema = true,
    // Each version only ADDED a table (2: shopping list, 3: recalls), so the
    // automatic migrations never touch existing data.
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun specs(): SpecDao
    abstract fun entries(): EntryDao
    abstract fun attachments(): AttachmentDao
    abstract fun reminders(): ReminderDao
    abstract fun odometer(): OdometerDao
    abstract fun parts(): PartDao
    abstract fun shopping(): ShoppingDao
    abstract fun recalls(): RecallDao

    /** Before the first sync with a server, queue everything made on this
     *  phone so it is uploaded (photos taken here are queued separately). */
    suspend fun markEverythingForUpload() {
        vehicles().markAllDirty(); specs().markAllDirty(); entries().markAllDirty(); reminders().markAllDirty()
        odometer().markAllDirty(); parts().markAllDirty(); shopping().markAllDirty(); recalls().markAllDirty()
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
