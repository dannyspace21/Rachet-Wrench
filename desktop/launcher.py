"""
launcher.py — the Ratchet Wrench DESKTOP app (Windows, macOS, Linux).

Double-click the app and this program:
  1. keeps your data in your computer's normal app-data folder:
       Windows  %APPDATA%\\RatchetWrench
       macOS    ~/Library/Application Support/Ratchet Wrench
       Linux    ~/.local/share/ratchet-wrench
  2. starts the Ratchet Wrench server ON THIS COMPUTER ONLY (127.0.0.1), so
     nothing else on your network can reach it,
  3. opens Ratchet Wrench in your web browser (the first time, on the
     "create your account" page, using a one-time setup code),
  4. shows a small window with:
       * Open Ratchet Wrench
       * Allow my phone to sync  — when ticked, the server also listens on
         your home network so the Android app can sync with this computer;
         the window shows the address to type into the phone
       * Quit
If the app is already running, starting it again just opens the browser.

It is the very same server as the Docker version (server/app), so the web
app, the API, the security protections and the sync all behave the same.

Run from source (for development):   python desktop/launcher.py
Options:  --headless   no window (keeps running until Ctrl+C)
          --port N     use another port (default 8077)
"""

from __future__ import annotations

import json
import os
import secrets
import socket
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

APP_NAME = "Ratchet Wrench"
DEFAULT_PORT = 8077


# ---------------------------------------------------------------------------
# Special mode: the packaged app runs its own PDF-reading helper (the server
# starts "<this app> --pdf-worker ..." in a separate, time-limited process).
# ---------------------------------------------------------------------------
def _maybe_run_pdf_worker() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--pdf-worker":
        sys.argv = [sys.argv[0], *sys.argv[2:]]
        from app import pdf_worker

        sys.exit(pdf_worker.main())


# ---------------------------------------------------------------------------
# Where things live
# ---------------------------------------------------------------------------
def bundle_dir() -> Path:
    """Folder with the bundled files (web, icons, brand, catalog, server code)."""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parents[1]  # running from the repository


def data_dir() -> Path:
    """Your data folder (database + photos), in the usual place for this OS."""
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming")) / "RatchetWrench"
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support" / APP_NAME
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "ratchet-wrench"
    base.mkdir(parents=True, exist_ok=True)
    return base


def load_prefs() -> dict:
    """Desktop preferences (port, phone sync on/off)."""
    try:
        prefs = json.loads((data_dir() / "desktop.json").read_text(encoding="utf-8"))
        return prefs if isinstance(prefs, dict) else {}
    except (OSError, ValueError):
        return {}


def save_prefs(prefs: dict) -> None:
    (data_dir() / "desktop.json").write_text(json.dumps(prefs, indent=2), encoding="utf-8")


def lan_addresses() -> list[str]:
    """This computer's home-network IP addresses (to type into the phone).
    Uses a UDP "connect", which picks the outgoing interface without sending
    any packet, plus the host name lookup."""
    found = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            found.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    return [ip for ip in dict.fromkeys(found) if not ip.startswith("127.")]


def already_running(port: int) -> bool:
    try:
        # Reviewed: fixed http://127.0.0.1 URL; port is always an int.
        with urllib.request.urlopen(f"http://127.0.0.1:{int(port)}/api/health", timeout=1) as r:  # noqa: S310  # nosemgrep
            return r.status == 200
    except OSError:
        return False


# ---------------------------------------------------------------------------
# The server
# ---------------------------------------------------------------------------
class ServerRunner:
    """Runs the Ratchet Wrench server in a background thread and can restart
    it (to switch phone sync on or off)."""

    def __init__(self, port: int):
        self.port = port
        self.server = None
        self.thread: threading.Thread | None = None
        self.setup_code = secrets.token_urlsafe(24)
        self._configure_environment()

    def _configure_environment(self) -> None:
        os.environ["RW_DATA_DIR"] = str(data_dir())
        os.environ["RW_ASSET_DIR"] = str(bundle_dir())
        os.environ["RW_SETUP_TOKEN"] = self.setup_code  # one-time code, new each start
        os.environ.setdefault("RW_ADMIN_PASSWORD", "")  # first account comes from the setup page
        # Plain http on this computer (and the home network when phone sync
        # is on), so cookies can't be "HTTPS only".  Use Tailscale for HTTPS.
        os.environ["RW_COOKIE_SECURE"] = "false"
        server_dir = bundle_dir() / "server"
        if server_dir.is_dir() and str(server_dir) not in sys.path:
            sys.path.insert(0, str(server_dir))

    def start(self, allow_phone: bool) -> None:
        import uvicorn

        from app.config import get_settings

        get_settings.cache_clear()
        from app.main import create_app

        host = "0.0.0.0" if allow_phone else "127.0.0.1"  # noqa: S104 - only when the user ticks "allow phone"
        config = uvicorn.Config(create_app(), host=host, port=self.port, log_level="warning",
                                server_header=False, proxy_headers=False)
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, daemon=True)
        self.thread.start()
        for _ in range(100):  # wait up to 10 s for it to answer
            if already_running(self.port):
                return
            time.sleep(0.1)

    def stop(self) -> None:
        if self.server is not None:
            self.server.should_exit = True
        if self.thread is not None:
            self.thread.join(timeout=10)
        self.server = None
        self.thread = None

    def needs_setup(self) -> bool:
        try:
            # Reviewed: fixed http://127.0.0.1 URL; port is always an int.
            with urllib.request.urlopen(f"http://127.0.0.1:{int(self.port)}/api/auth/setup-status", timeout=2) as r:  # noqa: S310  # nosemgrep
                return bool(json.loads(r.read()).get("needs_setup"))
        except (OSError, ValueError):
            return False

    def open_browser(self) -> None:
        base = f"http://127.0.0.1:{self.port}/"
        webbrowser.open(base + (f"#/setup?code={self.setup_code}" if self.needs_setup() else "#/"))


# ---------------------------------------------------------------------------
# The small window
# ---------------------------------------------------------------------------
def run_window(runner: ServerRunner, prefs: dict) -> None:
    import tkinter as tk
    from tkinter import ttk

    root = tk.Tk()
    root.title(APP_NAME)
    root.resizable(False, False)
    try:
        icon = tk.PhotoImage(file=str(bundle_dir() / "desktop" / "icon.png"))
        root.iconphoto(True, icon)
    except (tk.TclError, OSError):
        pass
    frame = ttk.Frame(root, padding=22)
    frame.grid()
    ttk.Label(frame, text=APP_NAME, font=("TkDefaultFont", 16, "bold")).grid(sticky="w")
    status = ttk.Label(frame, text="Running privately on this computer.")
    status.grid(sticky="w", pady=(4, 14))
    ttk.Button(frame, text="Open Ratchet Wrench", command=runner.open_browser).grid(sticky="ew", ipady=6)

    phone_var = tk.BooleanVar(value=bool(prefs.get("allow_phone_sync")))
    phone_info = ttk.Label(frame, text="", wraplength=380, justify="left")

    def describe() -> None:
        if phone_var.get():
            addrs = lan_addresses() or ["<this computer's IP address>"]
            lines = "\n".join(f"   http://{ip}:{runner.port}" for ip in addrs)
            phone_info.config(text="Phone sync is ON. In the Android app choose\n“Connect to my computer” and enter:\n"
                                   f"{lines}\nand tick “Allow plain HTTP on my home network”.\n"
                                   "Your phone must be on the same Wi-Fi (or use Tailscale).")
            status.config(text="Running — reachable from your home network for phone sync.")
        else:
            phone_info.config(text="Phone sync is off: only this computer can open Ratchet Wrench.")
            status.config(text="Running privately on this computer.")

    def toggle() -> None:
        prefs["allow_phone_sync"] = phone_var.get()
        save_prefs(prefs)
        status.config(text="Restarting…")
        root.update_idletasks()
        runner.stop()
        runner.start(phone_var.get())
        describe()

    ttk.Checkbutton(frame, text="Allow my phone to sync with this computer", variable=phone_var, command=toggle).grid(sticky="w", pady=(16, 4))
    phone_info.grid(sticky="w")
    ttk.Label(frame, text=f"Your data: {data_dir()}", foreground="#5b6577", wraplength=380).grid(sticky="w", pady=(14, 0))

    def quit_app() -> None:
        runner.stop()
        root.destroy()

    ttk.Button(frame, text="Quit", command=quit_app).grid(sticky="e", pady=(16, 0))
    root.protocol("WM_DELETE_WINDOW", quit_app)
    describe()
    root.mainloop()


def _ensure_output_streams() -> None:
    """A windowed app on Windows/macOS has no console: print() would crash.
    Send output to a log file in the data folder instead."""
    if sys.stdout is None or sys.stderr is None:
        log = open(data_dir() / "desktop.log", "a", encoding="utf-8", buffering=1)  # noqa: SIM115
        if sys.stdout is None:
            sys.stdout = log
        if sys.stderr is None:
            sys.stderr = log


def main() -> None:
    _maybe_run_pdf_worker()
    _ensure_output_streams()
    args = sys.argv[1:]
    prefs = load_prefs()
    port = int(args[args.index("--port") + 1]) if "--port" in args else int(prefs.get("port", DEFAULT_PORT))

    if already_running(port):  # second launch: just open the browser
        webbrowser.open(f"http://127.0.0.1:{port}/")
        return

    runner = ServerRunner(port)
    runner.start(bool(prefs.get("allow_phone_sync")))
    if not already_running(port):
        print(f"Ratchet Wrench could not start on port {port}. Is another program using it?", file=sys.stderr)
        sys.exit(1)
    print(f"Ratchet Wrench is running at http://127.0.0.1:{port}/  (data: {data_dir()})", flush=True)

    headless = "--headless" in args
    if not headless:
        try:
            import tkinter  # noqa: F401
            if sys.platform.startswith("linux") and not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
                raise ImportError("no display")
        except ImportError:
            headless = True
    if "--no-browser" not in args:
        runner.open_browser()
    if headless:
        if runner.needs_setup():
            print(f"First-time setup: open http://127.0.0.1:{port}/#/setup?code={runner.setup_code}")
        print("Press Ctrl+C to stop.", flush=True)
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            runner.stop()
    else:
        run_window(runner, prefs)


if __name__ == "__main__":
    main()
