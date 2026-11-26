// LiveSyncTest.kt — runs the phone's REAL sync code against a REAL server,
// several times in a row, the way the app does in daily use:
//   sign in → sync → change things on the phone → sync → change things on
//   the server → sync → sync again with nothing changed.
//
// Opt-in: it only runs when RW_E2E_URL points at a running server, e.g.
//   RW_E2E_URL=http://127.0.0.1:8766 RW_E2E_USER=admin RW_E2E_PASS=... \
//   ./gradlew :app:testDebugUnitTest --tests '*LiveSyncTest*'
// Normal builds skip it.
package com.ratchetwrench.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.AppDatabase
import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class LiveSyncTest {
    @Test
    fun syncWorksAgainAndAgain() = runBlocking {
        val url = System.getenv("RW_E2E_URL")
        assumeTrue("set RW_E2E_URL to run", !url.isNullOrBlank())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = Repository(ctx)
        val store = repo.store
        store.serverUrl = url!!
        store.allowHttpLocal = true
        com.ratchetwrench.app.data.SecureStore.tokenForTests = "pending"
        store.token = Api(store).login(System.getenv("RW_E2E_USER") ?: "admin", System.getenv("RW_E2E_PASS") ?: "")
        store.mode = "server"
        store.lastSince = null

        fun sync(step: String) {
            try { SyncEngine(ctx).run() } catch (e: Exception) { throw AssertionError("Sync failed at: $step — ${e::class.simpleName}: ${e.message}", e) }
        }

        // 1) First sync: everything comes down.
        sync("first sync")
        val vehicles = AppDatabase.get(ctx).vehicles().observeAll().first()
        assertTrue("vehicles pulled", vehicles.isNotEmpty())
        println("after sync 1: ${vehicles.size} vehicles, ${AppDatabase.get(ctx).entries().observeRecent(1000).first().size} entries")
        // Every record the server has must be on the phone.
        val server = com.ratchetwrench.app.data.JsonFormat.decodeFromString(
            com.ratchetwrench.app.data.SyncPull.serializer(), Api(store).get("/api/sync"))
        for (veh in vehicles) {
            val phone = AppDatabase.get(ctx).entries().observeFor(veh.id).first().size
            val srv = server.entries.count { it.vehicle_id == veh.id && it.deleted_at == null }
            println("  ${veh.displayName()}: phone $phone / server $srv entries")
        }
        val phoneTotal = vehicles.sumOf { AppDatabase.get(ctx).entries().observeFor(it.id).first().size }
        assertEquals("all server entries on the phone", server.entries.count { it.deleted_at == null }, phoneTotal)

        // 2) Change things on the phone, sync again.
        val v = Vehicle(make = "Kia", model = "Forte", year = 2014, current_mileage = 90000)
        repo.saveVehicle(v)
        val e = Entry(vehicle_id = v.id, date = "2026-11-01", type_key = "oil_change", description = "Phone-made entry", mileage = 90100)
        repo.saveEntry(e, isNew = true)
        repo.addOdometer(v.id, "2026-11-02", 90200)
        sync("second sync (after phone changes)")

        // 3) Change something on the server, sync again: the phone must see it.
        val api = Api(store)
        api.send("PATCH", "/api/entries/${e.id}", JsonObject(mapOf("description" to JsonPrimitive("Edited on the computer"))))
        sync("third sync (after a server change)")
        assertEquals("server edit reached the phone", "Edited on the computer", AppDatabase.get(ctx).entries().get(e.id)?.description)

        // 4) Nothing changed: still fine.
        sync("fourth sync (nothing changed)")
        // 5) Edit the same entry on the phone again.
        repo.saveEntry(AppDatabase.get(ctx).entries().get(e.id)!!.copy(notes = "Edited on the phone"), isNew = false)
        sync("fifth sync (phone edit of a synced record)")
        // 6) Shopping list: made on the phone -> on the server; ticked off on
        //    the computer -> ticked on the phone; a general item has no vehicle.
        val line = com.ratchetwrench.app.data.ShoppingItem(vehicle_id = v.id, description = "Oil filter", part_number = "26300-35505", quantity = 2)
        val general = com.ratchetwrench.app.data.ShoppingItem(description = "Shop towels")
        repo.saveShoppingItem(line); repo.saveShoppingItem(general)
        sync("sixth sync (shopping list made on the phone)")
        val onServer = com.ratchetwrench.app.data.JsonFormat.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.ratchetwrench.app.data.ShoppingItem.serializer()), api.get("/api/shopping"))
        assertEquals(2, onServer.firstOrNull { it.id == line.id }?.quantity)
        assertEquals(null, onServer.firstOrNull { it.id == general.id }?.vehicle_id)
        api.send("PATCH", "/api/shopping/${line.id}", JsonObject(mapOf("purchased" to kotlinx.serialization.json.JsonPrimitive(true))))
        sync("seventh sync (ticked off on the computer)")
        assertEquals(true, AppDatabase.get(ctx).shopping().get(line.id)?.purchased)
        repo.deleteShoppingItem(general.id)
        sync("eighth sync (removed on the phone)")
        assertTrue(com.ratchetwrench.app.data.JsonFormat.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.ratchetwrench.app.data.ShoppingItem.serializer()), api.get("/api/shopping"))
            .none { it.id == general.id })
        println("all syncs OK")
    }
}
