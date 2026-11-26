// VinFinderTest.kt — finding a VIN in scanned barcodes and printed text.
package com.ratchetwrench.app

import com.ratchetwrench.app.domain.VinFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinFinderTest {
    private val vin = "1HGCM82633A004352" // a valid VIN (correct check digit)

    @Test fun barcodeVariants() {
        assertEquals(vin, VinFinder.fromBarcode(vin))
        assertEquals(vin, VinFinder.fromBarcode("I$vin"))      // Code 39 import prefix
        assertEquals(vin, VinFinder.fromBarcode("$vin*"))      // stray trailing char
        assertNull(VinFinder.fromBarcode("HELLO"))
        assertNull(VinFinder.fromBarcode("1HGCM82633A004353")) // wrong check digit
    }

    @Test fun printedVinWithLabelAndSpaces() {
        assertEquals(listOf(vin), VinFinder.fromText("VIN: $vin"))
        assertEquals(listOf(vin), VinFinder.fromText("VIN 1HG CM826 33A 004352"))
        assertEquals(listOf(vin), VinFinder.fromText("MFD BY HONDA\n$vin\nGVWR 4000 LB"))
    }

    @Test fun lowercaseAndLetterOAreFixed() {
        // O read instead of 0, lowercase from the recogniser.
        assertEquals(listOf(vin), VinFinder.fromText("1hgcm82633aoo4352"))
    }

    @Test fun oneLookAlikeCharacterIsRepaired() {
        assertEquals(listOf(vin), VinFinder.fromText("1HGCMB2633A004352")) // 8 read as B
        assertEquals(listOf(vin), VinFinder.fromText("1HGCM82633A0043S2")) // 5 read as S
    }

    @Test fun ordinaryTextIsNotAVin() {
        assertTrue(VinFinder.fromText("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG").isEmpty())
        assertTrue(VinFinder.fromText("TIRE AND LOADING INFORMATION\nSEATING CAPACITY TOTAL 5").isEmpty())
        assertTrue(VinFinder.fromText("").isEmpty())
    }
}

/** NHTSA answers -> specs: the same results as the server (catalog/vin_tests.json). */
class NhtsaSpecsTest {
    @org.junit.Test fun sameSpecsAsTheServer() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "catalog/vin_tests.json").isFile }
        val cases = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(root, "catalog/vin_tests.json").readText())
            .let { kotlinx.serialization.json.JsonObject::class.java.cast(it) }["cases"]!!.let { it as kotlinx.serialization.json.JsonArray }
        for (c in cases) {
            val o = c as kotlinx.serialization.json.JsonObject
            val expected = (o["specs"] as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
            val got = com.ratchetwrench.app.data.Nhtsa.parse(o["result"] as kotlinx.serialization.json.JsonObject).specs
            org.junit.Assert.assertEquals((o["name"] as kotlinx.serialization.json.JsonPrimitive).content, expected, got.toMap())
        }
    }
}
