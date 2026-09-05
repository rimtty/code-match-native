# SDK fault injection (diagnostic only)

`InateckReadOnlyFaultGateway` is a debug-source-set decorator. The normal
`InateckExternalScanner.create()` factory does not construct it. It is absent
from the release source set. It is groundwork for a separate diagnostic host,
not a change to normal scanner operation and not yet a physical test result.

The decorator forwards SDK discovery, connection and settings reads. It holds
one completed read in process memory until explicitly released; additional
reads are rejected. Disconnect intentionally does not discard that completion,
so the real transport/session generation guards must reject a late delivery.
Closing the diagnostic host discards it. No inventory or identifier is logged
or written to storage.

Settings and illumination writes never reach the SDK. A setting write completes
with a fixed injected error; illumination is rejected. This allows a later host
to test a restore failure using a snapshot of the **unchanged** current settings,
without intentionally leaving physical symbologies restricted. Connection setup
still uses the real SDK and may perform authentication/output-mode setup.

Current evidence: six JVM tests cover one-shot held response, inventory copy,
no stacking, write suppression, close/discard, error sanitization, the production
transport's physical-disconnect gate, and the production session's 6,000 ms
read deadline (5,999 ms remains pending). The SDK is a stub in these tests.
Debug lint and release Kotlin compilation pass.

Still required: a separate opt-in APK host with vendor logging stripped, actual
SDK/Pixel/scanner execution, restore-failure snapshot retention, and verification
of the application camera-fallback projection. Do not use these JVM results to
close Issue #19's physical failure/late-callback acceptance item.
