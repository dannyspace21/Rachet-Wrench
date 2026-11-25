#!/usr/bin/env bash
# create-android-signing-key.sh — creates the PERMANENT signing key for the
# Ratchet Wrench Android app.  Run it ONCE, on a computer you trust (e.g.
# your home server), from the project folder:
#
#     scripts/create-android-signing-key.sh
#
# What it does:
#   1. Generates two long random passwords.
#   2. Uses the official Java "keytool" (inside the pinned eclipse-temurin
#      Docker image, so you don't need Java installed) to create an RSA-4096
#      key, valid for 30 years, in  ./ratchet-wrench-release.jks
#   3. Prints the four values you paste into GitHub as repository secrets.
#
# KEEP THE .jks FILE AND THE PASSWORDS SAFE AND BACKED UP (e.g. in a password
# manager).  If they are lost, new versions can never be installed as updates
# over the existing app: everyone would have to uninstall and reinstall.
# Never commit the .jks file (it is ignored by .gitignore).

set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="ratchet-wrench-release.jks"
ALIAS="ratchet-wrench"
IMAGE="eclipse-temurin:27.0.1.1_4-jre@sha256:9723bc085e2a7bd142b78c9a293427941c309962276d8ef28053a61f73238930"

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists — refusing to overwrite your existing key." >&2
  exit 1
fi
command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }

# 32 random characters each (letters and digits only, so they paste safely).
STORE_PASS=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)
KEY_PASS="$STORE_PASS"   # PKCS12 keystores use one password for both

docker run --rm --network none -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  -e STORE_PASS="$STORE_PASS" "$IMAGE" \
  keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$KEYSTORE" \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
    -dname "CN=Ratchet Wrench, O=Self-hosted" \
    -storepass:env STORE_PASS -keypass:env STORE_PASS >/dev/null
chmod 600 "$KEYSTORE"

FINGERPRINT=$(docker run --rm --network none -u "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  -e STORE_PASS="$STORE_PASS" "$IMAGE" \
  keytool -list -v -storetype PKCS12 -keystore "$KEYSTORE" -storepass:env STORE_PASS \
  | awk -F': ' '/SHA256:/ {print $2; exit}')

cat <<EOF

Signing key created:  $PWD/$KEYSTORE
Certificate SHA-256:  $FINGERPRINT

Now add these FOUR repository secrets on GitHub:
  Repository -> Settings -> Secrets and variables -> Actions -> New repository secret

  Name: ANDROID_KEYSTORE_BASE64
  Value: (the long text between the lines below)
-----------------------------------------------------------------------
$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')
-----------------------------------------------------------------------

  Name: ANDROID_KEYSTORE_PASSWORD   Value: $STORE_PASS
  Name: ANDROID_KEY_PASSWORD        Value: $KEY_PASS
  Name: ANDROID_KEY_ALIAS           Value: $ALIAS

Then BACK UP $KEYSTORE and the password above (e.g. in your password
manager), and clear this terminal (type: clear).
EOF
