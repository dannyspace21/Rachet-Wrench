// metrics.test.mjs — the Metrics tab's numbers, checked against hand-worked
// cases shared with Android (catalog/metrics_tests.json).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { byGroup, byType, byYear, cutoff, filterEntries, intervals, totals } from "../js/metrics.js";

const root = new URL("../../", import.meta.url);
const catalog = JSON.parse(readFileSync(new URL("catalog/catalog.json", root), "utf8"));
const c = JSON.parse(readFileSync(new URL("catalog/metrics_tests.json", root), "utf8"));
const pick = (o, keys) => Object.fromEntries(keys.map((k) => [k, o[k]]));

test("time ranges", () => {
  for (const [r, want] of Object.entries(c.cutoffs)) assert.equal(cutoff(r, c.today), want, r);
});

test("every filter gives exactly the records and totals behind it", () => {
  for (const x of c.filters) {
    const list = filterEntries(c.entries, x.f, catalog, c.today);
    assert.deepEqual(list.map((e) => e.id).sort(), x.ids.slice().sort(), JSON.stringify(x.f));
    assert.deepEqual(totals(list), { count: x.count, cents: x.cents, paid: x.paid }, JSON.stringify(x.f));
  }
});

test("categories, service types and years add up", () => {
  const all = filterEntries(c.entries, {}, catalog, c.today);
  assert.deepEqual(byGroup(all, catalog).map((g) => pick(g, ["key", "slot", "count", "cents"])), c.groups_all);
  const ge = c.groups_except;
  assert.deepEqual(byGroup(filterEntries(c.entries, ge.f, catalog, c.today, ge.except), catalog)
    .map((g) => pick(g, ["key", "slot", "count", "cents"])), ge.groups);
  assert.deepEqual(byType(all, catalog).map((t) => pick(t, ["type", "count", "cents"])), c.types_all);
  assert.deepEqual(byYear(all, c.years_all.from, c.years_all.to), c.years_all.years);
  // The parts always add up to the whole.
  assert.equal(byGroup(all, catalog).reduce((s, g) => s + g.cents, 0), totals(all).cents);
  assert.equal(byType(all, catalog).reduce((s, g) => s + g.count, 0), totals(all).count);
});

test("intervals between services", () => {
  const oil = filterEntries(c.entries, { type: "oil_change" }, catalog, c.today);
  const r = intervals(oil);
  const o = c.oil_intervals;
  assert.deepEqual(r.rows.map((x) => x.entry.id), o.ids);
  assert.deepEqual(r.rows.map((x) => x.sinceMiles), o.sinceMiles);
  assert.deepEqual(r.rows.map((x) => x.sinceMonths), o.sinceMonths);
  assert.equal(r.avgMiles, o.avgMiles);
  assert.equal(r.avgMonths, o.avgMonths);
});

test("a mileage typo (negative gap) is shown but not averaged", () => {
  const n = c.negative_gap;
  const r = intervals(n.entries);
  assert.deepEqual(r.rows.map((x) => x.sinceMiles), n.sinceMiles);
  assert.equal(r.avgMiles, n.avgMiles);
});
