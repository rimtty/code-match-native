# BLE scanner core

This module contains the SDK-neutral BLE boundary and deterministic protocol
state used by the Android port. It deliberately does not contain an Android
`BluetoothGatt` implementation or an Inateck dependency.

The future platform adapter supplies discovery/connection callbacks, the
settings characteristic endpoint, and GATT read/write operations through
`BleTransport`. UUIDs and scanner command details are therefore discovered and
validated by the adapter instead of being assumed here.

`BleSymbologySession` receives a `BleSymbologyProfile` containing the endpoint
and a `BleSymbologyCodec`. The transport always remains a raw `ByteArray`
boundary: the session never assumes that a read is UTF-8 JSON or that a write
uses `area`/`name`/`value` fields. There is no implicit codec default:
production construction must explicitly choose a codec in the profile.
`IosObservedSymbologyCodec` can be selected explicitly for tests and the
canonical iOS-observed JSON representation. An Android adapter must inject
another codec/profile when its bytes or flag format differs; no Android flag
range is fixed by the session.

The core guarantees:

- one GATT setting command in flight at a time;
- a timed-out command blocks further writes until the owner reports that the
  transport link has been closed and reset;
- a fresh, complete device-reported symbology inventory is required before a
  session restriction is applied;
- QR and Code 128 remain enabled together for the physical session, while the
  app validates the logical scan order;
- all symbology entries accepted by the adapter-selected codec retain their
  reported `name`/`area`/value and optional flag/metadata for restoration;
- connection adapters can attach request/link generations so callbacks from
  an older physical link are rejected before they reach scan state;
- `SymbologySettingCommand` carries optional flags and extra fields so an
  encoder can round-trip metadata when the scanner protocol requires it;
- raw scanner callbacks can be converted through `BleScanPayloadFactory` (or
  an injected `BleScanCallbackDecoder`) before a typed `ScanReceived` event is
  emitted; malformed envelopes and invalid UTF-8 are rejected;
- diagnostic events contain only sanitized connection/configuration outcomes,
  never scan payload text.
- `BleSymbologySnapshotSerializer` persists a versioned envelope containing
  only the adapter-selected profile identity, device ID, complete reported
  symbology inventory, and capture time. Every item keeps its `name`, `area`,
  value, optional flag, and stringified extra fields in original order.
- `BleSymbologySnapshotStore` is an app-private Preferences DataStore adapter.
  Its atomic write/clear operations are suitable for the session's save-before-
  apply and clear-after-restore lifecycle. Corrupt values, unsupported schema
  versions, and device/profile mismatches return an explicit rejection and are
  never treated as an empty store. The DataStore file is
  `files/datastore/codematch-ble-symbology.preferences_pb`; the app backup and
  device-transfer rules must exclude that exact path.
- `BleKnownDeviceStore` uses that same excluded DataStore for a separate,
  versioned profile-bound envelope containing only device ID and advertised
  name. It never stores settings, scan payloads, or raw frames. A recreated
  coordinator may reconnect that identity without discovery, but it still
  cannot become ready until the normal fresh-inventory and restore handshake
  succeeds.

The unit tests run on the JVM and use no scanner hardware.

## Android platform transport

`AndroidBleTransport` is the generic Android implementation of `BleTransport`.
Its production platform uses `BluetoothManager`, `BluetoothAdapter`,
`BluetoothLeScanner`, and `BluetoothGatt`; tests and host integrations can use
the `BlePlatform`/`BlePlatformGatt` seam instead. `AndroidBleProtocolProfile`
injects the service/read/write/notify UUIDs, GATT write type, notification
descriptor options, and a `BleNotificationDecoder`. No scanner UUID, framing,
or vendor SDK is selected in this module.

The transport checks `BLUETOOTH_SCAN` before discovery and
`BLUETOOTH_CONNECT` before a connection. Discovery and connection deadlines
are 5 seconds and 30 seconds respectively, through `BleTimeoutScheduler` so
they can be advanced deterministically in JVM tests. Moving the adapter to
`BACKGROUND` or `DESTROYED` stops discovery and performs `disconnect()` then
`close()`; callbacks retain request/link generations and stale callbacks are
discarded. Read/write callbacks complete once even when the platform call
fails synchronously. Notification bytes are passed only to the injected
decoder and never to diagnostics.

## M4 adapter audit (2026-09-02)

The upstream `Inateck-Technology-Inc/android_sdk` repository was inspected as
an investigation input only. Its current demo contains an
`inateck-scanner-ble-2-0-0.jar` (with FastBle, Gson, JNA, and an arm64 native
library) and exposes `BleListManager`, `BleScannerDevice`, and `BleMessager`
entry points. The repository currently does not provide a license file or
license metadata, so the artifact is not a production dependency until its
distribution terms and target-scanner compatibility are confirmed.

The demo requests additional legacy/location/advertise/Internet permissions,
which are intentionally not copied into this project. Its logging path also
prints raw notification bytes before dispatch, and an unsolicited barcode
callback contract was not established from the public API. That behavior is
not suitable for this app's payload privacy or scan-delivery requirements.

The demo's service/characteristic constants and command examples are useful
leads for a physical investigation only. They are not protocol guarantees and
must not be moved into this module. A future adapter must discover and record
the target scanner's UUIDs, notification framing, scan callback semantics,
setting inventory, and restoration behavior, then inject those observations
through `BleTransport`, `BleSymbologyProfile`, and the adapter-selected codec.
