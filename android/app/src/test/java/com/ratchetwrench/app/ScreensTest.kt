// ScreensTest.kt — opens the REAL app screens on the build machine
// (Robolectric) and taps through them like a user would, so crashes are
// caught before a release instead of on your phone.
//
// Camera screens (VIN barcode, photo capture) can't run here — they need
// real camera hardware — so they are covered by the release APK check
// (android/check_apk.py) instead.
package com.ratchetwrench.app

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real app, with WorkManager switched to its test mode first. */
class TestApp : RatchetApp() {
    override fun onCreate() {
        // Each test gets a brand-new app: forget the previous test's database.
        com.ratchetwrench.app.data.AppDatabase.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Wait (up to 5 s) until some text is on screen — database work runs in the background. */
    private fun waitForText(text: String, substring: Boolean = false) {
        try {
            rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            // Print what IS on screen so a failure is easy to diagnose.
            throw AssertionError("\"$text\" never appeared. Screen:\n" +
                rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().joinToString("\n") { it.config.toString() } +
                "\n" + runCatching { rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].printToString() }.getOrDefault(""), e)
        }
    }

    /** Tap the first thing showing (or labelled with) this text. */
    private fun click(text: String) {
        val matcher = hasText(text) or androidx.compose.ui.test.hasContentDescription(text)
        try {
            rule.waitUntil(5_000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) { waitForText(text) } // fails with a screen dump
        val node = rule.onAllNodes(matcher)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun type(label: String, text: String) {
        waitForText(label)
        rule.onAllNodes(hasText(label) and hasSetTextAction())[0].performScrollTo().performTextInput(text)
    }

    /** Wait until some text has left the screen (e.g. a form has closed). */
    private fun waitGone(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    /** Tap the icon-only button with this screen-reader label. */
    private fun tapIcon(description: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription(description)[0].performClick()
        rule.waitForIdle()
    }

    private fun startPhoneOnlyWithVehicle() {
        click("Start on this phone")
        waitForText("No vehicles yet")
        click("Add vehicle")
        waitForText("Save vehicle")
        type("Make", "Lexus")
        type("Model", "RC 350")
        type("Current mileage", "42000")
        click("Save vehicle")
        waitGone("Save vehicle")
        waitForText("Add entry")
    }

    @Test
    fun addViewEditDeleteEntry() {
        startPhoneOnlyWithVehicle()
        click("Add entry")
        waitForText("Save entry")
        type("Description", "Oil and filter")
        type("Total cost", "89.95")
        click("A shop did it")
        type("Shop name", "Corner Garage")
        click("Save entry")
        waitGone("Save entry")
        click("History")
        waitForText("Oil and filter")

        // View it, then edit it.
        click("Oil and filter")
        waitForText("Corner Garage")
        tapIcon("Edit entry")
        waitForText("Save entry")
        type("Notes", "Used 0W-20")
        click("Save entry")
        waitGone("Save entry")
        waitForText("Used 0W-20")

        // Delete it and make sure it's really gone.
        tapIcon("Delete entry")
        click("Delete")
        waitForText("No records yet", substring = true)
    }

    // NOTE: dialogs that contain a text box (Mileage, spec value, reminder,
    // part) are not opened here: on this toolchain Robolectric never lets a
    // Material dialog with a text field "settle" — a bare dialog with one
    // text field and none of our code hangs the same way.  Instead the data
    // those dialogs save is put in directly, and every tab is drawn with it.
    @Test
    fun everyVehicleTab() {
        startPhoneOnlyWithVehicle()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            repo.addOdometer(v.id, "2026-11-01", 43500)
            repo.saveSpec(v.id, com.ratchetwrench.app.domain.Catalog.get(ctx).specs.first().key, "Test value", "user")
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "oil_change",
                interval_miles = 5000, last_done_mileage = 37000, last_done_date = "2026-06-01"))
            repo.savePart(com.ratchetwrench.app.data.Part(vehicle_id = v.id, type_key = "oil_filter", part_number = "04152-YZZA1"))
        }

        // Tabs in the order they're used; no Odometer tab (mileage is the header button).
        org.junit.Assert.assertTrue("no Odometer tab", rule.onAllNodesWithText("Odometer").fetchSemanticsNodes().isEmpty())
        waitForText("43,500", substring = true)
        click("Specs")
        waitForText("Test value")
        click("Reminders")
        waitForText("every 5,000 mi", substring = true)
        click("Parts")
        waitForText("04152-YZZA1")

        // Smart Shopping on a part: the exact OEM part number is searched.
        tapIcon("Shop for Oil filter")
        waitForText("Shop — Oil filter")
        waitForText("“04152-YZZA1”")
        waitForText("Amazon"); waitForText("Walmart"); waitForText("Advance Auto Parts")
        click("Close")

        // Smart Shopping on a reminder: oil + the saved oil filter part number;
        // no oil type in Specs yet, so it says so instead of guessing.
        click("Reminders")
        tapIcon("Find supplies for Oil change")
        waitForText("Find supplies — Oil change")
        waitForText("“Lexus RC 350 engine oil”")
        waitForText("“OEM 04152-YZZA1 oil filter”")
        waitForText("Tip: add", substring = true)
        click("Close")

        // History tab filters.
        click("History")
        click("Repairs")
        click("All")

        // Door sticker screen opens and goes back.
        click("Door sticker")
        waitForText("Scan door sticker")
        tapIcon("Back")

        // No separate "Coming up" card any more: the Reminders tab (first) shows it
        // (last done at 37,000 mi, due at 42,000, the car is at 43,500 -> overdue).
        click("Reminders")
        waitForText("Overdue")
        org.junit.Assert.assertTrue("no Coming up card", rule.onAllNodesWithText("Coming up").fetchSemanticsNodes().isEmpty())

        // Edit vehicle screen.
        tapIcon("Edit vehicle")
        waitForText("Delete vehicle")
        type("Nickname (optional)", "Daily")
        click("Save vehicle")
        waitGone("Save vehicle")
        waitForText("Daily")

        // Back on the garage: just the vehicles — no maintenance list or recent work.
        tapIcon("Back")
        waitForText("Daily")
        rule.waitForIdle()
        org.junit.Assert.assertTrue("garage keeps it simple",
            rule.onAllNodesWithText("Coming up").fetchSemanticsNodes().isEmpty() && rule.onAllNodesWithText("Recent work").fetchSemanticsNodes().isEmpty())
    }

    /** Tapping an overdue reminder -> "Mark as done" -> it's OK and in the history. */
    @Test
    fun markReminderDoneClearsOverdueAndAddsHistory() {
        startPhoneOnlyWithVehicle()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            // Last done long ago at 30,000 mi, every 5,000 mi or 6 months: overdue both ways.
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "tire_rotation",
                interval_miles = 5000, interval_months = 6, last_done_mileage = 30000, last_done_date = "2025-01-01"))
        }
        click("Reminders")
        waitForText("Overdue")
        click("Tire rotation")
        waitForText("Did it? Log it as done")
        waitForText("42000")          // mileage pre-filled with the vehicle's current mileage
        click("Mark as done")
        waitForText("Marked as done and added to the history.")
        waitForText("OK")
        tapIcon("Back")
        // The list shows it as OK now, and the history has the record.
        waitForText("OK")
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Overdue").fetchSemanticsNodes().isEmpty())
        click("History")
        waitForText("Tire rotation")
    }

    /** Shopping list: from a reminder, from a part, typed in; tick off; remove. */
    @Test
    fun shoppingListFromReminderPartAndManual() {
        startPhoneOnlyWithVehicle()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            repo.saveSpec(v.id, "oil_type", "0W-20 full synthetic", "user")
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "oil_change",
                interval_miles = 5000, last_done_mileage = 30000, last_done_date = "2026-06-01"))
            repo.savePart(com.ratchetwrench.app.data.Part(vehicle_id = v.id, type_key = "air_filter", name = "Engine air filter", part_number = "17801-31120"))
        }
        // 1. From a reminder: oil (with the oil type from Specs) + oil filter.
        click("Reminders")
        click("Oil change")
        waitForText("Did it? Log it as done")
        click("Add to shopping list")
        waitForText("Added 2 items to the shopping list.")
        click("Add to shopping list")
        waitForText("Already on your shopping list.")
        tapIcon("Back")

        // 2. From a saved part.
        click("Parts")
        tapIcon("Add Engine air filter to the shopping list")

        // 3. The list (from the Garage).
        tapIcon("Back")
        tapIcon("Shopping list, 3 to buy")
        waitForText("Engine oil")
        waitForText("0W-20 full synthetic")
        waitForText("For: Oil change", substring = true)
        waitForText("OEM 17801-31120")

        // 4. Typed in.
        tapIcon("Add item")
        waitForText("Save item")
        type("Item", "Shop towels")
        type("Notes (optional)", "the blue ones")
        click("Save item")
        waitGone("Save item")
        waitForText("Shop towels")
        waitForText("Not for a vehicle")

        // 5. Bought: tick it off, then remove bought items.  The reminder stays as it was.
        tapIcon("Bought Engine oil")
        click("Remove bought")
        click("Remove")
        waitGone("Engine oil")
        kotlinx.coroutines.runBlocking {
            val r = repo.db.reminders().all().first()
            org.junit.Assert.assertEquals("buying never marks maintenance done", 30000, r.last_done_mileage)
            org.junit.Assert.assertTrue("no expense created", repo.db.entries().dirty().isEmpty())
        }
        // 6. Remove one directly.
        tapIcon("Remove Shop towels")
        waitGone("Shop towels")
        waitForText("Oil filter")
    }

    /** Overdue on top, then due soon, then OK; specs that are parts show up on the Parts tab. */
    @Test
    fun remindersByUrgencyAndSpecsBecomeParts() {
        startPhoneOnlyWithVehicle()   // at 42,000 mi
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "wipers", interval_miles = 50000, last_done_mileage = 40000))      // OK
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "brake_fluid", interval_miles = 2300, last_done_mileage = 40000))  // due soon
            repo.saveReminder(com.ratchetwrench.app.data.Reminder(vehicle_id = v.id, type_key = "tire_rotation", interval_miles = 5000, last_done_mileage = 30000)) // overdue
            repo.saveSpec(v.id, "oil_filter_part", "04152-YZZA1", "user")
            repo.saveSpec(v.id, "tire_size_rear", "255/35R19", "user")
            repo.saveSpec(v.id, "paint_code", "223", "user")
        }
        // The vehicle opens on Reminders, most urgent first.
        waitForText("Tire rotation")
        val top = { t: String -> rule.onAllNodesWithText(t)[0].fetchSemanticsNode().boundsInRoot.top }
        org.junit.Assert.assertTrue("overdue above due soon", top("Tire rotation") < top("Brake fluid"))
        org.junit.Assert.assertTrue("due soon above OK", top("Brake fluid") < top("Wiper blades"))

        click("Parts")
        waitForText("04152-YZZA1")
        waitForText("Rear tires")
        waitForText("255/35R19")
        // The parts site button is only on the Parts tab, not in the header too.
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Parts site").fetchSemanticsNodes().isEmpty())
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            org.junit.Assert.assertEquals("bbdf75ad-064d-3dd7-9a2b-7ded63f937fd",
                repo.specPartId("11111111-2222-4333-8444-555555555555", "oil_filter_part"))  // same as the server
            // Changing the spec updates the same part; clearing it removes the part.
            repo.saveSpec(v.id, "oil_filter_part", "90915-YZZD1", "user")
            repo.deleteSpec(repo.db.specs().find(v.id, "tire_size_rear")!!.id)
        }
        waitForText("90915-YZZD1")
        waitGone("Rear tires")
    }

    /** Scroll whichever list holds this text (lists only build what's visible). */
    private fun scrollTo(text: String, substring: Boolean = false) {
        rule.waitForIdle()
        val lists = rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())
        for (i in 0 until lists.fetchSemanticsNodes().size) {
            if (runCatching { lists[i].performScrollToNode(hasText(text, substring = substring) or androidx.compose.ui.test.hasContentDescription(text, substring = substring)) }.isSuccess) break
        }
        val m = hasText(text, substring = substring) or androidx.compose.ui.test.hasContentDescription(text, substring = substring)
        try { rule.waitUntil(5_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) { waitForText(text, substring) }
    }

    /** Save what's on screen (for reviewing the design); build/metrics-shots/<name>.png */
    private fun snap(name: String) {
        rule.waitForIdle()
        val img = rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].captureToImage().asAndroidBitmap()
        val dir = java.io.File("build/metrics-shots").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Metrics: every number opens the records behind it, and they add up. */
    @Test
    fun metricsDrillDownAddsUp() {
        startPhoneOnlyWithVehicle()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            fun e(d: String, t: String, k: String, m: Int?, c: Int?, by: String = "shop") =
                com.ratchetwrench.app.data.Entry(vehicle_id = v.id, date = d, type_key = t, kind = k, mileage = m, cost_cents = c, performed_by = by)
            listOf(e("2023-03-01", "oil_change", "maintenance", 30000, 5000, "diy"), e("2023-09-01", "oil_change", "maintenance", 35000, 6000),
                e("2024-03-01", "oil_change", "maintenance", 40500, null, "diy"), e("2024-06-15", "brake_pads", "repair", 42000, 30000),
                e("2025-12-01", "tire_rotation", "maintenance", 41000, 2500), e("2026-01-10", "tire_replacement", "repair", 41500, 100000))
                .forEach { repo.saveEntry(it, isNew = true) }
        }
        click("Metrics")
        // $50 + $60 + $0 + $300 + $25 + $1,000 = $1,435 across 5 records with a cost.
        snap("1-overview")
        tapIcon("Total spent: \$1,435.00")
        scrollTo("\$1,435.00 across 5 records")
        snap("2-costs")
        scrollTo("Total (5)")
        // A category filters everything: Brakes = 1 record, $300.
        scrollTo("Category Brakes: 1")
        tapIcon("Category Brakes: 1")
        scrollTo("Remove filter: Brakes")
        tapIcon("Remove filter: Brakes")
        scrollTo("Category Tires & wheels: 2")
        tapIcon("Category Tires & wheels: 2")
        scrollTo("\$1,025.00 across 2 records")
        scrollTo("Remove filter: Tires & wheels")
        tapIcon("Remove filter: Tires & wheels")
        // A service: oil changes, and the time between them.
        scrollTo("Service Oil change: 3")
        tapIcon("Service Oil change: 3")
        scrollTo("Total (3)")
        scrollTo("Intervals")
        click("Intervals")
        scrollTo("Average (2 gaps)")
        waitForText("5,250 mi · 6.0 mo")     // (5,000 + 5,500) / 2 ; 366 days / 2 / 30.4375
        snap("3-intervals")
        scrollTo("Remove filter: Oil change")
        tapIcon("Remove filter: Oil change")
        // A year's repair part: 2024 repairs = the brake job.
        scrollTo("2024 repairs: 1")
        tapIcon("2024 repairs: 1")
        scrollTo("1 record · \$300.00")
        // Tapping a record opens it.
        scrollTo("Brake pads · repair")
        click("Brake pads · repair")
        waitForText("Brake pads")
    }

    /** History → Recalls: open first, Mark as done adds it to the history, Doesn't apply. */
    @Test
    fun recallsInHistory() {
        startPhoneOnlyWithVehicle()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.ratchetwrench.app.data.Repository(ctx)
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            fun rc(c: String, comp: String, d: String, remedy: String = "") = com.ratchetwrench.app.data.VehicleRecall(id = repo.recallId(v.id, c),
                vehicle_id = v.id, campaign = c, component = comp, summary = "Summary of $c", consequence = "Risk of $c", remedy = remedy, report_date = d)
            repo.db.recalls().upsert(rc("20V012000", "FUEL SYSTEM, GASOLINE:DELIVERY:FUEL PUMP", "2020-01-13"))
            repo.db.recalls().upsert(rc("17V773000", "SERVICE BRAKES", "2017-12-01", "Kia's number for this recall is SC158."))
            // A recall already in the history (e.g. from Carfax) under the carmaker's number.
            repo.saveEntry(com.ratchetwrench.app.data.Entry(vehicle_id = v.id, type_key = "recall", date = "2019-03-01", description = "Recall SC158 performed"), isNew = true)
        }
        click("History")
        click("Recalls (1)")
        waitForText("Fuel System, Gasoline:Delivery:Fuel Pump", substring = true)
        waitForText("Done Mar 1, 2019 — see the history record")   // matched by the carmaker's number SC158
        click("Mark as done")
        waitForText("Mark recall as done")
        type("Dealer / shop (optional)", "Lexus of Town")
        click("Mark as done")
        waitGone("Mark recall as done")
        kotlinx.coroutines.runBlocking {
            val v = repo.db.vehicles().observeAll().first().first()
            val r = repo.db.recalls().get(repo.recallId(v.id, "20V012000"))!!
            org.junit.Assert.assertEquals("done", r.status)
            val e = repo.db.entries().get(r.entry_id!!)!!
            org.junit.Assert.assertEquals("recall", e.type_key)
            org.junit.Assert.assertEquals("Lexus of Town", e.shop_name)
            org.junit.Assert.assertTrue(e.description.startsWith("Recall 20V012000"))
        }
    }

    /** Sync problem banner: small, Resync, fix link after a failed retry, and ✕ to dismiss. */
    @Test
    fun syncBannerIsCompactAndDismissible() {
        click("Start on this phone")
        waitForText("No vehicles yet")
        val store = com.ratchetwrench.app.data.SecureStore(ApplicationProvider.getApplicationContext())
        store.serverUrl = "http://127.0.0.1:9"   // nothing listens there
        store.allowHttpLocal = true
        com.ratchetwrench.app.data.SecureStore.tokenForTests = "t"
        try {
            store.token = "t"
            store.mode = "server"
            // Pull to refresh = a sync attempt.
            rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[0].performTouchInput { swipeDown() }
            waitForText("Not synced with your computer")
            click("Resync")
            waitForText("Still can't reach your computer")
            waitForText("Fix in Settings")
            tapIcon("Dismiss")
            waitGone("Still can't reach your computer")
        } finally {
            // Don't leave this test's "signed in" state for the next tests.
            store.mode = "local"
            com.ratchetwrench.app.data.SecureStore.tokenForTests = null
        }
    }

    @Test
    fun settingsAboutAndTheme() {
        startPhoneOnlyWithVehicle()
        tapIcon("Back")
        click("Settings")
        waitForText("Appearance")
        click("Dark")
        click("Light")
        click("System")
        click("Kilometers")
        click("Miles")
        waitForText("Release notes")
        waitForText("Report a bug")
        // The in-app form (with the owner's reminder and the details that will be sent).
        rule.onAllNodes(hasText("Report a bug") and androidx.compose.ui.test.hasClickAction())[0].performScrollTo().performClick()
        waitForText("What's the problem? (one line)")
        waitForText("Reminder for the owner", substring = true)
        waitForText("Include these technical details")
        waitForText("Ratchet Wrench", substring = true)
        // This local test build has no key, so it offers GitHub instead of sending.
        waitForText("Continue on GitHub")
        click("Continue on GitHub")
        waitForText("Please describe the problem in a few words.")
        tapIcon("Back")
        waitForText("Smart Shopping")
        waitForText("About Ratchet Wrench")
        waitForText("Version", substring = true)
        tapIcon("Back")
        waitForText("Lexus RC 350")
    }

    @Test
    fun reopenWithSavedData() {
        startPhoneOnlyWithVehicle()
        click("Add entry")
        type("Description", "Brake pads")
        click("Save entry")
        waitForText("Brake pads")
        // Rotate / system kills the screen and restores it.
        rule.activityRule.scenario.recreate()
        waitForText("Brake pads")
        // Close the app completely and open it again (like swiping it away).
        rule.activityRule.scenario.close()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Lexus RC 350").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test
    fun addVehicleFormKeepsWhatYouTyped() {
        // Going to the camera / barcode scanner and back rebuilds the screen;
        // nothing typed (or decoded from the VIN) may be lost.
        click("Start on this phone")
        click("Add vehicle")
        waitForText("Save vehicle")
        type("Make", "Acura")
        type("Model", "TL")
        type("Current mileage", "123456")
        waitForText("Vehicle photo")
        rule.activityRule.scenario.recreate()
        waitForText("Save vehicle")
        waitForText("Acura")
        waitForText("TL")
        waitForText("123456")
    }

    @Test
    fun connectScreenOffersQrCode() {
        click("Connect")
        waitForText("Scan QR code")
        waitForText("Sign in")
    }

    @Test
    fun homeAddressAsksBeforePlainHttp() {
        click("Connect")
        waitForText("Sign in")
        type("Computer or server address", "192.168.1.20:8077")
        click("Sign in")
        // Instead of a cryptic TLS error: ask, with the http:// address.
        waitForText("Connect with plain HTTP?")
        waitForText("http://192.168.1.20:8077", substring = true)
        click("Cancel")
        waitGone("Connect with plain HTTP?")
    }

    @Test
    fun everyCatalogTypeHasAnIcon() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val missing = com.ratchetwrench.app.domain.Catalog.get(ctx).types.map { it.key }
            .filter { it !in com.ratchetwrench.app.ui.TYPE_ICON_RES }
        org.junit.Assert.assertEquals("types without an icon", emptyList<String>(), missing)
    }
}
