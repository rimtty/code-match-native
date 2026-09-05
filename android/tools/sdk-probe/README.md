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

## Probe-only response observation

`VersionObservation` wraps the running SDK firmware task's parser, calling the
original handler exactly once and returning its unchanged result. Only booleans
are retained: whether a reply reached the SDK, whether it was null, and whether
the original handler declared reception complete. No bytes, payloads, error
messages or identifiers are retained or exported. Java accesses the public
2.0.0 JAR ABI of Kotlin-internal task APIs; this is intentionally an isolated
diagnostic, not a production SDK integration contract. If no running handler is
available, the UI explicitly reports the missing observation rather than inferring
no response. Two unit tests verify transparent forwarding and null/missing cases.

Observed with this hook on Pixel 7 + BCST-36: firmware API failed in 51 ms;
the UI reported a non-null response and the SDK parser declaring reception
complete. This rules out a simple no-response timeout for that attempt. It does
not prove the response was valid firmware data: a rejection/acknowledgement,
unexpected response or incomplete frame may still have reached the native parser.
The probe disconnected after the failure as designed. Build/lint and all four
unit tests passed before installation.

The comparison build additionally calls the official standalone library's
`inateck_scanner_cmd_software_result` on a temporary clone of the same response.
The signature is verified against `scanner_lib` commit
`6d8fc093656c3535c5a48bbe7de51eab4a471b48`, `cmd/scanner_cmd.h`
(`const uint8_t*`, `uintptr_t`; arm64 maps length to Java long).
It sends no additional command and changes neither the original parser result
nor SDK queue behavior. Only a classification or validated printable version is
displayed. Comparative timing includes the extra parser/JNA initialization, and
must not be treated as the original SDK-only latency. Two additional tests check
success/rejection/malformed/control-character display handling.

Physical comparison result: on Pixel 7 + BCST-36 the UI showed SDK failure
(102 ms including comparison), non-null response/SDK reception complete, and
`比較：公式解析ライブラリも拒否`. Thus both native version parsers rejected
the first SDK response. This does not establish unsupported firmware commands,
because response completeness/type and correspondence to the request are not
yet verified. All six probe unit tests plus build/lint passed.
