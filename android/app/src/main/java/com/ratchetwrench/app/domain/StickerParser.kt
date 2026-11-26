// StickerParser.kt — turns text read from door-jamb stickers (by ML Kit, on
// the phone, offline) into labelled fields: VIN, tire sizes & pressures,
// paint/trim codes, build date, GVWR / GAWR.
//
// This is a line-by-line Kotlin copy of the server's sticker_parser.py, and
// both are tested with the same sample sticker text.  The rules are
// deliberately conservative: when in doubt a field is left out, never
// guessed.  You confirm, edit or reject each field on the review screen.
package com.ratchetwrench.app.domain

object StickerParser {
    private val TIRE = Regex(
        """\b(?:P|LT|T)?(\d{3})\s?/\s?(\d{2})\s?(Z?R|-)\s?(\d{2}(?:\.\d)?)(?:\s*(?:M/C)?\s*\(?(\d{2,3}[A-Z])\)?)?""",
        RegexOption.IGNORE_CASE,
    )
    private val SPARE = Regex("""\bT(\d{3})\s?/\s?(\d{2})\s?(D|R)\s?(\d{2})\b""", RegexOption.IGNORE_CASE)
    private val PSI = Regex("""(\d{2,3})\s*(?:PSI|LBS?\.?/?\s*IN)""", RegexOption.IGNORE_CASE)
    private const val LB = """([\d][\d,\.]{2,6})\s*(?:LB|LBS)"""
    private const val KG = """([\d][\d,\.]{2,6})\s*KG"""
    private val DATE = Regex("""\b(?:MFD|MFG|DATE|MANUFACTURED)[^0-9A-Z]{0,12}(?:BY\s*[^0-9]{0,40})?(\d{1,2})\s*/\s*(\d{2,4})\b""", RegexOption.IGNORE_CASE)
    private val DATE_FALLBACK = Regex("""\b(0[1-9]|1[0-2])\s*/\s*(\d{2}|20\d{2})\b""")

    private fun num(raw: String) = raw.replace(",", "").replace(" ", "").trimEnd('.')

    /** Find a VIN: first after a "VIN" label, otherwise a single 17-char word. */
    fun findVin(text: String): String? {
        val lines = text.uppercase().lines()
        val label = Regex("""\bV\.?I\.?N\b\.?""")
        for (line in lines) {
            val m = label.find(line) ?: continue
            val squeezed = line.substring(m.range.last + 1).filter { it.isLetterOrDigit() }
            for (i in 0 until maxOf(0, squeezed.length - 16)) {
                val cand = Vin.normalize(squeezed.substring(i, i + 17))
                if (Vin.isValid(cand)) return cand
            }
        }
        for (line in lines) {
            for (word in line.split(Regex("""[\s,;:]+"""))) {
                val cand = Vin.normalize(word.trim('-', '*', '#', '.'))
                if (cand.length == 17 && cand.count { it.isDigit() } >= 5 && Vin.isValid(cand)) return cand
            }
        }
        return null
    }

    private fun tireString(m: MatchResult): String {
        val g = m.groupValues
        val construction = if (g[3] == "-") "R" else g[3].uppercase()
        val size = "${g[1]}/${g[2]}$construction${g[4]}"
        return if (g[5].isNotEmpty()) "$size ${g[5].uppercase()}" else size
    }

    // GVWR / GAWR labels as printed on certification labels (the one with the
    // VIN), incl. dotted and French/Canadian bilingual forms.  Same rules as
    // the server (server/app/sticker_parser.py); shared cases in
    // catalog/sticker_weight_tests.json.
    private val WEIGHT_LABEL = Regex(
        """\bG\.?\s?V\.?\s?W\.?\s?R\b\.?(?:\s*/\s*PNBV)?""" +
        """|\bG\.?\s?A\.?\s?W\.?\s?R\b\.?(?:\s*/\s*PNBE)?\s*[:\-]?\s*(FRONT|FRT|FR|F|REAR|RR|R)\b(?:\s*/?\s*(?:PNBE\s*)?(?:AV|AR)\b)?""" +
        """|\b(FRONT|REAR)\b(?:\s*/\s*(?:AV|AR)\b)?""")
    private val W_KG_LB = Regex("""(\d[\d,.]{2,6})\s*KGS?[^0-9]{0,6}(\d[\d,.]{2,6})\s*LBS?\b""")
    private val W_LB = Regex("""(\d[\d,.]{2,6})\s*LBS?\b""")
    private val W_KG = Regex("""(\d[\d,.]{2,6})\s*KGS?\b""")

    private fun kgToLb(kg: String) = Math.round(num(kg).toDouble() * 2.20462).toString()

    private fun weight(seg: String): String? =
        W_KG_LB.find(seg)?.let { num(it.groupValues[2]) }
            ?: W_LB.find(seg)?.let { num(it.groupValues[1]) }
            ?: W_KG.find(seg)?.let { kgToLb(it.groupValues[1]) }

    /** GVWR and front/rear GAWR in lb from upper-case sticker text. */
    fun parseWeights(upper: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val lines = upper.lines().filter { it.isNotBlank() }
        for ((i, line) in lines.withIndex()) {
            val found = mutableListOf<Pair<String, MatchResult>>()
            var gawrSeen = false
            for (m in WEIGHT_LABEL.findAll(line)) {
                val side = m.groups[1]?.value ?: m.groups[2]?.value
                // A bare FRONT/REAR only counts after a GAWR label (not "FRONT 35 PSI").
                val key = when {
                    side == null -> "gvwr"
                    m.groups[2] != null && !gawrSeen -> null
                    side.startsWith("F") -> "gawr_front"
                    else -> "gawr_rear"
                }
                if (m.groups[1] != null) gawrSeen = true
                if (key != null) found += key to m
            }
            if (found.isEmpty()) continue
            val missing = mutableListOf<String>()
            for ((n, pair) in found.withIndex()) {
                val (key, m) = pair
                val end = if (n + 1 < found.size) found[n + 1].second.range.first else line.length
                val w = weight(line.substring(m.range.last + 1, end))
                if (w != null) { if (key !in out) out[key] = w } else missing += key
            }
            if (missing.isNotEmpty()) {
                // Table layout: headings on this line, values (kg and/or lb) below.
                val below = lines.drop(i + 1).take(3).joinToString(" ")
                val lbs = W_LB.findAll(below).map { num(it.groupValues[1]) }.toList()
                val kgs = W_KG.findAll(below).map { kgToLb(it.groupValues[1]) }.toList()
                val vals = if (lbs.size >= missing.size) lbs else if (kgs.size >= missing.size) kgs else emptyList()
                for ((key, v) in missing.zip(vals)) out.putIfAbsent(key, v)
            }
        }
        return out
    }

    fun parse(text: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        if (text.isBlank()) return fields
        val lines = text.lines().map { it.replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
        val upper = lines.joinToString("\n").uppercase()
        val upperLines = upper.lines()

        findVin(upper)?.let { fields["vin"] = it }

        // Tires: spare first so it isn't mistaken for a road tire.
        val spare = SPARE.find(upper)
        spare?.let { fields["tire_size_spare"] = "T${it.groupValues[1]}/${it.groupValues[2]}${it.groupValues[3].uppercase()}${it.groupValues[4]}" }
        val spareStart = spare?.range?.first ?: -1
        val spareEnd = spare?.range?.last ?: -2
        val tires = TIRE.findAll(upper).filter { !(it.range.first in spareStart..(spareEnd + 1)) }.toList()
        for (line in upperLines) {
            val m = TIRE.find(line) ?: continue
            if (Regex("""\bFRONT\b|\bFRT\b|\bF\b\s*:""").containsMatchIn(line) && "tire_size_front" !in fields) fields["tire_size_front"] = tireString(m)
            else if (Regex("""\bREAR\b|\bRR\b|\bR\b\s*:""").containsMatchIn(line) && "tire_size_rear" !in fields) fields["tire_size_rear"] = tireString(m)
        }
        if (tires.isNotEmpty() && "tire_size_front" !in fields) fields["tire_size_front"] = tireString(tires[0])
        if (tires.isNotEmpty() && "tire_size_rear" !in fields) {
            val distinct = tires.map { tireString(it) }.distinct()
            fields["tire_size_rear"] = if (distinct.size > 1) distinct[1] else distinct[0]
        }
        for (pos in listOf("front", "rear")) {
            fields["tire_size_$pos"]?.let { size -> Regex("""R(\d{2}(?:\.\d)?)""").find(size)?.let { fields["rim_size_$pos"] = it.groupValues[1] } }
        }

        // Pressures: labelled lines first, then front/rear/spare order.
        for (line in upperLines) {
            val m = PSI.find(line) ?: continue
            val v = m.groupValues[1]
            if (v.toInt() !in 10..80) continue
            when {
                "SPARE" in line && "tire_pressure_spare" !in fields -> fields["tire_pressure_spare"] = v
                Regex("""\bFRONT\b|\bFRT\b""").containsMatchIn(line) && "tire_pressure_front" !in fields -> fields["tire_pressure_front"] = v
                Regex("""\bREAR\b|\bRR\b""").containsMatchIn(line) && "tire_pressure_rear" !in fields -> fields["tire_pressure_rear"] = v
            }
        }
        val allPsi = PSI.findAll(upper).map { it.groupValues[1] }.filter { it.toInt() in 10..80 }.toMutableList()
        val order = listOf("tire_pressure_front", "tire_pressure_rear", "tire_pressure_spare")
        if (allPsi.isNotEmpty() && order.none { it in fields }) {
            for ((k, v) in order.zip(allPsi)) {
                if (k == "tire_pressure_spare" && "tire_size_spare" !in fields) break
                fields[k] = v
            }
        } else if (allPsi.isNotEmpty()) {
            for (k in order) fields[k]?.let { allPsi.remove(it) }
            for (k in order) if (k !in fields && allPsi.isNotEmpty() && (k != "tire_pressure_spare" || "tire_size_spare" in fields)) fields[k] = allPsi.removeAt(0)
        }

        // Weights.
        fields.putAll(parseWeights(upper))

        // Paint & trim.
        Regex("""\b(?:PAINT|COLOU?R|C/TR|PNT)\b(?:\s*CODE)?\s*[:#]?\s*([A-Z0-9]{2,4})\b""").find(upper)?.let {
            if (it.groupValues[1] !in setOf("CODE", "TRIM")) fields["paint_code"] = it.groupValues[1]
        }
        val ctr = Regex("""C/TR\s*[:]?\s*([A-Z0-9]{2,4})\s*/\s*([A-Z0-9]{2,6})""").find(upper)
        if (ctr != null) {
            fields["paint_code"] = ctr.groupValues[1]
            fields["trim_code"] = ctr.groupValues[2]
        } else {
            Regex("""\bTRIM\b(?:\s*CODE)?\s*[:#]?\s*([A-Z0-9]{2,6})\b""").find(upper)?.let { fields["trim_code"] = it.groupValues[1] }
        }

        // Build date.
        val d = DATE.find(upper) ?: DATE_FALLBACK.find(upper)
        if (d != null) {
            val month = d.groupValues[1].toInt()
            if (month in 1..12) fields["build_date"] = "%02d/%s".format(month, d.groupValues[2].takeLast(2))
        }
        return fields
    }
}
