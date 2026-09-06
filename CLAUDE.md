# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Native mobile monorepo for `code-match`: an operator scans a QR code (delivery slip), then a Code 128 barcode (product tag), and the app compares the two part numbers. Everything is on-device: no networking, no photo capture, no accounts, no analytics.

- `ios/`: SwiftUI/AVFoundation app (iOS 17+, Swift 5, bundle `jp.rimtty.CodeMatch`). Feature-complete; camera and Inateck BCST-47 Bluetooth input.
- `android/`: independent Gradle project (Kotlin, Jetpack Compose, Material 3, minSdk 31, compile/target SDK 37). Feature parity with iOS; camera (CameraX + bundled ML Kit) and Inateck BLE via the official Android SDK.

The cross-platform behavior contract lives in [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md). iOS design rationale and acceptance checklist: [docs/ios/IMPLEMENTATION_GUIDE.md](docs/ios/IMPLEMENTATION_GUIDE.md). Android porting plan and status: [docs/android/IMPLEMENTATION_PLAN.md](docs/android/IMPLEMENTATION_PLAN.md), [docs/android/STATUS.md](docs/android/STATUS.md), [docs/android/TEST_PARITY.md](docs/android/TEST_PARITY.md). Read the relevant ones before changing scanner flow or UI behavior.

## Project policy (as of 2026-09)

- **Android is for personal, local use only.** APKs are built and side-loaded by the owner; there is no store submission. Do not add store-submission work. The remaining physical-device / manual gates (Samsung, TalkBack, BLE fault cases, etc.) are waived and will not be verified further; `docs/android/STATUS.md` lists them under 打ち切った確認項目 (see issue #57). Scanner SDK libraries are bundled in the normal `release` build (#56); there is no separate `scannerPoc` build type anymore.
- **iOS is complete.** The "scanner stops reading after continuous use" audit (#58, closed 2026-09-06) landed as #62–#66: setting writes retry and recover instead of parking the session on the camera, `.inactive` no longer rewrites symbologies, the FF04 helper link has a deadline, the command timeout is 10 s with SDK link liveness checks, and the diagnostics log (300 events) can be shared from Settings. Verified on HPRT-4F5F; BCST-47 verification continues through TestFlight 1.0 (4).
- **Scanner tuning is applied on connect by both apps** (#59, closed): illumination `lighting_lamp_control` = 2 with a Settings toggle, plus a fixed profile (multi-code `*_read_more_code`/`*_read_multi` = 0, inverse `read_inverse_color`/`*_read_phase` = 0, red-light time `auto_close_mode` = 20 ≈ 4 s) written only when the inventory differs and confirmed by readback. Values persist on the scanner and are not restored on disconnect. The inventory-name ↔ generic-flag table is in `docs/ios/IMPLEMENTATION_GUIDE.md`. Per-step symbology on iOS (#39) is deferred.
- **Customer-facing data spec (澤井製作所向け)**: `docs/qr-barcode-spec-analysis.html` / `.pdf` are deliberately git-ignored (local only; revised 2026-09-06). They hold the QR field table and the 12 real label pairs. Keep them in sync when matching or scan-acceptance rules change, and regenerate the PDF from the HTML with headless Chrome.
- The 2026-09-06 spec audit follow-ups landed: camera Code 128 gets the same `4-2-4@code` format validation as BLE on both platforms (#78), and `shared/test-fixtures/matching-cases.json` pins all 12 real label pairs plus same-series mix-ups as mismatches (#79). Only pairs #1 and #9 carry a real full 66-char QR record; the other ten reuse a synthetic tail after the real first 22 characters — swap in the real decode when the label photos are re-read.
- Work items that should be tracked go into GitHub Issues (`gh issue create`).

## Git conventions

- Commit author: `rimtty <ttyrim@gmail.com>` (set in the repo-local config). **Do not add a `Co-Authored-By` trailer.**
- SSH pushes use `~/.ssh/id_ed25519_rimtty` (`core.sshCommand` in the repo-local config).
- Branch names follow `codex/<topic>`; PRs are squash-merged into `master`. Old local `codex/*` branches are mostly merged leftovers.

## Repository layout

- `ios/`: Xcode project, Swift sources, tests, Inateck iOS SDK bootstrap (`scripts/bootstrap_inateck_sdk.sh`, output in git-ignored `ios/Vendor/`), TestFlight tooling.
- `android/`: Gradle project — `app`, `core/{model,matching,designsystem,data,export}`, `feature/{scan,history,settings}`, `scanner/{api,camera,fake,ble,inateck}`, `scripts/`.
- `shared/test-fixtures/`: platform-neutral matching cases (`matching-cases.json`) and printable scan images. Keep this free of Swift/Kotlin production code.
- `shared/tools/generate_test_codes.swift`: regenerates the fixture images.
- `docs/`: `PRODUCT_SPEC.md`, `ios/`, `android/`.
- `.github/workflows/`: `ios-ci.yml` (device build + simulator tests) and `android-ci.yml` (unit/lint/build, emulator tests on API 36, release build + APK verification); both run only when their platform's paths change. Shared Android setup steps live in `.github/actions/setup-android`.

## Build & test

### iOS

Xcode project (no SPM/CocoaPods). Open `ios/CodeMatch.xcodeproj`, scheme `CodeMatch`.

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project ios/CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

Single test:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -project ios/CodeMatch.xcodeproj -scheme CodeMatch -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:CodeMatchTests/CodeMatcherTests/testComparisonIsCaseSensitive
```

Regenerate the printable sample codes in `shared/test-fixtures/images`:

```bash
swift shared/tools/generate_test_codes.swift
```

Simulators have no rear camera, so `CameraScanner` always fails there. Use the on-screen demo buttons (`demoMatchButton` / `demoMismatchButton`, plus the Bluetooth variants and the mock `SIMULATOR-BCST-47` scanner, compiled into simulator builds only via `#if targetEnvironment(simulator)`) to exercise match/mismatch UI, sound, and state transitions; real scanning must be verified on a device. UI tests rely on these buttons and therefore run on simulator only.

iOS CI runs two macOS jobs from `ios/`: `device-build` (Inateck SDK bootstrap + generic iOS build) and `simulator-test` (one `build-for-testing`, then `test-without-building` with per-test execution-time allowances and one retry). Do not split UI tests back into a per-test job matrix — macOS minutes bill at 10x. CI runners use an English locale; reproduce that locally with `-testLanguage en -testRegion US`, which is what exposed the Japanese-fallback bugs.

### Android

Run from `android/`. Needs JDK 21 (Android Studio's bundled JBR is fine) and Android SDK 37.

```bash
./gradlew assembleDebug
./gradlew lintDebug testDebugUnitTest          # JVM tests (~375)
bash scripts/run-connected-tests.sh           # instrumentation on a connected device/emulator (~110)
./gradlew :app:assembleRelease
bash scripts/setup-inateck-sdk.sh && ./gradlew :app:assembleRelease   # release bundles the official Inateck SDK (required binaries)
bash scripts/verify-release-hardening.sh                              # release APK privacy/packaging gate (add --dependency-report for the graph check)
```

Single JVM test: `./gradlew :core:matching:testDebugUnitTest --tests 'jp.rimtty.codematch.core.matching.CodeMatcherTest'`.

The Inateck Android SDK has no redistribution license: `setup-inateck-sdk.sh` fetches pinned, checksum-verified binaries into git-ignored paths (`android/scanner/inateck/libs/*.jar`, `src/main/jniLibs/**`). Never commit them. Emulators have no usable camera or BLE; `scanner/fake` (`debugImplementation` only) drives the scan flow in debug builds and instrumentation tests.

`scripts/verify-release-hardening.sh` is the single release gate: it inspects the release APK (forbidden permissions such as INTERNET, legacy Bluetooth, location; single exported MainActivity; backup/transfer exclusions and the scoped FileProvider in the compiled resources; no Fake or analytics classes; the Inateck adapter, its arm64 native libraries, stripped vendor logs, and ML Kit registrars in the DEX), the optional `releaseRuntimeClasspath` report, and production sources. No AAB is built or checked: the app is side-loaded as an APK only. Note the SDK's `.so` files are 4 KB page-aligned, so release does not run on 16 KB page-size devices (Pixel 7 is 4 KB).

## Architecture

### iOS

Flow: `CodeMatchApp` → `RootTabView` (owns the single `HistoryStore`, `BluetoothScannerService`, and `CameraScanner` as `@StateObject`s) → tabs. The scan tab shows `SessionStartView` until a session is active, then `ScannerScreen` keyed by `.id(session.id)`.

- **`ScannerViewModel`** (`@MainActor`, `ObservableObject`) is the state machine: `ScanStep` goes `.qr` → `.barcode` → `.result(MatchResult)`. It is the `CameraScannerDelegate`, so all scan handling funnels through `cameraScanner(_:didRead:type:)`, which drops reads that don't match the currently `expectedCode`.
- **`CameraScanner`** owns `AVCaptureSession` on a private serial `sessionQueue`, with metadata delivered on a second queue and hopped back to `@MainActor` before touching the delegate. Only `AVCaptureMetadataOutput` with `[.qr, .code128]` — deliberately no `AVCapturePhotoOutput`; do not add one.
- **`BluetoothScannerService`** wraps the Inateck iOS SDK (`#if INATECK_SDK`, simulator mock otherwise): discovery, connect/auto-reconnect, and symbology restriction. During a session it writes all symbology areas (flag 2001–2028) to 0 except `qrcode_on` / `code128_on`, using the `area`/`name` the device reported (never hardcoded areas), snapshots the original values, and restores them on session end/disconnect. All `setSettingInfo` traffic (session symbologies, illumination, tuning) goes through one gate (`settingCommandInFlight`, generation counters, 10 s timeout that closes the link and reconnects); failed writes retry twice and then recover from a fresh `getSettingInfo` every 5 s (max 3). After the session symbologies are ready the service applies illumination OFF, then the tuning profile, each confirmed by readback. Diagnostics keep 300 trace lines (no payloads) and `diagnosticLogText()` feeds the Settings share sheet.
- **`HistoryStore`** (`@MainActor`) holds `[MatchSession]` newest-first; at most one session has `endedAt == nil` (`activeSession`). Persists to `Application Support/CodeMatch/match-history.json` with `.completeFileProtection`, excluded from backup. `init(storageURL:)` exists so tests can point at a temp directory.
- **`CodeMatcher`** is the whole comparison rule: the QR (66-char fixed record) carries the part number at chars 11–20; the Code 128 is `PART-NO@code`. Both are normalized to a 10-char part number and compared. `compare` still contains a substring-containment fallback for non-standard QRs, but it is unreachable from the scan flow on both platforms: camera and BLE reject any QR that is not a valid 66-char record (`KanbanQRRecord.isValidScanPayload`) before comparison. Tests pin this behavior with real label payloads (all 12 documented pairs through the shared fixtures; see the synthetic-tail caveat above).

Two misread defenses that are intentional and easy to break accidentally:

- `scanLocked` suppresses reads for 250ms after accepting a QR value.
- Code 128 from the camera requires the *same* value seen twice within 1.5s (`barcodeCandidate`) before it is accepted; only then does the camera stop and comparison run. A BCST-47 trigger is accepted once. Camera and BLE Code 128 are both validated against the `4-2-4@code` business format (`TagBarcodeRecord.isValidScanPayload`) before the candidate logic runs; an unrelated Code 128 is rejected with a message and a 2 s repeat suppression, exactly like an unrelated QR (#78).

Audio/haptics: `FeedbackPlayer` synthesizes PCM tones via `AVAudioEngine` (ambient category, `.mixWithOthers`). It is a single shared instance (`FeedbackPlayer.shared`) — engine setup is expensive and per-view instances caused a re-render loop, so keep it that way. Individual code acceptance is haptic-only plus a short chirp; the success chime and the 4× failure alert fire only at the final verdict. Success is delayed ~0.28s after a barcode accept so the chirp and chime don't collide.

Only matches are recorded (`historyStore.recordMatch` on `.match`); mismatches never touch history or the header count. Duplicate detection keys on the box-specific QR payload (trimmed, uppercased) already matched in the active session (`activeSessionContainsMatchedQRPayload`), never on the part number: several boxes of one part number are recorded as 1箱目, 2箱目, … while re-scanning the same card yields `.duplicate`, which is shown but not recorded. Android mirrors this with `matchedQrPayloadIdentities` in `ScanReducer`, restored from the checkpoint.

### Android

Single `MainActivity` + Compose, Hilt DI, `NavigationSuiteScaffold` with three destinations (scan / history / settings), unidirectional `UiState` + `StateFlow`.

- **`core/model`, `core/matching`**: framework-free domain. `CodeMatcher` mirrors the Swift rule and is tested against `shared/test-fixtures/matching-cases.json` (loaded from the test classpath).
- **`feature/scan`**: `ScanReducer` (pure state machine), `ScanStabilizer` (camera Code 128 needs the same value twice; BLE reads are accepted once; a camera value outside the tag format skips the stabilizer and is rejected by `ScanReducer` on the first frame), `ScanSessionCoordinator`, `ScanCheckpointMapping`. `app/.../scan/ScanViewModel` persists a checkpoint (step, session, accepted values, input source) through Room/DataStore so an OS process kill restores the exact step.
- **`core/data`**: Room (`CodeMatchDatabase`, schema v2, exported schemas in `core/data/schemas`) for history, Preferences DataStore for settings and the scan checkpoint. Both are excluded from cloud backup and D2D transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
- **`core/export`**: A4 multi-page PDF via `PdfDocument`; saved through `CreateDocument` and shared through a `FileProvider` limited to `cache/codematch-pdf/`.
- **`scanner/api`**: `ExternalScanner` contract shared by camera/BLE/fake. **`scanner/camera`**: CameraX + bundled ML Kit, ROI limited to the on-screen guide (square for QR, wide for Code 128), only the format expected by the current step. **`scanner/ble`**: SDK-agnostic safety core — command queue, connection coordinator, per-step symbology restriction (QR step enables only flag 2022, Code 128 step only 2008, fresh readback required before Ready, full restore on session end/background), known-device store, reconnect budget. **`scanner/inateck`**: adapter over the official Inateck Android SDK 2.0.0 (`AndroidInateckSdkGateway`, native notification parser via JNA), illumination control (`lighting_lamp_control`, default 2 = always off on each connection, not restored on disconnect), and the connect-time tuning profile (`InateckTuningSettings`, applied after illumination settles, differences only, readback-confirmed). The BLE diagnostic log keeps 300 events; Settings can share it (ACTION_SEND text) or save it (SAF) via host-owned actions in `SettingsRoute`.
- **DI per build type**: `app/src/debug` binds `FakeExternalScanner`; `app/src/release` binds `InateckExternalScanner` (`releaseImplementation(project(":scanner:inateck"))`). Release is minified (R8 strips the SDK's raw-payload logging via `app/scanner-rules.pro`), arm64-v8a only, and signed with the debug keystore unless `codematchRelease*` Gradle properties supply a local keystore. `app/src/release/AndroidManifest.xml` adds `BLUETOOTH_SCAN` (neverForLocation) / `BLUETOOTH_CONNECT` and removes the legacy permissions the SDK manifest brings in. `UnavailableExternalScanner` remains in `app/src/main` as a fallback type only.
- **Strings**: `values/strings.xml` is Japanese (default), `values-en/` English; Android lint's `MissingTranslation` check (run by `lintDebug`) fails the build if a key is missing in either. In-app language and Android 13+ per-app locale are kept in sync by `AppLanguageSynchronizer`.

## Conventions

- iOS user-facing strings live in `ios/CodeMatch/Resources/Localizable.xcstrings` (source language **ja**, English translations) and are resolved through `AppLocalization.string(...)` / `AppLanguage`; Japanese is the fallback regardless of system locale. **Every key must keep an explicit `ja` entry with `state: translated`** — otherwise Xcode emits no `ja.lproj/Localizable.strings` and English-locale hosts (CI) silently resolve Japanese to English. When adding copy, add both `ja` and `en` entries.
- Both apps default to Japanese, and UI/instrumentation tests match on the literal Japanese labels (e.g. `一致しました`, `終了する`), so changing copy breaks tests on both platforms.
- Keep SwiftUI view `init`s cheap and side-effect-free: they rerun on every parent body evaluation. Anything heavy (stores, `AVCaptureSession`, audio engines) or side-effectful (UserDefaults resets) belongs inside a `@StateObject(wrappedValue:)` autoclosure (see `RootTabView.makeHistoryStore()`) or a shared instance. Violating this saturated the main thread and hung every UI test.
- iOS UI tests drive the app through launch arguments: `-resetHistory`, `-resetLanguage`, `-resetAutoAdvance`, `-resetBluetoothScanner` / `-demoBluetoothConnected`, and `-demoMatch` / `-demoMismatch`. Keep these sites in sync when adding a hook.
- Views are addressed in tests by accessibility identifiers (iOS `.accessibilityIdentifier`, Compose `testTag`) such as `startSessionButton`, `endSessionButton`, `sessionMatchCount`, `resetButton`, `scannerTitle`, `historySessionRow`; preserve them when refactoring.
- Colors come only from `AppTheme` (iOS) / `CodeMatchTheme` (Android, contrast pinned at ≥ 4.5:1 by `CodeMatchThemeContrastTest`); iOS pins `.preferredColorScheme(.light)`.
- Privacy: iOS declares no tracking in `Resources/PrivacyInfo.xcprivacy`; Android release must not request INTERNET, location, or legacy Bluetooth permissions (`docs/android/PRIVACY.md`). Any SDK addition must keep these boundaries.
- Do not leave Xcode `DerivedData` (`.derived-*`) or other build output in the repo root; they are git-ignored but consume gigabytes.
