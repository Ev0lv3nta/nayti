#!/usr/bin/env bash
set -euo pipefail

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly metadata="$repo_root/gradle/reduced-ort.properties"

property() {
  local key="$1"
  sed -n "s/^${key}=//p" "$metadata"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{ print $1 }'
  else
    shasum -a 256 "$1" | awk '{ print $1 }'
  fi
}

readonly release_tag="$(property releaseTag)"
readonly asset_name="$(property assetName)"
readonly expected_sha256="$(property sha256)"
readonly expected_bytes="$(property bytes)"
readonly destination="${1:-$repo_root/build/dependencies/$asset_name}"
readonly url="https://github.com/Ev0lv3nta/nayti/releases/download/$release_tag/$asset_name"

verify() {
  [[ -f "$1" ]] || return 1
  [[ "$(wc -c < "$1" | tr -d '[:space:]')" == "$expected_bytes" ]] || return 1
  [[ "$(sha256_file "$1")" == "$expected_sha256" ]]
}

if verify "$destination"; then
  echo "$destination"
  exit 0
fi

mkdir -p "$(dirname "$destination")"
readonly temporary="$destination.tmp.$$"
trap 'rm -f "$temporary"' EXIT

curl \
  --fail \
  --location \
  --proto '=https' \
  --retry 3 \
  --silent \
  --show-error \
  --tlsv1.2 \
  "$url" \
  --output "$temporary"

if ! verify "$temporary"; then
  echo "downloaded reduced ORT artifact failed size or SHA-256 verification" >&2
  exit 1
fi

mv "$temporary" "$destination"
echo "$destination"
