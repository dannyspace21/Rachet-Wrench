# External Integrations

Ratchet Wrench has **no telemetry, analytics, ads or crash upload**. These are the only outbound connections, all documented in `SECURITY.md`:

| # | Service | Used for | Who calls | Data sent | Can be turned off |
|---|---|---|---|---|---|
| 1 | **NHTSA vPIC** (`vpic.nhtsa.dot.gov`, U.S. government) | VIN decoding; model names for a make and year | Server/desktop; the phone directly in phone-only mode and for model lists | The VIN, or the make and year | `RW_NHTSA_ENABLED=false`; on the phone, don't use these features |
| 2 | **ntfy** (operator's own server) | Push alerts for due reminders | Server | Reminder title, vehicle name, miles/days left | Off unless `RW_NTFY_URL` is set |
| 3 | **Anthropic Claude API** (`api.anthropic.com`) | Optional AI reading of door-sticker photos | Server | The sticker photo | Off by default (`RW_AI_VISION_ENABLED=false`); per-scan warning |
| 4 | **GitHub** (`api.github.com`, `github.com`, release asset hosts) | Android self-update | Android app | Standard requests for the latest release; nothing about the user | Settings → App updates → off |
| — | **Optional paid VIN spec provider** (`RW_VIN_SPEC_*`) | Extra specs | Server | The VIN | Not configured by default |

**Links the user opens** (not fetched by the app):
- manufacturer parts sites and owner portals (catalog);
- GitHub Issues for **Report a bug** (a pre-filled form; the user submits it).

**On-device processing** (no network):
- ML Kit barcode and text recognition, with bundled models;
- Tesseract OCR (server only).

## Observations
- **Narrow and well documented:** the outbound surface is small, and each call is opt-in or user-triggered.
- **NHTSA calls are unauthenticated and rate-limited by NHTSA.** Model lists are cached in memory only, so heavy use could be throttled.
- **Manufacturer links can break.** Several were found dead during this audit and fixed in 1.0.19. Honda Powersports and BMW Motorrad couldn't be verified automatically. A periodic link check is recommended.
