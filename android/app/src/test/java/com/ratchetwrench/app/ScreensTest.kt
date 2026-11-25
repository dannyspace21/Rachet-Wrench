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
                interval_miles = 5000, last_done_mileage = 40000, last_done_date = "2026-06-01"))
            repo.savePart(com.ratchetwrench.app.data.Part(vehicle_id = v.id, type_key = "oil_filter", part_number = "04152-YZZA1"))
        }

        click("Odometer")
        waitForText("43,500 mi")
        click("Specs")
        waitForText("Test value")
        click("Reminders")
        waitForText("every 5,000 mi", substring = true)
        click("Parts")
        waitForText("04152-YZZA1")

        // History tab filters.
        click("History")
        click("Repairs")
        click("All")

        // Door sticker screen opens and goes back.
        click("Door sticker")
        waitForText("Scan door sticker")
        tapIcon("Back")

        // Edit vehicle screen.
        tapIcon("Edit vehicle")
        waitForText("Delete vehicle")
        type("Nickname (optional)", "Daily")
        click("Save vehicle")
        waitGone("Save vehicle")
        waitForText("Daily")

        // Back on the garage: the reminder now shows under "Coming up".
        tapIcon("Back")
        waitForText("Your vehicles")
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
        waitForText("About Ratchet Wrench")
        waitForText("Version", substring = true)
        tapIcon("Back")
        waitForText("Your vehicles")
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
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Your vehicles").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("Recent work").fetchSemanticsNodes()
        }
    }

    @Test
    fun everyCatalogTypeHasAnIcon() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val missing = com.ratchetwrench.app.domain.Catalog.get(ctx).types.map { it.key }
            .filter { it !in com.ratchetwrench.app.ui.TYPE_ICON_RES }
        org.junit.Assert.assertEquals("types without an icon", emptyList<String>(), missing)
    }
}
