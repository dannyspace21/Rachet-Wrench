# Desktop App (Windows, macOS, Linux)

**Location:** `desktop/` · **Packaging:** PyInstaller one-folder bundle · **UI:** small tkinter window + the regular web app in the browser.

## How it works
1. **Start-up:** `launcher.py` sets environment variables before importing the server:
   - `RW_DATA_DIR`: the per-OS data folder;
   - `RW_ASSET_DIR`: the bundle;
   - `RW_SETUP_TOKEN`: a one-time code for creating the first account;
   - `RW_COOKIE_SECURE=false`: plain HTTP on this computer.
2. **Server:** runs the **same FastAPI server** in a background thread with Uvicorn, on `127.0.0.1:8077` (default port).
3. **Phone sync:** when "Allow my phone to sync" is ticked, the server restarts bound to `0.0.0.0` so the phone can reach it over the home network. The window shows the most likely home-network address (virtual adapters are listed separately).
4. **Connect a phone (QR code):** creates a one-time pairing code directly in the database for the computer's account and shows it as a QR code.
5. **PDF helper:** in the packaged app, the PDF helper runs as `<app> --pdf-worker`, the same executable in helper mode.
6. **Second launch:** launching again while it runs just opens the browser.

## Build
- `desktop/build.py`: runs PyInstaller with hidden imports and bundled data (web app, catalog, icons, brand).
- **CI:** builds on Windows, macOS (Apple silicon) and Linux and **starts each build** to check `/api/health` before publishing.
- **Python version:** desktop builds use Python 3.14. The Windows Python 3.15 wheel of argon2-cffi-bindings needs a DLL that standard Windows Python doesn't ship.

## Observations
- **No TLS:** plain HTTP, including over the home network when phone sync is on.
- **Not code-signed:** Windows SmartScreen and macOS Gatekeeper will warn users.
- **No self-update:** users download new versions manually. The Android app updates itself; the desktop app doesn't.
- **No Windows sandbox limits:** on Windows the PDF helper can't use the Unix `resource` limits (memory/CPU/files); only the timeout applies.
- **No Intel Mac build:** macOS is built for Apple silicon only.
