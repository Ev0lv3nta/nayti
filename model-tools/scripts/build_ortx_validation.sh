#!/usr/bin/env bash
set -euo pipefail

readonly ORTX_REPOSITORY="https://github.com/microsoft/onnxruntime-extensions.git"
readonly ORTX_REVISION="fe4e13f46b19fb490c90b09fe280277308bd5bb7"
readonly ORTX_VERSION="0.15.0+fe4e13f"
readonly MINIMUM_FREE_KIB=$((100 * 1024 * 1024))

: "${NAYTI_MODEL_LAB:?NAYTI_MODEL_LAB must point to the workspace-local model lab}"
: "${UV_PROJECT_ENVIRONMENT:?UV_PROJECT_ENVIRONMENT must point to the model-tools environment}"

case "$NAYTI_MODEL_LAB" in
  /Users/*/Developer/nayti/model-lab) ;;
  *)
    echo "refusing to build outside the Nayti workspace: $NAYTI_MODEL_LAB" >&2
    exit 1
    ;;
esac

if [[ ! -x "$UV_PROJECT_ENVIRONMENT/bin/python" ]]; then
  echo "missing project-local Python: $UV_PROJECT_ENVIRONMENT/bin/python" >&2
  exit 1
fi
if ! command -v uv >/dev/null 2>&1; then
  echo "uv is required" >&2
  exit 1
fi

free_kib="$(df -Pk "$NAYTI_MODEL_LAB" | awk 'NR == 2 { print $4 }')"
if [[ ! "$free_kib" =~ ^[0-9]+$ ]] || (( free_kib < MINIMUM_FREE_KIB )); then
  echo "ORT Extensions build requires at least 100 GiB free" >&2
  exit 1
fi

readonly source_root="$NAYTI_MODEL_LAB/upstream/onnxruntime-extensions"
if [[ ! -d "$source_root/.git" ]]; then
  mkdir -p "$(dirname "$source_root")"
  git clone --filter=blob:none --no-checkout "$ORTX_REPOSITORY" "$source_root"
fi

if [[ -n "$(git -C "$source_root" status --porcelain --untracked-files=no)" ]]; then
  echo "refusing to replace a modified ORT Extensions checkout" >&2
  exit 1
fi

if [[ "$(git -C "$source_root" rev-parse HEAD 2>/dev/null || true)" != "$ORTX_REVISION" ]]; then
  git -C "$source_root" fetch --depth 1 origin "$ORTX_REVISION"
  git -C "$source_root" checkout --detach "$ORTX_REVISION"
fi
test "$(git -C "$source_root" rev-parse HEAD)" = "$ORTX_REVISION"

env \
  CMAKE_BUILD_PARALLEL_LEVEL=2 \
  MAX_JOBS=2 \
  uv pip install \
    --python "$UV_PROJECT_ENVIRONMENT/bin/python" \
    --no-deps \
    --reinstall \
    "$source_root" \
    --config-settings "ortx-user-option=pp-api,no-opencv"

actual_version="$($UV_PROJECT_ENVIRONMENT/bin/python -c 'import onnxruntime_extensions as x; print(x.__version__)')"
if [[ "$actual_version" != "$ORTX_VERSION" ]]; then
  echo "unexpected ORT Extensions version: $actual_version" >&2
  exit 1
fi
echo "verified ORT Extensions $actual_version at $ORTX_REVISION"
