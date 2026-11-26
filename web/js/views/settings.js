// settings.js — Settings (theme, units, push alerts, password, users, API
// docs, sign out) and the icon set overview.

import { api, getCatalog, getMe, refreshMe, forgetMe } from "../api.js";
import { affiliateOn, prefs as shopPrefs, savePrefs as saveShopPrefs, webPlatform } from "../shopping.js";
import { h, mount, icon, field, segmented, toast, showError, setPrefs, modal, applyTheme } from "../ui.js";

export async function settingsView(main) {
  const me = await getMe();
  const s = me.settings;

  const save = async (changes, message = "Saved") => {
    try {
      await api("/api/auth/settings", { method: "PUT", body: changes });
      const fresh = await refreshMe();
      setPrefs(fresh.settings);
      toast(message);
    } catch (e) { showError(e); }
  };

  // Appearance
  const theme = segmented([
    { value: "light", label: "Light", icon: "u-sun" }, { value: "dark", label: "Dark", icon: "u-moon" }, { value: "system", label: "System", icon: "u-monitor" }],
  s.theme, (val) => { applyTheme(val); save({ theme: val }, "Theme saved"); }, "Theme");
  const units = segmented([{ value: "mi", label: "Miles" }, { value: "km", label: "Kilometers" }], s.distance_unit, (val) => save({ distance_unit: val }), "Distance unit");
  const currency = field("Currency", { value: s.currency, maxlength: 3, autocapitalize: "characters" }, "3-letter code, e.g. USD, CAD, EUR");
  currency.input.addEventListener("change", () => save({ currency: currency.input.value.trim().toUpperCase() }));

  // Push notifications (ntfy)
  const ntfyOn = h("input", { type: "checkbox", checked: s.ntfy_enabled });
  const topic = field("ntfy topic", { value: s.ntfy_topic, maxlength: 64, placeholder: "e.g. rw-" + Math.random().toString(36).slice(2, 10) },
    "Treat the topic like a password: anyone who knows it can read your alerts. Letters, numbers, - and _ only.");
  const pushCard = h("section", { class: "card stack" }, h("h2", {}, "Push alerts (ntfy)"),
    me.server.ntfy_configured
      ? h("div", { class: "stack" },
        h("label", { class: "check" }, ntfyOn, h("span", {}, "Send reminder alerts to my phone through ntfy")), topic,
        h("div", { class: "btn-row" },
          h("button", { class: "btn primary", type: "button", onclick: () => save({ ntfy_enabled: ntfyOn.checked, ntfy_topic: topic.input.value.trim() }) }, "Save"),
          h("button", { class: "btn", type: "button", onclick: async () => {
            try { await api("/api/auth/settings/test-push", { method: "POST" }); toast("Test notification sent"); } catch (e) { showError(e); }
          } }, icon("u-bell"), "Send test")),
        h("p", { class: "muted small" }, "Subscribe to the same topic in the ntfy app on your phone. The Android app also shows reminders offline without ntfy."))
      : h("p", { class: "muted" }, "Not set up on this server. Add RW_NTFY_URL to the .env file to enable push alerts (see README). The Android app's local reminders work without it."));

  // Password
  const cur = field("Current password", { type: "password", autocomplete: "current-password" });
  const nw = field("New password", { type: "password", autocomplete: "new-password", minlength: 10 }, "At least 10 characters. Other devices will be signed out.");
  const pwForm = h("form", { class: "form", onsubmit: async (e) => {
    e.preventDefault();
    try {
      await api("/api/auth/change-password", { method: "POST", body: { current_password: cur.input.value, new_password: nw.input.value } });
      cur.input.value = ""; nw.input.value = "";
      toast("Password changed");
    } catch (err) { showError(err); }
  } }, h("div", { class: "form-grid" }, cur, nw), h("div", {}, h("button", { class: "btn primary", type: "submit" }, icon("u-key"), "Change password")));

  // Users (admins)
  let usersCard = null;
  if (me.is_admin) {
    const users = await api("/api/auth/users");
    usersCard = h("section", { class: "card stack" }, h("h2", {}, "Users"),
      h("div", { class: "list" }, users.map((u) => h("div", { class: "item" }, h("span", { class: "type-badge neutral" }, icon("u-user", "lg")),
        h("div", { class: "main" }, h("span", { class: "title" }, u.username), h("span", { class: "meta" }, u.is_admin ? "Administrator" : "User"))))),
      h("div", {}, h("button", { class: "btn", type: "button", onclick: addUser }, icon("u-plus"), "Add user")));
  }

  mount(main, h("div", { class: "page-head" }, h("h1", {}, "Settings")),
    h("div", { class: "stack-lg" },
      h("section", { class: "card stack" }, h("h2", {}, "Appearance"),
        h("div", { class: "field" }, h("span", {}, "Theme"), theme),
        h("div", { class: "field" }, h("span", {}, "Distance"), units), currency),
      pushCard,
      h("section", { class: "card stack" }, h("h2", {}, "Password"), pwForm),
      usersCard,
      pairCard(),
      await shoppingCard(),
      bugCard(me),
      h("section", { class: "card stack" }, h("h2", {}, "More"),
        h("div", { class: "btn-row" },
          h("a", { class: "btn", href: "#/icons" }, icon("u-part"), "Icon set"),
          h("a", { class: "btn", href: "/api/docs", target: "_blank", rel: "noopener" }, icon("u-book"), "API documentation"),
          h("button", { class: "btn danger", type: "button", onclick: async () => {
            try { await api("/api/auth/logout", { method: "POST" }); } catch { /* already logged out */ }
            forgetMe();
            location.hash = "#/login";
          } }, icon("u-logout"), "Sign out")),
        h("p", { class: "muted small" }, `Signed in as ${me.username}.`))));
}

async function addUser() {
  const name = field("Username", { autocapitalize: "none", maxlength: 64 });
  const pass = field("Password", { type: "password", autocomplete: "new-password" }, "At least 10 characters.");
  const admin = h("input", { type: "checkbox" });
  const ok = await modal({ title: "Add user", body: h("div", { class: "form" }, name, pass, h("label", { class: "check" }, admin, h("span", {}, "Administrator"))),
    actions: [{ label: "Cancel", value: false }, { label: "Create", primary: true, value: true }] });
  if (!ok) return;
  try {
    await api("/api/auth/users", { method: "POST", body: { username: name.input.value.trim(), password: pass.input.value, is_admin: admin.checked } });
    toast("User created");
    window.dispatchEvent(new HashChangeEvent("hashchange"));
  } catch (e) { showError(e); }
}

export async function iconsView(main) {
  const catalog = await getCatalog();
  const groups = {};
  for (const t of catalog.types) (groups[t.category] ||= []).push(t);
  mount(main, h("div", { class: "page-head" }, h("div", {}, h("h1", {}, "Icon set"),
    h("p", { class: "sub muted" }, `${catalog.types.length} maintenance & repair types for cars and motorcycles — one consistent style.`))),
  Object.entries(groups).map(([cat, list]) => h("section", { class: "icon-cat" }, h("h3", {}, cat),
    h("div", { class: "icon-grid" }, list.map((t) => h("div", { class: "icon-tile" },
      h("span", { class: "type-badge" + (t.category === "Repairs" ? " repair" : "") }, icon("t-" + t.key, "lg")), t.label))))));
}

// ---------------------------------------------------------------------------
// Connect a phone with a QR code (no typing the address, username or password)
// ---------------------------------------------------------------------------
function pairCard() {
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
  const address = field("Address your phone uses to reach this computer", {
    value: local ? "" : location.origin, placeholder: "Leave blank to detect it, or e.g. http://192.168.1.20:8077",
  }, "Desktop app: the address shown in its window. Docker: this computer's address and port 8000.");
  const out = h("div", { class: "stack" });
  const show = h("button", { class: "btn primary", type: "button", onclick: async () => {
    show.disabled = true;
    try {
      const r = await api("/api/auth/pair", { method: "POST", body: { address: address.input.value.trim() || null } });
      const until = new Date(r.expires_at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
      out.replaceChildren(
        h("img", { src: r.qr_svg, alt: "QR code to connect your phone", class: "pair-qr", width: 240, height: 240 }),
        h("p", {}, "On your phone: open Ratchet Wrench → ", h("strong", {}, "Scan QR code"), " (on the Connect screen or in Settings → Sync)."),
        h("p", { class: "muted small" }, `Connects to ${r.address}. Works once, until ${until}. Phone and computer must be on the same Wi-Fi.`));
    } catch (e) { showError(e); } finally { show.disabled = false; }
  } }, icon("u-scan"), "Show QR code");
  return h("section", { class: "card stack" }, h("h2", {}, "Connect a phone"),
    h("p", { class: "muted" }, "Show a one-time QR code and scan it with the Android app — no address, username or password to type."),
    address, h("div", {}, show), out);
}

// ---------------------------------------------------------------------------
// Report a bug: opens a pre-filled issue on the project's GitHub page.
// Nothing is sent from here — you review the report and press Submit there.
// ---------------------------------------------------------------------------
export const ISSUE_URL = "https://github.com/dannyspace21/Ratchet-Wrench/issues/new";

export function bugReportUrl(platform, details) {
  const q = new URLSearchParams({ template: "bug_report.yml", labels: "bug", platform, details });
  return `${ISSUE_URL}?${q.toString()}`;
}

function bugCard(me) {
  const platform = /Windows|Macintosh|Linux/.exec(navigator.userAgent)?.[0] || "Browser";
  const where = location.hostname === "127.0.0.1" || location.hostname === "localhost" ? "desktop app" : "server";
  const details = [`Web app (${where})`, `Platform: ${platform === "Macintosh" ? "macOS" : platform}`, `Browser: ${navigator.userAgent}`].join("\n");
  return h("section", { class: "card stack" }, h("h2", {}, "Report a bug"),
    h("p", { class: "muted" }, "Tell us what went wrong. Your report goes straight to the project's bug list — no account needed. You'll see everything that's sent before you send it."),
    h("div", {}, h("button", { class: "btn", type: "button", onclick: () => reportBug(me, platform === "Macintosh" ? "macOS" : platform, details) },
      icon("u-warning"), "Report a bug")));
}

/** The in-app bug report form; sends through this server to GitHub Issues. */
async function reportBug(me, platform, details) {
  let ready = false;
  try { ready = (await api("/api/bug-report")).enabled; } catch { /* treat as not set up */ }
  const summary = field("What's the problem? (one line)", { maxlength: 200, required: true, placeholder: "e.g. The Save button does nothing on the Specs tab" });
  const what = field("What happened?", { tag: "textarea", maxlength: 4000 });
  const steps = field("What were you doing? (steps)", { tag: "textarea", maxlength: 4000, placeholder: "1. Opened my car\n2. Tapped Specs\n3. …" });
  const expected = field("What did you expect? (optional)", { tag: "textarea", maxlength: 2000 });
  const include = h("input", { type: "checkbox", checked: true });
  const preview = h("pre", { class: "small muted bug-details" }, details);
  const body = h("div", { class: "form" }, summary, what, steps, expected,
    h("label", { class: "check" }, include, " Include these technical details"), preview,
    h("p", { class: "muted small" }, "Reports are public on GitHub. Don't include personal information (names, addresses, VINs, plates)."),
    me.is_admin ? h("p", { class: "small bug-reminder" },
      "Reminder for the owner: bug reports currently use a GitHub key built into the app. Move them to the relay service (option 1) — see docs/BUG-REPORTS.md.") : null,
    ready ? null : h("p", { class: "small" }, "Sending from here isn't set up on this computer yet, so this will open the report on GitHub instead."));
  for (;;) {
    const choice = await modal({ title: "Report a bug", body, actions: [{ label: "Cancel", value: null }, { label: ready ? "Send report" : "Continue on GitHub", primary: true, value: "send" }] });
    if (choice !== "send") return;
    const text = summary.input.value.trim();
    if (text.length < 5) { toast("Please describe the problem in a few words.", "error"); continue; }
    if (!ready) {
      window.open(bugReportUrl(platform, [text, what.input.value, steps.input.value, include.checked ? details : ""].filter(Boolean).join("\n\n")), "_blank", "noopener");
      return;
    }
    try {
      const r = await api("/api/bug-report", { method: "POST", body: { summary: text, what_happened: what.input.value, steps: steps.input.value,
        expected: expected.input.value, details: include.checked ? details : "" } });
      toast(`Thanks! Reported as #${r.number}.`);
      return;
    } catch (e) { showError(e); return; }
  }
}

// ---------------------------------------------------------------------------
// Smart Shopping: on/off, which retailers to show, preferred retailer.
// Kept in this browser only (like a display preference); nothing is synced.
// ---------------------------------------------------------------------------
async function shoppingCard() {
  const catalog = await getCatalog();
  const p = shopPrefs();
  const save = () => { saveShopPrefs(p); toast("Saved"); };
  const on = h("input", { type: "checkbox", checked: p.enabled });
  on.addEventListener("change", () => { p.enabled = on.checked; save(); });
  const boxes = (catalog.retailers || []).filter((r) => r.enabled).map((r) => {
    const b = h("input", { type: "checkbox", checked: !p.disabled.includes(r.key) });
    b.addEventListener("change", () => {
      p.disabled = b.checked ? p.disabled.filter((k) => k !== r.key) : [...new Set([...p.disabled, r.key])];
      save();
    });
    return h("label", { class: "row option-row" }, b, h("span", {}, r.name));
  });
  const preferred = field("Preferred retailer (listed first)", { tag: "select", value: p.preferred || "",
    options: [{ value: "", label: "No preference" }, ...(catalog.retailers || []).filter((r) => r.enabled).map((r) => ({ value: r.key, label: r.name }))] });
  preferred.input.addEventListener("change", () => { p.preferred = preferred.input.value || null; save(); });
  // Affiliate links: only offered when the administrator set up an approved
  // program for this platform (catalog/affiliates.json); this browser can opt out.
  const platform = webPlatform();
  const programs = (catalog.retailers || []).filter((r) => r.enabled && affiliateOn(r, platform, true));
  let affiliate = null;
  if (programs.length) {
    const box = h("input", { type: "checkbox", checked: p.affiliate !== false });
    box.addEventListener("change", () => { p.affiliate = box.checked; save(); });
    affiliate = h("div", { class: "stack" }, h("strong", {}, "Affiliate links"),
      h("label", { class: "row option-row" }, box, h("span", {}, `Use affiliate links for ${programs.map((r) => r.name).join(", ")}`)),
      h("p", { class: "muted small" }, ...programs.map((r) => r.affiliate.disclosure + " "),
        "Prices and search results are the same either way, and commissions never affect what's suggested. Turn this off for ordinary links."));
  }
  return h("section", { class: "card stack" }, h("h2", {}, "Smart Shopping"),
    h("p", { class: "muted" }, "Optional “Find supplies” and “Shop” buttons on reminders and parts. They open a search on the retailer's own website using your vehicle's specs and part numbers. You never have to buy anything, and nothing is sent from Ratchet Wrench."),
    h("label", { class: "row option-row" }, on, h("span", {}, "Show shopping buttons")),
    h("div", { class: "stack" }, h("strong", {}, "Retailers"), boxes), preferred, affiliate);
}
