#!/usr/bin/env bash
# One-time stable signing for the personal Android Fork. Run locally on a trusted PC.
# Requires: GitHub CLI (gh, logged into z2846852), Java keytool, openssl, base64.
set -euo pipefail
umask 077
repo="z2846852/dual-sub-replay"
dir="$HOME/.config/dualsub-fork-signing"
key="$dir/release-keystore.p12"
password_file="$dir/store-password.txt"
alias_name="dualsubfork"

for cmd in gh keytool openssl base64; do
  command -v "$cmd" >/dev/null || { echo "Missing required tool: $cmd" >&2; exit 1; }
done
gh auth status >/dev/null
mkdir -p "$dir"
chmod 700 "$dir"
if [[ -e "$key" && ! -f "$password_file" ]] || [[ ! -e "$key" && -f "$password_file" ]]; then
  echo "Partial signing setup found; refusing to regenerate signing identity." >&2
  exit 1
fi

if [[ ! -f "$key" ]]; then
  echo "Creating a dedicated permanent APK signing key; never share its private bytes."
  openssl rand -hex 24 > "$password_file"
  chmod 600 "$password_file"
  password="$(cat "$password_file")"
  keytool -genkeypair -storetype PKCS12 -keystore "$key" \
    -storepass "$password" -keypass "$password" \
    -alias "$alias_name" -keyalg RSA -keysize 3072 \
    -validity 36500 -dname "CN=DualSub Personal Fork, O=Personal Use, C=CN" \
    -noprompt
  chmod 600 "$key"
else
  echo "Reusing existing signing keystore; key rotation would break Android updates."
fi

password="$(cat "$password_file")"
echo "Uploading secrets securely (nothing is printed to the console)…"
base64 < "$key" | tr -d '\r\n' | gh secret set FORK_RELEASE_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$password" | gh secret set FORK_RELEASE_STORE_PASSWORD --repo "$repo"
printf '%s' "$password" | gh secret set FORK_RELEASE_KEY_PASSWORD --repo "$repo"
printf '%s' "$alias_name" | gh secret set FORK_RELEASE_KEY_ALIAS --repo "$repo"

echo "Secrets configured. Back up $dir securely; losing this signing key means future updates cannot replace installed APKs."
echo "After Android CI passes, run GitHub Actions -> Fork signed Android release -> Run workflow once."
