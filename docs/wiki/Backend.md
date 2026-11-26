# Backend (server)

**Location:** `server/app` · **Stack:** Python, FastAPI (Starlette + Pydantic), Uvicorn, SQLAlchemy 2.1 · **Process model:** one Uvicorn process, one SQLite database file.

## Modules

| Module | Responsibility |
|---|---|
| `main.py` | Builds the app: security headers middleware (CSP, HSTS, X-Frame-Options, …), optional CORS, request body-size limit, routers, login-protected `/api/docs`, static web app, startup tasks (create tables, first admin, optional sample data, reminder loop). |
| `config.py` | Reads every setting from `RW_*` environment variables (28 of them) with safe defaults and range checks. |
| `database.py` | SQLite engine; on every connection sets `foreign_keys=ON`, `journal_mode=WAL`, `busy_timeout=5000`. `init_db()` runs `create_all` (creates missing tables). |
| `models.py` | 11 tables (see [Database](Database)). All user data rows share sync fields: `id` (UUID), `owner_id`, `created_at`, `updated_at`, `deleted_at`, `server_modified_at`. |
| `schemas.py` | Pydantic models with `extra="forbid"` and explicit length/range limits for everything the API accepts. |
| `records.py` | One shared create/update/soft-delete path (`upsert`) used by both the REST endpoints and sync: ownership checks, "newest edit wins", soft deletes that cascade to children, mileage bump, reminder "last done" update. |
| `security.py` | Argon2id passwords, token creation/lookup (SHA-256 hashed), per-IP rate limiter, account lockout, CSRF check for cookie sessions, QR pairing codes. |
| `uploads.py` | File intake: magic-byte sniffing, size limits, image re-encoding (strips EXIF/GPS, HEIC → JPEG), thumbnails, random storage names, and the **sandboxed PDF helper** (separate process with memory/CPU/file limits and a timeout). |
| `pdf_worker.py` | The helper program: opens one untrusted PDF with pypdf and returns page count, text, or text with positions. |
| `carfax.py` | Carfax PDF importers (classic history report and the two-column "Service History" page). |
| `ocr.py`, `sticker_parser.py` | Door-sticker text recognition (Tesseract) and field extraction. |
| `vin.py` | VIN check digit, NHTSA vPIC decode and model lists, optional paid spec provider plug-in. |
| `reminders.py` | "Whichever comes first" reminder status, reminder loop (every `RW_REMINDER_CHECK_MINUTES`) and ntfy alerts. |
| `report.py` | History report PDF (fpdf2). |
| `netinfo.py` | Best-guess home-network address for the QR code. |
| `seed.py` | Optional sample data. |
| `routers/` | `auth` (login, logout, me, settings, users, setup, QR pairing), `items` (generic CRUD for 7 record kinds), `sync`, `attachments`, `vehicles` (VIN, Carfax, stickers, specs, links, report, model list). |

## Request flow
1. **Middleware:** a body-size limit is checked first. The security headers are added on the way out.
2. **Authentication:** `current_user` accepts a **Bearer token** (phone) or a **session cookie** (web). Cookie-authenticated writes must carry a matching `X-CSRF-Token` header.
3. **Validation:** Pydantic rejects unknown fields and out-of-range values.
4. **Business rules:** `records.upsert` checks ownership, so every query is scoped by `owner_id`.
5. **Background work:** the reminder loop runs inside the same process (an asyncio task). PDF parsing runs in a separate short-lived process.

## Observations
- The design is clean and consistent: a single write path (`records.upsert`) for REST and sync greatly reduces the chance of inconsistent rules.
- **Single-process assumptions:**
  - the login rate limiter and the NHTSA model cache are **in memory**;
  - the reminder loop runs inside the web process.

  This is fine for self-hosting, but these must change before running several server instances (see [Commercial Readiness](Commercial-Readiness)).
- **No global exception handler or structured logging.** Unhandled errors return FastAPI's generic 500 and the traceback goes to Uvicorn's log. There's no request ID or audit log.
