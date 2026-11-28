// shopping-ui.js — the Smart Shopping dialog and its small buttons.
//
// The dialog lists what to buy (e.g. "Engine oil", "Oil filter"), each with
// an editable search box and one button per retailer.  A button opens the
// retailer's own website in a new tab — nothing is embedded or sent from
// here, and nothing in the vehicle's records changes.

import { api } from "../api.js";
import { h, icon, modal } from "../ui.js";
import { activeRetailers, affiliateOn, itemsFor, partQuery, prefs, retailerUrl, savePrefs, webPlatform } from "../shopping.js";

/** Specs and parts of a vehicle, in the shape shopping.js expects. */
export async function shoppingContext(v) {
  const [specs, parts] = await Promise.all([api(`/api/specs?vehicle_id=${v.id}`), api(`/api/parts?vehicle_id=${v.id}`)]);
  return { vehicle: v, specs: Object.fromEntries(specs.map((s) => [s.key, s.value])), parts };
}

/** Is Smart Shopping switched on in this browser (and is there a retailer)? */
export function shoppingOn(catalog) {
  return prefs().enabled && activeRetailers(catalog).length > 0;
}

/** The dialog.  items = [{label, query, exact, hint}] */
export function openShopping(title, items, catalog) {
  const specLabel = Object.fromEntries(catalog.specs.map((s) => [s.key, s.label]));
  const platform = webPlatform();
  const allowed = prefs().affiliate;
  const url = (r, q) => retailerUrl(r, q, platform, allowed);
  const paid = activeRetailers(catalog).filter((r) => affiliateOn(r, platform, allowed));
  const rows = items.map((it) => {
    const box = h("input", { class: "input", value: it.query, maxlength: 200, "aria-label": `Search for ${it.label}` });
    const buttons = activeRetailers(catalog).map((r) => {
      const isPaid = paid.includes(r);
      const a = h("a", { class: "btn small shop-retailer", target: "_blank", rel: isPaid ? "noopener noreferrer sponsored" : "noopener noreferrer",
        href: url(r, it.query) }, icon("u-" + (r.icon || "cart")), r.name, isPaid ? h("span", { class: "muted small" }, " · affiliate") : null);
      // Accent colour via the style API (the page's CSP forbids inline style attributes).
      if (/^#[0-9a-f]{3,8}$/i.test(r.color || "")) a.style.setProperty("--retailer", r.color);
      // Remember the last retailer used as the preferred one (listed first next time).
      a.addEventListener("click", () => { const p = prefs(); p.preferred = r.key; savePrefs(p); });
      a.dataset.key = r.key;
      return a;
    });
    // The search text can be adjusted before opening a retailer.
    box.addEventListener("input", () => {
      const q = box.value.trim() || it.query;
      for (const a of buttons) a.href = url(activeRetailers(catalog).find((r) => r.key === a.dataset.key), q);
    });
    return h("div", { class: "shop-item" },
      h("strong", {}, it.label),
      box,
      it.hint ? h("p", { class: "muted small" }, `Tip: add “${specLabel[it.hint] || it.hint}” in Specs for an exact search.`) : null,
      h("div", { class: "btn-row" }, buttons));
  });
  return modal({ title, body: h("div", { class: "stack" }, rows,
    h("p", { class: "muted small" }, "Opens the retailer's website in a new tab. Check that the product fits your vehicle before buying. Nothing in your records changes."),
    // Required disclosure, shown whenever an affiliate link is on screen.
    paid.length ? h("p", { class: "small affiliate-note" }, ...paid.map((r) => r.affiliate.disclosure + " "),
      "Searches are the same with or without it — commissions never decide what's suggested. You can turn affiliate links off in Settings → Smart Shopping.") : null),
  actions: [{ label: "Close", value: null }] });
}

/** "Find supplies" for a reminder — or null when this reminder has nothing to shop for. */
export function reminderShopButton(r, ctx, catalog, labelText = null) {
  if (!shoppingOn(catalog)) return null;
  const items = itemsFor(r.type_key, ctx, catalog);
  if (!items.length) return null;
  const name = r.title || (catalog.types.find((t) => t.key === r.type_key) || {}).label || "this reminder";
  return h("button", { class: "btn small ghost shop-btn", type: "button", "aria-label": `Find supplies for ${name}`, title: "Find supplies",
    onclick: (e) => { e.stopPropagation(); openShopping(`Find supplies — ${name}`, items, catalog); },
    onkeydown: (e) => e.stopPropagation() }, icon("u-cart"), labelText);
}

/** "Find Products" for one saved part (the exact part number when there is one). */
export function partShopButton(p, v, catalog) {
  if (!shoppingOn(catalog)) return null;
  const typeLabel = (catalog.types.find((t) => t.key === p.type_key) || {}).label || "Part";
  const { query, exact } = partQuery(p, v, typeLabel);
  return h("button", { class: "btn small", type: "button", "aria-label": `Shop for ${p.name || typeLabel}`,
    onclick: () => openShopping(`Shop — ${p.name || typeLabel}`, [{ label: p.name || typeLabel, query, exact, hint: null }], catalog) },
  icon("u-search"), "Find Products");
}
