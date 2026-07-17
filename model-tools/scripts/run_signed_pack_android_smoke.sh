#!/usr/bin/env bash
set -euo pipefail

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point to the project-local Android SDK}"
: "${JAVA_HOME:?JAVA_HOME must point to the project-local JDK 17}"

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly adb="$ANDROID_SDK_ROOT/platform-tools/adb"
readonly aar="${NAYTI_ORT_AAR:-$NAYTI_MODEL_LAB/runtime-build/android-arm64/Release/java/build/android/outputs/aar/onnxruntime-release.aar}"
readonly pack="${NAYTI_MODEL_PACK:-$NAYTI_MODEL_LAB/model-packs/nayti-offline-search-0.1.0-alpha.2.naytipack}"
readonly package_name="app.nayti.model.runtime.proof"
readonly runner="$package_name/androidx.test.runner.AndroidJUnitRunner"
readonly test_class="$package_name.SignedModelPackInstrumentedTest"
readonly expected_pack_sha256="2c90206b2c1ac09233a2b4f3c882dbe4e721bd52ddc3bde46cc6631d51a42167"

if [[ ! -x "$adb" || ! -f "$aar" || ! -f "$pack" ]]; then
  echo "missing adb, reduced AAR, or signed model pack" >&2
  exit 1
fi
if [[ "$(shasum -a 256 "$pack" | awk '{ print $1 }')" != "$expected_pack_sha256" ]]; then
  echo "signed model pack identity does not match alpha.2" >&2
  exit 1
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  connected="$("$adb" devices | awk '$2 == "device" { print $1 }')"
  if [[ "$(printf '%s\n' "$connected" | awk 'NF { count++ } END { print count + 0 }')" != "1" ]]; then
    echo "exactly one online Android device is required" >&2
    exit 1
  fi
  export ANDROID_SERIAL="$connected"
fi

abi="$("$adb" shell getprop ro.product.cpu.abilist | tr -d '\r')"
api_level="$("$adb" shell getprop ro.build.version.sdk | tr -d '\r')"
page_size="$("$adb" shell getconf PAGE_SIZE | tr -d '\r')"
if [[ "$abi" != *arm64-v8a* || ! "$api_level" =~ ^[0-9]+$ || ! "$page_size" =~ ^[0-9]+$ ]]; then
  echo "the proof requires an online ARM64 Android device" >&2
  exit 1
fi
if [[ -n "${NAYTI_EXPECTED_PAGE_SIZE:-}" && "$page_size" != "$NAYTI_EXPECTED_PAGE_SIZE" ]]; then
  echo "unexpected Android page size: $page_size" >&2
  exit 1
fi

"$repo_root/model-tools/scripts/verify_reduced_ort_aar.sh" "$aar"

export NAYTI_ORT_AAR="$aar"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/../.gradle-home}"
"$repo_root/gradlew" :model-runtime-proof:assembleDebug --no-daemon --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

readonly apk="$repo_root/model-runtime-proof/build/outputs/apk/debug/model-runtime-proof-debug.apk"
python3 "$repo_root/scripts/verify_native_page_size.py" "$apk" --abi arm64-v8a --check-zip-alignment

readonly private_pack="files/nayti-offline-search-0.1.0-alpha.2.naytipack"
readonly log_dir="$NAYTI_MODEL_LAB/runtime-build/signed-pack-smoke"
readonly run_id="$(date -u +%Y%m%dT%H%M%SZ)"
readonly run_log="$log_dir/api-${api_level}-page-${page_size}-${run_id}.log"
readonly logcat_log="$log_dir/api-${api_level}-page-${page_size}-${run_id}.logcat"
mkdir -p "$log_dir"

cleanup() {
  "$adb" shell run-as "$package_name" rm -f "$private_pack" >/dev/null 2>&1 || true
  "$adb" uninstall "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$adb" install -r -t "$apk" >/dev/null
"$adb" shell run-as "$package_name" mkdir -p files
data_root="$("$adb" shell run-as "$package_name" pwd | tr -d '\r')"
if [[ "$data_root" != */"$package_name" ]]; then
  echo "unexpected private app directory: $data_root" >&2
  exit 1
fi
readonly runtime_pack="$data_root/$private_pack"

pack_kib="$(du -k "$pack" | awk '{ print $1 }')"
free_kib="$("$adb" shell df -k /data | tr -d '\r' | awk 'END { print $4 }')"
if [[ ! "$free_kib" =~ ^[0-9]+$ ]] || (( free_kib < pack_kib * 3 + 524288 )); then
  echo "device needs roughly three pack sizes plus 512 MiB free for verified staging" >&2
  exit 1
fi

"$adb" shell -T "run-as $package_name sh -c 'cat > $private_pack'" < "$pack"
"$adb" logcat -c
"$adb" shell am instrument \
  -w \
  -r \
  -e class "$test_class" \
  -e packPath "$runtime_pack" \
  "$runner" \
  | tee "$run_log"
"$adb" logcat -d -s \
  NaytiOrtProof:I \
  AndroidRuntime:E \
  ActivityManager:I \
  lowmemorykiller:I \
  '*:S' \
  > "$logcat_log"

if ! grep -q 'OK (1 test)' "$run_log"; then
  echo "signed model pack smoke did not report one passing test" >&2
  exit 1
fi
echo "verified signed pack import and seven runtime graphs on API $api_level with page size $page_size"
echo "instrumentation log: $run_log"
