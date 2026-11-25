// login.js — the sign-in screen.

import { api, forgetMe } from "../api.js";
import { h, mount, field, showError } from "../ui.js";

export async function loginView(main, { query }) {
  // A brand-new desktop install has no account yet: show first-run setup.
  try {
    const st = await api("/api/auth/setup-status");
    if (st.needs_setup) { location.hash = "#/setup" + (query.get("code") ? `?code=${encodeURIComponent(query.get("code"))}` : ""); return; }
  } catch { /* older server: just show sign-in */ }
  const user = field("Username", { name: "username", autocomplete: "username", required: true, autocapitalize: "none" });
  const pass = field("Password", { name: "password", type: "password", autocomplete: "current-password", required: true });
  const button = h("button", { class: "btn primary", type: "submit" }, "Sign in");
  const form = h("form", { class: "form", onsubmit: async (e) => {
    e.preventDefault();
    button.disabled = true;
    try {
      await api("/api/auth/login", { method: "POST", body: { username: user.input.value.trim(), password: pass.input.value, client: "web" } });
      forgetMe();
      const next = query.get("next") || "/";
      // Only follow "next" links inside this app (never to another website).
      if (next === "/api/docs") location.href = "/api/docs";
      else location.hash = "#" + (next.startsWith("/") && !next.startsWith("//") ? next : "/");
    } catch (err) {
      showError(err);
      pass.input.value = "";
      pass.input.focus();
    } finally {
      button.disabled = false;
    }
  } }, user, pass, button);

  mount(main, h("div", { class: "login" }, h("div", { class: "card" },
    h("div", { class: "logo" }, h("img", { src: "/brand/logo-mark.svg", alt: "" }),
      h("h1", {}, "Ratchet Wrench"), h("p", { class: "muted" }, "Your vehicles' maintenance & repair log")),
    form)));
  user.input.focus();
}

/**
 * First-run setup (desktop app): create your account.  The desktop app opens
 * this page with a one-time code in the address; the server only accepts
 * the account if the code matches, and only while no account exists.
 */
export async function setupView(main, { query }) {
  const code = query.get("code") || "";
  const user = field("Choose a username", { autocomplete: "username", placeholder: "e.g. dan", required: true, autocapitalize: "none", minlength: 3, maxlength: 64, pattern: "[A-Za-z0-9_.\\-]{3,64}" }, "3 or more letters or numbers.");
  const pass = field("Choose a password", { type: "password", autocomplete: "new-password", required: true, minlength: 10 }, "At least 10 characters. You'll use it to sign in, and to connect your phone.");
  const pass2 = field("Type the password again", { type: "password", autocomplete: "new-password", required: true });
  const button = h("button", { class: "btn primary", type: "submit" }, "Create my account");
  const form = h("form", { class: "form", onsubmit: async (e) => {
    e.preventDefault();
    if (pass.input.value !== pass2.input.value) { showError(new Error("The two passwords don't match.")); return; }
    button.disabled = true;
    try {
      await api("/api/auth/setup", { method: "POST", body: { token: code, username: user.input.value.trim(), password: pass.input.value } });
      forgetMe();
      location.hash = "#/";
    } catch (err) { showError(err); } finally { button.disabled = false; }
  } }, user, pass, pass2, button);

  mount(main, h("div", { class: "login" }, h("div", { class: "card" },
    h("div", { class: "logo" }, h("img", { src: "/brand/logo-mark.svg", alt: "" }),
      h("h1", {}, "Welcome to Ratchet Wrench"),
      h("p", { class: "muted" }, "Create your account. Everything is stored privately on this computer.")),
    code ? form : h("div", { class: "notice" }, h("p", {}, "Open Ratchet Wrench from the desktop app to finish setup (the app opens this page with a one-time setup code).")))));
  if (code) pass.input.focus();
}
