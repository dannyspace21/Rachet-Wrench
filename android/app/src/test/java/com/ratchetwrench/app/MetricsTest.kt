// MetricsTest.kt — the Metrics tab's numbers on the phone, checked against
// the SAME hand-worked cases as the web app (catalog/metrics_tests.json).
package com.ratchetwrench.app

import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.domain.CatalogData
import com.ratchetwrench.app.domain.MetricFilter
import com.ratchetwrench.app.domain.Metrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class MetricsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "catalog/catalog.json").isFile }
    private val catalog = json.decodeFromString(CatalogData.serializer(), File(root, "catalog/catalog.json").readText())
    private val c = json.parseToJsonElement(File(root, "catalog/metrics_tests.json").readText()).jsonObject
    private val today = LocalDate.parse(c["today"]!!.jsonPrimitive.content)

    private fun str(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonPrimitive.content
    private fun int(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonPrimitive.intOrNull
    private fun entries(a: JsonArray) = a.map { it.jsonObject }.map { o ->
        Entry(id = str(o["id"])!!, vehicle_id = "v", date = str(o["date"])!!, type_key = str(o["type_key"])!!, kind = str(o["kind"])!!,
            mileage = int(o["mileage"]), cost_cents = int(o["cost_cents"]), performed_by = str(o["performed_by"])!!, deleted_at = str(o["deleted_at"]))
    }
    private val all = entries(c["entries"]!!.jsonArray)
    private fun filter(o: JsonObject) = MetricFilter(range = str(o["range"]) ?: "all", kind = str(o["kind"]), group = str(o["group"]),
        type = str(o["type"]), year = int(o["year"]), paid = o["paid"]?.jsonPrimitive?.booleanOrNull ?: false, by = str(o["by"]))

    @Test fun timeRanges() {
        for ((r, want) in c["cutoffs"]!!.jsonObject) assertEquals(r, str(want), Metrics.cutoff(r, today)?.toString())
    }

    @Test fun everyFilterGivesExactlyTheRecordsAndTotalsBehindIt() {
        for (x in c["filters"]!!.jsonArray.map { it.jsonObject }) {
            val list = Metrics.filter(all, filter(x["f"]!!.jsonObject), catalog, today)
            assertEquals(x["f"].toString(), x["ids"]!!.jsonArray.map { str(it) }.sortedBy { it }, list.map { it.id }.sorted())
            val t = Metrics.totals(list)
            assertEquals(x["f"].toString(), listOf(x["count"]!!.jsonPrimitive.int.toLong(), x["cents"]!!.jsonPrimitive.long, x["paid"]!!.jsonPrimitive.int.toLong()),
                listOf(t.count.toLong(), t.cents, t.paid.toLong()))
        }
    }

    @Test fun categoriesServicesAndYearsAddUp() {
        val list = Metrics.filter(all, MetricFilter(), catalog, today)
        fun groups(a: JsonArray) = a.map { it.jsonObject }.map { listOf(str(it["key"]), it["slot"]!!.jsonPrimitive.int, it["count"]!!.jsonPrimitive.int, it["cents"]!!.jsonPrimitive.long) }
        assertEquals(groups(c["groups_all"]!!.jsonArray), Metrics.byGroup(list, catalog).map { listOf(it.key, it.slot, it.count, it.cents) })
        val ge = c["groups_except"]!!.jsonObject
        assertEquals(groups(ge["groups"]!!.jsonArray), Metrics.byGroup(Metrics.filter(all, filter(ge["f"]!!.jsonObject), catalog, today,
            ge["except"]!!.jsonArray.map { str(it)!! }.toSet()), catalog).map { listOf(it.key, it.slot, it.count, it.cents) })
        assertEquals(c["types_all"]!!.jsonArray.map { it.jsonObject }.map { listOf(str(it["type"]), it["count"]!!.jsonPrimitive.int, it["cents"]!!.jsonPrimitive.long) },
            Metrics.byType(list, catalog).map { listOf(it.type, it.count, it.cents) })
        val y = c["years_all"]!!.jsonObject
        assertEquals(y["years"]!!.jsonArray.map { it.jsonObject }.map { listOf(it["year"]!!.jsonPrimitive.int, it["maintenance"]!!.jsonPrimitive.int, it["repair"]!!.jsonPrimitive.int, it["cents"]!!.jsonPrimitive.long.toInt()) },
            Metrics.byYear(list, y["from"]!!.jsonPrimitive.int, y["to"]!!.jsonPrimitive.int).map { listOf(it.year, it.maintenance, it.repair, it.cents.toInt()) })
        assertEquals(Metrics.totals(list).cents, Metrics.byGroup(list, catalog).sumOf { it.cents })
    }

    @Test fun intervalsBetweenServices() {
        val r = Metrics.intervals(Metrics.filter(all, MetricFilter(type = "oil_change"), catalog, today))
        val o = c["oil_intervals"]!!.jsonObject
        assertEquals(o["ids"]!!.jsonArray.map { str(it) }, r.rows.map { it.entry.id })
        assertEquals(o["sinceMiles"]!!.jsonArray.map { int(it) }, r.rows.map { it.sinceMiles })
        assertEquals(o["sinceMonths"]!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }, r.rows.map { it.sinceMonths })
        assertEquals(int(o["avgMiles"]), r.avgMiles)
        assertEquals(o["avgMonths"]!!.jsonPrimitive.doubleOrNull, r.avgMonths)
        val n = c["negative_gap"]!!.jsonObject
        val neg = Metrics.intervals(entries(n["entries"]!!.jsonArray))
        assertEquals(n["sinceMiles"]!!.jsonArray.map { int(it) }, neg.rows.map { it.sinceMiles })
        assertEquals(int(n["avgMiles"]), neg.avgMiles)
    }
}
