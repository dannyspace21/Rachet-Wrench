// Metrics.kt — the numbers behind the vehicle's Metrics tab.
//
// IDENTICAL rules to the web app's web/js/metrics.js; both are checked
// against the same hand-worked cases (catalog/metrics_tests.json):
//   * every number comes from ONE filtered list of history records, so a
//     number and the records shown when you tap it always agree;
//   * each chart uses every filter EXCEPT its own;
//   * a gap where the mileage went down (a typo) is shown but not averaged.
package com.ratchetwrench.app.domain

import com.ratchetwrench.app.data.Entry
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

data class MetricFilter(
    val range: String = "all",      // all | 5y | 2y | 1y
    val kind: String? = null,       // maintenance | repair
    val group: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val paid: Boolean = false,      // only records with a cost
    val by: String? = null,         // diy | shop
)

data class Totals(val count: Int, val cents: Long, val paid: Int)
data class GroupCount(val key: String, val label: String, val slot: Int, val count: Int, val cents: Long)
data class TypeCount(val type: String, val label: String, val group: String, val count: Int, val cents: Long)
data class YearCount(val year: Int, val maintenance: Int, val repair: Int, val cents: Long)
data class IntervalRow(val entry: Entry, val sinceMiles: Int?, val sinceMonths: Double?)
data class Intervals(val rows: List<IntervalRow>, val avgMiles: Int?, val avgMonths: Double?)

object Metrics {
    val RANGE_YEARS = mapOf("all" to null, "5y" to 5, "2y" to 2, "1y" to 1)

    fun groupOf(typeKey: String, c: CatalogData): String {
        val t = c.types.firstOrNull { it.key == typeKey }
        return c.metric_groups.firstOrNull { t != null && t.category in it.categories }?.key ?: c.metric_groups.last().key
    }

    fun slotOf(typeKey: String, c: CatalogData): Int = c.metric_groups.indexOfFirst { it.key == groupOf(typeKey, c) }

    /** First day included by a time range, or null for all time. */
    fun cutoff(range: String, today: LocalDate): LocalDate? {
        val years = RANGE_YEARS[range] ?: return null
        return today.minusYears(years.toLong()).plusDays(1)
    }

    fun filter(entries: List<Entry>, f: MetricFilter, c: CatalogData, today: LocalDate, except: Set<String> = emptySet()): List<Entry> {
        val from = if ("range" in except) null else cutoff(f.range, today)?.toString()
        return entries.filter { e ->
            e.deleted_at == null &&
                (from == null || e.date >= from) &&
                ("kind" in except || f.kind == null || e.kind == f.kind) &&
                ("group" in except || f.group == null || groupOf(e.type_key, c) == f.group) &&
                ("type" in except || f.type == null || e.type_key == f.type) &&
                ("year" in except || f.year == null || e.date.take(4).toIntOrNull() == f.year) &&
                ("paid" in except || !f.paid || (e.cost_cents ?: 0) > 0) &&
                ("by" in except || f.by == null || e.performed_by == f.by)
        }
    }

    fun newestFirst(list: List<Entry>) = list.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.mileage ?: -1 })

    fun totals(list: List<Entry>) = Totals(list.size, list.sumOf { (it.cost_cents ?: 0).toLong() }, list.count { (it.cost_cents ?: 0) > 0 })

    fun byGroup(list: List<Entry>, c: CatalogData): List<GroupCount> = c.metric_groups.mapIndexed { slot, g ->
        val rows = list.filter { groupOf(it.type_key, c) == g.key }
        val t = totals(rows)
        GroupCount(g.key, g.label, slot, t.count, t.cents)
    }.filter { it.count > 0 }

    fun byType(list: List<Entry>, c: CatalogData): List<TypeCount> = list.groupBy { it.type_key }.map { (k, rows) ->
        TypeCount(k, c.types.firstOrNull { it.key == k }?.label ?: "Custom / other", groupOf(k, c), rows.size, totals(rows).cents)
    }.sortedWith(compareByDescending<TypeCount> { it.count }.thenBy { it.label })

    fun byYear(list: List<Entry>, from: Int, to: Int): List<YearCount> = (from..to).map { y ->
        val rows = list.filter { it.date.take(4).toIntOrNull() == y }
        YearCount(y, rows.count { it.kind != "repair" }, rows.count { it.kind == "repair" }, totals(rows).cents)
    }

    /** Same rounding as JavaScript's Math.round(x * 10) / 10. */
    private fun round1(x: Double): Double = Math.floor(x * 10 + 0.5) / 10

    fun intervals(list: List<Entry>): Intervals {
        val rows = list.sortedWith(compareBy<Entry> { it.date }.thenBy { it.mileage ?: 0 })
        val out = rows.mapIndexed { i, e ->
            val p = rows.getOrNull(i - 1)
            IntervalRow(e,
                if (p?.mileage != null && e.mileage != null) e.mileage - p.mileage else null,
                p?.let { round1(ChronoUnit.DAYS.between(LocalDate.parse(it.date), LocalDate.parse(e.date)) / 30.4375) })
        }
        val gaps = out.mapNotNull { it.sinceMiles }.filter { it >= 0 }
        val avgMonths = if (rows.size > 1)
            round1(ChronoUnit.DAYS.between(LocalDate.parse(rows.first().date), LocalDate.parse(rows.last().date)) / 30.4375 / (rows.size - 1)) else null
        return Intervals(out, if (gaps.isEmpty()) null else (gaps.sum().toDouble() / gaps.size).roundToLong().toInt(), avgMonths)
    }
}
