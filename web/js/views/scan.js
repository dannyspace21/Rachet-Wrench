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
  const rows = result.entries.map((p) => {
    const keep = h("input", { type: "checkbox", checked: p.suggested, "aria-label": "Import this record" });
    const date = h("input", { class: "input", type: "date", value: p.date, "aria-label": "Date" });
    const mileage = h("input", { class: "input", inputmode: "numeric", value: p.mileage ?? "", "aria-label": "Mileage", placeholder: "Mileage" });
    const type = h("select", { class: "input", "aria-label": "Type" }, types.map((t) => {
      const o = h("option", { value: t.key }, t.label); if (t.key === p.type_key) o.selected = true; return o;
    }));
    const kind = h("select", { class: "input", "aria-label": "Kind" },
      ["maintenance", "repair"].map((k) => { const o = h("option", { value: k }, k === "repair" ? "Repair" : "Maintenance"); if (k === p.kind) o.selected = true; return o; }));
    const desc = h("input", { class: "input", value: p.description, maxlength: 500, "aria-label": "Description" });
    const shop = h("input", { class: "input", value: p.shop_name, maxlength: 128, "aria-label": "Shop" });
    // The icon follows whatever type/kind you pick for this row.
    let badge = typeBadge(p.type_key, p.kind);
    const refreshBadge = () => { const fresh = typeBadge(type.value, kind.value); badge.replaceWith(fresh); badge = fresh; };
    type.addEventListener("change", refreshBadge);
    kind.addEventListener("change", refreshBadge);
    const row = h("div", { class: "review-row" + (p.suggested ? "" : " rejected") }, keep,
      h("div", { class: "stack" },
        h("div", { class: "row" }, badge, h("strong", {}, dateText(p.date)), p.suggested ? null : h("span", { class: "pill" }, "Not a service record?")),
        h("div", { class: "review-fields" }, date, mileage, type, kind), desc, shop,
        p.notes ? h("p", { class: "muted small" }, p.notes) : null));
    keep.addEventListener("change", () => row.classList.toggle("rejected", !keep.checked));
    return { p, keep, date, mileage, type, kind, desc, shop, row };
  });
  const count = h("span", { class: "muted" });
  const updateCount = () => { count.textContent = `${rows.filter((r) => r.keep.checked).length} of ${rows.length} selected`; };
  rows.forEach((r) => r.keep.addEventListener("change", updateCount));
  updateCount();
  const save = h("button", { class: "btn primary", type: "button", onclick: async () => {
    const entries = rows.filter((r) => r.keep.checked).map((r) => ({
      date: r.date.value, mileage: parseIntOrNull(r.mileage.value), type_key: r.type.value, kind: r.kind.value,
      description: r.desc.value.trim(), shop_name: r.shop.value.trim(), performed_by: "shop", notes: r.p.notes }));
    if (!entries.length) { toast("Nothing selected", "error"); return; }
    save.disabled = true;
    try {
      const res = await api(`/api/vehicles/${v.id}/carfax/apply`, { method: "POST", body: { entries } });
      toast(`${res.created} record${res.created === 1 ? "" : "s"} imported`);
      location.hash = `#/v/${v.id}/history`;
    } catch (e) { showError(e); save.disabled = false; }
  } }, icon("u-check"), "Import selected");

  mount(container, h("section", { class: "card" },
    h("div", { class: "section-title" }, h("h2", {}, `Review ${rows.length} record${rows.length === 1 ? "" : "s"}`), count),
    rows.length ? h("div", {}, rows.map((r) => r.row)) : h("div", { class: "notice" }, icon("u-warning"), h("p", {}, "No dated records were found in this PDF.")),
    rows.length ? h("div", { class: "btn-row review-actions" }, save, h("a", { class: "btn", href: `#/v/${v.id}` }, "Cancel")) : null));
  container.scrollIntoView({ behavior: "smooth" });
}
