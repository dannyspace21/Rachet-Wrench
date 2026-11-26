// metrics-tab.js — the vehicle's Metrics tab: how the maintenance history
// adds up, and the exact records behind every number.
//
// Everything is clickable and everything filters everything else:
//   * the time range / Maintenance-Repairs / DIY-Shop buttons,
//   * a tile at the top (records, total spent, a service's count, its
//     average interval),
//   * a category (donut slice or its legend row), a service (bar),
//   * a year, or the maintenance / repair part of a year's column.
// Active filters show as chips (× removes one).  The panel at the bottom
// always lists the records behind the numbers above it, with a total row
// that equals the tile — so you can see how every number was reached.
// All numbers come from metrics.js (tested against hand-worked cases).

import { api } from "../api.js";
import { h, mount, money, dateText, emptyState } from "../ui.js";
import { byGroup, byType, byYear, cutoff, filterEntries, intervals, newestFirst, totals } from "../metrics.js";

const SVG = "http://www.w3.org/2000/svg";
const PAGE = 50;

function s(tag, attrs = {}, ...kids) {
  const e = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null) e.setAttribute(k, v);
  for (const k of kids.flat()) if (k != null) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
}
const slotColor = (slot) => `var(--m${slot + 1})`;
const paint = (el, color, prop = "fill") => { el.style.setProperty(prop, color); return el; };

/** One floating tooltip for the whole tab (values first, label second). */
function tooltip() {
  let tip = null;
  return {
    show(e, value, label) {
      if (!tip) { tip = h("div", { class: "mx-tip", role: "status" }); document.body.append(tip); }
      tip.replaceChildren(h("b", {}, value), h("span", {}, label));
      tip.style.left = `${Math.min(e.clientX + 14, innerWidth - 240)}px`;
      tip.style.top = `${e.clientY + 14}px`;
      tip.hidden = false;
    },
    hide() { if (tip) tip.hidden = true; },
    remove() { tip?.remove(); },
  };
}

export async function metricsTab(body, v, { catalog, label }) {
  const entries = await api(`/api/entries?vehicle_id=${v.id}`);
  const today = (() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; })();
  const nameOf = (e) => (e.type_key === "custom" && e.custom_type) || label[e.type_key] || "Custom / other";
  const groupLabel = Object.fromEntries(catalog.metric_groups.map((g) => [g.key, g.label]));
  const groupSlot = Object.fromEntries(catalog.metric_groups.map((g, i) => [g.key, i]));
  const unit = v.mileage_unit || "mi";
  const tip = tooltip();
  window.addEventListener("hashchange", () => tip.remove(), { once: true });

  if (!entries.filter((e) => !e.deleted_at).length) {
    mount(body, emptyState("u-list", "No history yet", "Metrics appear once you log maintenance or repairs.",
      h("a", { class: "btn primary", href: `#/v/${v.id}/entry/new` }, "Add entry")));
    return;
  }

  // The whole state: filters + which view the records panel shows.
  const f = { range: "all", kind: null, group: null, type: null, year: null, paid: false, by: null };
  let view = "records"; // records | cost | interval
  let shown = PAGE;

  const root = h("div", { class: "mx" });
  mount(body, root);

  const set = (changes, newView = null) => {
    Object.assign(f, changes);
    if (newView) view = newView;
    if (view === "interval" && !f.type) view = "records";
    shown = PAGE;
    draw();
  };
  const toggle = (key, value) => set({ [key]: f[key] === value ? null : value });

  function segmented(options, value, onPick, aria) {
    return h("div", { class: "seg", role: "group", "aria-label": aria }, options.map(([val, text]) =>
      h("button", { type: "button", "aria-pressed": String(value === val), onclick: () => onPick(val) }, text)));
  }

  function chips() {
    const list = [];
    const add = (text, clear) => list.push(h("button", { class: "mx-chip", type: "button", "aria-label": `Remove filter: ${text}`, onclick: clear },
      text, h("span", { class: "x" }, "×")));
    if (f.range !== "all") add({ "5y": "Last 5 years", "2y": "Last 2 years", "1y": "Last 12 months" }[f.range], () => set({ range: "all" }));
    if (f.kind) add(f.kind === "repair" ? "Repairs" : "Maintenance", () => set({ kind: null }));
    if (f.by) add(f.by === "diy" ? "Done myself" : "Done by a shop", () => set({ by: null }));
    if (f.group) add(groupLabel[f.group], () => set({ group: null }));
    if (f.type) add(label[f.type] || "Custom / other", () => set({ type: null }));
    if (f.year) add(String(f.year), () => set({ year: null }));
    if (f.paid) add("Had a cost", () => set({ paid: false }));
    if (list.length > 1) list.push(h("button", { class: "btn small ghost", type: "button", onclick: () => set({ range: "all", kind: null, group: null, type: null, year: null, paid: false, by: null }) }, "Clear all"));
    return h("div", { class: "mx-chips", "aria-label": "Active filters" },
      list.length ? list : h("span", { class: "muted small" }, "Showing everything. Click any number, slice, bar or year to filter."));
  }

  function draw() {
    tip.hide();
    const list = filterEntries(entries, f, catalog, today);
    const t = totals(list);
    // The service the last two tiles describe: the one picked, else oil
    // changes when there are any, else the most frequent service shown.
    const focusType = f.type || (list.some((e) => e.type_key === "oil_change") ? "oil_change" : (byType(list, catalog)[0] || {}).type || "oil_change");
    const focusLabel = label[focusType] || "Custom / other";
    const typeRows = list.filter((e) => e.type_key === focusType);
    const iv = intervals(typeRows);
    const from = cutoff(f.range, today);

    // ---- Filter bar
    const bar = h("div", { class: "mx-bar" },
      segmented([["all", "All time"], ["5y", "5 years"], ["2y", "2 years"], ["1y", "12 months"]], f.range, (r) => set({ range: r }), "Time range"),
      segmented([[null, "All work"], ["maintenance", "Maintenance"], ["repair", "Repairs"]], f.kind, (k) => set({ kind: k }), "Kind of work"),
      segmented([[null, "Anyone"], ["diy", "DIY"], ["shop", "Shop"]], f.by, (b) => set({ by: b }), "Done by"));

    // ---- Tiles: each opens the records behind it
    const tile = (key, title, value, sub, onclick) => h("button", { type: "button", class: "mx-kpi" + (view === key ? " on" : ""), onclick,
      "aria-label": `${title}: ${value}. Show the records behind this number.` }, h("small", {}, title), h("b", {}, value), h("span", {}, sub));
    const tiles = h("div", { class: "mx-kpis" },
      tile("records", "Records", t.count.toLocaleString(), from ? `since ${dateText(from)}` : "all logged work", () => set({}, "records")),
      tile("cost", "Total spent", money(t.cents), `${t.paid} record${t.paid === 1 ? "" : "s"} with a cost`, () => set({}, "cost")),
      tile("type", focusLabel, String(typeRows.length), "times done", () => set({ type: focusType }, "records")),
      tile("interval", `Avg. ${unit} between: ${focusLabel.toLowerCase()}`, iv.avgMiles != null ? iv.avgMiles.toLocaleString() : "—",
        iv.avgMonths != null ? `about every ${iv.avgMonths} months` : "needs 2 or more with mileage", () => set({ type: focusType }, "interval")));
    tiles.querySelectorAll(".mx-kpi")[2].classList.toggle("on", view === "records" && !!f.type);
    tiles.querySelectorAll(".mx-kpi")[0].classList.toggle("on", view === "records" && !f.type);

    // ---- Donut: categories (every filter except category)
    const gl = byGroup(filterEntries(entries, f, catalog, today, ["group"]), catalog);
    const gTotal = gl.reduce((n, g) => n + g.count, 0);
    const donut = s("svg", { width: 210, height: 210, viewBox: "0 0 210 210", role: "img", "aria-label": "Records by category", class: f.group ? "mx-dim" : "" });
    let a0 = -Math.PI / 2;
    const P = (r, a) => `${105 + r * Math.cos(a)} ${105 + r * Math.sin(a)}`;
    for (const g of gl) {
      const a1 = a0 + (g.count / gTotal) * Math.PI * 2;
      const gap = gl.length > 1 ? 0.012 : 0, st = a0 + gap, en = Math.max(st + 0.001, a1 - gap), large = en - st > Math.PI ? 1 : 0;
      const d = gl.length === 1
        ? "M105 10 A95 95 0 1 1 104.99 10 L104.99 42 A63 63 0 1 0 105 42Z"
        : `M${P(95, st)} A95 95 0 ${large} 1 ${P(95, en)} L${P(63, en)} A63 63 0 ${large} 0 ${P(63, st)}Z`;
      const p = paint(s("path", { d, class: "mx-slice" + (f.group === g.key ? " on" : ""), tabindex: 0, role: "button",
        "aria-label": `${g.label}: ${g.count} records, ${money(g.cents)}` }), slotColor(g.slot));
      p.addEventListener("pointermove", (e) => tip.show(e, `${g.count} record${g.count === 1 ? "" : "s"} · ${money(g.cents)}`, `${g.label} · ${Math.round((g.count / gTotal) * 100)}%`));
      p.addEventListener("pointerleave", () => tip.hide());
      p.addEventListener("click", () => pickGroup(g.key));
      p.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pickGroup(g.key); } });
      donut.append(p);
      a0 = a1;
    }
    const sel = f.group && gl.find((g) => g.key === f.group);
    donut.append(paint(s("text", { x: 105, y: 104, "text-anchor": "middle", "font-size": 30, "font-weight": 750 }, String(sel ? sel.count : gTotal)), "var(--text)"),
      paint(s("text", { x: 105, y: 126, "text-anchor": "middle", "font-size": 12 }, sel ? sel.label : "records"), "var(--muted)"));
    const legend = h("ul", { class: "mx-legend" }, gl.map((g) => h("li", {}, h("button", { type: "button", class: f.group === g.key ? "on" : "",
      "aria-pressed": String(f.group === g.key), onclick: () => pickGroup(g.key) },
    paint(h("span", { class: "mx-sw" }), slotColor(g.slot), "background"), h("span", {}, g.label),
    h("span", { class: "mx-num" }, String(g.count)), h("span", { class: "mx-pct" }, `${Math.round((g.count / gTotal) * 100)}%`)))));

    // ---- Bars: services (every filter except service)
    const tl = byType(filterEntries(entries, f, catalog, today, ["type"]), catalog);
    const tMax = Math.max(1, ...tl.map((x) => x.count));
    const bars = h("div", { class: "mx-bars" }, tl.slice(0, 12).map((x) => {
      const b = h("button", { type: "button", class: "mx-brow" + (f.type === x.type ? " on" : ""), "aria-pressed": String(f.type === x.type),
        "aria-label": `${x.label}: ${x.count} records, ${money(x.cents)}`, onclick: () => set({ type: f.type === x.type ? null : x.type }, f.type === x.type ? null : "records") },
      h("span", { class: "l" }, x.label),
      h("span", {}, paint(h("span", { class: "f" }), slotColor(groupSlot[x.group]), "background")),
      h("span", { class: "mx-num" }, String(x.count)));
      b.querySelector(".f").style.width = `${(x.count / tMax) * 100}%`;
      b.addEventListener("pointermove", (e) => tip.show(e, `${x.count} time${x.count === 1 ? "" : "s"} · ${money(x.cents)}`, x.label));
      b.addEventListener("pointerleave", () => tip.hide());
      return b;
    }));
    const moreTypes = tl.length > 12 ? h("p", { class: "muted small" }, `+ ${tl.length - 12} more — pick a category to narrow the list.`) : null;

    // ---- Years (every filter except year): click a year, or its maintenance / repair part
    const ylist = filterEntries(entries, f, catalog, today, ["year"]);
    const firstYear = from ? Number(from.slice(0, 4)) : Math.min(...entries.filter((e) => !e.deleted_at).map((e) => Number(e.date.slice(0, 4))));
    const yd = byYear(ylist, firstYear, Number(today.slice(0, 4)));
    const W = Math.max(300, Math.min(1180, (body.clientWidth || 900) - 40)), H = 190, padL = 30, padB = 22;
    const top = Math.max(2, ...yd.map((d) => d.maintenance + d.repair));
    const sy = (n) => H - padB - (n / top) * (H - padB - 10);
    const ysvg = s("svg", { width: W, height: H, viewBox: `0 0 ${W} ${H}`, class: "mx-axis", role: "img", "aria-label": "Records per year" });
    for (const tick of [0, Math.round(top / 2), top]) {
      ysvg.append(s("line", { x1: padL, x2: W, y1: sy(tick), y2: sy(tick), class: "mx-gl" }), s("text", { x: padL - 6, y: sy(tick) + 4, "text-anchor": "end" }, String(tick)));
    }
    const bw = (W - padL) / yd.length;
    yd.forEach((d, i) => {
      const x = padL + i * bw + bw * 0.2, w = bw * 0.6;
      const g = s("g", { class: "mx-col" + (f.year === d.year ? " on" : ""), tabindex: 0, role: "button",
        "aria-label": `${d.year}: ${d.maintenance} maintenance, ${d.repair} repairs, ${money(d.cents)}` });
      g.append(s("rect", { x: padL + i * bw, y: 0, width: bw, height: H - padB, fill: "transparent", class: "hit" }));
      const seg = (n, base, kind, color, roundTop) => {
        if (!n) return;
        const r = paint(s("rect", { x, y: sy(base + n), width: w, height: Math.max(1, sy(base) - sy(base + n) - (base ? 2 : 0)), rx: roundTop ? 4 : 0, class: "mx-seg" }), color);
        r.addEventListener("click", (e) => { e.stopPropagation(); set({ year: d.year, kind }); });
        g.append(r);
      };
      seg(d.maintenance, 0, "maintenance", "var(--m-maint)", !d.repair);
      seg(d.repair, d.maintenance, "repair", "var(--m2)", true);
      g.addEventListener("click", () => toggle("year", d.year));
      g.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); toggle("year", d.year); } });
      g.addEventListener("pointermove", (e) => tip.show(e, `${d.maintenance + d.repair} records · ${money(d.cents)}`, `${d.year} · ${d.maintenance} maintenance, ${d.repair} repairs`));
      g.addEventListener("pointerleave", () => tip.hide());
      const lab = bw < 44 ? (i % 2 === 0 ? `'${String(d.year).slice(2)}` : null) : String(d.year);
      ysvg.append(g, lab ? s("text", { x: padL + i * bw + bw / 2, y: H - 6, "text-anchor": "middle" }, lab) : null);
    });
    const yLegend = h("div", { class: "row", role: "group", "aria-label": "Show only" },
      h("button", { type: "button", class: "btn small ghost", "aria-pressed": String(f.kind === "maintenance"), onclick: () => toggle("kind", "maintenance") },
        paint(h("span", { class: "mx-sw" }), "var(--m-maint)", "background"), "Maintenance"),
      h("button", { type: "button", class: "btn small ghost", "aria-pressed": String(f.kind === "repair"), onclick: () => toggle("kind", "repair") },
        paint(h("span", { class: "mx-sw" }), "var(--m2)", "background"), "Repairs"));

    mount(root,
      bar, chips(), tiles,
      h("div", { class: "mx-two" },
        h("section", { class: "mx-card" }, h("h2", {}, "By category"), h("p", { class: "hint" }, "Click a category to filter everything by it"), h("div", { class: "mx-donut" }, donut, legend)),
        h("section", { class: "mx-card" }, h("h2", {}, f.group ? `${groupLabel[f.group]} — services` : "Most frequent services"),
          h("p", { class: "hint" }, "Click a service to see every time it was done"), tl.length ? bars : h("p", { class: "muted" }, "Nothing matches these filters."), moreTypes)),
      h("section", { class: "mx-card" }, h("h2", {}, "By year"), h("p", { class: "hint" }, "Click a year — or just its maintenance or repair part"), ysvg, yLegend),
      drill(list, t, typeRows, iv, focusType, focusLabel));
  }

  function pickGroup(key) {
    const g = f.group === key ? null : key;
    // A service from another category can't stay selected.
    const keepType = f.type && g && byType(filterEntries(entries, { ...f, group: g }, catalog, today, ["type"]), catalog).some((x) => x.type === f.type);
    set({ group: g, type: keepType ? f.type : (g ? null : f.type) });
  }

  // ---- The records behind the numbers
  function drill(list, t, typeRows, iv, focusType, focusLabel) {
    const views = [["records", "Records"], ["cost", "Where the money went"]];
    if (f.type) views.push(["interval", "Time between services"]);
    const head = h("div", { class: "row between" },
      h("div", {}, h("h2", {}, "The records behind these numbers"),
        h("h3", {}, view === "cost" ? `${money(t.cents)} across ${t.paid} record${t.paid === 1 ? "" : "s"}`
          : view === "interval" ? `${focusLabel} — ${typeRows.length} time${typeRows.length === 1 ? "" : "s"}`
            : `${t.count} record${t.count === 1 ? "" : "s"} · ${money(t.cents)}`)),
      h("div", { class: "seg", role: "group", "aria-label": "Show" }, views.map(([k, text]) =>
        h("button", { type: "button", "aria-pressed": String(view === k), onclick: () => set({}, k) }, text))));
    const open = (e) => { location.hash = `#/e/${e.id}`; };
    const row = (e, cells) => { const tr = h("tr", { tabindex: 0, "aria-label": `Open ${nameOf(e)} on ${dateText(e.date)}` }, cells);
      tr.addEventListener("click", () => open(e)); tr.addEventListener("keydown", (k) => { if (k.key === "Enter") open(e); }); return tr; };
    const mi = (n) => (n == null ? "—" : `${n.toLocaleString()} ${unit}`);
    const by = (e) => (e.performed_by === "shop" ? (e.shop_name || "Shop") : "DIY");
    const more = (n) => (n > shown ? h("button", { class: "btn", type: "button", onclick: () => { shown = Infinity; draw(); } }, `Show all ${n}`) : null);

    if (view === "cost") {
      const paid = list.filter((e) => (e.cost_cents || 0) > 0).sort((a, b) => b.cost_cents - a.cost_cents || b.date.localeCompare(a.date));
      const groups = byGroup(paid, catalog).sort((a, b) => b.cents - a.cents);
      const gmax = Math.max(1, ...groups.map((g) => g.cents));
      const breakdown = h("div", { class: "mx-bars" }, groups.map((g) => {
        const b = h("button", { type: "button", class: "mx-brow", onclick: () => pickGroup(g.key), "aria-label": `${g.label}: ${money(g.cents)}` },
          h("span", { class: "l" }, g.label), h("span", {}, paint(h("span", { class: "f" }), slotColor(g.slot), "background")),
          h("span", { class: "mx-num" }, `${money(g.cents)} · ${t.cents ? Math.round((g.cents / t.cents) * 100) : 0}%`));
        b.querySelector(".f").style.width = `${(g.cents / gmax) * 100}%`;
        return b;
      }));
      return h("section", { class: "mx-card mx-drill" }, head,
        h("p", { class: "hint" }, "Every record with a cost, most expensive first. The total at the bottom is the “Total spent” tile."),
        breakdown,
        h("table", { class: "mx-table" },
          h("thead", {}, h("tr", {}, h("th", {}, "Date"), h("th", {}, "Service"), h("th", { class: "mx-hide-sm" }, "Done by"), h("th", { class: "r" }, "Cost"))),
          h("tbody", {}, paid.slice(0, shown).map((e) => row(e, [h("td", {}, dateText(e.date)), h("td", {}, nameOf(e)), h("td", { class: "mx-hide-sm" }, by(e)), h("td", { class: "r" }, money(e.cost_cents))]))),
          h("tfoot", {}, h("tr", {}, h("td", { colspan: 2 }, `Total (${paid.length})`), h("td", { class: "mx-hide-sm" }), h("td", { class: "r" }, money(t.cents))))),
        more(paid.length),
        t.count > t.paid ? h("p", { class: "muted small" }, `${t.count - t.paid} more record${t.count - t.paid === 1 ? " has" : "s have"} no cost entered ($0.00).`) : null);
    }

    if (view === "interval") {
      const rows = iv.rows.slice().reverse();
      const gaps = iv.rows.filter((r) => r.sinceMiles != null && r.sinceMiles >= 0);
      const typos = iv.rows.filter((r) => r.sinceMiles != null && r.sinceMiles < 0).length;
      return h("section", { class: "mx-card mx-drill" }, head,
        h("div", { class: "mx-stats" },
          [["Times done", String(typeRows.length)], ["Last done", typeRows.length ? dateText(newestFirst(typeRows)[0].date) : "—"],
            [`Avg. ${unit} between`, iv.avgMiles != null ? iv.avgMiles.toLocaleString() : "—"], ["Avg. months between", iv.avgMonths != null ? String(iv.avgMonths) : "—"],
            ["Total spent", money(totals(typeRows).cents)]].map(([k, val]) => h("div", { class: "mx-stat" }, h("span", {}, k), h("b", {}, val)))),
        timeline(typeRows, focusType),
        h("table", { class: "mx-table" },
          h("thead", {}, h("tr", {}, h("th", {}, "Date"), h("th", { class: "r" }, "Mileage"), h("th", { class: "r" }, `${unit} since last`), h("th", { class: "r mx-hide-sm" }, "Months since last"), h("th", { class: "r" }, "Cost"))),
          h("tbody", {}, rows.slice(0, shown).map((r) => row(r.entry, [h("td", {}, dateText(r.entry.date)), h("td", { class: "r" }, mi(r.entry.mileage)),
            h("td", { class: "r" }, r.sinceMiles == null ? "—" : r.sinceMiles < 0
              ? h("span", { title: "The mileage is lower than the record before — check this record. Not counted in the average." }, `⚠ ${r.sinceMiles.toLocaleString()}`)
              : r.sinceMiles.toLocaleString()), h("td", { class: "r mx-hide-sm" }, r.sinceMonths == null ? "—" : String(r.sinceMonths)),
            h("td", { class: "r" }, money(r.entry.cost_cents))]))),
          h("tfoot", {}, h("tr", {}, h("td", { colspan: 2 }, "Average"),
            h("td", { class: "r" }, iv.avgMiles != null ? `${iv.avgMiles.toLocaleString()} (${gaps.length} gap${gaps.length === 1 ? "" : "s"})` : "—"),
            h("td", { class: "r mx-hide-sm" }, iv.avgMonths != null ? String(iv.avgMonths) : "—"), h("td", { class: "r" }, money(totals(typeRows).cents))))),
        more(rows.length),
        h("p", { class: "muted small" }, `Average ${unit} = the gaps added up ÷ how many gaps (only records with a mileage count).`),
        typos ? h("p", { class: "small" }, `⚠ ${typos} record${typos === 1 ? " has" : "s have"} a lower mileage than the one before (probably a typo) — left out of the average. Click it to fix it.`) : null);
    }

    const rows = newestFirst(list);
    return h("section", { class: "mx-card mx-drill" }, head,
      f.type ? timeline(typeRows, focusType) : null,
      h("table", { class: "mx-table" },
        h("thead", {}, h("tr", {}, h("th", {}, "Date"), h("th", {}, "Service"), h("th", { class: "r mx-hide-sm" }, "Mileage"), h("th", { class: "mx-hide-sm" }, "Done by"), h("th", { class: "r" }, "Cost"))),
        h("tbody", {}, rows.slice(0, shown).map((e) => row(e, [h("td", {}, dateText(e.date)),
          h("td", {}, nameOf(e), e.kind === "repair" ? h("span", { class: "muted small" }, " · repair") : null),
          h("td", { class: "r mx-hide-sm" }, mi(e.mileage)), h("td", { class: "mx-hide-sm" }, by(e)), h("td", { class: "r" }, money(e.cost_cents))]))),
        h("tfoot", {}, h("tr", {}, h("td", { colspan: 2 }, `Total (${rows.length})`), h("td", { class: "mx-hide-sm" }), h("td", { class: "mx-hide-sm" }), h("td", { class: "r" }, money(t.cents))))),
      more(rows.length),
      h("p", { class: "muted small" }, "Click a record to open it."));
  }

  /** Each dot = one time the service was done (date across, mileage up). */
  function timeline(rows, typeKey) {
    const pts = rows.filter((e) => e.mileage != null).sort((a, b) => a.date.localeCompare(b.date));
    if (pts.length < 1) return null;
    const W = Math.max(300, Math.min(1180, (body.clientWidth || 900) - 40)), H = 160, padL = 56, padB = 22;
    const t0 = Date.parse(pts[0].date), t1 = Math.max(t0 + 86400000 * 30, Date.parse(pts[pts.length - 1].date));
    const mMax = Math.max(1000, ...pts.map((e) => e.mileage)) * 1.05;
    const sx = (d) => padL + ((Date.parse(d) - t0) / (t1 - t0)) * (W - padL - 14), sy = (m) => H - padB - (m / mMax) * (H - padB - 10);
    const color = slotColor(groupSlotOf(typeKey));
    const svg = s("svg", { width: W, height: H, viewBox: `0 0 ${W} ${H}`, class: "mx-axis", role: "img", "aria-label": "When it was done, and at what mileage" });
    for (const m of [0, mMax / 2, mMax]) {
      svg.append(s("line", { x1: padL, x2: W, y1: sy(m), y2: sy(m), class: "mx-gl" }),
        s("text", { x: padL - 8, y: sy(m) + 4, "text-anchor": "end" }, m ? `${Math.round(m / 1000)}k` : "0"));
    }
    const y0 = new Date(t0).getFullYear(), y1 = new Date(t1).getFullYear(), step = Math.max(1, Math.ceil((y1 - y0 + 1) / (W < 600 ? 5 : 10)));
    for (let y = y0 + 1; y <= y1; y += step) svg.append(s("text", { x: sx(`${y}-01-01`), y: H - 6, "text-anchor": "middle" }, String(y)));
    if (pts.length > 1) svg.append(paint(s("polyline", { points: pts.map((e) => `${sx(e.date)},${sy(e.mileage)}`).join(" "), fill: "none", "stroke-width": 2, "stroke-opacity": 0.45 }), color, "stroke"));
    for (const e of pts) {
      const g = s("g", { tabindex: 0, role: "button", "aria-label": `${dateText(e.date)}, ${e.mileage.toLocaleString()} ${unit}` });
      g.append(s("circle", { cx: sx(e.date), cy: sy(e.mileage), r: 13, fill: "transparent" }),
        paint(paint(s("circle", { cx: sx(e.date), cy: sy(e.mileage), r: 5, "stroke-width": 2 }), color), "var(--surface)", "stroke"));
      g.style.cursor = "pointer";
      g.addEventListener("pointermove", (ev) => tip.show(ev, `${e.mileage.toLocaleString()} ${unit}`, `${dateText(e.date)} · ${money(e.cost_cents)}`));
      g.addEventListener("pointerleave", () => tip.hide());
      g.addEventListener("click", () => { location.hash = `#/e/${e.id}`; });
      svg.append(g);
    }
    return h("div", {}, svg, h("p", { class: "muted small" }, "Each dot is one time it was done (date × mileage). Click a dot to open it."));
  }

  function groupSlotOf(typeKey) {
    const tt = catalog.types.find((x) => x.key === typeKey);
    const i = catalog.metric_groups.findIndex((g) => tt && g.categories.includes(tt.category));
    return i < 0 ? catalog.metric_groups.length - 1 : i;
  }

  draw();
}
