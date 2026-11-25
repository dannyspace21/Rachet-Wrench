# Putting Ratchet Wrench behind HTTPS (and reaching it from anywhere)

Ratchet Wrench's web server speaks plain HTTP on port 8000. That's fine on your own home network while you try it out. For daily use, put **HTTPS** in front of it so passwords and photos are encrypted, and **never open port 8000 on your router**.

## Recommended: Tailscale (private, nothing exposed to the internet)

[Tailscale](https://tailscale.com) creates a private network between your devices. Your phone can reach the home server from anywhere, while the server stays invisible to the rest of the internet.

1. Install Tailscale on the home server and on your phone, and sign in to both with the same account.
2. In the Tailscale admin console, turn on **MagicDNS** and **HTTPS certificates** (DNS page).
3. On the server, run:
   ```bash
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8000
   ```
   Tailscale now serves the app at `https://<server-name>.<your-tailnet>.ts.net` with a valid certificate.
4. In `.env`, keep `RW_COOKIE_SECURE=true`. Then restart: `docker compose up -d`.
5. In the Android app, use that `https://…ts.net` address as the server.

Optionally, set `RW_PORT=127.0.0.1:8000` in `.env`. Then only Tailscale (on the same machine) can reach the app, not other devices on your LAN.

## Alternative: Caddy reverse proxy

Caddy is a web server that gets HTTPS certificates automatically.

1. Copy `docs/Caddyfile.example` to `Caddyfile` in the project folder and edit the address.
2. In `.env`, set:
   ```
   RW_TRUST_PROXY_HEADERS=true
   RW_COOKIE_SECURE=true
   ```
3. Start everything with the HTTPS profile:
   ```bash
   docker compose --profile https up -d
   ```

If you use a public domain (Option A in the Caddyfile), your router must forward ports 80 and 443 to the server. That exposes the login page to the internet. Use a strong password, and prefer Tailscale if you can.

## Plain HTTP on your home network (not recommended)

To test without HTTPS:

- Set `RW_COOKIE_SECURE=false` in `.env` so the web login works over `http://`.
- In the Android app, tick **"Allow plain HTTP on my home network"** when signing in. The app only allows this for private addresses (192.168.x.x, 10.x.x.x, 172.16–31.x.x, Tailscale's 100.64–127.x.x, `*.local`, `*.lan`, `*.home.arpa`).
