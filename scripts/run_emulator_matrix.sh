#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

sdk_root="${ANDROID_SDK_ROOT:-}"
if [[ -z "$sdk_root" ]] && [[ -f local.properties ]]; then
  sdk_root="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
fi
adb_bin="$sdk_root/platform-tools/adb"
emulator_bin="$sdk_root/emulator/emulator"
if [[ ! -x "$adb_bin" || ! -x "$emulator_bin" ]]; then
  echo "ANDROID_SDK_ROOT must contain platform-tools/adb and emulator/emulator." >&2
  exit 1
fi

connected_devices="$("$adb_bin" devices | awk 'NR > 1 && NF >= 2 { print $1 " (" $2 ")" }')"
if [[ -n "$connected_devices" ]]; then
  echo "Refusing to run while ADB already lists a device or emulator:" >&2
  echo "$connected_devices" >&2
  exit 1
fi

default_avds=(nayti-api30 nayti-api33 nayti-api34 nayti-api35 nayti-api36 nayti-api37-16k)
if [[ -n "${NAYTI_MATRIX_AVDS:-}" ]]; then
  read -r -a avds <<< "$NAYTI_MATRIX_AVDS"
else
  avds=("${default_avds[@]}")
fi

available_avds="$("$emulator_bin" -list-avds)"
for avd in "${avds[@]}"; do
  if ! grep -Fxq "$avd" <<< "$available_avds"; then
    echo "AVD is not installed: $avd" >&2
    exit 1
  fi
done

report_dir="$repo_dir/build/reports/emulator-matrix"
mkdir -p "$report_dir"
emulator_pid=""
serial=""

cleanup_emulator() {
  if [[ -n "$serial" ]]; then
    "$adb_bin" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
  if [[ -n "$emulator_pid" ]]; then
    for _ in {1..20}; do
      kill -0 "$emulator_pid" >/dev/null 2>&1 || break
      sleep 1
    done
    kill "$emulator_pid" >/dev/null 2>&1 || true
    wait "$emulator_pid" >/dev/null 2>&1 || true
  fi
  emulator_pid=""
  serial=""
}
trap cleanup_emulator EXIT INT TERM

for avd in "${avds[@]}"; do
  emulator_log="$report_dir/$avd-emulator.log"
  gradle_log="$report_dir/$avd-tests.log"
  echo "[matrix] Starting $avd"
  "$emulator_bin" "@$avd" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot-load \
    -no-snapshot-save \
    -gpu swiftshader_indirect \
    >"$emulator_log" 2>&1 &
  emulator_pid=$!

  booted=0
  for _ in {1..120}; do
    serial="$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
    if [[ -n "$serial" ]] && \
      [[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      booted=1
      break
    fi
    if ! kill -0 "$emulator_pid" >/dev/null 2>&1; then
      echo "Emulator exited before boot: $avd" >&2
      tail -80 "$emulator_log" >&2
      exit 1
    fi
    sleep 1
  done
  if [[ "$booted" != "1" ]]; then
    echo "Timed out waiting for $avd to boot." >&2
    tail -80 "$emulator_log" >&2
    exit 1
  fi

  api_level="$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
  page_size="$("$adb_bin" -s "$serial" shell getconf PAGESIZE | tr -d '\r')"
  echo "[matrix] Running API $api_level ($page_size-byte pages) on $avd"
  ANDROID_SERIAL="$serial" ./gradlew \
    :app:connectedDebugAndroidTest \
    :ml-runtime:connectedDebugAndroidTest \
    :search-engine:connectedDebugAndroidTest \
    :storage:connectedDebugAndroidTest \
    --no-daemon \
    --max-workers="${NAYTI_GRADLE_WORKERS:-2}" \
    2>&1 | tee "$gradle_log"

  echo "[matrix] Passed $avd"
  cleanup_emulator
done

trap - EXIT INT TERM
echo "[matrix] All ${#avds[@]} configurations passed. Logs: $report_dir"
