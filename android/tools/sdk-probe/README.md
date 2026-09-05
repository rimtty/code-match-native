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

Further diagnostic flags compare the two native libraries' generated version
commands (status/byte-array validation, equality only, no transmitted extra command)
and classify the first response using the official notification parser. The probe
does not show or persist notification data, and does not interpret arbitrary scan
text as firmware. This classification is not a replacement for frame reassembly.

Observed on the same physical pair: generated version commands were equal;
firmware SDK failed in 81 ms; the official notification parser classified the
first response as incomplete (`notify_status=0`), while the SDK handler had
already declared reception complete. This provides a concrete reassembly gap
to test next. It does not yet establish that a complete response will contain a
valid firmware version. Eight unit tests/build/lint passed before installation.

## Reassembly experiment

The separate `本体ファームウェア取得（分割再構成）` button keeps the original
SDK command and completion but changes only the probe task's parse handler:
native notification status 0 retains native-returned data and returns Loading;
status 1 must pass the original SDK native firmware-result parser on either the
assembled response or the native-returned message before the original completion
is allowed. Buffer cap 8192 bytes; six-second application deadline; error, screen
departure and completion discard pending bytes. Arbitrary scan text is never
used as a version. The baseline getVersion button remains unchanged for comparison.
Three tests cover retained native prefixes, complete-but-invalid version,
malformed/oversize/cancelled input. This experiment is not in the production app.

**Physical success (2026-09-05, Pixel 7 + BCST-36):**

- `getHardwareInfo`: `OTA_D_V0.3.7` (52 ms).
- Reassembled firmware: `BCST-36 V2.6.16 AI JP` (154 ms).
- UI confirmed `再構成：分割結合して版解析成功` and identical generated commands.
- Same request and original SDK firmware parser/completion succeeded after
  reconstruction, whereas the baseline API failed on the incomplete first reply.
  This identifies the premature completion/reassembly defect for this observed
  device/firmware pair, not a blanket verdict about all SDK models.
- Eleven unit tests, build/lint passed. The normal scannerPoc app remains
  unchanged. Its recorded settings profile remains
  `inateck-android-sdk-2.0.0-area-name-v1`.

## Read-only external settings witness

The inventory buttons call only official `getSettingInfo`. Capture retains the
entire returned inventory keyed by exact `(area,name)` and all returned fields
in process memory. No disk, logs, hashes or raw values are exposed. Capture is
refused if a baseline already exists; explicit discard is required to replace it.
Comparison requires the same device, unique identities and a nonempty, bounded
inventory. Map order is ignored; missing/extra items or any changed value/field
are mismatches. This is stricter than comparing only symbologies: unrelated
general-setting changes can also cause a mismatch and need separate diagnosis.

To witness restoration: capture while the normal app is stopped and no session
is active; disconnect and wait for confirmed link closure; use the normal app;
end its session and await restoration; stop/disconnect the normal app; return to
the probe without killing its process; reconnect the same scanner and compare.
Activity recreation retains the in-memory witness, but process death loses it
and produces an explicit missing-baseline result. A probe-to-probe unchanged
comparison alone does NOT prove application restoration. Do not clear app data.
No restriction/restore write is made by this witness.

Physical witness result (2026-09-05, Pixel 7 + BCST-36): captured baseline with
normal app stopped; observed probe disconnect completion; launched installed
stable scannerPoc, confirmed SDK Ready, started empty session and observed
Bluetooth QR wait; confirmed End Session and subsequent Settings Ready;
force-stopped normal app after completion; returned to the still-alive probe,
reconnected same scanner and freshly read settings. UI confirmed all returned
settings/all fields exactly equal to baseline. No codes were scanned or history
entries created, and no existing records were cleared. This establishes normal
empty-session-end restoration, not unexpected-disconnect/timeout recovery.
The read-only comparison addition passed two new unit tests (13 total) and
assembleDebug/lintDebug. Baseline remains memory-only.

Second physical witness: reused the same baseline, disconnected the probe,
started another empty normal-app session and observed Bluetooth QR wait. User
confirmed scanner power OFF; UI changed to camera fallback while preserving QR
wait. User confirmed ON; without tapping reconnect the UI returned to Bluetooth
QR wait. Ended session, confirmed Settings Ready, stopped normal app after end,
reconnected the independent probe, explicitly reread and compared settings:
all returned settings/all fields matched the pre-session baseline again.
This covers power-cycle recovery followed by session-end restoration for the
tested pair. It does not measure the intermediate inventory before Ready, nor
independently cover auto-sleep, an injected SDK timeout, or restore-write failure.
