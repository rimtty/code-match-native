#!/bin/sh

set -eu

readonly SDK_REPOSITORY="https://github.com/Inateck-Technology-Inc/ios_sdk.git"
readonly SDK_COMMIT="03aa36d0e204997130afaca00c2176aa7e5089af"
readonly FRAMEWORK_BINARY_SHA256="8d831f550e470085f0362e5f94cce1d5fe681afef80eb592576648a5d63c388a"

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_directory/.." && pwd)
temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/codematch-inateck.XXXXXX")
checkout_directory="$temporary_directory/ios_sdk"
destination_directory="$repository_root/Vendor/Inateck"
source_framework="$checkout_directory/SDKDemo/InateckScannerBleKit.framework"

cleanup() {
    rm -rf "$temporary_directory"
}
trap cleanup EXIT HUP INT TERM

git init -q "$checkout_directory"
git -C "$checkout_directory" remote add origin "$SDK_REPOSITORY"
git -C "$checkout_directory" fetch -q --depth 1 origin "$SDK_COMMIT"
git -C "$checkout_directory" checkout -q --detach FETCH_HEAD

actual_checksum=$(shasum -a 256 "$source_framework/InateckScannerBleKit" | awk '{print $1}')
if [ "$actual_checksum" != "$FRAMEWORK_BINARY_SHA256" ]; then
    echo "Inateck SDK checksum mismatch." >&2
    echo "Expected: $FRAMEWORK_BINARY_SHA256" >&2
    echo "Actual:   $actual_checksum" >&2
    exit 1
fi

mkdir -p "$destination_directory"
ditto "$source_framework" "$destination_directory/InateckScannerBleKit.framework"

echo "Installed InateckScannerBleKit.framework at commit $SDK_COMMIT"
echo "Note: the upstream repository does not currently include an explicit license file."
