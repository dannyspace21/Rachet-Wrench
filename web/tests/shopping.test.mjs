// shopping.test.mjs — Smart Shopping search building (web app), checked
// against the SAME cases the Android app is tested with
// (catalog/shopping_tests.json).  Run:  node --test web/tests/
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { itemsFor, partQuery, retailerUrl, searchUrl, activeRetailers, listItemsFor, listItemQuery } from "../js/shopping.js";

const root = new URL("../../", import.meta.url);
const catalog = JSON.parse(readFileSync(new URL("catalog/catalog.json", root), "utf8"));
const cases = JSON.parse(readFileSync(new URL("catalog/shopping_tests.json", root), "utf8"));
const ctx = (name) => cases.vehicles[name];

test("reminder searches match the shared cases", () => {
  for (const c of cases.reminders) {
    assert.deepEqual(itemsFor(c.type_key, ctx(c.vehicle), catalog), c.items, `${c.vehicle} / ${c.type_key}`);
  }
});

test("part searches prefer the exact part number", () => {
  for (const c of cases.parts) {
    assert.deepEqual(partQuery(c.part, ctx(c.vehicle).vehicle, c.type_label), { query: c.query, exact: c.exact });
  }
});

test("retailer links come from the catalog", () => {
  const byKey = Object.fromEntries(catalog.retailers.map((r) => [r.key, r]));
  for (const c of cases.urls) assert.equal(retailerUrl(byKey[c.retailer], c.query), c.url);
});

test("affiliate links: only when configured, approved for this platform and allowed by the user", () => {
  for (const c of cases.affiliates) assert.equal(retailerUrl(c.retailer, c.query, c.platform, c.allowed), c.url, c.name);
});

test("the shipped catalog has NO affiliate tracking switched on", () => {
  for (const r of catalog.retailers) {
    assert.equal(r.affiliate.enabled, false, r.key);
    for (const p of ["web", "desktop", "android"]) assert.equal(retailerUrl(r, "oil filter", p), searchUrl(r, "oil filter"), `${r.key} ${p}`);
  }
});

test("a commission can never change what is searched for", () => {
  for (const c of cases.affiliates) {
    const plain = searchUrl(c.retailer, c.query);
    const url = retailerUrl(c.retailer, c.query, c.platform, c.allowed);
    // The ordinary search address is always inside the link, unchanged (param: as the start; wrap: encoded inside).
    assert.ok(url.startsWith(plain) || url.includes(encodeURIComponent(plain)), c.name);
  }
});

test("retailer preferences: turned-off retailers hidden, preferred first", () => {
  assert.deepEqual(activeRetailers(catalog, { enabled: true, disabled: ["walmart"], preferred: "advance_auto" }).map((r) => r.key),
    ["advance_auto", "amazon"]);
});

test("every shopping recipe points at real catalog types and specs", () => {
  const types = new Set(catalog.types.map((t) => t.key));
  const specs = new Set(catalog.specs.map((s) => s.key));
  for (const [type, items] of Object.entries(catalog.shopping)) {
    assert.ok(types.has(type), type);
    for (const it of items) for (const q of it.queries) for (const [, kind, key] of q.matchAll(/\{(spec|part):([a-z_]+)/g)) {
      assert.ok(kind === "spec" ? specs.has(key) : types.has(key), `${type}: ${q}`);
    }
  }
});

test("shopping-list lines from a reminder keep the known part number / spec", () => {
  for (const c of cases.list_items) {
    assert.deepEqual(listItemsFor(c.type_key, c.name, ctx(c.vehicle), catalog), c.items, `${c.vehicle} / ${c.type_key}`);
  }
});

test("shopping-list searches: part number, then spec, then vehicle", () => {
  for (const c of cases.list_queries) {
    assert.deepEqual(listItemQuery(c.item, ctx(c.vehicle).vehicle), { query: c.query, exact: c.exact });
  }
});
