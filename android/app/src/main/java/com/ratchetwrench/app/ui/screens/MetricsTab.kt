// MetricsTab.kt — the vehicle's Metrics tab on the phone: how the history
// adds up, and the exact records behind every number.
//
// Everything is tappable and everything filters everything else (same as the
// web app): time range, maintenance/repairs, DIY/shop, a tile, a category
// (donut slice or legend row), a service (bar), a year or just its
// maintenance/repair part.  Active filters show as chips (tap to remove).
// The list at the bottom is always the records behind the numbers, with a
// total that equals the tile.  All numbers come from domain/Metrics.kt.
package com.ratchetwrench.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ratchetwrench.app.data.Entry
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.MetricFilter
import com.ratchetwrench.app.domain.Metrics
import com.ratchetwrench.app.ui.LocalExtra
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.SecondaryButton
import com.ratchetwrench.app.ui.money
import com.ratchetwrench.app.ui.prettyDate
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.hypot

// 8 category colours (same validated palette as the web app), light and dark.
private val LIGHT = listOf(0xFF257DE0, 0xFFF47230, 0xFF00B894, 0xFFF2A600, 0xFFEE7FAC, 0xFF008A17, 0xFF6A4CE2, 0xFFEC4647).map { Color(it) }
private val DARK = listOf(0xFF338CED, 0xFFE76624, 0xFF00A888, 0xFFD28E05, 0xFFE24D8B, 0xFF0AAC23, 0xFF9887EF, 0xFFEB6263).map { Color(it) }
private const val PAGE = 50

@Composable
private fun palette(): List<Color> = if (MaterialTheme.colorScheme.background.luminance() < 0.4f) DARK else LIGHT

@Composable
fun MetricsTab(nav: NavHostController, v: Vehicle) {
    val repo = LocalRepo.current
    val ctx = LocalContext.current
    val catalog = Catalog.get(ctx)
    val all by repo.db.entries().observeFor(v.id).collectAsState(initial = emptyList())
    val cur = repo.store.currency
    val unit = v.mileage_unit
    val colors = palette()
    val maintColor = colors[0]
    val repairColor = colors[1]
    val today = LocalDate.now()

    var range by rememberSaveable { mutableStateOf("all") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var by by rememberSaveable { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf("records") } // records | cost | interval
    var shown by remember { mutableIntStateOf(PAGE) }
    val f = MetricFilter(range, kind, group, type, year, false, by)
    fun changed() { shown = PAGE; if (view == "interval" && type == null) view = "records" }
    fun label(k: String) = Catalog.typeLabel(ctx, k)
    fun name(e: Entry) = if (e.type_key == "custom" && e.custom_type.isNotBlank()) e.custom_type else label(e.type_key)
    fun pickGroup(k: String) {
        val g = if (group == k) null else k
        if (g != null && type != null && Metrics.groupOf(type!!, catalog) != g) type = null
        group = g; changed()
    }

    if (all.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text("No history yet", fontWeight = FontWeight.Bold)
            Text("Metrics appear once you log maintenance or repairs.", color = LocalExtra.current.muted)
        }
        return
    }

    val list = Metrics.filter(all, f, catalog, today)
    val t = Metrics.totals(list)
    // The service the last two tiles describe: the one picked, else oil changes, else the most frequent.
    val focus = type ?: if (list.any { it.type_key == "oil_change" }) "oil_change" else Metrics.byType(list, catalog).firstOrNull()?.type ?: "oil_change"
    val typeRows = list.filter { it.type_key == focus }
    val iv = Metrics.intervals(typeRows)
    val groups = Metrics.byGroup(Metrics.filter(all, f, catalog, today, setOf("group")), catalog)
    val types = Metrics.byType(Metrics.filter(all, f, catalog, today, setOf("type")), catalog)
    val from = Metrics.cutoff(range, today)
    val yearList = Metrics.filter(all, f, catalog, today, setOf("year"))
    val firstYear = from?.year ?: all.filter { it.deleted_at == null }.minOfOrNull { it.date.take(4).toInt() } ?: today.year
    val years = Metrics.byYear(yearList, firstYear, today.year)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Filters
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All time", "5y" to "5 years", "2y" to "2 years", "1y" to "12 months").forEach { (k, s) ->
                    FilterChip(range == k, { range = k; changed() }, label = { Text(s) })
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(kind == "maintenance", { kind = if (kind == "maintenance") null else "maintenance"; changed() }, label = { Text("Maintenance") })
                FilterChip(kind == "repair", { kind = if (kind == "repair") null else "repair"; changed() }, label = { Text("Repairs") })
                Spacer(Modifier.width(8.dp))
                FilterChip(by == "diy", { by = if (by == "diy") null else "diy"; changed() }, label = { Text("DIY") })
                FilterChip(by == "shop", { by = if (by == "shop") null else "shop"; changed() }, label = { Text("Shop") })
            }
        }
        // ---- Active filters (tap to remove)
        item {
            val active = buildList {
                if (range != "all") add(mapOf("5y" to "Last 5 years", "2y" to "Last 2 years", "1y" to "Last 12 months")[range]!! to { range = "all" })
                kind?.let { add((if (it == "repair") "Repairs" else "Maintenance") to { kind = null }) }
                by?.let { add((if (it == "diy") "Done myself" else "Done by a shop") to { by = null }) }
                group?.let { g -> add((catalog.metric_groups.first { it.key == g }.label) to { group = null }) }
                type?.let { add(label(it) to { type = null }) }
                year?.let { add(it.toString() to { year = null }) }
            }
            if (active.isEmpty()) Text("Showing everything. Tap any number, category, service or year to filter.",
                color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                active.forEach { (text, clear) ->
                    InputChip(true, { clear(); changed() }, label = { Text("$text  ×") },
                        modifier = Modifier.semantics { contentDescription = "Remove filter: $text" })
                }
                if (active.size > 1) TextButton({ range = "all"; kind = null; by = null; group = null; type = null; year = null; changed() }) { Text("Clear all") }
            }
        }
        // ---- Tiles (each opens the records behind it)
        item {
            val avg = iv.avgMiles?.let { "%,d".format(it) } ?: "—"
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Modifier.weight(1f), "Records", "%,d".format(t.count), from?.let { "since ${prettyDate(it.toString())}" } ?: "all logged work",
                        view == "records" && type == null) { view = "records"; changed() }
                    Tile(Modifier.weight(1f), "Total spent", money(t.cents.toInt(), cur), "${t.paid} with a cost", view == "cost") { view = "cost"; changed() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(Modifier.weight(1f), label(focus), typeRows.size.toString(), "times done", view == "records" && type != null) {
                        type = focus; view = "records"; changed()
                    }
                    Tile(Modifier.weight(1f), "Avg. $unit between", avg, iv.avgMonths?.let { "about every $it months" } ?: label(focus), view == "interval") {
                        type = focus; view = "interval"; changed()
                    }
                }
            }
        }
        // ---- Categories
        item {
            Card("By category", "Tap a category to filter everything by it") {
                val total = groups.sumOf { it.count }.coerceAtLeast(1)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Donut(groups.map { it.count to colors[it.slot] }, groups.indexOfFirst { it.key == group }) { i -> pickGroup(groups[i].key) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val sel = groups.firstOrNull { it.key == group }
                        Text((sel?.count ?: total).toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                        Text(sel?.label ?: "records", color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                groups.forEach { g ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (group == g.key) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { pickGroup(g.key) }.heightIn(min = 40.dp).padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Category ${g.label}: ${g.count}" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(colors[g.slot]))
                        Text(g.label, Modifier.weight(1f))
                        Text(g.count.toString(), fontWeight = FontWeight.Bold)
                        Text("${g.count * 100 / total}%", color = LocalExtra.current.muted, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }
        // ---- Services
        item {
            Card(group?.let { g -> catalog.metric_groups.first { it.key == g }.label + " — services" } ?: "Most frequent services",
                "Tap a service to see every time it was done") {
                val max = (types.maxOfOrNull { it.count } ?: 1).toFloat()
                if (types.isEmpty()) Text("Nothing matches these filters.", color = LocalExtra.current.muted)
                types.take(12).forEach { x ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (type == x.type) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { type = if (type == x.type) null else x.type; if (type != null) view = "records"; changed() }
                        .heightIn(min = 40.dp).padding(horizontal = 8.dp)
                        .semantics { contentDescription = "Service ${x.label}: ${x.count}" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(x.label, Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(x.count / max).height(12.dp).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                .background(colors[Metrics.slotOf(x.type, catalog).coerceAtLeast(0)]))
                        }
                        Text(x.count.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                if (types.size > 12) Text("+ ${types.size - 12} more — pick a category to narrow the list.", color = LocalExtra.current.muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        // ---- Years
        item {
            Card("By year", "Tap a year — or just its maintenance or repair part") {
                val top = (years.maxOfOrNull { it.maintenance + it.repair } ?: 1).coerceAtLeast(1).toFloat()
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    years.forEach { y ->
                        Column(Modifier.weight(1f).fillMaxHeight()
                            .background(if (year == y.year) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { year = if (year == y.year) null else y.year; changed() }
                            .semantics { contentDescription = "Year ${y.year}: ${y.maintenance} maintenance, ${y.repair} repairs" },
                            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (y.repair > 0) Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight(y.repair / top * 0.85f)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(repairColor)
                                .clickable { year = y.year; kind = "repair"; changed() }
                                .semantics { contentDescription = "${y.year} repairs: ${y.repair}" })
                            if (y.repair > 0 && y.maintenance > 0) Spacer(Modifier.height(2.dp))
                            if (y.maintenance > 0) Box(Modifier.fillMaxWidth(0.7f).height(((y.maintenance / top) * 127).dp)
                                .clip(RoundedCornerShape(topStart = if (y.repair > 0) 0.dp else 3.dp, topEnd = if (y.repair > 0) 0.dp else 3.dp))
                                .background(maintColor).clickable { year = y.year; kind = "maintenance"; changed() }
                                .semantics { contentDescription = "${y.year} maintenance: ${y.maintenance}" })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    years.forEachIndexed { i, y ->
                        Text(if (years.size > 8 && i % 2 == 1) "" else "'" + y.year.toString().takeLast(2), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted, maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendKey("Maintenance", maintColor) { kind = if (kind == "maintenance") null else "maintenance"; changed() }
                    LegendKey("Repairs", repairColor) { kind = if (kind == "repair") null else "repair"; changed() }
                }
            }
        }
        // ---- The records behind the numbers
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("THE RECORDS BEHIND THESE NUMBERS", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, fontWeight = FontWeight.Bold)
                Text(when (view) {
                    "cost" -> "${money(t.cents.toInt(), cur)} across ${t.paid} record${if (t.paid == 1) "" else "s"}"
                    "interval" -> "${label(focus)} — ${typeRows.size} time${if (typeRows.size == 1) "" else "s"}"
                    else -> "${t.count} record${if (t.count == 1) "" else "s"} · ${money(t.cents.toInt(), cur)}"
                }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val views = buildList { add("records" to "Records"); add("cost" to "Costs"); if (type != null) add("interval" to "Intervals") }
                com.ratchetwrench.app.ui.Segmented(views, view) { view = it; shown = PAGE }
            }
        }
        when (view) {
            "cost" -> {
                val paid = list.filter { (it.cost_cents ?: 0) > 0 }.sortedWith(compareByDescending<Entry> { it.cost_cents }.thenByDescending { it.date })
                val byG = Metrics.byGroup(paid, catalog).sortedByDescending { it.cents }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Every record with a cost, most expensive first. The total at the bottom is the “Total spent” tile.",
                            color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
                        val gmax = (byG.maxOfOrNull { it.cents } ?: 1L).toFloat()
                        byG.forEach { g ->
                            Row(Modifier.fillMaxWidth().clickable { pickGroup(g.key) }.heightIn(min = 36.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(g.label, Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.weight(1f)) { Box(Modifier.fillMaxWidth(g.cents / gmax).height(10.dp).background(colors[g.slot])) }
                                Text(money(g.cents.toInt(), cur), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                items(paid.take(shown), key = { "c" + it.id }) { e -> RecordRow(e, name(e), money(e.cost_cents, cur), unit) { nav.navigate("entry/${e.id}") } }
                item { TotalRow("Total (${paid.size})", money(t.cents.toInt(), cur)) }
                if (paid.size > shown) item { SecondaryButton("Show all ${paid.size}") { shown = Int.MAX_VALUE } }
                if (t.count > t.paid) item { Text("${t.count - t.paid} more record${if (t.count - t.paid == 1) " has" else "s have"} no cost entered (${money(0, cur)}).",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall) }
            }
            "interval" -> {
                val gaps = iv.rows.count { (it.sinceMiles ?: -1) >= 0 }
                val typos = iv.rows.count { (it.sinceMiles ?: 0) < 0 }
                item { Timeline(typeRows, colors[Metrics.slotOf(focus, catalog).coerceAtLeast(0)]) }
                items(iv.rows.reversed().take(shown), key = { "i" + it.entry.id }) { r ->
                    val gap = r.sinceMiles?.let { if (it < 0) "⚠ %,d $unit (check mileage)".format(it) else "+%,d $unit".format(it) } ?: "first one"
                    RecordRow(r.entry, gap + (r.sinceMonths?.let { " · $it mo" } ?: ""), money(r.entry.cost_cents, cur), unit) { nav.navigate("entry/${r.entry.id}") }
                }
                item { TotalRow("Average ($gaps gap${if (gaps == 1) "" else "s"})", iv.avgMiles?.let { "%,d $unit · ${iv.avgMonths} mo".format(it) } ?: "—") }
                item { Text("Average = the gaps added up ÷ how many gaps (only records with a mileage count)." +
                    if (typos > 0) " ⚠ $typos record${if (typos == 1) " has" else "s have"} a lower mileage than the one before — left out; tap it to fix it." else "",
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall) }
            }
            else -> {
                val rows = Metrics.newestFirst(list)
                if (type != null) item { Timeline(typeRows, colors[Metrics.slotOf(focus, catalog).coerceAtLeast(0)]) }
                items(rows.take(shown), key = { "r" + it.id }) { e -> RecordRow(e, name(e) + if (e.kind == "repair") " · repair" else "", money(e.cost_cents, cur), unit) { nav.navigate("entry/${e.id}") } }
                item { TotalRow("Total (${rows.size})", money(t.cents.toInt(), cur)) }
                if (rows.size > shown) item { SecondaryButton("Show all ${rows.size}") { shown = Int.MAX_VALUE } }
            }
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, title: String, value: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
        .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(12.dp)
        .semantics { contentDescription = "$title: $value" }) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.muted, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Card(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.muted, fontWeight = FontWeight.Bold)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.muted)
        content()
    }
}

@Composable
private fun LegendKey(text: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color)); Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordRow(e: Entry, title: String, cost: String, unit: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(prettyDate(e.date) + (e.mileage?.let { " · %,d $unit".format(it) } ?: "") +
                    " · " + (if (e.performed_by == "shop") e.shop_name.ifBlank { "Shop" } else "DIY"),
                    color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(cost, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

/** Donut chart; a tap on a slice calls onPick(index). */
@Composable
private fun Donut(slices: List<Pair<Int, Color>>, selected: Int, onPick: (Int) -> Unit) {
    val total = slices.sumOf { it.first }.toFloat().coerceAtLeast(1f)
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(190.dp).pointerInput(slices) {
        detectTapGestures { p ->
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = hypot(p.x - c.x, p.y - c.y)
            if (r < size.width * 0.28f || r > size.width / 2f) return@detectTapGestures
            var a = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())) + 90
            if (a < 0) a += 360
            var acc = 0.0
            slices.forEachIndexed { i, (n, _) -> acc += n / total * 360; if (a <= acc) { onPick(i); return@detectTapGestures } }
        }
    }) {
        val stroke = size.width * 0.17f
        val inset = stroke / 2
        var start = -90f
        slices.forEachIndexed { i, (n, color) ->
            val sweep = n / total * 360f
            val alpha = if (selected < 0 || selected == i) 1f else 0.28f
            drawArc(color.copy(alpha = alpha), start, sweep, false, topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = if (selected == i) stroke * 1.12f else stroke))
            if (slices.size > 1) drawArc(surface, start + sweep - 0.6f, 1.2f, false, topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke), style = Stroke(width = stroke * 1.2f))
            start += sweep
        }
    }
}

/** Each dot = one time it was done (date across, mileage up). */
@Composable
private fun Timeline(rows: List<Entry>, color: Color) {
    val pts = rows.filter { it.mileage != null }.sortedBy { it.date }
    if (pts.isEmpty()) return
    val grid = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "When it was done, and at what mileage" }) {
            val t0 = LocalDate.parse(pts.first().date).toEpochDay().toFloat()
            val t1 = maxOf(t0 + 30f, LocalDate.parse(pts.last().date).toEpochDay().toFloat())
            val mMax = maxOf(1000, pts.maxOf { it.mileage!! }) * 1.05f
            fun x(e: Entry) = 8f + (LocalDate.parse(e.date).toEpochDay() - t0) / (t1 - t0) * (size.width - 16f)
            fun y(e: Entry) = size.height - 8f - e.mileage!! / mMax * (size.height - 16f)
            for (k in 0..2) { val yy = size.height - 8f - k / 2f * (size.height - 16f); drawLine(grid.copy(alpha = 0.4f), Offset(0f, yy), Offset(size.width, yy), 1f) }
            if (pts.size > 1) {
                val path = Path().apply { moveTo(x(pts[0]), y(pts[0])); pts.drop(1).forEach { lineTo(x(it), y(it)) } }
                drawPath(path, color.copy(alpha = 0.45f), style = Stroke(width = 4f))
            }
            pts.forEach { drawCircle(surface, 9f, Offset(x(it), y(it))); drawCircle(color, 6f, Offset(x(it), y(it))) }
        }
        Text("Each dot is one time it was done (date across, mileage up). Tap a record below to open it.",
            color = LocalExtra.current.muted, style = MaterialTheme.typography.bodySmall)
    }
}

