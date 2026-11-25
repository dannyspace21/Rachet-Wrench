# Android app: install once, then it updates itself

## Install (one time)

1. On your phone, open **<https://github.com/dannyspace21/Rachet-Wrench/releases/latest>**.
2. Under **Assets**, tap **`ratchet-wrench-1.0.N.apk`** to download it.
3. Open the download. Android asks whether to allow installing apps from your browser; allow it, then tap **Install**.
4. Open **Ratchet Wrench**.

If you installed an earlier test copy, **uninstall it first** (one time only). It was signed with a different key, so Android won't let the new version replace it.

## Updates: automatic

Every time you open the app (at most every 6 hours), it checks this project's GitHub Releases for a newer version:

- **On Wi-Fi** it downloads the update right away and installs it.
- **On mobile data** it shows a banner, "Update 1.0.N is available", with an **Update now** button, so it doesn't use 70 MB of data without asking.
- **The very first update** asks you to allow Ratchet Wrench to install updates (*Install unknown apps* → on), just once. After that, Android shows its **"Update this app?"** prompt; tap **Update**. On Android 12 and newer, once the app has updated itself once, later updates can install without the prompt.

You can switch automatic updates off, or check right away, in **Settings → App updates**.

**What the app checks before installing anything:** the update must match the SHA-256 checksum published with the release, be the same app, be a newer version, and be **signed with the same key** as the installed app. If any check fails, the download is thrown away. Android itself also refuses any update signed with a different key.

## How releases are made (owner)

Every push to `main` that changes the app builds, signs and publishes a new GitHub Release (`v1.0.N`) automatically: see `.github/workflows/android.yml`. To publish one by hand, go to **Actions → Android release → Run workflow**.

This needs the permanent signing key stored as four repository secrets (*Settings → Secrets and variables → Actions*):

| Secret name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the key file, as one long line of text |
| `ANDROID_KEYSTORE_PASSWORD` | its password |
| `ANDROID_KEY_PASSWORD` | the same password |
| `ANDROID_KEY_ALIAS` | `ratchet-wrench` |

To create a new key, run `scripts/create-android-signing-key.sh` on a computer you trust. It prints these four values.

**Keep a backup of the key file and password.** If they're lost, future versions can't update the installed app, and everyone would have to uninstall and reinstall.

The signing certificate's SHA-256 fingerprint is shown in every release's notes. It must never change.
