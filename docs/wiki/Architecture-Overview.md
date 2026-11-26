# Architecture Overview

Ratchet Wrench is a **self-hosted vehicle maintenance and repair tracker**. One codebase serves four ways of running it:

| Way of running | What runs | Who it's for |
|---|---|---|
| **Phone only** | Android app with its own database; no server | One person, offline, no computer needed |
| **Desktop app** | The server and web app packaged as one program for Windows, macOS or Linux; opens in the browser | One person on their computer |
| **Home server (Docker)** | The same server in a hardened container, optionally behind Caddy (HTTPS) and ntfy (push alerts) | A household, always-on machine |
| **Phone + computer** | The Android app syncs with the desktop app or the Docker server over the home network (or Tailscale) | Using both |

## Languages and frameworks

| Area | Language | Frameworks and key libraries |
|---|---|---|
| Server / API | Python 3.15 (Docker), 3.14 (desktop builds) | FastAPI + Starlette + Pydantic, Uvicorn, SQLAlchemy 2.1 (ORM), argon2-cffi, Pillow + pillow-heif, pypdf (sandboxed), fpdf2, Tesseract OCR (Docker only), segno (QR codes), anthropic SDK (optional) |
| Web app | JavaScript (ES modules), HTML, CSS | **No framework and no third-party libraries** — plain DOM code (`web/js`). Swagger UI is vendored only for the login-protected API docs page |
| Android app | Kotlin 2.4 | Jetpack Compose + Material 3, Navigation-Compose, Room 2.8 (SQLite), WorkManager, CameraX, ML Kit (bundled models: barcode + text), OkHttp 5, kotlinx.serialization, kotlinx.coroutines |
| Desktop app | Python | PyInstaller (one-folder bundle) + tkinter window; runs the same FastAPI server in-process |
| Build / CI | Gradle (Kotlin DSL), GitHub Actions YAML, Bash, Python scripts | AGP 9.5, KSP, R8; `uv` for Python lock files |
| Tests | Python, Kotlin | pytest + FastAPI TestClient; JUnit 4 + Robolectric + Compose UI Test |
| Catalog / icons | Python generators | `catalog/build_catalog.py` (types, specs, makes), `icons/build_icons.py` (SVG + Android VectorDrawables from one source) |

**Size (excluding vendored code):** about 7,400 lines of Python, 6,000 lines of Kotlin, 1,600 lines of JavaScript, 370 lines of CSS.

## How the parts fit together

```
 ┌────────────── Android app ──────────────┐        ┌────────── Server (FastAPI) ──────────┐
 │ Compose UI → Repository → Room (SQLite) │  HTTP  │ /api/*  → SQLAlchemy → SQLite (WAL)  │
 │ WorkManager: SyncWorker (6 h),          │◄──────►│ uploads/ (cleaned photos, PDFs)      │
 │   ReminderWorker (12 h)                 │  sync  │ background reminder loop → ntfy      │
 │ CameraX + ML Kit (VIN, stickers, QR)    │        │ sandboxed PDF worker (subprocess)    │
 │ Self-updater (GitHub Releases)          │        │ serves the web app (static files)    │
 └─────────────────────────────────────────┘        └──────────────▲───────────────────────┘
                                                                   │ same code
                       ┌───────────────────────────────────────────┴───────────────┐
                       │ Desktop app: tkinter window + server thread on 127.0.0.1   │
                       │   (0.0.0.0 when "Allow my phone to sync" is on)            │
                       │ Docker: hardened container + optional Caddy (HTTPS), ntfy  │
                       └────────────────────────────────────────────────────────────┘
```

- **One server codebase:** `server/app` serves the REST API, the web app and the OpenAPI docs.
- **The web app** is plain static files served by the same server, with a strict Content-Security-Policy.
- **The Android app** works fully offline on its own database. In connected mode it syncs (see [Synchronization](Synchronization)).
- **The catalog** (`catalog/catalog.json`: 80 maintenance types, 46 spec fields, manufacturer links and make names) is shared by the server, the web app and the phone (bundled as an asset).
- **One release pipeline** (`.github/workflows/android.yml`) builds the signed APK and the three desktop bundles and publishes them together as one GitHub Release. The Docker image is built locally by the operator (`docker compose up -d --build`).
