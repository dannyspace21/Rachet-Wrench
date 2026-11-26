// vehicle.js — one vehicle's page (with tabs) and the add/edit vehicle form.

import { api, getCatalog, getMe } from "../api.js";
import {
  h, mount, icon, typeBadge, money, miles, dateText, today, vehicleName, vehicleFull, emptyState, field,
  segmented, modal, confirmBox, toast, showError, parseIntOrNull, filePicker,
} from "../ui.js";
import { reminderItem } from "./dashboard.js";
import { partShopButton, reminderShopButton, shoppingContext } from "./shopping-ui.js";
import { addPartToList, addReminderToList } from "./shopping-list.js";
import { metricsTab } from "./metrics-tab.js";

// In the order you use them: what's due, what fits, what was done, the specs.
// (Mileage is updated with the "Update mileage" button; no separate tab.)
const TABS = [
  ["reminders", "Reminders", "u-bell"], ["parts", "Parts", "u-part"], ["history", "History", "u-list"],
  ["metrics", "Metrics", "u-chart"], ["specs", "Specs", "u-spec"], ["docs", "Documents", "u-book"],
];
const SOURCE_CLASS = { vin: "src-vin", provider: "src-vin", sticker: "src-sticker", user: "src-user", unverified: "src-unverified", carfax: "src-vin" };

// ===========================================================================
// Vehicle page
// ===========================================================================
export async function vehicleView(main, { params }) {
  const [id, tabParam] = params;
  const tab = TABS.some(([k]) => k === tabParam) ? tabParam : "reminders";
  const [v, catalog] = await Promise.all([api(`/api/vehicles/${id}`), getCatalog()]);
  const label = Object.fromEntries(catalog.types.map((t) => [t.key, t.label]));

  const photo = v.photo_attachment_id
    ? h("img", { src: `/api/attachments/${v.photo_attachment_id}/thumb`, alt: "", class: "vphoto" })
    : h("span", { class: "type-badge neutral", style: null }, icon(v.kind === "motorcycle" ? "u-motorcycle" : "u-car", "lg"));

  const head = h("div", { class: "card", style: null },
    h("div", { class: "row between" },
      h("div", { class: "row" }, h("div", { class: "vhead-photo" }, photo),
        h("div", {}, h("h1", {}, vehicleName(v)),
          h("p", { class: "muted" }, [vehicleFull(v), v.vin ? `VIN ${v.vin}` : null].filter(Boolean).join(" · ") || "Add details with Edit"),
          h("p", { class: "kpi" }, h("b", {}, v.current_mileage !== null ? Number(v.current_mileage).toLocaleString() : "—"), h("span", { class: "muted" }, v.mileage_unit)))),
      h("div", { class: "btn-row" },
        h("a", { class: "btn primary", href: `#/v/${id}/entry/new` }, icon("u-plus"), "Add entry"),
        h("button", { class: "btn", type: "button", onclick: () => logMileage(v) }, icon("u-gauge"), "Update mileage"),
        h("a", { class: "btn", href: `#/v/${id}/edit` }, icon("u-edit"), "Edit"))),
    h("div", { class: "btn-row", style: null },
      h("a", { class: "btn small", href: `#/v/${id}/sticker` }, icon("u-sticker"), "Scan door sticker"),
      h("a", { class: "btn small", href: `#/v/${id}/carfax` }, icon("u-carfax"), "Import Carfax"),
      h("a", { class: "btn small", href: `/api/vehicles/${id}/report.pdf`, download: "" }, icon("u-download"), "History report (PDF)")));
  head.querySelector(".btn-row:last-child").style.marginTop = "18px";

  const tabs = h("nav", { class: "tabs", "aria-label": "Vehicle sections" }, TABS.map(([key, text, ic]) =>
    h("a", { href: `#/v/${id}/${key}`, class: key === tab ? "active" : "", "aria-current": key === tab ? "page" : null }, icon(ic), text)));

  const body = h("div", {});
  mount(main, h("a", { class: "back", href: "#/" }, icon("u-chevron_left"), "Garage"), head, h("div", { style: null }), tabs, body);
  main.children[2].style.height = "22px";
  // Fixed allow-list of tab screens (anything else shows Reminders).
  const renderers = new Map([["history", historyTab], ["specs", specsTab], ["reminders", remindersTab],
    ["parts", partsTab], ["metrics", metricsTab], ["docs", docsTab]]);
  const renderTab = renderers.get(tab) || remindersTab;
  await renderTab(body, v, { catalog, label });
}

/** Quick "update mileage" dialog: records an odometer reading. */
async function logMileage(v) {
  const m = field(`Current mileage (${v.mileage_unit})`, { inputmode: "numeric", value: v.current_mileage ?? "" });
  const d = field("Date", { type: "date", value: today() });
  const ok = await modal({ title: "Update mileage", body: h("div", { class: "form" }, m, d),
    actions: [{ label: "Cancel", value: false }, { label: "Save", primary: true, value: true }] });
  if (!ok) return;
  try {
    await api("/api/odometer", { method: "POST", body: { vehicle_id: v.id, date: d.input.value, mileage: parseIntOrNull(m.input.value) } });
    toast("Mileage saved");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

// ---------------------------------------------------------------------------
// History tab: maintenance & repair entries, filterable
// ---------------------------------------------------------------------------
async function historyTab(body, v, { label }) {
  const [entries, atts] = await Promise.all([api(`/api/entries?vehicle_id=${v.id}`), api(`/api/attachments?vehicle_id=${v.id}`)]);
  const photos = {};
  for (const a of atts) if (a.entry_id && a.content_type.startsWith("image/")) (photos[a.entry_id] ||= []).push(a);
  let filter = "all";
  const list = h("div", { class: "list" });
  const totals = h("p", { class: "muted small" });
  const draw = () => {
    const shown = entries.filter((e) => filter === "all" || e.kind === filter);
    const sum = (k) => entries.filter((e) => e.kind === k).reduce((s, e) => s + (e.cost_cents || 0), 0);
    totals.textContent = `Maintenance ${money(sum("maintenance"))} · Repairs ${money(sum("repair"))} · Total ${money(sum("maintenance") + sum("repair"))}`;
    mount(list, shown.length ? shown.map((e) => entryItem(e, label, photos[e.id] || [], v)) :
      emptyState("u-list", "No records yet", "Log your first maintenance or repair.", h("a", { class: "btn primary", href: `#/v/${v.id}/entry/new` }, "Add entry")));
  };
  const seg = segmented([{ value: "all", label: "All" }, { value: "maintenance", label: "Maintenance", icon: "u-maintenance" }, { value: "repair", label: "Repairs", icon: "u-repair" }],
    filter, (val) => { filter = val; draw(); }, "Filter records");
  draw();
  mount(body, h("div", { class: "row between", style: null }, seg, totals), h("div", { style: null }), list);
  body.children[1].style.height = "16px";
}

export function entryItem(e, label, photos, v) {
  return h("a", { class: "item", href: `#/e/${e.id}` },
    typeBadge(e.type_key, e.kind),
    h("div", { class: "main" },
      // A "Custom / other" record is titled by what was done, never by the generic label.
      h("span", { class: "title" }, e.custom_type || (e.type_key === "custom" ? e.description : "") || label[e.type_key] || "Entry"),
      e.description && e.description !== e.custom_type && !(e.type_key === "custom" && !e.custom_type) ? h("span", {}, e.description) : null,
      h("span", { class: "meta" }, h("span", {}, dateText(e.date)), e.mileage !== null ? h("span", {}, miles(e.mileage, v?.mileage_unit)) : null,
        h("span", {}, e.performed_by === "shop" ? (e.shop_name || "Shop") : "DIY"), e.source === "carfax" ? h("span", {}, "From Carfax") : null),
      photos.length ? h("div", { class: "thumbs" }, photos.slice(0, 5).map((a) => h("img", { src: `/api/attachments/${a.id}/thumb`, alt: a.caption || "Photo", loading: "lazy" }))) : null),
    h("div", { class: "right" },
      h("span", { class: "pill " + (e.kind === "repair" ? "repair" : "maint") }, e.kind === "repair" ? "Repair" : "Maintenance"),
      h("strong", {}, money(e.cost_cents))));
}

// ---------------------------------------------------------------------------
// Specs tab: every catalog field, with its source label
// ---------------------------------------------------------------------------
async function specsTab(body, v, { catalog }) {
  // Hide fields that can't apply to this kind of vehicle (but always show a
  // field that already has a value).
  const CAR_ONLY = new Set(["power_steering_fluid_type", "differential_fluid_type", "seating_capacity", "wiper_size_driver", "wiper_size_passenger", "tire_size_spare", "tire_pressure_spare"]);
  const BIKE_ONLY = new Set(["fork_oil_type", "chain_size", "sprocket_front", "sprocket_rear", "chain_slack"]);
  const rows = (await api(`/api/vehicles/${v.id}/specs-table`)).filter((r) =>
    r.value || (v.kind === "motorcycle" ? !CAR_ONLY.has(r.key) : !BIKE_ONLY.has(r.key)));
  const groups = {};
  for (const r of rows) (groups[r.group] ||= []).push(r);
  const sources = catalog.spec_sources;
  const intro = h("div", { class: "notice info" }, icon("u-info"),
    h("p", {}, "Specs are never guessed. Each value shows where it came from — ", h("strong", {}, "From VIN"), ", ",
      h("strong", {}, "From door sticker"), ", ", h("strong", {}, "Entered by me"), " or ", h("strong", {}, "Unverified"),
      ". Blank means unknown. Tap Edit to confirm or change a value."));
  const actions = h("div", { class: "btn-row" },
    v.vin ? h("button", { class: "btn small", type: "button", onclick: () => decodeIntoSpecs(v) }, icon("u-barcode"), "Fill from VIN") : null,
    h("a", { class: "btn small", href: `#/v/${v.id}/sticker` }, icon("u-sticker"), "Fill from door sticker"));
  const filled = rows.filter((r) => r.value).length;
  mount(body, h("div", { class: "stack" }, intro, h("div", { class: "row between" }, h("p", { class: "muted" }, `${filled} of ${rows.length} fields filled`), actions),
    h("div", { class: "card" }, Object.entries(groups).map(([g, list]) => h("div", { class: "spec-group" }, h("h3", {}, g),
      list.map((r) => h("div", { class: "spec-row" },
        h("span", {}, r.label),
        h("span", { class: "v" + (r.value ? "" : " unknown") }, r.value || "Unknown"),
        r.source ? h("span", { class: "pill " + (SOURCE_CLASS[r.source] || "") }, sources[r.source] || r.source) : h("span", {}),
        h("button", { class: "btn small ghost", type: "button", "aria-label": `Edit ${r.label}`, onclick: () => editSpec(v, r, sources) }, icon("u-edit")))))))));
}

async function editSpec(v, row, sources) {
  const val = field(row.label, { value: row.value || "", placeholder: row.hint || "", maxlength: 255 }, row.hint ? `Example: ${row.hint}` : "");
  const src = field("Label", { tag: "select", value: row.source && row.source !== "vin" && row.source !== "sticker" ? row.source : "user",
    options: [{ value: "user", label: sources.user }, { value: "unverified", label: sources.unverified }] },
  "Saving an edit marks it as entered or confirmed by you.");
  const choice = await modal({ title: "Edit spec", body: h("div", { class: "form" }, val, src), actions: [
    row.id ? { label: "Clear", danger: true, value: "clear" } : null, { label: "Cancel", value: null }, { label: "Save", primary: true, value: "save" }].filter(Boolean) });
  try {
    if (choice === "clear" && row.id) await api(`/api/specs/${row.id}`, { method: "DELETE" });
    else if (choice === "save") {
      if (row.id) await api(`/api/specs/${row.id}`, { method: "PATCH", body: { value: val.input.value, source: src.input.value } });
      else await api("/api/specs", { method: "POST", body: { vehicle_id: v.id, key: row.key, value: val.input.value, source: src.input.value } });
    } else return;
    toast("Spec saved");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

/** Decode the saved VIN and let the user review which values to keep. */
async function decodeIntoSpecs(v) {
  let result;
  try { result = await api("/api/vin/decode", { method: "POST", body: { vin: v.vin } }); } catch (e) { showError(e); return; }
  const catalog = await getCatalog();
  const labels = Object.fromEntries(catalog.specs.map((s) => [s.key, s.label]));
  const checks = result.specs.map((s) => ({ s, box: h("input", { type: "checkbox", checked: true }), input: h("input", { class: "input", value: s.value }) }));
  if (!checks.length) { toast("The VIN decoder returned no spec values for this vehicle."); return; }
  const ok = await modal({ title: "Values from VIN", body: h("div", { class: "stack" },
    result.warnings.length ? h("div", { class: "notice" }, icon("u-warning"), h("p", {}, result.warnings.join(" "))) : null,
    h("p", { class: "muted small" }, "Untick anything that looks wrong. Saved values are labelled “From VIN”."),
    checks.map((c) => h("label", { class: "check" }, c.box, h("span", { style: null }, labels[c.s.key] || c.s.key), c.input))),
  actions: [{ label: "Cancel", value: false }, { label: "Save selected", primary: true, value: true }] });
  if (!ok) return;
  const fields = checks.filter((c) => c.box.checked && c.input.value.trim()).map((c) => ({ key: c.s.key, value: c.input.value.trim() }));
  try {
    await api(`/api/vehicles/${v.id}/apply-specs?source=vin`, { method: "POST", body: { fields } });
    toast(`${fields.length} spec${fields.length === 1 ? "" : "s"} saved`);
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

// ---------------------------------------------------------------------------
// Reminders tab
// ---------------------------------------------------------------------------
/** Overdue first, then due soon, then everything in good standing (original order kept within each). */
export function byUrgency(rems) {
  const rank = { overdue: 0, due_soon: 1 };
  return rems.map((r, i) => [r, i]).sort(([a, i], [b, j]) => ((rank[a.due?.status] ?? 2) - (rank[b.due?.status] ?? 2)) || i - j).map(([r]) => r);
}

async function remindersTab(body, v, { catalog, label }) {
  const [rems, shopCtx] = await Promise.all([api(`/api/reminders?vehicle_id=${v.id}`), shoppingContext(v)]);
  const add = h("button", { class: "btn primary", type: "button", onclick: () => editReminder(v, null, catalog) }, icon("u-plus"), "Add reminder");
  mount(body, h("div", { class: "stack" },
    h("div", { class: "row between" }, h("p", { class: "muted" }, "Get reminded by mileage, by date, or whichever comes first."), add),
    rems.length ? h("div", { class: "list" }, byUrgency(rems).map((r) => {
      const item = reminderItem(r, null, label);
      item.removeAttribute("href");
      item.setAttribute("role", "button");
      item.setAttribute("tabindex", "0");
      // Tapping a reminder opens "Mark as done" (the common case); the
      // schedule is edited from there.
      item.addEventListener("click", () => reminderActions(v, r, catalog, shopCtx, label));
      item.addEventListener("keydown", (e) => { if (e.key === "Enter") reminderActions(v, r, catalog, shopCtx, label); });
      // Smart Shopping: a small "Find supplies" button, only for reminders that have something to buy.
      const shop = reminderShopButton(r, shopCtx, catalog);
      if (shop) item.querySelector(".pill").before(shop);
      const every = [r.interval_miles ? `every ${r.interval_miles.toLocaleString()} ${v.mileage_unit}` : null, r.interval_months ? `every ${r.interval_months} months` : null].filter(Boolean).join(" or ");
      item.querySelector(".meta").append(h("span", {}, every + (r.interval_miles && r.interval_months ? ", whichever first" : "")));
      return item;
    })) : emptyState("u-bell", "No reminders", "Example: oil change every 5,000 miles or 6 months.")));
}

/**
 * One reminder: where it stands + "Mark as done".  Marking it done sets the
 * last-done date AND mileage (so a "whichever comes first" reminder can't stay
 * overdue on the mileage side) and, unless unticked, adds the record to the
 * history.  "Edit schedule" opens the full editor.
 */
/** The reminder's row (status pill etc.) as plain, non-clickable content. */
function statusRow(r, label) {
  const row = reminderItem(r, null, label);
  row.removeAttribute("href");
  return h("div", { class: "list" }, row);
}

async function reminderActions(v, r, catalog, shopCtx, label) {
  const name = r.title || label[r.type_key] || "Reminder";
  const date = field("Done on", { type: "date", value: today(), max: today() });
  const miles = field(`Mileage (${v.mileage_unit})`, { inputmode: "numeric", value: v.current_mileage ?? "" });
  const addHist = h("input", { type: "checkbox", checked: true });
  const lastDone = r.last_done_date ? `${dateText(r.last_done_date)}${r.last_done_mileage != null ? ` · ${r.last_done_mileage.toLocaleString()} ${v.mileage_unit}` : ""}` : "not yet";
  const shop = shopCtx ? reminderShopButton(r, shopCtx, catalog, "Find supplies") : null;
  const choice = await modal({ title: name, body: h("div", { class: "form" },
    statusRow(r, label),
    h("p", { class: "muted small" }, `Last done: ${lastDone}`),
    h("h3", {}, "Mark as done"),
    h("div", { class: "form-grid" }, date, miles),
    h("label", { class: "check" }, addHist, " Also add it to the vehicle's history"),
    shop ? h("div", {}, shop) : null),
    actions: [{ label: "Edit schedule", value: "edit" }, { label: "Add to shopping list", value: "list" },
      { label: "Close", value: null }, { label: "Mark as done", primary: true, value: "done" }] });
  if (choice === "edit") return editReminder(v, r, catalog, shopCtx);
  if (choice === "list") {
    // Adds what this job needs (with known part numbers/specs); never marks it done.
    try { await addReminderToList(v, r, name, shopCtx || await shoppingContext(v), catalog); } catch (e) { showError(e); }
    return;
  }
  if (choice !== "done") return;
  const when = date.input.value || today();
  const mileage = parseIntOrNull(miles.input.value);
  try {
    if (addHist.checked) {
      // The server moves this (and any other matching) reminder forward.
      await api("/api/entries", { method: "POST", body: { vehicle_id: v.id, kind: "maintenance", type_key: r.type_key,
        date: when, mileage, description: name } });
    }
    // Set it explicitly too: covers "don't add to history" and an older date.
    await api(`/api/reminders/${r.id}`, { method: "PATCH", body: { last_done_date: when,
      last_done_mileage: mileage ?? r.last_done_mileage ?? null } });
    toast(addHist.checked ? "Marked as done and added to the history" : "Marked as done");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

async function editReminder(v, r, catalog, shopCtx = null) {
  const types = catalog.types.filter((t) => t.applies_to === "both" || t.applies_to === v.kind);
  const type = field("What", { tag: "select", value: r?.type_key || "oil_change", options: types.map((t) => ({ value: t.key, label: t.label })) });
  const title = field("Name (optional)", { value: r?.title || "", maxlength: 128 });
  const im = field(`Every … ${v.mileage_unit}`, { inputmode: "numeric", value: r?.interval_miles ?? "" }, "Leave blank for date-only");
  const mo = field("Every … months", { inputmode: "numeric", value: r?.interval_months ?? "" }, "Leave blank for mileage-only");
  const ld = field("Last done (date)", { type: "date", value: r?.last_done_date || "" });
  const lm = field(`Last done (${v.mileage_unit})`, { inputmode: "numeric", value: r?.last_done_mileage ?? "" });
  // Optional "Find supplies" (doesn't save or change anything).
  const shop = r && shopCtx ? reminderShopButton(r, shopCtx, catalog, "Find supplies") : null;
  const choice = await modal({ title: r ? "Edit schedule" : "New reminder", body: h("div", { class: "form" }, type, title, h("div", { class: "form-grid" }, im, mo, ld, lm),
    shop ? h("div", {}, shop) : null),
    actions: [r ? { label: "Delete", danger: true, value: "delete" } : null, { label: "Cancel", value: null }, { label: "Save", primary: true, value: "save" }].filter(Boolean) });
  try {
    if (choice === "delete") await api(`/api/reminders/${r.id}`, { method: "DELETE" });
    else if (choice === "save") {
      const bodyData = { type_key: type.input.value, title: title.input.value, interval_miles: parseIntOrNull(im.input.value),
        interval_months: parseIntOrNull(mo.input.value), last_done_date: ld.input.value || null, last_done_mileage: parseIntOrNull(lm.input.value) };
      if (r) await api(`/api/reminders/${r.id}`, { method: "PATCH", body: bodyData });
      else await api("/api/reminders", { method: "POST", body: { vehicle_id: v.id, ...bodyData } });
    } else return;
    toast("Reminder saved");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

// ---------------------------------------------------------------------------
// Parts tab: saved OEM part numbers + official parts site link
// ---------------------------------------------------------------------------
async function partsTab(body, v, { catalog, label }) {
  const [parts, links] = await Promise.all([api(`/api/parts?vehicle_id=${v.id}`), api(`/api/vehicles/${v.id}/links`)]);
  const openParts = links.parts
    ? h("a", { class: "btn", href: links.parts, target: "_blank", rel: "noopener noreferrer" }, icon("u-external"), `${v.make || "Manufacturer"} parts site`)
    : h("span", { class: "muted small" }, "No official parts site known for this make — add one under Edit.");
  mount(body, h("div", { class: "stack" },
    h("div", { class: "row between" }, h("p", { class: "muted" }, "Save the exact part numbers that fit this vehicle."),
      h("div", { class: "btn-row" }, openParts, h("button", { class: "btn primary", type: "button", onclick: () => editPart(v, null, catalog) }, icon("u-plus"), "Add part"))),
    parts.length ? h("div", { class: "list" }, parts.map((p) => h("div", { class: "item" },
      h("span", { class: "type-badge neutral" }, icon("t-" + p.type_key, "lg")),
      h("div", { class: "main" }, h("span", { class: "title" }, p.name || label[p.type_key]),
        h("span", { class: "meta" }, p.part_number ? h("strong", {}, p.part_number) : h("span", {}, "No part number yet"), p.brand ? h("span", {}, p.brand) : null),
        p.notes ? h("span", { class: "small muted" }, p.notes) : null),
      h("div", { class: "right" }, h("div", { class: "btn-row" },
        p.part_number ? h("button", { class: "btn small", type: "button", onclick: () => navigator.clipboard?.writeText(p.part_number).then(() => toast("Part number copied")) }, "Copy") : null,
        links.parts && p.part_number ? h("a", { class: "btn small", href: links.parts, target: "_blank", rel: "noopener noreferrer" }, icon("u-external"), "Find") : null,
        partShopButton(p, v, catalog),
        h("button", { class: "btn small", type: "button", "aria-label": `Add ${p.name || label[p.type_key]} to the shopping list`,
          onclick: () => addPartToList(v, p, label[p.type_key] || "Part").catch(showError) }, icon("u-list"), "Add to list"),
        h("button", { class: "btn small ghost", type: "button", "aria-label": "Edit part", onclick: () => editPart(v, p, catalog) }, icon("u-edit")))))))
      : emptyState("u-part", "No parts saved", "Add your oil filter, air filter, spark plugs, brake pads…")));
}

async function editPart(v, p, catalog) {
  const types = catalog.types.filter((t) => t.applies_to === "both" || t.applies_to === v.kind);
  const type = field("Category", { tag: "select", value: p?.type_key || "oil_filter", options: types.map((t) => ({ value: t.key, label: t.label })) });
  const name = field("Name", { value: p?.name || "", maxlength: 128, placeholder: "e.g. Oil filter" });
  const num = field("OEM part number", { value: p?.part_number || "", maxlength: 64 });
  const brand = field("Brand", { value: p?.brand || "", maxlength: 64 });
  const notes = field("Notes", { tag: "textarea", value: p?.notes || "" });
  const choice = await modal({ title: p ? "Edit part" : "Add part", body: h("div", { class: "form" }, type, name, h("div", { class: "form-grid" }, num, brand), notes),
    actions: [p ? { label: "Delete", danger: true, value: "delete" } : null, { label: "Cancel", value: null }, { label: "Save", primary: true, value: "save" }].filter(Boolean) });
  try {
    const data = { type_key: type.input.value, name: name.input.value, part_number: num.input.value, brand: brand.input.value, notes: notes.input.value };
    if (choice === "delete") await api(`/api/parts/${p.id}`, { method: "DELETE" });
    else if (choice === "save") await api(p ? `/api/parts/${p.id}` : "/api/parts", { method: p ? "PATCH" : "POST", body: p ? data : { vehicle_id: v.id, ...data } });
    else return;
    toast("Part saved");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

// ---------------------------------------------------------------------------
// Documents tab: owner's manual, sticker photos, Carfax reports, receipts
// ---------------------------------------------------------------------------
async function docsTab(body, v) {
  const [atts, links] = await Promise.all([api(`/api/attachments?vehicle_id=${v.id}`), api(`/api/vehicles/${v.id}/links`)]);
  const byCat = (c) => atts.filter((a) => a.category === c && a.has_file);
  const picker = filePicker({ accept: "application/pdf", multiple: false, label: "Upload owner's manual (PDF)" });
  const uploadBtn = h("button", { class: "btn primary", type: "button", onclick: async () => {
    const [file] = picker.files();
    if (!file) { toast("Choose a PDF first", "error"); return; }
    const fd = new FormData();
    fd.append("file", file); fd.append("vehicle_id", v.id); fd.append("category", "manual"); fd.append("caption", "Owner's manual");
    uploadBtn.disabled = true;
    try { await api("/api/attachments", { method: "POST", body: fd }); toast("Manual uploaded"); window.dispatchEvent(new HashChangeEvent("hashchange")); }
    catch (e) { showError(e); } finally { uploadBtn.disabled = false; }
  } }, icon("u-upload"), "Upload");

  const fileRow = (a) => h("div", { class: "item" },
    h("span", { class: "type-badge neutral" }, icon(a.content_type === "application/pdf" ? "u-report" : "u-image", "lg")),
    h("div", { class: "main" }, h("span", { class: "title" }, a.caption || a.original_name || a.category),
      h("span", { class: "meta" }, dateText(a.created_at), a.page_count ? `${a.page_count} pages` : null, `${Math.max(1, Math.round(a.size_bytes / 1024))} KB`)),
    h("div", { class: "btn-row" }, h("a", { class: "btn small", href: `/api/attachments/${a.id}/file`, target: "_blank", rel: "noopener" }, icon("u-download"), "Open"),
      h("button", { class: "btn small ghost", type: "button", "aria-label": "Delete file", onclick: async () => {
        if (await confirmBox("Delete file?", "The file is erased from the server.")) { await api(`/api/attachments/${a.id}`, { method: "DELETE" }); window.dispatchEvent(new HashChangeEvent("hashchange")); }
      } }, icon("u-trash"))));

  const gallery = (list) => h("div", { class: "gallery" }, list.map((a) =>
    h("a", { href: `/api/attachments/${a.id}/file`, target: "_blank", rel: "noopener" }, h("img", { src: `/api/attachments/${a.id}/thumb`, alt: a.caption || "Photo", loading: "lazy" }))));

  mount(body, h("div", { class: "stack-lg" },
    h("section", { class: "card" }, h("h2", {}, "Owner's manual"),
      h("div", { class: "stack" },
        links.owners ? h("a", { class: "btn", href: links.owners, target: "_blank", rel: "noopener noreferrer" }, icon("u-external"), `${v.make} owner portal`) : null,
        byCat("manual").map(fileRow), picker.el, h("div", {}, uploadBtn))),
    h("section", { class: "card" }, h("h2", {}, "Door sticker photos"),
      byCat("sticker").length ? gallery(byCat("sticker")) : h("p", { class: "muted" }, "None yet — use “Scan door sticker”.")),
    h("section", { class: "card" }, h("h2", {}, "Carfax reports & receipts"),
      [...byCat("carfax"), ...byCat("receipt")].length ? h("div", { class: "list" }, [...byCat("carfax"), ...byCat("receipt")].map(fileRow)) : h("p", { class: "muted" }, "None yet."))));
}

// ===========================================================================
// Add / edit vehicle form (with VIN decode)
// ===========================================================================
export async function vehicleFormView(main, { params }) {
  const [id] = params;
  const [loaded, catalog] = await Promise.all([
    id ? api(`/api/vehicles/${id}`) : getMe().then((me) => ({ kind: "car", mileage_unit: me.settings.distance_unit })), getCatalog()]);
  // Draft: if this page is reloaded (e.g. a phone browser reopening after the
  // camera), what you typed — and VIN-decoded specs — come back.  Kept only in
  // this browser tab (sessionStorage) and cleared when you save.
  const draftKey = `rw-vehicle-draft-${id || "new"}`;
  let draft = null;
  try { draft = JSON.parse(sessionStorage.getItem(draftKey) || "null"); } catch { draft = null; }
  const v = { ...loaded, ...(draft?.data || {}) };
  let kind = v.kind || "car";
  const makeNames = catalog.make_names || { car: [], motorcycle: [] };
  const makeList = h("datalist", { id: "rw-make-list" });
  const modelList = h("datalist", { id: "rw-model-list" });
  const fillMakes = () => makeList.replaceChildren(...(makeNames[kind] || []).map((m) => h("option", { value: m })));
  const kindSeg = segmented([{ value: "car", label: "Car", icon: "u-car" }, { value: "motorcycle", label: "Motorcycle", icon: "u-motorcycle" }], kind,
    (val) => { kind = val; fillMakes(); loadModels(); saveDraft(); }, "Vehicle type");
  const vin = field("VIN", { value: v.vin || "", maxlength: 17, autocapitalize: "characters", spellcheck: "false" }, "17 characters. Find it on the dashboard, the door jamb or your registration.");
  const year = field("Year", { inputmode: "numeric", value: v.year ?? "", maxlength: 4 });
  const make = field("Make", { value: v.make || "", maxlength: 64, placeholder: "Pick or type, e.g. Lexus", list: "rw-make-list", autocomplete: "off" });
  const model = field("Model", { value: v.model || "", maxlength: 64, placeholder: "Pick or type, e.g. RC 350", list: "rw-model-list", autocomplete: "off" });
  const trim = field("Trim", { value: v.trim || "", maxlength: 64 });
  const nick = field("Nickname", { value: v.nickname || "", maxlength: 64, placeholder: "Optional" });
  const mileage = field("Current mileage", { inputmode: "numeric", value: v.current_mileage ?? "" });
  const unit = field("Unit", { tag: "select", value: v.mileage_unit || "mi", options: [{ value: "mi", label: "Miles" }, { value: "km", label: "Kilometers" }] });
  const parts = field("Parts website (optional override)", { value: v.parts_url || "", type: "url", placeholder: "https://" }, "Leave blank to use the manufacturer's official site.");
  const owners = field("Owner portal (optional override)", { value: v.owners_url || "", type: "url", placeholder: "https://" });
  const notes = field("Notes", { tag: "textarea", value: v.notes || "" });
  const photo = filePicker({ accept: "image/jpeg,image/png,image/heic,image/heif", multiple: false, label: id ? "Replace vehicle photo" : "Add a vehicle photo" });
  let decodedSpecs = draft?.specs || [];

  // Model suggestions for the chosen make (and year), from NHTSA via the server.
  let modelsFor = "";
  async function loadModels() {
    const mk = make.input.value.trim();
    const yr = parseIntOrNull(year.input.value);
    const key = `${kind}|${mk.toLowerCase()}|${yr || ""}`;
    if (!mk || key === modelsFor) return;
    modelsFor = key;
    try {
      const r = await api(`/api/vehicle-models?make=${encodeURIComponent(mk)}&kind=${kind}${yr ? `&year=${yr}` : ""}`);
      if (modelsFor === key) modelList.replaceChildren(...r.models.map((m) => h("option", { value: m })));
    } catch { modelList.replaceChildren(); /* offline: you can still type the model */ }
  }
  function saveDraft() {
    try {
      sessionStorage.setItem(draftKey, JSON.stringify({ specs: decodedSpecs, data: {
        kind, vin: vin.input.value, year: year.input.value, make: make.input.value, model: model.input.value, trim: trim.input.value,
        nickname: nick.input.value, current_mileage: mileage.input.value, mileage_unit: unit.input.value,
        parts_url: parts.input.value, owners_url: owners.input.value, notes: notes.input.value } }));
    } catch { /* storage unavailable: nothing to restore later */ }
  }
  make.input.addEventListener("change", loadModels);
  year.input.addEventListener("change", loadModels);
  fillMakes();
  if (v.make) loadModels();

  const decodeBtn = h("button", { class: "btn", type: "button", onclick: async () => {
    decodeBtn.disabled = true;
    try {
      const r = await api("/api/vin/decode", { method: "POST", body: { vin: vin.input.value } });
      vin.input.value = r.vin;
      const d = r.vehicle;
      const changes = [["year", year, d.year], ["make", make, d.make], ["model", model, d.model], ["trim", trim, d.trim]].filter(([, , val]) => val);
      const boxes = changes.map(([k, f, val]) => ({ f, val, box: h("input", { type: "checkbox", checked: true }), k }));
      decodedSpecs = r.specs;
      const ok = await modal({ title: "Decoded from VIN", body: h("div", { class: "stack" },
        r.warnings.length ? h("div", { class: "notice" }, icon("u-warning"), h("p", {}, r.warnings.join(" "))) : null,
        boxes.length ? boxes.map((b) => h("label", { class: "check" }, b.box, h("span", {}, `${b.k[0].toUpperCase() + b.k.slice(1)}: `, h("strong", {}, String(b.val)))))
          : h("p", {}, "No vehicle details were returned."),
        r.specs.length ? h("p", { class: "muted small" }, `${r.specs.length} spec value(s) (engine, body, drive…) will be saved labelled “From VIN”.`) : null),
      actions: [{ label: "Cancel", value: false }, { label: "Use these", primary: true, value: true }] });
      if (ok) {
        for (const b of boxes) if (b.box.checked) b.f.input.value = b.val;
        loadModels(); saveDraft();
        if (d.kind) { kind = d.kind; kindSeg.value = d.kind; kindSeg.querySelectorAll("button").forEach((btn, i) => btn.setAttribute("aria-pressed", String(["car", "motorcycle"][i] === d.kind))); }
      } else decodedSpecs = [];
    } catch (e) { showError(e); } finally { decodeBtn.disabled = false; }
  } }, icon("u-barcode"), "Decode VIN");

  const save = h("button", { class: "btn primary", type: "submit" }, "Save vehicle");
  const form = h("form", { class: "form", onsubmit: async (e) => {
    e.preventDefault();
    save.disabled = true;
    const data = { kind, vin: vin.input.value.trim().toUpperCase(), year: parseIntOrNull(year.input.value), make: make.input.value.trim(),
      model: model.input.value.trim(), trim: trim.input.value.trim(), nickname: nick.input.value.trim(), current_mileage: parseIntOrNull(mileage.input.value),
      mileage_unit: unit.input.value, parts_url: parts.input.value.trim(), owners_url: owners.input.value.trim(), notes: notes.input.value };
    try {
      const saved = await api(id ? `/api/vehicles/${id}` : "/api/vehicles", { method: id ? "PATCH" : "POST", body: data });
      if (decodedSpecs.length) await api(`/api/vehicles/${saved.id}/apply-specs?source=vin`, { method: "POST", body: { fields: decodedSpecs.map((s) => ({ key: s.key, value: s.value })) } });
      const [file] = photo.files();
      if (file) {
        const fd = new FormData();
        fd.append("file", file); fd.append("vehicle_id", saved.id); fd.append("category", "vehicle_photo");
        await api("/api/attachments", { method: "POST", body: fd });
      }
      try { sessionStorage.removeItem(draftKey); } catch { /* ignore */ }
      toast("Vehicle saved");
      location.hash = `#/v/${saved.id}`;
    } catch (err) { showError(err); } finally { save.disabled = false; }
  } },
  h("div", { class: "field" }, h("span", {}, "Type"), kindSeg),
  h("div", { class: "row", style: null }, h("div", { style: null }, vin), decodeBtn),
  h("div", { class: "form-grid" }, year, make, model, trim, nick, mileage, unit),
  photo.el, h("div", { class: "form-grid" }, parts, owners), notes,
  h("div", { class: "btn-row" }, save,
    id ? h("button", { class: "btn danger", type: "button", onclick: async () => {
      if (await confirmBox("Delete vehicle?", "This deletes the vehicle and all of its records and files.")) {
        await api(`/api/vehicles/${id}`, { method: "DELETE" }); toast("Vehicle deleted"); location.hash = "#/";
      }
    } }, icon("u-trash"), "Delete vehicle") : null));
  form.addEventListener("input", saveDraft);
  form.append(makeList, modelList);
  const vinRow = form.children[1];
  vinRow.style.alignItems = "flex-end";
  vinRow.firstChild.style.flex = "1";
  vinRow.firstChild.style.minWidth = "240px";

  mount(main, h("a", { class: "back", href: id ? `#/v/${id}` : "#/" }, icon("u-chevron_left"), "Back"),
    h("div", { class: "page-head" }, h("h1", {}, id ? `Edit ${vehicleName(v)}` : "Add vehicle")),
    h("div", { class: "card" }, form));
}
