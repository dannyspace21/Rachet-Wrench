# Android app: automatic updates

Every time a change to the app reaches the `main` branch, GitHub builds a new version and publishes it as a **GitHub Release**, signed with your permanent key. People who install the app through **Obtainium** get each new version automatically. Nobody has to download APKs by hand.

```
code pushed to main ──► GitHub builds + signs the APK ──► GitHub Release v1.0.N
                                                              │
                                    phones running Obtainium ◄┘ (check, then update)
```

---

## Step 1 — Create the permanent signing key (owner, once)

Android only installs an update if it is signed with the **same key** as the installed app. So the key is created once and kept forever. It is never stored in the repository; GitHub keeps it as encrypted secrets.

1. On your home server, or any computer you trust that has Docker, open a terminal in the project folder and run:
   ```bash
   scripts/create-android-signing-key.sh
   ```
   This creates `ratchet-wrench-release.jks` (the key) and prints four values.
2. On GitHub, open the repository, then go to **Settings → Secrets and variables → Actions → New repository secret**. Add the four secrets **exactly** as printed:

   | Secret name | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | the long block of text |
   | `ANDROID_KEYSTORE_PASSWORD` | the password |
   | `ANDROID_KEY_PASSWORD` | the same password |
   | `ANDROID_KEY_ALIAS` | `ratchet-wrench` |

3. **Back up** `ratchet-wrench-release.jks` and the password, for example in your password manager or on a USB stick kept somewhere safe. **If they're lost, no future version can be installed as an update**, and everyone would have to uninstall and reinstall.
4. Clear the terminal (`clear`). The key file is ignored by git, so it can't be committed by accident.

From now on, each push to `main` that changes the app creates a release. To publish one right away, go to **Actions → Android release → Run workflow**.

> Until the secrets are added, the "Android release" workflow stops with a clear message. That's deliberate: it never publishes an APK that phones couldn't install as an update.

**One-time switch:** the copy you installed earlier was signed with a temporary key. Uninstall it once before installing the first release signed with the permanent key. From then on, updates install over the top.

---

## Step 2 — Install with auto-updates (everyone who uses the app)

[Obtainium](https://github.com/ImranR98/Obtainium) is a free, open-source Android app that installs apps straight from their GitHub releases and keeps them updated.

1. Install **Obtainium**, from F-Droid or from its GitHub releases page.
2. In Obtainium, tap **Add App** and paste:
   ```
   https://github.com/dannyspace21/Rachet-Wrench
   ```
   Then tap **Add**.
3. Tap **Install**. Android asks once to allow installs from Obtainium; allow it.
4. Optional: in Obtainium's settings, choose how often it checks for updates, and turn on background updates. On recent Android versions, Obtainium can install updates without asking each time.

Each new release then shows up in Obtainium and installs over the existing app. Your data stays on the phone.

**Checking a download yourself (optional):** every release lists the signing certificate's SHA-256 fingerprint and a `SHA256SUMS.txt` file. The fingerprint must always be the same; if it ever changes, don't install that APK.

---

## How the version number works

Releases are named `v1.0.N`, where N is GitHub's build number for the workflow. The internal Android version code is N × 1000. It always goes up, which Android requires for an update.
