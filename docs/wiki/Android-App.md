# Android App

**Location:** `android/` · **Package:** `com.ratchetwrench.app` · **minSdk 29 (Android 10), targetSdk 37** · Kotlin 2.4, Jetpack Compose (Material 3), single Activity.

## Structure (`app/src/main/java/com/ratchetwrench/app`)

| Package | What it does |
|---|---|
| `App.kt` | `RatchetApp` (creates the notification channel, schedules workers, installs the on-device crash log) and `MainActivity` (hosts Compose; handles orientation changes itself). |
| `ui/` | Navigation graph (`Navigation.kt`), theme, shared components, generated icon tables (`IconResources.kt`), release notes, update banner. |
| `ui/screens/` | Welcome, Connect (login and QR), Settings/About, Garage, Vehicle (tabs: History, Specs, Reminders, Parts, Odometer), Add/Edit vehicle, Entry add/edit/detail, Door sticker. |
| `data/` | Room database and DAOs, models, `Repository` (all writes mark records "dirty" and ask for a sync), `Api` (OkHttp client with the connection-rule guard), `SecureStore` (settings and Keystore-encrypted token), `Nhtsa` (VIN decode and model lists), `ReleaseNotes`. |
| `sync/` | `SyncEngine` (push → upload photos → pull) and WorkManager workers: `SyncWorker` (every 6 h when online, plus on demand) and `ReminderWorker` (every 12 h, offline reminder notifications). |
| `camera/` | CameraX screens: VIN scanner (barcode + printed text, 1080p analysis, torch/zoom/tap-to-focus), QR pairing scanner, photo capture (upright, EXIF stripped). |
| `domain/` | Pure logic: VIN validation, `VinFinder` (OCR repair), reminder rules, catalog, sticker parser. |
| `report/` | Phone-made history report PDF (Android `PdfDocument`) for phone-only mode. |
| `update/` | Self-updater: reads the latest GitHub Release, downloads the APK, checks the SHA-256, package name, version and signing certificate, then installs through `PackageInstaller`. |

## Modes
- **Phone only (`mode=local`):** no network except the documented optional calls (NHTSA, GitHub updates).
- **Connected (`mode=server`):** syncs with one server URL. Every request is checked by `UrlPolicy`: HTTPS always, plain HTTP only to private home-network addresses and only after the user allows it.

## Build and release
- **Toolchain:** Gradle 9.9 with AGP 9.5. R8 full mode with resource shrinking, and keep rules for ML Kit.
- **Locked versions:** dependency versions are locked in `app/gradle.lockfile`.
- **Signing:** releases are signed with a permanent key restored from GitHub secrets. `android/check_apk.py` fails a release if components created by name, or any icon, are missing.
- **Versioning:** version code = workflow run number × 1000. Release notes are required (`release_notes.json`; the workflow refuses to publish without them).

## Permissions
`INTERNET`, `CAMERA`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES` (for self-update). Unneeded library components and permissions are removed in the manifest. `allowBackup=false`.

## Observations
- **Clean layering:** UI → Repository → Room, with sync isolated in `SyncEngine`.
- **No ViewModels.** Screens read state straight from DAOs/Flows and use `rememberSaveable`. That's simple, but business logic in composables is harder to unit-test.
- **No dependency injection framework.** Objects are created directly, for example `Repository(context)`. Acceptable at this size.
- **Room is at schema version 1 with no migrations.** Plan migrations before any schema change.
