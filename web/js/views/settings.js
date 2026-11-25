// settings.js — Settings (theme, units, push alerts, password, users, API
// docs, sign out) and the icon set overview.

import { api, getCatalog, getMe, refreshMe, forgetMe } from "../api.js";
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
