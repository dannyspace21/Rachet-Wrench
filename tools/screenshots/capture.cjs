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

  await page.goto(BASE + "/#/"); await shot(page, "dashboard", scheme);
  await page.goto(BASE + `/#/v/${rc.id}/history`); await shot(page, "vehicle-history", scheme);
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
  await page.waitForSelector(".review-row", { timeout: 60000 });
  await page.evaluate(() => document.querySelector(".review-row").scrollIntoView({ block: "start" }));
  await shot(page, "carfax-review", scheme);
  await ctx.close();
}
await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
