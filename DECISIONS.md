# Ratchet Wrench — Decisions

Every significant choice made while building Ratchet Wrench, and why. Written for a non-programmer: each entry says what was picked, what the alternatives were, and the reason.

---

## Architecture

### D1. Server language and framework: Python + FastAPI
- **Chosen:** Python 3.15 with FastAPI.
- **Why:** FastAPI is one of the most widely used, actively maintained Python web frameworks. It validates every request automatically and generates the browsable API documentation the brief asks for. Python lets the server reuse mature libraries for images (Pillow), PDFs (pypdf, fpdf2) and password hashing (argon2-cffi).
- **Alternatives:** Node.js/Express (more dependencies for the same features), Go (fewer ready-made PDF/image libraries).

### D2. Database: SQLite (one file)
- **Chosen:** SQLite, stored in the Docker volume at `/data/ratchet-wrench.db`.
- **Why:** For one household's vehicles, SQLite is fast and reliable, needs no separate database server, and backing up means copying one file. WAL mode, foreign keys and a busy timeout are turned on.
- **Alternative:** PostgreSQL. It's more to run, update and secure, and has no benefit at this scale. The code uses SQLAlchemy, so switching later is a small change.
- **Schema changes:** tables are created automatically on start. If a future version changes tables, add Alembic migrations then (not needed for v1).

### D3. Web app: plain HTML/CSS/JavaScript with **zero** third-party JS libraries
- **Why:** This removes the largest supply-chain risk in web development (hundreds of npm packages). There's no build step, and the strict Content-Security-Policy (`script-src 'self'`) can be kept. The UI is a small single-page app using hash routes (`#/vehicles/…`).
- **Only exception:** Swagger UI (`swagger-ui-dist`, Apache-2.0), used for the login-protected API docs page. It is pinned, lock-filed, audited and copied into the server, so it never loads from a CDN.

### D4. Login tokens: random opaque tokens stored as SHA-256 hashes (not JWT)
- **Why:** Tokens can be revoked instantly (logout, password change), no signing secret has to be managed, and a stolen database can't be used to log in. Tokens expire after `RW_TOKEN_TTL_HOURS` (default 30 days).
- **Web:** token in an `HttpOnly; Secure; SameSite=Strict` cookie, plus a double-submit CSRF token header on every change.
- **Android:** the token comes back in the login response and is stored encrypted with an Android Keystore key.

### D5. Password hashing: Argon2id (argon2-cffi defaults, RFC 9106 profile)
- **Why:** Argon2id is the algorithm OWASP recommends first. Old hashes are re-hashed automatically if the parameters change.

### D6. Login protection
- Per-account lockout: 5 wrong passwords lock the account for 15 minutes.
- Per-IP rate limit: 10 login attempts per minute.
- The same error appears for "unknown user" and "wrong password", and the server spends the same time on both, so attackers can't discover usernames.
- All limits can be changed in `.env`.

### D7. Users and access control
- Multiple accounts are supported. The first admin is created from `.env`, and admins can create more users.
- Every record has an `owner_id`. Every query is filtered by it, and someone else's record answers "404 Not found", never "403 Forbidden", so IDs can't even be confirmed.

### D8. Sync design (offline phone)
- UUIDs for all IDs. `created_at`/`updated_at`/`deleted_at` on every record. Soft deletes.
- `updated_at` = when the user edited it, used for "newest edit wins". `server_modified_at` = when the server stored it, used by `GET /api/sync?since=`. Keeping these separate means a phone with a wrong clock can't hide changes from other devices. Future timestamps are clamped to "now + 1 minute".
- `next_since` overlaps by 5 seconds, so writes that commit during a sync are never missed. Records are idempotent, so repeats are harmless.
- Photos upload separately in the background (`POST /api/attachments` with a phone-chosen UUID, which makes retries safe).

### D9. Money stored as whole cents (integers)
- **Why:** This avoids rounding errors (e.g. $0.10 + $0.20 ≠ $0.30 in floating point).

### D10. Maintenance vs. repair
- A single `entries` table with `kind = maintenance | repair`. They are shown with different colors and badges, can be filtered separately, and are totaled separately in the report.

## Features

### D11. VIN decoding through the server (not directly from the phone)
- The NHTSA vPIC API is called by the server, so the documented list of outbound connections stays in one place.
- A VIN is checked with the official check-digit formula first. European vehicles (including many motorcycles) don't always use the check digit, so a mismatch is a warning, not an error.
- Only fields NHTSA actually returns are offered, labelled "From VIN". Nothing is guessed. For example, NHTSA does not provide oil type, so it stays blank.
- **Paid spec API:** there's a plug-in interface (`SpecProvider` in `server/app/vin.py`) switched on by `RW_VIN_SPEC_PROVIDER`/`RW_VIN_SPEC_API_KEY`. None is bundled, because no specific provider was chosen.

### D12. Door-sticker text recognition
- **Android:** Google ML Kit on-device text recognition, *bundled* model (works offline, nothing leaves the phone).
- **Web uploads:** Tesseract OCR from Debian's official package, running inside the server container. Tesseract only ever receives a grayscale PNG that Pillow freshly regenerated, never the uploaded file, and it runs with memory/CPU limits (see SECURITY.md for the unfixed Debian CVEs).
- **Parsing:** one set of conservative rules, written in Python (`sticker_parser.py`) and mirrored in Kotlin (`StickerParser.kt`), tested against the same sample text. VINs must pass the check digit. Separate words are never glued together to "find" a VIN (a test caught this producing a fake VIN).
- Every field goes to a review screen (confirm, edit or reject) before it is saved as "From door sticker".
- Sticker photos are saved to the vehicle (category `sticker`).

### D13. Optional AI vision for stickers: Anthropic Claude API, off by default
- **Model:** `claude-opus-5-5` (the current default Claude model; changeable with `RW_AI_VISION_MODEL`).
- **Library:** Anthropic's official Python SDK (`anthropic`). It is used instead of hand-written HTTP because the official SDK is the supported, maintained way to call the API. It is imported only when the mode is used.
- **Requirements to turn it on:** `RW_AI_VISION_ENABLED=true` AND an API key. The app shows a clear warning that the photo is sent to a third party.
- **How it's called:** structured JSON output (a schema with only the sticker field names), low effort (simple transcription), and server-side refusal fallback (`fallbacks: "default"`) so a rare false-positive refusal is retried on a fallback model automatically.
- The answer is validated (known keys only, short strings) and still goes through the human review screen.

### D14. Carfax import
- The PDF text is extracted by pypdf in a separate, sandboxed process with memory, CPU and time limits and a page limit (60 pages for Carfax).
- Records are split at dates and typed by keyword. "Title issued / offered for sale"-style rows are proposed as *not suggested*.
- Costs are never invented (Carfax has none).
- The user reviews every row before saving. The original PDF is kept on the vehicle.

### D15. Manufacturer links (parts store and owner portal)
- These are a fixed list of links per make in `catalog/build_catalog.py`. There is no scraping.
- Where we couldn't confirm a parts store run by the manufacturer itself (Acura, Honda, Kia, Hyundai), the parts link falls back to the owner portal, never to a dealer site.
- Every vehicle can override both links.
- **Caveat:** the links could not be checked live from the build environment (no general web access). Verify them once and adjust the catalog if any have moved.

### D16. Reminder logic
- "Whichever comes first": due at `last mileage + N` miles OR `last date + M months`.
- "Due soon" means within 500 miles or 30 days.
- Logging a maintenance entry of the same type moves matching reminders forward automatically (only if that entry is newer).
- Month arithmetic is calendar-correct (Jan 31 + 1 month = Feb 28/29).

### D17. Alerts: no email
- **Android:** local notifications computed on the phone (WorkManager), which work offline.
- **Server:** optional ntfy push. A background task runs every `RW_REMINDER_CHECK_MINUTES`, and only if `RW_NTFY_URL` is set. Re-notifications are limited to once every 3 days per reminder and status.
- An optional ntfy container is included in `docker-compose.yml` (profile `ntfy`).

### D18. History report PDF: fpdf2
- fpdf2 is maintained, pure Python, and can draw our SVG icons directly.
- Built-in fonts are Latin-1 only, so other characters are replaced (e.g. "—" → "-"). This avoids bundling a font file.

### D19. Uploads
- **Allowed types:** JPEG, PNG, HEIC and PDF, detected by magic bytes. Pillow's decoder must agree with the detected type.
- **Images** are re-encoded from pixels only. This removes EXIF/GPS/comments and anything appended after the image. HEIC is converted to JPEG for browser compatibility, and PNG stays PNG.
- **Limit on image size:** 60 megapixels (decompression-bomb protection). Size limits come from `.env`.
- **PDFs** are opened only in the sandboxed helper. They are served as downloads (`Content-Disposition: attachment`) so a browser PDF viewer never runs embedded content.
- Files get random names (`<32 hex>.jpg`), are stored under `/data/uploads` (outside the web root) with 0600 permissions, and are never executed.
- Deleting an attachment erases the file immediately.

### D20. Seed data
- Your five vehicles. The model years for the Lexus cars and the Ducati weren't given, so they're left blank. Only the 2014 Kia Forte has a year.
- Specs were added only where certain for every model year (e.g. RC F = 5.0 L V8, Monster 696 = 696 cc twin), and even those are labelled "Unverified".
- One sample part number, marked "Sample data — verify fitment".
- The photos are simple drawn illustrations, not real photos.

## Supply chain & tooling

### D21. Exact pins and hash-locked installs
- `server/requirements.in` lists the direct dependencies. `server/requirements.txt` is the full lock with SHA-256 hashes, generated by `uv pip compile --generate-hashes`.
- Docker installs with `--require-hashes --only-binary=:all:`, so only those exact pre-built files are accepted and no build scripts run.
- **Why requirements.txt rather than .lock:** it is the standard pip-tools name, which OSV-Scanner, pip-audit and Dependabot all recognize. An earlier `requirements.lock` name was silently skipped by OSV-Scanner.

### D22. Docker base image: `python:3.15.2-slim-trixie`, pinned by digest
- This is the official Python image, the latest stable Python, and the minimal "slim" variant. `apt-get upgrade` runs at build time to pull in Debian security fixes released after the image.
- The container runs as uid 10001 with a read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, and memory and process limits (docker-compose.yml).

### D23. npm install scripts disabled
- `swagger-ui-dist` depends on `@scarf/scarf`, an install-time download-analytics package. `tools/vendor/.npmrc` sets `ignore-scripts=true`, so it never runs. The copied browser files contain no Scarf code (verified with grep).
- Swagger UI's online validator badge is switched off (`validatorUrl: null`). The page's CSP would block it anyway.

### D24. Security scanners and how they ran in the build sandbox
- **Scanners:** pip-audit, npm audit, OSV-Scanner (offline database mode), Trivy (image and config), Semgrep (security rule folders from the official `semgrep/semgrep-rules` repository) and Gitleaks.
- The build environment blocked semgrep.dev and api.osv.dev, so Semgrep used a local clone of the official rules and OSV-Scanner used its downloadable offline database. On your machine the script uses the online registries by default.
- Semgrep runs with `--metrics=off` (no telemetry).

### D25. GitHub Actions pinned to commit SHAs
- CodeQL (`security-extended` queries; Python, JavaScript, GitHub Actions and Kotlin) and Dependabot (pip, npm, Gradle, Docker, docker-compose, GitHub Actions).

## Phase 2 — logo, icons, web app

### D26. Icon set: original drawings in a Lucide-compatible style
- **78 type icons** (every catalog type, cars and motorcycles, plus "Custom / other") and **46 interface icons**, all generated by `icons/build_icons.py`.
- **Style:** 24 × 24 grid, 2 px round strokes, radius-2 corners, `currentColor`. These are the conventions of the open-source **Lucide** icon set (ISC license, https://lucide.dev). Every drawing here is original, made for this project, and no Lucide files were copied. The ISC license is permissive (use, modify and redistribute with the notice), so even a coincidentally similar simple shape (e.g. a check mark) raises no license issue.
- **Why paths only:** every shape is an SVG `<path>`, so the identical geometry converts 1:1 into Android VectorDrawables (Android vectors only support paths). The web app, the PDF report and the Android app all show the same icons.
- **One sprite file** (`icons/sprite.svg`) serves the web app, so any icon is one `<use href>` with no JavaScript.
- **Tests:** every type has a well-formed, unique icon. One test caught a real bug: titles containing "&" weren't escaped.

### D27. Logo
- An original flat design: a **ratchet and an open-end wrench crossed like an "X"**, in white on an orange (#EA580C) rounded tile.
  - The **ratchet** runs bottom-left to top-right: a round head with a toothed ratchet gear, a square drive, a direction switch, and a hang hole in the handle.
  - The **wrench** runs top-left to bottom-right: an open jaw at the top and a hexagonal box end at the bottom.
- The ratchet sits on top, with a thin orange "gap" outline where the tools overlap, so both shapes stay distinct even at 16 px (favicon size).
- The tile carries its own background, so the same mark works on light and dark pages, in browser tabs and as an Android adaptive icon. The monochrome themed icon keeps only the white shapes.
- The geometry is computed in `brand/build_brand.py` as an ordered list of layers and written as plain paths, so browsers, fpdf2 (PDF report) and Android all render it identically.

### D28. Web app structure
- `web/index.html` holds only the shell. `web/theme.js` applies the saved theme before first paint. `web/js/app.js` is a hash router, and `web/js/views/*` contain one file per area.
- **No `innerHTML` anywhere.** Every element is built with `createElement`/`textContent` through one helper (`h()`), which also refuses `javascript:` links. A test enforces no `innerHTML`, `eval` or `new Function` and no inline scripts or styles.
- **Theme:** Light / Dark / System, saved in the user's server settings (so it follows you to the phone) and mirrored in `localStorage` so the next page load paints correctly.
- **Review screens:** the door sticker, Carfax and VIN flows never save until you press Save. Every field can be kept, edited or rejected.
- **Spec fields** that can't apply to the vehicle type are hidden (e.g. chain size on a car), unless they already hold a value.
- **Photos:** a single picker handles camera, gallery and computer upload. Files are previewed locally and uploaded only after the entry is saved, so they can be linked to it.

## Phase 3 — Android app

### D29. Android stack
- **Language and UI:** Kotlin with Jetpack Compose (Material 3).
- **Data:** Room (on-phone SQLite) for offline storage, WorkManager for background sync and reminder checks, CameraX for the camera, and ML Kit for barcodes and text. Networking uses OkHttp, and JSON uses kotlinx.serialization.
- **No other libraries.** There's no image-loading library (thumbnails use OkHttp + `BitmapFactory` with an in-memory cache), no dependency-injection framework, and no analytics or crash reporting.
- **Versions:** SDK 29+ (Android 10). The app targets and compiles against API 37 (Android 17, `compileSdkMinor = 2`, required by the newest Compose libraries). Versions are pinned exactly. `app/gradle.lockfile` records every resolved library across all configurations, including build tools.
- **Repositories:** limited to `google()` (only `com.android.*`, `com.google.*` and `androidx.*` groups) and `mavenCentral()`. The Gradle Plugin Portal is not used.
- The Gradle wrapper (9.9.2) checks the distribution's SHA-256.

### D30. Offline-first sync
- Every screen reads from Room. Every change is written to Room first, marked `dirty`, and a sync is queued.
- A sync run does three things in order:
  1. **Push** dirty records (`POST /api/sync`). Only the fields the server accepts are sent; it rejects unknown fields.
  2. **Upload** queued photos with phone-made UUIDs, so retries are idempotent.
  3. **Pull** changes since the last bookmark. A newer unsent local edit is never overwritten.
- The phone mirrors two server rules offline: logging maintenance advances matching reminders, and higher mileage updates the vehicle.
- When the server merges a record into an existing one (e.g. a spec for the same field), the phone replaces its copy with the server's ID.

### D31. Login token in encrypted storage
- The token is encrypted with an AES-256-GCM key generated inside the Android Keystore and never exported.
- The `androidx.security:security-crypto` library was *not* used: it is deprecated, and a 30-line Keystore wrapper is simpler to audit.
- App data is excluded from cloud backup and device transfer (`allowBackup=false`, `dataExtractionRules`).

### D32. Cleartext HTTP policy
- The platform network security config trusts **system CAs only** and permits cleartext *at the platform level*. That is the only way to let a user opt in for a home server whose IP isn't known at build time, because Android's config can't express IP ranges.
- The app then enforces the real rule on **every request** (`UrlPolicy` + an OkHttp interceptor). `http://` is refused unless the user ticked "Allow plain HTTP on my home network" **and** the host is private (RFC 1918, loopback, Tailscale 100.64/10, link-local, `*.local`, `*.lan`, `*.home.arpa`).
- Requests can only go to the configured server, and redirects are never followed.
- Lint's `InsecureBaseConfiguration` warning is suppressed with this explanation. Unit tests cover the policy.

### D33. Permissions and exported components
- **Requested:** INTERNET, CAMERA, POST_NOTIFICATIONS.
- **Kept from WorkManager:** ACCESS_NETWORK_STATE, WAKE_LOCK and RECEIVE_BOOT_COMPLETED, needed for "sync when online" and to survive reboots.
- **Removed:** FOREGROUND_SERVICE.
- **Exported:** only `MainActivity` (the launcher) and WorkManager's `SystemJobService`, which is guarded by `BIND_JOB_SERVICE` so only Android can call it.
- **Also removed:** unused library components — the foreground service, WorkManager's diagnostics receiver and the profile-installer receiver.
- **No storage permission:** gallery access uses Android's photo picker, and the report is saved through MediaStore Downloads.

### D34. ML Kit telemetry blocked
- ML Kit bundles Google's `datatransport` logger, which would upload anonymous usage statistics.
- Excluding the upload library made R8 report that ML Kit references it directly, so removing it would risk a crash.
- Instead, the app manifest removes the `cct` backend's registration from `TransportBackendDiscovery`. The library then has nowhere to send events. The final APK manifest was checked: `CctBackendFactory` is absent.
- Bundled (offline) models are used for both barcode and text recognition, so no model downloads happen.

### D35. Vulnerable transitive libraries forced to patched versions
- OSV-Scanner found older Guava in the app's runtime classpath (GHSA-xxph-c9ww-hj94), plus commons-lang3, httpclient and Bouncy Castle in Android's lint tool.
- A `resolutionStrategy` forces patched versions: Guava 33.8.0, commons-lang3 3.22.0, httpclient 4.5.14 and Bouncy Castle 1.87.
- The rescan is clean.

### D36. Release signing
- To make side-loading possible out of the box, the release build is signed with the debug key unless you configure your own. The README explains how to create your own key; do that before sharing the APK with anyone.

### D37. Testing limits in the build environment
- The build sandbox has no hardware virtualization (no `/dev/kvm`), so an Android emulator could not run.
- What was verified: the app compiles (debug and release, with R8 shrinking), lint passes with zero errors, and unit tests pass. These cover the sticker parser — which is checked against the same sample as the server's parser — VIN rules, reminder logic and the HTTP policy.
- What was *not* verified: the app was not run on a device or emulator, so there are no Android screenshots. Please try it on your phone; see the README.
- **APK download:** the GitHub workflow `.github/workflows/android.yml` builds the APK on GitHub and offers it as a download.

## Phase 4 — final review and documentation

### D38. Final security review additions
- **Cross-site form posts:** a test now proves that `text/plain` and form-encoded posts are rejected. A malicious website can send those without a CORS preflight; FastAPI only accepts real JSON.
- **Deleted attachments:** a re-upload can no longer give a deleted attachment its file back. Found during review, fixed and tested.

### D39. Carfax kind guessing
- "Replaced" no longer marks normal wear items (tires, alignment, fluids, belts, bulbs, inspections…) as repairs. You can still change the kind of every row on the review screen.

### D40. Screenshots
- Screenshots are captured automatically by Playwright from the real web app with seed data, in light and dark mode (`tools/screenshots/run.sh`).
- The sticker and Carfax inputs are synthetic sample files generated by `make_samples.py`, so no real vehicle's documents are in the repository.

### D41. Docker Compose project name fixed to `ratchet-wrench`
- This keeps the data volume name (`ratchet-wrench_ratchet-data`) the same whatever folder you cloned into, so the backup command in the README always works.

## Automatic Android updates

### D42. Permanent signing key + GitHub Releases + Obtainium
- **Goal:** users shouldn't have to download each APK by hand.
- **Why a permanent key:** Android only installs an update signed with the same key as the installed app, and CI's debug keys change on every run.
- **How the key is handled:**
  - It's created once by `scripts/create-android-signing-key.sh` on the owner's own machine, using the official `keytool` inside a digest-pinned `eclipse-temurin` image with no network access: RSA-4096, valid 30 years, PKCS12, random 32-character password.
  - It's **not** generated in this build session, so the private key never passed through it.
  - GitHub holds it as four encrypted repository secrets.
- **The release workflow:**
  - **Runs only for `main`**, so Dependabot pull requests and other proposed changes never get access to the secrets.
  - **Uses the key safely:** it restores the key to a temporary file and deletes it right after the build.
  - **Checks before publishing:** it verifies the signature, refuses to publish a debug-signed APK, and fails clearly if the secrets are missing.
  - **Versions every release:** version code = run number × 1000, so it always increases, and the name is `1.0.<run>`.
  - **Publishes** a GitHub Release with the APK, `SHA256SUMS.txt` and the certificate fingerprint in the notes.
- *(Superseded by D48: the app now updates itself; Obtainium is no longer used.)* **Why Obtainium and not an in-app update checker:**
  - **Obtainium** is a widely used open-source app that installs and updates apps straight from GitHub Releases.
  - An **in-app checker** would add a new outbound connection from Ratchet Wrench itself, against the "documented outbound connections only" rule, and duplicate what Obtainium already does well.
- **Local builds** without the key still fall back to the debug key, so they install for testing, but they can't update a release-signed install.

## Phone-only, computer-only and phone ⇄ computer sync

### D43. Three ways to use it, one codebase
- **Phone only:** the Android app with no server.
- **Computer only:** a desktop app.
- **Phone + computer:** the phone syncs with the desktop app.
- **Home server (Docker):** still supported, unchanged.

The desktop app *is* the existing server plus web app, packaged to run on a PC. The phone therefore syncs with the computer through exactly the same, already-tested sync API it uses with a home server. There's no second sync system to maintain or secure.

### D44. Desktop app = server + web app packaged with PyInstaller
- **Why PyInstaller:** it's the most widely used tool to bundle a Python program with its own Python. The user installs nothing, needs no Docker, and double-clicks one app. Its license (GPL with a bootloader exception) allows distributing the apps it builds.
- **The launcher** (`desktop/launcher.py`, standard library only, using tkinter for the window):
  - stores data in the OS's normal app-data folder
  - runs the server on **127.0.0.1** by default, and opens the browser
  - shows a small window with **Open**, **Allow my phone to sync** and **Quit**
  - when phone sync is ticked, restarts the server on `0.0.0.0` and shows the computer's LAN addresses to type into the phone
  - starting the app a second time just opens the browser
- **Why a browser UI rather than a native window:** it reuses the existing web app unchanged, including its strict CSP. A native wrapper (Electron/Tauri) would add a large new dependency tree.
- **Cookies:** `RW_COOKIE_SECURE=false`, because the desktop app is plain HTTP on the computer and LAN. Use Tailscale for encryption over the network.
- **Platform fixes:**
  - Windows has no `resource` module, so CPU/memory caps apply only where available and the wall-clock timeout always applies.
  - Windows helper processes get `SYSTEMROOT`, which Windows programs can't start without.
  - In the packaged app, the PDF helper runs as `RatchetWrench --pdf-worker`, because there's no separate Python.
  - Shared files are found through `RW_ASSET_DIR`.
- **Builds:** CI builds Windows, macOS (Apple silicon) and Linux. It smoke-tests each build on its own OS, then publishes a GitHub Release `desktop-v1.0.N` with checksums. Desktop releases are **not** marked "Latest", so Obtainium keeps tracking the Android releases.
- **Python 3.14 for desktop builds (the Docker server uses 3.15):** on Windows, the Python 3.15 wheel of argon2-cffi-bindings uses the new "abi3t" stable ABI and needs `python3t.dll`. Standard Windows Python 3.15 doesn't ship that file, so the packaged app couldn't start. On 3.14 the long-established abi3 wheel is used. The same hash-locked requirements cover both versions.
- **Not code-signed:** Apple/Microsoft certificates cost money, so users see a one-time warning; DESKTOP.md explains it.
- **Intel Macs:** GitHub's macOS runners are Apple silicon. An Intel build can be added with a `macos-13` runner if needed.
- **Tesseract** isn't bundled: it's large and platform-specific. Sticker OCR on the desktop is optional (install Tesseract), and the phone reads stickers on-device anyway.

### D45. First-run setup page with a one-time code
- Docker users still set the first admin password in `.env`. Desktop users shouldn't have to edit files.
- The launcher creates a random 32-character code on every start (`RW_SETUP_TOKEN`) and opens `/#/setup?code=…`.
- `POST /api/auth/setup` creates the first account **only** if no account exists **and** the code matches (constant-time comparison). It's rate-limited.
- A server without a setup code (Docker) never offers the page.

### D46. Android phone-only mode
- **First launch:** the app asks "Use on this phone only" or "Connect to my computer or home server", and stores the choice in `mode`.
- **Phone-only:**
  - the sync engine does nothing
  - VIN decoding calls NHTSA directly from the phone (the same documented outbound host, sending only the VIN, never following redirects)
  - the history report is built on the phone with Android's built-in `PdfDocument`, with the same layout as the server's
  - AI sticker vision (a server feature) is hidden
- **Connecting later:** every local record is marked "needs upload" before the first sync, and photos taken on the phone are already queued, so nothing is lost.
- **Disconnecting:** you can keep the data on the phone (switching back to phone-only) or sign out and wipe it.
- Older installs that already have a login are treated as "connected", so nothing changes for them.
- **Tests:** a unit test checks the phone's NHTSA parsing against the server's test sample.

### D47. Lock files now cover every platform
- `server/requirements.txt` is now compiled with `--universal`, so the same hash-locked file works on Linux (Docker), Windows and macOS (desktop builds).
- The only new entry, `httpx2-jsfetch`, is marked for browser-Python (emscripten) only and is never installed on these platforms.

### D48. The Android app updates itself (Obtainium dropped)
- **What you asked for:** install once, then the app just updates itself. A third-party updater (Obtainium) was an extra step you didn't want.
- **How it works:** on opening (at most every 6 hours), the app reads GitHub's "latest release". The tag `v1.0.N` means version code N × 1000, which matches the release workflow.
  - **On Wi-Fi:** it downloads straight away.
  - **On mobile data:** it shows an "Update available" banner and waits for a tap, because each update is about 70 MB.
- **Checks before installing:** the SHA-256 must match `SHA256SUMS.txt`, the package name must match, the version must be newer, and the signing certificate must be identical. The app then installs through Android's `PackageInstaller` session API (no FileProvider needed).
- **Why Android still asks to confirm:** Android always asks the user to confirm installs from outside an app store. `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` (Android 12+) lets later updates go through without the prompt once the app is its own installer.
- **Security:**
  - **Outbound connection #4 (GitHub):** documented, and can be switched off in Settings. Only HTTPS and GitHub hosts are allowed, checked on every redirect. Debug builds never self-update.
  - **The install-result receiver** isn't exported, so the confirm screen it opens can only come from Android's installer.
  - **Permission:** `REQUEST_INSTALL_PACKAGES` is required for this.
- **Desktop app:** it doesn't update itself yet. You download new versions from Releases (see docs/DESKTOP.md).

### D49. Crash fix: keep ML Kit intact in release builds, and a check
- **The crash:** on a real phone, tapping **Scan barcode** crashed v1.0.9. Inspecting the APK showed that R8's full mode (the AGP 9 default) had removed the no-argument constructors of ML Kit's four `ComponentRegistrar` classes. ML Kit creates those by name when the scanner starts, so the app crashed. The door-sticker reader had the same defect.
- **Ruled out first:** code shrinking removing manifest classes, the removed telemetry backend (it fails gracefully), missing native libraries, and 16 KB page alignment.
- **Fix:** keep rules in `app/proguard-rules.pro` that keep ML Kit, including the classes its native code calls by name. This adds about 1 MB.
- **Regression guard:** `android/check_apk.py` runs in the release workflow and fails if any component that Android or ML Kit creates by name lacks its constructor. It was verified to flag the published v1.0.9 APK and to pass the fixed build.
- **Local crash details:** the app saves the stack trace of any crash *on the phone only* (`last_crash.txt` in its private storage). On the next launch it offers **Copy details**. Nothing is sent anywhere, so problems can be reported precisely without adding telemetry.

### D50. Crash fix: the release build had deleted every icon; screen tests added
- **The crash (v1.0.10):** opening the app with a vehicle saved, and opening **Add entry**, crashed. The release build's resource shrinker had removed 123 of the 125 icon drawables. The app looked icons up *by name* at run time (`getIdentifier`), which the shrinker can't see, so it treated them as unused. Drawing a missing icon (`painterResource(0)`) crashes. It also explains why the Settings gear was invisible.
- **Fix:**
  - `icons/build_icons.py` now also generates `IconResources.kt`, which maps each type and UI icon key to a direct `R.drawable` reference. The compiler checks every icon exists, and the shrinker can see they are used. No by-name lookups remain in the app.
  - A `res/raw/keep_icons.xml` (`tools:keep`) is a second safety net.
  - An unknown type falls back to the "Custom" icon, and an unknown UI icon draws nothing — neither can crash.
- **Regression guards:**
  - `check_apk.py` now also fails the release if any icon from `icons/types` or `icons/ui` is missing from the APK. It flags v1.0.10 (2 of 125 present).
  - New Robolectric screen tests (`ScreensTest.kt`) run the real app on the build machine and tap through it like a user:
    - add, view, edit and delete an entry;
    - every vehicle tab drawn with real data (odometer, specs, reminders, parts) and the history filters;
    - the door-sticker screen and edit vehicle;
    - Settings, theme switching and About;
    - reopening the app with saved data;
    - every catalog type has an icon.
  - Camera screens need real hardware, so they rely on the APK check.
  - The four small dialogs with a text box (Mileage, spec value, reminder, part) can't be opened under Robolectric: a bare Material dialog with one text field and none of our code never "settles" there. Their save code was reviewed by hand, and the data they save is inserted directly so the tabs are tested with it.
  - Robolectric needs `nativeruntime-dist-compat`, a large file that Maven Central rate-limited from the build container. CI downloads it normally; locally it was taken from Google's official Maven Central mirror and its SHA-1 checked against Central's.
- **Other fixes found in the review:**
  - **Delete entry** left the screen before the delete ran, which could cancel it. The screen now deletes first, then leaves.
  - The app's first screen is now decided once per launch instead of on every redraw.
  - Going back or opening a screen after a save or delete now always runs on the main thread (`onMain`). The navigation library throws when used off it, and the tests caught a save resuming on another thread.
  - The **Add vehicle** button now has an explicit screen-reader label: Material 3 1.5 doesn't pass its text on.
- **Settings & About:** the Garage top bar now shows a labelled **Settings** button (not just a gear). Settings ends with an **About** card: version, what the app is, exactly what it connects to, and links to the project page, issue tracker and security notes. The links open in the browser; the app fetches nothing itself.

### D51. Photos: keep them when the phone turns, and always the right way up
- **Bug 1 — photo disappeared on rotation:** turning the phone made Android rebuild the screen, and the list of photos just taken (also on the entry form and the door-sticker screen) lived only in short-lived memory.
  - **Fix:** `MainActivity` now handles orientation and screen-size changes itself (`configChanges`). Compose simply re-lays the screen out, so the camera stays connected and a photo being saved at that moment isn't lost.
  - As a second safety net, the photo lists use `rememberSaveable`, so they also survive other rebuilds (dark mode switch, language change, Android reclaiming memory).
- **Bug 2 — photos came out sideways or mirrored:** cameras save the pixels sideways plus an EXIF "rotate when showing" note. Android's basic decoder ignores the note, so thumbnails and the phone-made PDF were sideways. The server already handled it (`ImageOps.exif_transpose`).
  - **Upright at save time:** `camera/Photos.kt` turns new photos upright right after they are taken (and gallery picks when imported) and re-saves them without EXIF. Everything downstream then sees an upright image, and hidden details such as GPS location are dropped from the phone's copy.
  - **Upright at display time:** thumbnails and the PDF also honour the EXIF note, so photos taken with older versions show correctly too.
  - **No new dependency:** this uses Android's built-in `android.media.ExifInterface`.
- **Holding the phone with auto-rotate off:** an `OrientationEventListener` tells the camera how the phone is physically held, so landscape shots are saved landscape.
- **Camera screen:** now shows small previews of the photos taken so far.
- **Tests:** `PhotosTest` builds a two-colour JPEG stored sideways (and one mirrored) with an EXIF note, then checks:
  - the pixels end up upright, with the colours in the right places;
  - the note is removed;
  - upright photos are left untouched;
  - older photos display upright.

### D52. Better VIN scanning: higher resolution, printed-text reading, flashlight, zoom
- **Why scanning was poor:** the analysis stream was left at CameraX's default 640×480, which is meant for fast, simple codes. A VIN barcode (Code 39, 17 characters) is long with very thin bars, which blur together at that size. Now 1920×1080 is requested (closest available otherwise).
- **Reads printed VINs too:** every frame is checked as a barcode and then as text, using the ML Kit text recognizer already bundled for the door-sticker reader (no new dependency).
  - **Look-alike repair:** `VinFinder.fromText` drops spaces, dashes and a "VIN" label, checks every 17-character stretch against the check digit, and repairs a single look-alike misread (S/5, B/8, Z/2, G/6, D/0, T/7, A/4). A repair is used only if there is one clear winner. Ties are broken by the rule that a major manufacturer's last 5 characters are digits.
  - **Safeguards:** a check digit can't catch everything (two misreads can still pass about 1 time in 11). So a VIN read from text must be read identically in two frames *and* confirmed by the user ("Is this the VIN?") before it's used. Barcode reads with a valid check digit are still used immediately.
- **Controls:** flashlight toggle (if the phone has one), zoom slider, and tap-to-focus.
- **Tests:** `VinFinderTest` covers the barcode variants, labelled, spaced and multi-line text, O/0 and lowercase, look-alike repair, and that ordinary sticker text is not mistaken for a VIN.

### D53. Release notes in the app and on every release
- **What you asked for:** every Android update comes with high-level release notes, shown as the first pop-up after updating and also in Settings.
- **One source:** `android/app/src/main/assets/release_notes.json` (newest first, with `new` / `improved` / `fixed` lists).
  - It's bundled in the app, so it works offline and nothing extra is downloaded.
  - The release workflow puts the same notes at the top of the GitHub release page.
- **Can't be forgotten:** the first step of the release workflow (`android/release_notes.py check`) fails unless the top entry is exactly the version being built and isn't empty. The version is `1.0.<workflow run number>`, so the entry is written for the next run number.
- **In the app:**
  - **"What's new" pop-up:** appears once per version on the Garage screen.
    - A brand-new install doesn't see it, because there's nothing "new" yet.
    - Signing out doesn't make it reappear.
    - It never covers the crash-details notice.
  - **Settings → Release notes:** shows the installed version's notes, with earlier versions behind **Show earlier versions**.
- **Backfilled:** notes for 1.0.9–1.0.13 were written from the release history.
- **Tests:** `ReleaseNotesTest` checks the file loads, versions are unique and newest first, and every entry has content. It also checks the pop-up shows after an update and is remembered once dismissed. The Settings screen test checks the section is there.

### D54. Connecting the phone to the desktop app or Docker: fixes for what went wrong
- **What you hit:**
  1. **"Unable to parse TLS packet header":** the connect screen started pre-filled with `https://`, but the desktop app (8077) and the Docker server (8000) speak plain HTTP at home.
  2. **"failed to connect to 192.168.157.1:8077":** the desktop window showed a **virtual** adapter's address (VMware / VirtualBox / Hyper-V / WSL), on a different network from the phone (192.168.1.x).
  3. **"Not found" at sign-in:** the likely causes were addressed — a copied browser link with a path, a different server on that port, or the first sync stopping on one photo the server refused.
- **Phone fixes:**
  - **Address box:** starts empty. A bare home-network address gets `http://`, anything else `https://`. Anything after the port (a pasted browser link) is dropped.
  - **Plain HTTP only with consent:** for a home address, the app asks "Connect with plain HTTP?" instead of failing. The same question appears when an `https://` home address fails the TLS handshake. Plain HTTP is still used only after the user says yes, and only for private addresses, as the security rules require.
  - **Checks it's our server:** before signing in, the phone calls `GET /api/health`. A wrong port then gets a clear message.
  - **Plain-English errors:**
    - TLS mismatch.
    - Can't reach: checklist covering the app running, phone sync ticked, same Wi-Fi, Windows Firewall on Private networks, and the right port.
    - Unknown host.
    - Wrong password: notes that the desktop app and Docker keep separate accounts.
    - Not our server.
  - **Sign-in survives a first-sync problem:** if the login works, you're signed in and the sync retries in the background.
  - **One rejected photo doesn't stop the sync:** a 4xx for one photo skips it, and it stays queued for the next sync. Photos also wait until their vehicle has reached the server.
- **Desktop fix:** `lan_addresses()` finds the network card used for the internet route, using a UDP "connect" to the TEST-NET address 203.0.113.1, which picks the route without sending any packet. It lists that address first and separates out addresses that look virtual (x.x.x.1, 172.16–31.x, 192.168.56.x, 169.254.x), so they aren't mistaken for the right one. The phone section of the window explains that both devices' addresses should start the same way and mentions the Private-networks firewall prompt.
- **Docs:** `docs/DESKTOP.md` has an "If the phone can't connect" table.
- **Tests:**
  - LogicTest covers address completion and cleanup, offering `http://` only for home addresses, and the error explanations.
  - A screen test checks that a bare home address brings up the plain-HTTP question.

### D55. Carfax import rebuilt: reads the "Service History" page, one entry per piece of work, updates odometer and reminders
- **What went wrong:** the importer only understood the classic *Vehicle History Report* (rows of Date | Mileage | Source | comments). The carfax.com **Service History** page printed to PDF (Car Care) is laid out in two columns: a summary list on the left and a detailed card per visit on the right. Plain text extraction interleaves the columns, so every left-hand date started a bogus record with stray text. Mileage was hit and miss, services were lost, the type fell back to "Custom / other", the odometer wasn't updated, and DIY work was saved as a shop visit.
- **How it reads the page now:**
  - **Text with positions:** the sandboxed PDF helper has a new `layout` mode that returns each piece of text with its position (pypdf's own `visitor_text`; no new library). The importer keeps only the right-hand cards: everything right of the "Services Performed" headings, which only appear in cards.
  - **Card by card:** it reads each card's shop (the Carfax review link removed), Date, Odometer and every Services Performed line. "Self-Service (DIY)" becomes DIY with no shop name.
  - **Checked on a real report:** a real 16-page report gives all 46 visits with correct dates and mileage. The visits with no odometer reading in Carfax come through without a mileage rather than with a wrong one.
- **One entry per kind of work**, so each gets its own icon and can drive reminders. A rules table maps Carfax wording to catalog types (e.g. "Oil and filter changed" → Oil change, "Control arm bushing(s) replaced" → Suspension, repair).
  - **Merged within a visit:** lines meaning the same thing, e.g. front and rear brake pads.
  - **Routine checks:** become one Inspection entry per visit.
  - **"Vehicle serviced":** dropped when the visit lists the real work.
  - **Mileage services:** "N,000 mile service" and "Recommended maintenance" become **Scheduled service** (new type).
  - **Unknown lines:** keep Carfax's words as the title, never "Custom / other".
  - **Paperwork** (title issued, new owner): shown but not pre-selected.
  - **Same rules for both formats:** the classic report now goes through this grouping too.
- **New catalog types, with icons:** `scheduled_service` ("Scheduled service (e.g. 30,000-mile)") and `wheel_repair` ("Wheel repair / refinish").
- **Saving can also (options on the review screen):**
  - **Update the odometer:** one reading per visit, and the vehicle's mileage rises to the newest record. km vehicles get converted values.
  - **Update reminders:** for routine maintenance, existing reminders take "last done" from the newest record. Missing reminders are created with typical, editable intervals (oil 5,000 mi/6 mo, tire rotation 5,000/6, filters 15,000/12, brake fluid 24 mo, alignment 12 mo, …), with a note saying so. Repairs never get reminders, and an Inspection reminder is only created from a real safety/state inspection.
  - **Replace an earlier Carfax import** (pre-ticked when one exists): soft-deletes only `source = carfax` entries, then re-imports. Records you entered yourself are never touched. Re-importing without this skips anything already there, and the review screen marks those items "Already in your history".
- **Web review screen:** grouped by visit, with editable date, odometer and done-by/shop per visit. Each item shows its icon, type, kind and description. The summary after saving lists records, odometer and reminders.
- **History titles:** a "Custom / other" record is titled by its description on the web and Android.
- **Tests:**
  - A synthetic two-column Car Care PDF covers DIY detection, shop-name cleanup, each mapping above, the left column being ignored, reminders with the newest "last done", odometer, duplicate skipping and replacing.
  - The classic-format test is updated to the per-service grouping.
  - All 60 server tests pass.

### D56. Sync problems are shown and fixable; a tighter Carfax review screen
- **What you reported:** after the first connection, the phone and the computer "no longer see each other".
- **What testing showed:**
  - **The sync logic is sound.** A new opt-in `LiveSyncTest` runs the phone's real sync code against a real server, with your actual Carfax import loaded (110 entries on one vehicle). It covers pull, push of phone changes, a change made on the computer, an idle sync and a phone edit, and all of them work. Every server record reaches the phone. Logins also survive a server restart.
  - **So the failures are about the connection.** The likely causes: the computer's address changed (a new IP after a restart), the desktop app was closed, or the phone was signed in to the *other* server (desktop app and Docker are separate). Before 1.0.15, one photo the server rejected also made every sync fail.
  - **The phone hid all of this.** Errors showed as small grey text with a raw exception, and a missing login made sync silently do nothing.
- **Android 1.0.17:**
  - **Garage:** when a sync fails, a clear card says so, explains why in plain English (`UrlPolicy.explainSync`, which also covers an ended login, 401), shows the last good sync time, and offers **Try again** and **Fix in Settings**.
  - **Settings → Sync:** shows the last successful sync with time and the last problem. **Sync now**, **Change address** and **Sign in again** buttons.
  - **Change address:** checks the new address is a Ratchet Wrench server and that the login still works there, without signing out. A plain-HTTP home address needs the same explicit "allow" tick.
  - **No more silent sync:** sync never silently does nothing; a missing login is reported.
  - **Switching servers:** signing in to a different server marks everything on the phone for upload, so both sides match. Re-sending to the same server is harmless, because the newest copy wins.
  - **Test hook:** `SecureStore.tokenForTests` lets tests run without the Android Keystore. The app never sets it.
- **Web Carfax review:** each item is now one line (tick, icon, type, kind) with the description underneath. The separate title is gone because the type drop-down already names it, and the visit fields sit two per line on a phone.
- **Docs:** `docs/DESKTOP.md` covers "connected once but won't sync" and explains that the desktop app and Docker are separate servers.

### D57. One release with every download
- **What you asked for:** every release must contain **all** downloads — the Android APK and the desktop app for Windows, macOS and Linux — even when only one part changed. Separate releases aren't acceptable.
- **Before:** two workflows published two kinds of release. Android went out as `v1.0.N` (marked Latest), and the desktop app as `desktop-v1.0.N`, deliberately *not* Latest (an Obtainium-era choice, see D44). On the Releases page the desktop builds were buried and looked out of date, and desktop releases only happened when desktop-related files changed.
- **Now:** one workflow, "Release (Android + desktop)", in `.github/workflows/android.yml`.
  - **Steps:** check release notes → build and test Android and build and smoke-test the desktop app on all three systems, in parallel → publish one `v1.0.N` release.
  - **Contents:** `ratchet-wrench-1.0.N.apk`, `RatchetWrench-Windows.zip`, `RatchetWrench-macOS-AppleSilicon.zip`, `RatchetWrench-Linux.tar.gz` and one `SHA256SUMS.txt`, with a downloads table in the notes.
  - **Trigger:** any change to Android, server, web, desktop, catalog, icons or branding.
  - **All or nothing:** the publish step runs only if every build passed, and it refuses to publish if any file is missing.
- **Why the file keeps its name:** GitHub numbers runs per workflow file, and the version (`1.0.<run number>`) must keep rising for Android to accept updates. A new file would restart at 1.
- **Desktop workflow removed:** `desktop.yml` is deleted.
- **Phone updater unchanged:** it already picks the APK by name and its own line in `SHA256SUMS.txt`.

### D58. QR pairing, make/model lists, simpler phone Garage, Report a bug, motorcycle fixes
- **Connect a phone by QR code:**
  - **Server:** `POST /api/auth/pair` (signed-in users only) creates a one-time code. The code is random (192 bits), only its SHA-256 hash is stored in the new `pairing_codes` table, it expires in 10 minutes and works once. The QR code holds `ratchetwrench://pair?server=…&code=…`. `POST /api/auth/pair/claim` exchanges the code for an Android login, rate-limited like password logins.
  - **Where the QR code appears:** the web app's Settings (with an editable address, needed for Docker) and the desktop window. The desktop window acts in-process as the computer's account and turns on phone sync if it's off.
  - **Phone:** a QR-only scanner on the Connect screen and in Settings → Sync. Plain HTTP at home still asks first.
  - **New dependency: segno 1.6.6**, a pure-Python QR library with no dependencies. It's the latest release, OSV.dev lists no vulnerabilities, and it's hash-locked in `requirements.txt` (only that line changed).
- **Make / model lists:**
  - **Makes:** `catalog.make_names` lists 57 car makes and 28 motorcycle makes.
  - **Models:** they come from **NHTSA vPIC** (`GetModelsForMakeYear`) for the chosen make, model year and kind. Cars cover passenger car, MPV and truck types; motorcycles their own type. Results are cached.
  - **Who calls NHTSA:** the web form asks the server (`GET /api/vehicle-models`); the phone calls NHTSA directly. Only the make and year are sent. NHTSA was already a documented outbound connection (#1); its description was widened.
  - **Typing:** any make or model can still be typed. Checked live: a 2005 Acura gives MDX, NSX, RL, RSX, TL, TSX.
- **Vehicle photo on Android, and a real bug fixed:**
  - **Photo:** camera or gallery on the Add/Edit vehicle screen. It uploads as `vehicle_photo`, and the server links it to the vehicle.
  - **Bug fixed:** the edit screen kept "loaded" and the VIN-decoded specs in non-saveable state. Going to the barcode scanner (or camera) and back made the screen reload the saved vehicle *over what you'd typed*, and dropped the decoded specs. Both are now saveable, and saving starts from the stored vehicle.
  - **Web form:** keeps a draft in `sessionStorage` (this tab only, cleared on save), so a phone browser reloading after the camera loses nothing.
  - **Tests:** a screen test rebuilds the form and checks the typed values survive.
- **Simpler phone Garage:** only vehicles (photo or car/motorcycle badge, mileage, an Overdue / Due soon marker) and Add vehicle. The vehicle screen has a compact header (photo, mileage, Add entry on one line; small scrollable action chips) and a **Coming up** card with up to 3 overdue or due-soon items. The web and desktop dashboard are unchanged.
- **$0.00 instead of "—"** for entries without a cost (web, phone, both PDF reports).
- **Report a bug:**
  - **What it does:** a Settings section on the phone and the web opens a pre-filled GitHub issue (`.github/ISSUE_TEMPLATE/bug_report.yml`) in the browser. On the phone it includes the app version, Android version, device, mode, last sync problem and, if the app crashed, the first lines of the error.
  - **Privacy:** nothing is sent by the app. The person reviews and submits, so no GitHub credentials are ever built into the app.
- **Motorcycle links:** checked each manufacturer page directly.
  - **Fixed:** Ducati parts (was 404) and manuals, Kawasaki parts, Yamaha parts and manuals (yamaha-motor.com / yamahapubs.com), Harley-Davidson owners (serviceinfo.harley-davidson.com), and KTM added (parts finder and manuals). Suzuki parts falls back to the owners page.
  - **Separate motorcycle links:** makes that build cars and motorcycles get `moto_parts` / `moto_owners` (Honda → powersports.honda.com).
  - **Not verified:** powersports.honda.com blocks automated checks; BMW Motorrad's site didn't answer from the build environment, so it keeps the existing BMW link.
- **Motorcycle icon:** redrawn as a motorbike (engine block, seat and tank, raked fork). The old one looked like a bicycle.

### D59. Smart Shopping (Amazon, Walmart, Advance Auto Parts)
- **What it does:**
  - An optional **Find supplies** action on maintenance reminders, and a **Shop** action on saved parts.
  - Both build a search from the vehicle's specs and part numbers and open it in the retailer's own app or website.
  - Nothing is embedded, nothing is sent by Ratchet Wrench, and no record or expense changes.
- **One configuration:** `catalog/build_catalog.py` → `catalog.json`.
  - **`retailers`:** each has a key, name, icon and colour (no third-party logos are bundled, because they're trademarks), a `search_url` with a `{q}` placeholder, an `enabled` flag, and an `affiliate` `{param, value}` pair for future use. The affiliate is appended only when a value is set, and none is set.
  - **`shopping`:** maps a maintenance type to items, each with search templates in order of preference: `{part:TYPE}` (the saved part number), `{spec:KEY}` (optionally `|inches`), and `{ymm}`. The first template whose information is all known wins, so exact part numbers beat descriptions and nothing is invented. `needs` names the spec behind the tip shown when a general search was used. Types with no entry (inspection, rotation, alignment…) get no shopping action.
  - **Retailer addresses appear nowhere else in the code.**
- **Search builders:** `web/js/shopping.js` (web, desktop, Docker) and `android/.../domain/Shopping.kt` follow identical rules. They're tested against the **same** cases (`catalog/shopping_tests.json`), and link encoding matches `encodeURIComponent`, so web and Android produce identical links.
- **Where the buttons appear:**
  - **Reminders:** a cart button on reminder rows, a "Find supplies" button in the reminder form, and on the phone's Coming up card.
  - **Parts:** a shop button on each part.
  - **Specs:** the screen is unchanged; it only supplies the information.
- **Settings (this device only, not synced):** turn Smart Shopping off, turn individual retailers off, and pick a preferred retailer (listed first; the last one used becomes the preferred one). Kept in browser storage on the web and in the phone's preferences.
- **Unchanged:** no new database table or column, no API change, no sync change.
- **Tests:**
  - **Web:** `node --test` covers the shared cases, links, affiliate handling, preferences and catalog references. It runs in the release workflow's check job and from pytest when Node is present.
  - **Android:** `ShoppingTest` checks the shared cases; screen tests tap Shop on a part and Find supplies on an oil-change reminder.
  - **Server:** the catalog served by `/api/catalog` includes valid retailers and shopping rules.
- **Docs:** SECURITY.md lists the retailer links among the user-opened links.

### D60. Reminders: tap to mark as done
- **Problem:** tapping a reminder opened the schedule editor. Changing only the last-done *date* left the old last-done *mileage*, so a "whichever comes first" reminder stayed Overdue on the mileage side. Records saved as repairs didn't move reminders at all.
- **Now:** tapping a reminder opens it (Android: its own screen; web: a dialog) with **Mark as done** first: date = today, mileage = the vehicle's current mileage, "Also add it to the vehicle's history" on. It sets both last-done date and mileage and (by default) adds a maintenance record. **Edit schedule**, Find supplies and Delete are secondary.
- **Any new record** (maintenance or repair) with a reminder's type moves that reminder forward — server (`records.upsert`) and phone (`Repository.saveEntry`) alike, only if the record is newer than what the reminder knew.

### D61. Shopping list (synced)
- **What:** one consolidated list of supplies/parts to buy before doing maintenance. Lines come from a reminder ("Add to shopping list"), a saved part, or are typed in. Each line: description, quantity (1–999), OEM part number, specification, notes, optional vehicle, optional reminder (and the part it came from). Tick off when bought; remove one, or "Remove bought items".
- **Why a new table** (`shopping_items`, record kind `shopping`): the list must sync between devices, and nothing existing can hold it — parts are "what fits", not "what to buy", and entries are expenses. It uses the same synced-record rules as every other kind (owner scoping, newest edit wins, soft delete, removed with its vehicle). `vehicle_id` may be null (general items); `reminder_id`/`part_id` links that aren't the user's are dropped rather than rejecting the item.
- **What a reminder adds:** `listItemsFor` reuses the Smart Shopping recipes and records only what is KNOWN: a `{part:…}` or `*_part` spec becomes the OEM part number, other specs become the specification ("26 in / 18\""), nothing is guessed. Lines already waiting for the same reminder/part aren't added twice.
- **Searching a line:** part number + description, else spec + description, else year/make/model + description (`listItemQuery`) — opens the retailer's site exactly like Smart Shopping; no cart, no payment.
- **Not expenses, not completions:** ticking off never touches reminders or history; the cost is recorded as an entry when the job is logged. Tested on server, web and phone.
- **Phone database version 2:** Room auto-migration (new table only; nothing existing changes), verified by a test that opens a real version-1 database with data.
- **Older computers:** the phone sends shopping lines in a separate sync request; a computer still on an older version refuses it (422) and the lines just wait on the phone while everything else keeps syncing.
- **Shared rules:** `catalog/shopping_tests.json` gained `list_items` and `list_queries` cases checked by both the web (node) and Android tests.

### D62. Vehicle page order; specs that are parts
- **Sections** in the order they're used: Reminders, Parts, History, Specs (+ Documents on the web); a vehicle opens on Reminders. The Odometer section was removed — "Update mileage" / "Mileage" records a reading, and readings still sync and update the vehicle.
- **Reminders** are listed overdue → due soon → everything else (stable within each group), on web and phone.
- **One parts-site button**: the header chip on the phone was removed; the Parts tab keeps it.
- **Specs that are parts** (`spec_parts` in the catalog: oil type, oil filter / spark plug numbers, fluids, tire sizes, battery group, wiper sizes, chain): saving one adds or updates a part ("Added from Specs"); clearing/deleting it removes the part. The part's ID is a name-based UUID of the vehicle + spec key (MD5, identical to Java's `UUID.nameUUIDFromBytes`, checked against real Java), so the phone and the server create the SAME record and sync never duplicates it. A part already saved by hand with the same type and number isn't added again; one the user deleted stays deleted unless the spec changes. Existing specs are back-filled once at server start (and on the phone in phone-only mode).
