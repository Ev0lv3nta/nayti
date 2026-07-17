#!/usr/bin/env bash
set -euo pipefail

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point to the project-local Android SDK}"
: "${JAVA_HOME:?JAVA_HOME must point to the project-local JDK 17}"

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly adb="$ANDROID_SDK_ROOT/platform-tools/adb"
readonly aar="${NAYTI_ORT_AAR:-$NAYTI_MODEL_LAB/runtime-build/android-arm64/Release/java/build/android/outputs/aar/onnxruntime-release.aar}"
readonly kat_root="$NAYTI_MODEL_LAB/android-kat"
readonly package_name="app.nayti.model.runtime.proof"
readonly runner="$package_name/androidx.test.runner.AndroidJUnitRunner"
readonly test_class="$package_name.ReducedOrtInstrumentedTest"

if [[ ! -x "$adb" || ! -f "$aar" || ! -f "$kat_root/manifest.json" ]]; then
  echo "missing adb, reduced AAR, or Android KAT bundle" >&2
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
"$repo_root/gradlew" \
  :model-runtime-proof:assembleDebug \
  --no-daemon \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

readonly apk="$repo_root/model-runtime-proof/build/outputs/apk/debug/model-runtime-proof-debug.apk"
python3 "$repo_root/scripts/verify_native_page_size.py" \
  "$apk" \
  --abi arm64-v8a \
  --check-zip-alignment

manifest_sha256="$(shasum -a 256 "$kat_root/manifest.json" | awk '{ print $1 }')"
readonly private_root="files/kat-${manifest_sha256:0:12}"
readonly log_dir="$NAYTI_MODEL_LAB/runtime-build/android-smoke"
readonly run_id="$(date -u +%Y%m%dT%H%M%SZ)"
readonly run_log="$log_dir/api-${api_level}-page-${page_size}-${run_id}.log"
readonly logcat_log="$log_dir/api-${api_level}-page-${page_size}-${run_id}.logcat"
mkdir -p "$log_dir"

cleanup() {
  case "$private_root" in
    files/kat-*)
      "$adb" shell run-as "$package_name" rm -rf "$private_root" >/dev/null 2>&1 || true
      ;;
  esac
  "$adb" uninstall "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$adb" install -r -t "$apk" >/dev/null
"$adb" shell run-as "$package_name" mkdir -p "$private_root/inputs" "$private_root/outputs"
data_root="$("$adb" shell run-as "$package_name" pwd | tr -d '\r')"
if [[ "$data_root" != */"$package_name" ]]; then
  echo "unexpected private app directory: $data_root" >&2
  exit 1
fi
readonly runtime_root="$data_root/$private_root"

required_kib="$(du -sk "$kat_root" "$NAYTI_MODEL_LAB/ort-models" | awk '{ total += $1 } END { print total }')"
free_kib="$("$adb" shell df -k /data | tr -d '\r' | awk 'END { print $4 }')"
if [[ ! "$free_kib" =~ ^[0-9]+$ ]] || (( free_kib < required_kib + 1048576 )); then
  echo "device needs the KAT/model bytes plus 1 GiB free" >&2
  exit 1
fi

copy_private() {
  local source="$1"
  local destination="$2"
  "$adb" shell -T "run-as $package_name sh -c 'cat > $destination'" < "$source"
}

copy_private "$kat_root/manifest.json" "$private_root/manifest.json"
for fixture in "$kat_root"/inputs/*; do
  copy_private "$fixture" "$private_root/inputs/$(basename "$fixture")"
done
for fixture in "$kat_root"/outputs/*; do
  copy_private "$fixture" "$private_root/outputs/$(basename "$fixture")"
done
for model_name in \
  eslav_recognizer \
  ppocrv6_detector \
  siglip2_image \
  siglip2_text \
  siglip2_tokenizer \
  user2_encoder \
  user2_tokenizer; do
  copy_private \
    "$NAYTI_MODEL_LAB/ort-models/$model_name.ort" \
    "$private_root/$model_name.ort"
done

"$adb" logcat -c
"$adb" shell am instrument \
  -w \
  -r \
  -e class "$test_class" \
  -e katRoot "$runtime_root" \
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
  echo "reduced ORT Android smoke did not report one passing test" >&2
  exit 1
fi
echo "verified seven reduced ORT graphs on API $api_level with page size $page_size"
echo "instrumentation log: $run_log"
