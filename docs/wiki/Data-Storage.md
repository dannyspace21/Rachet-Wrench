# Data Storage Locations

| Where | What | Path | Protection |
|---|---|---|---|
| **Docker server** | Database + uploads | Named volume `ratchet-data` → `/data` in the container: `/data/ratchet-wrench.db` (+ `-wal`, `-shm`), `/data/uploads/<2 hex>/<random name>` and `.thumb.jpg` thumbnails | Container user 10001, folder mode 700, read-only root FS |
| **Desktop – Windows** | Database, uploads, `desktop.json` (preferences), `desktop.log` | `%APPDATA%\RatchetWrench` | Windows user profile permissions |
| **Desktop – macOS** | same | `~/Library/Application Support/Ratchet Wrench` | User account |
| **Desktop – Linux** | same | `$XDG_DATA_HOME/ratchet-wrench` (default `~/.local/share/ratchet-wrench`) | User account |
| **Android – records** | Room database | app-private `databases/ratchet-wrench.db` | Android app sandbox; excluded from backups |
| **Android – photos** | Photos and receipts taken or picked (until uploaded; kept after) | app-private `files/photos/` | App sandbox |
| **Android – settings** | Server URL, mode, theme, sync bookmarks, last sync error | SharedPreferences `rw_prefs` | App sandbox |
| **Android – login token** | Encrypted token | `rw_prefs` → `token_enc` (AES-256-GCM, key in Android Keystore) | Keystore; key never leaves it |
| **Android – crash log** | Last crash stack trace (never sent) | app-private `files/last_crash.txt` | App sandbox |
| **Android – downloads** | History report PDFs the user saves | public `Downloads/` (MediaStore) | User-visible by design |
| **GitHub** | Releases (APK, desktop bundles), source, issues | github.com | Signed APK; SHA-256 checksums |
| **GitHub secrets** | Android signing keystore + passwords | repository secrets | Encrypted by GitHub; restored only in the release job |

**Not stored anywhere:** passwords (only Argon2id hashes), plain login tokens on the server (only SHA-256), and photo EXIF/GPS (stripped on upload and on the phone's copy).

## Observations
- **No automatic backup.** Server and desktop data are files the user must copy themselves. The phone's database is excluded from Android backups on purpose (privacy), so a **phone-only** user who loses or resets the phone loses everything.
- **Hot copies need care.** Copying the SQLite file while the server runs can produce an inconsistent copy unless the `-wal` file is copied too, or SQLite's backup API (`VACUUM INTO`) is used.
