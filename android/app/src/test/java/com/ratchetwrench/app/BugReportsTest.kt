// BugReportsTest.kt — what an in-app bug report sends, and its limits.
package com.ratchetwrench.app

import com.ratchetwrench.app.data.BugReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportsTest {
    @Test fun reportBodyIsSafeAndComplete() {
        val b = BugReports.body("Crashes when @someone taps Save <!-- x -->", "1. Open Specs", "", "Ratchet Wrench 1.0.27\n```oops```")
        assertTrue(b.contains("@​someone"))          // no GitHub mention pings
        assertFalse(b.contains("<!--"))
        assertTrue(b.contains("## Steps to reproduce\n1. Open Specs"))
        assertFalse("empty section left out", b.contains("## What was expected"))
        assertTrue(b.contains("```\nRatchet Wrench 1.0.27\n'''oops'''\n```"))  // details can't break out of the code block
    }

    @Test fun rateLimit() {
        val now = 1_000_000_000L
        assertNull(BugReports.tooSoon(emptyList(), now))
        assertTrue(BugReports.tooSoon(listOf(now - 30_000), now)!!.contains("wait a minute"))
        assertNull(BugReports.tooSoon(listOf(now - 61_000), now))
        assertTrue(BugReports.tooSoon(List(10) { now - 3_600_000L * (it + 1) }, now)!!.contains("10 reports a day"))
        assertNull("yesterday's don't count", BugReports.tooSoon(List(10) { now - 90_000_000L }, now))
    }

    @Test fun localBuildsHaveNoKey() = assertEquals(false, BugReports.enabled)
}
