#!/usr/bin/env bash

set -euo pipefail

fail() { printf '[process-recovery] %s\n' "$*" >&2; exit 1; }
log() { printf '[process-recovery] %s\n' "$*"; }

serial=''
skip_build=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$#" -ge 2 ] || fail '--serial requires an emulator serial'
      serial="$2"
      shift 2
      ;;
    --skip-build) skip_build=true; shift ;;
    *) fail 'usage: run-process-recovery-tests.sh --serial emulator-N [--skip-build]' ;;
  esac
done

# Refuse physical/default targets before running any ADB or build command.
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || fail 'an explicit emulator-N target is required; physical devices are refused'
script_dir="$(cd "$(dirname "$0")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
codematch_adb="${CODEMATCH_ADB:-$sdk_dir/platform-tools/adb}"
[ -x "$codematch_adb" ] || fail 'ADB is unavailable; set ANDROID_HOME'
adb_target() { "$codematch_adb" -s "$serial" "$@"; }
[ "$(adb_target get-state 2>/dev/null | tr -d '\r')" = 'device' ] || fail 'the selected emulator is not online'
[ "$(adb_target shell getprop ro.kernel.qemu | tr -d '\r')" = '1' ] || fail 'the selected target does not identify as an emulator'

app_package='jp.rimtty.codematch.recoverytest'
test_package='jp.rimtty.codematch.recoverytest.test'
for package in "$app_package" "$test_package"; do
  installed="$(adb_target shell pm list packages "$package" | tr -d '\r')"
  if printf '%s\n' "$installed" | grep -Fxq "package:$package"; then
    fail 'a recovery package already exists; use a fresh test installation without clearing existing data'
  fi
done

codematch_aapt2="${CODEMATCH_AAPT2:-}"
if [ -z "$codematch_aapt2" ]; then
  for candidate in "$sdk_dir"/build-tools/*/aapt2; do
    if [ -x "$candidate" ]; then codematch_aapt2="$candidate"; fi
  done
fi
[ -x "$codematch_aapt2" ] || fail 'AAPT2 is unavailable; install Android build tools'

if [ "$skip_build" = false ]; then
  (cd "$android_dir" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    -PcodematchProcessRecoveryTests=true --no-parallel --stacktrace)
fi

app_apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$android_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test_class='jp.rimtty.codematch.ProcessRecoveryInstrumentationTest'
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"

apk_package() {
  "$codematch_aapt2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"
}
[ "$(apk_package "$app_apk")" = "$app_package" ] || fail 'refusing to install a non-isolated application APK'
[ "$(apk_package "$test_apk")" = "$test_package" ] || fail 'refusing to install a non-isolated test APK'
test_manifest="$("$codematch_aapt2" dump xmltree "$test_apk" --file AndroidManifest.xml)"
printf '%s\n' "$test_manifest" | grep 'targetPackage' | grep -Fq "=\"$app_package\"" || \
  fail 'instrumentation must target the isolated application only'

# Every mutation below names the isolated package and explicit emulator. There
# is no pm clear, uninstall, broad data deletion, or physical camera/BLE access.
adb_target install "$app_apk"
adb_target install "$test_apk"
# Prevent camera permission prompts/capture from affecting logical UI tests.
adb_target shell pm revoke "$app_package" android.permission.CAMERA
adb_target shell pm set-permission-flags "$app_package" android.permission.CAMERA user-set user-fixed

instrument() {
  local method="$1"
  local output
  shift
  if ! output="$(adb_target shell am instrument -w -r \
    -e class "$test_class#$method" \
    -e recoveryCase "$recovery_case" -e recoveryRunId "$run_id" \
    "$@" "$runner" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "instrumentation failed: $method / $recovery_case"
  fi
  if ! printf '%s\n' "$output" | grep -Eq '^OK \(1 test\)' || \
      printf '%s\n' "$output" | grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED'; then
    # Only synthetic fixture values can occur in this test-only installation.
    printf '%s\n' "$output" >&2
    fail "instrumentation did not prove one passing test: $method / $recovery_case"
  fi
}

for recovery_case in waiting_qr waiting_code128 result_match; do
  if [ -r /proc/sys/kernel/random/uuid ]; then
    read -r run_id < /proc/sys/kernel/random/uuid
  elif command -v uuidgen >/dev/null 2>&1; then
    run_id="$(uuidgen)"
  else
    fail 'a UUID generator is required'
  fi
  log "preparing $recovery_case"
  instrument seedCheckpoint
  adb_target shell am start -W -n "$app_package/jp.rimtty.codematch.MainActivity" >/dev/null
  previous_pid="$(adb_target shell pidof "$app_package" | tr -d '\r')"
  [[ "$previous_pid" =~ ^[0-9]+$ ]] || fail 'a unique running application PID was not observed'
  adb_target shell am force-stop "$app_package"
  stopped=false
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if [ -z "$(adb_target shell pidof "$app_package" 2>/dev/null | tr -d '\r' || true)" ]; then
      stopped=true
      break
    fi
    sleep 0.2
  done
  [ "$stopped" = true ] || fail 'the isolated application did not stop'
  log "OS force-stop confirmed; verifying fresh process for $recovery_case"
  instrument verifyCheckpoint -e previousPid "$previous_pid"
  log "passed $recovery_case"
done
log 'all three isolated process-recovery cases passed; no physical-device acceptance is implied'
