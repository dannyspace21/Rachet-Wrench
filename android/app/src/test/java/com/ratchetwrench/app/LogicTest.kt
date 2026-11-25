// LogicTest.kt — unit tests for the offline logic.  The sticker text is the
// SAME sample used by the server's tests (server/tests/test_features.py), so
// the Kotlin and Python parsers are checked against each other.
package com.ratchetwrench.app

import com.ratchetwrench.app.data.Reminder
import com.ratchetwrench.app.data.UrlPolicy
import com.ratchetwrench.app.domain.Reminders
import com.ratchetwrench.app.domain.StickerParser
import com.ratchetwrench.app.domain.Vin
import com.ratchetwrench.app.camera.vinFromBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    private val sticker = """
        TIRE AND LOADING INFORMATION
        SEATING CAPACITY TOTAL 4 FRONT 2 REAR 2
        The combined weight of occupants and cargo should never exceed 340 kg or 750 lbs.
        TIRE    SIZE              COLD TIRE PRESSURE
        FRONT   235/40R19 96W     240 KPA, 35 PSI
        REAR    265/35R19 98W     240 KPA, 35 PSI
        SPARE   T145/70D18        420 KPA, 60 PSI
        MFD BY TOYOTA MOTOR CORPORATION 04/15
        GVWR 2,250 KG (4,960 LB)
        GAWR FRONT 1,125 KG (2,480 LB)  GAWR REAR 1,175 KG (2,590 LB)
        VIN 1HGCM82633A004352
        C/TR: 223/LA20
    """.trimIndent()

    @Test fun stickerParserMatchesServer() {
        val f = StickerParser.parse(sticker)
        assertEquals("1HGCM82633A004352", f["vin"])
        assertEquals("235/40R19 96W", f["tire_size_front"])
        assertEquals("265/35R19 98W", f["tire_size_rear"])
        assertEquals("T145/70D18", f["tire_size_spare"])
        assertEquals("35", f["tire_pressure_front"]); assertEquals("35", f["tire_pressure_rear"]); assertEquals("60", f["tire_pressure_spare"])
        assertEquals("19", f["rim_size_front"])
        assertEquals("4960", f["gvwr"]); assertEquals("2480", f["gawr_front"]); assertEquals("2590", f["gawr_rear"])
        assertEquals("223", f["paint_code"]); assertEquals("LA20", f["trim_code"])
        assertEquals("04/15", f["build_date"])
    }

    @Test fun stickerParserDoesNotGuess() {
        assertTrue(StickerParser.parse("").isEmpty())
        assertTrue(StickerParser.parse("hello world, nothing to see").isEmpty())
        assertNull(StickerParser.parse("VIN 1HGCM82633A004353")["vin"])
    }

    @Test fun vinCheckDigit() {
        assertTrue(Vin.isValid("1HGCM82633A004352"))
        assertFalse(Vin.isValid("1HGCM82633A004353"))
        assertEquals("1HGCM82633A004352", Vin.normalize("1hgcm8263 3a0O4352"))
        assertEquals("1HGCM82633A004352", vinFromBarcode("I1HGCM82633A004352")) // Code 39 import prefix
        assertNull(vinFromBarcode("HELLO"))
    }

    @Test fun reminderWhicheverFirst() {
        val r = Reminder(vehicle_id = "v", interval_miles = 5000, interval_months = 6, last_done_mileage = 10000, last_done_date = "2025-01-31")
        assertEquals("due_soon", Reminders.status(r, 14800, LocalDate.of(2025, 3, 1)).status)
        assertEquals("overdue", Reminders.status(r, 11000, LocalDate.of(2025, 7, 31)).status)
        assertEquals("overdue", Reminders.status(r, 15000, LocalDate.of(2025, 2, 1)).status)
        assertEquals("ok", Reminders.status(r, 11000, LocalDate.of(2025, 2, 1)).status)
    }

    @Test fun nhtsaParsingMatchesServer() {
        // Same sample as server/tests/test_features.py (NHTSA_SAMPLE).
        val json = com.ratchetwrench.app.data.JsonFormat.parseToJsonElement(
            "{\"ModelYear\":\"2014\",\"Make\":\"KIA\",\"Model\":\"Forte\",\"Trim\":\"\",\"VehicleType\":\"PASSENGER CAR\"," +
                "\"DisplacementL\":\"1.8\",\"EngineCylinders\":\"4\",\"EngineConfiguration\":\"In-Line\",\"FuelTypePrimary\":\"Gasoline\"," +
                "\"BodyClass\":\"Sedan/Saloon\",\"DriveType\":\"FWD/Front-Wheel Drive\",\"ErrorCode\":\"0\",\"ErrorText\":\"\"}",
        ) as kotlinx.serialization.json.JsonObject
        val r = com.ratchetwrench.app.data.Nhtsa.parse(json, "1HGCM82633A004352")
        assertEquals(2014, r.year); assertEquals("Kia", r.make); assertEquals("Forte", r.model); assertEquals("car", r.kind)
        assertNull(r.trim) // empty = unknown, never guessed
        assertTrue(r.specs.getValue("engine").startsWith("1.8L"))
        assertNull(r.specs["oil_type"])
        assertNull(r.warning)
    }

    @Test fun httpOnlyForHomeNetwork() {
        assertNull(UrlPolicy.problem("https://garage.example.ts.net", false))
        assertNotNull(UrlPolicy.problem("http://192.168.1.10:8000", false))
        assertNull(UrlPolicy.problem("http://192.168.1.10:8000", true))
        assertNull(UrlPolicy.problem("http://100.101.102.103:8000", true)) // Tailscale range
        assertNotNull(UrlPolicy.problem("http://example.com", true))
        assertNotNull(UrlPolicy.problem("http://8.8.8.8", true))
        assertNotNull(UrlPolicy.problem("ftp://192.168.1.10", true))
    }
}
