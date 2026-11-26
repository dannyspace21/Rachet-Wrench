// RecallsTest.kt — recall status rules on the phone, checked against the
// SAME cases as the web app (catalog/recalls_tests.json); NHTSA answer parsing.
package com.ratchetwrench.app

import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.Nhtsa
import com.ratchetwrench.app.data.VehicleRecall
import com.ratchetwrench.app.domain.Recalls
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class RecallsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "catalog/recalls_tests.json").isFile }
    private val c = Json.parseToJsonElement(File(root, "catalog/recalls_tests.json").readText()).jsonObject
    private fun s(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonPrimitive.content
    private val today = LocalDate.parse(s(c["today"]))
    private val entries = c["entries"]!!.jsonArray.map { it.jsonObject }.map { o ->
        Entry(id = s(o["id"])!!, vehicle_id = "v", date = "2020-01-01", type_key = s(o["type_key"])!!, description = s(o["description"]).orEmpty(),
            notes = s(o["notes"]).orEmpty(), custom_type = s(o["custom_type"]).orEmpty(), deleted_at = s(o["deleted_at"]))
    }
    private fun recall(o: kotlinx.serialization.json.JsonObject) = VehicleRecall(id = s(o["campaign"])!!, vehicle_id = "v", campaign = s(o["campaign"])!!,
        status = s(o["status"])!!, report_date = s(o["report_date"]), entry_id = s(o["entry_id"]), remedy = s(o["remedy"]).orEmpty())

    @Test fun statusOfEachRecall() {
        for (x in c["cases"]!!.jsonArray.map { it.jsonObject }) {
            val st = Recalls.state(recall(x["recall"]!!.jsonObject), entries, today)
            assertEquals(s(x["recall"]!!.jsonObject["campaign"]), listOf(s(x["status"]), s(x["entry"]), x["isNew"]!!.jsonPrimitive.boolean),
                listOf(st.status, st.entry?.id, st.isNew))
        }
    }

    @Test fun openFirstThenDoneThenDoesntApply() {
        val got = Recalls.sorted(c["cases"]!!.jsonArray.map { recall(it.jsonObject["recall"]!!.jsonObject) }, entries, today).map { it.first.campaign }
        assertEquals(c["order"]!!.jsonArray.map { s(it) }, got)
    }

    @Test fun parsesNhtsaAnswer() {
        val body = """{"results":[{"NHTSACampaignNumber":"20V-012000","Component":"FUEL","Summary":"s","Consequence":"c",
            "Remedy":"r","ReportReceivedDate":"13/01/2020","parkIt":false,"parkOutSide":true}]}"""
        assertEquals(listOf(Nhtsa.RecallInfo("20V012000", "FUEL", "s", "c", "r", "2020-01-13", true)), Nhtsa.parseRecalls(body))
    }
}
