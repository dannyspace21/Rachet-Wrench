# Authentication and Security

The full security design is in the repository's `SECURITY.md`. This page summarises it and records the audit's view.

## Accounts and passwords
- **Password hashing:** Argon2id (argon2-cffi), with automatic re-hashing when parameters change.
- **Password rules:** at least 10 characters, and not the same as the username.
- **Lockout:** 5 failed logins lock the account for 15 minutes.
- **Rate limit:** 10 login attempts per minute per IP (in-memory).
- **No user enumeration:** "unknown user" and "wrong password" return the same message.
- **Account creation:**
  - **Docker:** the first admin comes from `.env`.
  - **Desktop:** the first account is created on a setup page protected by a one-time code that changes at every start.
  - **Further users:** an admin creates them (`POST /api/auth/users`).
- **Missing account features:** there's **no password reset** ("forgot password"), **no account deletion**, and no multi-factor authentication.

## Sessions and tokens
- **Tokens:** 256-bit random; only their SHA-256 hash is stored. They expire after 30 days (`RW_TOKEN_TTL_HOURS`), stop working at logout, and changing the password revokes all other tokens.
- **Web:**
  - the session cookie is `HttpOnly`, `SameSite=Strict`, and `Secure` when served over HTTPS;
  - cookie-authenticated writes need a double-submit CSRF header;
  - the API only accepts JSON or multipart bodies.
- **Phone:** the token is encrypted with AES-256-GCM using a key held in the **Android Keystore** (hardware-backed on most phones). App data is excluded from backups.
- **QR pairing:** a signed-in user creates a 192-bit one-time code. It's stored hashed, valid for 10 minutes, used once, and exchanged for a phone token through a rate-limited endpoint.

## Transport
- **HTTPS:** available through Caddy (Docker profile) or Tailscale.
- **Plain HTTP is common in practice:** the desktop app and a default Docker install speak plain HTTP on the home network.
- **The phone's rules:**
  - HTTPS is always allowed;
  - plain HTTP is allowed only to private addresses, only after the user agrees;
  - redirects are never followed;
  - every request must go to the configured host and port.

## Web hardening
- **Strict CSP:** `default-src 'self'`, no inline script, `frame-ancestors 'none'`.
- **Other headers:** HSTS, `X-Frame-Options: DENY`, `nosniff`, `no-referrer`, a restrictive `Permissions-Policy`, and COOP/CORP.
- **CORS:** closed unless configured.
- **API docs:** behind login.

## Uploads
- **Intake checks:** magic-byte type detection, and size and page limits.
- **Images:** decoded and re-encoded with Pillow, which removes EXIF/GPS and anything hidden. HEIC is converted.
- **PDFs:** parsed only in a separate process with memory/CPU/file limits (Unix) and a timeout, then stored as-is. They're always sent as downloads, never displayed inline.
- **Storage:** random names in a sharded folder; path traversal is impossible by construction and re-checked.

## Supply chain and release integrity
- **Pinned and locked:**
  - Python dependencies are hash-locked (`uv pip compile --generate-hashes`);
  - Android dependencies are locked (`gradle.lockfile`);
  - Docker base images are pinned by digest;
  - GitHub Actions are pinned to commit SHAs.
- **Scanners run** (`scripts/security-scan.sh`): pip-audit, npm audit, OSV-Scanner (including the Gradle lockfile), Semgrep, Gitleaks over the full history, and Trivy (container). **Results at this audit: no known vulnerabilities, no code findings, no leaked secrets** (Trivy wasn't re-run, because the image dependencies are unchanged).
- **On GitHub:** Dependabot with a 7-day cooldown, and CodeQL.
- **Releases:**
  - the APK is signed with a permanent key restored from GitHub secrets, and signature-checked in CI;
  - the phone verifies the SHA-256, package name, version and **identical signing certificate** before self-updating.

## Audit view
- **Above average** for a self-hosted app: layered defences, tested security behaviour (40 security-focused server tests), and clear documentation.
- **Main gaps:**
  - plain HTTP on the LAN by default (tokens and QR codes can be sniffed on the same Wi-Fi);
  - no password reset or account deletion;
  - the in-memory rate limiter;
  - no MFA;
  - ntfy open by default;
  - no PDF-helper resource limits on Windows.

  See [Code Quality Audit](Code-Quality-Audit) for the ranked list.
