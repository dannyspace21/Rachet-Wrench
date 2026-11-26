// Paint.kt — paint code on the door sticker -> colour name, from the
// catalog's curated table.  Same rules as web/js/paint.js; both are checked
// against catalog/paint_tests.json.  Only the vehicle's own make is used, and
// an unknown code gives null (never a guess).  The owner confirms it.
package com.ratchetwrench.app.domain

object Paint {
    private val PREFIX = Regex("^(?:C/TR|COLOU?R(?:\\s*CODE)?|PAINT(?:\\s*CODE)?)\\s*[:#]?\\s*")

    /** "C/TR 1G3/FB13" -> "1G3", " swp " -> "SWP". */
    fun normalize(code: String?): String {
        val c = PREFIX.replace((code ?: "").uppercase().trim(), "")
        return c.split('/', ' ', '\t').firstOrNull().orEmpty().filter { it in 'A'..'Z' || it in '0'..'9' }
    }

    fun name(table: Map<String, Map<String, String>>, make: String?, code: String?): String? {
        val byMake = table[(make ?: "").trim().lowercase()] ?: return null
        val c = normalize(code)
        return if (c.isEmpty()) null else byMake[c]
    }
}
