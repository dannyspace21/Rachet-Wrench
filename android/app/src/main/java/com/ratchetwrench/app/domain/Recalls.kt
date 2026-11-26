// Recalls.kt — what to show for each of a vehicle's recalls.  IDENTICAL
// rules to the web app's web/js/recalls.js; both are checked against
// catalog/recalls_tests.json.
//   done            the owner marked it done, OR a recall record in the
//                   history mentions its campaign number (or the carmaker's
//                   own number, e.g. "SC158", from NHTSA's text);
//   not_applicable  the owner checked: it doesn't apply to this car;
//   open            otherwise.  isNew = open and reported in the last 180 days.
package com.ratchetwrench.app.domain

import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.VehicleRecall
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class RecallState(val status: String, val entry: Entry?, val isNew: Boolean)

object Recalls {
    private fun norm(s: String?) = (s ?: "").uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }
    private val makerRe = Regex("""number for this recall is ([A-Z0-9][A-Z0-9-]{2,15})""", RegexOption.IGNORE_CASE)

    fun makerNumber(r: VehicleRecall): String? = makerRe.find("${r.remedy} ${r.summary}")?.groupValues?.get(1)?.let { norm(it) }

    fun doneInHistory(r: VehicleRecall, entries: List<Entry>): Entry? {
        val c = norm(r.campaign)
        if (c.length < 4) return null
        val ids = listOfNotNull(if (c.length == 9 && c.endsWith("000")) c.take(6) else c, makerNumber(r)).filter { it.length >= 4 }
        return entries.firstOrNull { e ->
            e.deleted_at == null && e.type_key == "recall" &&
                ids.any { id -> norm("${e.description} ${e.notes} ${e.custom_type}").contains(id) }
        }
    }

    fun state(r: VehicleRecall, entries: List<Entry>, today: LocalDate): RecallState = when (r.status) {
        "done" -> RecallState("done", entries.firstOrNull { it.id == r.entry_id }, false)
        "not_applicable" -> RecallState("not_applicable", null, false)
        else -> doneInHistory(r, entries)?.let { RecallState("done", it, false) } ?: run {
            val days = r.report_date?.let { runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), today) }.getOrNull() }
            RecallState("open", null, days != null && days <= 180)
        }
    }

    /** Open first (newest first), then done, then doesn't apply. */
    fun sorted(list: List<VehicleRecall>, entries: List<Entry>, today: LocalDate): List<Pair<VehicleRecall, RecallState>> {
        val rank = mapOf("open" to 0, "done" to 1, "not_applicable" to 2)
        return list.filter { it.deleted_at == null }.map { it to state(it, entries, today) }
            .sortedWith(compareBy<Pair<VehicleRecall, RecallState>> { rank[it.second.status] }.thenByDescending { it.first.report_date ?: "" })
    }
}
