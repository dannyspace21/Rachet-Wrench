# Ratchet Wrench on your computer (Windows, macOS, Linux)

The desktop app runs Ratchet Wrench **on your own computer**. You don't need a server, Docker or an internet connection. Your data stays in a folder on that computer. If you like, your phone can sync with it over your home Wi-Fi.

## 1. Download

1. Go to the repository's **Releases** page: <https://github.com/dannyspace21/Rachet-Wrench/releases>
2. Open the newest release named **"Ratchet Wrench Desktop 1.0.N"** and download the file for your computer:

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
3. Enter the address from the desktop window, plus your username and password. Tick **"Allow plain HTTP on my home network"**, then tap **Sign in**.

The phone and computer must be on the **same Wi-Fi**, or both on **Tailscale** (see [HTTPS.md](HTTPS.md)). The phone keeps working offline and syncs whenever it can reach the computer.

When the box is unticked (the default), the app only listens to the computer itself, and nothing else on your network can reach it.

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
