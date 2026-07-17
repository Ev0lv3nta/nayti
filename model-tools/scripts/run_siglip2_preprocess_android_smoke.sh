#!/usr/bin/env bash
set -euo pipefail

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point to the project-local Android SDK}"
: "${JAVA_HOME:?JAVA_HOME must point to the project-local JDK 17}"

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly adb="$ANDROID_SDK_ROOT/platform-tools/adb"
readonly aar="${NAYTI_ORT_AAR:-$NAYTI_MODEL_LAB/runtime-build/android-arm64/Release/java/build/android/outputs/aar/onnxruntime-release.aar}"
readonly kat_root="$NAYTI_MODEL_LAB/android-preprocess-kat"
readonly package_name="app.nayti.model.runtime.proof"
readonly runner="$package_name/androidx.test.runner.AndroidJUnitRunner"
readonly test_class="$package_name.Siglip2ProductionRuntimeInstrumentedTest"

if [[ ! -x "$adb" || ! -f "$aar" || ! -f "$kat_root/manifest.json" ]]; then
  echo "missing adb, reduced AAR, or SigLIP2 preprocessing KAT" >&2
  exit 1
fi
connected="$($adb devices | awk '$2 == "device" { print $1 }')"
if [[ "$(printf '%s\n' "$connected" | awk 'NF { count++ } END { print count + 0 }')" != "1" ]]; then
  echo "exactly one online Android device is required" >&2
  exit 1
fi
export ANDROID_SERIAL="${ANDROID_SERIAL:-$connected}"

export NAYTI_ORT_AAR="$aar"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/../.gradle-home}"
"$repo_root/gradlew" :model-runtime-proof:assembleDebug --no-daemon --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

readonly apk="$repo_root/model-runtime-proof/build/outputs/apk/debug/model-runtime-proof-debug.apk"
readonly private_root="files/siglip2-preprocess-kat"
readonly run_log="$(mktemp)"
cleanup() {
  rm -f "$run_log"
  "$adb" shell run-as "$package_name" rm -rf "$private_root" >/dev/null 2>&1 || true
  "$adb" uninstall "$package_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$adb" install -r -t "$apk" >/dev/null
"$adb" shell run-as "$package_name" mkdir -p "$private_root/payload/models"
data_root="$($adb shell run-as "$package_name" pwd | tr -d '\r')"
readonly runtime_root="$data_root/$private_root"

copy_private() {
  local source="$1"
  local destination="$2"
  "$adb" shell -T "run-as $package_name sh -c 'cat > $destination'" < "$source"
}
copy_private "$kat_root/siglip2-input.png" "$private_root/siglip2-input.png"
copy_private "$kat_root/siglip2-pixel-values.raw" "$private_root/siglip2-pixel-values.raw"
copy_private "$kat_root/siglip2-image-embedding.raw" "$private_root/siglip2-image-embedding.raw"
copy_private "$NAYTI_MODEL_LAB/ort-models/siglip2_image.ort" "$private_root/payload/models/siglip2_image.ort"

"$adb" logcat -c
"$adb" shell am instrument -w -r \
  -e class "$test_class" \
  -e siglipKatRoot "$runtime_root" \
  "$runner" | tee "$run_log"
"$adb" logcat -d -s NaytiSiglip2Proof:I AndroidRuntime:E '*:S'
grep -q 'OK (1 test)' "$run_log"
