// ShoppingTest.kt — Smart Shopping search building on the phone, checked
// against the SAME cases the web app is tested with
// (catalog/shopping_tests.json), so Android and web can never drift apart.
package com.ratchetwrench.app

import com.ratchetwrench.app.domain.CatalogData
import com.ratchetwrench.app.domain.ListLine
import com.ratchetwrench.app.domain.Retailer
import com.ratchetwrench.app.domain.ShopContext
import com.ratchetwrench.app.domain.ShopItem
import com.ratchetwrench.app.domain.Shopping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ShoppingTest {
    private val json = Json { ignoreUnknownKeys = true }
    // Unit tests run from android/app; the shared files live in /catalog.
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "catalog/catalog.json").isFile }
    private val catalog = json.decodeFromString(CatalogData.serializer(), File(root, "catalog/catalog.json").readText())
    private val cases = json.parseToJsonElement(File(root, "catalog/shopping_tests.json").readText()).jsonObject

    private fun str(e: JsonElement?) = if (e == null || e is JsonNull) null else e.jsonPrimitive.content

    private fun ctx(name: String): ShopContext {
        val c = cases["vehicles"]!!.jsonObject[name]!!.jsonObject
        val v = c["vehicle"]!!.jsonObject
        return ShopContext(
            year = v["year"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
            make = str(v["make"]).orEmpty(), model = str(v["model"]).orEmpty(),
            specs = c["specs"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            parts = c["parts"]!!.jsonArray.map { p ->
                val o = p.jsonObject
                Triple(str(o["type_key"])!!, str(o["part_number"]).orEmpty(), str(o["updated_at"]).orEmpty())
            })
    }

    @Test fun reminderSearchesMatchSharedCases() {
        for (case in cases["reminders"]!!.jsonArray) {
            val o = case.jsonObject
            val expected = o["items"]!!.jsonArray.map {
                val i = it.jsonObject
                ShopItem(str(i["label"])!!, str(i["query"])!!, i["exact"]!!.jsonPrimitive.booleanOrNull!!, str(i["hint"]))
            }
            assertEquals("${str(o["vehicle"])} / ${str(o["type_key"])}", expected,
                Shopping.itemsFor(str(o["type_key"])!!, ctx(str(o["vehicle"])!!), catalog))
        }
    }

    @Test fun shoppingListLinesMatchSharedCases() {
        for (case in cases["list_items"]!!.jsonArray) {
            val o = case.jsonObject
            val expected = o["items"]!!.jsonArray.map {
                val i = it.jsonObject
                ListLine(str(i["description"])!!, str(i["part_number"])!!, str(i["spec"])!!, str(i["type_key"])!!)
            }
            assertEquals("${str(o["vehicle"])} / ${str(o["type_key"])}", expected,
                Shopping.listItemsFor(str(o["type_key"])!!, str(o["name"])!!, ctx(str(o["vehicle"])!!), catalog))
        }
    }

    @Test fun shoppingListSearchesMatchSharedCases() {
        for (case in cases["list_queries"]!!.jsonArray) {
            val o = case.jsonObject
            val i = o["item"]!!.jsonObject
            assertEquals(str(o["query"])!! to o["exact"]!!.jsonPrimitive.booleanOrNull!!,
                Shopping.listItemQuery(str(i["description"])!!, str(i["part_number"])!!, str(i["spec"])!!, ctx(str(o["vehicle"])!!)))
        }
    }

    @Test fun partSearchesPreferExactPartNumber() {
        for (case in cases["parts"]!!.jsonArray) {
            val o = case.jsonObject
            val p = o["part"]!!.jsonObject
            val got = Shopping.partQuery(str(p["part_number"]).orEmpty(), str(p["name"]).orEmpty(), str(o["type_label"])!!, ctx(str(o["vehicle"])!!))
            assertEquals(str(o["query"])!! to o["exact"]!!.jsonPrimitive.booleanOrNull!!, got)
        }
    }

    @Test fun retailerLinksComeFromTheCatalogAndMatchTheWeb() {
        val byKey = catalog.retailers.associateBy { it.key }
        for (case in cases["urls"]!!.jsonArray) {
            val o = case.jsonObject
            assertEquals(str(o["url"]), Shopping.retailerUrl(byKey.getValue(str(o["retailer"])!!), str(o["query"])!!))
        }
    }

    @Test fun affiliateLinksOnlyWhenConfiguredApprovedAndAllowed() {
        for (case in cases["affiliates"]!!.jsonArray) {
            val o = case.jsonObject
            val retailer = json.decodeFromJsonElement(Retailer.serializer(), o["retailer"]!!)
            val url = Shopping.retailerUrl(retailer, str(o["query"])!!, str(o["platform"]), o["allowed"]!!.jsonPrimitive.booleanOrNull!!)
            assertEquals(str(o["name"]), str(o["url"]), url)
            // A commission can never change what is searched for.
            val plain = Shopping.searchUrl(retailer, str(o["query"])!!)
            org.junit.Assert.assertTrue(url.startsWith(plain) || url.contains(Shopping.encode(plain)))
        }
        // The shipped catalog has no affiliate tracking switched on, on any platform.
        for (r in catalog.retailers) for (p in listOf("web", "desktop", "android")) {
            assertFalse(r.key, r.affiliate.enabled)
            assertEquals(Shopping.searchUrl(r, "oil filter"), Shopping.retailerUrl(r, "oil filter", p))
        }
    }

    @Test fun preferredRetailerFirstAndTurnedOffHidden() {
        assertEquals(listOf("advance_auto", "amazon"),
            Shopping.activeRetailers(catalog, setOf("walmart"), "advance_auto").map { it.key })
        assertEquals(3, catalog.retailers.size)
    }
}
