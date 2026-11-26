// metrics.js — the numbers behind the Metrics tab.
//
// Every number on the tab comes from ONE filtered list of history records,
// so a number and the records shown when you click it can never disagree:
//   * filterEntries() applies the active filters (time range, maintenance /
//     repair, category group, service type, year, "had a cost", DIY / shop);
//   * each chart is computed with every filter EXCEPT its own (so clicking a
//     category highlights it while the others stay visible to pick instead);
//   * totals/byGroup/byType/byYear/intervals only add up what they're given.
// Identical rules on Android (Metrics.kt); both are checked against the same
// hand-worked cases in catalog/metrics_tests.json.  No page code here, so it
// can be tested on its own (node --test).

export const RANGE_YEARS = { all: null, "5y": 5, "2y": 2, "1y": 1 };

/** The metric group a service type belongs to (catalog category -> group). */
export function groupOf(typeKey, catalog) {
  const t = catalog.types.find((x) => x.key === typeKey);
  const g = t && catalog.metric_groups.find((x) => x.categories.includes(t.category));
  return g ? g.key : catalog.metric_groups[catalog.metric_groups.length - 1].key;
}

/** First day included by a time range ("YYYY-MM-DD"), or null for all time. */
export function cutoff(range, today) {
  const years = RANGE_YEARS[range];
  if (!years) return null;
  const [y, m, d] = today.split("-").map(Number);
  const dt = new Date(Date.UTC(y - years, m - 1, d));
  if (dt.getUTCMonth() !== m - 1) dt.setUTCDate(0); // 29 Feb -> 28 Feb
  dt.setUTCDate(dt.getUTCDate() + 1);               // "the last N years" = after this day
  return dt.toISOString().slice(0, 10);
}

/** Records matching the filters (except the dimensions listed in `except`). */
export function filterEntries(entries, f, catalog, today, except = []) {
  const skip = new Set(except);
  const from = skip.has("range") ? null : cutoff(f.range || "all", today);
  return entries.filter((e) => !e.deleted_at
    && (!from || e.date >= from)
    && (skip.has("kind") || !f.kind || e.kind === f.kind)
    && (skip.has("group") || !f.group || groupOf(e.type_key, catalog) === f.group)
    && (skip.has("type") || !f.type || e.type_key === f.type)
    && (skip.has("year") || !f.year || Number(e.date.slice(0, 4)) === f.year)
    && (skip.has("paid") || !f.paid || (e.cost_cents || 0) > 0)
    && (skip.has("by") || !f.by || e.performed_by === f.by));
}

/** Newest first (date, then mileage). */
export function newestFirst(list) {
  return list.slice().sort((a, b) => b.date.localeCompare(a.date) || (b.mileage ?? -1) - (a.mileage ?? -1));
}

export function totals(list) {
  return { count: list.length, cents: list.reduce((s, e) => s + (e.cost_cents || 0), 0),
    paid: list.filter((e) => (e.cost_cents || 0) > 0).length };
}

/** Count and cost per metric group, in catalog (colour) order; empty groups left out. */
export function byGroup(list, catalog) {
  return catalog.metric_groups.map((g, slot) => {
    const rows = list.filter((e) => groupOf(e.type_key, catalog) === g.key);
    return { key: g.key, label: g.label, slot, ...totals(rows) };
  }).filter((g) => g.count > 0);
}

/** Count and cost per service type, most frequent first. */
export function byType(list, catalog) {
  const m = new Map();
  for (const e of list) {
    const r = m.get(e.type_key) || { type: e.type_key, count: 0, cents: 0 };
    r.count++; r.cents += e.cost_cents || 0; m.set(e.type_key, r);
  }
  const label = (k) => (catalog.types.find((t) => t.key === k) || {}).label || "Custom / other";
  return [...m.values()].map((r) => ({ ...r, label: label(r.type), group: groupOf(r.type, catalog) }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}

/** Maintenance / repair counts and cost per year, every year from `from` to `to`. */
export function byYear(list, from, to) {
  const out = [];
  for (let y = from; y <= to; y++) {
    const rows = list.filter((e) => Number(e.date.slice(0, 4)) === y);
    out.push({ year: y, maintenance: rows.filter((e) => e.kind !== "repair").length,
      repair: rows.filter((e) => e.kind === "repair").length, cents: totals(rows).cents });
  }
  return out;
}

const DAY = 86400000;
const days = (a, b) => (Date.parse(b) - Date.parse(a)) / DAY;

/**
 * Oldest-first rows of ONE service type with the gap to the previous one,
 * plus the averages.  A gap in miles needs both mileages; a negative gap
 * (mileage typo) is left out of the miles average.
 * Returns {rows: [{entry, sinceMiles, sinceMonths}], avgMiles, avgMonths}.
 */
export function intervals(list) {
  const rows = list.slice().sort((a, b) => a.date.localeCompare(b.date) || (a.mileage ?? 0) - (b.mileage ?? 0));
  const out = rows.map((e, i) => {
    const p = rows[i - 1];
    return { entry: e,
      sinceMiles: p && p.mileage != null && e.mileage != null ? e.mileage - p.mileage : null,
      sinceMonths: p ? Math.round((days(p.date, e.date) / 30.4375) * 10) / 10 : null };
  });
  // A gap where the mileage went DOWN is a typo in a record: it's shown (and
  // flagged) but left out of the average so it can't distort it.
  const mi = out.map((r) => r.sinceMiles).filter((x) => x != null && x >= 0);
  const span = rows.length > 1 ? days(rows[0].date, rows[rows.length - 1].date) / 30.4375 / (rows.length - 1) : null;
  return { rows: out, avgMiles: mi.length ? Math.round(mi.reduce((a, b) => a + b, 0) / mi.length) : null,
    avgMonths: span == null ? null : Math.round(span * 10) / 10 };
}
