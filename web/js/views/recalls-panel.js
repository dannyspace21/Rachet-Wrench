// recalls-panel.js — History tab → "Recalls": every safety recall NHTSA
// lists for this vehicle's year/make/model, with what's known about each one
// (open / done / doesn't apply), plus "Mark as done" (adds the job to the
// history) and a link to check this car's VIN on NHTSA's own website.
//
// Looking recalls up sends only the year, make and model to NHTSA (through
// this server).  The VIN is only used if the owner presses "Check my VIN on
// NHTSA", which opens nhtsa.gov in a new tab.

import { api } from "../api.js";
import { h, mount, icon, field, modal, toast, showError, emptyState, dateText, today, parseIntOrNull } from "../ui.js";
import { sortRecalls } from "../recalls.js";

const STATUS = { open: ["Open", "overdue"], done: ["Done", "ok"], not_applicable: ["Doesn't apply", "disabled"] };
const refreshed = new Set(); // vehicles looked up during this visit
const reload = () => window.dispatchEvent(new HashChangeEvent("hashchange"));
const titleCase = (s) => String(s || "").toLowerCase().replace(/(^|[\s:/,-])([a-z])/g, (m, a, b) => a + b.toUpperCase());

export function recallsPanel(container, v, entries, recalls) {
  const ready = v.year && v.make && v.model;
  const status = h("p", { class: "muted small" });
  let showEmpty = null;
  const refresh = async (quiet = false) => {
    if (!ready) return;
    status.textContent = "Checking NHTSA for recalls…";
    try {
      const r = await api(`/api/vehicles/${v.id}/recalls/refresh`, { method: "POST" });
      refreshed.add(v.id);
      if (r.added) { toast(`${r.added} new recall${r.added === 1 ? "" : "s"} found`); reload(); return; }
      status.textContent = `Checked NHTSA just now: ${r.total} recall${r.total === 1 ? "" : "s"} for the ${v.year} ${v.make} ${v.model}.`;
      showEmpty?.("checked");
    } catch (e) {
      status.textContent = "";
      showEmpty?.("failed");
      if (!quiet) showError(e);
    }
  };

  const vinLink = v.vin
    ? h("a", { class: "btn", href: `https://www.nhtsa.gov/recalls?vin=${encodeURIComponent(v.vin)}`, target: "_blank", rel: "noopener noreferrer" },
      icon("u-external"), "Check my VIN on NHTSA")
    : null;
  const head = h("div", { class: "stack" },
    h("div", { class: "row between" },
      h("p", { class: "muted" }, ready ? `Safety recalls for the ${v.year} ${v.make} ${v.model}, from NHTSA.` : "Add the year, make and model (Edit) to look up recalls."),
      h("div", { class: "btn-row" }, vinLink, ready ? h("button", { class: "btn", type: "button", onclick: () => refresh(false) }, icon("u-sync"), "Check for new recalls") : null)),
    h("p", { class: "muted small" }, "NHTSA lists recalls for every car of this model. Whether one applies to your exact car — and whether it was already fixed — is known by the manufacturer: use “Check my VIN on NHTSA” or ask a dealer, then mark it below."),
    status);

  const rows = sortRecalls(recalls, entries, today());
  const empty = h("div", {});
  showEmpty = (state) => mount(empty, !ready ? emptyState("t-recall", "Recalls need year, make and model", "Edit the vehicle to add them.")
    : state === "checked" ? emptyState("t-recall", "No recalls", `NHTSA has no recalls on record for the ${v.year} ${v.make} ${v.model}.`)
      : state === "failed" ? emptyState("t-recall", "Couldn't check recalls", "NHTSA didn't answer. Try “Check for new recalls” again in a moment.")
        : emptyState("t-recall", "Checking for recalls…", "Asking NHTSA about this model."));
  const list = rows.length ? h("div", { class: "stack" }, rows.map(({ r, s }) => recallCard(v, r, s))) : empty;
  if (!rows.length) showEmpty(refreshed.has(v.id) ? "checked" : "checking");
  mount(container, head, list);

  // Look up once per visit (the server and NHTSA cache it).
  if (ready && !refreshed.has(v.id)) refresh(true);
}

function recallCard(v, r, s) {
  const [label, cls] = STATUS[s.status];
  const summary = h("p", { class: "recall-text" }, r.summary || "");
  const more = (r.summary || "").length > 220
    ? h("button", { class: "btn small ghost", type: "button", onclick: (e) => { summary.classList.toggle("open"); e.target.textContent = summary.classList.contains("open") ? "Less" : "More"; } }, "More")
    : null;
  const actions = [];
  if (s.status === "open") {
    actions.push(h("button", { class: "btn small primary", type: "button", onclick: () => markDone(v, r) }, icon("u-check"), "Mark as done"));
    actions.push(h("button", { class: "btn small", type: "button", onclick: () => setStatus(r, "not_applicable") }, "Doesn't apply to my car"));
  } else if (r.status !== "open") {
    actions.push(h("button", { class: "btn small ghost", type: "button", onclick: () => setStatus(r, "open") }, "Mark as open again"));
  }
  return h("section", { class: "card recall" + (s.status === "open" ? " recall-open" : "") },
    h("div", { class: "row between" },
      h("div", {}, h("strong", {}, titleCase(r.component) || "Recall"),
        h("p", { class: "muted small" }, `NHTSA campaign ${r.campaign}` + (r.report_date ? ` · ${dateText(r.report_date)}` : ""))),
      h("div", { class: "row" }, s.isNew ? h("span", { class: "pill due_soon" }, "New") : null, h("span", { class: `pill ${cls}` }, label))),
    r.park_it && s.status === "open" ? h("p", { class: "recall-warning" }, icon("u-warning"), " NHTSA advises not to drive this vehicle, or to park it outside, until it's fixed.") : null,
    summary, more ? h("div", {}, more) : null,
    r.consequence ? h("p", { class: "small" }, h("strong", {}, "Risk: "), r.consequence) : null,
    r.remedy ? h("p", { class: "small" }, h("strong", {}, "Fix: "), r.remedy) : null,
    s.status === "done" ? h("p", { class: "small" }, s.entry
      ? h("a", { href: `#/e/${s.entry.id}` }, `Done ${dateText(s.entry.date)} — see the history record`)
      : `Marked done${r.done_date ? ` ${dateText(r.done_date)}` : ""}.`) : null,
    actions.length ? h("div", { class: "btn-row" }, actions) : null);
}

async function setStatus(r, status) {
  try {
    await api(`/api/recalls/${r.id}`, { method: "PATCH", body: { status, ...(status === "open" ? { done_date: null, entry_id: null } : {}) } });
    reload();
  } catch (e) { showError(e); }
}

/** Record the recall repair in the history and mark the recall done. */
async function markDone(v, r) {
  const date = field("Done on", { type: "date", value: today(), max: today() });
  const miles = field(`Mileage (${v.mileage_unit})`, { inputmode: "numeric", value: v.current_mileage ?? "" });
  const shop = field("Dealer / shop (optional)", { maxlength: 128 });
  const ok = await modal({ title: "Mark recall as done", body: h("div", { class: "form" },
    h("p", { class: "muted small" }, `Adds “Recall ${r.campaign}” to the history (no cost — recalls are free).`),
    h("div", { class: "form-grid" }, date, miles), shop),
  actions: [{ label: "Cancel", value: false }, { label: "Mark as done", primary: true, value: true }] });
  if (!ok) return;
  try {
    const when = date.input.value || today();
    const e = await api("/api/entries", { method: "POST", body: { vehicle_id: v.id, type_key: "recall", kind: "repair", date: when,
      mileage: parseIntOrNull(miles.input.value), description: `Recall ${r.campaign}: ${titleCase(r.component)}`.slice(0, 500),
      performed_by: "shop", shop_name: shop.input.value.trim(), notes: r.remedy ? `Fix: ${r.remedy}`.slice(0, 5000) : "" } });
    await api(`/api/recalls/${r.id}`, { method: "PATCH", body: { status: "done", done_date: when, entry_id: e.id } });
    toast("Recall marked as done and added to the history");
    reload();
  } catch (err) { showError(err); }
}
