# Ratchet Wrench on your computer (Windows, macOS, Linux)

The desktop app runs Ratchet Wrench **on your own computer**. You don't need a server, Docker or an internet connection. Your data stays in a folder on that computer. If you like, your phone can sync with it over your home Wi-Fi.

## 1. Download

1. Open the **latest release**: <https://github.com/dannyspace21/Rachet-Wrench/releases/latest>
2. Every release ("Ratchet Wrench 1.0.N") contains **all** downloads — the Android app and the desktop app for every computer, built from the same version. Download the file for your computer:

| Computer | File |
|---|---|
| Windows 10/11 | `RatchetWrench-Windows.zip` |
| Mac with Apple silicon (M1 or newer) | `RatchetWrench-macOS-AppleSilicon.zip` |
| Linux (x86-64) | `RatchetWrench-Linux.tar.gz` |

3. Unzip it.

## 2. Open it

- **Windows:** open the `RatchetWrench` folder and double-click **RatchetWrench.exe**.
  - The first time, Windows may show *"Windows protected your PC"*, because the app isn't signed with a paid Microsoft certificate. Click **More info → Run anyway**.
  - Tip: right-click `RatchetWrench.exe` → *Send to → Desktop (create shortcut)*.
- **macOS:** move **RatchetWrench.app** to *Applications*, then **right-click it → Open → Open**.
  - This one-time step is needed because the app isn't signed with a paid Apple certificate.
  - If macOS still refuses, go to *System Settings → Privacy & Security* and click **Open Anyway**.
- **Linux:** open the `RatchetWrench` folder and run `./RatchetWrench`.

A small **Ratchet Wrench** window appears, and your web browser opens.

**The first time**, the browser shows **"Welcome to Ratchet Wrench"**. Choose a username and a password, then click **Create my account**. Use a password with at least 10 characters; you'll use it to connect your phone too. Only someone at this computer can do this step: the app opens the page with a one-time code.

From then on, use the **Open Ratchet Wrench** button in the small window (or just start the app again). To stop the app, click **Quit**.

## 3. Sync with your phone (optional)

1. In the desktop app's window, tick **"Allow my phone to sync with this computer"**. The window shows an address like `http://192.168.1.20:8077`.
2. On your phone, open Ratchet Wrench:
   - **New install:** choose **"Connect to my computer or home server"**.
   - **Already using it on the phone only:** go to *Settings → Connect to my computer or server*. Everything already on the phone is copied to the computer.
**Easiest — scan a QR code:** in the desktop window click **Connect a phone (QR code)**, then on the phone tap **Scan QR code** (Connect screen, or *Settings → Sync → Reconnect with QR code*). Nothing to type. The code works once, for 10 minutes. Or, by hand:

3. Enter the address shown **first** in the desktop window (for example `192.168.1.20:8077`), plus your username and password. Tap **Sign in**, then **Yes, connect** when the app asks about plain HTTP on your home network.

The phone and computer must be on the **same Wi-Fi**, or both on **Tailscale** (see [HTTPS.md](HTTPS.md)). The phone keeps working offline and syncs whenever it can reach the computer.

When the box is unticked (the default), the app only listens to the computer itself, and nothing else on your network can reach it.

### If the phone can't connect

| What the phone says | What to do |
|---|---|
| "doesn't use secure HTTPS" / "Unable to parse TLS packet header" | The desktop app (port 8077) and the Docker server (port 8000) use `http://` at home. Type just the address, e.g. `192.168.1.20:8077`; the app asks before using plain HTTP. |
| "Couldn't reach …" / "failed to connect … after 15000 ms" | **Check the address.** The phone and computer must be on the same network: if the phone is `192.168.1.x`, the computer's address must also start with `192.168.1.`. Addresses like `192.168.157.1` or `172.x.x.1` belong to *virtual* network adapters (VMware, VirtualBox, Hyper-V, WSL, Docker) and won't work. The desktop window lists the right address first. To double-check on Windows, run `ipconfig` and use the IPv4 address under your Wi-Fi or Ethernet adapter. **Also check:** "Allow my phone to sync" is ticked, and Windows Firewall allows Ratchet Wrench on **Private networks** (Windows Security → Firewall → Allow an app). |
| "Incorrect username or password" | The desktop app and the Docker server keep **separate** accounts and data. Use the account you created on the one you're connecting to. |
| "doesn't look like a Ratchet Wrench server" | Wrong port. Use 8077 for the desktop app and 8000 for Docker. |
| It connected once, but now it won't sync | Open the phone's **Settings → Sync**; it says exactly what went wrong. **Most often the computer got a new address after restarting:** check the address in the desktop window and tap **Change address** (you stay signed in). **Also check:** the desktop app is open (closing its window stops it), and "Allow my phone to sync" is still ticked. |
| Changes made on the computer don't show on the phone | **The desktop app and the Docker server are two separate servers that don't share data.** The phone only syncs with the one it's signed in to (shown in Settings → Sync). Use one of them as your "home base". |

## Where your data is

| Computer | Folder |
|---|---|
| Windows | `%APPDATA%\RatchetWrench` (paste into File Explorer's address bar) |
| macOS | `~/Library/Application Support/Ratchet Wrench` |
| Linux | `~/.local/share/ratchet-wrench` |

To **back up**, quit the app and copy that whole folder somewhere safe. To **move to a new computer**, install the app there, run it once, quit, and replace its folder with your backup.

## Door-sticker scanning on the computer

Reading door-sticker photos **on the computer** needs the free Tesseract program. The phone app doesn't; it reads stickers itself. To use it on the computer, install Tesseract and restart Ratchet Wrench:

- **Windows:** install it from <https://github.com/UB-Mannheim/tesseract/wiki> and tick *"Add to PATH"*.
- **macOS:** `brew install tesseract`
- **Linux:** `sudo apt install tesseract-ocr`

Without Tesseract, everything else works. The sticker screen just says text recognition isn't available, so scan stickers with the phone instead.

## Updating

Download the newest desktop release and replace the old app (or folder) with the new one. Your data folder isn't touched.
