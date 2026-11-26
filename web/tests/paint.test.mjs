// paint.test.mjs — paint code -> colour name, shared with Android (catalog/paint_tests.json).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { paintName } from "../js/paint.js";

const cat = JSON.parse(readFileSync(new URL("../../catalog/catalog.json", import.meta.url), "utf8"));
const c = JSON.parse(readFileSync(new URL("../../catalog/paint_tests.json", import.meta.url), "utf8"));

test("paint codes", () => {
  for (const x of c.cases) assert.equal(paintName(cat.paint_codes, x.make, x.code), x.expect, `${x.make} ${x.code}`);
});

test("every name in the table is plain text", () => {
  for (const [make, codes] of Object.entries(cat.paint_codes))
    for (const [code, name] of Object.entries(codes)) {
      assert.match(code, /^[A-Z0-9]{2,4}$/, `${make} ${code}`);
      assert.match(name, /^[A-Za-z0-9 .-]{3,40}$/, `${make} ${code}`);
    }
});
