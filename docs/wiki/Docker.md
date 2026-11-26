# Docker (home server)

**Files:** `server/Dockerfile`, `docker-compose.yml`, `docs/Caddyfile.example`, `.env.example`.

## Image (`server/Dockerfile`)
- **Base image:** `python:3.15.2-slim-trixie`, pinned by **digest**. `apt-get upgrade` runs at build time, and only `tesseract-ocr` + English data are added.
- **Python packages:** installed with `--require-hashes --only-binary=:all:` (hash-locked, no source builds). pip/setuptools/wheel are removed afterwards.
- **Runs as a non-root user** (`10001:10001`). The data volume is `/data` (mode 700).
- **Health check** calls `/api/health`. Uvicorn runs with `--no-server-header --no-proxy-headers`.

## Compose (`docker-compose.yml`)

| Service | Default | Hardening |
|---|---|---|
| `ratchet-wrench` | always on, port `${RW_PORT:-8000}` | `read_only` root FS, `tmpfs /tmp`, `cap_drop: ALL`, `no-new-privileges`, `pids_limit 200`, `mem_limit 1g`, named volume `ratchet-data` |
| `caddy` (profile `https`) | opt-in | HTTPS with automatic certificates; `cap_drop ALL` + `NET_BIND_SERVICE` |
| `ntfy` (profile `ntfy`) | opt-in | push notification server |

Configuration comes from `.env` (template `.env.example`, with **no secrets committed**). The first admin account comes from `RW_ADMIN_USERNAME` / `RW_ADMIN_PASSWORD`.

## Observations
- **Strong container hardening** for a self-hosted app.
- **Plain HTTP on the LAN:** port 8000 is published on all interfaces, so without the `https` profile the server speaks plain HTTP to the whole network.
- **ntfy is open by default:** the ntfy profile sets `NTFY_AUTH_DEFAULT_ACCESS=read-write`, so anyone who can reach ntfy can read or post to topics (see Code Quality, *Security*).
- **No published image:** each operator builds locally, and there's no registry image or image signing.
- **No backup container or schedule:** the compose file only comments "back this up!".
