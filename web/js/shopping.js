// shopping.js — Smart Shopping: turns what we know about a vehicle into a
// retailer search, e.g. "OEM 04152-YZZA1 oil filter" or "255/35R19 tires".
//
// Everything about retailers (names, search addresses, enabled, future
// affiliate settings) and about what to buy for each maintenance type comes
// from the shared catalog (catalog.json -> "retailers" and "shopping"), so
// no retailer address is written anywhere else in the app.
//
// Rules (identical on Android — see ShoppingTest / web/tests):
//   * the FIRST search template whose information is all known is used, so
//     exact part numbers beat descriptions;
//   * nothing is invented: a template needing a missing spec is skipped;
//   * a reminder type with no entry in the catalog has no shopping action.
// Searches only OPEN the retailer's own site/app; nothing is sent from here.
// This file has no page code, so it can be tested on its own (node --test).

/** "year make model" — or null without at least make and model. */
export function yearMakeModel(v) {
  if (!v || !String(v.make || "").trim() || !String(v.model || "").trim()) return null;
  return [v.year, v.make, v.model].filter((x) => x !== null && x !== undefined && String(x).trim() !== "")
    .map((x) => String(x).trim()).join(" ");
}

/** The vehicle's saved part number for a part type (most recently updated first). */
function partNumber(parts, typeKey) {
  const list = (parts || []).filter((p) => !p.deleted_at && p.type_key === typeKey && String(p.part_number || "").trim())
    .sort((a, b) => String(b.updated_at || "").localeCompare(String(a.updated_at || "")));
  return list.length ? String(list[0].part_number).trim() : null;
}

function placeholder(name, ctx) {
  if (name === "ymm") return yearMakeModel(ctx.vehicle);
  const [kind, rest] = name.split(":");
  const [key, filter] = (rest || "").split("|");
  if (kind === "part") return partNumber(ctx.parts, key);
  if (kind === "spec") {
    const raw = String((ctx.specs || {})[key] ?? "").trim();
    if (!raw) return null;
    if (filter === "inches") { const m = raw.match(/\d+(?:\.\d+)?/); return m ? m[0] : null; }
    return raw;
  }
  return null;
}

/**
 * Fill one template; null if any of its information is missing.  `used`
 * (optional) collects the part number and spec values that went in.
 */
export function fill(template, ctx, used = null) {
  let missing = false;
  const out = template.replace(/\{([^}]+)\}/g, (_, name) => {
    const v = placeholder(name, ctx);
    if (v === null) { missing = true; return ""; }
    if (used && name.startsWith("part:")) used.part_number = v;
    if (used && name.startsWith("spec:")) {
      const key = name.slice(5).split("|")[0];
      // Specs like "oil_filter_part" hold an OEM part number; other specs
      // are kept as written in Specs (e.g. "26 in"), not the search form ("26").
      if (key.endsWith("_part")) used.part_number = v;
      else used.specs.push(String(ctx.specs[key]).trim());
    }
    return v;
  });
  return missing ? null : out.replace(/\s+/g, " ").trim();
}

/**
 * What to shop for a maintenance type.  ctx = {vehicle, specs: {key: value},
 * parts: [...]}.  Returns [{label, query, exact, hint}] — empty means "no
 * shopping action for this reminder".  exact = built from a part number or a
 * spec; hint = the spec key that would make a general search exact (or null).
 */
export function itemsFor(typeKey, ctx, catalog) {
  const recipe = (catalog.shopping || {})[typeKey];
  if (!recipe) return [];
  const seen = new Set();
  const items = [];
  for (const item of recipe) {
    for (const t of item.queries) {
      const q = fill(t, ctx);
      if (q === null) continue;
      if (!seen.has(q)) {
        seen.add(q);
        // A tip only when the search had to fall back to a general description.
        const exact = /\{(spec|part):/.test(t);
        items.push({ label: item.label, query: q, exact, hint: !exact && item.needs ? item.needs : null });
      }
      break;
    }
  }
  return items;
}

// ---------------------------------------------------------------------------
// Shopping list
// ---------------------------------------------------------------------------
/**
 * Shopping-list lines for a reminder: one per thing to buy, with the OEM
 * part number and/or spec that is KNOWN (never guessed).  A reminder type
 * with nothing in the catalog becomes a single line named after it.
 * Returns [{description, part_number, spec, type_key}].
 */
export function listItemsFor(typeKey, name, ctx, catalog) {
  const recipe = (catalog.shopping || {})[typeKey];
  if (!recipe) return [{ description: name, part_number: "", spec: "", type_key: typeKey }];
  return recipe.map((item) => {
    for (const t of item.queries) {
      const used = { part_number: "", specs: [] };
      if (fill(t, ctx, used) === null) continue;
      return { description: item.label, part_number: used.part_number, spec: used.specs.join(" / "), type_key: typeKey };
    }
    return { description: item.label, part_number: "", spec: "", type_key: typeKey };
  });
}

/** Search text for a shopping-list line: part number, else spec + description, else vehicle + description. */
export function listItemQuery(item, vehicle) {
  const pn = String(item.part_number || "").trim();
  const what = String(item.description || "").trim();
  if (pn) return { query: `${pn} ${what}`.trim(), exact: true };
  const spec = String(item.spec || "").trim();
  if (spec) return { query: `${spec} ${what}`.trim(), exact: true };
  const ymm = yearMakeModel(vehicle);
  return { query: ymm ? `${ymm} ${what}` : what, exact: false };
}

/** Search text for one saved part: the exact part number when there is one. */
export function partQuery(part, vehicle, typeLabel) {
  const number = String(part.part_number || "").trim();
  if (number) return { query: number, exact: true };
  const what = String(part.name || "").trim() || typeLabel;
  const ymm = yearMakeModel(vehicle);
  return { query: ymm ? `${ymm} ${what}` : what, exact: false };
}

/** The retailer's search address for some text (affiliate tag only if configured). */
export function retailerUrl(retailer, query) {
  let url = retailer.search_url.replace("{q}", encodeURIComponent(query));
  const a = retailer.affiliate || {};
  if (a.param && a.value) url += (url.includes("?") ? "&" : "?") + `${encodeURIComponent(a.param)}=${encodeURIComponent(a.value)}`;
  return url;
}

// ---------------------------------------------------------------------------
// This browser's preferences (kept only here, like the theme; never synced).
// ---------------------------------------------------------------------------
const KEY = "rw-smart-shopping";

export function prefs() {
  try {
    const p = JSON.parse(localStorage.getItem(KEY) || "{}");
    return { enabled: p.enabled !== false, disabled: Array.isArray(p.disabled) ? p.disabled : [], preferred: p.preferred || null };
  } catch { return { enabled: true, disabled: [], preferred: null }; }
}

export function savePrefs(p) {
  try { localStorage.setItem(KEY, JSON.stringify(p)); } catch { /* storage blocked: defaults apply */ }
}

/** Retailers to offer: enabled in the catalog and not turned off here, preferred one first. */
export function activeRetailers(catalog, p = prefs()) {
  const list = (catalog.retailers || []).filter((r) => r.enabled && !p.disabled.includes(r.key));
  return list.sort((a, b) => (a.key === p.preferred ? -1 : b.key === p.preferred ? 1 : 0));
}
