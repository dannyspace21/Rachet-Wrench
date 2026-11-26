# Database

## Server (SQLite via SQLAlchemy)
- **File:** `<RW_DATA_DIR>/ratchet-wrench.db` (Docker: `/data`, desktop: the per-OS data folder; see [Data Storage](Data-Storage)).
- **Connection settings:**
  - `PRAGMA foreign_keys=ON`;
  - `journal_mode=WAL`, so readers aren't blocked by a writer;
  - `busy_timeout=5000`, so it waits up to 5 s for a lock.
- **Schema creation:** `Base.metadata.create_all()` at every start. This creates **missing tables only**; it never alters existing ones.

| Table | Purpose | Notes |
|---|---|---|
| `users` | Accounts | Argon2id hash, admin flag, failed-login counter, lock-until |
| `auth_tokens` | Logins (web + phone) | Only the SHA-256 of the token; expiry (30 days), revoked flag, client |
| `pairing_codes` | One-time QR codes | SHA-256 only; 10-minute expiry; `used_at` |
| `user_settings` | Theme, units, currency, ntfy topic | One row per user |
| `vehicles` | Cars and motorcycles | Includes `photo_attachment_id`, parts/owner URL overrides |
| `vehicle_specs` | One spec value per (vehicle, field) | `source` = vin / sticker / user / unverified / provider / carfax |
| `entries` | Maintenance and repair records | `kind`, `type_key`, cost in cents, DIY/shop, `source` = manual / carfax |
| `attachments` | Photos, receipts, manuals, Carfax PDFs | Stored file name, SHA-256, size, page count, `has_file` |
| `reminders` | "Every N miles or M months" | Last done date/mileage, notification bookkeeping |
| `odometer_readings` | Mileage over time | |
| `parts` | Part numbers per vehicle | |

All user-data tables use **UUID primary keys** (so phones can create records offline) and **soft deletes** (`deleted_at`), so deletions sync to phones. Child rows are soft-deleted together with their vehicle or entry.

## Android (Room)
- **File:** `ratchet-wrench.db` in the app's private storage. 7 entities that mirror the server tables (vehicles, specs, entries, attachments, reminders, odometer, parts), plus a local `dirty` flag and `localPath` for photos.
- **Version:** `version = 1`, `exportSchema = true` (schema JSON in `android/app/schemas`). **No migrations exist yet**, so the first schema change will need one (see [Code Quality Audit](Code-Quality-Audit)).
- Settings and the encrypted login token live in SharedPreferences (`rw_prefs`), not in Room.

## Observations
- WAL mode plus foreign keys plus a busy timeout is the correct SQLite setup for a single-node app.
- **No migration tool on the server** (such as Alembic). Adding a column to an existing table won't reach existing installations. Today only new tables have been added (for example `pairing_codes`), which `create_all` handles.
- **Soft-deleted rows are never purged.** The database grows forever. Attachment *files* are deleted from disk when soft-deleted.
- **No built-in backup** (see [Code Quality Audit](Code-Quality-Audit), section "Backup and restoration").
