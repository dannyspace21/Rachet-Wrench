# Ratchet Wrench — Security

This page lists every security measure in Ratchet Wrench, the scanners used to check it, the final scan results, and how you can rerun the scans yourself. It's written for non-programmers. The technical reasoning behind each choice is in [DECISIONS.md](DECISIONS.md).

**Reporting a problem:** open a *private* security advisory on GitHub (repository → Security → Report a vulnerability) instead of a public issue.

---

## 1. Outbound connections (the complete list)

Ratchet Wrench contains **no telemetry, analytics, crash reporting, ads or "phone home" code**. Its only outbound connections are:

| # | Where to | When | What is sent | How to turn off |
|---|---|---|---|---|
| 1b | `https://api.nhtsa.gov` (US government recall database) | When a vehicle's history is opened, and on *Check for new* (Recalls) — server/desktop for the web app, the phone directly in phone-only mode | The vehicle's **year, make and model** only (never the VIN) | Turn off NHTSA lookups (`RW_NHTSA_ENABLED=false`); on the phone, leave year/make/model empty |
| 1 | `https://vpic.nhtsa.dot.gov` (US government vehicle database) | You press **Decode VIN** (the server or desktop app calls it; in phone-only mode the phone does). Also when you choose a make in **Add vehicle**, to suggest that make's models (the phone calls it directly; the web app asks its server). | The VIN, or the make name and model year | `RW_NHTSA_ENABLED=false` (server); on the phone, type the model instead |
| 2 | **Your own** ntfy server (`RW_NTFY_URL`) | A reminder becomes due (if you turned push alerts on) | Reminder title, vehicle name, miles/days left | Leave `RW_NTFY_URL` empty (default) |
| 4 | GitHub (`api.github.com`, `github.com`, GitHub's download servers) — **Android app only** | When you open the app (at most every 6 hours) or tap *Check for updates* | Nothing about you or your data: only standard requests to read the latest release and download the app | *Settings → App updates* → off |
| 5 | GitHub (`api.github.com`) — bug reports | Only when you press **Send report** in Settings → Report a bug | What you typed, plus (unless switched off) app version, device/browser and the start of a crash log — shown in the form before sending; filed as a **public** issue | Don't send a report; builds/servers without `RW_ISSUE_TOKEN` never send (they open GitHub in your browser instead) |
| 3 | `https://api.anthropic.com` (Claude AI) | Only if the server owner enabled AI vision **and** you choose it for a sticker scan after a warning | The sticker photo | Off by default (`RW_AI_VISION_ENABLED=false`) |

Everything else stays on your server and your phone:
- **The web app** loads nothing from the internet: no CDNs and no web fonts. The Content-Security-Policy would block it anyway.
- **The Android app** talks only to the server address you enter (enforced on every request), plus whatever links you choose to open in your browser (parts sites, owner portals).
- **ML Kit** text and barcode recognition run on the phone with bundled models. Google's usage logger that ML Kit includes has its upload backend removed (see §4).
- **Manufacturer links** are plain links you click. The server never fetches or scrapes them.
- **Smart Shopping** buttons (Amazon, Walmart, Advance Auto Parts) are links you choose to open: the app builds a search (for example "OEM 04152-YZZA1 oil filter" or "255/35R19 tires") and opens it in the retailer's own app or website. Ratchet Wrench itself sends nothing and uses no retailer account or API key. Affiliate tracking is **off**: it can only be switched on by the administrator for a program they are approved for, per retailer and per platform, in `catalog/affiliates.json` (see docs/AFFILIATES.md); affiliate links are then marked and disclosed, never change the search, and each device can turn them off. The retailer sees the search text (part numbers, sizes, year/make/model; never the VIN). Turn it off in *Settings → Smart Shopping*.
- **Report a bug** sends nothing until you press **Send report**. The form shows everything that will be sent: what you typed and (unless you switch it off) the app version, device or browser, and, if the app crashed, the first lines of the error. It is filed as a **public** issue on the project's GitHub (`api.github.com`); nothing about your vehicles, records or account is included. The phone sends it directly; the desktop/web app sends it through your own server. Temporary set-up: the key that files the issue ships inside the release builds and can only create issues on this repository (see docs/BUG-REPORTS.md). Builds without the key open a pre-filled GitHub page instead.

---

## 2. Server and web app

### Logins and sessions (OWASP A07: Identification & Authentication Failures)
- Passwords are hashed with **Argon2id** (argon2-cffi). The real password is never stored, and old hashes are upgraded automatically.
- Passwords need at least 10 characters and can't match the username.
- **Lockout:** 5 wrong passwords lock the account for 15 minutes (configurable).
- **Rate limit:** at most 10 login attempts per minute per IP address (configurable).
- "Unknown user" and "wrong password" give the same message and take the same time, so attackers can't discover usernames.
- **Login tokens:**
  - **Generation and storage:** 256-bit random tokens. Only their **SHA-256 hash** is stored, so a stolen database can't be used to log in.
  - **Expiry:** 30 days (`RW_TOKEN_TTL_HOURS`).
  - **Revocation:** a token stops working at logout, and changing your password signs out all other devices.
- **Connect a phone by QR code:**
  - **Making a code:** only a signed-in user can make one, in the web app's Settings or the desktop window, which acts as the account on that computer.
  - **The code:** a random 192-bit value that works **once** and expires after **10 minutes**. Only its SHA-256 hash is stored.
  - **Using a code:** the phone exchanges it for its own Android login, with the same per-IP rate limit as password logins.
  - **Plain HTTP:** the QR code holds the server address and the code. The phone still asks before using plain HTTP on the home network.
- **Web cookies:** `HttpOnly` (scripts can't read them), `Secure` (HTTPS only), `SameSite=Strict`.
- **CSRF protection:** every change made with a cookie must carry a matching `X-CSRF-Token` header (double-submit). The API also only accepts real JSON bodies, so cross-site form posts are rejected. Both are tested.

### Access control (OWASP A01: Broken Access Control)
- Every record has an owner, and every query is filtered by it.
- Another user's record answers **404 Not found**, so even its existence isn't confirmed.
- References are checked too: you can't attach an entry or a file to someone else's vehicle, move records between vehicles, or overwrite someone else's record through sync.
- Only admins can create users.
- Covered by `server/tests/test_access_control.py`.

### Input validation and injection (OWASP A03: Injection)
- Every request body is validated by strict schemas:
  - unknown fields are rejected
  - lengths and number ranges are limited
  - types must be known catalog types
  - IDs must be UUIDs
  - links must be `https://` (no `javascript:` or `data:`)
- The database is reached only through the SQLAlchemy ORM, which always uses parameterized queries. There's no raw SQL built from text.
- **The web app never builds HTML from text.** Every element is created with `createElement`/`textContent`. A test enforces no `innerHTML`, `eval` or `new Function`.

### Security headers and CORS (OWASP A05: Security Misconfiguration)
- **Every response carries:**
  - a strict **Content-Security-Policy**: `default-src 'self'`, no inline scripts, `frame-ancestors 'none'`, `object-src 'none'`
  - **HSTS**
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: no-referrer`
  - a restrictive `Permissions-Policy`
  - `Cross-Origin-Opener-Policy` and `Cross-Origin-Resource-Policy`
- **CORS is closed by default**: only the built-in web app may call the API (`RW_CORS_ORIGINS` to add others).
- The server doesn't advertise its software version (`--no-server-header`).
- The `/api/health` endpoint reveals nothing.
- **API documentation** (`/api/docs`) and the OpenAPI schema **require login**. Swagger UI is served from your server, not a CDN, and its online validator is disabled.
- Request bodies larger than the biggest allowed upload are cut off early.

### File uploads
- **Allowed types:** only JPEG, PNG, HEIC and PDF.
- **Real type check:** the type is detected from the file's **content** (magic bytes), not its name, and the image decoder must agree with it.
- **Size limits:** 20 MB per image and 40 MB per PDF (configurable). Images above 60 megapixels are refused (decompression-bomb protection).
- **Metadata removal:** images are **fully re-encoded from their pixels**. This removes **EXIF, including GPS location**, camera details, embedded thumbnails, comments and anything appended to the file. HEIC is converted to JPEG. Tests check that GPS and camera data are gone.
- **Untrusted PDFs** (Carfax reports, manuals) are opened only by a separate **sandboxed helper process**:
  - isolated Python (`-I`) with an empty environment
  - **1 GB memory cap, CPU-time cap and wall-clock timeout** (30 s)
  - page limits: 1,500 pages for manuals, 60 for Carfax
  - parsed with pypdf, which never runs embedded scripts
- **Storage and serving:**
  - files are saved under **random names** in `/data/uploads`, outside the web root, with owner-only permissions
  - files are only sent back to their owner, with `nosniff` and a sandboxing CSP
  - PDFs are always sent as downloads, never shown inline
  - nothing uploaded is ever executed
- Deleting an attachment erases the file from disk immediately, and a deleted attachment can't be revived.

### Door-sticker text recognition (web uploads)
- Tesseract OCR (from Debian's official packages) only ever receives a **grayscale PNG freshly regenerated by Pillow**, never your uploaded file. It runs with memory and CPU limits and a timeout.

### Containers and deployment
- **Base image:** official `python:3.15.2-slim-trixie`, **pinned by digest**. Debian security updates are applied at build time.
- **Python packages:** installed only from `server/requirements.txt` with **SHA-256 hashes enforced** (`--require-hashes`) and only as pre-built wheels (no build scripts run). pip/setuptools are removed afterwards.
- **The container runs as an unprivileged user** (uid 10001), never root.
- **Docker Compose adds:**
  - a **read-only root filesystem** (scratch space in a small `/tmp` tmpfs)
  - **all Linux capabilities dropped**
  - `no-new-privileges`
  - memory and process limits
- **Secrets** live only in `.env`, which git ignores. `.env.example` contains no real values.
- **HTTPS:** use Tailscale (recommended) or Caddy in front — see [docs/HTTPS.md](docs/HTTPS.md). **Never forward port 8000 on your router.**

### Desktop app (Windows, macOS, Linux)
- It's the **same server code**, with every protection above, packaged with PyInstaller.
- **By default it listens only on the computer itself (127.0.0.1).** Nothing else on the network can reach it.
- Only when you tick **"Allow my phone to sync"** does it listen on your home network, so your phone can sync. Logins, lockout, rate limiting and per-user access control still apply. That traffic is plain HTTP on your LAN; use Tailscale for encryption (see [docs/HTTPS.md](docs/HTTPS.md)).
- **First account:** the first account can only be created with a **one-time random setup code**. The app generates a new code each start and opens the setup page with it, so only the person at that computer can create the account. The setup page stops working once an account exists. Tests cover a wrong code, a weak password and a second attempt.
- **Data** stays in your user's app-data folder.
- **On Windows**, the PDF helper process and Tesseract run with the wall-clock timeout only, because Windows has no `resource` limits. They still run as separate processes with an almost-empty environment.
- **Not code-signed:** the desktop builds aren't signed with paid Apple/Microsoft certificates, so Windows SmartScreen and macOS Gatekeeper warn on first launch. Every release lists SHA-256 checksums so you can check a download.
- **Builds:** they're made on GitHub from hash-locked dependencies (`desktop/requirements-desktop.txt`), and each build is started once on its own operating system before it's published.

### Android phone-only mode
- Nothing leaves the phone except, when you press **Decode VIN**, the VIN, sent directly to NHTSA. That client is locked to `https://vpic.nhtsa.dot.gov` and never follows redirects.
- The history report is generated on the phone.
- When you later connect to a computer or server, everything on the phone is uploaded over the same authenticated sync.

---

## 3. Supply chain

- **Few dependencies, all well known.**
  - **Server:** FastAPI, Uvicorn, SQLAlchemy, python-multipart, argon2-cffi, Pillow, pillow-heif, pypdf, fpdf2, segno (QR codes for connecting a phone; pure Python, no dependencies), and anthropic (only used by the optional AI mode).
  - **Web app:** no JavaScript libraries at all, apart from Swagger UI on the docs page.
  - **Android:** AndroidX (Compose, Room, WorkManager, CameraX), ML Kit, OkHttp and kotlinx.serialization.
- **Exact names checked** against the official registries (no look-alike "typosquat" packages).
- **Vulnerability lookups:** each library was checked against vulnerability databases before use (OSV database, PyPI advisories, npm audit).
- **Exact versions pinned** and **lock files committed:**
  - `server/requirements.txt` (with hashes)
  - `tools/vendor/package-lock.json`
  - `android/app/gradle.lockfile`, covering every configuration including build tools
- **Gradle download sources:**
  - only Google's Maven and Maven Central, with each limited to its own package groups
  - the Gradle wrapper download is verified by SHA-256
- **npm install scripts are disabled** (`tools/vendor/.npmrc`). This blocks `@scarf/scarf`, an install-time analytics package that Swagger UI depends on. The copied Swagger files contain no Scarf code.
- **Older vulnerable libraries forced to patched versions:** some libraries and Android's lint tool pulled in Guava, commons-lang3, httpclient and Bouncy Castle versions with known vulnerabilities. The build forces the patched versions (see `android/app/build.gradle.kts`).
- **GitHub Actions are pinned to exact commit SHAs.**
- **Dependabot** watches pip, npm, Gradle, Docker, docker-compose and GitHub Actions.
- **CodeQL** (`security-extended`) scans Python, JavaScript, Kotlin and the workflows.

---

## 4. Android app (OWASP Mobile Top 10)

- **Credential storage (M1, M9):**
  - the login token is encrypted with **AES-256-GCM using a key generated inside the Android Keystore**, which can't be exported
  - app data is **excluded from cloud backup and device transfer**
  - signing out wipes the local database and token
- **Network communication (M5):**
  - only system certificate authorities are trusted (user-installed CAs are ignored)
  - `http://` is refused **on every request** unless you explicitly allow "plain HTTP on my home network" **and** the address is private (192.168/16, 10/8, 172.16/12, Tailscale 100.64/10, loopback, `*.local`, `*.lan`, `*.home.arpa`)
  - requests can only go to the configured server
  - redirects are not followed
- **Permissions and components (M8):**
  - **permissions:** only INTERNET, CAMERA (requested when you open a scanner), POST_NOTIFICATIONS and REQUEST_INSTALL_PACKAGES (for the app's own updates), plus WorkManager's network-state, wake-lock and boot permissions for background sync
  - **no storage, location, contacts or microphone access**; the gallery uses Android's photo picker
  - **only two exported components:** the launcher screen and WorkManager's job service (callable only by Android itself). Unused library components were removed.
- **Privacy (M6):**
  - text and barcode recognition run on the phone
  - **ML Kit's Google usage-logging upload backend is removed** from the app manifest (verified in the final APK)
  - AI vision is optional, off unless the server owner enables it, and shows a third-party warning before anything is sent
- **Self-updates:**
  - the app installs its own updates through Android's PackageInstaller, using the `REQUEST_INSTALL_PACKAGES` permission (you allow "Install unknown apps" once)
  - **before installing, it requires** that the download matches the SHA-256 checksum published with the release, has the same package name and a newer version, and is **signed with exactly the same certificate as the installed app**; anything else is deleted
  - **downloads** come only over HTTPS from GitHub hosts, which are allow-listed and re-checked on every redirect
  - the install-result receiver isn't exported
- **Release signing and updates:**
  - releases are signed with one permanent RSA-4096 key
  - the key exists only as encrypted GitHub secrets plus your offline backup, never in the repository
  - the release workflow runs only for `main` (never for pull requests, so secrets can't be exposed), refuses to publish a debug-signed APK, and lists the certificate fingerprint and SHA-256 checksums with every release
  - Android itself rejects any update signed with a different key
- **Phone-only mode:** see the end of §2. The sync engine does nothing unless you've connected.
- **Code hardening (M7):** release builds are shrunk and obfuscated with R8, and Android lint is run with errors treated as failures.

---

## 5. Automated tests that cover security

`server/tests/` — **57 tests**. Security-specific ones include:
- **Logins:** Argon2 storage, same response for unknown users, lockout, per-IP rate limit, expired and garbage tokens, logout revocation, password change signing out other devices
- **Request protections:** CSRF, rejection of cross-site form posts, security headers, closed CORS, login-protected docs, unknown fields rejected, `javascript:` links rejected, oversized requests
- **Access control:** another user's vehicles, entries, files, report and sync can't be read or changed
- **Uploads:**
  - type spoofing (script/exe/php/gif disguised as images or PDFs)
  - EXIF/GPS removal, PNG text chunks removed, HEIC conversion
  - size limit and decompression bomb
  - malformed PDFs, the PDF page limit, and safe download headers
  - deleted files are erased and can't be revived

`android/app/src/test` — the HTTP-only-on-home-network policy, VIN validation, reminder logic, and the sticker parser (the same sample as the server's tests).

---

## 6. Scanners and final results

Final run: **25 November 2026**, on the commit that added this file.

| Scanner | What it checks | Result |
|---|---|---|
| **pip-audit** | Server Python packages (`requirements.txt`, dev requirements) against the PyPI/OSV advisory database | ✅ No known vulnerabilities |
| **npm audit** | Vendored Swagger UI (`tools/vendor`) | ✅ 0 vulnerabilities |
| **OSV-Scanner** | All lock files: Python (32 + 11 packages), npm (2), **Android Gradle (all configurations)** | ✅ No issues found (after forcing patched Guava, commons-lang3, httpclient, Bouncy Castle) |
| **Trivy** (image) | The Docker image: Debian packages + Python packages | ✅ **0 vulnerabilities with a fix available.** 424 Debian findings have no fix yet (see §7) |
| **Trivy** (config) | Dockerfile misconfigurations | ✅ 0 misconfigurations |
| **Semgrep** | Security rules for Python, FastAPI, SQLAlchemy, JavaScript (browser), Kotlin, Android, Dockerfile, docker-compose, GitHub Actions, HTML, secrets | ✅ 0 findings. Six lines were reviewed and annotated with the reason (two fixed-argument subprocess calls, two fixed-URL HTTP calls, the launcher activity, and the deliberate `apt-get upgrade`) |
| **Gitleaks** | Secrets in the full git history | ✅ No leaks found |
| **Android lint** | Android-specific issues incl. security checks | ✅ 0 errors |

Findings fixed during development:
- Semgrep flagged a dynamic function lookup in the web app (replaced with an allow-list) and an XML-module import (removed).
- Semgrep flagged the Dockerfile for `pipefail` and `apt-get clean` (both added).
- OSV-Scanner found 9 vulnerabilities in transitive Android and build-tool libraries (all forced to patched versions).
- Tests caught a VIN-finder weakness (words glued together could fake a valid VIN) and unescaped "&" in icon titles (both fixed).

---

## 7. Known issues with no fix available

Trivy reports **424 vulnerabilities in Debian 13 ("trixie") packages for which Debian has not yet released a fix**: 1 critical, 83 high, 143 medium, 173 low and 24 unknown. Every Debian-based image has a list like this. **None of them has a fixed version available**; the image is rebuilt with `apt-get upgrade`, and Dependabot proposes base-image updates as fixes appear.

| Package(s) | Highest severity | Why it's there | Risk for Ratchet Wrench | Mitigation |
|---|---|---|---|---|
| `tesseract-ocr`, `libtesseract5` (6 high) | High | Reads text on sticker photos uploaded from the web | **Most relevant**: it processes image data that came from a user | Tesseract never sees the upload, only a grayscale PNG regenerated by Pillow, so malformed input can't reach it. It runs with a 1 GB memory cap, CPU limit and 30 s timeout, as a non-root user in a read-only container with no capabilities. Only logged-in users can reach it. You can disable it with `RW_OCR_ENABLED=false`; the Android app reads stickers on the phone instead. |
| `libxml2` (1 critical, 7 high) | Critical | Pulled in by Debian system libraries | Low: Ratchet Wrench never parses XML with libxml2 (pypdf and fpdf2 don't use it; uploaded SVGs aren't accepted) | Not reachable from user input; container hardening as above |
| `libtiff6` (2 high), `libx11-6`, `libxrender1` | High | Dependencies of Tesseract's image library (Leptonica) | Low: TIFF and X11 code paths aren't used (only our own PNGs are read) | Same as Tesseract |
| `openssl`, `libssl3t64`, `libcurl4t64`, `libexpat1`, `libsqlite3-0` | High | Base system libraries (Python links OpenSSL, SQLite and expat) | Low to moderate: SQLite only stores our own validated data. expat isn't fed user XML. OpenSSL/curl handle outbound HTTPS only to NHTSA, ntfy and Anthropic | Keep the image rebuilt; outbound connections go only to the three documented hosts |
| `util-linux` family (`libblkid1`, `libmount1`, `mount`, `login`, `bsdutils`, …), `ncurses`, `perl-base`, `libsystemd0`, `libudev1`, `libacl1` | High | Part of every Debian base image | Very low: these command-line/system tools aren't used by the app, and there's no shell or login in the container | Non-root, read-only, no capabilities |

The Android app and the Python/npm dependencies have **no** known unfixed vulnerabilities.

**What you should do:** rebuild the server image every month or two (`docker compose build --pull && docker compose up -d`) to pick up Debian fixes as they're released, and keep the server reachable only through Tailscale or HTTPS.

---

## 8. Rerunning the scans yourself

Everything is in one script: [`scripts/security-scan.sh`](scripts/security-scan.sh). Results go to `scan-results/`.

1. **Install the tools once** (on the server or any Linux/Mac computer with Docker):
   ```bash
   python3 -m pip install --user pip-audit semgrep
   go install github.com/google/osv-scanner/v2/cmd/osv-scanner@latest
   go install github.com/zricethezav/gitleaks/v8@latest
   # Trivy runs from its official Docker image; nothing to install.
   ```
2. **Build the image** (from the project folder):
   ```bash
   docker compose build
   ```
3. **Run all scans:**
   ```bash
   scripts/security-scan.sh
   ```
   It prints `ALL SCANS PASSED`, or the names of the scanners that found something. Useful options:
   - `SKIP_TRIVY=1` — skip the Docker image scan
   - `OSV_OFFLINE=1` — use OSV-Scanner's downloadable database instead of the online API
   - `SEMGREP_RULES_DIR=/path/to/semgrep-rules` — use a local copy of the Semgrep rules instead of the online registry

   Semgrep runs with `--metrics=off`, so it sends no usage data.
4. **Android checks:**
   ```bash
   cd android && ./gradlew lintRelease testDebugUnitTest
   ```

On GitHub, **CodeQL** runs on every push and weekly. **Dependabot** alerts appear under the repository's *Security* tab. Make sure these are switched on under *Settings → Code security*:
- **Dependabot alerts**
- **Dependabot security updates**
- **Code scanning**
