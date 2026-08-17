# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

SwiftUI/AVFoundation iOS app (iOS 17+, Swift 5, bundle `jp.rimtty.CodeMatch`) that ports the `code-match` web SPA. The operator scans a QR code, then a Code 128 barcode, and the app compares the two strings. Everything is on-device: no networking, no photo capture, no accounts.

Design rationale, the ported spec, and the acceptance checklist live in [docs/IMPLEMENTATION_GUIDE.md](docs/IMPLEMENTATION_GUIDE.md) — read it before changing scanner flow or UI behavior.

## Build & test

Xcode project (no SPM/CocoaPods). Open `CodeMatch.xcodeproj`, scheme `CodeMatch`.

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

Single test target or single test:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:CodeMatchTests/CodeMatcherTests/testComparisonIsCaseSensitive
```

Regenerate the printable sample codes in `TestResources/Generated`:

```bash
swift tools/generate_test_codes.swift TestResources/Generated
```

Simulators have no rear camera, so `CameraScanner` always fails there. Use the on-screen demo buttons (`demoMatchButton` / `demoMismatchButton`, visible in the scanner card) to exercise match/mismatch UI, sound, and state transitions; real scanning must be verified on a device.

## Architecture

Flow: `CodeMatchApp` → `RootTabView` (owns the single `HistoryStore`) → tabs. The scan tab shows `SessionStartView` until a session is active, then `ScannerScreen` keyed by `.id(session.id)`.

- **`ScannerViewModel`** (`@MainActor`, `ObservableObject`) is the state machine: `ScanStep` goes `.qr` → `.barcode` → `.result(MatchResult)`. It is the `CameraScannerDelegate`, so all scan handling funnels through `cameraScanner(_:didRead:type:)`, which drops reads that don't match the currently `expectedCode`.
- **`CameraScanner`** owns `AVCaptureSession` on a private serial `sessionQueue`, with metadata delivered on a second queue and hopped back to `@MainActor` before touching the delegate. Only `AVCaptureMetadataOutput` with `[.qr, .code128]` — deliberately no `AVCapturePhotoOutput`; do not add one.
- **`HistoryStore`** (`@MainActor`) holds `[MatchSession]` newest-first; at most one session has `endedAt == nil` (`activeSession`). Persists to `Application Support/CodeMatch/match-history.json` with `.completeFileProtection`, excluded from backup. `init(storageURL:)` exists so tests can point at a temp directory.
- **`CodeMatcher`** is the whole comparison rule: the QR (delivery slip, 66-char fixed record) carries the part number at chars 11–20; the Code 128 (product tag) is `PART-NO@code`. Both are normalized to a 10-char part number and compared; non-standard QRs fall back to substring containment. See `docs/qr-barcode-spec-analysis.html` for the decoded field map. Tests pin this behavior with real label payloads.

Two misread defenses that are intentional and easy to break accidentally:

- `scanLocked` suppresses reads for 250ms after accepting a QR value.
- Code 128 requires the *same* value seen twice within 0.7s (`barcodeCandidate`) before it is accepted; only then does the camera stop and comparison run.

Audio/haptics: `FeedbackPlayer` synthesizes PCM tones via `AVAudioEngine` (ambient category, `.mixWithOthers`). Per the guide, individual code acceptance is haptic-only plus a short chirp; the success chime and the 4× failure alert fire only at the final verdict. Success is delayed ~0.28s after a barcode accept so the chirp and chime don't collide.

Only matches are recorded (`historyStore.recordMatch` on `.match`); mismatches never touch history or the header count.

## Conventions

- All user-facing strings are Japanese, hardcoded in the views — there is no localization catalog. UI tests match on those literal Japanese labels (e.g. `一致しました`, `終了する`), so changing copy breaks `CodeMatchUITests`.
- UI tests drive the app through launch arguments handled in `HistoryStore.init` / `ScannerViewModel.init` / `RootTabView.init`: `-resetHistory` wipes the store, `-demoMatch` boots straight into an active session showing a match. Keep the three sites in sync when adding a hook.
- Views are addressed in tests by `.accessibilityIdentifier` (`startSessionButton`, `endSessionButton`, `sessionMatchCount`, `resetButton`, `scannerTitle`, `historySessionRow`, …); preserve identifiers when refactoring view hierarchies.
- Colors come only from `AppTheme` (inherited from the web version's charcoal/green/lime); the app pins `.preferredColorScheme(.light)`.
- The app declares no tracking in `Resources/PrivacyInfo.xcprivacy`. Any cloud sync, analytics, or SDK addition requires updating that manifest and the App Store Connect answers.
