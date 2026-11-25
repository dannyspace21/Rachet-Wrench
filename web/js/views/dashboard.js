// dashboard.js — the "Garage" home screen: totals, what's due soon, your
// vehicles, and the latest work logged.

import { api } from "../api.js";
import { h, mount, icon, typeBadge, money, dateText, vehicleName, vehicleFull, emptyState } from "../ui.js";
import { getCatalog } from "../api.js";

export async function dashboardView(main) {
  const [vehicles, reminders, entries, catalog] = await Promise.all([
    api("/api/vehicles"), api("/api/reminders"), api("/api/entries"), getCatalog()]);
  const byId = Object.fromEntries(vehicles.map((v) => [v.id, v]));
  const label = Object.fromEntries(catalog.types.map((t) => [t.key, t.label]));

  const year = new Date().getFullYear();
  const thisYear = entries.filter((e) => e.date.startsWith(String(year)));
  const due = reminders
    .filter((r) => ["overdue", "due_soon"].includes(r.due.status) && byId[r.vehicle_id])
    .sort((a, b) => (a.due.status === "overdue" ? -1 : 1) - (b.due.status === "overdue" ? -1 : 1));

  const addBtn = h("a", { class: "btn primary", href: "#/v/new" }, icon("u-plus"), "Add vehicle");
  const head = h("div", { class: "page-head" },
    h("div", {}, h("h1", {}, "Garage"), h("p", { class: "sub muted" }, `${vehicles.length} vehicle${vehicles.length === 1 ? "" : "s"}`)),
    addBtn);

  const stats = h("div", { class: "stats" },
    stat("Spent this year", money(thisYear.reduce((s, e) => s + (e.cost_cents || 0), 0))),
    stat("Records this year", String(thisYear.length)),
    stat("Due soon", String(due.filter((r) => r.due.status === "due_soon").length)),
    stat("Overdue", String(due.filter((r) => r.due.status === "overdue").length)));

  const dueCard = h("section", { class: "card" }, h("div", { class: "section-title" }, h("h2", {}, "Coming up")),
    due.length ? h("div", { class: "list" }, due.slice(0, 6).map((r) => reminderItem(r, byId[r.vehicle_id], label)))
      : h("p", { class: "muted" }, "Nothing is due. Nice work!"));

  const cards = vehicles.length ? h("div", { class: "grid" }, vehicles.map((v) => vehicleCard(v, reminders)))
    : emptyState("u-car", "No vehicles yet", "Add your first car or motorcycle to start tracking.", h("a", { class: "btn primary", href: "#/v/new" }, "Add vehicle"));

  const recent = entries.slice(0, 6);
  const recentCard = h("section", { class: "card" }, h("div", { class: "section-title" }, h("h2", {}, "Recent work")),
    recent.length ? h("div", { class: "list" }, recent.map((e) => h("a", { class: "item", href: `#/e/${e.id}` },
      typeBadge(e.type_key, e.kind),
      h("div", { class: "main" }, h("span", { class: "title" }, e.custom_type || label[e.type_key] || "Entry"),
        h("span", { class: "meta" }, h("span", {}, byId[e.vehicle_id] ? vehicleName(byId[e.vehicle_id]) : ""), h("span", {}, dateText(e.date)))),
      h("div", { class: "right" }, h("span", { class: "pill " + (e.kind === "repair" ? "repair" : "maint") }, e.kind === "repair" ? "Repair" : "Maintenance"),
        h("span", { class: "small muted" }, money(e.cost_cents))))))
      : h("p", { class: "muted" }, "No records yet."));

  mount(main, head, stats, h("div", { class: "stack-lg" },
    h("section", {}, h("div", { class: "section-title" }, h("h2", {}, "Your vehicles")), cards),
    h("div", { class: "grid-2" }, dueCard, recentCard)));
}

function stat(labelText, value) {
  return h("div", { class: "stat" }, h("div", { class: "label" }, labelText), h("div", { class: "value" }, value));
}

/** One reminder row with its status pill. */
export function reminderItem(r, vehicle, label) {
  const parts = [];
  if (r.due.miles_left !== null) parts.push(r.due.miles_left <= 0 ? `${Math.abs(r.due.miles_left).toLocaleString()} mi overdue` : `${r.due.miles_left.toLocaleString()} mi left`);
  if (r.due.days_left !== null) {
    const d = r.due.days_left;
    parts.push(d === 0 ? "due today" : d < 0 ? `${-d} day${d === -1 ? "" : "s"} overdue` : `${d} day${d === 1 ? "" : "s"} left`);
  }
  const statusText = { overdue: "Overdue", due_soon: "Due soon", ok: "OK", unknown: "Set last done", disabled: "Off" }[r.due.status];
  return h("a", { class: "item", href: `#/v/${r.vehicle_id}/reminders` },
    h("span", { class: "type-badge neutral" }, icon("t-" + r.type_key, "lg")),
    h("div", { class: "main" }, h("span", { class: "title" }, r.title || label[r.type_key] || "Reminder"),
      h("span", { class: "meta" }, vehicle ? h("span", {}, vehicleName(vehicle)) : null, h("span", {}, parts.join(" · ") || "No due point yet"))),
    h("span", { class: "pill " + r.due.status }, statusText));
}

function vehicleCard(v, reminders) {
  const mine = reminders.filter((r) => r.vehicle_id === v.id && ["overdue", "due_soon"].includes(r.due.status));
  const photo = v.photo_attachment_id
    ? h("img", { src: `/api/attachments/${v.photo_attachment_id}/thumb`, alt: "", loading: "lazy" })
    : icon(v.kind === "motorcycle" ? "u-motorcycle" : "u-car", "xl");
  return h("a", { class: "card vcard card-link", href: `#/v/${v.id}` },
    h("div", { class: "photo" }, photo),
    h("div", { class: "body" },
      h("span", { class: "name" }, vehicleName(v)),
      h("span", { class: "muted small" }, vehicleFull(v) || (v.kind === "motorcycle" ? "Motorcycle" : "Car")),
      h("div", { class: "row between" },
        h("span", { class: "kpi" }, h("b", {}, v.current_mileage !== null ? Number(v.current_mileage).toLocaleString() : "—"), h("span", { class: "muted" }, v.mileage_unit)),
        mine.length ? h("span", { class: "pill " + (mine.some((r) => r.due.status === "overdue") ? "overdue" : "due_soon") }, `${mine.length} due`)
          : h("span", { class: "pill ok" }, "All good"))));
}
