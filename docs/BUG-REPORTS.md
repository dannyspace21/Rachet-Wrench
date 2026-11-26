# In-app bug reports

People press **Settings → Report a bug** (phone, desktop app or web), fill in a
short form, see exactly what will be sent, and press **Send report**. The
report is created as an issue on this repository with the labels `bug` and
`user-report`. Nobody needs a GitHub account.

## Current set-up (temporary — "option 2")

The GitHub key that creates the issues is **built into the release builds**:

* It comes from the repository secret **`RW_ISSUE_TOKEN`**, added only while
  the release workflow builds the Android app and the desktop app. It is
  never in the source code.
* The Docker / home server reads it from `RW_ISSUE_TOKEN` in `.env` (optional).
* Without the secret, everything still works: the form offers **Continue on
  GitHub** (the old pre-filled GitHub page) instead of sending.

### One-time setup (about 5 minutes)

1. GitHub → your profile picture → **Settings → Developer settings →
   Personal access tokens → Fine-grained tokens → Generate new token**.
2. **Token name:** `Ratchet Wrench bug reports`. **Expiration:** 90 days (set a
   calendar reminder to renew it), or longer if you prefer.
3. **Repository access:** *Only select repositories* → `Rachet-Wrench`.
4. **Permissions → Repository permissions → Issues: Read and write.** Leave
   everything else at *No access*.
5. Generate, and copy the token.
6. In the repository: **Settings → Secrets and variables → Actions → New
   repository secret** → name `RW_ISSUE_TOKEN`, paste the token, save.
7. The next release includes it (any push to `main` builds one).

To stop it at any time, delete or revoke the token on GitHub — reports then
fall back to "Continue on GitHub".

### The risk you accepted

Anyone determined can extract the key from the app files. With it they can
**only** create (and edit) issues on this repository — not touch code,
releases or settings. The worst case is spam issues; revoke the token if that
happens. The apps also limit how often reports are sent (1 a minute, 10 a day
per phone or user).

## Later: move to a relay ("option 1") — the reminder in the app

The Report-a-bug screen shows a reminder for the owner until this is done.

1. Create a free Cloudflare account and a **Worker** (a tiny web function).
2. Store the same kind of key as a Worker secret (it never leaves Cloudflare).
3. The Worker accepts a report (summary, steps, details), checks its size,
   rate-limits by IP, and creates the issue with the key.
4. Change the apps to send reports to the Worker's address instead of GitHub,
   remove `RW_ISSUE_TOKEN` from the release workflow and the builds, delete
   the reminder, and revoke the old key.

Ask Claude to "set up the bug-report relay" to do steps 3–4.
