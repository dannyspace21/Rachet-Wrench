# Ratchet Wrench — Technical Audit Wiki

This wiki holds an **independent audit** of Ratchet Wrench in three phases: architecture, code quality and commercial readiness.

> **Audit rule: nothing was changed.** The audit only *reads* the code, configuration, build files and test results, and writes down what it finds. No application code, configuration or architecture was modified. Every recommendation is a proposal for the owner to decide on.

| | |
|---|---|
| **Audited version** | 1.0.19 (commit `8edaaae`, 26 Nov 2026) |
| **Scope** | Server (FastAPI), web app, Android app, desktop app (Windows/macOS/Linux), Docker deployment, release pipeline |
| **Method** | Reading the source, reading the running server's live OpenAPI description, running the existing test suites and security scanners, checking dependency licences, checking manufacturer links over the network |

## Pages

**Start here**
- [Audit Summary](Audit-Summary) — the top risks and recommendations on one page

**Phase 1 — Architecture**
- [Architecture Overview](Architecture-Overview) — languages, frameworks, how the parts fit together
- [Backend](Backend)
- [Database](Database)
- [Android App](Android-App)
- [Desktop App](Desktop-App)
- [Docker](Docker)
- [Synchronization](Synchronization)
- [API Endpoints](API-Endpoints)
- [Authentication and Security](Authentication-and-Security)
- [Data Storage](Data-Storage)
- [External Integrations](External-Integrations)
- [Dependencies](Dependencies)

**Phase 2 — [Code Quality Audit](Code-Quality-Audit)**

**Phase 3 — [Commercial Readiness](Commercial-Readiness)**

A PDF of the whole audit is attached to the audit's delivery.
