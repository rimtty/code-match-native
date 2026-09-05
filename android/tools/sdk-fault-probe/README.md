# Independent SDK failure probe

Local-only, opt-in debug APK, package `jp.rimtty.codematch.sdkfaultprobe`.
Not installed into CodeMatch and not available as a release variant.

Build with the same locally bootstrapped official SDK as scannerPoc:

```sh
./gradlew -PincludeSdkProbe=true :tools:sdk-fault-probe:assembleDebug :tools:sdk-fault-probe:lintDebug
./gradlew :scanner:inateck:testDebugUnitTest
```

The Activity is in `scanner:inateck/src/debug` so it can use the **unchanged**
internal production `AndroidInateckSdkGateway`, `InateckSdkTransport`, codec and
`BleSymbologySession`. The normal scanner factory never installs the fault
decorator, and the normal app manifest does not expose this Activity.

## Safety

- Disconnect the normal app and SDK version/inventory probe before using this
  APK; do not scan codes during the test. Select a scanner from SDK discovery;
  there is no model or fixed identity filter.
- Connection/authentication and settings reads use the official SDK. The two
  read-only modes never forward a settings or illumination write. The explicit
  **exact replay mode does send one unchanged symbology command**. SDK connection
  setup itself may configure its output mode, as in the normal adapter.
- Current settings are retained only in memory. No app repositories or user
  history are used. Values, identifiers, frames and scan payloads are not logged.
- Debug APK is minified and non-debuggable so R8 can remove vendor `Log` calls.
  Inspect the built APK before installation, especially vendor notification
  callbacks. No Internet/location/camera permissions are requested.
- Stop waits for the actual transport disconnect event before allowing another
  discovery. A failure to confirm close is not treated as disconnected.

## Read deadline

Choose the 6-second timeout mode and select the scanner. The real SDK completes
its read, but the decorator retains its callback. `実SDK読出し成功：true` is
required to distinguish this from connection failure or absent response.
The production session's real elapsed-time deadline triggers disconnect and
non-Ready. Release the retained callback using the button; Ready must stay false.
Physical disconnect confirmation and logical session state are shown separately.

## Recovery write failure

Choose restore failure mode after ending the previous test. Read the current
inventory and create an isolated in-memory snapshot of those **unchanged**
symbologies. A new session owner performs a second fresh SDK read and attempts
recovery. The decorator rejects the write before it reaches the scanner. Require
failure/non-Ready and a retained recovery snapshot. This is intentional fault
injection, not evidence of a naturally failing SDK/device or altered symbologies.

## Exact replay / real SDK write completion deadline

The third mode uses `InateckExactReplayGateway`. It arms once from the same
device's current snapshot. Different devices, changed values, missing/extra
items, general settings, and repeat writes are rejected. An additional fresh SDK
read must still match all armed symbologies immediately before dispatch. It then
performs a real SDK `setSettingInfo` and the production gateway's fresh readback,
but holds the completion callback. No illumination command is permitted.

The production session keeps its 25-second write deadline. The retained callback
can be delivered after timeout/disconnect to test the actual transport/session
generation guards. This simulates loss/delay of the application-facing completion,
not a device failing to apply an in-flight command.

Pixel 7/API36 + BCST-36, 2026-09-05: one real unchanged-settings replay and SDK
readback succeeded. While its success was held, the session remained Restoring,
Ready=false, baseline retained=true. After 25 seconds it timed out and physical
disconnect was confirmed. Late success delivery left Ready=false and the baseline
retained. A separate already-running inventory probe then reconnected, performed
a fresh read and compared all returned fields/items with its earlier baseline:
**exact match**. No normal app data was cleared and no codes were scanned.

Eight additional JVM tests cover the one-shot exact replay guard, fresh mismatch,
duplicate/late preparation callbacks, rejected preparation, sanitization,
transport disconnection, and 24,999/25,000 ms session deadline boundaries.

## Reconnect using the retained baseline

After an injected timeout/failure, end/disconnect the physical link without
starting a new discovery. `切断後：保持した基準で再接続` is accepted only when
physical disconnection was confirmed, no pending/active link remains, and the
same selected device still has a retained snapshot. It creates a fresh SDK
gateway/transport/session but keeps the existing memory store. It never captures
a replacement baseline during recovery.

This recovery again permits only a same-values replay with fresh validation.
While the real write/readback completion is held, require non-Ready and baseline
retention. Within the normal 25-second deadline, use
`再接続後：確認済み復元応答を反映`; only then may Ready become true and the
baseline clear. If the deadline expires first, reconnect again rather than
treating the late success as valid. Illumination/changed-value commands remain
prohibited.

This additional path builds and passes lint. A ninth replay JVM test verifies
the new session uses the old store until acknowledged recovery succeeds, then
clears it and publishes Ready. Physical execution of this new reconnect path
is pending: the normal app is currently being left untouched for its independent
auto-sleep acceptance observation.

## Evidence boundary

Seven JVM tests pass for the decorator plus production transport/session gates.
The APK build/lint and notification `Log` removal were checked. Initial physical
selection exposed an Activity/View `handler` name collision; explicitly using the
Activity handler fixed it. The scanner was power-cycled before the final run.

Pixel 7/API 36 + BCST-36 physical results (2026-09-05):

- Read mode: actual SDK settings completion succeeded and was held. After the
  6-second session deadline, UI showed timeout, Ready=false, physical disconnect
  confirmed, link held=false. Releasing the held result changed the delivery
  indicator to true; Ready remained false and the session stayed timed out.
- Recovery mode: actual SDK inventory was captured as an unchanged baseline,
  then a second read fed the recovery owner. Injected pre-dispatch write failure
  produced failure, Ready=false, baseline retained=true. The link was still
  connected until the explicit Stop operation; Stop confirmed physical disconnect
  and link held=false. No settings/illumination writes reached the SDK decorator.
- No normal app data was cleared, no scan codes were read, and the independently
  installed version/inventory probe was not overwritten.

This host does not instantiate CodeMatch's ViewModel/camera UI or the full
automatic reconnect coordinator. The exact replay mode proves an application-side
completion loss after a successful real set/readback, not a device failure partway
through a write. Do not infer camera-fallback UI or successful recovery of physically
partially modified settings from these probes.
Issue #19 remains open until those applicable acceptance conditions are resolved.
