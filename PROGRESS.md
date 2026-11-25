# Ratchet Wrench — Progress

A new session can read this file and continue where the last one stopped.

## Status

| Phase | What | Status |
|---|---|---|
| 1 | Server, database, API for all features | ✅ Done |
| 2 | Logo, icon set, web app | ⏳ Next |
| 3 | Android app (offline sync, VIN barcode, sticker scan, local notifications) | ⏳ |
| 4 | Final security review, README, screenshots | ⏳ |

## Phase 1 — done
- FastAPI server in `server/app` (see the top-of-file comments in each module).
- Features: auth (Argon2id, lockout, per-IP rate limit, expiring/revocable tokens, CSRF), vehicles, specs with source labels, maintenance/repair entries, attachments (EXIF strip, type sniffing, sandboxed PDF parsing), reminders (miles/months, whichever first), odometer, parts, manufacturer links, VIN decode (NHTSA + paid-provider plug-in), door-sticker OCR (Tesseract) + optional AI vision, Carfax import with review, history report PDF, sync pull/push, ntfy push, login-protected Swagger docs, sample data.
- Tests: `server/tests` — 51 passing (Python 3.13 locally, and in a Python 3.15 container).
- Docker: `server/Dockerfile` (python:3.15.2-slim-trixie by digest, non-root, hash-locked deps), `docker-compose.yml` (read-only, cap_drop ALL, optional Caddy and ntfy profiles), `.env.example`.
- Security scans (`scripts/security-scan.sh`): pip-audit, npm audit, OSV-Scanner, Trivy (image + config), Semgrep, Gitleaks — all passing. Trivy: 0 fixable vulnerabilities (unfixed Debian items are listed for SECURITY.md).
- GitHub: `.github/dependabot.yml`, `.github/workflows/codeql.yml`.

## How to run things (for the next session)
```bash
# tests
cd server && python3 -m venv .venv && .venv/bin/pip install --require-hashes -r requirements.txt \
  && .venv/bin/pip install --require-hashes --no-deps -r requirements-dev.txt && .venv/bin/python -m pytest -q
# scans
scripts/security-scan.sh
# regenerate catalog after editing catalog/build_catalog.py
python3 catalog/build_catalog.py
```
Build-sandbox notes: the sandbox blocks dl.google.com, api.osv.dev, semgrep.dev and vpic.nhtsa.dot.gov. Workarounds used: OSV offline DB (`OSV_OFFLINE=1`), local semgrep-rules clone (`SEMGREP_RULES_DIR`), Trivy with `TRIVY_DOCKER_OPTS` proxy settings, Android SDK copied from the `cimg/android` Docker image. Docker builds there need a temporary Dockerfile copy that routes apt through the proxy (not committed).

## Next steps
1. Phase 2: design the SVG logo (`brand/`) and the full icon set (`icons/types/*.svg`, one per catalog type), add `server/tests/test_icons.py`, then build the web app in `web/`.
2. Phase 3: Android app in `android/`.
3. Phase 4: README with screenshots, SECURITY.md, final scans.
