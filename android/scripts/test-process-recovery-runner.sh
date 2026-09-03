#!/usr/bin/env bash

set -euo pipefail

# This file doubles as its own read-only ADB/AAPT2 mock through temporary
# symlinks. Every unapproved ADB call is an error, including install/clear/stop.
case "$(basename "$0")" in
  adb-mock)
    [ "$1" = '-s' ] && [ "$2" = 'emulator-5564' ] || exit 91
    shift 2
    case "$*" in
      get-state)
        if [ "$CODEMATCH_GUARD_SCENARIO" = 'offline' ]; then printf 'offline\n'; else printf 'device\n'; fi
        ;;
      'shell getprop ro.kernel.qemu')
        if [ "$CODEMATCH_GUARD_SCENARIO" = 'not-emulator' ]; then printf '0\n'; else printf '1\n'; fi
        ;;
      'shell pm list packages jp.rimtty.codematch.recoverytest')
        if [ "$CODEMATCH_GUARD_SCENARIO" = 'existing-app' ]; then
          printf 'package:jp.rimtty.codematch.recoverytest\n'
        fi
        ;;
      'shell pm list packages jp.rimtty.codematch.recoverytest.test')
        if [ "$CODEMATCH_GUARD_SCENARIO" = 'existing-test' ]; then
          printf 'package:jp.rimtty.codematch.recoverytest.test\n'
        fi
        ;;
      *) printf 'UNEXPECTED_ADB_MUTATION\n' >&2; exit 92 ;;
    esac
    exit 0
    ;;
  aapt2-mock)
    case "$2" in
      badging)
        package='jp.rimtty.codematch.recoverytest'
        if [[ "$3" == *androidTest* ]]; then
          package='jp.rimtty.codematch.recoverytest.test'
          if [ "$CODEMATCH_GUARD_SCENARIO" = 'normal-test' ]; then package='jp.rimtty.codematch.test'; fi
        elif [ "$CODEMATCH_GUARD_SCENARIO" = 'normal-app' ]; then
          package='jp.rimtty.codematch'
        fi
        printf "package: name='%s' versionCode='1'\n" "$package"
        ;;
      xmltree)
        printf 'A: android:targetPackage(0x01010021)="jp.rimtty.codematch"\n'
        ;;
      *) exit 93 ;;
    esac
    exit 0
    ;;
esac

script_dir="$(cd "$(dirname "$0")" && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/codematch-recovery-guard.XXXXXX")"
trap 'rm -f "$test_dir/adb-mock" "$test_dir/aapt2-mock"; rmdir "$test_dir"' EXIT
ln -s "$script_dir/test-process-recovery-runner.sh" "$test_dir/adb-mock"
ln -s "$script_dir/test-process-recovery-runner.sh" "$test_dir/aapt2-mock"

expect_rejection() {
  local scenario="$1"
  local expected="$2"
  local output
  shift 2
  if output="$(CODEMATCH_GUARD_SCENARIO="$scenario" \
    CODEMATCH_ADB="$test_dir/adb-mock" CODEMATCH_AAPT2="$test_dir/aapt2-mock" \
    bash "$script_dir/run-process-recovery-tests.sh" --skip-build "$@" 2>&1)"; then
    printf 'Expected rejection: %s\n' "$scenario" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* || "$output" == *UNEXPECTED_ADB_MUTATION* ]]; then
    printf 'Unexpected guard failure: %s\n%s\n' "$scenario" "$output" >&2
    exit 1
  fi
}

expect_rejection missing-target 'an explicit emulator-N target is required'
expect_rejection physical-target 'physical devices are refused' --serial synthetic-usb-device
expect_rejection offline 'the selected emulator is not online' --serial emulator-5564
expect_rejection not-emulator 'the selected target does not identify as an emulator' --serial emulator-5564
expect_rejection existing-app 'a recovery package already exists' --serial emulator-5564
expect_rejection existing-test 'a recovery package already exists' --serial emulator-5564
expect_rejection normal-app 'refusing to install a non-isolated application APK' --serial emulator-5564
expect_rejection normal-test 'refusing to install a non-isolated test APK' --serial emulator-5564
expect_rejection wrong-instrumentation 'instrumentation must target the isolated application only' --serial emulator-5564
printf '[process-recovery-guard] all nine fail-closed target/package checks passed\n'
