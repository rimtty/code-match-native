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

The upstream repository does not currently include a license file. The user
has explicitly limited this integration to a local, non-distributed PoC. This
repository therefore contains only integration source and the pinned fetcher,
not copies of the SDK artifacts. Confirm redistribution terms before using
them in any distributed build.

The upstream demo also bundles `jna-platform.jar`, but the scanner SDK has no
reference to that desktop helper library. It is intentionally excluded from
the Android PoC to avoid unused AWT/Swing classes and reduce the local binary
surface.
