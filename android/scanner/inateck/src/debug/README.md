# SDK fault injection (diagnostic only)

`InateckReadOnlyFaultGateway` is a debug-source-set decorator. The normal
`InateckExternalScanner.create()` factory does not construct it. It is absent
from the release source set. It is groundwork for a separate diagnostic host,
not a change to normal scanner operation. The separate opt-in host and its narrow
physical results are documented in `android/tools/sdk-fault-probe/README.md`.

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

Current evidence: seven JVM tests cover one-shot held response, inventory copy,
no stacking, write suppression, close/discard, error sanitization, the production
transport's physical-disconnect gate, and the production session's 6,000 ms
read deadline (5,999 ms remains pending), and snapshot retention after an injected
restore failure. The SDK is a stub in these tests.
Debug lint and release Kotlin compilation pass.

The independent APK has now exercised held read timeout/late completion and
pre-dispatch restore failure on Pixel/scanner. Actual in-flight write failure and
the application camera-fallback projection remain outside that host's evidence.
Do not use the narrower results to close Issue #19's complete physical gate.
