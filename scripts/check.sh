#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

./gradlew \
  verifyArchitecture \
  testDebugUnitTest \
  lintDebug \
  :app:assembleDebug \
  :benchmark:assemble \
  :ml-runtime:assembleDebugAndroidTest \
  :storage:assembleDebugAndroidTest \
  --no-daemon \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

python3 scripts/verify_manifest_policy.py \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml

python3 -m unittest discover -s scripts/tests

debug_apk="app/build/outputs/apk/debug/app-debug.apk"
python3 scripts/verify_native_page_size.py "$debug_apk"

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
"$zipalign_bin" -c -P 16 -v 4 "$debug_apk" >/dev/null
echo "APK ZIP alignment supports 16 KiB pages."

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
fi
