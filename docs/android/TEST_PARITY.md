# iOS↔Android テスト意図パリティ表

監査日: 2026-09-04。対象は [`CodeMatcherTests.swift`](../../ios/CodeMatchTests/CodeMatcherTests.swift) の XCTest 71本と [`CodeMatchUITests.swift`](../../ios/CodeMatchUITests/CodeMatchUITests.swift) の UI テスト5本。件数やテスト名の一致ではなく、各テストが保証する意図を Android 側の証拠へ対応付ける。Android の `D` は同じ契約を同等の層で検査、`P` は近接する状態・部品の検査（元テスト全体の代替ではない）、`—` は Android に適用されるが証拠がない行、`N/A` は Android の現行共通仕様に含まれず対応不要と根拠リンクで確認した行を表す。

共通照合データは [`matching-cases.json`](../../shared/test-fixtures/matching-cases.json)（schemaVersion 1、5ケース）であり、Swift はファイルを直接読み、Kotlin は test runtime classpath から読む。`src/test` は JVM テスト、`src/androidTest` は端末・エミュレーター依存の証拠である。D/P は「実カメラ読取」や「対象 BLE scanner 通信」の成功を意味しない。

### Android 証拠ファイル（略記の正本）

表中のテストクラス名は、次の相対パスのファイルを指す。

| テストクラス | 正確なファイル |
|---|---|
| `CameraUiContractTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/CameraUiContractTest.kt` |
| `CameraStageTest` | `android/feature/scan/src/androidTest/kotlin/jp/rimtty/codematch/feature/scan/CameraStageTest.kt` |
| `CameraScannerAsyncTest` | `android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraScannerAsyncTest.kt` |
| `BundledMlKitImageDecodeTest` | `android/scanner/camera/src/androidTest/kotlin/jp/rimtty/codematch/scanner/camera/BundledMlKitImageDecodeTest.kt` |
| `CameraStopBoundaryTest` | `android/app/src/test/java/jp/rimtty/codematch/scan/CameraStopBoundaryTest.kt` |
| `CameraPermissionStateTest` | `android/app/src/test/java/jp/rimtty/codematch/scan/CameraPermissionStateTest.kt` |
| `CameraModelsTest` | `android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt` |
| `CodeMatcherTest` | `android/core/matching/src/test/kotlin/jp/rimtty/codematch/core/matching/CodeMatcherTest.kt` |
| `SettingsModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/SettingsModelsTest.kt` |
| `SettingsRepositoryTest` | `android/core/data/src/androidTest/kotlin/jp/rimtty/codematch/core/data/SettingsRepositoryTest.kt` |
| `HistoryUiTextTest` | `android/feature/history/src/test/kotlin/jp/rimtty/codematch/feature/history/HistoryUiTextTest.kt` |
| `HistoryExportTextTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryExportTextTest.kt` |
| `HistoryPdfContentTest` | `android/core/export/src/test/kotlin/jp/rimtty/codematch/core/export/HistoryPdfContentTest.kt` |
| `HistoryPdfBridgeTest` | `android/app/src/test/java/jp/rimtty/codematch/history/HistoryPdfBridgeTest.kt` |
| `HistoryPdfExporterInstrumentationTest` | `android/core/export/src/androidTest/kotlin/jp/rimtty/codematch/core/export/HistoryPdfExporterInstrumentationTest.kt` |
| `ScanModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/ScanModelsTest.kt` |
| `Code128BarcodeTest` | `android/feature/settings/src/test/kotlin/jp/rimtty/codematch/feature/settings/Code128BarcodeTest.kt` |
| `SetupBarcodeDecodeTest` | `android/feature/settings/src/androidTest/kotlin/jp/rimtty/codematch/feature/settings/SetupBarcodeDecodeTest.kt` |
| `BleSymbologyTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologyTest.kt` |
| `BleSymbologySessionTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySessionTest.kt` |
| `FakeExternalScannerTest` | `android/scanner/fake/src/test/kotlin/jp/rimtty/codematch/scanner/fake/FakeExternalScannerTest.kt` |
| `BleSymbologySnapshotSerializerTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotSerializerTest.kt` |
| `BlePayloadTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BlePayloadTest.kt` |
| `BleConnectionCoordinatorTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleConnectionCoordinatorTest.kt` |
| `BleSymbologySnapshotStoreTest` | `android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStoreTest.kt` |
| `BleKnownDeviceRecoveryTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceRecoveryTest.kt` |
| `BleKnownDeviceStoreTest` | `android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt` |
| `ScanReducerTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/ScanReducerTest.kt` |
| `ScanSessionCoordinatorTest` | `android/feature/scan/src/test/kotlin/jp/rimtty/codematch/feature/scan/ScanSessionCoordinatorTest.kt` |
| `BleScannerSessionCoordinatorTest` | `android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleScannerSessionCoordinatorTest.kt` |
| `HistoryRepositoryTest` | `android/core/data/src/androidTest/kotlin/jp/rimtty/codematch/core/data/HistoryRepositoryTest.kt` |
| `HistoryModelsTest` | `android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/HistoryModelsTest.kt` |
| `ScanScreenTest` | `android/feature/scan/src/androidTest/kotlin/jp/rimtty/codematch/feature/scan/ScanScreenTest.kt` |
| `SettingsScreenTest` | `android/feature/settings/src/androidTest/kotlin/jp/rimtty/codematch/feature/settings/SettingsScreenTest.kt` |
| `FeedbackContractTest` | `android/app/src/test/java/jp/rimtty/codematch/feedback/FeedbackContractTest.kt` |
| `HistoryScreenTest` | `android/feature/history/src/androidTest/kotlin/jp/rimtty/codematch/feature/history/HistoryScreenTest.kt` |
| `AppFlowInstrumentationTest` | `android/app/src/androidTest/java/jp/rimtty/codematch/AppFlowInstrumentationTest.kt` |
| `NavigationTest` | `android/app/src/androidTest/java/jp/rimtty/codematch/NavigationTest.kt` |


## Swift 単体テスト（71本）

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Preview 層が capture session を付け替え・detach できる（`CodeMatcherTests.swift:8`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::a completed provider binds once when viewport is unchanged` + `::pending provider future restarts with the latest QR to Code 128 format` + `::running binding rebinds when the preview viewport rotates`。AndroidではCameraX use-caseのunbind/rebindで同じ資源境界を固定する | D（CameraX境界） |
| 2 | Preview dismantle で session を外し復旧を無効化する（`CodeMatcherTests.swift:23`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::preview dismantle unbind invalidates a pending provider callback` + `::preview dismantle invalidates an in-flight ML Kit failure callback`。dismantle後のprovider/解析callbackを世代で破棄する | D |
| 3 | inactive 化で再利用 session を detach/rebind する（`CodeMatcherTests.swift:35`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::inactive stop then start rebinds the same host after teardown` + `::clearing the format stops then rebinds on the same attached host` | D |
| 4 | queued teardown 完了まで scanner を保持する（`CodeMatcherTests.swift:50`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion` は処理中frame完了前に論理sessionを終了しないことを検査する | D |
| 5 | metadata region を正規化座標へ clamp する（`CodeMatcherTests.swift:70`、`CameraPreviewTests`） | iOSの [`CameraScanner.swift#L204`](../../ios/CodeMatch/Services/CameraScanner.swift#L204) が正規化矩形をclampするのに対し、Androidは [`CameraModelsTest.kt#L60`](../../android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt#L60) `roi rejects coordinates outside the preview instead of clamping them` + [`#L72`](../../android/scanner/camera/src/test/kotlin/jp/rimtty/codematch/scanner/camera/CameraModelsTest.kt#L72) `roi accepts coordinates exactly on the normalized preview boundary` で範囲外を拒否する安全側policyを検査する。iOSのclamp動作そのものは移植していない | P（policy差） |
| 6 | screen capture 中でも multitasking camera 対応時は開始し、非対応時だけ block（`CodeMatcherTests.swift:85`、`CameraPreviewTests`） | iOSの [`CameraScanner.swift#L209`](../../ios/CodeMatch/Services/CameraScanner.swift#L209) / [`#L217`](../../ios/CodeMatch/Services/CameraScanner.swift#L217) と [`CodeMatcherTests.swift#L85`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L85) がscene captureとmultitasking cameraの組合せを固定する。一方、共通 [`PRODUCT_SPEC.md#L3`](../PRODUCT_SPEC.md#L3) にこの要件はなく、Androidの現行camera状態は [`CameraModels.kt#L11`](../../android/scanner/camera/src/main/kotlin/jp/rimtty/codematch/scanner/camera/CameraModels.kt#L11)、Manifestは [`AndroidManifest.xml#L5`](../../android/app/src/main/AndroidManifest.xml#L5) のCAMERA/任意cameraだけである | N/A（iOS固有の防御策。Android要件が追加される場合は別仕様） |
| 7 | Code 128 から品番を抽出する（`:125`） | `CodeMatcherTest.kt::partNumberFromBarcodeUsesTextBeforeFirstAt` | D |
| 8 | 標準 QR の固定位置と非標準入力の品番抽出（`:132`） | `CodeMatcherTest.kt::partNumberFromQrReadsTheStandardCardAndItemPositions` | D |
| 9 | 実データの QR/Code 128 が一致する（`:144`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 10 | shared fixture の全ケースを照合する（`:148`） | `CodeMatcherTest.kt::sharedMatchingFixturesHaveTheSameResultsAsSwift`（classpath fixture） | D |
| 11 | 別品番は不一致になる（`:185`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 12 | `@` 以降の管理コード差は一致へ影響しない（`:193`） | `CodeMatcherTest.kt::compareMatchesRealPairAndIgnoresManagementCode` | D |
| 13 | 非標準 QR は保守的 containment fallback を使う（`:201`） | `CodeMatcherTest.kt::compareUsesOnlyConservativeContainmentForNonStandardQr` | D |
| 14 | 空 payload は不一致になる（`:212`） | `CodeMatcherTest.kt::emptyOrUnparseableValuesMismatch` | D |
| 15 | 品番表示形式を整形する（`:217`） | `CodeMatcherTest.kt::formatPartNumberUsesTheFourTwoFourDisplayShape` | D |
| 16 | Kanban QR の全フィールドを解析する（`:222`） | `CodeMatcherTest.kt::kanbanRecordParsesAllFields` | D |
| 17 | 枝番空白の Kanban QR と数量を扱う（`:234`） | `CodeMatcherTest.kt::kanbanRecordHandlesBlankSuffixAndParsesQuantities` | D |
| 18 | 非標準/短い QR を拒否する（`:246`） | `CodeMatcherTest.kt::kanbanRecordRejectsNonStandardPayloadsAndChecksStrictScanLength` | D |
| 19 | Bluetooth 入力の QR/Code 128 逆順・形式を拒否する（`:251`） | `CodeMatcherTest.kt::kanbanRecordRejectsNonStandardPayloadsAndChecksStrictScanLength` + `::tagRecordValidationRejectsReverseOrderAndWrongShape` | D |
| 20 | Code 128 の品番と管理コードを分離して解析する（`:262`） | `CodeMatcherTest.kt::tagRecordParsingKeepsPartAndManagementCodeSeparate` | D |
| 21 | 保存値なしの言語は日本語へ fallback（`:293`） | `SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults`（`fromCode(null)`）+ `SettingsRepositoryTest.kt::defaultsAndUpdatesArePersistedAsOneSettingsFlow` | D（enum/DataStore 契約） |
| 22 | 不正な保存言語は日本語へ fallback（`:298`） | `SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults`（未知値） | D |
| 23 | 現在言語に応じて localization を切り替える（`:303`） | `ScanScreenTest.kt::languageOverrideRendersEnglishAndRecomposesInJapanese` + `SettingsScreenTest.kt::languageStateRedrawsSettingsTextWithoutWaitingForActivityRecreation` + `HistoryScreenTest.kt::englishBoxCountsUseSingularAndPluralAndRedrawInJapanese` | D |
| 24 | 英語の単数/複数形を自然に表示する（`:311`） | `HistoryExportTextTest.kt::boxCountUsesNaturalEnglishSingularAndPlural` + `HistoryPdfContentTest.kt::englishContentInflectsBoxUnitForOneAndMany` + `HistoryScreenTest.kt::englishBoxCountsUseSingularAndPluralAndRedrawInJapanese` | D |
| 25 | auto-advance の既定値 OFF/3秒、保存値 5秒、選択肢 1/3/5秒（`:328`） | `ScanModelsTest.kt::autoAdvanceDelaysMatchTheSwiftContract` + `SettingsModelsTest.kt::defaultsMatchProductContract` + `SettingsRepositoryTest.kt::defaultsAndUpdatesArePersistedAsOneSettingsFlow` | D |
| 26 | Inateck setup Code 128 の順序・文字列（`:364`） | `Code128BarcodeTest.kt::setupCodesKeepTheScannerOrderAndExactAsciiMessages` + `SetupBarcodeDecodeTest.kt::allSetupBarcodesDecodeToTheirExactCommands`（Android同梱ML Kitで3コードをexact decode） | D |
| 27 | symbology mode の active restriction 文言（`:371`） | `ScanScreenTest.kt::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady` はReady後のQR・Code 128 session restriction文言を日英resource経由で検査する。iOS固有のlegacy qrOnly/code128Only mode文言は対象外 | P（session mode） |
| 28 | 論理工程変更で ready session を再設定しない（`:393`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `FakeExternalScannerTest.kt::expectedFormatChangesAreLogicalAndDoNotReconfigureEveryStep` | D |
| 29 | scanner が報告した全 barcode type を command に含め QR/Code 128 だけ有効化（`:418`） | `BleSymbologyTest.kt::sessionModeDisablesEveryReportedTypeExceptQrAndCode128` + `::documentedFlagValueCodecRestrictsAndRestoresTheCompleteReportedInventory`。iOS area/name形式に加え、公式SDK-level flag形式でも2008/2022だけを有効化する | D |
| 30 | 元の barcode 設定を値変更なしで round-trip 復元（`:491`） | `BleSymbologyTest.kt::restoreUsesOriginalValuesAndDeviceReportedAreas` + `::documentedFlagValueCodecRestrictsAndRestoresTheCompleteReportedInventory` + `BleSymbologySnapshotSerializerTest.kt::completeInventoryRoundTripsWithoutDroppingMetadata`。公式形式はflag/valueだけを数値で戻し、areaとの相互変換を仮定しない | D |
| 31 | transport terminator は末尾だけ除去（`:535`） | `BlePayloadTest.kt::normalizerRemovesOnlyTrailingTransportTerminators` | D |
| 32 | pinned iOS SDK JSON envelope を scan payload へ unwrap（`:543`） | `BlePayloadTest.kt::decoderAcceptsDirectTextAndPinnedSdkEnvelope` | D |
| 33 | scanner-lib notification JSON を unwrap（`:552`） | `BlePayloadTest.kt::decoderAcceptsNotificationBytesAndRejectsNonScanNotifications` | D |
| 34 | direct text を維持し、非 scan notification/不正 JSON を reject（`:565`） | `BlePayloadTest.kt::malformedStructuralJsonIsRejectedWhileValidObjectTextRemainsCompatible` + decoder テスト | D |
| 35 | simulator scanner の discovery/connect と preferred reconnect（`:582`） | `FakeExternalScannerTest.kt::discoveryAndConnectionAreSynchronousAndObservable` + `::disconnectPreservesKnownDeviceForExplicitReconnect`、`BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation`、`BleExternalScannerTest.kt::selectableFacadeBindsTheDiscoveredDeviceBeforeConnectionAndConfiguration` + `::selectableFacadeRecreatesTheSessionForAPersistedKnownDevice`。検索後選択と別instanceへのidentity永続化は安全コア層で検査済みだが、実SDK cacheは未検査 | P |
| 36 | 最近の接続イベントだけ保持し scan payload を診断へ出さない（`:604`） | `FakeExternalScannerTest.kt::diagnosticsKeepOnlyConnectionConfigurationEventsAndNeverPayloads` + `BleConnectionCoordinatorTest.kt::scanPayloadIsForwardedButNeverWrittenToDiagnostics` | D |
| 37 | 再起動後 restricted scanner を安全 baseline へ復元（`:631`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `BleSymbologySnapshotStoreTest.kt::dataStoreRoundTripsAndClearsOnlyTheMatchingDevice` + `BleKnownDeviceStoreTest.kt::recreatedServiceReconnectsKnownDeviceAndRestoresSnapshotBeforeReady`。再生成後のfresh inventory→restore→Ready境界を検査済みだが、対象scanner実通信は未検査 | P |
| 38 | 旧 stuck build の Code128-only recovery を移行（`:664`） | iOSの [`BluetoothScannerService.swift#L1396`](../../ios/CodeMatch/Services/BluetoothScannerService.swift#L1396) と [`CodeMatcherTests.swift#L664`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L664) は、旧iOS UserDefaultsの制限状態を新しい全種snapshotへ移す互換処理を検査する。Androidは独立Gradle projectの [`README.md#L1`](../../android/README.md#L1) で、現行の復旧値は [`BleSymbologySnapshotStore.kt#L63`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L63) のversioned envelopeだけを受理し、未知versionは [`#L109`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L109) 以降で拒否する。旧iOS stateを受けるAndroid移行契約はない | N/A（Androidに旧iOSデータ移行元なし） |
| 39 | manual disconnect で unrestricted baseline へ戻る（`:701`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical`（endSession の restore/clear）+ `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` | D（安全コア層） |
| 40 | manual disconnect 後、検索結果がなくても既知端末を reconnect（`:720`） | `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` + `::discoveryCanBeStoppedOrEndedByConnection` + `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation` + `BleExternalScannerTest.kt::selectableFacadeRecreatesTheSessionForAPersistedKnownDevice`。永続IDからdiscoveryなしで正しいsettings sessionを生成して再接続開始できるが、実SDK cacheは未検査 | P |
| 41 | service 再起動後も manual disconnect 済み既知端末を保持（`:747`） | `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation` + `BleKnownDeviceStoreTest.kt::knownDeviceSurvivesDataStoreReopenAndWrongDeviceCannotClearIt`。version/profile一致時だけ再生成後に復元し、別deviceからのclearを拒否する | D（安全コア層） |
| 42 | 旧 diagnostics から last connected device を migration（`:764`） | iOSの [`BluetoothScannerService.swift#L612`](../../ios/CodeMatch/Services/BluetoothScannerService.swift#L612) と [`CodeMatcherTests.swift#L764`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L764) は、旧診断eventの文字列から既知端末identityを復元する互換処理を検査する。Androidの [`BleKnownDeviceStore.kt#L90`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStore.kt#L90) はprofile/device identityだけの新規versioned envelopeであり、DataStore再オープンは [`BleKnownDeviceStoreTest.kt#L34`](../../android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt#L34) で検査するが、旧diagnosticsを入力する契約はない | N/A（Androidに旧iOS diagnostics移行元なし） |
| 43 | 750ms 未満の重複 callback を抑止（`:804`） | `BlePayloadTest.kt::duplicateGateUsesStrictLessThanSevenHundredFiftyMillis` + `FakeExternalScannerTest.kt::duplicateCallbacksAreSuppressedByInjectedClock` | D |
| 44 | 一致後 countdown を表示し次の QR 工程へ自動遷移（`:851`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` + `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` | D |
| 45 | auto-advance OFF で countdown を取消し結果を残す（`:887`） | `ScanReducerTest.kt::turningAutoAdvanceOffCancelsAndKeepsMatchResult` | D |
| 46 | countdown を5秒へ変更すると表示を再開（`:904`） | `ScanReducerTest.kt::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 47 | countdown を1秒へ変更すると表示を再開（`:918`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` | D |
| 48 | 不一致では auto-advance countdown を開始しない（`:930`） | `ScanReducerTest.kt::mismatchRemainsVisibleAndNeverProducesRecordEffect` + `::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 49 | 同じ箱QRの成功済み照合は二重計上せず、auto-advanceも開始しない（`:940`） | `ScanReducerTest.kt::sameBoxQrCannotBeCountedTwiceInOneActiveSession` + `::restoredBoxQrIsDuplicateIgnoringCaseAndSurroundingWhitespace` + `AppFlowInstrumentationTest.kt::fakeScannerDifferentBoxesDuplicateRereadAndMismatchPreserveCountAndHistory` | D（Reducer + debug Fake/Room） |
| 50 | 箱QRが異なれば同一Code 128でも別箱として2件計上（`:959`） | `ScanReducerTest.kt::differentBoxQrsWithSameBarcodeAreBothRecorded` + `AppFlowInstrumentationTest.kt::fakeScannerDifferentBoxesDuplicateRereadAndMismatchPreserveCountAndHistory` | D（Reducer + debug Fake/Room） |
| 51 | Bluetooth で Code 128 を QR 前に読んでも進めない（`:988`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` + `::bluetoothRequiresBusinessPayloadFormats` | D（論理状態） |
| 52 | Code 128 待機中の QR callback を reject（`:1015`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` | D（論理状態） |
| 53 | QR reread で値を消し Bluetooth を QR 待機へ戻す（`:1034`） | `ScanReducerTest.kt::rereadQrReturnsToQrAndPreservesMatchedCount` + `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep`（工程保持） | D（状態） |
| 54 | Bluetooth の QR→Code 128 で即時一致し、同じ session mode を維持（`:1056`） | `ScanReducerTest.kt::cameraQrThenCode128ProducesMatchAndRecordEffect` + `BleScannerSessionCoordinatorTest.kt::connectionReadAndWriteMustCompleteBeforePayloadsAreForwarded` + `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` | D（debug Fake） |
| 55 | 接続済み Bluetooth を既定にするが手動 camera 選択を尊重（`:1093`） | `ScanSessionCoordinatorTest.kt::readyBluetoothIsSelectedAtSessionStart` + `::explicitCameraChoiceWinsOverLaterReadyCallbacks` | D |
| 56 | configuration message 後に QR 読取案内へ戻る（`:1109`） | `ScanScreenTest.kt::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady` はConfiguring文言が消え、Ready後にQR読取案内とsession restriction文言へ戻ることを検査する。`ScanSessionCoordinatorTest.kt::configurationFailureKeepsTypedIssueWhenBaselineRestoreMakesScannerReady` は設定失敗をbaseline復元後も型付きで保持する | D（stateless UI + coordinator） |
| 57 | background で baseline、foreground で現在工程の mode を再適用（`:1126`） | `ScanSessionCoordinatorTest.kt::backgroundStopsScannerAndForegroundResumesCurrentFormat` + `ScanReducerTest.kt::backgroundCancelsCountdownWithoutDiscardingResult`/`::foregroundResumesExpectedFormatWithoutChangingTheCurrentStep` | D（coordinator/reducer） |
| 58 | Bluetooth session 終了で unrestricted baseline へ復元（`:1151`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` | D（安全コア層） |
| 59 | camera stop 完了まで session end completion を待つ（`:1171`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion`。CameraX unbindと処理中frame完了後にだけ論理sessionを終了 | D |
| 60 | ViewModel deinit が camera stop を drain するまで待つ（`:1201`） | Androidではcamera hostがViewModel外で資源を所有し、composition disposal時にCameraScannerのterminal `close`/`unbind` completionを同じdrain境界へ保持する。`CameraScannerAsyncTest::close completion and a late unbind wait for an in-flight ML Kit task` が処理中frame・client解放順序を検査するが、Activity/process破棄を含む一体化lifetime試験ではない | P |
| 61 | Bluetooth disconnect 後も現在工程・QRを保持して camera へ fallback（`:1232`） | `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep` は工程・QR保持を検査し、`ScanScreenTest.kt::bluetoothFallbackPreservesCurrentStepAndOffersRetryAndSettings` はtyped fallback文言と再接続・設定actionを検査する | D（stateless UI + coordinator） |
| 62 | 正規化した match を記録して session を終了（`:1294`） | `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 63 | history を保存し再ロード（`:1314`） | `HistoryRepositoryTest.kt::activeSessionIsRestoredAfterDatabaseRecreation` + `::activeSessionAndCheckpointPhasesSurviveIsolatedDatabaseReopen` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 64 | 前 session と別の新 session を作成（`:1329`） | `HistoryRepositoryTest.kt::sessionsAreNewestFirstAndRenameBlankBecomesNull` | D |
| 65 | payload を保存し code ごとの count を返す（`:1345`） | `HistoryRepositoryTest.kt::payloadsArePersistedWithEachDuplicateEntry` + `::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` | D |
| 66 | active sessionの成功済み箱QRだけを正規化して重複判定（`:1366`） | `ScanReducerTest.kt::restoredBoxQrIsDuplicateIgnoringCaseAndSurroundingWhitespace` は履歴から注入したQR identityのみで大文字/小文字と前後空白を正規化して重複とし、Code 128値で別箱を拒否しないことを検査する | D（Reducer復元境界） |
| 67 | 同一品番でも異なるラベルは別箱として記録し first-seen 順に group（`:1388`） | `ScanReducerTest.kt::differentBoxQrsWithSameBarcodeAreBothRecorded` + `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `HistoryModelsTest.kt::groupedEntriesKeepFirstSeenPartOrderAndEveryDuplicate` | D |
| 68 | 開始時の名前と rename（空白・再保存含む）（`:1415`） | `HistoryRepositoryTest.kt::beginSessionTrimsNameAndReusesActiveSession` + `::sessionsAreNewestFirstAndRenameBlankBecomesNull` + `::nonBlankRenameSurvivesDatabaseReopen` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 69 | 空名を nil/空 display name として扱う（`:1435`） | `HistoryRepositoryTest.kt::blankNameIsStoredAsNull` + `HistoryModelsTest.kt::blankDisplayNameAndEndedStateAreRepresented` | D |
| 70 | session 削除を保存し cascade する（`:1444`） | `HistoryRepositoryTest.kt::deletingSessionCascadesEntries` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 71 | match なし終了は history へ保存せず破棄（`:1464`） | `HistoryRepositoryTest.kt::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` + `AppFlowInstrumentationTest.kt::emptySessionIsNotKeptAfterConfirmedEnd` | D |

## Swift UI テスト（5本）

UI テストは複数の層・起動引数・永続ストレージを一度に検査するため、Android の個別 Compose/reducer/adapter テストを同じ一本の E2E と見なさない。

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Fake Bluetooth 接続、camera 切替、逆順拒否、QR→Code 128、一致・件数（`CodeMatchUITests.swift:55`） | `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` は同じdebug Fake、app navigation、ViewModel、repositoryを通す | D（debug Fake） |
| 2 | 設定ガイド3段、拡大表示、検索・接続・切断・既知端末 reconnect（`CodeMatchUITests.swift:103`） | `AppFlowInstrumentationTest.kt::settingsGuideAndFakeScannerReconnectAreConnectedThroughTheApp` + `Code128BarcodeTest` | D（debug Fake） |
| 3 | 一致→duplicate→reset→不一致、件数、終了、history 掲載（`CodeMatchUITests.swift:148`） | `AppFlowInstrumentationTest.kt::fakeScannerMatchDuplicateRereadAndMismatchPreserveCountAndHistory` は同じdebug Fake、app navigation、ViewModel、Roomを通し、2箱だけの保存と不一致非保存を検査 | D（debug Fake） |
| 4 | 音選択、音量、日英切替、再起動後 language 維持（`CodeMatchUITests.swift:202`） | `SettingsScreenTest.kt::scannerActionsAndSoundLanguageCallbacksAreEmitted`、`FeedbackContractTest.kt`、`AppFlowInstrumentationTest.kt::languageSelectionPersistsAcrossActivityRecreation`、`SettingsRepositoryTest.kt::languageSurvivesDataStoreReopen` + `::allSettingsSurviveIsolatedDataStoreReopen`、`AppLanguageSynchronizerTest` 6件。Activity再生成、テスト専用DataStore再オープン、Android per-app languageとの双方向no-loop契約は検査済みだが、OS設定画面の実操作とforce-stop/relaunch UIは未検査 | P |
| 5 | auto-advance ON、countdown 表示、次の QR へ遷移（`CodeMatchUITests.swift:265`） | `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` は設定保存、実時間countdown、次QR工程を同じapp graphで検査 | D |

Swift UI 5本の直接対応とは別に、`NavigationTest`は履歴のsession→group→box選択がActivity再生成とHistory→Settings→Scan→History往復後も復元されること、およびcompact system backがbox→group→session→listを順に戻ることを実app graphで検査する。`HistoryPdfBridgeTest`と`HistoryPdfExporterInstrumentationTest`は、A4複数ページPDFの実render、専用cache、SAF byte保存、共有Intent/FileProvider契約を検査するが、実DocumentProvider/共有先アプリの操作を代替しない。

## 残る物理・手動・未対応の証拠

- AVFoundationのPreview層attach/detachとqueued shutdownはAndroidのCameraX境界へ対応済みだが、#5はiOSのclampとAndroidのrejectでpolicyが異なるためPのままにする。#6のscreen-capture policyはiOS固有であり、共通仕様にないことを根拠リンクで確認したN/Aである。
- Android の camera adapter について、実端末での QR→Code 128 実読取、focus 成否、連続箱、回転、background/foreground、権限の実結果は [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md) に記録する。Compose stage/ROI テストだけでは完了にならない。
- Android BLE はSDK非依存 safety coreとFake/UIに加え、公式Inateck Android SDK 2.0.0を非配付`scannerPoc`へ接続した。2026-09-04にPixel 7 / BCST-36で検索・接続、実機inventory由来のQR+Code 128制限とfresh readback、分割通知、QR→Code 128一致、背景時復元、QR待機中のapp強制終了後の既知端末自動再接続と追加のQR→Code 128一致を確認した。logical format補正、接続・探索世代取消、一時的なBluetooth/権限不可時の有限再試行、PoC最小権限とvendor-log除去は自動検査する。同一箱重複・不一致・連続箱、手動/予期しない切断、scanner再起動、Code 128待機/結果表示中のapp強制終了、timeout、Samsung、配付条件は未完了。
- #38のlegacy Code128-only recoveryと#42のdiagnosticsからの既知端末migrationは、旧iOS UserDefaults/diagnosticsをAndroidへ移すproduct-history-only処理なのでN/Aとする。Android側の新規snapshot/known-device envelope、service再生成後のidentity保持、fresh inventory、復元完了前Ready禁止は [`BleKnownDeviceRecoveryTest`](../../android/scanner/ble/src/test/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceRecoveryTest.kt) / [`BleKnownDeviceStoreTest`](../../android/scanner/ble/src/androidTest/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStoreTest.kt) で自動化したが、対象scanner実通信の証拠ではない。
- Swift UI 5本のうち、Fake接続、一致→duplicate→読み直し→不一致、設定ガイド、実時間auto-advanceは同じdebug Fake/DI/repositoryを通すapp instrumentationへ昇格した。0件破棄、履歴名称変更・詳細・削除、履歴選択のActivity再生成/画面往復/system back、英語1/2 plural、テスト専用ランダムファイルを使ったDataStore/Room再オープン（active session、全checkpoint phase、全設定値）、PDF render/Intent契約も自動化済み。これらはストレージと復元経路の証拠であり、OSのprocess kill/force-stop後にアプリを再起動するUI証拠ではない。実DocumentProvider/共有先、TalkBack/Switch Access、実カメラ、対象BLE、Samsung受け入れは引き続き別ゲートである。

## この監査で追加した純 JVM 証拠

`android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults` に `AppLanguage.fromCode(null) == JAPANESE` を追加した。これは Swift の「保存値なし」fallback を Android の framework-free model 層で直接固定するもので、production code や BLE/camera/navigation は変更していない。#6/#38/#42は根拠付きN/Aであり、AVCapture lifetimeや旧iOS migrationを推測するテストは追加していない。

今回のBLE UI監査では、`ScanSessionCoordinator`が設定失敗をbaseline復元で上書きする前に`ScannerIssue`を渡す境界を追加し、明示的な非同期再接続後のReadyだけがBluetoothへ再昇格できるようにした。`ScanScreenTest::bluetoothConfigurationStatusReturnsToSessionRestrictionAfterReady`と`SettingsScreenTest::configurationStatusIsLocalizedAndFailureReasonStaysHidden`は日英の設定中・session restriction・失敗文言を検査し、adapter由来のraw reasonを表示しない。いずれも`ExternalScanner`の安全境界を使う状態/UI証拠であり、release camera-onlyのNearby permission、GATT/UUID/protocol、対象scanner実通信は追加していない。

今回のカメラ監査では、実カメラを要求しない境界として、既存バインディング後のPreviewView回転時rebind、権限待ち、背面カメラなし、停止後のfocus結果破棄、focus開始例外の型付き通知を `CameraScannerAsyncTest` に追加した。`CameraStageTest::pointerFocusTracksRunningStateAcrossStartAndStop` は開始/停止を跨いで古いCompose pointer callbackがfocusを発火しないことを検査し、`CameraPermissionStateTest::canceledPermissionResultCannotAffectTheNextCameraBinding` はキャンセル済みActivityResultを次の要求へ誤帰属しないtombstone境界を固定する。ROI中間Bitmapの例外経路も解放する。iOS固有の画面録画ポリシー、実カメラdecode、process kill/relaunchは追加していない。

## 検証

- Swift source の `func test` 数: unit 71、UI 5。fixture は JSON として schemaVersion 1、5 case、ID 重複なし。
- Android の focused Gradle test は Android Studio の JDK と SDK を明示して実行し、次の2系統がともに `BUILD SUCCESSFUL` になった。`./gradlew :core:model:testDebugUnitTest :core:matching:testDebugUnitTest :feature:scan:testDebugUnitTest :scanner:ble:testDebugUnitTest :scanner:fake:testDebugUnitTest`、および `./gradlew :core:export:testDebugUnitTest :feature:history:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest`。
- `:scanner:camera:testDebugUnitTest` は非同期境界20テストを含め `BUILD SUCCESSFUL`、`:scanner:camera:lintDebug` も `BUILD SUCCESSFUL` になった。`BundledMlKitImageDecodeTest` 3件は共有QR/Code 128画像の実decodeと誤形式拒否に成功したが、実機 camera readを意味しない。
- 2026-09-03の追加hardening後、Android JVM testは全249件が成功した。Pixel 7/API 36では通常のdebugアプリ保存領域を消去せず、`core:data` 21件、`feature:scan` 15件、`scanner:camera` 3件の計39件を実行し、失敗・skip 0だった。以前の全module instrumentation 80件、BLE変更時のfocused 36件、Android 17/API 37.1・16KB emulator 63件は別時点の記録であり、この差分全体はPRのAPI 31/36 CIで再確認する。
- Android実装の証拠にiOS Simulatorは使用しない。iOS XCTestは、同一リポジトリの既存iOS版を壊していないことを確認するPR CIの回帰ゲートとしてのみ扱う。
- 2026-09-04の追加差分は、`feature:scan`/`scanner:ble`/`app`の対象JVM test、Android test compile、lintが成功した。Android 17/API 37.1・16KB emulatorで`ScanScreenTest` 11件と実ContentProvider経由のPDF write/read 1件が成功し、検証後にemulatorを終了した。対象BCST-36はオートスリープ中のため、BLE実通信の追加合否には使用していない。
- 実行可能だった静的確認は source/test 数、fixture 構造、`git diff --check`。実機 camera/BLE の結果はこの表に含めていない。
