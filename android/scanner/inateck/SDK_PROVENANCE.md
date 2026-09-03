# Inateck Android SDK provenance

This PoC module uses binary artifacts published in the official
[`Inateck-Technology-Inc/android_sdk`](https://github.com/Inateck-Technology-Inc/android_sdk)
repository. `android/scripts/setup-inateck-sdk-poc.sh` downloads byte-for-byte
copies from commit `8ce0fd5d25d1` (2025-01-09) and rejects any SHA-256 mismatch.
The generated files are ignored by Git and must not be committed.

| Artifact | Git blob SHA-1 | SHA-256 |
| --- | --- | --- |
| `libs/inateck-scanner-ble-2-0-0.jar` | `2867f66a2fda92d8c62fda9d2d5ca4c59b17c644` | `a016e427ae9be3489b2293ce77bad1116a3ee7e25a475ab219764c9ac0981311` |
| `libs/jna-min.jar` | `5467dffb1a9001dbfe68432b4c3dc850c962e54a` | `b0f0a45245fbc5655c09756235d19e8a044bfe4ee239bcf8c72dd267827f6d35` |
| `src/main/jniLibs/arm64-v8a/libjnidispatch.so` | `cd7e6716fea3058c9657e8f6de3e9fd563c157fb` | `3730ce014f7a6807ae74af78c3b9f5a3cba1f5886316b3d3858493df923efe96` |
| `src/main/jniLibs/arm64-v8a/libscanner_cmd.so` | `d6333c7fae726696a65e49075ec8abd39e40eeb1` | `1aa9658a52e1e35da8af9a72fbe602b48698b105885ff98f1b1bf06a8e76bb8b` |

The FF01 notification framing and SDK-output-mode command use the official
[`Inateck-Technology-Inc/scanner_lib`](https://github.com/Inateck-Technology-Inc/scanner_lib)
command library. The setup script pins commit `6d8fc093656c` and verifies the
additional artifact independently:

| Artifact | Git blob SHA-1 | SHA-256 |
| --- | --- | --- |
| `src/main/jniLibs/arm64-v8a/libinateck_scanner_cmd.so` | `1731911472e3417122372bf184eeae1de8030775` | `96b3a8850813c4ebdb0b5086b5eef9af70d8c15b5227dc5ecbb22b5310478d31` |

The newer command library is intentionally used only alongside the official
Android BLE SDK: the Android SDK owns discovery, connection, settings, and
GATT access, while `scanner_lib` supplies the currently documented FF01 parser
and the FF04 command that routes barcode output to the SDK notification path.

The Android command library reassembles BCST-36 type-1 notifications but does
not expose the final notify-code decoder present in the official
[`Inateck-Technology-Inc/ios_sdk`](https://github.com/Inateck-Technology-Inc/ios_sdk)
framework at commit `03aa36d0e204` (2025-01-09). For interoperability, this
module performs that final protocol step locally: validate the trailing
low-eight-bit additive checksum, remove the two-byte notification header and
checksum byte, then pass only the payload to the strict UTF-8 decoder. A
physical BCST-36 confirmed the complete QR-to-Code-128 match flow with this
adapter. No scan payload, raw frame, setting value, or device identifier is
written to diagnostics.

The upstream repository does not currently include a license file. The user
has explicitly limited this integration to a local, non-distributed PoC. This
repository therefore contains only integration source and the pinned fetcher,
not copies of the SDK artifacts. Confirm redistribution terms before using
them in any distributed build.

The upstream demo also bundles `jna-platform.jar`, but the scanner SDK has no
reference to that desktop helper library. It is intentionally excluded from
the Android PoC to avoid unused AWT/Swing classes and reduce the local binary
surface.
