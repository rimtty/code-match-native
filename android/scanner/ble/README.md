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

The unit tests run on the JVM and use no scanner hardware.
