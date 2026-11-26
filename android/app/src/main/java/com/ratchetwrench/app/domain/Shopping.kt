// Shopping.kt — Smart Shopping: turns what we know about a vehicle into a
// retailer search, e.g. "OEM 04152-YZZA1 oil filter" or "255/35R19 tires".
//
// Retailers (names, search addresses, enabled, future affiliate settings)
// and "what to buy" for each maintenance type come from the shared catalog
// (assets/catalog.json), never from code.  The rules are IDENTICAL to the
// web app's web/js/shopping.js; both are tested against the same cases
// (catalog/shopping_tests.json):
//   * the FIRST search template whose information is all known is used, so
//     exact part numbers beat descriptions;
//   * nothing is invented: a template needing a missing spec is skipped;
//   * a reminder type with no entry in the catalog has no shopping action.
// Searches only OPEN the retailer's own app or website; nothing is sent.
package com.ratchetwrench.app.domain

/** What the search builder needs to know about one vehicle. */
data class ShopContext(
    val year: Int?, val make: String, val model: String,
    val specs: Map<String, String>,
    /** Saved parts: (type_key, part_number, updated_at). */
    val parts: List<Triple<String, String, String>>,
)

data class ShopItem(val label: String, val query: String, val exact: Boolean, val hint: String?)

/** A shopping-list line worked out from a reminder (what's KNOWN, never guessed). */
data class ListLine(val description: String, val part_number: String, val spec: String, val type_key: String)

/** What went into a filled template (for shopping-list lines). */
class Used { var partNumber = ""; val specs = mutableListOf<String>() }

object Shopping {
    private val placeholderRe = Regex("""\{([^}]+)\}""")
    private val numberRe = Regex("""\d+(?:\.\d+)?""")
    private val exactRe = Regex("""\{(spec|part):""")

    fun yearMakeModel(c: ShopContext): String? {
        if (c.make.isBlank() || c.model.isBlank()) return null
        return listOfNotNull(c.year?.toString(), c.make.trim(), c.model.trim()).joinToString(" ")
    }

    private fun partNumber(c: ShopContext, type: String): String? =
        c.parts.filter { it.first == type && it.second.isNotBlank() }.maxByOrNull { it.third }?.second?.trim()

    private fun placeholder(name: String, c: ShopContext): String? {
        if (name == "ymm") return yearMakeModel(c)
        val kind = name.substringBefore(':')
        val rest = name.substringAfter(':', "")
        val key = rest.substringBefore('|')
        val filter = rest.substringAfter('|', "")
        return when (kind) {
            "part" -> partNumber(c, key)
            "spec" -> {
                val raw = c.specs[key]?.trim().orEmpty()
                when {
                    raw.isEmpty() -> null
                    filter == "inches" -> numberRe.find(raw)?.value
                    else -> raw
                }
            }
            else -> null
        }
    }

    /** Fill one template; null if any of its information is missing.
     *  [used] (optional) collects the part number / specs that went in. */
    fun fill(template: String, c: ShopContext, used: Used? = null): String? {
        var missing = false
        val out = placeholderRe.replace(template) { m ->
            val name = m.groupValues[1]
            val v = placeholder(name, c) ?: run { missing = true; return@replace "" }
            if (used != null && name.startsWith("part:")) used.partNumber = v
            if (used != null && name.startsWith("spec:")) {
                val key = name.removePrefix("spec:").substringBefore('|')
                // "..._part" specs hold an OEM part number; other specs are kept as written ("26 in").
                if (key.endsWith("_part")) used.partNumber = v else used.specs += c.specs[key].orEmpty().trim()
            }
            v
        }
        return if (missing) null else out.replace(Regex("""\s+"""), " ").trim()
    }

    /** What to shop for a maintenance type (empty = no shopping action). */
    fun itemsFor(typeKey: String, c: ShopContext, catalog: CatalogData): List<ShopItem> {
        val recipe = catalog.shopping[typeKey] ?: return emptyList()
        val seen = mutableSetOf<String>()
        val items = mutableListOf<ShopItem>()
        for (item in recipe) {
            for (t in item.queries) {
                val q = fill(t, c) ?: continue
                if (seen.add(q)) {
                    // A tip only when the search had to fall back to a general description.
                    val exact = exactRe.containsMatchIn(t)
                    items += ShopItem(item.label, q, exact, if (!exact) item.needs else null)
                }
                break
            }
        }
        return items
    }

    /** Shopping-list lines for a reminder (same rules as web listItemsFor). */
    fun listItemsFor(typeKey: String, name: String, c: ShopContext, catalog: CatalogData): List<ListLine> {
        val recipe = catalog.shopping[typeKey] ?: return listOf(ListLine(name, "", "", typeKey))
        return recipe.map { item ->
            item.queries.firstNotNullOfOrNull { t ->
                val used = Used()
                fill(t, c, used)?.let { ListLine(item.label, used.partNumber, used.specs.joinToString(" / "), typeKey) }
            } ?: ListLine(item.label, "", "", typeKey)
        }
    }

    /** Search text for a shopping-list line: part number, else spec + description, else vehicle + description. */
    fun listItemQuery(description: String, partNumber: String, spec: String, c: ShopContext?): Pair<String, Boolean> {
        val what = description.trim()
        val pn = partNumber.trim()
        if (pn.isNotEmpty()) return "$pn $what".trim() to true
        val sp = spec.trim()
        if (sp.isNotEmpty()) return "$sp $what".trim() to true
        val ymm = c?.let { yearMakeModel(it) }
        return (if (ymm != null) "$ymm $what" else what) to false
    }

    /** Search text for one saved part: the exact part number when there is one. */
    fun partQuery(partNumber: String, name: String, typeLabel: String, c: ShopContext): Pair<String, Boolean> {
        val number = partNumber.trim()
        if (number.isNotEmpty()) return number to true
        val what = name.trim().ifEmpty { typeLabel }
        val ymm = yearMakeModel(c)
        return (if (ymm != null) "$ymm $what" else what) to false
    }

    /** Same encoding as JavaScript's encodeURIComponent, so links match the web app exactly. */
    fun encode(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xff).toChar()
            if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.!~*'()") append(ch)
            else append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }

    /** The retailer's search address (affiliate tag only when configured). */
    fun retailerUrl(r: Retailer, query: String): String {
        var url = r.search_url.replace("{q}", encode(query))
        if (r.affiliate.param.isNotBlank() && r.affiliate.value.isNotBlank()) {
            url += (if ('?' in url) "&" else "?") + encode(r.affiliate.param) + "=" + encode(r.affiliate.value)
        }
        return url
    }

    /** Retailers to offer: enabled in the catalog and not turned off on this phone, preferred first. */
    fun activeRetailers(catalog: CatalogData, disabled: Set<String>, preferred: String?): List<Retailer> =
        catalog.retailers.filter { it.enabled && it.key !in disabled }.sortedBy { if (it.key == preferred) 0 else 1 }
}
