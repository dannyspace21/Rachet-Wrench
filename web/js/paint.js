// paint.js — turn the paint code on the door-jamb sticker into a colour name,
// using the catalog's curated table (catalog.paint_codes).  The same rules
// run on Android and are checked against catalog/paint_tests.json.
//
// Only the vehicle's own make is used (the same code can mean different
// colours at different brands).  Unknown codes give null: the app never
// guesses, and the owner confirms any suggestion before it's saved.

/** "C/TR 1G3/FB13" -> "1G3", " swp " -> "SWP". */
export function normalizePaintCode(code) {
  let c = String(code || "").toUpperCase().trim();
  c = c.replace(/^(?:C\/TR|COLOU?R(?:\s*CODE)?|PAINT(?:\s*CODE)?)\s*[:#]?\s*/, "");
  c = c.split(/[\/\s]/)[0] || "";
  return c.replace(/[^A-Z0-9]/g, "");
}

/** The colour name for this make + code, or null. */
export function paintName(table, make, code) {
  const byMake = (table || {})[String(make || "").trim().toLowerCase()];
  const c = normalizePaintCode(code);
  return byMake && c ? byMake[c] || null : null;
}
