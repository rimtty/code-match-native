#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
module_root="$repo_root/android/scanner/inateck"
android_sdk_commit="8ce0fd5d25d13a7303a3e504b0366e01b15fb8c7"
android_sdk_root="https://raw.githubusercontent.com/Inateck-Technology-Inc/android_sdk/$android_sdk_commit"
scanner_lib_commit="6d8fc093656c3535c5a48bbe7de51eab4a471b48"
scanner_lib_root="https://raw.githubusercontent.com/Inateck-Technology-Inc/scanner_lib/$scanner_lib_commit"

download_and_verify() {
    local download_root="$1"
    local upstream_path="$2"
    local destination="$3"
    local expected_sha256="$4"
    local temporary
    temporary="$(mktemp "${TMPDIR:-/tmp}/inateck-sdk.XXXXXX")"
    trap 'rm -f "$temporary"' RETURN
    mkdir -p "$(dirname "$destination")"
    curl --fail --location --silent --show-error \
        "$download_root/$upstream_path" \
        --output "$temporary"
    local actual_sha256
    actual_sha256="$(shasum -a 256 "$temporary" | awk '{print $1}')"
    if [[ "$actual_sha256" != "$expected_sha256" ]]; then
        echo "Inateck SDK hash mismatch for $upstream_path" >&2
        return 1
    fi
    mv "$temporary" "$destination"
    trap - RETURN
}

download_and_verify \
    "$android_sdk_root" \
    "app/libs/inateck-scanner-ble-2-0-0.jar" \
    "$module_root/libs/inateck-scanner-ble-2-0-0.jar" \
    "a016e427ae9be3489b2293ce77bad1116a3ee7e25a475ab219764c9ac0981311"
download_and_verify \
    "$android_sdk_root" \
    "app/libs/jna-min.jar" \
    "$module_root/libs/jna-min.jar" \
    "b0f0a45245fbc5655c09756235d19e8a044bfe4ee239bcf8c72dd267827f6d35"
download_and_verify \
    "$android_sdk_root" \
    "app/src/main/jniLibs/arm64-v8a/libjnidispatch.so" \
    "$module_root/src/main/jniLibs/arm64-v8a/libjnidispatch.so" \
    "3730ce014f7a6807ae74af78c3b9f5a3cba1f5886316b3d3858493df923efe96"
download_and_verify \
    "$android_sdk_root" \
    "app/src/main/jniLibs/arm64-v8a/libscanner_cmd.so" \
    "$module_root/src/main/jniLibs/arm64-v8a/libscanner_cmd.so" \
    "1aa9658a52e1e35da8af9a72fbe602b48698b105885ff98f1b1bf06a8e76bb8b"
download_and_verify \
    "$scanner_lib_root" \
    "cmd/mobile/aarch64-linux-android/libinateck_scanner_cmd.so" \
    "$module_root/src/main/jniLibs/arm64-v8a/libinateck_scanner_cmd.so" \
    "96b3a8850813c4ebdb0b5086b5eef9af70d8c15b5227dc5ecbb22b5310478d31"

echo "Inateck SDK dependencies are installed and verified locally."
