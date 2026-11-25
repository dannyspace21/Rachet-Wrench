// login.js — the sign-in screen.

import { api, forgetMe } from "../api.js";
import { h, mount, field, showError } from "../ui.js";

export async function loginView(main, { query }) {
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
