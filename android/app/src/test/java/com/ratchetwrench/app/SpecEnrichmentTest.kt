// SpecEnrichmentTest.kt — paint code -> colour name (SAME cases as the web
// app: catalog/paint_tests.json) and reading the server's owner's-manual
// answer for the review screen.
package com.ratchetwrench.app

import com.ratchetwrench.app.domain.CatalogData
import com.ratchetwrench.app.domain.Paint
import com.ratchetwrench.app.ui.screens.ManualResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpecEnrichmentTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "catalog/paint_tests.json").isFile }
    private val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(CatalogData.serializer(), File(root, "catalog/catalog.json").readText())

    @Test fun paintCodesSameAsTheWebApp() {
        val cases = Json.parseToJsonElement(File(root, "catalog/paint_tests.json").readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 5)
        for (c in cases) {
            val o = c.jsonObject
            val expect = o["expect"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("${o["make"]} ${o["code"]}", expect, Paint.name(catalog.paint_codes, o["make"]!!.jsonPrimitive.content, o["code"]!!.jsonPrimitive.content))
        }
    }

    @Test fun stickerWeightsSameAsTheServer() {
        val cases = Json.parseToJsonElement(File(root, "catalog/sticker_weight_tests.json").readText()).jsonObject["cases"]!!.jsonArray
        for (c in cases) {
            val o = c.jsonObject
            val expect = o["expect"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(o["name"]!!.jsonPrimitive.content, expect, com.ratchetwrench.app.domain.StickerParser.parseWeights(o["text"]!!.jsonPrimitive.content))
        }
    }

    @Test fun manualAnswerBecomesReviewRows() {
        val body = """{"pages": 400, "readable_pages": 398, "attachment": {}, "specs": [
            {"key": "oil_type", "label": "Engine oil", "current": null, "options": [
               {"value": "0W-20 (API SP)", "page": 512, "snippet": "Engine oil ... 0W-20"}, {"value": "5W-20", "page": 3, "snippet": "in an emergency", "where": "link 3 (owners.example.com)"}]},
            {"key": "brake_fluid_type", "label": "Brake fluid", "current": "DOT 3", "options": [{"value": "DOT 3", "page": 515, "snippet": "Brake fluid FMVSS No.116 DOT 3"}]},
            {"key": "coolant_type", "label": "Coolant", "options": []}]}"""
        val (pages, readable, specs) = ManualResult.parse(body)
        assertEquals(400, pages); assertEquals(398, readable)
        assertEquals(listOf("oil_type", "brake_fluid_type"), specs.map { it.key }) // empty ones dropped
        assertEquals("0W-20 (API SP)", specs[0].value)
        assertEquals("page 512", specs[0].options[0].where)   // older servers send no "where"
        assertEquals("link 3 (owners.example.com)", specs[0].options[1].where)
        assertTrue("empty spec is ticked", specs[0].keep)
        assertFalse("a spec that already has a value is not ticked", specs[1].keep)
    }
}
