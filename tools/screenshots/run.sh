#!/usr/bin/env bash
# run.sh — regenerate the README screenshots.
#   1. starts a throw-away server (temporary folder, sample data) on port 8099
#   2. runs capture.cjs (Playwright) for light and dark mode
#   3. renders the sample history report PDF to PNG (needs pdftoppm)
# Needs: the server's Python packages, Node.js with Playwright, Tesseract.
set -euo pipefail
cd "$(dirname "$0")"
PY="${PYTHON:-python3}"
DATA="$(mktemp -d)"
"$PY" make_samples.py
( cd ../../server && RW_DATA_DIR="$DATA" RW_ADMIN_PASSWORD=screenshot-password RW_COOKIE_SECURE=false \
  RW_SEED_SAMPLE_DATA=true "$PY" -m uvicorn app.main:app --port 8099 >"$DATA/server.log" 2>&1 & echo $! > "$DATA/pid" )
trap 'kill "$(cat "$DATA/pid")" 2>/dev/null || true; rm -rf "$DATA"' EXIT
for _ in $(seq 1 30); do curl -sf http://127.0.0.1:8099/api/health >/dev/null && break; sleep 1; done
node capture.cjs
# History report: download as the sample user and render page 1 to PNG.
TOKEN=$(curl -s -X POST http://127.0.0.1:8099/api/auth/login -H 'content-type: application/json' \
  -d '{"username":"admin","password":"screenshot-password","client":"android"}' | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["token"])')
VID=$(curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8099/api/vehicles | "$PY" -c 'import json,sys;print([v for v in json.load(sys.stdin) if v["model"]=="RC 350"][0]["id"])')
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8099/api/vehicles/$VID/report.pdf" -o ../../docs/screenshots/sample-history-report.pdf
pdftoppm -r 110 -png -f 1 -l 2 ../../docs/screenshots/sample-history-report.pdf ../../docs/screenshots/history-report
echo "done"
