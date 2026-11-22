#!/usr/bin/env bash
# Run ONCE on a trusted PC. No secrets are ever sent to ChatGPT.
# Public project compiles the APK only once. Public Actions then reads a password-protected
# PKCS12 keystore from private z2846852/data using a restricted read-only PAT, and signs it.
set -euo pipefail
umask 077

public="z2846852/dual-sub-replay"
private="z2846852/data"
dir="$HOME/.config/dualsub-fork-signing"
key="$dir/dualsub-release.p12"
password_file="$dir/keystore-password.txt"
checkout="$dir/data-clone"
alias_name="dualsubfork"

for cmd in gh keytool openssl git; do
  command -v "$cmd" >/dev/null || { echo "Missing program: $cmd" >&2; exit 1; }
done
gh auth status >/dev/null
repo_private="$(gh repo view "$private" --json visibility --jq .visibility)"
[[ "$repo_private" == "PRIVATE" ]] || {
  echo "Create https://github.com/new with owner z2846852, name data, visibility Private and Initialize with README first." >&2
  exit 1
}
mkdir -p "$dir"
chmod 700 "$dir"

if [[ -e "$key" && ! -f "$password_file" ]] || [[ ! -e "$key" && -f "$password_file" ]]; then
  echo "Incomplete local key+password pair. Do NOT regenerate; restore the backup first." >&2
  exit 1
fi

if [[ ! -d "$checkout/.git" ]]; then
  gh repo clone "$private" "$checkout"
else
  git -C "$checkout" pull --ff-only
fi
if [[ -f "$checkout/signing/dualsub-release.p12" && ! -f "$key" ]]; then
  echo "Signing key already exists in private data repo. Restore original key and password first." >&2
  exit 1
fi

if [[ ! -f "$key" ]]; then
  openssl rand -hex 24 > "$password_file"
  chmod 600 "$password_file"
  password="$(cat "$password_file")"
  keytool -genkeypair -storetype PKCS12 -keystore "$key" \
    -storepass "$password" -keypass "$password" \
    -alias "$alias_name" -keyalg RSA -keysize 3072 \
    -validity 36500 -dname "CN=DualSub Personal Fork, O=Personal Use, C=CN" \
    -noprompt
  chmod 600 "$key"
fi

if [[ ! -f "$checkout/signing/dualsub-release.p12" ]]; then
  mkdir -p "$checkout/signing"
  cp "$key" "$checkout/signing/dualsub-release.p12"
  git -C "$checkout" add signing/dualsub-release.p12
  git -C "$checkout" -c user.name="z2846852" -c user.email="noreply@users.noreply.github.com" \
    commit -m "Store password-protected DualSub signing keystore (private)"
  git -C "$checkout" push
fi

# One-time setup of public repository Actions Secrets, which are not public files.
printf '%s' "$(cat "$password_file")" | gh secret set FORK_SIGNING_PASSWORD --repo "$public"
printf '\nCreate a fine-grained PAT: only %s, Contents: Read, then paste it here (input hidden).\n' "$private"
read -r -s -p "Read-only data PAT: " data_pat
printf '\n'
if [[ -z "$data_pat" ]]; then echo "PAT not entered; no data credential saved" >&2; exit 1; fi
printf '%s' "$data_pat" | gh secret set DATA_READ_PAT --repo "$public"
unset data_pat password

echo "Done. Keep offline backups of BOTH $key and $password_file. Do not share them."
echo "Main CI now produces one unsigned APK. Once CI passes, public workflow signs it using data and publishes a GitHub Release."
