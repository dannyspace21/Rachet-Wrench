// PhoneScreenshots.kt — the README's Android screenshots, rendered from the
// REAL app screens (Robolectric draws them with the actual Android graphics
// code; no emulator needed).  Opt-in, so normal builds skip it:
//   RW_PHONE_SHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*PhoneScreenshots*'
// Saves docs/screenshots/phone-*.png.  The data is sample data.
package com.ratchetwrench.app

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.Part
import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.data.ShoppingItem
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.data.VehicleRecall
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenshots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "docs/screenshots").isDirectory }
        .let { File(it, "docs/screenshots") }

    private fun waitFor(text: String) = rule.waitUntil(8_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(text: String) {
        waitFor(text)
        rule.onAllNodes(hasText(text) and hasClickAction())[0].also { runCatching { it.performScrollTo() } }.performClick()
        rule.waitForIdle()
    }
    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        val img = rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(out, "phone-$name.png").outputStream().use { img.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun capture() {
        assumeTrue("set RW_PHONE_SHOTS=1 to render the README's phone screenshots", System.getenv("RW_PHONE_SHOTS") == "1")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = Repository(ctx)
        tap("Start on this phone")
        waitFor("No vehicles yet")
        val today = LocalDate.now()
        runBlocking {
            val forte = Vehicle(kind = "car", year = 2014, make = "Kia", model = "Forte", nickname = "Forte", current_mileage = 118400)
            val rc = Vehicle(kind = "car", year = 2015, make = "Lexus", model = "RC 350", nickname = "RC", current_mileage = 48210)
            val duc = Vehicle(kind = "motorcycle", year = 2012, make = "Ducati", model = "Monster 696", nickname = "Monster", current_mileage = 15800)
            listOf(forte, rc, duc).forEach { repo.saveVehicle(it) }
            // ~8 years of service history for the Forte (sample data).
            var seed = 7L
            fun rnd(): Double { seed = (seed * 16807) % 2147483647; return seed / 2147483647.0 }
            val plan = listOf(Triple("oil_change", 5000, 4900), Triple("tire_rotation", 6000, 2000), Triple("air_filter", 15000, 3000),
                Triple("cabin_filter", 15000, 2800), Triple("brake_pads", 35000, 26000), Triple("wipers", 12000, 3600),
                Triple("inspection", 11000, 3500), Triple("tire_replacement", 45000, 64000))
            val start = today.minusYears(8); val mpd = 70000.0 / (8 * 365)
            for ((type, every, cost) in plan) {
                var m = every.toDouble()
                while (m < 70000) {
                    val d = start.plusDays((m / mpd).toLong())
                    repo.saveEntry(Entry(vehicle_id = forte.id, type_key = type, kind = if (type in listOf("brake_pads", "tire_replacement")) "repair" else "maintenance",
                        date = d.toString(), mileage = 48400 + m.toInt() / 10 * 10, cost_cents = (cost * (0.9 + rnd() * 0.3)).toInt(),
                        performed_by = if (rnd() < 0.5) "shop" else "diy", shop_name = "Corner Garage"), isNew = true)
                    m += every * (0.9 + rnd() * 0.25)
                }
            }
            repo.saveReminder(Reminder(vehicle_id = forte.id, type_key = "oil_change", interval_miles = 5000, interval_months = 6, last_done_mileage = 112900, last_done_date = today.minusMonths(5).toString()))
            repo.saveReminder(Reminder(vehicle_id = forte.id, type_key = "inspection", interval_months = 12, last_done_date = today.minusMonths(13).toString()))
            repo.saveReminder(Reminder(vehicle_id = forte.id, type_key = "tire_rotation", interval_miles = 6000, last_done_mileage = 114000))
            repo.savePart(Part(vehicle_id = forte.id, type_key = "oil_filter", name = "Oil filter", part_number = "26300-35505"))
            repo.saveSpec(forte.id, "oil_type", "5W-20 synthetic blend", "user")
            repo.saveSpec(forte.id, "tire_size_front", "215/45R17", "sticker")
            fun rc(c: String, comp: String, d: String, summary: String) = VehicleRecall(id = repo.recallId(forte.id, c), vehicle_id = forte.id,
                campaign = c, component = comp, summary = summary, report_date = d)
            repo.db.recalls().upsert(rc("17V773000", "SERVICE BRAKES, HYDRAULIC:PEDALS AND LINKAGES", "2017-12-01",
                "The brake pedal stopper pad can deteriorate, allowing the brake lights to remain illuminated."))
            repo.db.recalls().upsert(rc("15V015000", "ELECTRICAL SYSTEM", "2015-01-16", "The cooling fan resistor may overheat and melt."))
            repo.addToShoppingList(listOf(ShoppingItem(vehicle_id = forte.id, description = "Engine oil", spec = "5W-20 synthetic blend", quantity = 5),
                ShoppingItem(vehicle_id = forte.id, description = "Oil filter", part_number = "26300-35505"),
                ShoppingItem(description = "Shop towels", notes = "the blue ones")))
        }
        waitFor("Forte"); shot("garage")
        tap("Forte"); waitFor("Reminders"); shot("reminders")
        tap("History"); tap("Recalls (2)"); waitFor("Service Brakes"); shot("recalls")
        tap("Metrics"); waitFor("TOTAL SPENT"); shot("metrics")
        tap("Parts"); waitFor("26300-35505"); shot("parts")
    }
}
