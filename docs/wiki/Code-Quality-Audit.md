# Phase 2 — Code Quality Audit

Each finding has an **ID**, a **severity** (High / Medium / Low / Info), the **evidence** (file and line, as of commit `8edaaae`), a realistic **scenario**, and a **recommendation**. **Nothing was changed during this audit.** Recommendations are proposals for the owner to decide on.

Severity guide:
- **High:** can lose user data or expose it under normal use.
- **Medium:** needs a specific but plausible situation.
- **Low:** an edge case or a hardening improvement.
- **Info:** an observation.

---

## 1. Database corruption risks

| ID | Sev. | Finding | Evidence | Recommendation |
|---|---|---|---|---|
| DB-1 | Info | SQLite is configured correctly for one node: WAL, `foreign_keys=ON`, `busy_timeout=5000`. Every write goes through one code path and commits per request, and SQLite commits are atomic. | `server/app/database.py` 33–45 | Keep as is. |
| DB-2 | Medium | **No schema migrations on the server.** `create_all` creates missing *tables* but never adds or alters *columns*. The first release that adds a column to an existing table will fail at runtime on existing installations ("no such column"). | `database.py` 71–75 | Adopt Alembic (or a small versioned migration runner) before the next schema change, with a migration test that upgrades a copy of an old database. |
| DB-3 | Medium | **Room is at schema version 1 with no migrations.** Changing an entity without a migration crashes the app on update. Adding `fallbackToDestructiveMigration` would silently **erase** phone data. | `android/.../data/Database.kt` 200–204 | Write explicit `Migration` objects for every future version and test them with Room's `MigrationTestHelper` against the exported schema JSON (already enabled). |
| DB-4 | Low | **The database and files can disagree.** Uploaded files are written before the database row commits, and deleted after soft-delete. A crash between the two leaves an orphan file, or a row whose file failed to delete. Files are written without `fsync`. | `uploads.py` 261–268; `records.py` soft_delete | Add a periodic "orphan sweep" (files with no row, rows marked `has_file` with no file) and optionally `fsync` + write-to-temp-then-rename. |
| DB-5 | Low | **Unsafe storage locations.** SQLite in WAL mode isn't safe on network or cloud-synced folders (OneDrive, Dropbox, NAS shares). A user who moves the desktop data folder into one can corrupt the database. | Desktop data paths | Document "don't put the data folder in a synced or network drive"; optionally detect common sync folders. |
| DB-6 | Low | **Two copies against one folder.** Starting the desktop app twice with different `--port` values would run two servers on one database. SQLite locking keeps it consistent, but in-memory state (rate limits, caches) diverges. | `desktop/launcher.py` `already_running` only checks one port | Use a lock file in the data folder. |
| DB-7 | Info | **Soft-deleted rows are never purged**, so the database grows slowly forever. | `records.py` | Optional: purge rows deleted more than N months ago, once all devices have synced past that point. |

## 2. Synchronization conflicts

| ID | Sev. | Finding | Evidence | Recommendation |
|---|---|---|---|---|
| SY-1 | Medium | **Whole-record "newest edit wins".** If two devices edit *different fields* of the same record (for example, cost on the phone offline and notes on the computer), the later edit replaces the whole record and the other field change is lost. | `records.py` ~172; `SyncEngine.kt` push/pull | Accept it, but tell the user. Or move to field-level merge, either by sending only changed fields with per-field timestamps or by keeping a small change log. |
| SY-2 | Medium | **Device clocks decide the winner.** `updated_at` comes from the editing device; times more than 1 minute in the future are clamped, but times in the *past* aren't. A phone whose clock runs behind loses to older server edits. | `records.py` `_clamp_updated_at` 91–101 | Use a server-assigned version number (or hybrid logical clock) for conflict decisions, keeping `updated_at` for display. |
| SY-3 | Medium | **Silent divergence when the server rejects a record.** For `rejected` results the phone just clears `dirty` and keeps its own copy, so the record exists on the phone but never reaches the server, and nobody is told. | `SyncEngine.kt` 133–140 | Keep rejected records flagged (for example `sync_error`), show them in Settings → Sync with the reason, and let the user fix or discard them. |
| SY-4 | Low | **"Kept server copy" is silent.** When the server keeps a newer copy, the phone's offline edit is discarded without notice. | `SyncEngine.kt` applyResults | Count and show "N of your changes were older than the computer's and were not applied". |
| SY-5 | Low | **No paging on the first full sync.** It downloads every record in one response, which is fine for households but slow for large accounts. | `routers/sync.py` pull | Add `limit`/cursor paging if accounts grow. |
| SY-6 | Info | **Done well:** phone-made UUIDs (idempotent retries), per-record ownership checks, soft deletes that sync, a 5-second pull overlap, attachment uploads keyed by the phone's ID, and photos that wait for their parent records. A live end-to-end test (`LiveSyncTest`, opt-in) runs five consecutive syncs against a real server. | | Run `LiveSyncTest` in CI against a throw-away server (see §9). |

## 3. Data loss scenarios

| ID | Sev. | Scenario | Evidence | Recommendation |
|---|---|---|---|---|
| DL-1 | **High** | **Sign out on the phone deletes unsynced work immediately.** "Sign out and remove data from this phone" logs out and wipes the database **without a confirmation dialog and without a final sync**. One accidental tap loses every change not yet on the server. ("Disconnect — keep my data" *does* sync first.) | `AccountScreens.kt` ~338; `Database.kt` 222 `wipe()` | Add a confirmation that states how many unsynced changes would be lost; try a final sync first; block or warn if it fails. |
| DL-2 | **High** | **Phone-only mode has no backup.** Data lives only in the app's private storage, and Android backup is deliberately disabled. A lost, broken or reset phone, or an uninstall, loses everything. | `AndroidManifest.xml` 35–37 | Add **Export / Import** (a ZIP with JSON + photos, optionally encrypted with a passphrase) and a reminder to export. Consider opt-in encrypted Android backup. |
| DL-3 | Medium | **Server and desktop data have no built-in backup.** The user must copy files by hand, and copying a live SQLite file without its `-wal` can produce a corrupt copy. `docker compose down -v` deletes the data volume. | `docker-compose.yml` comment "back this up!"; no backup code anywhere | Add a **Backup now / scheduled backup** (SQLite `VACUUM INTO` + uploads archive) and a documented, tested **restore**. Warn about `down -v` in the docs. |
| DL-4 | Medium | **"Replace earlier Carfax import" also removes user edits** made to imported records afterwards (it deletes every `source = carfax` entry). | `routers/vehicles.py` carfax_apply `replace_previous` | Skip entries edited after import (`updated_at > created_at`), or list them for confirmation. |
| DL-5 | Medium | **Records rejected during sync** stay only on the phone, see SY-3. **Photos the server refuses** (4xx) are retried at every sync forever, silently. | `SyncEngine.kt` 167 | Surface stuck uploads with the reason; allow retry or remove. |
| DL-6 | Low | **Deleting a vehicle is irreversible.** It cascades to all records, and its **files are deleted from disk** immediately. It's confirmed by a dialog, but there's no trash or undo. | `records.py` soft_delete | Keep files for a grace period ("Recently deleted", 30 days). |
| DL-7 | Low | **Future upgrades without migrations** (DB-2, DB-3) can make data unreachable or wipe it. | | See DB-2 and DB-3. |

## 4. Security vulnerabilities

Scanner results at audit time: **pip-audit, OSV-Scanner (including the Android lockfile) and npm audit found no known vulnerabilities; Semgrep found no code findings; Gitleaks found no secrets in the full history.** The findings below are design-level.

| ID | Sev. | Finding | Evidence | Recommendation |
|---|---|---|---|---|
| SEC-1 | Medium–High | **Plain HTTP on the home network by default** (desktop app with phone sync; Docker without the `https` profile). Anyone on the same Wi-Fi can capture passwords at login, 30-day bearer tokens and QR pairing codes. | `launcher.py` 205; `docker-compose.yml` ports; phone `UrlPolicy` allows LAN HTTP after consent | Offer HTTPS by default: a self-signed certificate pinned through the QR code (the QR code can carry the certificate fingerprint), or bundle Tailscale guidance; shorten phone token lifetime on plain HTTP. |
| SEC-2 | Medium | **ntfy is open by default.** The `ntfy` profile sets `NTFY_AUTH_DEFAULT_ACCESS=read-write`, so anyone who can reach port 8080 can read reminder alerts or post fake ones. | `docker-compose.yml` 68 | Default to `deny-all` and create a token for the server (`RW_NTFY_TOKEN` is already supported). |
| SEC-3 | Medium | **The desktop server listens on every network** (`0.0.0.0`) whenever phone sync is on, including public Wi-Fi on a laptop. | `launcher.py` 205 | Bind only to the private address the phone uses, and/or turn phone sync off automatically on networks Windows marks Public. |
| SEC-4 | Medium | **No password reset, no admin reset of other users, no MFA, no session list.** A forgotten admin password means editing the database by hand. A lost phone's token can only be revoked by changing the password, which signs out everything. | `routers/auth.py` (only `change-password`) | Add admin "reset user password", a "devices signed in" list with per-device revoke, and optional TOTP for web logins. |
| SEC-5 | Low–Medium | **The Windows PDF sandbox has no resource limits.** The Unix `resource` limits on the PDF helper don't exist on Windows, so a hostile PDF can use unlimited memory/CPU for up to 30 s. | `uploads.py` 180–182 | Use a Windows Job Object (memory and CPU limits) for the helper process. |
| SEC-6 | Low | **Rate limiting is in memory and per IP.** It resets on restart, doesn't work across multiple instances, and is only as accurate as the client IP (`RW_TRUST_PROXY_HEADERS`). | `security.py` 85–112 | Fine for self-hosting; use a shared store for any hosted version. |
| SEC-7 | Low | **The Android network config allows cleartext globally.** Enforcement relies on the app's own `UrlPolicy` (well tested). A future code path that bypasses `Api` could send plain HTTP anywhere. | `network_security_config.xml` 11 | Keep all network code behind `Api`, and add a lint/test that no other `OkHttpClient` is created (the updater and NHTSA clients are HTTPS-only by construction today). |
| SEC-8 | Low | **Release trust rests on the GitHub account.** Whoever controls the repository and its secrets can ship an update phones will install (same key). | Release workflow | Protect the owner account with 2FA, restrict who can change workflows and secrets, require review for workflow changes, and keep an offline copy of the signing key. |
| SEC-9 | Info | **Strong points:** Argon2id, hashed tokens, CSRF, strict CSP and headers, upload sanitisation, PDF sandbox, per-owner scoping (tested), hash-locked dependencies, digest-pinned images, SHA-pinned actions, a signed and verified self-update, and no telemetry. | | |

## 5. Hardcoded credentials

| ID | Sev. | Finding |
|---|---|---|
| HC-1 | Info | **None found.** Gitleaks over the full git history found no leaks. `.env.example` has **empty** values for every password and key. There's no default admin password: Docker requires `RW_ADMIN_PASSWORD`, and the desktop app uses a one-time setup code that changes at every start. The Android app contains no keys. |
| HC-2 | Info | **Test-only passwords** exist inside the test suites (for throw-away test databases); that's expected. |
| HC-3 | Low | **The Android release signing key** lives only in GitHub secrets (and the owner's offline copy). Copies made while generating it in an earlier working session were in temporary, session-only storage. **Recommendation:** confirm the only copies are the GitHub secrets plus one offline backup in a password manager or safe. |

## 6. API key exposure

| ID | Sev. | Finding |
|---|---|---|
| AK-1 | Info | **Third-party keys stay on the server.** The Anthropic key (`RW_AI_VISION_API_KEY`) and the optional VIN-provider key (`RW_VIN_SPEC_API_KEY`) are read **only** from server environment variables, never sent to browsers or phones (clients only see `ai_vision_available: true/false`), and never committed. |
| AK-2 | Low | **The `.env` file holds them in plain text** on the host. **Recommendation:** document `chmod 600 .env`, or support Docker secrets / `*_FILE` variables. |
| AK-3 | Info | **The signing secrets are handled safely in CI.** They're decoded to a temporary file inside the release job and deleted before the APK is published. They aren't available to pull requests. |
| AK-4 | Info | **No keys in the Android app.** NHTSA needs no key, and the GitHub updater uses unauthenticated public endpoints. |

## 7. Backup and restoration

**Current state: there is no backup or restore feature on any platform.**
- **Server and desktop:** users must copy the data folder or volume by hand. The README and compose file mention backing up, but there's no tooling or tested restore.
- **Phone (phone-only mode):** no export, and Android backup is disabled (DL-2).
- **Phone (connected mode):** the server copy acts as the phone's backup, which works only if the server itself is backed up.

**Recommended (in priority order):**
1. **Server/desktop "Backup now"** plus an optional daily schedule:
   - SQLite `VACUUM INTO 'backup-YYYYMMDD.db'` (a consistent copy while running);
   - plus a tar/zip of `uploads/`;
   - keep the last N copies.
2. **Restore:** a documented, *tested* procedure (stop → replace files → start), plus a CI test that backs up, restores and checks the record counts.
3. **Phone Export/Import:** a ZIP (JSON + photos), optionally passphrase-encrypted, shareable to cloud storage chosen by the user.
4. **Docker docs:** a backup example (`docker run --rm -v ratchet-data:/data …`) and a warning about `docker compose down -v`.

## 8. Error handling

| Area | Current behaviour | Gaps / recommendations |
|---|---|---|
| Server API | **Validation:** Pydantic returns 422 with field details. Expected errors use `HTTPException` with plain-English messages. **PDF problems:** a bad PDF, timeout or too many pages becomes a friendly 400. **Reminder loop:** catches and logs exceptions so it never dies. | **No global exception handler:** unexpected errors become a generic 500, with the traceback only in the Uvicorn log. **No structured logging or request IDs**, and no audit log of security events (logins, failed logins, password changes). *Recommend:* a JSON logging setup, a request ID header, and a security-event log. |
| Android | **Crashes:** saved on the device (`last_crash.txt`) and offered for copying, or included in Report a bug. **Sync and network errors:** explained in plain English (`UrlPolicy.explain`/`explainSync`), shown on the Garage and in Settings. **Screen changes:** navigation happens on the main thread. | **Silent catches:** a few `runCatching { … }` blocks discard errors (for example sending settings to the server, and some best-effort steps). That's OK for best-effort work, but log them. **Stuck items:** rejected sync records and stuck uploads aren't surfaced (SY-3, DL-5). |
| Web | **API errors:** shown as toasts (`showError`). **Sign-in:** a 401 redirects to the login page. | **No offline indicator.** |
| Desktop | **Output:** goes to `desktop.log` in the data folder (windowed apps have no console). **Start-up failure:** a clear message if the port is in use. | **No log rotation:** `desktop.log` grows without limit. |

## 9. Existing automated tests

| Suite | Count | What it covers | Runs in CI? |
|---|---|---|---|
| Server (`server/tests`, pytest) | **62** | **Auth and security (24):** lockout, rate limit, CSRF, cookies, tokens, password change, setup code, QR pairing. **Access control (4):** one user can never read or change another's data. **Upload security (12):** type sniffing, EXIF stripping, PDF sandbox and limits, path safety. **Features (18):** VIN, sticker parsing, both Carfax formats, reminders, report, sync. **Icons and web (4).** | ❌ **No.** Run by hand / by the developer only. |
| Android unit + screen (`android/app/src/test`, JUnit + Robolectric) | **28** (+1 opt-in) | **Logic (8):** VIN, reminders, sticker parser, NHTSA parsing, URL rules. **VIN finder (5). Photo orientation (4). Release notes (2). Screens (8):** real screens tapped through — add/view/edit/delete an entry, every vehicle tab, Settings/About/Report a bug, reopening with saved data, the form keeping typed values, QR on Connect, plain-HTTP consent. **Live sync (opt-in):** five syncs against a real server. | ✅ Yes (release workflow, with lint). |
| Release checks | — | `check_apk.py` (components created by name and every icon present in the shrunk APK), signature check, release-notes check, desktop **start-up smoke test** on Windows, macOS and Linux. | ✅ Yes |
| Security scanners | — | pip-audit, npm audit, OSV-Scanner, Semgrep, Gitleaks (`scripts/security-scan.sh`); CodeQL and Dependabot on GitHub. | ⚠️ CodeQL/Dependabot only; the scan script is run by hand. |
| Web app (JavaScript) | **0** | No unit or end-to-end tests. (Screenshots are generated with Playwright, but that isn't a test.) | ❌ |

**Gaps (in priority order):**
1. **The server tests aren't run by CI.** The release workflow builds the desktop app and could ship a server regression the 62 tests would have caught. **Recommend:** a `server-tests` job that must pass before `publish`, and the same for `scripts/security-scan.sh`.
2. **No web end-to-end tests:** add a small Playwright suite covering login, add vehicle (make/model), add entry, the Carfax review and the QR pairing page.
3. **The live sync test isn't in CI:** run `LiveSyncTest` against a throw-away server in CI.
4. **No migration tests** (DB-2, DB-3) and no backup/restore test (§7).
5. **Untested by automation:** the camera screens (VIN scanner, capture) and dialogs containing text fields (a Robolectric limitation). They need instrumented tests on an emulator (or a device farm) before commercial release.
