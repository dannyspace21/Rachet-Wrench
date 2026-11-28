// shopping-list.js — the Shopping List (#/shopping).
//
// One list of everything to buy before doing the work: items added from a
// reminder, from a saved part, or typed in.  Each line can have a quantity,
// an OEM part number, a spec (e.g. "0W-20 full synthetic"), notes, a vehicle
// and the reminder it's for.  Lines are records on the server, so they sync
// to the phone like everything else.
//
// What it deliberately does NOT do:
//   * no cart or payment — "Search" just opens the retailer's own website;
//   * ticking something off as bought never marks maintenance done;
//   * nothing here is an expense — costs are recorded as history entries.

import { api, getCatalog } from "../api.js";
import { h, mount, icon, field, modal, confirmBox, toast, showError, emptyState, vehicleName, parseIntOrNull } from "../ui.js";
import { listItemQuery, listItemsFor } from "../shopping.js";
import { openShopping, shoppingOn } from "./shopping-ui.js";

const NO_VEHICLE = "";

export async function shoppingListView(main, { query }) {
  const [items, vehicles, reminders, catalog] = await Promise.all([
    api("/api/shopping"), api("/api/vehicles"), api("/api/reminders"), getCatalog()]);
  const byId = Object.fromEntries(vehicles.map((v) => [v.id, v]));
  const remById = Object.fromEntries(reminders.map((r) => [r.id, r]));
  const label = Object.fromEntries(catalog.types.map((t) => [t.key, t.label]));
  let filter = query.get("vehicle") || "all";
  const refresh = () => window.dispatchEvent(new HashChangeEvent("hashchange"));

  const add = h("button", { class: "btn primary", type: "button", onclick: async () => {
    if (await editItem(null, vehicles, reminders, label, filter !== "all" && filter !== "general" ? filter : NO_VEHICLE)) refresh();
  } }, icon("u-plus"), "Add item");
  const bought = items.filter((i) => i.purchased);
  const clear = bought.length ? h("button", { class: "btn", type: "button", onclick: async () => {
    if (!await confirmBox("Remove bought items?", `${bought.length} item${bought.length === 1 ? "" : "s"} you ticked off will be removed from the list.`, "Remove")) return;
    try { for (const i of bought) await api(`/api/shopping/${i.id}`, { method: "DELETE" }); toast("Removed"); refresh(); } catch (e) { showError(e); }
  } }, icon("u-check"), "Remove bought items") : null;

  // Filter: all / one vehicle / general items.
  const options = [{ value: "all", label: "All vehicles" }, ...vehicles.map((v) => ({ value: v.id, label: vehicleName(v) })),
    { value: "general", label: "Not for a vehicle" }];
  const pick = field("Show", { tag: "select", value: filter, options });
  const list = h("div", { class: "stack" });
  const draw = () => {
    const shown = items.filter((i) => filter === "all" || (filter === "general" ? !i.vehicle_id : i.vehicle_id === filter));
    if (!items.length) {
      mount(list, emptyState("u-cart", "Your shopping list is empty",
        "Add supplies from a reminder (“Add to shopping list”), from a saved part, or type one in."));
      return;
    }
    if (!shown.length) { mount(list, h("p", { class: "muted" }, "Nothing on the list for this vehicle.")); return; }
    // Grouped by vehicle, items still to buy first (the server sorts that way).
    const groups = new Map();
    for (const i of shown) {
      const k = i.vehicle_id && byId[i.vehicle_id] ? i.vehicle_id : NO_VEHICLE;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(i);
    }
    mount(list, [...groups].map(([vid, rows]) => h("section", { class: "stack" },
      h("h2", { class: "section-title" }, vid ? vehicleName(byId[vid]) : "Not for a vehicle"),
      h("div", { class: "list" }, rows.map((i) => itemRow(i, byId[i.vehicle_id], remById[i.reminder_id], label, catalog,
        async () => { if (await editItem(i, vehicles, reminders, label)) refresh(); }, refresh))))));
  };
  pick.input.addEventListener("change", () => { filter = pick.input.value; draw(); });
  draw();

  mount(main,
    h("div", { class: "page-head" }, h("div", {}, h("h1", {}, "Shopping Cart"),
      h("p", { class: "muted" }, "Everything you need before doing the work.")), h("div", { class: "btn-row" }, clear, add)),
    vehicles.length ? h("div", { class: "shop-filter" }, pick) : null,
    list,
    h("p", { class: "muted small" }, "Ticking an item off doesn't mark the maintenance as done or add an expense — record the job in the vehicle's history when you do it."));
}

/** One line of the list. */
function itemRow(i, v, rem, label, catalog, onEdit, refresh) {
  const box = h("input", { type: "checkbox", checked: !!i.purchased, "aria-label": `Bought ${i.description}` });
  box.addEventListener("change", async () => {
    try { await api(`/api/shopping/${i.id}`, { method: "PATCH", body: { purchased: box.checked } }); refresh(); }
    catch (e) { box.checked = !box.checked; showError(e); }
  });
  const search = shoppingOn(catalog) ? h("button", { class: "btn small", type: "button", "aria-label": `Find Products for ${i.description}`,
    onclick: () => {
      const { query, exact } = listItemQuery(i, v);
      openShopping(`Find Products — ${i.description}`, [{ label: i.description, query, exact, hint: null }], catalog);
    } }, icon("u-search"), "Find Products") : null;
  const remove = h("button", { class: "btn small ghost", type: "button", "aria-label": `Remove ${i.description}`,
    onclick: async () => {
      try { await api(`/api/shopping/${i.id}`, { method: "DELETE" }); toast("Removed from the list"); refresh(); } catch (e) { showError(e); }
    } }, icon("u-trash"));
  const edit = h("button", { class: "btn small ghost", type: "button", "aria-label": `Edit ${i.description}`, onclick: onEdit }, icon("u-edit"));
  return h("div", { class: "item shop-line" + (i.purchased ? " bought" : "") },
    h("label", { class: "check" }, box),
    h("div", { class: "main" },
      h("span", { class: "title" }, i.quantity > 1 ? `${i.quantity} × ` : "", i.description),
      h("span", { class: "meta" },
        i.part_number ? h("strong", {}, `OEM ${i.part_number}`) : null,
        i.spec ? h("span", {}, i.spec) : null,
        rem ? h("span", {}, `For: ${rem.title || label[rem.type_key] || "reminder"}`) : null),
      i.notes ? h("span", { class: "small muted" }, i.notes) : null),
    h("div", { class: "right" }, h("div", { class: "btn-row" }, search, edit, remove)));
}

/** Add or edit one line.  Returns true when something was saved. */
export async function editItem(item, vehicles, reminders, label, vehicleId = NO_VEHICLE) {
  const veh = field("Vehicle", { tag: "select", value: item ? (item.vehicle_id || NO_VEHICLE) : vehicleId,
    options: [{ value: NO_VEHICLE, label: "Not for a vehicle" }, ...vehicles.map((v) => ({ value: v.id, label: vehicleName(v) }))] });
  const desc = field("Item", { value: item?.description || "", maxlength: 200, required: true, placeholder: "e.g. Oil filter" });
  const pn = field("OEM part number (optional)", { value: item?.part_number || "", maxlength: 64 });
  const spec = field("Specification (optional)", { value: item?.spec || "", maxlength: 200, placeholder: "e.g. 0W-20 full synthetic" });
  const qty = field("Quantity", { inputmode: "numeric", value: item?.quantity ?? 1 });
  const rem = field("For reminder (optional)", { tag: "select", value: item?.reminder_id || "", options: [] });
  const notes = field("Notes (optional)", { tag: "textarea", value: item?.notes || "", maxlength: 5000 });
  const fillReminders = () => {
    const current = rem.input.value;
    rem.input.replaceChildren(h("option", { value: "" }, "None"),
      ...reminders.filter((r) => r.vehicle_id === veh.input.value)
        .map((r) => h("option", { value: r.id }, r.title || label[r.type_key] || "Reminder")));
    rem.input.value = [...rem.input.options].some((o) => o.value === current) ? current : "";
  };
  veh.input.addEventListener("change", fillReminders);
  fillReminders();
  if (item?.reminder_id) rem.input.value = item.reminder_id;
  const choice = await modal({ title: item ? "Edit item" : "Add to shopping list", body: h("div", { class: "form" },
    desc, h("div", { class: "form-grid" }, pn, spec, qty, veh), rem, notes),
  actions: [{ label: "Cancel", value: null }, { label: "Save", primary: true, value: "save" }] });
  if (choice !== "save") return false;
  const description = desc.input.value.trim();
  if (!description) { toast("Describe the item to add it.", "error"); return false; }
  const quantity = Math.min(999, Math.max(1, parseIntOrNull(qty.input.value) || 1));
  const body = { description, part_number: pn.input.value.trim(), spec: spec.input.value.trim(), quantity,
    notes: notes.input.value, vehicle_id: veh.input.value || null, reminder_id: rem.input.value || null };
  try {
    if (item) await api(`/api/shopping/${item.id}`, { method: "PATCH", body });
    else await api("/api/shopping", { method: "POST", body });
    toast(item ? "Saved" : "Added to the shopping list");
    return true;
  } catch (e) { showError(e); return false; }
}

/** Put a reminder's supplies on the list (skipping lines already there). */
export async function addReminderToList(v, r, name, ctx, catalog) {
  const existing = (await api("/api/shopping")).filter((i) => !i.purchased && i.reminder_id === r.id);
  const lines = listItemsFor(r.type_key, name, ctx, catalog)
    .filter((l) => !existing.some((i) => i.description.toLowerCase() === l.description.toLowerCase()));
  if (!lines.length) { toast("Already on your shopping list"); return 0; }
  for (const l of lines) {
    await api("/api/shopping", { method: "POST", body: { ...l, vehicle_id: v.id, reminder_id: r.id, quantity: 1 } });
  }
  toast(`Added ${lines.length} item${lines.length === 1 ? "" : "s"} to the shopping list`);
  return lines.length;
}

/** Put a saved part on the list (once). */
export async function addPartToList(v, p, typeLabel) {
  const existing = (await api("/api/shopping")).find((i) => !i.purchased && i.part_id === p.id);
  if (existing) { toast("Already on your shopping list"); return; }
  await api("/api/shopping", { method: "POST", body: { vehicle_id: v.id, part_id: p.id, type_key: p.type_key,
    description: p.name || typeLabel, part_number: p.part_number || "", quantity: 1 } });
  toast("Added to the shopping list");
}
