// capture.cjs — takes the README screenshots automatically.
//
// It drives the REAL web app (with the sample data) in a headless Chromium
// browser via Playwright, in light AND dark mode, and saves PNGs to
// docs/screenshots/.  Run tools/screenshots/run.sh (it starts a temporary
// server with sample data first).
const { chromium } = require("playwright");
const { mkdirSync } = require("node:fs");
const { join } = require("node:path");

const here = __dirname;
const out = join(here, "..", "..", "docs", "screenshots");
mkdirSync(out, { recursive: true });
const BASE = process.env.RW_URL || "http://127.0.0.1:8099";
const USER = process.env.RW_USER || "admin";
const PASS = process.env.RW_PASS || "screenshot-password";

async function login(page) {
  await page.goto(BASE + "/#/login");
  await page.fill("input[name=username]", USER);
  await page.fill("input[name=password]", PASS);
  await page.click("button[type=submit]");
  await page.waitForSelector(".vcard");
}

async function shot(page, name, scheme, { full = false } = {}) {
  await page.waitForTimeout(600);
  await page.screenshot({ path: join(out, `${name}-${scheme}.png`), fullPage: full });
  console.log("saved", `${name}-${scheme}.png`);
}

/**
 * Screenshot-only sample history: ~10 years of service for the sample Kia
 * Forte, so the Metrics charts have something to show.  Added through the
 * normal API to the throw-away screenshot server only (never to real data).
 */
async function addSampleHistory(page, vehicleId) {
  await page.evaluate(async (vid) => {
    const T = { oil_change: [5000, 6, 49], tire_rotation: [6000, 6, 20], air_filter: [15000, 18, 30], cabin_filter: [15000, 12, 28],
      brake_pads: [35000, 0, 260], wheel_alignment: [30000, 0, 95], tire_replacement: [45000, 0, 640], coolant: [60000, 0, 140],
      spark_plugs: [60000, 0, 180], wipers: [0, 12, 36], inspection: [0, 12, 35], transmission_fluid: [60000, 0, 190] };
    let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    const start = new Date(2016, 4, 1), end = new Date(Date.now() - 20 * 864e5), mpd = 115000 / ((end - start) / 864e5);
    const posts = [];
    for (const [key, [mi, mo, cost]] of Object.entries(T)) {
      let d = new Date(start);
      for (;;) {
        d = new Date(+d + (mi ? (mi / mpd) * (0.9 + rnd() * 0.25) : mo * 30.4 * (0.95 + rnd() * 0.15)) * 864e5);
        if (d > end) break;
        const shop = rnd() < 0.5;
        posts.push({ vehicle_id: vid, type_key: key, kind: ["brake_pads", "tire_replacement"].includes(key) ? "repair" : "maintenance",
          date: d.toISOString().slice(0, 10), mileage: Math.round(((d - start) / 864e5) * mpd / 10) * 10,
          cost_cents: Math.round(cost * (0.9 + rnd() * 0.3)) * 100, performed_by: shop ? "shop" : "diy", shop_name: shop ? "Corner Garage" : "" });
      }
    }
    for (const body of posts) await fetch("/api/entries", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    // A few shopping-list lines (one from a reminder-style set, one general).
    for (const body of [{ vehicle_id: vid, type_key: "oil_change", description: "Engine oil", spec: "5W-20 synthetic blend", quantity: 5 },
      { vehicle_id: vid, type_key: "oil_filter", description: "Oil filter", part_number: "26300-35505" },
      { vehicle_id: vid, type_key: "wipers", description: "Wiper blades", spec: "24 in / 18 in", quantity: 2 },
      { description: "Shop towels", notes: "the blue ones" }]) {
      await fetch("/api/shopping", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
    }
  }, vehicleId);
}

(async () => {
const browser = await chromium.launch();
for (const scheme of ["light", "dark"]) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, deviceScaleFactor: 2, colorScheme: scheme });
  const page = await ctx.newPage();
  await login(page);
  // Follow the device setting ("System") so colorScheme picks light/dark.
  await page.evaluate(() => { localStorage.setItem("rw-theme", "system"); document.documentElement.removeAttribute("data-theme"); });
  const vehicles = await page.evaluate(async () => (await (await fetch("/api/vehicles")).json()));
  const rc = vehicles.find((v) => v.model === "RC 350");

  const forte = vehicles.find((v) => v.model === "Forte");
  if (scheme === "light") await addSampleHistory(page, forte.id);

  await page.goto(BASE + "/#/"); await shot(page, "dashboard", scheme);
  // A vehicle opens on Reminders (overdue first).
  await page.goto(BASE + `/#/v/${rc.id}`); await shot(page, "vehicle-reminders", scheme);
  // Tapping a reminder: "Mark as done".
  await page.locator(".list .item").first().click(); await page.waitForSelector(".modal");
  await shot(page, "reminder-done", scheme);
  await page.keyboard.press("Escape");
  await page.goto(BASE + `/#/v/${rc.id}/parts`); await shot(page, "vehicle-parts", scheme);
  await page.goto(BASE + `/#/v/${rc.id}/history`); await shot(page, "vehicle-history", scheme);
  // Recalls (real NHTSA data for the 2014 Kia Forte; skipped if NHTSA doesn't answer).
  await page.goto(BASE + `/#/v/${forte.id}/history`);
  await page.click(".seg >> text=Recalls");
  if (await page.waitForSelector(".recall", { timeout: 45000 }).catch(() => null)) await shot(page, "recalls", scheme, { full: true });
  else console.log("NHTSA didn't answer: recalls screenshot skipped");
  await page.goto(BASE + `/#/v/${forte.id}/history`); await page.click(".seg >> text=All");
  // Metrics: the overview, then "Total spent" opened up.
  await page.goto(BASE + `/#/v/${forte.id}/metrics`); await page.waitForSelector(".mx-kpi");
  await shot(page, "metrics", scheme);
  await page.click(".mx-kpi >> nth=1");
  await page.evaluate(() => document.querySelector(".mx-drill").scrollIntoView({ block: "start" }));
  await shot(page, "metrics-costs", scheme);
  await page.goto(BASE + "/#/shopping"); await shot(page, "shopping-list", scheme);
  await page.goto(BASE + `/#/v/${rc.id}/specs`); await shot(page, "vehicle-specs", scheme);

  // Door-jamb sticker: upload the sample and show the review screen.
  await page.goto(BASE + `/#/v/${rc.id}/sticker`);
  await page.setInputFiles("input[type=file]", join(here, "sample-sticker.png"));
  await page.click("button:has-text(\"Read stickers\")");
  await page.waitForSelector(".review-row", { timeout: 60000 });
  await page.evaluate(() => document.querySelector(".review-row").scrollIntoView({ block: "center" }));
  await shot(page, "sticker-review", scheme);

  // Adding an entry with photos.
  await page.goto(BASE + `/#/v/${rc.id}/entry/new`);
  await page.click(".type-opt >> text=Brake pads");
  await page.click(".seg >> text=Repair");
  await page.fill("input[placeholder='What was done?']", "Front pads and rotors replaced");
  await page.fill("input[inputmode=decimal]", "412.80");
  await page.setInputFiles("input[type=file]", [join(here, "sample-brake-before.jpg"), join(here, "sample-brake-after.jpg")]);
  await page.evaluate(() => document.querySelector(".previews").scrollIntoView({ block: "center" }));
  await shot(page, "add-entry", scheme);

  // Icon set.
  await page.goto(BASE + "/#/icons"); await shot(page, "icon-set", scheme);

  // Carfax review.
  await page.goto(BASE + `/#/v/${rc.id}/carfax`);
  await page.setInputFiles("input[type=file]", join(here, "sample-carfax.pdf"));
  await page.click("button:has-text(\"Read report\")");
  await page.waitForSelector(".carfax-visit", { timeout: 60000 });
  await page.evaluate(() => document.querySelector(".carfax-visit").scrollIntoView({ block: "start" }));
  await shot(page, "carfax-review", scheme);
  await ctx.close();
}
await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
