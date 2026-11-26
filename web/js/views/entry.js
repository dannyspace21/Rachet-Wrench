// entry.js — adding/editing a maintenance or repair entry (with photos and
// receipts), and viewing one entry.

import { api, getCatalog } from "../api.js";
import {
  h, mount, icon, typeBadge, money, miles, dateText, today, vehicleName, field, segmented, confirmBox,
  toast, showError, parseMoney, parseIntOrNull, filePicker,
} from "../ui.js";

/** A searchable grid of type icons; returns {el, value}. */
function typePicker(types, selected, onPick) {
  const state = { value: selected };
  const search = h("input", { class: "input type-search", type: "search", placeholder: "Search types (e.g. brake, chain, oil)…", "aria-label": "Search types" });
  const grid = h("div", { class: "type-grid", role: "group", "aria-label": "Type" });
  const draw = () => {
    const q = search.value.trim().toLowerCase();
    const shown = types.filter((t) => !q || t.label.toLowerCase().includes(q) || t.category.toLowerCase().includes(q));
    grid.replaceChildren(...shown.map((t) => h("button", { class: "type-opt", type: "button", "aria-pressed": String(t.key === state.value),
      onclick: () => { state.value = t.key; draw(); onPick?.(t.key); } }, icon("t-" + t.key, "lg"), t.label)));
  };
  search.addEventListener("input", draw);
  draw();
  return { el: h("div", {}, search, grid), get value() { return state.value; } };
}

/** Upload chosen files one by one, attached to an entry. */
async function uploadFiles(files, vehicleId, entryId) {
  let done = 0;
  for (const file of files) {
    const fd = new FormData();
    fd.append("file", file);
    fd.append("vehicle_id", vehicleId);
    fd.append("entry_id", entryId);
    fd.append("category", file.type === "application/pdf" ? "receipt" : "photo");
    fd.append("caption", file.name.replace(/\.[^.]+$/, "").slice(0, 120));
    try { await api("/api/attachments", { method: "POST", body: fd }); done++; } catch (e) { showError(e); }
  }
  return done;
}

export async function entryFormView(main, { params, entryEdit }) {
  let entry = null;
  let vehicleId = params[0];
  if (entryEdit) { entry = await api(`/api/entries/${params[0]}`); vehicleId = entry.vehicle_id; }
  const [v, catalog] = await Promise.all([api(`/api/vehicles/${vehicleId}`), getCatalog()]);
  const types = catalog.types.filter((t) => t.applies_to === "both" || t.applies_to === v.kind);

  let kind = entry?.kind || "maintenance";
  const kindSeg = segmented([{ value: "maintenance", label: "Maintenance", icon: "u-maintenance" }, { value: "repair", label: "Repair", icon: "u-repair" }], kind, (val) => { kind = val; }, "Entry kind");
  const customName = field("Custom type name", { value: entry?.custom_type || "", maxlength: 64, placeholder: "e.g. Ceramic coating" });
  customName.hidden = (entry?.type_key || "oil_change") !== "custom";
  const picker = typePicker(types, entry?.type_key || "oil_change", (key) => { customName.hidden = key !== "custom"; });
  const date = field("Date", { type: "date", value: entry?.date || today(), required: true });
  const mileage = field(`Mileage (${v.mileage_unit})`, { inputmode: "numeric", value: entry?.mileage ?? v.current_mileage ?? "" });
  const desc = field("Description", { value: entry?.description || "", maxlength: 500, placeholder: "What was done?" });
  const parts = field("Parts used", { tag: "textarea", value: entry?.parts_used || "", placeholder: "One per line, with part numbers if you have them" });
  const cost = field("Total cost", { inputmode: "decimal", value: entry?.cost_cents != null ? (entry.cost_cents / 100).toFixed(2) : "", placeholder: "0.00" });
  let performed = entry?.performed_by || "diy";
  const shop = field("Shop name", { value: entry?.shop_name || "", maxlength: 128 });
  shop.hidden = performed !== "shop";
  const bySeg = segmented([{ value: "diy", label: "I did it (DIY)" }, { value: "shop", label: "A shop did it" }], performed, (val) => { performed = val; shop.hidden = val !== "shop"; }, "Who did the work");
  const notes = field("Notes", { tag: "textarea", value: entry?.notes || "" });
  const files = filePicker({ label: entry ? "Add more photos or receipts" : "Add photos or receipts" });

  const save = h("button", { class: "btn primary", type: "submit" }, icon("u-check"), "Save entry");
  const form = h("form", { class: "form", onsubmit: async (e) => {
    e.preventDefault();
    save.disabled = true;
    const data = { kind, type_key: picker.value, custom_type: picker.value === "custom" ? customName.input.value.trim() : "", date: date.input.value,
      mileage: parseIntOrNull(mileage.input.value), description: desc.input.value.trim(), parts_used: parts.input.value, cost_cents: parseMoney(cost.input.value),
      performed_by: performed, shop_name: performed === "shop" ? shop.input.value.trim() : "", notes: notes.input.value };
    try {
      const saved = await api(entry ? `/api/entries/${entry.id}` : "/api/entries", { method: entry ? "PATCH" : "POST", body: entry ? data : { vehicle_id: v.id, ...data } });
      const chosen = files.files();
      if (chosen.length) {
        save.textContent = "Uploading photos…";
        const n = await uploadFiles(chosen, v.id, saved.id);
        toast(`Saved with ${n} file${n === 1 ? "" : "s"}`);
      } else toast("Entry saved");
      location.hash = `#/e/${saved.id}`;
    } catch (err) { showError(err); save.disabled = false; save.replaceChildren(icon("u-check"), "Save entry"); }
  } },
  h("div", { class: "field" }, h("span", {}, "Kind"), kindSeg),
  h("div", { class: "field" }, h("span", {}, "Type"), picker.el), customName,
  h("div", { class: "form-grid" }, date, mileage, cost), desc,
  h("div", { class: "field" }, h("span", {}, "Who did the work"), bySeg), shop,
  parts, notes,
  h("div", { class: "field" }, h("span", {}, "Photos & receipts"), files.el),
  h("div", { class: "btn-row" }, save, h("a", { class: "btn", href: entry ? `#/e/${entry.id}` : `#/v/${v.id}` }, "Cancel")));

  mount(main, h("a", { class: "back", href: `#/v/${v.id}` }, icon("u-chevron_left"), vehicleName(v)),
    h("div", { class: "page-head" }, h("div", {}, h("h1", {}, entry ? "Edit entry" : "Add entry"), h("p", { class: "sub muted" }, vehicleName(v)))),
    h("div", { class: "card" }, form));
}

export async function entryDetailView(main, { params }) {
  const e = await api(`/api/entries/${params[0]}`);
  const [v, catalog, atts] = await Promise.all([api(`/api/vehicles/${e.vehicle_id}`), getCatalog(), api(`/api/attachments?vehicle_id=${e.vehicle_id}`)]);
  const label = Object.fromEntries(catalog.types.map((t) => [t.key, t.label]));
  const mine = atts.filter((a) => a.entry_id === e.id && a.has_file);
  const photos = mine.filter((a) => a.content_type.startsWith("image/"));
  const docs = mine.filter((a) => a.content_type === "application/pdf");

  const facts = [["Date", dateText(e.date)], ["Mileage", e.mileage !== null ? miles(e.mileage, v.mileage_unit) : "—"], ["Cost", money(e.cost_cents)],
    ["Done by", e.performed_by === "shop" ? (e.shop_name || "Shop") : "DIY"], ["Source", e.source === "carfax" ? "Imported from Carfax" : "Entered by me"]];

  mount(main, h("a", { class: "back", href: `#/v/${v.id}/history` }, icon("u-chevron_left"), vehicleName(v)),
    h("div", { class: "page-head" },
      h("div", { class: "row" }, typeBadge(e.type_key, e.kind),
        h("div", {}, h("h1", {}, e.custom_type || label[e.type_key] || "Entry"),
          h("p", { class: "sub" }, h("span", { class: "pill " + (e.kind === "repair" ? "repair" : "maint") }, e.kind === "repair" ? "Repair" : "Maintenance")))),
      h("div", { class: "btn-row" }, h("a", { class: "btn", href: `#/e/${e.id}/edit` }, icon("u-edit"), "Edit"),
        h("button", { class: "btn danger", type: "button", onclick: async () => {
          if (await confirmBox("Delete entry?", "The entry and its photos will be deleted.")) {
            try { await api(`/api/entries/${e.id}`, { method: "DELETE" }); toast("Entry deleted"); location.hash = `#/v/${v.id}/history`; } catch (err) { showError(err); }
          }
        } }, icon("u-trash"), "Delete"))),
    h("div", { class: "stack-lg" },
      h("div", { class: "stats" }, facts.map(([k, val]) => h("div", { class: "stat" }, h("div", { class: "label" }, k), h("div", { class: "value small" }, val)))),
      e.description || e.parts_used || e.notes ? h("section", { class: "card stack" },
        e.description ? h("div", {}, h("h3", {}, "Description"), h("p", {}, e.description)) : null,
        e.parts_used ? h("div", {}, h("h3", {}, "Parts used"), h("p", { class: "prewrap" }, e.parts_used)) : null,
        e.notes ? h("div", {}, h("h3", {}, "Notes"), h("p", { class: "prewrap" }, e.notes)) : null) : null,
      h("section", { class: "card" }, h("h2", {}, `Photos (${photos.length})`),
        photos.length ? h("div", { class: "gallery" }, photos.map((a) => h("a", { href: `/api/attachments/${a.id}/file`, target: "_blank", rel: "noopener" },
          h("img", { src: `/api/attachments/${a.id}/thumb`, alt: a.caption || "Photo", loading: "lazy" })))) : h("p", { class: "muted" }, "No photos. Use Edit to add some.")),
      docs.length ? h("section", { class: "card" }, h("h2", {}, "Receipts"), h("div", { class: "list" }, docs.map((a) =>
        h("a", { class: "item", href: `/api/attachments/${a.id}/file` }, h("span", { class: "type-badge neutral" }, icon("u-report", "lg")),
          h("div", { class: "main" }, h("span", { class: "title" }, a.caption || a.original_name || "Receipt"), h("span", { class: "meta" }, `${a.page_count || 1} page(s)`)))))) : null));
}
