// IssueFixesTest.kt — the bugs reported from inside the app (#4 scan box, #5 notifications).
package com.ratchetwrench.app

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ratchetwrench.app.camera.ScanRegion
import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.sync.ReminderWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class IssueFixesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** #4: only barcodes/text inside the orange box count. */
    @Test fun scanBoxMapsOntoTheCameraPicture() {
        // Screen 1080x2340, box across the middle (48 px margins, 420 px tall);
        // upright camera picture 1080x1920 shown FILL_CENTER (scale 1.21875, 109 px cropped each side).
        val region = ScanRegion.inImage(floatArrayOf(48f, 960f, 1032f, 1380f, 1080f, 2340f), 1080, 1920)!!
        fun r(cx: Int, cy: Int) = android.graphics.Rect(cx - 50, cy - 10, cx + 50, cy + 10)
        assertTrue("barcode in the box", ScanRegion.contains(region, r(540, 960)))       // picture centre = screen centre
        assertFalse("text near the top of the picture", ScanRegion.contains(region, r(540, 300)))
        assertFalse("label at the bottom", ScanRegion.contains(region, r(540, 1700)))
        assertTrue("no box measured yet: whole picture", ScanRegion.contains(null, r(10, 10)))
    }

    /** #5: "Check reminders now" shows every due reminder, even one notified recently. */
    @Test fun checkNowShowsDueRemindersEvenIfRecentlyNotified() = runBlocking {
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderWorker.createChannel(ctx)
        val repo = Repository(ctx)
        repo.store.notificationsEnabled = true
        val v = Vehicle(make = "Kia", model = "Forte", current_mileage = 50000)
        repo.saveVehicle(v)
        repo.saveReminder(Reminder(vehicle_id = v.id, type_key = "oil_change", interval_miles = 5000, last_done_mileage = 40000))
        assertTrue(ReminderWorker.canNotify(ctx))
        assertEquals(1, ReminderWorker.dueCount(ctx))
        assertEquals("first check notifies", 1, ReminderWorker.checkAndNotify(ctx))
        assertEquals("twice-a-day check waits 3 days", 0, ReminderWorker.checkAndNotify(ctx))
        assertEquals("Check reminders now: shown again", 1, ReminderWorker.checkAndNotify(ctx, force = true))
    }

    @Test fun noPermissionMeansNotShownButStillCountedAsDue() = runBlocking {
        shadowOf(ctx as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val repo = Repository(ctx)
        repo.store.notificationsEnabled = true
        val v = Vehicle(make = "Kia", model = "Forte", current_mileage = 50000)
        repo.saveVehicle(v)
        repo.saveReminder(Reminder(vehicle_id = v.id, type_key = "oil_change", interval_miles = 5000, last_done_mileage = 40000))
        assertFalse(ReminderWorker.canNotify(ctx))
        assertEquals(0, ReminderWorker.checkAndNotify(ctx, force = true))
        assertEquals(1, ReminderWorker.dueCount(ctx))   // so Settings can say "1 is due, but notifications are blocked"
    }
}
