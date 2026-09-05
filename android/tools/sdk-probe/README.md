# Standalone Inateck SDK version probe (local only)

Separate APK `jp.rimtty.codematch.sdkprobe`. No dependency on CodeMatch app,
adapter, Room, DataStore, camera, scan parser, or settings controller.
Uses the official SDK JAR directly: discovery/select/connect, `getHardwareInfo`,
`getVersion`, disconnect. No symbology/illumination/HID configuration writes.
SDK connection still performs its own authorization handshake.

Bootstrap the ignored vendor files with `android/scripts/setup-inateck-sdk-poc.sh`.
From `android`, with Android Studio JBR and ANDROID_HOME configured:

```sh
./gradlew -PincludeSdkProbe=true :tools:sdk-probe:assembleDebug :tools:sdk-probe:testDebugUnitTest
```

Module is absent unless explicitly opted in. Both build variants minify and strip
vendor Android logs (including raw notifications). No INTERNET permission, disk
storage, analytics or export. Displayed versions are not firmware authenticity proof.
No SDK binaries are redistributed in Git. APK is a local PoC only.

Before testing, end the CodeMatch session, disconnect its scanner, and close that
app so two applications do not compete for GATT. Do not scan codes during this
test. Grant Nearby permission, search, select the intended SDK device, then read
Bluetooth version and firmware separately. A firmware API failure does not prove
the device is unsupported. No fabricated/default version is substituted.

Operations are single-flight. Six-second read timeout invalidates late callbacks
and disconnects; leaving the screen also disconnects. Restart the probe after an
interrupted operation. The normal app and its data are not replaced or cleared.

## Observed on 2026-09-05

Pixel 7 + currently available BCST-36, official SDK JAR 2.0.0:

- Direct SDK discovery and connection succeeded.
- `getHardwareInfo`: `OTA_D_V0.3.7`, UI measured 67 ms. This is the Bluetooth
  component version, NOT the scanner firmware revision.
- `getVersion`: SDK failure, UI measured 80 ms (before the 6-second probe timeout).
- Both results were observed together on the independent probe screen. No
  CodeMatch adapter, configuration controller or session logic was involved.
- This reproduces firmware read failure outside CodeMatch, but does not yet
  distinguish device/firmware API support from vendor command/response parsing.

Validation: assembleDebug, testDebugUnitTest (2 tests), lintDebug passed.
Final APK approximately 2.5 MiB; package identity verified; no INTERNET, camera,
or location permissions. `debug` is intentionally non-debuggable: R8 otherwise
retains vendor logging. Inspected compiled SDK notification callback and version
completion to verify Log calls were removed. No iOS changes or tests.

The first runtime smoke exposed the vendor JAR's undeclared ActivityCompat
dependency. AndroidX core is now explicit; subsequent search/connect/read ran.
Vendor native libraries may trigger Android's 16 KB compatibility warning;
this PoC does not claim 16 KB ELF compatibility or distribution readiness.
