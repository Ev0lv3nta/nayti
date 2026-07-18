#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

: "${NAYTI_MODEL_PACK:?NAYTI_MODEL_PACK must point to the signed alpha.2 .naytipack}"
: "${JAVA_HOME:?JAVA_HOME must point to JDK 17 for apksigner}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME does not contain an executable Java runtime." >&2
  exit 1
fi
readonly expected_pack_sha256="2c90206b2c1ac09233a2b4f3c882dbe4e721bd52ddc3bde46cc6631d51a42167"
readonly pack="$(cd "$(dirname "$NAYTI_MODEL_PACK")" && pwd)/$(basename "$NAYTI_MODEL_PACK")"
readonly installable_apk="$repo_dir/app/build/outputs/apk/benchmark/app-benchmark.apk"
readonly unsigned_apk="$repo_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
readonly app_sbom="$repo_dir/build/reports/nayti-app-release.cdx.json"
readonly app_notices="$repo_dir/build/reports/nayti-app-release-notices.md"

required_files=(
  "$pack"
  "$installable_apk"
  "$unsigned_apk"
  "$app_sbom"
  "$app_notices"
  "$repo_dir/model-tools/manifests/model-pack-sbom.alpha2.cdx.json"
  "$repo_dir/model-tools/manifests/model-pack-notices.alpha2.md"
)
for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing release input: $file" >&2
    exit 1
  fi
done

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
"$apksigner_bin" verify --verbose "$installable_apk" >/dev/null
signer_summary="$("$apksigner_bin" verify --print-certs "$installable_apk" | sed -n '1,3p')"

readonly commit="$(git rev-parse HEAD)"
readonly short_commit="$(git rev-parse --short=12 HEAD)"
readonly output_dir="${NAYTI_ALPHA_BUNDLE_DIR:-$repo_dir/build/releases/nayti-alpha-$short_commit}"
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

copy_artifact "$installable_apk" "$temporary_dir/nayti-alpha-local-signed.apk"
copy_artifact "$unsigned_apk" "$temporary_dir/nayti-release-unsigned.apk"
copy_artifact "$pack" "$temporary_dir/nayti-offline-search-0.1.0-alpha.2.naytipack"
cp "$app_sbom" "$temporary_dir/nayti-app-release.cdx.json"
cp "$app_notices" "$temporary_dir/nayti-app-release-notices.md"
cp model-tools/manifests/model-pack-sbom.alpha2.cdx.json "$temporary_dir/nayti-model-pack.cdx.json"
cp model-tools/manifests/model-pack-notices.alpha2.md "$temporary_dir/nayti-model-pack-notices.md"
cp docs/device-alpha-runbook.md "$temporary_dir/device-alpha-runbook.md"
cp docs/known-limitations-alpha.md "$temporary_dir/known-limitations-alpha.md"
cp docs/PROVENANCE.md "$temporary_dir/PROVENANCE.md"
cp LICENSE "$temporary_dir/LICENSE"

dirty="no"
[[ -n "$(git status --porcelain)" ]] && dirty="yes"
{
  echo "Nayti local device-alpha bundle"
  echo "source_commit=$commit"
  echo "source_tree_dirty=$dirty"
  echo "application_id=app.nayti"
  echo "version=0.1.0-dev-alpha-local"
  echo "apk_signing=Android debug certificate; local testing only"
  echo "model_pack_sha256=$expected_pack_sha256"
  echo
  echo "$signer_summary"
} > "$temporary_dir/BUILD-INFO.txt"

(
  cd "$temporary_dir"
  files=(
    BUILD-INFO.txt
    LICENSE
    PROVENANCE.md
    device-alpha-runbook.md
    known-limitations-alpha.md
    nayti-alpha-local-signed.apk
    nayti-app-release-notices.md
    nayti-app-release.cdx.json
    nayti-model-pack-notices.md
    nayti-model-pack.cdx.json
    nayti-offline-search-0.1.0-alpha.2.naytipack
    nayti-release-unsigned.apk
  )
  shasum -a 256 "${files[@]}" > SHA256SUMS
  shasum -a 256 -c SHA256SUMS >/dev/null
)

mkdir -p "$(dirname "$output_dir")"
mv "$temporary_dir" "$output_dir"
trap - EXIT INT TERM
echo "$output_dir"
