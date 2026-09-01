#!/usr/bin/env bash

# Fast source-only regression test for verify-release-hardening.sh. The release
# job invokes the same checker with APK/AAB/dependency-report inputs after the
# artifacts are built.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
checker="$script_dir/verify-release-hardening.sh"
android_root="$(cd -- "$script_dir/.." && pwd -P)"
source_manifest="$android_root/app/src/main/AndroidManifest.xml"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/codematch-release-hardening-test.XXXXXX")"

cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT

[[ -x "$checker" || -f "$checker" ]] || {
    printf 'Release hardening checker is missing: %s\n' "$checker" >&2
    exit 1
}

bash -n "$checker"
"$checker" --help >/dev/null
"$checker" --skip-artifacts

merged_fixture="$tmp_dir/merged-source.xml"
awk '
    /^[[:space:]]*<uses-permission([[:space:]>]|$)/ {
        block = $0
        in_permission = 1
        if ($0 ~ /\/>/) {
            if (block !~ /tools:node="remove"/) print block
            in_permission = 0
        }
        next
    }
    in_permission {
        block = block ORS $0
        if ($0 ~ /\/>/) {
            if (block !~ /tools:node="remove"/) print block
            in_permission = 0
        }
        next
    }
    { print }
' "$source_manifest" > "$merged_fixture"

assert_manifest_mutation_is_rejected() {
    local name="$1"
    local original="$2"
    local replacement="$3"
    local expected_message="$4"
    local mutated_manifest="$tmp_dir/$name.xml"
    local output_file="$tmp_dir/$name.log"

    sed "s|$original|$replacement|" "$merged_fixture" > "$mutated_manifest"
    if "$checker" --manifest "$mutated_manifest" --skip-artifacts > "$output_file" 2>&1; then
        printf 'Release hardening checker accepted unsafe manifest mutation: %s\n' "$name" >&2
        exit 1
    fi
    rg -q -F "$expected_message" "$output_file" || {
        printf 'Release hardening checker rejected %s for an unexpected reason:\n' "$name" >&2
        sed -n '1,80p' "$output_file" >&2
        exit 1
    }
}

assert_manifest_mutation_is_rejected \
    "broad-file-provider" \
    "@xml/file_paths" \
    "@xml/broad_paths" \
    "FileProvider metadata must reference @xml/file_paths"

assert_manifest_mutation_is_rejected \
    "wrong-backup-rules" \
    "@xml/backup_rules" \
    "@xml/transferable_backup_rules" \
    "must reference @xml/backup_rules"

printf '[release-hardening-test] positive and fail-closed source checks passed\n'
