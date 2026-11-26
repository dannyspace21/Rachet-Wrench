// scan.js — two "read a document, then review" flows:
//
//  * Door-jamb sticker: upload photos -> text recognition -> review every
//    field (keep / edit / reject) -> save as "From door sticker".
//  * Carfax: upload the PDF -> proposed entries -> review every row ->
//    save the approved ones.
//
// Nothing is saved until you press the final Save button.

import { api, getCatalog, getMe } from "../api.js";
import { h, mount, icon, vehicleName, filePicker, toast, showError, typeBadge, dateText, parseIntOrNull } from "../ui.js";

// ===========================================================================
// Door sticker
// ===========================================================================
export async function stickerView(main, { params }) {
  const [id] = params;
  const [v, me, catalog] = await Promise.all([api(`/api/vehicles/${id}`), getMe(), getCatalog()]);
  const labels = Object.fromEntries(catalog.specs.map((s) => [s.key, s.label]));
  labels.vin = "VIN";
  const aiAvailable = me.server.ai_vision_available;

  const picker = filePicker({ accept: "image/jpeg,image/png,image/heic,image/heif", label: "Photos of the door-jamb stickers", capture: true });
  const aiBox = h("input", { type: "checkbox" });
  const aiWarning = h("div", { class: "notice hidden" }, icon("u-warning"),
    h("p", {}, h("strong", {}, "Third-party service: "), "With AI vision on, your sticker photos are sent to Anthropic's Claude API over the internet for reading. Leave it off to keep photos on your own server."));
  aiBox.addEventListener("change", () => aiWarning.classList.toggle("hidden", !aiBox.checked));
  const aiOption = aiAvailable
    ? h("div", { class: "stack" }, h("label", { class: "check" }, aiBox, h("span", {}, h("strong", {}, "Use AI vision"), " — better on worn or blurry stickers (optional)")), aiWarning)
    : h("p", { class: "muted small" }, "Text is read on your own server. (Optional AI vision for hard-to-read stickers can be enabled by the server owner.)");

  const scanBtn = h("button", { class: "btn primary", type: "button" }, icon("u-scan"), "Read stickers");
  const results = h("div", {});
  scanBtn.addEventListener("click", async () => {
    const files = picker.files();
    if (!files.length) { toast("Add at least one photo", "error"); return; }
    const fd = new FormData();
    for (const f of files) fd.append("files", f);
    fd.append("use_ai", aiBox.checked ? "true" : "false");
    scanBtn.disabled = true;
    scanBtn.replaceChildren("Reading…");
    try {
      const r = await api(`/api/vehicles/${id}/sticker/scan`, { method: "POST", body: fd });
      picker.clear();
      showReview(results, v, r, labels);
    } catch (e) { showError(e); } finally { scanBtn.disabled = false; scanBtn.replaceChildren(icon("u-scan"), "Read stickers"); }
  });

  mount(main, h("a", { class: "back", href: `#/v/${id}/specs` }, icon("u-chevron_left"), vehicleName(v)),
    h("div", { class: "page-head" }, h("div", {}, h("h1", {}, "Scan door sticker"),
      h("p", { class: "sub muted" }, "Photograph the tire & loading placard and the certification label inside the driver's door jamb."))),
    h("div", { class: "stack-lg" }, h("section", { class: "card stack" }, picker.el, aiOption, h("div", {}, scanBtn)), results));
}

/** The review screen: one row per recognised field. */
function showReview(container, v, result, labels) {
  const rows = result.fields.map((f) => {
    const keep = h("input", { type: "checkbox", checked: true, "aria-label": `Keep ${labels[f.key] || f.key}` });
    const input = h("input", { class: "input", value: f.value, maxlength: 255 });
    const row = h("div", { class: "review-row" }, keep, h("div", { class: "field" }, h("span", {}, labels[f.key] || f.key), input,
      h("span", { class: "hint" }, h("span", { class: "pill src-sticker" }, "From door sticker"))));
    keep.addEventListener("change", () => row.classList.toggle("rejected", !keep.checked));
    return { f, keep, input, row };
  });
  const save = h("button", { class: "btn primary", type: "button", onclick: async () => {
    const fields = rows.filter((r) => r.keep.checked && r.input.value.trim()).map((r) => ({ key: r.f.key, value: r.input.value.trim() }));
    if (!fields.length) { toast("Nothing selected to save", "error"); return; }
    save.disabled = true;
    try {
      await api(`/api/vehicles/${v.id}/sticker/apply`, { method: "POST", body: { fields } });
      toast(`${fields.length} field${fields.length === 1 ? "" : "s"} saved to Specs`);
      location.hash = `#/v/${v.id}/specs`;
    } catch (e) { showError(e); save.disabled = false; }
  } }, icon("u-check"), "Save approved fields");

  mount(container, h("section", { class: "card" },
    h("div", { class: "section-title" }, h("h2", {}, "Review what was read"), h("span", { class: "pill" }, result.method === "ai" ? "AI vision" : "On-server text recognition")),
    h("p", { class: "muted", style: null }, "Check each value against the sticker. Untick anything wrong, or fix it. Nothing is saved until you press Save."),
    result.attachments.length ? h("div", { class: "gallery" }, result.attachments.map((a) => h("a", { href: `/api/attachments/${a.id}/file`, target: "_blank", rel: "noopener" },
      h("img", { src: `/api/attachments/${a.id}/thumb`, alt: "Sticker photo" })))) : null,
    rows.length ? h("div", {}, rows.map((r) => r.row)) : h("div", { class: "notice" }, icon("u-warning"),
      h("p", {}, "No fields could be read. Try a sharper, straight-on photo with good light" + (result.method === "ocr" ? ", or AI vision if available." : "."))),
    rows.length ? h("div", { class: "btn-row", style: null }, save, h("a", { class: "btn", href: `#/v/${v.id}/specs` }, "Reject all")) : null));
  const sectionKids = container.firstChild.children;
  sectionKids[sectionKids.length - 1].style.marginTop = "18px";
  container.scrollIntoView({ behavior: "smooth" });
}

// ===========================================================================
// Carfax import
// ===========================================================================
export async function carfaxView(main, { params }) {
  const [id] = params;
  const [v, catalog] = await Promise.all([api(`/api/vehicles/${id}`), getCatalog()]);
  const picker = filePicker({ accept: "application/pdf", multiple: false, label: "Choose your Carfax report (PDF)" });
  const results = h("div", {});
  const readBtn = h("button", { class: "btn primary", type: "button", onclick: async () => {
    const [file] = picker.files();
    if (!file) { toast("Choose the PDF first", "error"); return; }
    const fd = new FormData();
    fd.append("file", file);
    readBtn.disabled = true;
    readBtn.replaceChildren("Reading…");
    try {
      const r = await api(`/api/vehicles/${id}/carfax/parse`, { method: "POST", body: fd });
      picker.clear();
      showCarfaxReview(results, v, r, catalog);
    } catch (e) { showError(e); } finally { readBtn.disabled = false; readBtn.replaceChildren(icon("u-carfax"), "Read report"); }
  } }, icon("u-carfax"), "Read report");

  mount(main, h("a", { class: "back", href: `#/v/${id}` }, icon("u-chevron_left"), vehicleName(v)),
    h("div", { class: "page-head" }, h("div", {}, h("h1", {}, "Import Carfax report"),
      h("p", { class: "sub muted" }, "We pull out the service records. You review every one before anything is saved."))),
    h("div", { class: "stack-lg" }, h("section", { class: "card stack" }, picker.el,
      h("p", { class: "muted small" }, "The PDF is read on your server in a locked-down helper process. It is kept with the vehicle's documents."),
      h("div", {}, readBtn)), results));
}

export function showCarfaxReview(container, v, result, catalog) {
  const types = catalog.types.filter((t) => t.applies_to === "both" || t.applies_to === v.kind);
  const labelOf = Object.fromEntries(catalog.types.map((t) => [t.key, t.label]));

  // One row per piece of work; rows are grouped by visit (same date + shop).
  const makeRow = (p) => {
    const keep = h("input", { type: "checkbox", checked: p.suggested, "aria-label": "Import this record" });
    const type = h("select", { class: "input", "aria-label": "Type" }, types.map((t) => {
      const o = h("option", { value: t.key }, t.label); if (t.key === p.type_key) o.selected = true; return o;
    }));
    const kind = h("select", { class: "input", "aria-label": "Kind" },
      ["maintenance", "repair"].map((k) => { const o = h("option", { value: k }, k === "repair" ? "Repair" : "Maintenance"); if (k === p.kind) o.selected = true; return o; }));
    const custom = h("input", { class: "input", value: p.custom_type || "", maxlength: 64, "aria-label": "What was done", placeholder: "What was done" });
    const desc = h("input", { class: "input", value: p.description, maxlength: 500, "aria-label": "Description" });
    // The icon follows whatever type/kind you pick for this row.
    let badge = typeBadge(p.type_key, p.kind);
    const title = h("strong", {});
    const refresh = () => {
      const fresh = typeBadge(type.value, kind.value); badge.replaceWith(fresh); badge = fresh;
      custom.hidden = type.value !== "custom";
      title.textContent = type.value === "custom" ? (custom.value || "Custom / other") : (labelOf[type.value] || type.value);
    };
    type.addEventListener("change", refresh);
    kind.addEventListener("change", refresh);
    custom.addEventListener("input", refresh);
    const row = h("div", { class: "review-row" + (p.suggested ? "" : " rejected") }, keep,
      h("div", { class: "stack" },
        h("div", { class: "row" }, badge, title,
          p.duplicate ? h("span", { class: "pill" }, "Already in your history") : null,
          !p.suggested && !p.duplicate ? h("span", { class: "pill" }, "Paperwork — not a service?") : null),
        h("div", { class: "review-fields" }, type, kind, custom), desc));
    refresh();
    keep.addEventListener("change", () => row.classList.toggle("rejected", !keep.checked));
    return { p, keep, type, kind, custom, desc, row };
  };

  const visitsById = new Map();
  for (const p of result.entries) {
    if (!visitsById.has(p.visit)) visitsById.set(p.visit, []);
    visitsById.get(p.visit).push(p);
  }
  const visits = [...visitsById.values()].map((items) => {
    const first = items[0];
    const date = h("input", { class: "input", type: "date", value: first.date, "aria-label": "Date" });
    const mileage = h("input", { class: "input", inputmode: "numeric", value: first.mileage ?? "", "aria-label": "Odometer", placeholder: "Odometer" });
    const by = h("select", { class: "input", "aria-label": "Done by" },
      [["shop", "A shop did it"], ["diy", "I did it (DIY)"]].map(([val, txt]) => { const o = h("option", { value: val }, txt); if (val === first.performed_by) o.selected = true; return o; }));
    const shop = h("input", { class: "input", value: first.shop_name, maxlength: 128, "aria-label": "Shop", placeholder: "Shop name" });
    const syncShop = () => { shop.hidden = by.value !== "shop"; };
    by.addEventListener("change", syncShop); syncShop();
    const rows = items.map(makeRow);
    const el = h("section", { class: "card stack" },
      h("div", { class: "row between" },
        h("h3", {}, `${dateText(first.date)} · ${first.mileage != null ? first.mileage.toLocaleString() + " " + (v.mileage_unit || "mi") : "no odometer"} · ${first.performed_by === "diy" ? "DIY" : (first.shop_name || "Shop")}`),
        h("span", { class: "muted small" }, `${rows.length} item${rows.length === 1 ? "" : "s"}`)),
      h("div", { class: "review-fields" }, date, mileage, by, shop),
      h("div", {}, rows.map((r) => r.row)));
    return { date, mileage, by, shop, rows, el, notes: first.notes };
  });
  const allRows = visits.flatMap((vi) => vi.rows);

  // Options applied when saving.
  const optOdo = h("input", { type: "checkbox", checked: true });
  const optRem = h("input", { type: "checkbox", checked: true });
  const optReplace = h("input", { type: "checkbox", checked: result.previous_imports > 0 });
  const newest = result.newest_mileage != null
    ? ` The newest record is ${result.newest_mileage.toLocaleString()} ${v.mileage_unit || "mi"} on ${dateText(result.newest_date)}.` : "";
  const options = h("section", { class: "card stack" },
    h("h2", {}, "When importing"),
    h("label", { class: "row option-row" }, optOdo, h("span", {}, "Update the odometer: add a reading for each visit and raise the current mileage." + newest)),
    h("label", { class: "row option-row" }, optRem, h("span", {}, "Update reminders: set “last done” from the newest oil change, tire rotation, filters, fluids and so on, and create reminders that don't exist yet (with typical intervals you can change).")),
    result.previous_imports > 0
      ? h("label", { class: "row option-row" }, optReplace, h("span", {},
        `Replace the ${result.previous_imports} record${result.previous_imports === 1 ? "" : "s"} from your earlier Carfax import (recommended if it came out wrong). Only records that came from Carfax are removed — anything you added yourself is never touched.`))
      : null);

  const count = h("span", { class: "muted" });
  const updateCount = () => { count.textContent = `${allRows.filter((r) => r.keep.checked).length} of ${allRows.length} selected`; };
  allRows.forEach((r) => r.keep.addEventListener("change", updateCount));
  updateCount();
  const save = h("button", { class: "btn primary", type: "button", onclick: async () => {
    const entries = visits.flatMap((vi) => vi.rows.filter((r) => r.keep.checked).map((r) => ({
      date: vi.date.value, mileage: parseIntOrNull(vi.mileage.value), type_key: r.type.value,
      custom_type: r.type.value === "custom" ? r.custom.value.trim() : "", kind: r.kind.value,
      description: r.desc.value.trim(), performed_by: vi.by.value, shop_name: vi.by.value === "shop" ? vi.shop.value.trim() : "",
      notes: vi.notes })));
    if (!entries.length) { toast("Nothing selected", "error"); return; }
    save.disabled = true;
    try {
      const res = await api(`/api/vehicles/${v.id}/carfax/apply`, { method: "POST", body: {
        entries, update_odometer: optOdo.checked, update_reminders: optRem.checked, replace_previous: optReplace.checked } });
      const parts = [`${res.created} record${res.created === 1 ? "" : "s"} imported`];
      if (res.skipped_duplicates) parts.push(`${res.skipped_duplicates} already there`);
      if (res.current_mileage != null) parts.push(`odometer ${res.current_mileage.toLocaleString()} ${v.mileage_unit || "mi"}`);
      const rems = res.reminders_created.length + res.reminders_updated.length;
      if (rems) parts.push(`${rems} reminder${rems === 1 ? "" : "s"} updated`);
      toast(parts.join(" · "));
      location.hash = `#/v/${v.id}/history`;
    } catch (e) { showError(e); save.disabled = false; }
  } }, icon("u-check"), "Import selected");

  mount(container, h("div", { class: "stack-lg" },
    h("section", { class: "card" },
      h("div", { class: "section-title" }, h("h2", {}, `Review ${visits.length} visit${visits.length === 1 ? "" : "s"} · ${allRows.length} item${allRows.length === 1 ? "" : "s"}`), count),
      h("p", { class: "muted small" }, "Each visit is split into what was done, so every item gets its own icon and keeps reminders up to date. Untick anything you don't want; change a type if it's wrong.")),
    allRows.length ? visits.map((vi) => vi.el) : h("div", { class: "notice" }, icon("u-warning"), h("p", {}, "No service records were found in this PDF.")),
    allRows.length ? options : null,
    allRows.length ? h("div", { class: "btn-row review-actions" }, save, h("a", { class: "btn", href: `#/v/${v.id}` }, "Cancel")) : null));
  container.scrollIntoView({ behavior: "smooth" });
}
