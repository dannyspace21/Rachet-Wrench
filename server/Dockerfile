# Dockerfile — builds the Ratchet Wrench server image.
#
# Security choices (see SECURITY.md):
#  * Official Python "slim" image, pinned to an exact version AND digest so the
#    base can't silently change underneath us.
#  * Python packages are installed ONLY from requirements.txt, where every
#    file's SHA-256 hash is checked (--require-hashes), and only as pre-built
#    wheels (--only-binary) so no package build scripts run.
#  * Tesseract OCR comes from Debian's official package repository.
#  * The server runs as an unprivileged user (uid 10001), never as root.
#  * Uploaded files and the database live in /data (a volume), outside the
#    code/web folders.
#
# Build from the repository root:   docker build -f server/Dockerfile -t ratchet-wrench .

FROM python:3.15.2-slim-trixie@sha256:63bb298d2b7b88f176f51b5189f54ce6535c997682ea70885d2132f5d473a8a5

# Fail a build step if ANY command in a pipe fails, not just the last one.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    RW_DATA_DIR=/data

# Text recognition for door-sticker photos uploaded from the web app, plus
# the latest Debian security updates for the base system.  ("apt-get upgrade"
# is deliberate here: it applies security fixes released after the base
# image was published.)
# nosemgrep: dockerfile.best-practice.avoid-apt-get-upgrade
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged user that will run the server.
RUN groupadd --system --gid 10001 rw \
 && useradd --system --uid 10001 --gid rw --home-dir /app --shell /usr/sbin/nologin rw

WORKDIR /app/server
COPY server/requirements.txt ./requirements.txt
RUN pip install --require-hashes --only-binary=:all: -r requirements.txt \
 && pip uninstall -y pip setuptools wheel

# Application code and shared assets (read-only for the rw user).
COPY server/app ./app
COPY catalog/catalog.json /app/catalog/catalog.json
COPY icons /app/icons
COPY brand /app/brand
COPY web /app/web

RUN mkdir -p /data && chown rw:rw /data && chmod 700 /data

USER 10001:10001
VOLUME ["/data"]
EXPOSE 8000

# Docker restarts the container if this stops answering.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=4).status == 200 else 1)"]

# --no-server-header: don't advertise the server software and version.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header", "--no-proxy-headers"]
