// ui.js — small building blocks shared by every screen.
//
// SECURITY: every element is created with document.createElement and every
// piece of text is set with textContent.  We never build HTML from strings,
// so text you type (or text read from a sticker or a Carfax PDF) can never
// turn into code in the page (this prevents "cross-site scripting").

const SPRITE = "/icons/sprite.svg";
const SVG_NS = "http://www.w3.org/2000/svg";

/**
 * h("div", {class: "card", onclick: fn}, child1, "text", ...)
 * Creates an element.  Attributes starting with "on" become event
 * listeners; null/false attributes and children are skipped.
 */
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k === "dataset") Object.assign(el.dataset, v);
    else if (k === "value") el.value = v;
    else if (k === "checked") el.checked = !!v;
    else if (k === "href" && typeof v === "string" && /^\s*javascript:/i.test(v)) continue; // never allow js: links
    else el.setAttribute(k, v === true ? "" : String(v));
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

/** An icon from the sprite.  name = "u-camera" (interface) or "t-oil_change" (type). */
export function icon(name, cls = "") {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("class", ("i " + cls).trim());
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(SVG_NS, "use");
  use.setAttribute("href", `${SPRITE}#${name}`);
  svg.append(use);
  return svg;
}

/** The coloured square icon for a maintenance/repair type. */
export function typeBadge(typeKey, kind = "maintenance") {
  return h("span", { class: "type-badge" + (kind === "repair" ? " repair" : "") }, icon("t-" + (typeKey || "custom"), "lg"));
}

/** Replace everything inside an element. */
export function mount(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------
/** Light / Dark / System: apply now, and remember it locally so the next
 *  page load paints in the right colours straight away (see /theme.js). */
export function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === "light" || theme === "dark") root.setAttribute("data-theme", theme);
  else root.removeAttribute("data-theme");
  try { localStorage.setItem("rw-theme", theme || "system"); } catch { /* storage blocked */ }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------
let currency = "USD";
let unit = "mi";
export function setPrefs(settings) {
  if (settings?.currency) currency = settings.currency;
  if (settings?.distance_unit) unit = settings.distance_unit;
}
export function money(cents) {
  // No cost entered shows as $0.00 (not a dash), as asked.
  if (cents === null || cents === undefined || cents === "") cents = 0;
  return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(cents / 100);
}
export function miles(n, u) {
  if (n === null || n === undefined || n === "") return "—";
  return `${Number(n).toLocaleString()} ${u || unit}`;
}
export function dateText(iso) {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}
export function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
export function vehicleName(v) {
  return v.nickname || [v.year, v.make, v.model].filter(Boolean).join(" ") || "Vehicle";
}
export function vehicleFull(v) {
  return [v.year, v.make, v.model, v.trim].filter(Boolean).join(" ");
}
/** "$12.34" text -> 1234 cents (or null). */
export function parseMoney(text) {
  const clean = String(text || "").replace(/[^0-9.]/g, "");
  if (!clean) return null;
  const n = Math.round(parseFloat(clean) * 100);
  return Number.isFinite(n) ? n : null;
}
export function parseIntOrNull(text) {
  const clean = String(text ?? "").replace(/[^0-9]/g, "");
  return clean ? parseInt(clean, 10) : null;
}

// ---------------------------------------------------------------------------
// Feedback: toasts, modals, loading
// ---------------------------------------------------------------------------
export function toast(message, kind = "info") {
  const box = document.getElementById("toasts");
  const t = h("div", { class: "toast" + (kind === "error" ? " error" : "") }, message);
  box.append(t);
  setTimeout(() => t.remove(), kind === "error" ? 6000 : 3500);
}

/** Show an error from a failed API call. */
export function showError(err) {
  console.error(err);
  toast(err?.message || "Something went wrong.", "error");
}

/**
 * modal({title, body, actions:[{label, primary, danger, value}]}) -> Promise
 * Resolves with the chosen action's value (or null if dismissed).
 */
export function modal({ title, body, actions = [{ label: "OK", primary: true, value: true }], dismissOnBackdrop = true, dismissOnEscape = true }) {
  return new Promise((resolve) => {
    const prev = document.activeElement;
    const close = (value) => { back.remove(); document.removeEventListener("keydown", onKey); prev?.focus?.(); resolve(value); };
    const onKey = (e) => { if (dismissOnEscape && e.key === "Escape") close(null); };
    const dialog = h("div", { class: "modal", role: "dialog", "aria-modal": "true", "aria-label": title },
      h("h2", {}, title), body,
      h("div", { class: "actions" }, actions.map((a) =>
        h("button", { class: "btn" + (a.primary ? " primary" : "") + (a.danger ? " danger" : ""), type: "button",
          onclick: () => close(typeof a.value === "function" ? a.value() : a.value) }, a.label))));
    const back = h("div", { class: "modal-back", onclick: (e) => { if (dismissOnBackdrop && e.target === back) close(null); } }, dialog);
    document.body.append(back);
    document.addEventListener("keydown", onKey);
    (dialog.querySelector("input, select, textarea, button.primary") || dialog).focus();
  });
}

export async function confirmBox(title, message, okLabel = "Delete") {
  return modal({ title, body: h("p", {}, message), actions: [
    { label: "Cancel", value: false }, { label: okLabel, primary: true, danger: true, value: true }] });
}

export function loading() {
  return h("div", { class: "empty" }, h("p", {}, "Loading…"));
}

export function emptyState(iconName, title, text, action) {
  return h("div", { class: "empty card flat" }, icon(iconName, "xl"), h("h3", {}, title), text ? h("p", {}, text) : null, action || null);
}

// ---------------------------------------------------------------------------
// Form controls
// ---------------------------------------------------------------------------
/** A labelled input.  Returns the wrapper; the input is wrapper.input. */
export function field(label, attrs = {}, hint = "") {
  const tag = attrs.tag || "input";
  const input = h(tag, { class: "input", ...attrs, tag: null });
  if (tag === "select") {
    for (const o of attrs.options || []) {
      const opt = h("option", { value: o.value }, o.label);
      if (String(o.value) === String(attrs.value ?? "")) opt.selected = true;
      input.append(opt);
    }
    input.removeAttribute("options");
  }
  const wrap = h("label", { class: "field" }, h("span", {}, label), input, hint ? h("span", { class: "hint" }, hint) : null);
  wrap.input = input;
  return wrap;
}

/**
 * Segmented control: options [{value, label, icon}] -> element with .value
 * and an onChange callback.
 */
export function segmented(options, value, onChange, ariaLabel = "") {
  const wrap = h("div", { class: "seg", role: "group", "aria-label": ariaLabel });
  wrap.value = value;
  const render = () => {
    wrap.replaceChildren(...options.map((o) => h("button", {
      type: "button", "aria-pressed": String(o.value === wrap.value),
      onclick: () => { wrap.value = o.value; render(); onChange?.(o.value); },
    }, o.icon ? icon(o.icon) : null, o.label)));
  };
  render();
  return wrap;
}

/** Inline SVG line chart for odometer readings. points: [{date, mileage}] */
export function lineChart(points) {
  const W = 640, H = 180, P = 28;
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("class", "chart");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "Odometer readings over time");
  if (points.length < 2) return svg;
  const xs = points.map((p) => new Date(p.date).getTime());
  const ys = points.map((p) => p.mileage);
  const [x0, x1] = [Math.min(...xs), Math.max(...xs)];
  const [y0, y1] = [Math.min(...ys), Math.max(...ys)];
  const sx = (x) => P + ((x - x0) / Math.max(1, x1 - x0)) * (W - 2 * P);
  const sy = (y) => H - P - ((y - y0) / Math.max(1, y1 - y0)) * (H - 2 * P);
  const pts = points.map((p, i) => [sx(xs[i]), sy(ys[i])]);
  const mk = (tag, attrs) => { const e = document.createElementNS(SVG_NS, tag); for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v); return e; };
  const d = pts.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join("");
  svg.append(mk("path", { class: "area", d: `${d}L${pts.at(-1)[0]} ${H - P}L${pts[0][0]} ${H - P}Z` }));
  svg.append(mk("path", { class: "line", d }));
  for (const p of pts) svg.append(mk("circle", { class: "dot", cx: p[0], cy: p[1], r: 4 }));
  const lo = mk("text", { x: P, y: H - 8 }); lo.textContent = Number(y0).toLocaleString();
  const hi = mk("text", { x: W - P, y: 16, "text-anchor": "end" }); hi.textContent = Number(y1).toLocaleString();
  svg.append(lo, hi);
  return svg;
}

/**
 * Photo/file picker with previews.  Returns {el, files()} where files()
 * gives the chosen File objects.  Works with the camera on phones.
 */
export function filePicker({ accept = "image/jpeg,image/png,image/heic,image/heif,application/pdf", multiple = true, label = "Add photos or receipts", capture = false } = {}) {
  let chosen = [];
  const previews = h("div", { class: "previews" });
  const input = h("input", { type: "file", accept, multiple, class: "hidden", capture: capture ? "environment" : null });
  const render = () => {
    previews.replaceChildren(...chosen.map((f, idx) => {
      const isImg = f.type.startsWith("image/") && !/hei[cf]/i.test(f.type);
      const inner = isImg ? h("img", { alt: f.name }) : icon(f.type === "application/pdf" ? "u-report" : "u-image", "xl");
      if (isImg) { const url = URL.createObjectURL(f); inner.src = url; inner.onload = () => URL.revokeObjectURL(url); }
      return h("div", { class: "preview" }, inner,
        h("button", { class: "rm", type: "button", "aria-label": `Remove ${f.name}`, onclick: () => { chosen.splice(idx, 1); render(); } }, icon("u-x")),
        h("span", { class: "cap" }, f.name));
    }));
  };
  const add = (list) => { chosen = multiple ? chosen.concat([...list]) : [...list].slice(0, 1); render(); };
  input.addEventListener("change", () => { add(input.files); input.value = ""; });
  const zone = h("div", { class: "dropzone", role: "button", tabindex: "0",
    onclick: () => input.click(), onkeydown: (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); input.click(); } },
    ondragover: (e) => { e.preventDefault(); zone.classList.add("drag"); },
    ondragleave: () => zone.classList.remove("drag"),
    ondrop: (e) => { e.preventDefault(); zone.classList.remove("drag"); add(e.dataTransfer.files); } },
  icon("u-camera", "xl"), h("strong", {}, label), h("span", { class: "small" }, "Take a photo, choose from your gallery, or drop files here (JPEG, PNG, HEIC, PDF)"));
  return { el: h("div", { class: "stack" }, zone, input, previews), files: () => chosen.slice(), clear: () => { chosen = []; render(); } };
}
