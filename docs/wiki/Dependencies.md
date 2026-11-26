# Dependencies

All versions are **pinned exactly** and locked with hashes (Python) or a lockfile (Android). Scans at audit time: **no known vulnerabilities** (pip-audit, OSV-Scanner, npm audit).

## Server (Python) — `server/requirements.txt`

Direct dependencies are **bold**; the rest are pulled in by them. Licences are read from each installed package's metadata.

| Package | Version | Licence | Notes |
|---|---|---|---|
| annotated-doc | 0.0.7 | MIT |  |
| annotated-types | 0.8.0 | MIT |  |
| **anthropic** | 2.14.0 | Apache-2.0 | Only used by the optional AI vision mode |
| anyio | 4.16.0 | MIT |  |
| **argon2-cffi** | 25.1.0 | MIT |  |
| argon2-cffi-bindings | 26.2.0 | MIT |  |
| cffi | 2.1.1 | MIT-0 |  |
| click | 8.5.1 | BSD-3-Clause |  |
| docstring-parser | 0.18.0 | MIT |  |
| **fastapi** | 0.165.0 | MIT |  |
| fonttools | 4.68.0 | MIT |  |
| **fpdf2** | 2.9.0 | LGPL-3.0-only | LGPL-3.0 — see Commercial Readiness |
| h11 | 0.16.0 | MIT |  |
| httpcore2 | 2.16.2 | BSD-3-Clause |  (marker: sys_platform != 'emscripten') |
| httpx2 | 2.16.2 | BSD-3-Clause |  |
| httpx2-jsfetch | 1.0 | — | Browser-Python only; never installed on Linux/Windows/macOS (marker: sys_platform == 'emscripten') |
| idna | 3.20 | BSD-3-Clause |  |
| jiter | 0.19.0 | MIT |  |
| opentelemetry-api | 1.45.2 | Apache-2.0 |  |
| **pillow** | 13.0.0 | MIT-CMU |  |
| **pillow-heif** | 1.9.0 | BSD-3-Clause | Bundles libheif (LGPL-3.0), libde265 (LGPL-3.0) **and libx265 (GPL-2.0+)** — see Commercial Readiness |
| pycparser | 3.12 | BSD-3-Clause |  (marker: implementation_name != 'PyPy') |
| pydantic | 2.14.4 | MIT |  |
| pydantic-core | 2.50.5 | MIT |  |
| **pypdf** | 7.1.1 | BSD-3-Clause |  |
| **python-multipart** | 0.0.34 | Apache-2.0 |  |
| **segno** | 1.6.6 | BSD License | QR codes (added in 1.0.19) |
| sniffio | 1.3.1 | MIT OR Apache-2.0 |  |
| **sqlalchemy** | 2.1.9 | MIT |  |
| starlette | 1.10.0 | BSD-3-Clause |  |
| truststore | 0.10.5 | MIT |  (marker: sys_platform != 'emscripten') |
| typing-extensions | 4.16.0 | PSF-2.0 |  |
| typing-inspection | 0.4.5 | MIT |  |
| **uvicorn** | 0.54.0 | BSD-3-Clause |  |

## Server dev/test — `server/requirements-dev.txt`

anyio 4.16.0, certifi 2026.11.20, colorama 0.4.6, h11 0.16.0, httpcore 1.0.9, httpx 0.28.1, idna 3.20, iniconfig 2.3.1, packaging 26.4, pluggy 1.7.0, pygments 2.21.0, pytest 9.2.0

## Desktop packaging — `desktop/requirements-desktop.txt`

altgraph 0.17.5, macholib 1.16.4, packaging 26.4, pefile 2024.8.26, pyinstaller 6.22.3, pyinstaller-hooks-contrib 2026.10, pywin32-ctypes 0.2.3, setuptools 84.2.1 (PyInstaller is GPL-2.0 *with a bootloader exception*, which allows distributing the built app under any licence).

## Android — `android/app/build.gradle.kts` (locked in `app/gradle.lockfile`)

**Toolchain:** Android Gradle Plugin 9.5.2, Kotlin 2.4.21 (Compose and serialization plugins), KSP 2.3.14, Gradle 9.9.2, compileSdk/targetSdk 37, minSdk 29.

| Library | Version | Licence | Used for |
|---|---|---|---|
| Jetpack Compose (BOM) + Material 3 | BOM-managed (Material 3 1.5) | Apache-2.0 | UI |
| androidx.core-ktx | 1.19.1 | Apache-2.0 | Android helpers |
| androidx.activity-compose | 1.13.0 | Apache-2.0 | Activity + photo picker |
| androidx.navigation-compose | 2.10.2 | Apache-2.0 | Screens |
| androidx.lifecycle (runtime/viewmodel-compose) | 2.11.1 | Apache-2.0 | Lifecycle |
| androidx.room (runtime, ktx, compiler) | 2.8.5 | Apache-2.0 | Local database |
| androidx.work-runtime-ktx | 2.12.0 | Apache-2.0 | Background sync and reminders |
| androidx.camera (camera2, lifecycle, view) | 1.7.0 | Apache-2.0 | Camera |
| com.google.mlkit:barcode-scanning | 17.3.0 | ML Kit Terms of Service (proprietary, free) | VIN barcode and QR scanning (bundled model) |
| com.google.mlkit:text-recognition | 16.0.1 | ML Kit Terms of Service | Printed VIN and door-sticker text (bundled model) |
| com.squareup.okhttp3:okhttp | 5.5.1 | Apache-2.0 | HTTP |
| kotlinx-serialization-json | 1.13.0 | Apache-2.0 | JSON |
| kotlinx-coroutines-android | 1.11.0 | Apache-2.0 | Async work |

**Release runtime:** 191 resolved artifacts in the `releaseRuntimeClasspath` lock entries. Vulnerable transitive versions are forced to patched ones in `build.gradle.kts` (Guava 33.8.0, commons-lang3 3.22.0, httpclient 4.5.14, Bouncy Castle 1.87).

**Tests only:** JUnit 4.13.2, Robolectric 4.18.1, Compose UI Test, work-testing 2.12.0, androidx.test core-ktx 1.7.0.

## Web app
**No runtime third-party code.** The only vendored package is **swagger-ui-dist 5.36.0** (Apache-2.0), copied into `server/app/static/docs/vendor` for the login-protected API docs page (`tools/vendor`, `npm ci` + checked by `npm audit`).

## Docker images
- **Server base image:** `python:3.15.2-slim-trixie`, pinned by digest, plus Debian `tesseract-ocr` (Apache-2.0).
- **Optional services:** `caddy:2.11.9-alpine` (Apache-2.0) and `binwiederhier/ntfy:v2.38.2` (Apache-2.0 / GPL-2.0 dual), both pinned by digest.

## CI (GitHub Actions, pinned to commit SHAs)
`actions/checkout` v7.0.1, `actions/setup-java` v6.3.0, `actions/setup-python` v7.1.0, `actions/upload-artifact` v7.0.2 and `actions/download-artifact` v8.0.2; CodeQL, Dependabot.

## Observations
- **Dependency hygiene is strong:** a minimal set, exact pins, hashes and lockfiles, digest-pinned images, SHA-pinned actions, scanners in CI, and Dependabot with a cooldown.
- **Licence compliance needs attention before commercial distribution:** pillow-heif's bundled **x265 (GPL)**, **fpdf2 (LGPL-3.0)**, and the **ML Kit Terms of Service**. There's also no third-party notices file in the app. See [Commercial Readiness](Commercial-Readiness).
