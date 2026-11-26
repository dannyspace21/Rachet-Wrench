// VinFinder.kt — finds a VIN in what the camera reads.
//
//  fromBarcode() — the raw value of a scanned barcode.  VIN barcodes are
//                  sometimes printed with an extra leading "I" (import) or a
//                  trailing check character, so a few variants are tried.
//  fromText()    — text read by on-phone text recognition (the VIN printed on
//                  a sticker, the dashboard plate, a registration card...).
//
// Every VIN has a check digit (9th character) computed from the other 16, so
// a misread almost always fails it.  Text recognition often confuses
// look-alike characters (S/5, B/8, Z/2, G/6, D/0, T/7, A/4); when a 17-character
// run fails the check digit, single look-alike swaps are tried and the result
// is used only if EXACTLY ONE swap makes it valid (ties are broken by the rule
// that the last 5 characters of a major manufacturer's VIN are digits).
package com.ratchetwrench.app.domain

object VinFinder {

    /** Characters text recognition commonly mixes up (both directions). */
    private val LOOK_ALIKES: Map<Char, List<Char>> = listOf('S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6', 'D' to '0', 'T' to '7', 'A' to '4')
        .flatMap { (a, b) -> listOf(a to b, b to a) }
        .groupBy({ it.first }, { it.second })

    /** The model-year character (10th) is never U, Z or 0. */
    private fun plausible(vin: String) = vin[9] !in "UZ0"

    fun fromBarcode(raw: String): String? {
        val clean = raw.uppercase().filter { it.isLetterOrDigit() }
        val candidates = listOf(clean, clean.drop(1), clean.dropLast(1), clean.take(17))
        return candidates.map { Vin.normalize(it) }.firstOrNull { it.length == 17 && Vin.isValid(it) }
    }

    /** A valid VIN for this 17-character run, fixing one look-alike character if needed. */
    private fun repair(run: String): String? {
        if (Vin.isValid(run) && plausible(run)) return run
        val fixes = mutableSetOf<String>()
        for (i in run.indices) for (alt in LOOK_ALIKES[run[i]].orEmpty()) {
            val v = run.substring(0, i) + alt + run.substring(i + 1)
            if (Vin.isValid(v) && plausible(v)) fixes += v
        }
        // Several fixes possible: prefer ones whose last 5 characters are
        // digits (always true for major manufacturers); still only accept
        // a single clear winner.
        return fixes.singleOrNull() ?: fixes.filter { v -> v.takeLast(5).all { it.isDigit() } }.singleOrNull()
    }

    /**
     * All VINs found in recognised text.  Each line is cleaned (spaces and
     * dashes dropped, so "JTH HE5BC 0F5 001234" still works) and every
     * 17-character stretch is checked.  Look-alike repair is only tried on
     * lines that are (almost) exactly one VIN long, to avoid "finding" VINs
     * in unrelated text.
     */
    fun fromText(text: String): List<String> {
        val found = linkedSetOf<String>()
        for (line in text.lines()) {
            var s = Vin.normalize(line)
            // Drop a "VIN" label in front ("V1N" after normalising I -> 1).
            if (s.length > 17 && s.startsWith("V1N")) s = s.removePrefix("V1N")
            if (s.length < 17) continue
            var exact = false
            for (i in 0..s.length - 17) {
                val run = s.substring(i, i + 17)
                if (Vin.isValid(run) && plausible(run)) { found += run; exact = true }
            }
            if (!exact && s.length <= 18) for (i in 0..s.length - 17) repair(s.substring(i, i + 17))?.let { found += it }
        }
        return found.toList()
    }
}
