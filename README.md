<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/logo-dark.svg">
    <img src="brand/logo-light.svg" alt="Ratchet Wrench" width="360">
  </picture>
</p>

<p align="center"><strong>A private, self-hosted maintenance &amp; repair log for your cars and motorcycles.</strong></p>

---

## What it does

Ratchet Wrench keeps the complete history of your vehicles **on your own devices**: no accounts with outside companies, no ads, no tracking. I don't want your information and you don't want anyone wanting your information. Use it any of these ways:

| How | What you need | Guide |
|---|---|---|
| 📱 **Phone only** | The Android app — choose "Use on this phone only" | [Installing the Android app](#installing-the-android-app) |
| 💻 **Computer only** | The desktop app for Windows, macOS or Linux; no server or Docker | [docs/DESKTOP.md](docs/DESKTOP.md) |
| 📱 ⇄ 💻 **Phone + computer, synced** | Both of the above; tick "Allow my phone to sync" in the desktop app | [docs/DESKTOP.md § Sync](docs/DESKTOP.md#3-sync-with-your-phone-optional) |
| 🏠 **Home server** (always on, for the whole family) | A home server with Docker | [Running the server](#running-the-server-on-your-home-server-docker) |

You can start with the phone alone and connect it to a computer or server later: everything you've added is copied over.

**Manage your vehicles**
- **Vehicles:** cars and motorcycles with year, make, model (pick from NHTSA's lists for that make and year), nickname, VIN, mileage and a photo.
- **VIN:** type it, or **scan the barcode** with the phone camera (only what's inside the on-screen box is read). The free US government (NHTSA) decoder fills in the year, make, model and every spec it knows. If NHTSA lists several possible trims, you pick yours.
- **Door-jamb sticker scan:** photograph the stickers on the driver's door and door pillar — the tire placard **and** the label with the VIN. The phone reads them offline and finds the VIN, tire sizes and pressures, paint and trim codes, build date and the weight ratings (**GVWR** and front/rear **GAWR**, in lb). You **confirm, edit or reject every value** before it's saved.
- **Specs** — everything about the vehicle in one list, starting with VIN, year, make, model and trim. Ways to fill it in:
  - **Decode VIN:** engine and engine model, horsepower, transmission, drive, body, curb weight, wheelbase, safety equipment, plant.
  - **Door sticker:** tires, pressures, paint, weights (above).
  - **Fill from owner's manual:** opens the maker's owner website to find the manual, then reads it from a **PDF**, from **links** (one PDF or several web-manual pages) or from **pasted text**. It finds oil grade, coolant, transmission and brake fluid, capacities, GVWR and more, and shows where each value came from. Empty fields the manual usually covers say "type it in or import from owner's manual": tap to type the value, or choose **Import from owner's manual** in the box.
  - **Paint colour:** a known paint code (Lexus, Toyota, Kia) suggests the colour name.
  - Every value is labelled **From VIN**, **From door sticker**, **From owner's manual**, **Entered by me** or **Unverified**. Nothing is guessed, and you confirm everything.
- **Parts** with OEM part numbers and a link to the manufacturer's official parts site. Specs that are parts (oil type, oil filter number, tire sizes, battery group, wiper sizes…) are **added to Parts automatically**.
- **Maintenance and repair history:** date, mileage, type (each with its own icon), description, parts, cost, shop or DIY, notes, and any number of photos and receipts. Costs with nothing entered show $0.00.
- **Reminders** by mileage, by date, or **whichever comes first**. A vehicle opens on its reminders, **overdue first**. Tap one and **Mark as done**: it fills in today's date and mileage, adds the job to the history, and the status changes from Overdue to OK. Any matching record you log also updates the reminder.
- **Recalls:** the History tab lists every safety recall NHTSA has for your vehicle's year, make and model — the problem, the risk and the fix — marked **Open**, **Done** or **Doesn't apply**. A recall in your history (for example from a Carfax import) counts as done. "Check my VIN" opens NHTSA's own page for your exact car.
- **Alerts:** phone notifications that work offline (and "Check reminders now" in Settings), plus optional push alerts through your own ntfy server. Never email.
- **Carfax import:** upload a Carfax PDF; every visit is split into what was done, DIY vs shop is kept, and you review everything before saving.
- **Owner's manual:** link to the owner portal or upload the PDF. **History report PDF** with everything in date order, costs, totals and photos.

**Analyze**
- **Metrics:** each vehicle's history as charts — by category, by service and by year, total spent, how often each service is done and the miles and months between them. **Every number, slice, bar and year is clickable** and filters everything else, and the list at the bottom always shows the exact records behind the numbers (the total row equals the tile).

**Shop (optional)**
- **Smart Shopping:** "Find supplies" on reminders and "Shop" on parts open a search at Amazon, Walmart or Advance Auto Parts built from your specs and part numbers (exact OEM numbers first; nothing invented). Turn it off or choose retailers in Settings. Affiliate links are **off**; the app is ready for approved programs, disclosed and optional ([docs/AFFILIATES.md](docs/AFFILIATES.md)).
- **Shopping list:** collect what you need before a job — from a reminder, a saved part, or typed in — with quantity, OEM part number, spec and notes. Search any item, tick it off when bought. Never creates expenses or marks maintenance done by itself.

**Everything else**
- **Connect your phone by QR code:** the desktop app (or Settings → Connect a phone on the web) shows a one-time QR code; scan it in the Android app. A small banner offers **Resync** when the phone can't reach the computer (and can be dismissed). *Settings → Sync* has Sync now, Change address, Reconnect with QR code and Sign in again.
- **Report a bug** from inside the app: describe the problem, see exactly what will be sent, and press Send — it goes straight to the project's GitHub issues ([docs/BUG-REPORTS.md](docs/BUG-REPORTS.md)).
- **"What's new"** after every update (including versions you skipped), and **Light, Dark or System** theme.

## Screenshots

**Android app** — rendered from the real app screens with sample data (`PhoneScreenshots` test).

| Garage | Reminders | Recalls | Metrics | Parts |
|---|---|---|---|---|
| ![Garage](docs/screenshots/phone-garage.png) | ![Reminders](docs/screenshots/phone-reminders.png) | ![Recalls](docs/screenshots/phone-recalls.png) | ![Metrics](docs/screenshots/phone-metrics.png) | ![Parts](docs/screenshots/phone-parts.png) |

**Desktop / web app** — captured automatically from the running app with sample data (`tools/screenshots/run.sh`). The sticker, Carfax files and the Forte's long service history are synthetic samples; the recalls are real NHTSA data.

| | Light | Dark |
|---|---|---|
| **Garage** | ![Garage, light](docs/screenshots/dashboard-light.png) | ![Garage, dark](docs/screenshots/dashboard-dark.png) |
| **Vehicle — reminders (overdue first)** | ![Reminders, light](docs/screenshots/vehicle-reminders-light.png) | ![Reminders, dark](docs/screenshots/vehicle-reminders-dark.png) |
| **Tap a reminder — Mark as done** | ![Mark as done, light](docs/screenshots/reminder-done-light.png) | ![Mark as done, dark](docs/screenshots/reminder-done-dark.png) |
| **Metrics** | ![Metrics, light](docs/screenshots/metrics-light.png) | ![Metrics, dark](docs/screenshots/metrics-dark.png) |
| **Metrics — where the money went** | ![Costs, light](docs/screenshots/metrics-costs-light.png) | ![Costs, dark](docs/screenshots/metrics-costs-dark.png) |
| **History — recalls** | ![Recalls, light](docs/screenshots/recalls-light.png) | ![Recalls, dark](docs/screenshots/recalls-dark.png) |
| **History** | ![History, light](docs/screenshots/vehicle-history-light.png) | ![History, dark](docs/screenshots/vehicle-history-dark.png) |
| **Parts (specs added automatically)** | ![Parts, light](docs/screenshots/vehicle-parts-light.png) | ![Parts, dark](docs/screenshots/vehicle-parts-dark.png) |
| **Specs with source labels** | ![Specs, light](docs/screenshots/vehicle-specs-light.png) | ![Specs, dark](docs/screenshots/vehicle-specs-dark.png) |
| **Shopping list** | ![Shopping list, light](docs/screenshots/shopping-list-light.png) | ![Shopping list, dark](docs/screenshots/shopping-list-dark.png) |
| **Door-jamb sticker scan — review** | ![Sticker review, light](docs/screenshots/sticker-review-light.png) | ![Sticker review, dark](docs/screenshots/sticker-review-dark.png) |
| **Adding a repair with photos** | ![Add entry, light](docs/screenshots/add-entry-light.png) | ![Add entry, dark](docs/screenshots/add-entry-dark.png) |
| **Carfax import — review** | ![Carfax review, light](docs/screenshots/carfax-review-light.png) | ![Carfax review, dark](docs/screenshots/carfax-review-dark.png) |
| **Icon set** | ![Icon set, light](docs/screenshots/icon-set-light.png) | ![Icon set, dark](docs/screenshots/icon-set-dark.png) |

**History report (PDF)**

| Page 1 | Page 2 |
|---|---|
| ![History report page 1](docs/screenshots/history-report-1.png) | ![History report page 2](docs/screenshots/history-report-2.png) |

---|---|---|
| **Garage dashboard** | ![Dashboard, light](docs/screenshots/dashboard-light.png) | ![Dashboard, dark](docs/screenshots/dashboard-dark.png) |
| **Vehicle page — history** | ![Vehicle history, light](docs/screenshots/vehicle-history-light.png) | ![Vehicle history, dark](docs/screenshots/vehicle-history-dark.png) |
| **Vehicle page — specs with source labels** | ![Specs, light](docs/screenshots/vehicle-specs-light.png) | ![Specs, dark](docs/screenshots/vehicle-specs-dark.png) |
| **Door-jamb sticker scan — review screen** | ![Sticker review, light](docs/screenshots/sticker-review-light.png) | ![Sticker review, dark](docs/screenshots/sticker-review-dark.png) |
| **Adding a repair with photos** | ![Add entry, light](docs/screenshots/add-entry-light.png) | ![Add entry, dark](docs/screenshots/add-entry-dark.png) |
| **Icon set (78 types)** | ![Icon set, light](docs/screenshots/icon-set-light.png) | ![Icon set, dark](docs/screenshots/icon-set-dark.png) |
| **Carfax import — review screen** | ![Carfax review, light](docs/screenshots/carfax-review-light.png) | ![Carfax review, dark](docs/screenshots/carfax-review-dark.png) |

**History report (PDF)**

| Page 1 | Page 2 |
|---|---|
| ![History report page 1](docs/screenshots/history-report-1.png) | ![History report page 2](docs/screenshots/history-report-2.png) |

> **Android screenshots:** the build machine couldn't run an Android emulator (no hardware virtualization), so there are no phone screenshots yet. The Android app has the same screens and colors. See [Installing the Android app](#installing-the-android-app).

---

## Using it on your computer (desktop app)

Download the desktop app for Windows, macOS or Linux from the **Releases** page, unzip it and open it. There's nothing else to install. Step-by-step instructions, including syncing with your phone, are in **[docs/DESKTOP.md](docs/DESKTOP.md)**.

## Running the server on your home server (Docker)

You need a computer that stays on (a home server, NAS or mini PC) with **Docker** and **Docker Compose** installed. On Linux, follow Docker's official guide: <https://docs.docker.com/engine/install/>.

1. **Download Ratchet Wrench.** Open a terminal on the server and run:
   ```bash
   git clone https://github.com/dannyspace21/Ratchet-Wrench.git
   cd Rachet-Wrench
   ```
2. **Create your settings file:**
   ```bash
   cp .env.example .env
   nano .env
   ```
   - Set **`RW_ADMIN_PASSWORD`** to a long password you don't use anywhere else. This creates your first login.
   - Trying it on your home network without HTTPS first? Set `RW_COOKIE_SECURE=false` (switch it back once you add HTTPS).
   - Optional: `RW_SEED_SAMPLE_DATA=true` loads the sample vehicles so you can explore.
   - Save with **Ctrl+O**, Enter, then **Ctrl+X**.
3. **Start it:**
   ```bash
   docker compose up -d --build
   ```
   The first build takes a few minutes.
4. **Open it** in a browser on the same network: `http://SERVER-IP:8000`. Find the server's IP with `hostname -I`. Sign in as `admin` with your password.
5. **Set up HTTPS and remote access** — strongly recommended. Follow **[docs/HTTPS.md](docs/HTTPS.md)**; the easiest option is Tailscale. **Never forward port 8000 on your router.**

**Everyday commands** (run them in the `Rachet-Wrench` folder):

| What | Command |
|---|---|
| Stop | `docker compose down` |
| Update to the latest version | `git pull && docker compose up -d --build --pull always` |
| See the logs | `docker compose logs -f` |
| Back up everything | `docker run --rm -v ratchet-wrench_ratchet-data:/data -v "$PWD":/backup alpine tar czf /backup/ratchet-backup.tgz /data` |

Your database and photos live in the Docker volume `ratchet-data`. Back it up regularly.

**Extras**
- **Add family members:** *Settings → Users → Add user*. Each person sees only their own vehicles.
- **Browse the API documentation** (for tinkerers): *Settings → API documentation*. You must be logged in.
- **Optional AI vision for hard-to-read stickers:** set `RW_AI_VISION_ENABLED=true` and `RW_AI_VISION_API_KEY=…` (an Anthropic API key) in `.env`. This **sends sticker photos to a third party**; the app warns before every use.
- **A paid VIN-spec service** can be plugged in later (`RW_VIN_SPEC_PROVIDER`, `RW_VIN_SPEC_API_KEY`). See `server/app/vin.py`.

---

## Installing the Android app

The app needs **Android 10 or newer**.

**Install once:** on your phone, open <https://github.com/dannyspace21/Ratchet-Wrench/releases/latest>, tap the **`.apk`** file under *Assets*, then open it and tap **Install**. Android asks once to allow installs from your browser.

**Updates are automatic:** whenever you open the app, it checks for a newer version and installs it. It downloads on Wi-Fi; on mobile data it asks first. Android shows an "Update this app?" prompt; tap **Update**. Details: **[docs/ANDROID-UPDATES.md](docs/ANDROID-UPDATES.md)**.

**First start:** choose one of:
- **"Use on this phone only"**: no computer or server needed, works fully offline. Everything stays on the phone, and the history report PDF is made on the phone. You can connect later from *Settings*.
- **"Connect to my computer or home server"**: enter the address and your username and password.
  - **Desktop app:** use the address shown in its window, e.g. `http://192.168.1.20:8077`, and tick "Allow plain HTTP on my home network".
  - **Home server:** use your HTTPS address, e.g. `https://garage.your-tailnet.ts.net`.

When asked, allow **notifications** (for reminders) and the **camera** (the first time you scan).

**Alternatively, build it yourself:** open the `android` folder in Android Studio, or run `cd android && ./gradlew assembleRelease`. A build made on your own computer without the permanent key uses a temporary key and can't update an installed copy.

**How offline mode works:** everything you add on the phone is saved on the phone first. When you're online again, the app syncs with your server automatically (pull down on the Garage screen to sync right away). Photos upload in the background. Reminder notifications work with no connection at all.

---

## Setting up ntfy push alerts (optional)

[ntfy](https://ntfy.sh) is a small, free notification server you can run yourself. Ratchet Wrench uses it to push "oil change due" alerts to any device. The Android app already shows reminders offline without it.

1. **Start the included ntfy server:**
   ```bash
   docker compose --profile ntfy up -d
   ```
   It runs on port 8080. Put it behind Tailscale or HTTPS like the main app.
2. **Tell Ratchet Wrench where it is.** In `.env`, set:
   ```
   RW_NTFY_URL=http://ntfy:80
   ```
   (Use your own ntfy address instead if you already have one; add `RW_NTFY_TOKEN` if it requires login.) Restart with `docker compose up -d`.
3. **Choose a topic:** in the web app, go to *Settings → Push alerts*, tick the box, and pick a **hard-to-guess topic name**. Anyone who knows the topic can read it, so treat it like a password. Press **Save**, then **Send test**.
4. **Subscribe on your phone:** install the **ntfy** app, add your server's address, and subscribe to the same topic.

Ratchet Wrench checks reminders every hour and alerts you when one becomes *due soon* (within 500 miles or 30 days) or *overdue*, at most once every 3 days per reminder.

---

## Security

Security was the top priority. Highlights:
- **Logins and sessions:** Argon2id password hashing, account lockout and rate limiting, expiring login tokens.
- **Web protections:** strict security headers, closed CORS.
- **File uploads:** checked by their real content type, with GPS and other metadata stripped from photos, and untrusted PDFs read in a sandbox.
- **Server container:** runs as non-root with a read-only filesystem.
- **Android app:** the login token is encrypted with the Android Keystore, and HTTPS is enforced.
- **No telemetry or tracking.** Outbound connections are few, documented and mostly optional: NHTSA (VIN decoding, model lists and recalls — year/make/model only), owner's-manual links you paste (only those exact links, public websites only), GitHub (app updates, and bug reports only when you press Send), your own ntfy server, and AI sticker reading only if the owner turns it on. Smart Shopping links only open a retailer's site when you tap them. Full table in [SECURITY.md](SECURITY.md).

Every dependency is pinned and scanned with pip-audit, npm audit, OSV-Scanner, Trivy, Semgrep, Gitleaks, Android lint and CodeQL.

👉 **Read [SECURITY.md](SECURITY.md)** for the full list, the final scan results, known unfixed issues (with their risk and mitigations), and how to rerun the scans yourself.

---

## For the curious

| Folder | What's inside |
|---|---|
| `server/` | The API server (Python, FastAPI, SQLite) and its automated tests |
| `desktop/` | The desktop app: a launcher that runs the same server on your computer, plus the packaging script |
| `web/` | The web app (plain HTML/CSS/JavaScript, no third-party libraries) |
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `catalog/` | The shared catalog (maintenance types, spec fields, manufacturer links, Smart Shopping retailers, metric categories) plus the shared test cases both apps must pass; `affiliates.json` holds the (switched-off) affiliate settings |
| `icons/`, `brand/` | The icon set and logo, plus the scripts that draw them |
| `docs/` | Guides (desktop, Android updates, HTTPS, bug reports, affiliate links) and screenshots |
| `scripts/security-scan.sh` | Runs every security scanner |
| [DECISIONS.md](DECISIONS.md) | Why each design choice was made |
| [PROGRESS.md](PROGRESS.md) | Build status and notes for future work |

All code is commented in plain English: every file starts with what it does, and every function explains what and why.
