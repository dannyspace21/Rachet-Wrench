// recalls.test.mjs — recall status rules, shared with Android (catalog/recalls_tests.json).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { recallState, sortRecalls } from "../js/recalls.js";

const c = JSON.parse(readFileSync(new URL("../../catalog/recalls_tests.json", import.meta.url), "utf8"));

test("status of each recall", () => {
  for (const x of c.cases) {
    const s = recallState(x.recall, c.entries, c.today);
    assert.deepEqual({ status: s.status, entry: s.entry?.id ?? null, isNew: s.isNew }, { status: x.status, entry: x.entry, isNew: x.isNew }, x.recall.campaign);
  }
});

test("open first (newest first), then done, then doesn't apply", () => {
  assert.deepEqual(sortRecalls(c.cases.map((x) => x.recall), c.entries, c.today).map((x) => x.r.campaign), c.order);
});
