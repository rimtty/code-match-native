# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Native mobile monorepo for `code-match`. The existing app is a SwiftUI/AVFoundation iOS app (iOS 17+, Swift 5, bundle `jp.rimtty.CodeMatch`) under `ios/`. Android has not been added yet and will live in an independent top-level `android/` Gradle project. The operator scans a QR code, then a Code 128 barcode, and the app compares the two strings. Everything is on-device: no networking, no photo capture, no accounts.

The cross-platform behavior contract lives in [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md). iOS design rationale and the acceptance checklist live in [docs/ios/IMPLEMENTATION_GUIDE.md](docs/ios/IMPLEMENTATION_GUIDE.md) — read both before changing scanner flow or UI behavior.

## Repository layout

- `ios/`: Xcode project, Swift sources, tests, iOS SDK bootstrap, and TestFlight tooling.
- `shared/test-fixtures/`: platform-neutral matching cases and printable scan images. Keep this free of Swift/Kotlin production code.
- `docs/ios/`: iOS-only architecture and physical-device validation.
- `.github/workflows/ios-ci.yml`: iOS device build and Simulator test workflow.

## Build & test

Xcode project (no SPM/CocoaPods). Open `ios/CodeMatch.xcodeproj`, scheme `CodeMatch`.

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project ios/CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

Single test target or single test:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project ios/CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:CodeMatchTests/CodeMatcherTests/testComparisonIsCaseSensitive
```

Regenerate the printable sample codes in `shared/test-fixtures/images`:

```bash
swift shared/tools/generate_test_codes.swift
```

Simulators have no rear camera, so `CameraScanner` always fails there. Use the on-screen demo buttons (`demoMatchButton` / `demoMismatchButton`, plus the Bluetooth variants and the mock `SIMULATOR-BCST-47` scanner, compiled into simulator builds only via `#if targetEnvironment(simulator)`) to exercise match/mismatch UI, sound, and state transitions; real scanning must be verified on a device. UI tests rely on these buttons and therefore run on simulator only.

CI (`.github/workflows/ios-ci.yml`) runs two macOS jobs from `ios/`: `device-build` (Inateck SDK bootstrap + generic iOS build) and `simulator-test` (one `build-for-testing`, then `test-without-building` for unit and UI tests with per-test execution-time allowances and one retry). Do not split UI tests back into a per-test job matrix — macOS minutes bill at 10x and the matrix burned them for no coverage gain. CI runners use an English locale; reproduce that locally with `-testLanguage en -testRegion US`, which is what exposed the Japanese-fallback bugs.

## Architecture

Flow: `CodeMatchApp` → `RootTabView` (owns the single `HistoryStore`, `BluetoothScannerService`, and `CameraScanner` as `@StateObject`s) → tabs. The scan tab shows `SessionStartView` until a session is active, then `ScannerScreen` keyed by `.id(session.id)`.

- **`ScannerViewModel`** (`@MainActor`, `ObservableObject`) is the state machine: `ScanStep` goes `.qr` → `.barcode` → `.result(MatchResult)`. It is the `CameraScannerDelegate`, so all scan handling funnels through `cameraScanner(_:didRead:type:)`, which drops reads that don't match the currently `expectedCode`.
- **`CameraScanner`** owns `AVCaptureSession` on a private serial `sessionQueue`, with metadata delivered on a second queue and hopped back to `@MainActor` before touching the delegate. Only `AVCaptureMetadataOutput` with `[.qr, .code128]` — deliberately no `AVCapturePhotoOutput`; do not add one.
- **`HistoryStore`** (`@MainActor`) holds `[MatchSession]` newest-first; at most one session has `endedAt == nil` (`activeSession`). Persists to `Application Support/CodeMatch/match-history.json` with `.completeFileProtection`, excluded from backup. `init(storageURL:)` exists so tests can point at a temp directory.
- **`CodeMatcher`** is the whole comparison rule: the QR (delivery slip, 66-char fixed record) carries the part number at chars 11–20; the Code 128 (product tag) is `PART-NO@code`. Both are normalized to a 10-char part number and compared; non-standard QRs fall back to substring containment. See `docs/PRODUCT_SPEC.md` for the shared matching contract. Tests pin this behavior with real label payloads.

Two misread defenses that are intentional and easy to break accidentally:

- `scanLocked` suppresses reads for 250ms after accepting a QR value.
- Code 128 requires the *same* value seen twice within 0.7s (`barcodeCandidate`) before it is accepted; only then does the camera stop and comparison run.

Audio/haptics: `FeedbackPlayer` synthesizes PCM tones via `AVAudioEngine` (ambient category, `.mixWithOthers`). It is a single shared instance (`FeedbackPlayer.shared`) — engine setup is expensive and per-view instances caused a re-render loop, so keep it that way. Per the guide, individual code acceptance is haptic-only plus a short chirp; the success chime and the 4× failure alert fire only at the final verdict. Success is delayed ~0.28s after a barcode accept so the chirp and chime don't collide.

Only matches are recorded (`historyStore.recordMatch` on `.match`); mismatches never touch history or the header count.

## Conventions

- User-facing strings live in `ios/CodeMatch/Resources/Localizable.xcstrings` (source language **ja**, English translations) and are resolved through `AppLocalization.string(...)` / `AppLanguage` (`ios/CodeMatch/App/AppLanguage.swift`); the in-app language setting is stored under `AppLanguage.storageKey` and Japanese is the fallback regardless of system locale. **Every key must keep an explicit `ja` entry with `state: translated`** — otherwise Xcode emits no `ja.lproj/Localizable.strings` and English-locale hosts (CI) silently resolve Japanese to English. When adding copy, add both `ja` and `en` entries.
- The app defaults to Japanese, and UI tests match on the literal Japanese labels (e.g. `一致しました`, `終了する`), so changing copy breaks `CodeMatchUITests`.
- Keep SwiftUI view `init`s cheap and side-effect-free: they rerun on every parent body evaluation. Anything heavy (stores, `AVCaptureSession`, audio engines) or side-effectful (UserDefaults resets) belongs inside a `@StateObject(wrappedValue:)` autoclosure (see `RootTabView.makeHistoryStore()`) or a shared instance. Violating this saturated the main thread and hung every UI test with "Timed out while evaluating UI query".
- UI tests drive the app through launch arguments: `-resetHistory` (`HistoryStore.init`), `-resetLanguage` (`AppLanguage.prepareForLaunch`), `-resetAutoAdvance` (`AutoAdvanceSettings`, invoked from `RootTabView.makeHistoryStore`), `-resetBluetoothScanner` / `-demoBluetoothConnected` (`BluetoothScannerService`), and `-demoMatch` / `-demoMismatch` (`RootTabView.makeHistoryStore` + `ScannerViewModel.init`) which boot straight into an active session showing a result. Keep these sites in sync when adding a hook.
- Views are addressed in tests by `.accessibilityIdentifier` (`startSessionButton`, `endSessionButton`, `sessionMatchCount`, `resetButton`, `scannerTitle`, `historySessionRow`, …); preserve identifiers when refactoring view hierarchies.
- Colors come only from `AppTheme` (inherited from the web version's charcoal/green/lime); the app pins `.preferredColorScheme(.light)`.
- The app declares no tracking in `Resources/PrivacyInfo.xcprivacy`. Any cloud sync, analytics, or SDK addition requires updating that manifest and the App Store Connect answers.
