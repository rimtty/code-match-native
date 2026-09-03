#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
android_root="$(cd -- "$script_dir/.." && pwd -P)"
apk="${1:-$android_root/app/build/outputs/apk/scannerPoc/app-scannerPoc.apk}"

[[ -f "$apk" ]] || {
    echo "Inateck PoC verification failed: APK is missing: $apk" >&2
    exit 1
}

find_aapt2() {
    local root candidate
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [[ -d "$root/build-tools" ]] || continue
        candidate="$(find "$root/build-tools" -type f -name aapt2 -perm -111 -print | sort | tail -n 1)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    return 1
}

aapt2="$(find_aapt2)" || {
    echo "Inateck PoC verification failed: aapt2 is unavailable" >&2
    exit 1
}
permissions="$($aapt2 dump permissions "$apk")"
manifest="$($aapt2 dump xmltree "$apk" --file AndroidManifest.xml)"

printf '%s\n' "$permissions" | rg -q \
    "uses-permission: name='android[.]permission[.]BLUETOOTH_SCAN' usesPermissionFlags='neverForLocation'" || {
    echo "Inateck PoC verification failed: BLUETOOTH_SCAN neverForLocation is missing" >&2
    exit 1
}
printf '%s\n' "$permissions" | rg -q \
    "uses-permission: name='android[.]permission[.]BLUETOOTH_CONNECT'" || {
    echo "Inateck PoC verification failed: BLUETOOTH_CONNECT is missing" >&2
    exit 1
}

for forbidden in BLUETOOTH BLUETOOTH_ADMIN BLUETOOTH_ADVERTISE ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION INTERNET ACCESS_NETWORK_STATE; do
    if printf '%s\n' "$permissions" | rg -q "android[.]permission[.]$forbidden([^_[:alnum:]]|$)"; then
        echo "Inateck PoC verification failed: forbidden permission $forbidden" >&2
        exit 1
    fi
done

if printf '%s\n' "$manifest" | rg -q 'android:debuggable[^=]*=true'; then
    echo "Inateck PoC verification failed: APK is debuggable" >&2
    exit 1
fi
exported_count="$(printf '%s\n' "$manifest" | rg -c 'android:exported[^=]*=true' || true)"
if [[ "$exported_count" != "1" ]] || \
    ! printf '%s\n' "$manifest" | rg -q 'jp[.]rimtty[.]codematch[.]MainActivity'; then
    echo "Inateck PoC verification failed: unexpected exported app component" >&2
    exit 1
fi
if printf '%s\n' "$manifest" | rg -q \
    'androidx[.]compose[.]ui[.]tooling[.]PreviewActivity|androidx[.]activity[.]ComponentActivity'; then
    echo "Inateck PoC verification failed: debug tooling activity is packaged" >&2
    exit 1
fi

entries="$(unzip -Z1 "$apk")"
for required_native in lib/arm64-v8a/libjnidispatch.so lib/arm64-v8a/libscanner_cmd.so; do
    printf '%s\n' "$entries" | rg -q -x "$required_native" || {
        echo "Inateck PoC verification failed: $required_native is missing" >&2
        exit 1
    }
done
if printf '%s\n' "$entries" | rg -q -P '^lib/(?!arm64-v8a/)'; then
    echo "Inateck PoC verification failed: unexpected native ABI" >&2
    exit 1
fi

temporary="$(mktemp -d "${TMPDIR:-/tmp}/codematch-inateck-poc.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
while IFS= read -r dex; do
    unzip -p "$apk" "$dex"
done < <(printf '%s\n' "$entries" | rg '^classes[0-9]*[.]dex$') > "$temporary/all.dex"
strings "$temporary/all.dex" > "$temporary/dex-strings.txt"
if rg -q -i \
    'notify (00|01).*data|notify 00----- onDisConnected completion|setSettingInfo result|getSettingInfo result' \
    "$temporary/dex-strings.txt"; then
    echo "Inateck PoC verification failed: vendor raw-log strings remain in DEX" >&2
    exit 1
fi

echo "Inateck SDK PoC APK permissions, entry points, ABI, and vendor-log stripping are verified."
