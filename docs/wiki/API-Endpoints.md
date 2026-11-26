# API Endpoints

Taken from the running server's own OpenAPI description (`/api/openapi.json`, login required). **64 endpoints.** The interactive docs are at `/api/docs` (also login-protected).

**Authentication:** every endpoint needs a login (phone: `Authorization: Bearer <token>`; web: session cookie + `X-CSRF-Token` on writes) **except** those marked *public*. Every record endpoint only ever sees the signed-in user's own data.

The seven record kinds (`vehicles`, `entries`, `specs`, `reminders`, `odometer`, `parts`, `attachments`) share one generic set of routes: list (`?vehicle_id=` filter), get, create, `PATCH` update and soft `DELETE`.

## Accounts and logins

| Method | Path | Access | What it does |
|---|---|---|---|
| `POST` | `/api/auth/change-password` | login | Change your password.  All OTHER logins (other devices) are signed out. |
| `POST` | `/api/auth/login` | public (rate-limited, lockout) | Check username + password.  Web: sets secure cookies.  Android: returns the token in the response body (stored |
| `POST` | `/api/auth/logout` | login | End this login (the token stops working immediately). |
| `GET` | `/api/auth/me` | login | Me |
| `POST` | `/api/auth/pair` | login | Make a one-time QR code that connects a phone to this account without typing the address, username or password |
| `POST` | `/api/auth/pair/claim` | public (one-time QR code; rate-limited) | The phone sends the code from the QR code and gets its own login. |
| `GET` | `/api/auth/settings` | login | Get User Settings |
| `PUT` | `/api/auth/settings` | login | Put User Settings |
| `POST` | `/api/auth/settings/test-push` | login | Send a test ntfy notification to your topic. |
| `POST` | `/api/auth/setup` | public (one-time setup code, only while no account exists) | Create the FIRST account.  Only works while there are no accounts, and only with the one-time setup code the d |
| `GET` | `/api/auth/setup-status` | public | Does this server still need its first account created on the web? |
| `GET` | `/api/auth/users` | admin | List Users |
| `POST` | `/api/auth/users` | admin | Create User |

## Vehicles (records + vehicle tools)

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/vehicles` | login | List vehicles |
| `POST` | `/api/vehicles` | login | Create vehicles |
| `GET` | `/api/vehicles/{item_id}` | login | Get one of vehicles |
| `PATCH` | `/api/vehicles/{item_id}` | login | Update one of vehicles |
| `DELETE` | `/api/vehicles/{item_id}` | login | Delete one of vehicles |
| `POST` | `/api/vehicles/{vehicle_id}/apply-specs` | login | Save approved spec values with a source label (vin / provider / user). |
| `POST` | `/api/vehicles/{vehicle_id}/carfax/apply` | login | Save the entries the user approved on the review screen, then (if asked) add odometer readings and bring remin |
| `POST` | `/api/vehicles/{vehicle_id}/carfax/parse` | login | Read a Carfax PDF and propose entries for the review screen. The PDF is also stored on the vehicle for your re |
| `GET` | `/api/vehicles/{vehicle_id}/links` | login | Official manufacturer links.  Your per-vehicle overrides win. |
| `GET` | `/api/vehicles/{vehicle_id}/report.pdf` | login | Download the full maintenance & repair history as a PDF. |
| `GET` | `/api/vehicles/{vehicle_id}/specs-table` | login | Every spec field from the catalog with its current value (or blank). |
| `POST` | `/api/vehicles/{vehicle_id}/sticker/apply` | login | Save the approved fields, labelled 'From door sticker'. |
| `POST` | `/api/vehicles/{vehicle_id}/sticker/scan` | login | Save the sticker photos to the vehicle and read text from them. Returns PROPOSED fields; nothing is saved to s |

## Entries

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/entries` | login | List entries |
| `POST` | `/api/entries` | login | Create entries |
| `GET` | `/api/entries/{item_id}` | login | Get one of entries |
| `PATCH` | `/api/entries/{item_id}` | login | Update one of entries |
| `DELETE` | `/api/entries/{item_id}` | login | Delete one of entries |

## Specs

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/specs` | login | List specs |
| `POST` | `/api/specs` | login | Create specs |
| `GET` | `/api/specs/{item_id}` | login | Get one of specs |
| `PATCH` | `/api/specs/{item_id}` | login | Update one of specs |
| `DELETE` | `/api/specs/{item_id}` | login | Delete one of specs |

## Reminders

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/reminders` | login | List reminders |
| `POST` | `/api/reminders` | login | Create reminders |
| `GET` | `/api/reminders/{item_id}` | login | Get one of reminders |
| `PATCH` | `/api/reminders/{item_id}` | login | Update one of reminders |
| `DELETE` | `/api/reminders/{item_id}` | login | Delete one of reminders |

## Odometer

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/odometer` | login | List odometer |
| `POST` | `/api/odometer` | login | Create odometer |
| `GET` | `/api/odometer/{item_id}` | login | Get one of odometer |
| `PATCH` | `/api/odometer/{item_id}` | login | Update one of odometer |
| `DELETE` | `/api/odometer/{item_id}` | login | Delete one of odometer |

## Parts

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/parts` | login | List parts |
| `POST` | `/api/parts` | login | Create parts |
| `GET` | `/api/parts/{item_id}` | login | Get one of parts |
| `PATCH` | `/api/parts/{item_id}` | login | Update one of parts |
| `DELETE` | `/api/parts/{item_id}` | login | Delete one of parts |

## Attachments

| Method | Path | Access | What it does |
|---|---|---|---|
| `POST` | `/api/attachments` | login | Upload one file and attach it to a vehicle (and optionally an entry). |
| `GET` | `/api/attachments` | login | List attachments |
| `GET` | `/api/attachments/{attachment_id}/file` | login | Download the original (cleaned) file.  PDFs are always sent as a download, never displayed inline, so the brow |
| `GET` | `/api/attachments/{attachment_id}/thumb` | login | A small JPEG preview of a photo (for lists and galleries). |
| `GET` | `/api/attachments/{item_id}` | login | Get one of attachments |
| `PATCH` | `/api/attachments/{item_id}` | login | Update one of attachments |
| `DELETE` | `/api/attachments/{item_id}` | login | Delete one of attachments |

## Sync (phone)

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/sync` | login | Return all records changed after `since` (all records if omitted). |
| `POST` | `/api/sync` | login | Apply a batch of offline changes from the phone. |

## VIN

| Method | Path | Access | What it does |
|---|---|---|---|
| `POST` | `/api/vin/decode` | login | Decode a VIN with NHTSA (and the optional paid provider).  Nothing is saved; the app shows the result for the  |

## Make / model lists

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/vehicle-models` | login | Model names for a make (and year) from NHTSA, for the Add vehicle form. |

## Catalog

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/catalog` | login | Maintenance types, spec fields and manufacturer links. |

## Health

| Method | Path | Access | What it does |
|---|---|---|---|
| `GET` | `/api/health` | public | Used by Docker to check the server is alive.  Reveals nothing. |

## Limits applied to every request
- Request body size limit: the larger of `RW_MAX_IMAGE_MB` × 6 and `RW_MAX_PDF_MB`, plus 2 MB (122 MB by default).
- JSON bodies are validated with unknown fields rejected; strings and numbers have explicit maximums.
- Uploads: images up to `RW_MAX_IMAGE_MB` (20 MB), PDFs up to `RW_MAX_PDF_MB` (40 MB) and `RW_MAX_PDF_PAGES` (1,500); PDF parsing timeout `RW_PDF_PARSE_TIMEOUT_SECONDS` (30 s).
