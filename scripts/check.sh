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
  --no-daemon \
  --max-workers="${NAYTI_GRADLE_WORKERS:-2}"

python3 scripts/verify_manifest_policy.py \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml

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
