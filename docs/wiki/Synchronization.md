# Synchronization

The phone keeps a full offline copy; the server is the meeting point. Implemented in `android/.../sync/SyncEngine.kt` and `server/app/routers/sync.py`, using the shared write path `server/app/records.py`.

## One sync run (phone)
1. **Push:** every record marked `dirty` on the phone goes to `POST /api/sync` in one batch, in dependency order: vehicles → entries → specs → reminders → odometer → parts → attachments metadata. Only whitelisted fields are sent.
   - For each record the server answers `applied`, `kept_server_copy` or `rejected`, plus its final copy. The phone stores the server's copy and clears `dirty`.
2. **Upload:** photos taken on the phone (`localPath`, `has_file=false`) are uploaded with `POST /api/attachments`, using the **phone-made ID**, so a retry can never duplicate. A photo waits until its vehicle/entry has reached the server. A 4xx for one photo skips it (it stays queued).
3. **Pull:** `GET /api/sync?since=<bookmark>` returns every record of the user changed after the bookmark, including soft deletes, plus user settings and `next_since`. The phone overwrites its copy unless it holds a **newer unsent edit**.

**When it runs:**
- when the app opens (Garage);
- pull-to-refresh and **Sync now**;
- after local edits (`requestSync`);
- every 6 hours in the background (WorkManager, network required).

## Conflict rule: "newest edit wins" (per record)
- Each record carries `updated_at` set by the device that edited it.
- The server applies an incoming record unless its own copy has a **newer** `updated_at` (`records.py` line ~172).
- Incoming times are **clamped** to at most 1 minute in the future, so a phone with a wrong clock can't make its edits permanent.
- Deletions are soft: `deleted_at` travels like any other change.
- The pull **overlaps by 5 seconds** (`next_since = start − 5 s`), so a write committed during a pull isn't missed. Re-applying is harmless.

## Identity, ownership, idempotency
- UUIDs are created on the device.
- Every server read and write is scoped by `owner_id`; a record ID owned by another user is answered "not found".
- Re-sending the same record is idempotent.

## Diagnostics
The phone stores the last plain-English sync error and the last success time. Both appear on the Garage and in Settings → Sync.

## Observations (details in [Code Quality Audit](Code-Quality-Audit))
- **Whole-record conflicts:** two devices editing *different fields* of the same record lose one side's edits.
- **Device clocks decide who wins:** a phone clock running behind loses to older server edits.
- **Silent divergence on rejection:** a `rejected` record is marked clean on the phone, so phone and server copies differ with no visible warning.
- **The first full pull downloads everything** (no paging). This is fine for a household; large accounts would need paging.
