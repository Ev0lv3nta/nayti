#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ -z "${NAYTI_ORT_AAR:-}" || ! -f "$NAYTI_ORT_AAR" ]]; then
  echo "NAYTI_ORT_AAR must point to the pinned reduced runtime; run scripts/fetch_reduced_ort.sh first." >&2
  exit 1
fi

./gradlew \
  verifyArchitecture \
  testDebugUnitTest \
  lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  :benchmark:assemble \
  :ml-runtime:assembleDebugAndroidTest \
  :search-engine:assembleDebugAndroidTest \
  :storage:assembleDebugAndroidTest \
  --no-daemon \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

python3 scripts/verify_manifest_policy.py \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
python3 scripts/verify_manifest_policy.py \
  app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml

release_dependency_report="build/reports/release-dependencies.txt"
./gradlew \
  :app:dependencies \
  --configuration releaseRuntimeClasspath \
  --no-daemon \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}" \
  --console=plain \
  > "$release_dependency_report"
python3 scripts/generate_release_sbom.py \
  "$release_dependency_report" \
  --policy scripts/release_license_policy.json \
  --sbom build/reports/nayti-app-release.cdx.json \
  --notices build/reports/nayti-app-release-notices.md

python3 -m unittest discover -s scripts/tests
PYTHONPATH=model-tools/src python3 -m unittest discover -s model-tools/tests

apks=(
  "app/build/outputs/apk/debug/app-debug.apk"
  "app/build/outputs/apk/release/app-release-unsigned.apk"
)
for apk in "${apks[@]}"; do
  python3 scripts/verify_native_page_size.py "$apk" --check-zip-alignment
done

sdk_root="${ANDROID_SDK_ROOT:-}"
if [[ -z "$sdk_root" ]] && [[ -f local.properties ]]; then
  sdk_root="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
fi
zipalign_bin=""
if [[ -n "$sdk_root" ]]; then
  for candidate in "$sdk_root"/build-tools/*/zipalign; do
    if [[ -x "$candidate" ]]; then
      zipalign_bin="$candidate"
    fi
  done
fi
if [[ -z "$zipalign_bin" ]]; then
  echo "Android SDK zipalign is required for the 16 KiB APK check." >&2
  exit 1
fi
for apk in "${apks[@]}"; do
  if "$zipalign_bin" -c -P 16 -v 4 "$apk" >/dev/null 2>&1; then
    echo "APK ZIP alignment supports 16 KiB pages: $apk"
  elif [[ "$(uname -m)" == "arm64" ]] && file "$zipalign_bin" | grep -q 'x86_64'; then
    echo "APK ZIP alignment supports 16 KiB pages (portable parser): $apk"
  else
    echo "Android SDK zipalign rejected $apk." >&2
    exit 1
  fi
done

if [[ "${NAYTI_SKIP_HOST_NATIVE_TESTS:-0}" != "1" ]]; then
  cmake_bin="${CMAKE_BIN:-}"
  if [[ -z "$cmake_bin" ]] && command -v cmake >/dev/null 2>&1; then
    cmake_bin="$(command -v cmake)"
  fi
  if [[ -z "$cmake_bin" ]] && [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    cmake_bin="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
  fi
  if [[ ! -x "$cmake_bin" ]]; then
    echo "CMake 3.22.1+ is required for host native tests." >&2
    exit 1
  fi

  host_build="$repo_dir/build/native-host"
  "$cmake_bin" -S ml-runtime/src/main/cpp -B "$host_build" -DCMAKE_BUILD_TYPE=Release
  "$cmake_bin" --build "$host_build" --parallel 2
  "$cmake_bin" --build "$host_build" --target test

  search_host_build="$repo_dir/build/search-native-host"
  "$cmake_bin" -S search-engine/src/main/cpp -B "$search_host_build" -DCMAKE_BUILD_TYPE=Release
  "$cmake_bin" --build "$search_host_build" --parallel 2
  "$cmake_bin" --build "$search_host_build" --target test
fi
