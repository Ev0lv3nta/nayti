#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

: "${NAYTI_MODEL_PACK:?NAYTI_MODEL_PACK must point to the signed alpha.2 .naytipack}"
: "${NAYTI_RELEASE_KEYSTORE:?NAYTI_RELEASE_KEYSTORE must point to the public-alpha keystore}"
: "${NAYTI_RELEASE_KEY_ALIAS:?NAYTI_RELEASE_KEY_ALIAS is required}"
: "${NAYTI_RELEASE_STORE_PASSWORD:?NAYTI_RELEASE_STORE_PASSWORD is required}"
: "${NAYTI_RELEASE_KEY_PASSWORD:?NAYTI_RELEASE_KEY_PASSWORD is required}"
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17}"

readonly version="0.1.0-alpha.1"
readonly expected_pack_sha256="2c90206b2c1ac09233a2b4f3c882dbe4e721bd52ddc3bde46cc6631d51a42167"
readonly expected_signer_sha256="15c830a26fce61cf0797bdee34c4d8394dd85ee1e52d03d2b6affe10a90c2048"
if [[ ! -f "$NAYTI_MODEL_PACK" ]]; then
  echo "Signed model pack does not exist: $NAYTI_MODEL_PACK" >&2
  exit 1
fi
if [[ ! -f "$NAYTI_RELEASE_KEYSTORE" ]]; then
  echo "Release keystore does not exist: $NAYTI_RELEASE_KEYSTORE" >&2
  exit 1
fi
readonly pack="$(cd "$(dirname "$NAYTI_MODEL_PACK")" && pwd)/$(basename "$NAYTI_MODEL_PACK")"
readonly keystore="$(cd "$(dirname "$NAYTI_RELEASE_KEYSTORE")" && pwd)/$(basename "$NAYTI_RELEASE_KEYSTORE")"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Refusing to assemble a public alpha from a dirty source tree." >&2
  exit 1
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME does not contain an executable Java runtime." >&2
  exit 1
fi
actual_pack_sha256="$(shasum -a 256 "$pack" | awk '{ print $1 }')"
if [[ "$actual_pack_sha256" != "$expected_pack_sha256" ]]; then
  echo "Signed model pack does not match reviewed alpha.2 identity." >&2
  exit 1
fi

sdk_root="${ANDROID_SDK_ROOT:-}"
if [[ -z "$sdk_root" ]] && [[ -f local.properties ]]; then
  sdk_root="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
fi
apksigner_bin=""
for candidate in "$sdk_root"/build-tools/*/apksigner; do
  [[ -x "$candidate" ]] && apksigner_bin="$candidate"
done
if [[ -z "$apksigner_bin" ]]; then
  echo "Android SDK apksigner is required." >&2
  exit 1
fi

runtime="$(./scripts/fetch_reduced_ort.sh)"
env \
  -u NAYTI_RELEASE_KEYSTORE \
  -u NAYTI_RELEASE_KEY_ALIAS \
  -u NAYTI_RELEASE_STORE_PASSWORD \
  -u NAYTI_RELEASE_KEY_PASSWORD \
  NAYTI_ORT_AAR="$runtime" \
  ./scripts/check.sh

NAYTI_ORT_AAR="$runtime" ./gradlew \
  --no-daemon \
  --no-configuration-cache \
  :app:assembleRelease \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

readonly signed_apk="$repo_dir/app/build/outputs/apk/release/app-release.apk"
readonly app_sbom="$repo_dir/build/reports/nayti-app-release.cdx.json"
readonly app_notices="$repo_dir/build/reports/nayti-app-release-notices.md"
required_files=(
  "$signed_apk"
  "$app_sbom"
  "$app_notices"
  "$repo_dir/model-tools/manifests/model-pack-sbom.alpha2.cdx.json"
  "$repo_dir/model-tools/manifests/model-pack-notices.alpha2.md"
  "$repo_dir/docs/releases/$version.md"
)
for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing release input: $file" >&2
    exit 1
  fi
done

"$apksigner_bin" verify --verbose --print-certs "$signed_apk" > "$repo_dir/build/reports/nayti-alpha-signature.txt"
if rg -q "Android Debug" "$repo_dir/build/reports/nayti-alpha-signature.txt"; then
  echo "Public alpha must not use the Android debug certificate." >&2
  exit 1
fi
if ! rg -q "Verified using v3 scheme .*: true" "$repo_dir/build/reports/nayti-alpha-signature.txt"; then
  echo "Public alpha must carry an Android v3 APK signature." >&2
  exit 1
fi
signer_sha256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$repo_dir/build/reports/nayti-alpha-signature.txt" | head -n 1)"
if [[ "$signer_sha256" != "$expected_signer_sha256" ]]; then
  echo "Release signer does not match the pinned personal-alpha certificate." >&2
  exit 1
fi

readonly commit="$(git rev-parse HEAD)"
readonly short_commit="$(git rev-parse --short=12 HEAD)"
readonly output_dir="${NAYTI_ALPHA_BUNDLE_DIR:-$repo_dir/build/releases/nayti-$version-$short_commit}"
readonly temporary_dir="$output_dir.tmp.$$"
if [[ -e "$output_dir" || -e "$temporary_dir" ]]; then
  echo "Refusing to replace an existing bundle: $output_dir" >&2
  exit 1
fi
mkdir -p "$temporary_dir"
cleanup() { rm -rf "$temporary_dir"; }
trap cleanup EXIT INT TERM

copy_artifact() {
  local source="$1"
  local destination="$2"
  if [[ "$(uname -s)" == "Darwin" ]] && cp -c "$source" "$destination" 2>/dev/null; then
    return
  fi
  if cp --reflink=auto "$source" "$destination" 2>/dev/null; then
    return
  fi
  cp "$source" "$destination"
}

copy_artifact "$signed_apk" "$temporary_dir/nayti-$version-arm64.apk"
copy_artifact "$pack" "$temporary_dir/nayti-offline-search-0.1.0-alpha.2.naytipack"
cp "$app_sbom" "$temporary_dir/nayti-app-release.cdx.json"
cp "$app_notices" "$temporary_dir/nayti-app-release-notices.md"
cp model-tools/manifests/model-pack-sbom.alpha2.cdx.json "$temporary_dir/nayti-model-pack.cdx.json"
cp model-tools/manifests/model-pack-notices.alpha2.md "$temporary_dir/nayti-model-pack-notices.md"
cp "docs/releases/$version.md" "$temporary_dir/RELEASE-NOTES.md"
cp docs/device-alpha-runbook.md "$temporary_dir/INSTALL.md"
cp docs/known-limitations-alpha.md "$temporary_dir/KNOWN-LIMITATIONS.md"
cp LICENSE "$temporary_dir/LICENSE"

{
  echo "Nayti personal alpha release bundle"
  echo "source_commit=$commit"
  echo "source_tree_dirty=no"
  echo "application_id=app.nayti"
  echo "version=$version"
  echo "apk_signer_sha256=$signer_sha256"
  echo "model_pack_sha256=$expected_pack_sha256"
} > "$temporary_dir/BUILD-INFO.txt"

(
  cd "$temporary_dir"
  files=(
    BUILD-INFO.txt
    INSTALL.md
    KNOWN-LIMITATIONS.md
    LICENSE
    RELEASE-NOTES.md
    "nayti-$version-arm64.apk"
    nayti-app-release-notices.md
    nayti-app-release.cdx.json
    nayti-model-pack-notices.md
    nayti-model-pack.cdx.json
    nayti-offline-search-0.1.0-alpha.2.naytipack
  )
  shasum -a 256 "${files[@]}" > SHA256SUMS
  shasum -a 256 -c SHA256SUMS >/dev/null
)

mkdir -p "$(dirname "$output_dir")"
mv "$temporary_dir" "$output_dir"
trap - EXIT INT TERM
echo "$output_dir"
