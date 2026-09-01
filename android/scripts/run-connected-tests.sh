#!/usr/bin/env bash

set -euo pipefail

test_tasks=(
  :app:connectedDebugAndroidTest
  :core:data:connectedDebugAndroidTest
  :feature:history:connectedDebugAndroidTest
  :feature:scan:connectedDebugAndroidTest
  :feature:settings:connectedDebugAndroidTest
)

for test_task in "${test_tasks[@]}"; do
  ./gradlew "$test_task" --stacktrace --no-parallel --max-workers=2
done
