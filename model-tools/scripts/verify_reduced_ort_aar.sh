#!/usr/bin/env bash
set -euo pipefail

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point to the project-local Android SDK}"

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ndk_bin=""
for candidate in "$ANDROID_SDK_ROOT"/ndk/27.0.12077973/toolchains/llvm/prebuilt/*/bin; do
  if [[ -x "$candidate/llvm-readobj" && -x "$candidate/llvm-strings" ]]; then
    if [[ -n "$ndk_bin" ]]; then
      echo "multiple pinned NDK host toolchains found" >&2
      exit 1
    fi
    ndk_bin="$candidate"
  fi
done
readonly ndk_bin
readonly readobj="$ndk_bin/llvm-readobj"
readonly strings_tool="$ndk_bin/llvm-strings"
readonly aar="${1:-$NAYTI_MODEL_LAB/runtime-build/android-arm64/Release/java/build/android/outputs/aar/onnxruntime-release.aar}"

if [[ -z "$ndk_bin" || ! -f "$aar" || ! -x "$readobj" || ! -x "$strings_tool" ]]; then
  echo "missing reduced AAR or pinned NDK inspection tools" >&2
  exit 1
fi

actual_entries="$(unzip -Z1 "$aar" | awk '/^jni\/.*\.so$/ { print }' | LC_ALL=C sort)"
expected_entries=$'jni/arm64-v8a/libonnxruntime.so\njni/arm64-v8a/libonnxruntime4j_jni.so'
if [[ "$actual_entries" != "$expected_entries" ]]; then
  echo "unexpected native library set in reduced AAR:" >&2
  printf '%s\n' "$actual_entries" >&2
  exit 1
fi

python3 "$repo_root/scripts/verify_native_page_size.py" \
  "$aar" \
  --abi arm64-v8a \
  --native-root jni

aar_sha256="$(shasum -a 256 "$aar" | awk '{ print $1 }')"
readonly verification_dir="$NAYTI_MODEL_LAB/runtime-build/verification/aar-${aar_sha256:0:12}"
mkdir -p "$verification_dir"
unzip -qo "$aar" 'jni/arm64-v8a/*.so' -d "$verification_dir"

readonly runtime="$verification_dir/jni/arm64-v8a/libonnxruntime.so"
readonly jni="$verification_dir/jni/arm64-v8a/libonnxruntime4j_jni.so"

needed_libraries() {
  "$readobj" --needed-libs "$1" | awk '
    /NeededLibraries \[/ { inside = 1; next }
    inside && /^]/ { inside = 0 }
    inside { sub(/^[[:space:]]+/, ""); print }
  ' | LC_ALL=C sort
}

runtime_dependencies="$(needed_libraries "$runtime")"
jni_dependencies="$(needed_libraries "$jni")"
if [[ "$runtime_dependencies" != $'libc.so\nlibdl.so\nliblog.so\nlibm.so' ]]; then
  echo "unexpected libonnxruntime.so dependencies" >&2
  printf '%s\n' "$runtime_dependencies" >&2
  exit 1
fi
if [[ "$jni_dependencies" != $'libc.so\nlibdl.so\nlibm.so\nlibonnxruntime.so' ]]; then
  echo "unexpected libonnxruntime4j_jni.so dependencies" >&2
  printf '%s\n' "$jni_dependencies" >&2
  exit 1
fi
for library in "$runtime" "$jni"; do
  if ! "$readobj" --file-headers "$library" | grep -q 'Arch: aarch64'; then
    echo "non-AArch64 library in reduced AAR: $library" >&2
    exit 1
  fi
done
if ! "$strings_tool" "$runtime" | awk '
  $0 == "HfJsonTokenizer" { tokenizer = 1 }
  $0 == "OrtGetApiBase" { api = 1 }
  END { exit !(tokenizer && api) }
'; then
  echo "required ORT API or HfJsonTokenizer registration is missing" >&2
  exit 1
fi

aar_bytes="$(wc -c < "$aar" | tr -d '[:space:]')"
echo "verified reduced Android AAR: sha256=$aar_sha256 bytes=$aar_bytes"
