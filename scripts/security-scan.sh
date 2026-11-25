#!/usr/bin/env bash
# security-scan.sh — runs every security scanner used by Ratchet Wrench.
#
# What it checks:
#   1. pip-audit    known vulnerabilities in the server's Python packages
#   2. npm audit    known vulnerabilities in the vendored Swagger UI package
#   3. OSV-Scanner  known vulnerabilities in ALL lock files (Python, npm,
#                   Android Gradle) using Google's OSV database
#   4. Trivy        vulnerabilities in the Docker image (OS + Python packages)
#   5. Semgrep      insecure code patterns (OWASP rules) in Python, JS, Kotlin
#   6. Gitleaks     passwords/keys accidentally committed anywhere in git history
#
# Usage (from the repository root):
#   scripts/security-scan.sh             # everything
#   SKIP_TRIVY=1 scripts/security-scan.sh
#
# Needs: Python 3, Node/npm, Docker, plus the tools below on your PATH
# (install hints are printed if one is missing):
#   pip install pip-audit semgrep
#   go install github.com/google/osv-scanner/v2/cmd/osv-scanner@latest
#   go install github.com/zricethezav/gitleaks/v8@latest
#   Trivy runs from its official Docker image (aquasec/trivy).
#
# Results are written to ./scan-results/ (ignored by git).  The script exits
# non-zero if any scanner reports a problem.

set -u
cd "$(dirname "$0")/.."
OUT=scan-results
mkdir -p "$OUT"
FAILED=()
IMAGE="${IMAGE:-ratchet-wrench:latest}"

have() { command -v "$1" >/dev/null 2>&1; }
step() { printf '\n==== %s ====\n' "$1"; }
fail() { FAILED+=("$1"); }

step "1. pip-audit (Python packages)"
if have pip-audit; then
  pip-audit --strict --require-hashes --disable-pip -r server/requirements.txt -f columns -o "$OUT/pip-audit.txt" || fail pip-audit
  pip-audit --strict --disable-pip --no-deps -r server/requirements-dev.txt >>"$OUT/pip-audit.txt" 2>&1 || fail pip-audit-dev
  cat "$OUT/pip-audit.txt"
else echo "pip-audit not installed (pip install pip-audit)"; fail pip-audit-missing; fi

step "2. npm audit (vendored browser assets)"
(cd tools/vendor && npm audit --audit-level=low) | tee "$OUT/npm-audit.txt" || fail npm-audit

step "3. OSV-Scanner (all lock files incl. Android Gradle)"
if have osv-scanner; then
  # --offline-vulnerabilities uses a downloaded copy of the OSV database.
  OSV_ARGS=(scan source -r --allow-no-lockfiles .)
  [ "${OSV_OFFLINE:-0}" = "1" ] && OSV_ARGS=(scan source --offline-vulnerabilities --download-offline-databases -r --allow-no-lockfiles .)
  osv-scanner "${OSV_ARGS[@]}" | tee "$OUT/osv-scanner.txt"; [ "${PIPESTATUS[0]}" = "0" ] || fail osv-scanner
else echo "osv-scanner not installed"; fail osv-missing; fi

step "4. Trivy (Docker image $IMAGE)"
if [ "${SKIP_TRIVY:-0}" != "1" ]; then
  docker run --rm ${TRIVY_DOCKER_OPTS:-} -v /var/run/docker.sock:/var/run/docker.sock -v "${HOME}/.cache/trivy:/root/.cache/" \
    aquasec/trivy:latest image --scanners vuln --ignore-unfixed --severity LOW,MEDIUM,HIGH,CRITICAL \
    --exit-code 1 "$IMAGE" | tee "$OUT/trivy.txt"; [ "${PIPESTATUS[0]}" = "0" ] || fail trivy
  # Informational: everything, including issues that have no fix yet.
  docker run --rm ${TRIVY_DOCKER_OPTS:-} -v /var/run/docker.sock:/var/run/docker.sock -v "${HOME}/.cache/trivy:/root/.cache/" \
    aquasec/trivy:latest image --scanners vuln,secret,misconfig "$IMAGE" >"$OUT/trivy-all.txt" 2>&1 || true
  docker run --rm ${TRIVY_DOCKER_OPTS:-} -v "$PWD:/src:ro" -v "${HOME}/.cache/trivy:/root/.cache/" aquasec/trivy:latest \
    config --exit-code 1 /src | tee "$OUT/trivy-config.txt"; [ "${PIPESTATUS[0]}" = "0" ] || fail trivy-config
fi

step "5. Semgrep (code patterns)"
if have semgrep; then
  if [ -n "${SEMGREP_RULES_DIR:-}" ]; then
    # Offline: a local clone of https://github.com/semgrep/semgrep-rules
    # Only the SECURITY rule folders (the "maintainability" folders are
    # style checks that report false positives on this code base).
    R="$SEMGREP_RULES_DIR"
    CONFIGS=(--config "$R/python/lang/security" --config "$R/python/fastapi" --config "$R/python/sqlalchemy"
             --config "$R/python/requests" --config "$R/python/cryptography" --config "$R/python/jwt"
             --config "$R/javascript/browser" --config "$R/javascript/lang/security"
             --config "$R/kotlin/lang/security" --config "$R/java/android" --config "$R/dockerfile"
             --config "$R/generic/secrets" --config "$R/html/security" --config "$R/yaml/docker-compose"
             --config "$R/yaml/github-actions")
  else
    CONFIGS=(--config p/security-audit --config p/owasp-top-ten --config p/python --config p/javascript
             --config p/kotlin --config p/dockerfile --config p/secrets)
  fi
  semgrep scan --metrics=off --error --quiet "${CONFIGS[@]}" \
    --exclude 'server/app/static/docs/vendor' --exclude 'tools/vendor/node_modules' --exclude 'scan-results' \
    --exclude 'server/tests' --exclude 'android/**/build' . | tee "$OUT/semgrep.txt"; [ "${PIPESTATUS[0]}" = "0" ] || fail semgrep
else echo "semgrep not installed (pip install semgrep)"; fail semgrep-missing; fi

step "6. Gitleaks (secrets in full git history)"
if have gitleaks; then
  gitleaks git --redact --no-banner . | tee "$OUT/gitleaks.txt"; [ "${PIPESTATUS[0]}" = "0" ] || fail gitleaks
else echo "gitleaks not installed"; fail gitleaks-missing; fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "ALL SCANS PASSED"
else
  echo "PROBLEMS FOUND IN: ${FAILED[*]}  (details in $OUT/)"
  exit 1
fi
