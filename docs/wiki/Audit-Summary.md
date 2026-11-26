# Audit Summary

**Version audited:** 1.0.19 (commit `8edaaae`, 26 Nov 2026) · **Rule:** documentation and recommendations only. **No code was changed during the audit.**

## Overall assessment

| Area | Rating | In one sentence |
|---|---|---|
| Architecture | **Good** | One server codebase serves Docker, the desktop and the web; an offline-first Android app with clean layering; a single shared write path for API and sync. |
| Security | **Good, with LAN gaps** | Argon2id, hashed tokens, CSRF, strict CSP, upload sanitisation, PDF sandbox, hash-locked supply chain, signed self-update, no telemetry; plain HTTP on the home network is the main weakness. |
| Data safety | **Needs work** | No backup or export anywhere, an instant destructive sign-out on the phone, and no migration framework for future schema changes. |
| Sync | **Sound for households** | Idempotent, ownership-checked, tested live end to end; conflicts are whole-record "newest edit wins" decided by device clocks, with some silent outcomes. |
| Tests | **Good coverage, incomplete automation** | 62 server and 28 Android tests plus release checks — but the server tests and security scans are **not run by CI**. |
| Commercial readiness | **Not yet** | No licence, GPL code bundled through pillow-heif, unsigned desktop builds, a self-updater not allowed on Google Play, no account deletion or password reset, no privacy policy. |

## Top 12 findings (ranked)

| # | ID | Severity | Finding | Recommendation |
|---|---|---|---|---|
| 1 | DL-1 | High | Phone **Sign out** wipes all unsynced data instantly — no confirmation, no final sync | Confirm, sync first, warn on failure |
| 2 | DL-2 | High | **Phone-only mode has no backup or export**; a lost phone loses everything | Export/Import ZIP (+ optional encryption) |
| 3 | L-1 | Blocker (commercial) | **No LICENSE / EULA** | Choose a licensing model and add it |
| 4 | L-2 | Blocker (commercial) | **pillow-heif bundles GPL x265** in the desktop and Docker distributions | Decode-only HEIC or a commercial licence |
| 5 | §9 | High | **Server tests (62) and security scans aren't run in CI**; the desktop bundles ship server code untested by CI | Add a required server-tests and scan job before publishing |
| 6 | DL-3 | Medium | **No server/desktop backup or tested restore** | "Backup now" (`VACUUM INTO` + uploads) + restore test |
| 7 | SEC-1 | Medium–High | **Plain HTTP on the LAN by default** exposes passwords, tokens and QR codes on shared Wi-Fi | HTTPS by default (pinned self-signed via QR, or Tailscale) |
| 8 | DB-2 / DB-3 | Medium | **No migration framework** (server `create_all`, Room v1 without migrations) | Alembic + Room migrations with tests before the next schema change |
| 9 | SEC-4 | Medium | **No password reset, admin reset, MFA or device revoke** | Add account-management essentials |
| 10 | SY-1/2/3 | Medium | **Sync conflicts** are whole-record, decided by device clocks, and rejected records diverge silently | Server versions or field-level merge; surface rejected/stuck items |
| 11 | SEC-2 / SEC-3 | Medium | **ntfy open read-write** by default; **desktop listens on all networks** when phone sync is on | ntfy deny-all + token; bind to the private interface only |
| 12 | DL-4 | Medium | **"Replace earlier Carfax import"** also deletes imported records the user edited later | Skip or confirm edited records |

## What is already done well
- **Supply chain:**
  - every dependency pinned and hash-locked;
  - Docker images pinned by digest and Actions by commit SHA;
  - Dependabot with a cooldown, CodeQL;
  - at audit time: zero known vulnerabilities, zero Semgrep findings, zero leaked secrets.
- **Defence in depth on uploads:** type sniffing, re-encoding with EXIF/GPS stripped, a sandboxed PDF parser, random storage names.
- **Privacy:** no telemetry; a small, fully documented list of outbound calls; the Keystore-encrypted token; no backups of phone data to Google.
- **Release integrity:** a signed APK, certificate continuity checked before self-update, and checksums for every download. One release contains every platform, and release notes are enforced.
- **Real-world testing culture:** Robolectric screen tests that tap through the actual app; APK checks that catch shrinker problems; a live sync test against a real server.

## How to use this audit
- **Detailed pages:** Phase 1 (the [Architecture Overview](Architecture-Overview) and its sub-pages), Phase 2 ([Code Quality Audit](Code-Quality-Audit)) and Phase 3 ([Commercial Readiness](Commercial-Readiness)).
- **Decisions stay with the owner:** each finding has an ID so you can pick which ones to approve. **None has been implemented.**
