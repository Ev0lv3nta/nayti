#!/usr/bin/env bash
set -euo pipefail

readonly ORT_REVISION="8f0278c77bf44b0cc83c098c6c722b92a36ac4b5"
readonly ORTX_REVISION="fe4e13f46b19fb490c90b09fe280277308bd5bb7"
readonly MINIMUM_FREE_KIB=$((100 * 1024 * 1024))
readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly expected_model_lab="$(cd "$repo_root/.." && pwd)/model-lab"

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point to the project-local Android SDK}"
: "${JAVA_HOME:?JAVA_HOME must point to the project-local JDK 17}"

if [[ "$(cd "$NAYTI_MODEL_LAB" && pwd)" != "$expected_model_lab" ]]; then
  echo "refusing to build outside the Nayti workspace: $NAYTI_MODEL_LAB" >&2
  exit 1
fi

readonly ort_source="$NAYTI_MODEL_LAB/upstream/onnxruntime"
readonly ortx_source="$NAYTI_MODEL_LAB/upstream/onnxruntime-extensions"
readonly operator_config="$NAYTI_MODEL_LAB/ort-models/required_operators_and_types.config"
readonly ndk_root="$ANDROID_SDK_ROOT/ndk/27.0.12077973"
readonly cmake_bin="$ANDROID_SDK_ROOT/cmake/3.31.6/bin/cmake"
readonly build_root="$NAYTI_MODEL_LAB/runtime-build/android-arm64"
readonly build_operator_config="$NAYTI_MODEL_LAB/runtime-build/required_build_operators_and_types.config"
readonly build_operator_config_tmp="${build_operator_config}.tmp.$$"
readonly build_log="$NAYTI_MODEL_LAB/runtime-build/android-arm64.log"
readonly workers="${NAYTI_ORT_BUILD_WORKERS:-2}"

if [[ "$workers" != "1" && "$workers" != "2" ]]; then
  echo "NAYTI_ORT_BUILD_WORKERS must be 1 or 2" >&2
  exit 1
fi
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$cmake_bin" || ! -d "$ndk_root" ]]; then
  echo "missing project-local JDK, CMake, or Android NDK r27" >&2
  exit 1
fi
if [[ ! -f "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" ]]; then
  echo "Android platform 34 is required by the pinned ORT AAR build" >&2
  exit 1
fi
if [[ ! -f "$operator_config" ]]; then
  echo "run nayti-model convert-ort before building the runtime" >&2
  exit 1
fi
if [[ ! -d "$ort_source/.git" || ! -d "$ortx_source/.git" ]]; then
  echo "missing pinned ONNX Runtime source checkouts" >&2
  exit 1
fi
if [[ "$(git -C "$ort_source" rev-parse HEAD)" != "$ORT_REVISION" ]]; then
  echo "unexpected ONNX Runtime revision" >&2
  exit 1
fi
if [[ "$(git -C "$ortx_source" rev-parse HEAD)" != "$ORTX_REVISION" ]]; then
  echo "unexpected ONNX Runtime Extensions revision" >&2
  exit 1
fi
if [[ -n "$(git -C "$ort_source" status --porcelain --untracked-files=no)" ]]; then
  echo "refusing to build from a modified ONNX Runtime checkout" >&2
  exit 1
fi
if [[ -n "$(git -C "$ortx_source" status --porcelain --untracked-files=no)" ]]; then
  echo "refusing to build from a modified ONNX Runtime Extensions checkout" >&2
  exit 1
fi

free_kib="$(df -Pk "$NAYTI_MODEL_LAB" | awk 'NR == 2 { print $4 }')"
if [[ ! "$free_kib" =~ ^[0-9]+$ ]] || (( free_kib < MINIMUM_FREE_KIB )); then
  echo "reduced ORT build requires at least 100 GiB free" >&2
  exit 1
fi
if [[ "$(sysctl -n vm.swapusage 2>/dev/null || true)" =~ used\ =\ ([1-9][0-9.]*)M ]]; then
  echo "refusing to start a heavy build while swap is in use" >&2
  exit 1
fi

mkdir -p "$NAYTI_MODEL_LAB/runtime-build" "$NAYTI_MODEL_LAB/gradle-home"
trap 'rm -f "$build_operator_config_tmp"' EXIT

# ORT Extensions at the pinned revision compiles HfJsonTokenizer inside its
# GPT2-tokenizer family, but its selected-op generator does not yet map that
# newer public name. Select the exact same family through an older public op
# name. This affects build-time source selection only; deploy graphs and their
# runtime custom-op contract stay unchanged.
awk '
  /^ai\.onnx\.contrib;1;HfJsonTokenizer$/ {
    print "ai.onnx.contrib;1;GPT2Tokenizer"
    next
  }
  { print }
' "$operator_config" > "$build_operator_config_tmp"
if cmp -s "$build_operator_config_tmp" "$build_operator_config"; then
  rm -f "$build_operator_config_tmp"
else
  mv "$build_operator_config_tmp" "$build_operator_config"
fi
if grep -q 'HfJsonTokenizer' "$build_operator_config"; then
  echo "unmapped tokenizer op remained in the build config" >&2
  exit 1
fi
if ! grep -q '^ai\.onnx\.contrib;1;GPT2Tokenizer$' "$build_operator_config"; then
  echo "GPT2 tokenizer family selector is missing" >&2
  exit 1
fi

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_NDK_HOME="$ndk_root"
export GRADLE_USER_HOME="$NAYTI_MODEL_LAB/gradle-home"
export PATH="$ANDROID_SDK_ROOT/cmake/3.31.6/bin:$PATH"
export CMAKE_BUILD_PARALLEL_LEVEL="$workers"
export MAX_JOBS="$workers"
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.workers.max=$workers -Xmx2g"

"$NAYTI_MODEL_LAB/venv/bin/python" "$ort_source/tools/ci_build/build.py" \
  --config Release \
  --build_dir "$build_root" \
  --update \
  --build \
  --parallel "$workers" \
  --compile_no_warning_as_error \
  --skip_tests \
  --skip_pip_install \
  --android \
  --android_abi arm64-v8a \
  --android_api 30 \
  --android_sdk_path "$ANDROID_SDK_ROOT" \
  --android_ndk_path "$ndk_root" \
  --cmake_path "$cmake_bin" \
  --cmake_generator Ninja \
  --build_java \
  --minimal_build custom_ops \
  --include_ops_by_config "$build_operator_config" \
  --enable_reduced_operator_type_support \
  --use_extensions \
  --extensions_overridden_path "$ortx_source" \
  --cmake_extra_defines \
    ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    OCOS_BUILD_PRESET= \
    OCOS_ENABLE_C_API=OFF \
  >"$build_log" 2>&1

readonly aar="$build_root/Release/java/build/android/outputs/aar/onnxruntime-release.aar"
if [[ ! -f "$aar" ]]; then
  echo "build completed without the expected AAR: $aar" >&2
  exit 1
fi
"$repo_root/model-tools/scripts/verify_reduced_ort_aar.sh" "$aar"
echo "built reduced Android AAR: $aar"
echo "build log: $build_log"
