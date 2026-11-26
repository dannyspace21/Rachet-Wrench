"""
netinfo.py — this computer's home-network address, used to build the
"connect your phone" QR code when the browser can't tell us (e.g. it was
opened as http://127.0.0.1).  No packet is ever sent: a UDP "connect" to a
reserved documentation address (TEST-NET-3) only asks the operating system
which network card it would use.  Virtual adapters (VMware, VirtualBox,
Hyper-V, WSL, Docker) are skipped where possible, because a phone can't reach them.
"""

from __future__ import annotations

import socket


def looks_virtual(ip: str) -> bool:
    try:
        parts = [int(p) for p in ip.split(".")]
    except ValueError:
        return True
    return (ip.endswith(".1") or (parts[0] == 172 and 16 <= parts[1] <= 31)
            or (parts[0] == 192 and parts[1] == 168 and parts[2] == 56) or parts[0] in (127, 169))


def lan_addresses() -> list[str]:
    """Best guess first: the address of the card used for the internet route."""
    found: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("203.0.113.1", 9))  # reserved address; nothing is sent
            found.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(str(info[4][0]))
    except OSError:
        pass
    found = [ip for ip in dict.fromkeys(found) if ip != "0.0.0.0" and not ip.startswith("127.")]  # noqa: S104
    return [ip for ip in found if not looks_virtual(ip)] + [ip for ip in found if looks_virtual(ip)]
