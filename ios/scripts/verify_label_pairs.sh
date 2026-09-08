#!/bin/sh
# Run decoded label pairs through the production matching core without Xcode.
#
#   swiftc -O shared/tools/decode_label_photos.swift -o /tmp/decode_label_photos
#   /tmp/decode_label_photos tmp/labels > tmp/decoded.tsv       # photos stay git-ignored
#   ios/scripts/verify_label_pairs.sh tmp/decoded.tsv
#
# Compiles ios/CodeMatch/Models/ScanModels.swift together with the driver in
# ios/scripts/verify_label_pairs/main.swift, so the result reflects exactly the
# rules the app ships. Run it once on master to see what the current build does
# with a new label set, and again after changing the rules. See
# docs/label-variation-playbook.html.
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 <decoded.tsv>" >&2
    exit 2
fi

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ios_root=$(CDPATH= cd -- "$script_directory/.." && pwd)
build_directory=$(mktemp -d "${TMPDIR:-/tmp}/codematch-verify-labels.XXXXXX")
trap 'rm -rf "$build_directory"' EXIT HUP INT TERM

# swiftc treats only a file named main.swift as the program entry point.
cp "$script_directory/verify_label_pairs/main.swift" "$build_directory/main.swift"
cp "$ios_root/CodeMatch/Models/ScanModels.swift" "$build_directory/ScanModels.swift"
xcrun swiftc -O "$build_directory/ScanModels.swift" "$build_directory/main.swift" -o "$build_directory/verify_label_pairs"
"$build_directory/verify_label_pairs" "$1"
