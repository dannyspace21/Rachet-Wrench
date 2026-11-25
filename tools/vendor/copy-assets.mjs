// copy-assets.mjs — copies the two Swagger UI files we need from the pinned
// npm package into server/app/static/docs/vendor so the API docs page loads
// everything from YOUR server (no CDN, no outside network calls).
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, "node_modules", "swagger-ui-dist");
const dest = join(here, "..", "..", "server", "app", "static", "docs", "vendor");
mkdirSync(dest, { recursive: true });
for (const f of ["swagger-ui-bundle.js", "swagger-ui.css", "LICENSE"]) {
  copyFileSync(join(src, f), join(dest, f));
  console.log("copied", f);
}
