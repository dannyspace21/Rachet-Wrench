# Phase 3 — Commercial Readiness

What would be required to **distribute Ratchet Wrench commercially** (sold, or given away by a business, to people outside the owner's household). This is a technical and practical checklist, **not legal advice**: items marked ⚖️ need a lawyer's review.

**Current state in one line:** technically solid and unusually security-conscious for a self-hosted app, but missing the legal, licensing, store and support groundwork a commercial product needs.

---

## 1. Licensing — blockers to resolve first

| # | Item | Why it matters | What to do |
|---|---|---|---|
| L-1 ⚖️ | **The project has no LICENSE file.** | Without a licence, nobody else may legally use, copy or redistribute the software, and the terms for customers are undefined. | Choose a model: a proprietary EULA (closed, paid), or an open-source licence plus a commercial offering (for example AGPL + a paid licence, or Apache-2.0 + paid hosting/support). Add `LICENSE`, and an EULA for the binaries. |
| L-2 ⚖️ | **pillow-heif bundles libx265, which is GPL-2.0+**, together with libheif/libde265 (LGPL-3.0). It ships inside the **desktop bundles** and any distributed **Docker image**. | Distributing GPL code inside a proprietary product generally requires releasing the whole combined work under the GPL, or obtaining a commercial x265 licence. The app only needs HEIC *decoding* (x265 is the *encoder*). | Replace it with a decode-only HEIC path: a libheif build without x265, or converting HEIC on the phone and dropping server-side HEIC. Or buy a commercial x265 licence. Re-check the bundle with a licence scanner (for example ScanCode). |
| L-3 ⚖️ | **fpdf2 is LGPL-3.0.** | LGPL allows use in proprietary software, but users must be able to replace the library, and its licence text and source availability must be provided. | The PyInstaller one-folder bundle keeps fpdf2 replaceable. Ship the licence text and a source offer, or replace it with a permissively licensed PDF writer. |
| L-4 ⚖️ | **ML Kit is under Google's ML Kit Terms of Service** (not open source), and Google Play has its own policies. | These terms apply to commercial use; their attribution and data rules must be followed. | Review the ML Kit terms; keep on-device models (already done); add attributions. |
| L-5 | **No third-party notices file or "Open-source licences" screen.** | Apache-2.0 (AndroidX, OkHttp, …), MIT, BSD and LGPL all require keeping notices with distributed binaries. | Generate a notices file at build time (for example the AboutLibraries Gradle plugin; `pip-licenses` for Python) and show it in Settings → About and the web app. |
| L-6 ⚖️ | **Names and trademarks:** "Carfax", vehicle manufacturers' names, and the product name "Ratchet Wrench". | Using third-party marks to describe compatibility is usually acceptable (nominative use), but not in a way that suggests endorsement. The product name itself should be cleared. | Trademark search and registration for the product name; a neutral wording review ("Import a Carfax® service history PDF"); no manufacturer logos (none are used today). |

## 2. Distribution channels

### Android
| Item | Status | Required for commercial distribution |
|---|---|---|
| Google Play | Not published (APK on GitHub) | Publish as an **Android App Bundle**, use **Play App Signing**, and complete the **Data safety** form, **privacy policy URL**, content rating and target-API compliance (targetSdk 37 is current). |
| Self-update | Uses `REQUEST_INSTALL_PACKAGES` and downloads APKs from GitHub | **Not allowed on Google Play** (apps distributed on Play must update through Play). Build a *Play flavour* without the updater and keep the current flavour for direct downloads. |
| Device coverage | minSdk 29 (Android 10+) | Test on a device matrix (Firebase Test Lab or similar): camera screens, small screens, tablets, Android 10–17. |
| Signing key | Permanent key in GitHub secrets | Keep an offline backup. For Play, enrol in Play App Signing (upload key ≠ app signing key). |

### Desktop
| Item | Status | Required |
|---|---|---|
| Windows | Unsigned zip | **Authenticode code-signing certificate** (preferably EV) to avoid SmartScreen warnings; an installer (MSI/MSIX); optionally the Microsoft Store. |
| macOS | Unsigned zip, Apple silicon only | **Apple Developer ID signing + notarisation** (otherwise Gatekeeper blocks it); add an Intel/universal build; a DMG. |
| Linux | tar.gz | AppImage / Flatpak / .deb with signatures. |
| Updates | Manual download | An in-app update check with signed update manifests (the Android updater's model can be reused). |

### Server (Docker)
| Item | Status | Required |
|---|---|---|
| Image | Built locally by each user | Publish signed images (GHCR + cosign), with an SBOM (CycloneDX/SPDX) and release notes per image tag. |
| HTTPS | Optional Caddy profile | Make HTTPS the default path in the docs and onboarding. |

## 3. Privacy and data protection ⚖️
- **Privacy policy:** none is published yet. It's needed for the app stores and for GDPR/CCPA. The app's actual data flows are small and well documented in `SECURITY.md` (no telemetry), which makes the policy easy to write.
- **User rights:**
  - **Deletion:** there's no account-deletion endpoint or button. One is needed (GDPR right to erasure; Google Play requires in-app and web account deletion for apps with accounts).
  - **Export:** there's no export. Data portability is needed (see Backup, Code Quality §7).
- **Processor terms:**
  - **AI vision:** if a hosted version uses Anthropic, a data-processing agreement is needed and the opt-in warning should be kept.
  - **NHTSA:** a public API; only VINs or make names are sent.
- **Report a bug:** reports go to a **public** GitHub repository, so users could post personal data. Use a private intake for a commercial product (a private repository, a help-desk form, or a support email), and add a "don't include personal details" notice (already in the template).

## 4. Product and operations gaps

| Area | Gap | Recommendation |
|---|---|---|
| **Accounts** | No password reset, no admin reset of others, no MFA, no device/session list | Add them before selling (Code Quality SEC-4). Password reset by email needs an email service. |
| **Backup** | None built in; phone-only data is at risk | Backup/restore and phone export/import (Code Quality §7) are prerequisites for paying customers. |
| **Data safety on upgrades** | No migration framework (server) and no Room migrations | Required before shipping schema changes to customers (DB-2, DB-3). |
| **Hosted / multi-user offering** (if planned) | SQLite, in-memory rate limits, an in-process reminder loop, no paging, no tenant isolation beyond `owner_id` | PostgreSQL; a shared rate-limit store; a separate worker; paging; per-tenant quotas; monitoring; a penetration test. |
| **Support** | GitHub Issues only | A support channel and SLA; crash reporting stays **opt-in** (respecting the no-telemetry promise); a status page for any hosted service. |
| **Quality gates** | Server tests and scanners not in CI; no web E2E; no instrumented camera tests | See Code Quality §9; add them before customer releases. |
| **Accessibility** | Partial (labels, large touch targets, dark mode) | A WCAG 2.2 AA review for the web; TalkBack and font-scaling review on Android. |
| **Localisation** | English only; miles/km and currency supported | Externalise strings (Android `strings.xml` and web i18n) if selling outside English-speaking markets. |
| **Documentation** | Strong technical docs; no end-user manual | A user guide, FAQ and troubleshooting (much already exists in `docs/`). |
| **Liability** ⚖️ | Reminders and specs could be taken as advice | Disclaimers in the EULA and the app ("intervals are typical; follow your owner's manual"; specs are labelled by source already). |
| **Security assurance** | Strong internal practices | An independent penetration test; a vulnerability disclosure policy with a contact (add to `SECURITY.md`); signed releases for every artefact. |

## 5. Suggested order of work

1. **Legal foundation:** choose and add a licence/EULA (L-1); resolve x265 (L-2); fpdf2 and ML Kit compliance (L-3, L-4); notices screen (L-5); privacy policy; trademark check.
2. **Protect customer data:** backup/restore and phone export (DL-2, DL-3); sign-out confirmation with final sync (DL-1); migrations (DB-2, DB-3).
3. **Account essentials:** password reset, account deletion, device list and revoke, optional MFA.
4. **Quality gates in CI:** server tests, scanners, web E2E, live sync test, emulator tests for the camera screens.
5. **Distribution:** Play flavour without the self-updater; signed and notarised desktop installers; published signed Docker images with an SBOM.
6. **Secure by default on the LAN:** HTTPS for desktop and Docker; ntfy locked down; desktop bound to the private interface only.
7. **Support and operations:** support channel, private bug intake, user guide, disclosure policy, penetration test.
