// app.js — starts the web app and decides which screen to show.
//
// The address bar holds the current screen after the "#", e.g.
//   #/                     garage dashboard
//   #/v/<id>/history       a vehicle (tabs: reminders, parts, history, metrics, specs, docs)
//   #/v/new                add a vehicle
//   #/v/<id>/edit          edit a vehicle
//   #/v/<id>/entry/new     add a maintenance/repair entry
//   #/e/<id>               one entry;  #/e/<id>/edit to change it
//   #/v/<id>/sticker       door-jamb sticker scan + review
//   #/v/<id>/carfax        Carfax import + review
//   #/shopping             the shopping list
//   #/settings, #/icons, #/login

import { getMe } from "./api.js";
import { mount, loading, showError, setPrefs, applyTheme } from "./ui.js";
import { loginView, setupView } from "./views/login.js";
import { dashboardView } from "./views/dashboard.js";
import { vehicleView, vehicleFormView } from "./views/vehicle.js";
import { entryFormView, entryDetailView } from "./views/entry.js";
import { stickerView, carfaxView } from "./views/scan.js";
import { settingsView, iconsView } from "./views/settings.js";
import { shoppingListView } from "./views/shopping-list.js";

const UUID = "([0-9a-fA-F-]{36})";
const routes = [
  [/^\/login$/, loginView, { public: true }],
  [/^\/setup$/, setupView, { public: true }],
  [/^\/$/, dashboardView, { nav: "home" }],
  [/^\/v\/new$/, vehicleFormView, { nav: "home" }],
  [new RegExp(`^/v/${UUID}/edit$`), vehicleFormView, { nav: "home" }],
  [new RegExp(`^/v/${UUID}/entry/new$`), entryFormView, { nav: "home" }],
  [new RegExp(`^/v/${UUID}/sticker$`), stickerView, { nav: "home" }],
  [new RegExp(`^/v/${UUID}/carfax$`), carfaxView, { nav: "home" }],
  [new RegExp(`^/v/${UUID}(?:/(history|specs|reminders|parts|metrics|odometer|docs))?$`), vehicleView, { nav: "home" }],
  [new RegExp(`^/e/${UUID}/edit$`), entryFormView, { nav: "home", entryEdit: true }],
  [new RegExp(`^/e/${UUID}$`), entryDetailView, { nav: "home" }],
  [/^\/shopping$/, shoppingListView, { nav: "shopping" }],
  [/^\/settings$/, settingsView, { nav: "settings" }],
  [/^\/icons$/, iconsView, { nav: "icons" }],
];

/** Split "#/path?x=1" into a path and query parameters. */
function parseHash() {
  const raw = location.hash.replace(/^#/, "") || "/";
  const [path, qs] = raw.split("?");
  return { path, query: new URLSearchParams(qs || "") };
}

let renderCounter = 0;

/** Draw the screen for the current address. */
async function render() {
  const myTurn = ++renderCounter;
  const main = document.getElementById("main");
  const topbar = document.getElementById("topbar");
  const { path, query } = parseHash();
  const match = routes.find(([re]) => re.test(path));
  if (!match) { location.hash = "#/"; return; }
  const [re, view, opts] = match;
  const params = path.match(re).slice(1);

  if (!opts.public) {
    try {
      const me = await getMe();
      setPrefs(me.settings);
      applyTheme(me.settings.theme);
    } catch { return; } // api() already redirected to the login page
  }
  if (myTurn !== renderCounter) return; // user navigated again meanwhile

  topbar.hidden = !!opts.public;
  for (const a of document.querySelectorAll("[data-nav]")) a.classList.toggle("active", a.dataset.nav === opts.nav);
  mount(main, loading());
  try {
    await view(main, { params, query, ...opts });
    document.title = (main.querySelector("h1")?.textContent || "Garage") + " · Ratchet Wrench";
  } catch (err) {
    if (err?.status !== 401) showError(err);
  }
  window.scrollTo(0, 0);
}

window.addEventListener("hashchange", render);
render();
