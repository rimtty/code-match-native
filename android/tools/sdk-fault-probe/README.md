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
- Connection/authentication and settings reads use the official SDK. The
  decorator never forwards a settings or illumination write. SDK connection
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
automatic reconnect coordinator. Do not infer camera-fallback UI, late replies
after a real set command, the 25-second write deadline, or successful restoration
from a failed/in-flight physical write from this narrower read-only experiment.
Issue #19 remains open until those applicable acceptance conditions are resolved.
