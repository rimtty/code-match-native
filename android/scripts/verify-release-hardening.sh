#!/usr/bin/env bash

# Release privacy and packaging checks shared by local verification and CI.
# This script deliberately uses only Android SDK tools, ripgrep, xmllint, and
# standard Unix tools; it must not add a Gradle plugin, a network service, or a
# generated lock/SBOM file to the app.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
android_root="$(cd -- "$script_dir/.." && pwd -P)"
app_dir="$android_root/app"

source_manifest="$app_dir/src/main/AndroidManifest.xml"
backup_rules="$app_dir/src/main/res/xml/backup_rules.xml"
data_extraction_rules="$app_dir/src/main/res/xml/data_extraction_rules.xml"
file_paths="$app_dir/src/main/res/xml/file_paths.xml"
release_manifest=""
release_apk="$app_dir/build/outputs/apk/release/app-release.apk"
release_aab="$app_dir/build/outputs/bundle/release/app-release.aab"
dependency_report=""
skip_artifacts=false
apk_explicit=false
aab_explicit=false
tmp_dir=""
expected_backup_rules_resource_id=""
expected_data_extraction_rules_resource_id=""
expected_file_paths_resource_id=""

usage() {
    cat <<'EOF'
Usage: verify-release-hardening.sh [options]

Checks the source backup/FileProvider contract, release dependency graph, and
release APK/AAB manifest. Run from any directory.

Options:
  --manifest PATH             Merged release manifest (defaults to the first
                              known AGP release manifest, then source manifest)
  --apk PATH                  Release APK to inspect (required when supplied)
  --aab PATH                  Release AAB to inspect (required when supplied)
  --dependency-report PATH    releaseRuntimeClasspath report to inspect
  --skip-artifacts            Skip APK/AAB inspection (for source-only tests)
  --help                      Show this help

Release builds bundle the official Inateck scanner SDK and request only
BLUETOOTH_SCAN (neverForLocation) and BLUETOOTH_CONNECT. Legacy Bluetooth,
location, advertising, network, and unrelated Nearby/UWB permissions stay
forbidden; Fake/debug scanner entry points and analytics SDKs must not leak
into release.
EOF
}

die() {
    printf 'Release hardening check failed: %s\n' "$*" >&2
    exit 1
}

note() {
    printf '[release-hardening] %s\n' "$*"
}

require_file() {
    [[ -f "$1" ]] || die "required file is missing: $1"
}

while (($# > 0)); do
    case "$1" in
        --manifest)
            (($# >= 2)) || die "--manifest requires a path"
            release_manifest="$2"
            shift 2
            ;;
        --apk)
            (($# >= 2)) || die "--apk requires a path"
            release_apk="$2"
            apk_explicit=true
            shift 2
            ;;
        --aab)
            (($# >= 2)) || die "--aab requires a path"
            release_aab="$2"
            aab_explicit=true
            shift 2
            ;;
        --dependency-report)
            (($# >= 2)) || die "--dependency-report requires a path"
            dependency_report="$2"
            shift 2
            ;;
        --skip-artifacts)
            skip_artifacts=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

command -v rg >/dev/null 2>&1 || die "ripgrep (rg) is required"

if [[ -z "$release_manifest" ]]; then
    # AGP 8/9 has used both spellings over time. Keep the lookup deterministic
    # so a stale debug/intermediate manifest cannot accidentally be inspected.
    for candidate in \
        "$app_dir/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml" \
        "$app_dir/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"; do
        if [[ -f "$candidate" ]]; then
            release_manifest="$candidate"
            break
        fi
    done
fi

if [[ -z "$release_manifest" ]]; then
    release_manifest="$source_manifest"
    note "no merged release manifest found; source manifest will be checked"
fi

require_file "$source_manifest"
require_file "$backup_rules"
require_file "$data_extraction_rules"
require_file "$file_paths"
require_file "$release_manifest"

if [[ "$skip_artifacts" == false ]]; then
    if [[ "$apk_explicit" == true || "$aab_explicit" == false ]]; then
        require_file "$release_apk"
    fi
    if [[ "$aab_explicit" == true ]]; then
        require_file "$release_aab"
    fi
fi

xml_count() {
    local file="$1"
    local expression="$2"
    local count

    if command -v xmllint >/dev/null 2>&1; then
        count="$(xmllint --xpath "count($expression)" "$file" 2>/dev/null)" || \
            die "could not evaluate XML rule in $file"
        [[ "$count" =~ ^[0-9]+$ ]] || die "XML rule returned a non-count value in $file"
        printf '%s\n' "$count"
        return
    fi

    # The hosted runner and macOS both provide xmllint. This conservative
    # fallback keeps source-only checks useful on minimal developer images.
    printf '%s\n' ""
}

validate_xml() {
    local file="$1"
    if command -v xmllint >/dev/null 2>&1; then
        xmllint --noout "$file" 2>/dev/null || die "invalid XML: $file"
    else
        head -n 1 "$file" | rg -q '^<\?xml' || die "XML parser unavailable and file has no XML declaration: $file"
    fi
}

require_xpath_count() {
    local file="$1"
    local expression="$2"
    local expected="$3"
    local description="$4"
    local count

    count="$(xml_count "$file" "$expression")"
    if [[ -n "$count" ]]; then
        [[ "$count" == "$expected" ]] || die "$description (found $count, expected $expected)"
    fi
}

require_xpath_at_least_one() {
    local file="$1"
    local expression="$2"
    local description="$3"
    local count

    count="$(xml_count "$file" "$expression")"
    if [[ -n "$count" ]]; then
        [[ "$count" =~ ^[0-9]+$ ]] && ((count >= 1)) || die "$description"
    fi
}

require_exclude() {
    local file="$1"
    local scope="$2"
    local domain="$3"
    local path="$4"
    local description="$5"
    local expression="${scope}/exclude[@domain='$domain'][@path='$path']"

    if command -v xmllint >/dev/null 2>&1; then
        require_xpath_at_least_one "$file" "$expression" "$description"
    else
        rg -q -F "domain=\"$domain\" path=\"$path\"" "$file" || die "$description"
    fi
}

validate_backup_rules() {
    local file="$1"
    local root_expression="$2"
    local description="$3"
    local domain path

    validate_xml "$file"
    require_xpath_count "$file" "$root_expression" "1" "$description root element is missing"

    if command -v xmllint >/dev/null 2>&1; then
        require_xpath_count "$file" "//*[local-name()='include']" "0" "$description must not add backup includes"
    else
        ! rg -q '<include([[:space:]>])' "$file" || die "$description must not add backup includes"
    fi

    while IFS='|' read -r domain path; do
        require_exclude "$file" "$root_expression" "$domain" "$path" \
            "$description must exclude domain=$domain path=$path"
    done <<'EOF'
root|.
database|.
file|datastore/
file|datastore/codematch-ble-symbology.preferences_pb
file|ble/
sharedpref|.
external|.
device_root|.
device_database|.
device_file|.
device_sharedpref|.
EOF
}

validate_data_extraction_rules() {
    local file="$1"

    validate_xml "$file"
    require_xpath_count "$file" "/*[local-name()='data-extraction-rules']" "1" \
        "data extraction rules root element is missing"
    if command -v xmllint >/dev/null 2>&1; then
        require_xpath_count "$file" "//*[local-name()='include']" "0" \
            "data extraction rules must not add backup includes"
        require_xpath_count "$file" "/*[local-name()='data-extraction-rules']/*[local-name()='cloud-backup']" "1" \
            "cloud-backup section is missing"
        require_xpath_count "$file" "/*[local-name()='data-extraction-rules']/*[local-name()='device-transfer']" "1" \
            "device-transfer section is missing"
    else
        ! rg -q '<include([[:space:]>])' "$file" || die "data extraction rules must not add backup includes"
        rg -q '<cloud-backup([[:space:]>])' "$file" || die "cloud-backup section is missing"
        rg -q '<device-transfer([[:space:]>])' "$file" || die "device-transfer section is missing"
    fi

    local section
    for section in '//cloud-backup' '//device-transfer'; do
        while IFS='|' read -r domain path; do
            require_exclude "$file" "$section" "$domain" "$path" \
                "$section must exclude domain=$domain path=$path"
        done <<'EOF'
root|.
database|.
file|datastore/
file|datastore/codematch-ble-symbology.preferences_pb
file|ble/
sharedpref|.
external|.
device_root|.
device_database|.
device_file|.
device_sharedpref|.
EOF
    done
}

validate_file_paths() {
    local file="$1"

    validate_xml "$file"
    require_xpath_count "$file" "/*[local-name()='paths']" "1" "FileProvider paths root is missing"
    if command -v xmllint >/dev/null 2>&1; then
        require_xpath_count "$file" "/*[local-name()='paths']/*" "1" \
            "FileProvider must expose exactly one path"
        require_xpath_count "$file" \
            "/*[local-name()='paths']/*[local-name()='cache-path'][@name='history_pdf'][@path='codematch-pdf/']" \
            "1" "FileProvider cache path must be the dedicated PDF directory"
        require_xpath_count "$file" \
            "/*[local-name()='paths']/*[local-name()='files-path' or local-name()='external-path' or local-name()='root-path' or local-name()='external-files-path' or local-name()='external-cache-path' or local-name()='external-media-path']" \
            "0" "FileProvider must not expose a broad or external path"
    else
        ! rg -q '<(files-path|external-path|root-path|external-files-path|external-cache-path|external-media-path)([[:space:]>]|$)' "$file" || \
            die "FileProvider must not expose a broad or external path"
        [[ "$(rg -c '<cache-path([[:space:]>]|$)' "$file" || true)" == "1" ]] || \
            die "FileProvider must expose exactly one cache path"
        rg -q 'name="history_pdf"' "$file" && rg -q 'path="codematch-pdf/"' "$file" || \
            die "FileProvider cache path must be the dedicated PDF directory"
    fi
}

validate_file_paths_dump() {
    local dump="$1"
    local label="$2"
    local cache_count broad_count

    rg -q '^\s+E: paths([[:space:]]|$)' <<< "$dump" || \
        die "$label FileProvider paths root is missing"
    cache_count="$(rg -c '^\s+E: cache-path([[:space:]]|$)' <<< "$dump" || true)"
    [[ "$cache_count" == "1" ]] || \
        die "$label must expose exactly one FileProvider cache path"
    broad_count="$(rg -c '^\s+E: (files-path|external-path|root-path|external-files-path|external-cache-path|external-media-path)([[:space:]]|$)' <<< "$dump" || true)"
    [[ -z "$broad_count" || "$broad_count" == "0" ]] || \
        die "$label FileProvider exposes a broad or external path"
    rg -q 'A: name="history_pdf"' <<< "$dump" || \
        die "$label FileProvider cache path name is not history_pdf"
    rg -q 'A: path="codematch-pdf/"' <<< "$dump" || \
        die "$label FileProvider cache path is not codematch-pdf/"
}

verify_artifact_file_paths() {
    local artifact="$1"
    local label="$2"
    local aapt2_path="$3"
    local resources_dump resource_file paths_dump

    resources_dump="$("$aapt2_path" dump resources -v "$artifact")" || \
        die "aapt2 could not inspect resources in $label"
    resource_file="$(printf '%s\n' "$resources_dump" | awk '
        /resource .* xml\/file_paths([[:space:]]|$)/ {
            if (getline > 0) {
                line = $0
                sub(/^.*\(file\)[[:space:]]*/, "", line)
                sub(/[[:space:]].*$/, "", line)
                if (!found) { print line; found = 1 }
            }
        }
    ')"
    [[ -n "$resource_file" ]] || \
        die "$label does not contain the compiled FileProvider paths resource"
    paths_dump="$("$aapt2_path" dump xmltree --file "$resource_file" "$artifact")" || \
        die "aapt2 could not inspect the FileProvider paths resource in $label"
    validate_file_paths_dump "$paths_dump" "$label"
}

verify_aab_file_paths() {
    local artifact="$1"
    local label="$2"
    local resource_entry="base/res/xml/file_paths.xml"
    local resource_file="$tmp_dir/aab-file-paths.pb"
    local resource_strings="$tmp_dir/aab-file-paths.strings"

    zip_entries "$artifact" | rg -q -x "$resource_entry" || \
        die "$label does not contain the base FileProvider paths resource"
    command -v strings >/dev/null 2>&1 || \
        die "strings is required to inspect the FileProvider paths resource in $label"
    unzip -p "$artifact" "$resource_entry" > "$resource_file" || \
        die "could not extract the FileProvider paths resource from $label"
    strings "$resource_file" > "$resource_strings"

    rg -q '(^|[^[:alnum:]_-])paths([^[:alnum:]_-]|$)' "$resource_strings" || \
        die "$label FileProvider paths root is missing"
    [[ "$(rg -o '(^|[^[:alnum:]_-])cache-path([^[:alnum:]_-]|$)' "$resource_strings" | wc -l | tr -d '[:space:]')" == "1" ]] || \
        die "$label must expose exactly one FileProvider cache path"
    ! rg -q '(^|[^[:alnum:]_-])(files-path|external-path|root-path|external-files-path|external-cache-path|external-media-path)([^[:alnum:]_-]|$)' "$resource_strings" || \
        die "$label FileProvider exposes a broad or external path"
    rg -q '(^|[^[:alnum:]_-])history_pdf([^[:alnum:]_-]|$)' "$resource_strings" || \
        die "$label FileProvider cache path name is not history_pdf"
    rg -q 'codematch-pdf/' "$resource_strings" || \
        die "$label FileProvider cache path is not codematch-pdf/"
}

compiled_exclude_pairs() {
    awk '
        function flush_exclude() {
            if (in_exclude && section != "" && domain != "" && path != "") {
                print section "|" domain "|" path
            }
            in_exclude = 0
            domain = ""
            path = ""
        }
        /^[[:space:]]*E: (full-backup-content|cloud-backup|device-transfer)([[:space:]]|$)/ {
            flush_exclude()
            section = $0
            sub(/^.*E: /, "", section)
            sub(/[[:space:]].*$/, "", section)
            next
        }
        /^[[:space:]]*E: exclude([[:space:]]|$)/ {
            flush_exclude()
            in_exclude = 1
            next
        }
        in_exclude && /A: domain="/ {
            domain = $0
            sub(/^.*A: domain="/, "", domain)
            sub(/".*$/, "", domain)
            next
        }
        in_exclude && /A: path="/ {
            path = $0
            sub(/^.*A: path="/, "", path)
            sub(/".*$/, "", path)
            next
        }
        END { flush_exclude() }
    '
}

validate_compiled_backup_dump() {
    local dump="$1"
    local label="$2"
    shift 2
    local sections=("$@")
    local pairs section domain path count

    ! printf '%s\n' "$dump" | rg -q '^[[:space:]]*E: include([[:space:]]|$)' || \
        die "$label must not contain backup includes"
    pairs="$(printf '%s\n' "$dump" | compiled_exclude_pairs)"

    for section in "${sections[@]}"; do
        while IFS='|' read -r domain path; do
            count="$(printf '%s\n' "$pairs" | rg -c -F -x "$section|$domain|$path" || true)"
            [[ "$count" == "1" ]] || \
                die "$label must contain exactly one $section exclusion for domain=$domain path=$path"
        done <<'EOF'
root|.
database|.
file|datastore/
file|datastore/codematch-ble-symbology.preferences_pb
file|ble/
sharedpref|.
external|.
device_root|.
device_database|.
device_file|.
device_sharedpref|.
EOF
    done
}

compiled_xml_file() {
    local resources_dump="$1"
    local resource_name="$2"

    printf '%s\n' "$resources_dump" | awk -v name="$resource_name" '
        $0 ~ "resource .* " name "([[:space:]]|$)" {
            if (getline > 0) {
                line = $0
                sub(/^.*\(file\)[[:space:]]*/, "", line)
                sub(/[[:space:]].*$/, "", line)
                print line
                exit
            }
        }
    '
}

verify_apk_backup_resources() {
    local artifact="$1"
    local label="$2"
    local aapt2_path="$3"
    local resources_dump="$4"
    local backup_file extraction_file backup_dump extraction_dump

    backup_file="$(compiled_xml_file "$resources_dump" xml/backup_rules)"
    extraction_file="$(compiled_xml_file "$resources_dump" xml/data_extraction_rules)"
    [[ -n "$backup_file" && -n "$extraction_file" ]] || \
        die "$label is missing compiled backup resources"
    backup_dump="$("$aapt2_path" dump xmltree --file "$backup_file" "$artifact")" || \
        die "aapt2 could not inspect backup_rules in $label"
    extraction_dump="$("$aapt2_path" dump xmltree --file "$extraction_file" "$artifact")" || \
        die "aapt2 could not inspect data_extraction_rules in $label"
    validate_compiled_backup_dump "$backup_dump" "$label backup_rules" full-backup-content
    validate_compiled_backup_dump \
        "$extraction_dump" "$label data_extraction_rules" cloud-backup device-transfer
}

verify_aab_backup_resources() {
    local artifact="$1"
    local label="$2"
    local aapt2_path="$3"
    local name entry resource_dir resource_apk resource_dump

    for name in backup_rules data_extraction_rules; do
        entry="base/res/xml/$name.xml"
        zip_entries "$artifact" | rg -q -x "$entry" || \
            die "$label does not contain $entry"
        resource_dir="$tmp_dir/aab-$name"
        resource_apk="$tmp_dir/aab-$name.apk"
        mkdir -p "$resource_dir/res/xml"
        unzip -p "$artifact" base/manifest/AndroidManifest.xml > "$resource_dir/AndroidManifest.xml" || \
            die "could not extract the base manifest from $label"
        unzip -p "$artifact" "$entry" > "$resource_dir/res/xml/$name.xml" || \
            die "could not extract $entry from $label"
        (cd "$resource_dir" && zip -q -r "$resource_apk" AndroidManifest.xml res) || \
            die "could not prepare $entry for inspection in $label"
        resource_dump="$("$aapt2_path" dump xmltree --file "res/xml/$name.xml" "$resource_apk")" || \
            die "aapt2 could not inspect $entry in $label"
        if [[ "$name" == backup_rules ]]; then
            validate_compiled_backup_dump \
                "$resource_dump" "$label backup_rules" full-backup-content
        else
            validate_compiled_backup_dump \
                "$resource_dump" "$label data_extraction_rules" cloud-backup device-transfer
        fi
    done
}

verify_manifest_resource_reference() {
    local manifest_dump="$1"
    local resources_dump="$2"
    local attribute="$3"
    local resource_name="$4"
    local label="$5"
    local resource_id

    resource_id="$(printf '%s\n' "$resources_dump" | awk -v name="$resource_name" '
        $1 == "resource" && $3 == name { print $2; exit }
    ')"
    [[ "$resource_id" =~ ^0x[0-9a-fA-F]+$ ]] || \
        die "$label does not contain $resource_name"
    printf '%s\n' "$manifest_dump" | rg -q -i "$attribute[^=]*=@$resource_id([[:space:]]|$)" || \
        die "$label does not reference $resource_name from $attribute"
    printf '%s\n' "$resource_id"
}

manifest_attr() {
    local file="$1"
    local attr="$2"
    local value=""
    if command -v xmllint >/dev/null 2>&1; then
        value="$(xmllint --xpath "string(/*[local-name()='manifest']/*[local-name()='application']/@*[local-name()='$attr' and namespace-uri()='http://schemas.android.com/apk/res/android'])" "$file" 2>/dev/null || true)"
    fi
    if [[ -z "$value" ]]; then
        value="$(rg -o "android:$attr=\"[^\"]+\"" "$file" | head -n 1 | sed -E 's/.*=\"([^\"]+)\"/\1/' || true)"
    fi
    printf '%s\n' "$value"
}

validate_application_contract() {
    local file="$1"

    [[ "$(manifest_attr "$file" allowBackup)" == "false" ]] || \
        die "application must set android:allowBackup=\"false\": $file"
    [[ "$(manifest_attr "$file" fullBackupContent)" == *"@xml/backup_rules"* ]] || \
        die "application must reference @xml/backup_rules: $file"
    [[ "$(manifest_attr "$file" dataExtractionRules)" == *"@xml/data_extraction_rules"* ]] || \
        die "application must reference @xml/data_extraction_rules: $file"
}

# Release bundles a real BLE adapter and may request only the two Nearby
# Devices runtime permissions (BLUETOOTH_SCAN with neverForLocation and
# BLUETOOTH_CONNECT). Legacy Bluetooth, location, advertising, network, and
# unrelated Nearby/UWB permissions remain forbidden.
forbidden_permissions=(
    "INTERNET"
    "ACCESS_NETWORK_STATE"
    "BLUETOOTH"
    "BLUETOOTH_ADMIN"
    "BLUETOOTH_ADVERTISE"
    "BLUETOOTH_PRIVILEGED"
    "ACCESS_FINE_LOCATION"
    "ACCESS_COARSE_LOCATION"
    "ACCESS_BACKGROUND_LOCATION"
    "NEARBY_WIFI_DEVICES"
    "UWB_RANGING"
)

permission_pattern() {
    printf 'android\\.permission\\.%s(["'"'"'[:space:]]|$)' "$1"
}

validate_source_manifest() {
    local file="$1"
    local permission matches direct_count removal_count

    validate_xml "$file"
    validate_application_contract "$file"

    for permission in "${forbidden_permissions[@]}"; do
        if command -v xmllint >/dev/null 2>&1; then
            direct_count="$(xml_count "$file" \
                "/*[local-name()='manifest']/*[local-name()='uses-permission'][@*[local-name()='name' and namespace-uri()='http://schemas.android.com/apk/res/android']='android.permission.$permission' and not(@*[local-name()='node' and namespace-uri()='http://schemas.android.com/tools']='remove')]")"
            removal_count="$(xml_count "$file" \
                "/*[local-name()='manifest']/*[local-name()='uses-permission'][@*[local-name()='name' and namespace-uri()='http://schemas.android.com/apk/res/android']='android.permission.$permission'][@*[local-name()='node' and namespace-uri()='http://schemas.android.com/tools']='remove']")"
            [[ "$direct_count" == "0" ]] || \
                die "source manifest declares forbidden permission $permission"
            # INTERNET and ACCESS_NETWORK_STATE are deliberately present only
            # as tools:node=remove entries to strip transitive declarations.
            if [[ "$permission" == INTERNET || "$permission" == ACCESS_NETWORK_STATE ]]; then
                [[ "$removal_count" =~ ^[0-9]+$ ]] && ((removal_count >= 1)) || \
                    die "source manifest must remove transitive permission $permission"
            fi
        else
            # Minimal parser fallback for developer images without xmllint.
            matches="$(awk '
                /<uses-permission([[:space:]]|>)/ {
                    block=$0
                    if ($0 ~ /\/>/) { print block; in_block=0 } else { in_block=1 }
                    next
                }
                in_block { block=block " " $0 }
                in_block && /\/>/ { print block; in_block=0 }
            ' "$file" | rg -F "android.permission.$permission" || true)"
            if [[ -n "$matches" ]]; then
                if [[ "$permission" == INTERNET || "$permission" == ACCESS_NETWORK_STATE ]]; then
                    printf '%s\n' "$matches" | rg -q 'tools:node="remove"' || \
                        die "source manifest declares forbidden permission $permission"
                else
                    die "source manifest declares unconnected-stage permission $permission"
                fi
            fi
        fi
    done

    local provider_block
    provider_block="$(sed -n '/androidx.core.content.FileProvider/,/<\//p' "$file")"
    [[ -n "$provider_block" ]] || die "source manifest is missing FileProvider"
    printf '%s\n' "$provider_block" | rg -q 'android:exported="false"' || \
        die "FileProvider must be non-exported"
    printf '%s\n' "$provider_block" | rg -q 'android:grantUriPermissions="true"' || \
        die "FileProvider must grant URI permissions explicitly"
    printf '%s\n' "$provider_block" | rg -q 'android.support.FILE_PROVIDER_PATHS' || \
        die "FileProvider paths metadata is missing"
    printf '%s\n' "$provider_block" | rg -q 'android:resource="@xml/file_paths"' || \
        die "FileProvider paths metadata must reference @xml/file_paths"
}

validate_manifest_text() {
    local text="$1"
    local label="$2"
    local require_app_contract="${3:-true}"
    local expected_file_paths_id="${4:-}"
    local permission

    for permission in "${forbidden_permissions[@]}"; do
        if printf '%s\n' "$text" | rg -q -e "$(permission_pattern "$permission")"; then
            die "$label contains forbidden permission android.permission.$permission"
        fi
    done

    if [[ "$require_app_contract" == true ]]; then
        printf '%s\n' "$text" | rg -q -i 'allowBackup[^=]*=[^[:alnum:]]*(false|0x00000000)' || \
            die "$label must set android:allowBackup=false"
        printf '%s\n' "$text" | rg -q -i 'fullBackupContent' || \
            die "$label must contain fullBackupContent"
        printf '%s\n' "$text" | rg -q -i 'dataExtractionRules' || \
            die "$label must contain dataExtractionRules"
        if printf '%s\n' "$text" | rg -q '<manifest([[:space:]>]|$)'; then
            printf '%s\n' "$text" | rg -q 'fullBackupContent="@xml/backup_rules"' || \
                die "$label must reference @xml/backup_rules"
            printf '%s\n' "$text" | rg -q 'dataExtractionRules="@xml/data_extraction_rules"' || \
                die "$label must reference @xml/data_extraction_rules"
        elif printf '%s\n' "$text" | rg -q -i 'fullBackupContent[^=]*=@xml/backup_rules([[:space:]]|$)' && \
            printf '%s\n' "$text" | rg -q -i 'dataExtractionRules[^=]*=@xml/data_extraction_rules([[:space:]]|$)'; then
            : # AAB protobuf manifests retain symbolic resource names.
        elif [[ -n "$expected_backup_rules_resource_id" && -n "$expected_data_extraction_rules_resource_id" ]]; then
            printf '%s\n' "$text" | rg -q -i "fullBackupContent[^=]*=@$expected_backup_rules_resource_id([[:space:]]|$)" || \
                die "$label must resolve fullBackupContent to xml/backup_rules"
            printf '%s\n' "$text" | rg -q -i "dataExtractionRules[^=]*=@$expected_data_extraction_rules_resource_id([[:space:]]|$)" || \
                die "$label must resolve dataExtractionRules to xml/data_extraction_rules"
        else
            die "$label backup resource identity could not be verified"
        fi
    fi

    if printf '%s\n' "$text" | rg -q -i 'debuggable[^=]*=[^[:alnum:]]*(true|0x00000001)|application-debuggable'; then
        die "$label is debuggable"
    fi
    if printf '%s\n' "$text" | rg -q -i 'scanner[/:.]fake|FakeExternalScanner|show_debug_demo_tools[^[:alnum:]]*(true|1)|debug[[:alnum:]_-]*(menu|entry)|demo[[:alnum:]_-]*(menu|entry)'; then
        die "$label contains a Fake/debug release entry"
    fi

    if [[ "$require_app_contract" == false ]]; then
        # A newly introduced dynamic feature must not silently expand the
        # externally reachable surface. A future intentional exported entry
        # requires an explicit hardening-policy update and review.
        if printf '%s\n' "$text" | rg -q -i 'exported[^=]*=[^[:alnum:]]*(true|0x00000001)'; then
            die "$label contains an exported dynamic-feature component"
        fi
        if printf '%s\n' "$text" | rg -q -i 'androidx\.core\.content\.FileProvider|FILE_PROVIDER_PATHS'; then
            die "$label must not define a dynamic-feature FileProvider"
        fi
    fi

    if [[ "$require_app_contract" == true ]]; then
        local provider_block
        if printf '%s\n' "$text" | rg -q '<provider([[:space:]>]|$)'; then
            provider_block="$(printf '%s\n' "$text" | sed -n '/androidx.core.content.FileProvider/,/<\//p')"
        else
            provider_block="$(printf '%s\n' "$text" | awk '
            function flush_provider() {
                if (provider_block ~ /androidx\.core\.content\.FileProvider/) {
                    print provider_block
                    found = 1
                }
                provider_block = ""
                in_provider = 0
            }
            {
                match($0, /^[[:space:]]*/)
                current_indent = RLENGTH
                is_element = $0 ~ /^[[:space:]]+E: /

                if (in_provider && is_element && current_indent <= provider_indent) {
                    flush_provider()
                }
                if (found) {
                    exit
                }
                if ($0 ~ /^[[:space:]]+E: provider([[:space:]]|$)/) {
                    in_provider = 1
                    provider_indent = current_indent
                    provider_block = $0 ORS
                    next
                }
                if (in_provider) {
                    provider_block = provider_block $0 ORS
                }
            }
            END {
                if (!found && in_provider) {
                    flush_provider()
                }
            }
            ')"
        fi
        [[ -n "$provider_block" ]] || die "$label is missing FileProvider"
        printf '%s\n' "$provider_block" | rg -q -i 'exported[^=]*=[^[:alnum:]]*(false|0x00000000)' || \
            die "$label FileProvider must be non-exported"
        printf '%s\n' "$provider_block" | rg -q -i 'grantUriPermissions[^=]*=[^[:alnum:]]*(true|0x00000001)' || \
            die "$label FileProvider must grant URI permissions"
        if printf '%s\n' "$text" | rg -q '<manifest([[:space:]>]|$)'; then
            printf '%s\n' "$provider_block" | rg -q 'android:resource="@xml/file_paths"' || \
                die "$label FileProvider metadata must reference @xml/file_paths"
        elif printf '%s\n' "$provider_block" | rg -q -i 'resource[^=]*=@xml/file_paths([[:space:]]|$)'; then
            : # AAB protobuf manifests retain the symbolic metadata resource.
        elif [[ -n "$expected_file_paths_id" ]]; then
            printf '%s\n' "$provider_block" | rg -q -i "resource[^=]*=@$expected_file_paths_id([[:space:]]|$)" || \
                die "$label FileProvider metadata must resolve to xml/file_paths"
        else
            die "$label FileProvider resource identity could not be verified"
        fi
    fi
}

find_aapt2() {
    local sdk_root candidate
    if [[ -n "${AAPT2:-}" && -x "${AAPT2}" ]]; then
        printf '%s\n' "$AAPT2"
        return 0
    fi
    if command -v aapt2 >/dev/null 2>&1; then
        command -v aapt2
        return 0
    fi

    local roots=()
    [[ -n "${ANDROID_HOME:-}" ]] && roots+=("$ANDROID_HOME")
    [[ -n "${ANDROID_SDK_ROOT:-}" ]] && roots+=("$ANDROID_SDK_ROOT")
    [[ -n "${HOME:-}" ]] && roots+=("$HOME/Library/Android/sdk" "$HOME/.android/sdk")
    for sdk_root in "${roots[@]}"; do
        [[ -d "$sdk_root/build-tools" ]] || continue
        candidate="$(find "$sdk_root/build-tools" -type f -name aapt2 -perm -111 -print 2>/dev/null | sort | tail -n 1)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

zip_entries() {
    local archive="$1"
    if command -v zipinfo >/dev/null 2>&1; then
        zipinfo -1 "$archive"
    else
        unzip -Z1 "$archive"
    fi
}

verify_dex_does_not_contain_fake() {
    local archive="$1"
    local label="$2"
    local entries
    entries="$(zip_entries "$archive" | rg '(^|/)classes[0-9]*\.dex$' || true)"
    [[ -n "$entries" ]] || return 0

    local dex_file strings_file entry
    dex_file="$tmp_dir/$(basename "$archive").dex"
    strings_file="$tmp_dir/$(basename "$archive").strings"
    while IFS= read -r entry; do
        [[ -n "$entry" ]] || continue
        unzip -p "$archive" "$entry"
    done <<< "$entries" > "$dex_file"
    if command -v strings >/dev/null 2>&1; then
        strings "$dex_file" > "$strings_file"
        if rg -n -i 'jp/rimtty/codematch/scanner/fake|FakeExternalScanner|FAKE-BCST-47' "$strings_file"; then
            die "$label contains Fake scanner classes or identifiers"
        fi
        rg -q 'jp/rimtty/codematch/scanner/inateck' "$strings_file" || \
            die "$label does not contain the Inateck scanner adapter"
        if rg -n -i 'com/google/firebase/analytics|com/google/firebase/crashlytics|com/google/android/gms/analytics|io/sentry|com/bugsnag|com/newrelic|com/datadog|com/mixpanel|com/amplitude|com/segment|com/posthog|com/countly' "$strings_file"; then
            die "$label contains analytics or crash-reporting classes"
        fi
    else
        note "strings is unavailable; skipping binary Fake-class scan for $label"
    fi
    local native_lib
    for native_lib in libjnidispatch.so libscanner_cmd.so libinateck_scanner_cmd.so; do
        zip_entries "$archive" | rg -q "(^|/)lib/arm64-v8a/$native_lib\$" || \
            die "$label is missing the Inateck native library $native_lib"
    done
    if zip_entries "$archive" | rg -q -P '(^|/)lib/(?!arm64-v8a/)'; then
        die "$label contains native libraries for an unexpected ABI"
    fi
}

verify_apk_or_aab() {
    local artifact="$1"
    local label="$2"
    local manifest_dump permissions_dump resources_dump bool_value
    local aapt2_path

    aapt2_path="$(find_aapt2 || true)"
    [[ -n "$aapt2_path" ]] || die "aapt2 is required to inspect $label"

    case "$artifact" in
        *.apk)
            permissions_dump="$("$aapt2_path" dump permissions "$artifact")" || \
                die "aapt2 could not inspect permissions in $label"
            manifest_dump="$("$aapt2_path" dump xmltree --file AndroidManifest.xml "$artifact")" || \
                die "aapt2 could not inspect the manifest in $label"
            for permission in "${forbidden_permissions[@]}"; do
                printf '%s\n' "$permissions_dump" | rg -q -e "$(permission_pattern "$permission")" && \
                    die "$label contains forbidden permission android.permission.$permission"
            done
            resources_dump="$("$aapt2_path" dump resources "$artifact")" || \
                die "aapt2 could not inspect resources in $label"
            expected_backup_rules_resource_id="$(verify_manifest_resource_reference \
                "$manifest_dump" "$resources_dump" fullBackupContent xml/backup_rules "$label")"
            expected_data_extraction_rules_resource_id="$(verify_manifest_resource_reference \
                "$manifest_dump" "$resources_dump" dataExtractionRules xml/data_extraction_rules "$label")"
            expected_file_paths_resource_id="$(printf '%s\n' "$resources_dump" | awk '
                $1 == "resource" && $3 == "xml/file_paths" { print $2; exit }
            ')"
            [[ "$expected_file_paths_resource_id" =~ ^0x[0-9a-fA-F]+$ ]] || \
                die "$label does not contain xml/file_paths"
            bool_value="$(printf '%s\n' "$resources_dump" | awk '
                /bool\/show_debug_demo_tools/ { found=1; next }
                found && !printed && /\(\) (true|false)/ { print; printed=1 }
            ' || true)"
            [[ "$bool_value" == *"false"* ]] || \
                die "$label does not resolve show_debug_demo_tools to false"
            ;;
        *.aab)
            command -v zip >/dev/null 2>&1 || die "zip is required to inspect compiled AAB manifests"
            # aapt2 dump does not accept an AAB container (it expects an APK).
            # Extract each compiled module manifest and wrap it in a temporary
            # APK so the binary XML is still parsed without adding bundletool.
            local manifest_entries manifest_entry manifest_index manifest_dir manifest_apk
            local base_manifest_count=0
            manifest_entries="$(zip_entries "$artifact" | rg '(^|/)manifest/AndroidManifest\.xml$' || true)"
            [[ -n "$manifest_entries" ]] || die "$label has no compiled module manifest"
            manifest_index=0
            while IFS= read -r manifest_entry; do
                [[ -n "$manifest_entry" ]] || continue
                manifest_dir="$tmp_dir/aab-manifest-$manifest_index"
                manifest_apk="$tmp_dir/aab-manifest-$manifest_index.apk"
                mkdir -p "$manifest_dir"
                unzip -p "$artifact" "$manifest_entry" > "$manifest_dir/AndroidManifest.xml" || \
                    die "could not extract $manifest_entry from $label"
                (cd "$manifest_dir" && zip -q "$manifest_apk" AndroidManifest.xml) || \
                    die "could not prepare $manifest_entry for inspection in $label"
                manifest_dump="$("$aapt2_path" dump xmltree --file AndroidManifest.xml "$manifest_apk")" || \
                    die "aapt2 could not inspect $manifest_entry in $label"
                if [[ "$manifest_entry" == "base/manifest/AndroidManifest.xml" ]]; then
                    base_manifest_count=$((base_manifest_count + 1))
                    validate_manifest_text \
                        "$manifest_dump" "$label $manifest_entry" true \
                        "$expected_file_paths_resource_id"
                else
                    # Dynamic-feature manifests may not repeat the base app's
                    # application/FileProvider contract, but their own
                    # permissions and components must still stay release-safe.
                    validate_manifest_text "$manifest_dump" "$label $manifest_entry" false
                fi
                manifest_index=$((manifest_index + 1))
            done <<< "$manifest_entries"
            ((manifest_index >= 1)) || die "$label has no inspectable module manifest"
            ((base_manifest_count == 1)) || \
                die "$label must contain exactly one base/manifest/AndroidManifest.xml"
            ;;
        *)
            die "unsupported release artifact type: $artifact"
            ;;
    esac

    if [[ "$artifact" == *.apk ]]; then
        validate_manifest_text \
            "$manifest_dump" "$label manifest" true "$expected_file_paths_resource_id"
        verify_artifact_file_paths "$artifact" "$label" "$aapt2_path"
        verify_apk_backup_resources \
            "$artifact" "$label" "$aapt2_path" "$resources_dump"
    else
        # AAB resources are protobuf rather than APK binary XML. Inspect the
        # packaged base FileProvider resource directly and keep the source,
        # merged-manifest, and release-APK checks for resolved resource IDs.
        verify_aab_file_paths "$artifact" "$label"
        verify_aab_backup_resources "$artifact" "$label" "$aapt2_path"
        note "$label base FileProvider/backup resources and all module manifests passed"
    fi
    verify_dex_does_not_contain_fake "$artifact" "$label"
    note "$label manifest and release-only packaging checks passed"
}

validate_source_privacy() {
    local dependency_source_hits production_hits file_hits
    local gradle_root="$android_root"

    require_file "$android_root/core/data/src/main/kotlin/jp/rimtty/codematch/core/data/CodeMatchDatabase.kt"
    require_file "$android_root/core/data/src/main/kotlin/jp/rimtty/codematch/core/data/SettingsDataStore.kt"

    # Dependency coordinates are checked independently of a Gradle report so
    # an accidentally omitted report cannot hide an analytics/crash SDK.
    dependency_source_hits="$(rg -n -i \
        'firebase-analytics|firebase-crashlytics|firebase-crashlytics-ndk|sentry|bugsnag|newrelic|datadog|appcenter|instabug|rollbar|raygun|airbrake|hockeyapp|mixpanel|amplitude|segment|posthog|countly|telemetry' \
        "$gradle_root" --glob '*.gradle' --glob '*.gradle.kts' --glob '*.toml' --glob '!**/build/**' || true)"
    [[ -z "$dependency_source_hits" ]] || die "analytics/crash dependency coordinate found:\n$dependency_source_hits"

    local production_dirs=()
    while IFS= read -r directory; do
        [[ -d "$directory" ]] && production_dirs+=("$directory")
    done < <(find "$android_root" -path '*/src/main' -type d -print | sort)
    if ((${#production_dirs[@]} > 0)); then
        production_hits="$(rg -n -i \
            'android\.provider\.MediaStore|Bitmap\.compress|ImageCapture|\.takePicture\(|openFileOutput|FileOutputStream|getExternalFilesDir|externalFilesDir|writeBytes|writeText|printStackTrace|Log\.(v|d|i|w|e)\([^\n]*(payload|frame|image)|println\([^\n]*(payload|frame|image)' \
            "${production_dirs[@]}" --glob '*.kt' --glob '*.java' || true)"
        [[ -z "$production_hits" ]] || die "production source contains a frame/image/payload persistence or log API:\n$production_hits"

        file_hits="$(rg -n '\bFile\s*\(' "${production_dirs[@]}" --glob '*.kt' --glob '*.java' || true)"
        if [[ -n "$file_hits" ]]; then
            if printf '%s\n' "$file_hits" | rg -v 'core/export/src/main/.*/HistoryPdfExporter\.kt:' | rg -q '.'; then
                die "production source creates files outside the dedicated PDF exporter:\n$file_hits"
            fi
        fi

        production_hits="$(rg -n -i \
            'FirebaseAnalytics|FirebaseCrashlytics|Crashlytics|Sentry|Bugsnag|NewRelic|Datadog|Mixpanel|PostHog|Countly|AnalyticsTracker|CrashReporter' \
            "${production_dirs[@]}" --glob '*.kt' --glob '*.java' || true)"
        [[ -z "$production_hits" ]] || die "production source references analytics/crash reporting:\n$production_hits"
    fi
}

validate_release_source_boundary() {
    local release_scanner_module="$app_dir/src/release/java/jp/rimtty/codematch/di/ScannerModule.kt"
    local default_debug_flag="$app_dir/src/main/res/values/bools.xml"
    local unsafe_app_dependencies

    # The release source set binds the official Inateck SDK adapter. This
    # source check complements the APK/Dex scan: it catches a release wiring
    # mistake even when artifacts are not built.
    require_file "$release_scanner_module"
    require_file "$default_debug_flag"
    rg -q 'InateckExternalScanner' "$release_scanner_module" || \
        die "release scanner binding must use InateckExternalScanner"
    ! rg -q -i 'scanner[.]fake|FakeExternalScanner|debug[[:alnum:]_-]*(menu|entry)|demo[[:alnum:]_-]*(menu|entry)' \
        "$release_scanner_module" || \
        die "release scanner binding contains a Fake/debug entry"
    rg -q '<bool[[:space:]]+name="show_debug_demo_tools">false</bool>' "$default_debug_flag" || \
        die "default show_debug_demo_tools must be false for release"

    unsafe_app_dependencies="$(rg -n \
        '(^|[^[:alnum:]_])(implementation|api|runtimeOnly|compileOnly)[[:space:]]*[(].*project[(][[:space:]]*["]:scanner:fake["]' \
        "$app_dir/build.gradle.kts" || true)"
    [[ -z "$unsafe_app_dependencies" ]] || \
        die "app has a non-debug dependency on scanner:fake:\n$unsafe_app_dependencies"
}

validate_dependency_report() {
    [[ -z "$dependency_report" ]] && {
        note "no dependency report supplied; source dependency coordinate scan passed"
        return
    }
    require_file "$dependency_report"
    if rg -n -F ':scanner:fake' "$dependency_report"; then
        die "Fake scanner leaked into the release dependency graph"
    fi
    rg -q -F ':scanner:inateck' "$dependency_report" || \
        die "release dependency graph is missing :scanner:inateck"
    local forbidden_dependency_hits
    forbidden_dependency_hits="$(rg -n -i \
        'firebase-analytics|firebase-crashlytics|firebase-crashlytics-ndk|sentry|bugsnag|newrelic|datadog|appcenter|instabug|rollbar|raygun|airbrake|hockeyapp|mixpanel|amplitude|segment|posthog|countly|telemetry' \
        "$dependency_report" || true)"
    [[ -z "$forbidden_dependency_hits" ]] || die "analytics/crash dependency found in release graph:\n$forbidden_dependency_hits"
    note "release dependency graph contains :scanner:inateck and no Fake, analytics, or crash SDK"
}

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/codematch-release-hardening.XXXXXX")"
cleanup() {
    if [[ -n "$tmp_dir" && -d "$tmp_dir" ]]; then
        rm -rf "$tmp_dir"
    fi
}
trap cleanup EXIT

validate_backup_rules "$backup_rules" "/*[local-name()='full-backup-content']" 'full-backup-content'
validate_data_extraction_rules "$data_extraction_rules"
validate_file_paths "$file_paths"
validate_source_manifest "$source_manifest"
validate_source_privacy
validate_release_source_boundary
validate_dependency_report

if [[ "$release_manifest" != "$source_manifest" ]]; then
    validate_manifest_text "$(cat "$release_manifest")" "merged release manifest"
    note "merged release manifest passed"
fi

if [[ "$skip_artifacts" == false ]]; then
    if [[ -f "$release_apk" ]]; then
        verify_apk_or_aab "$release_apk" "release APK"
    elif [[ "$apk_explicit" == true ]]; then
        die "explicit release APK is missing: $release_apk"
    else
        die "release APK is missing: $release_apk"
    fi
    if [[ "$aab_explicit" == true ]]; then
        verify_apk_or_aab "$release_aab" "release AAB"
    elif [[ -f "$release_aab" ]]; then
        verify_apk_or_aab "$release_aab" "release AAB"
    else
        note "release AAB not present; pass --aab in CI when bundleRelease is built"
    fi
else
    note "APK/AAB inspection skipped by --skip-artifacts"
fi

note "all release hardening checks passed"
