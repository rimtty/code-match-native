# iOS↔Android テスト意図パリティ表

監査日: 2026-09-02。対象は [`CodeMatcherTests.swift`](../../ios/CodeMatchTests/CodeMatcherTests.swift) の XCTest 68本と [`CodeMatchUITests.swift`](../../ios/CodeMatchUITests/CodeMatchUITests.swift) の UI テスト5本。件数やテスト名の一致ではなく、各テストが保証する意図を Android 側の証拠へ対応付ける。Android の `D` は同じ契約を同等の層で検査、`P` は近接する状態・部品の検査（元テスト全体の代替ではない）、`—` は Android の証拠なしを表す。

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


## Swift 単体テスト（68本）

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Preview 層が capture session を付け替え・detach できる（`CodeMatcherTests.swift:8`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::a completed provider binds once when viewport is unchanged` + `::pending provider future restarts with the latest QR to Code 128 format` + viewport/rotation変更時の再bind。AndroidではCameraX use-caseのunbind/rebindで同じ資源境界を固定する | D（CameraX境界） |
| 2 | Preview dismantle で session を外し復旧を無効化する（`CodeMatcherTests.swift:23`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::preview dismantle unbind invalidates a pending provider callback` + `::preview dismantle invalidates an in-flight ML Kit failure callback`。dismantle後のprovider/解析callbackを世代で破棄する | D |
| 3 | inactive 化で再利用 session を detach/rebind する（`CodeMatcherTests.swift:35`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::inactive stop then start rebinds the same host after teardown` + `::clearing the format stops then rebinds on the same attached host` | D |
| 4 | queued teardown 完了まで scanner を保持する（`CodeMatcherTests.swift:50`、`CameraPreviewTests`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion` は処理中frame完了前に論理sessionを終了しないことを検査する | D |
| 5 | metadata region を正規化座標へ clamp する（`CodeMatcherTests.swift:70`、`CameraPreviewTests`） | `CameraModelsTest.kt::roi rejects coordinates outside the preview instead of clamping them` + `::roi accepts coordinates exactly on the normalized preview boundary`。Androidは範囲外をclampせず拒否する安全側policyで同じ正規化境界を保証 | D（Android reject policy） |
| 6 | screen capture 中でも multitasking camera 対応時は開始し、非対応時だけ block（`CodeMatcherTests.swift:85`、`CameraPreviewTests`） | Android の permission 状態テストはあるが、この screen-capture policy は未実装・未検査 | — |
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
| 27 | symbology mode の active restriction 文言（`:371`） | `BleSymbologyTest.kt::expectedFormatChangesRemainInOnePhysicalSessionMode` は mode 遷移のみで、Android の同じ status 文言は未検査 | P |
| 28 | 論理工程変更で ready session を再設定しない（`:393`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `FakeExternalScannerTest.kt::expectedFormatChangesAreLogicalAndDoNotReconfigureEveryStep` | D |
| 29 | scanner が報告した全 barcode type を command に含め QR/Code 128 だけ有効化（`:418`） | `BleSymbologyTest.kt::sessionModeDisablesEveryReportedTypeExceptQrAndCode128` | D |
| 30 | 元の barcode 設定を値変更なしで round-trip 復元（`:491`） | `BleSymbologyTest.kt::restoreUsesOriginalValuesAndDeviceReportedAreas` + `BleSymbologySnapshotSerializerTest.kt::completeInventoryRoundTripsWithoutDroppingMetadata` | D |
| 31 | transport terminator は末尾だけ除去（`:535`） | `BlePayloadTest.kt::normalizerRemovesOnlyTrailingTransportTerminators` | D |
| 32 | pinned iOS SDK JSON envelope を scan payload へ unwrap（`:543`） | `BlePayloadTest.kt::decoderAcceptsDirectTextAndPinnedSdkEnvelope` | D |
| 33 | scanner-lib notification JSON を unwrap（`:552`） | `BlePayloadTest.kt::decoderAcceptsNotificationBytesAndRejectsNonScanNotifications` | D |
| 34 | direct text を維持し、非 scan notification/不正 JSON を reject（`:565`） | `BlePayloadTest.kt::malformedStructuralJsonIsRejectedWhileValidObjectTextRemainsCompatible` + decoder テスト | D |
| 35 | simulator scanner の discovery/connect と preferred reconnect（`:582`） | `FakeExternalScannerTest.kt::discoveryAndConnectionAreSynchronousAndObservable` + `::disconnectPreservesKnownDeviceForExplicitReconnect`、`BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation`。別instanceへのidentity永続化は安全コア層で検査済みだが、実SDK cacheは未検査 | P |
| 36 | 最近の接続イベントだけ保持し scan payload を診断へ出さない（`:604`） | `FakeExternalScannerTest.kt::diagnosticsKeepOnlyConnectionConfigurationEventsAndNeverPayloads` + `BleConnectionCoordinatorTest.kt::scanPayloadIsForwardedButNeverWrittenToDiagnostics` | D |
| 37 | 再起動後 restricted scanner を安全 baseline へ復元（`:631`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` + `BleSymbologySnapshotStoreTest.kt::dataStoreRoundTripsAndClearsOnlyTheMatchingDevice` + `BleKnownDeviceStoreTest.kt::recreatedServiceReconnectsKnownDeviceAndRestoresSnapshotBeforeReady`。再生成後のfresh inventory→restore→Ready境界を検査済みだが、対象scanner実通信は未検査 | P |
| 38 | 旧 stuck build の Code128-only recovery を移行（`:664`） | Android に同じ legacy recovery mode/migration の証拠なし | — |
| 39 | manual disconnect で unrestricted baseline へ戻る（`:701`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical`（endSession の restore/clear）+ `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` | D（安全コア層） |
| 40 | manual disconnect 後、検索結果がなくても既知端末を reconnect（`:720`） | `FakeExternalScannerTest.kt::disconnectPreservesKnownDeviceForExplicitReconnect` + `::discoveryCanBeStoppedOrEndedByConnection` + `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation`。永続IDからdiscoveryなしで再接続開始できるが、実SDK cacheは未検査 | P |
| 41 | service 再起動後も manual disconnect 済み既知端末を保持（`:747`） | `BleKnownDeviceRecoveryTest.kt::coordinatorReusesPersistedKnownDeviceAfterServiceRecreation` + `BleKnownDeviceStoreTest.kt::knownDeviceSurvivesDataStoreReopenAndWrongDeviceCannotClearIt`。version/profile一致時だけ再生成後に復元し、別deviceからのclearを拒否する | D（安全コア層） |
| 42 | 旧 diagnostics から last connected device を migration（`:764`） | Android に同じ diagnostics migration の証拠なし | — |
| 43 | 750ms 未満の重複 callback を抑止（`:804`） | `BlePayloadTest.kt::duplicateGateUsesStrictLessThanSevenHundredFiftyMillis` + `FakeExternalScannerTest.kt::duplicateCallbacksAreSuppressedByInjectedClock` | D |
| 44 | 一致後 countdown を表示し次の QR 工程へ自動遷移（`:851`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` + `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` | D |
| 45 | auto-advance OFF で countdown を取消し結果を残す（`:887`） | `ScanReducerTest.kt::turningAutoAdvanceOffCancelsAndKeepsMatchResult` | D |
| 46 | countdown を5秒へ変更すると表示を再開（`:904`） | `ScanReducerTest.kt::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 47 | countdown を1秒へ変更すると表示を再開（`:918`） | `ScanReducerTest.kt::autoAdvanceSupportsOneThreeAndFiveSecondsWithVirtualTicks` | D |
| 48 | 不一致では auto-advance countdown を開始しない（`:930`） | `ScanReducerTest.kt::mismatchRemainsVisibleAndNeverProducesRecordEffect` + `::changingDelayRestartsOnlyAnActiveMatchCountdown` | D |
| 49 | Bluetooth で Code 128 を QR 前に読んでも進めない（`:940`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` + `::bluetoothRequiresBusinessPayloadFormats` | D（論理状態） |
| 50 | Code 128 待機中の QR callback を reject（`:967`） | `ScanReducerTest.kt::reverseOrderAndInvalidPayloadAreRejectedWithoutChangingState` | D（論理状態） |
| 51 | QR reread で値を消し Bluetooth を QR 待機へ戻す（`:986`） | `ScanReducerTest.kt::rereadQrReturnsToQrAndPreservesMatchedCount` + `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep`（工程保持） | D（状態） |
| 52 | Bluetooth の QR→Code 128 で即時一致し、同じ session mode を維持（`:1008`） | `ScanReducerTest.kt::cameraQrThenCode128ProducesMatchAndRecordEffect` + `BleScannerSessionCoordinatorTest.kt::connectionReadAndWriteMustCompleteBeforePayloadsAreForwarded` + `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` | D（debug Fake） |
| 53 | 接続済み Bluetooth を既定にするが手動 camera 選択を尊重（`:1045`） | `ScanSessionCoordinatorTest.kt::readyBluetoothIsSelectedAtSessionStart` + `::explicitCameraChoiceWinsOverLaterReadyCallbacks` | D |
| 54 | configuration message 後に QR 読取案内へ戻る（`:1061`） | `BleScannerSessionCoordinatorTest.kt::connectionReadAndWriteMustCompleteBeforePayloadsAreForwarded` は Configuring→Ready 状態のみ。Android message 文言は未検査 | P |
| 55 | background で baseline、foreground で現在工程の mode を再適用（`:1078`） | `ScanSessionCoordinatorTest.kt::backgroundStopsScannerAndForegroundResumesCurrentFormat` + `ScanReducerTest.kt::backgroundCancelsCountdownWithoutDiscardingResult`/`::foregroundResumesExpectedFormatWithoutChangingTheCurrentStep` | D（coordinator/reducer） |
| 56 | Bluetooth session 終了で unrestricted baseline へ復元（`:1103`） | `BleSymbologySessionTest.kt::connectedSessionRequiresFreshInventoryAndKeepsLogicalStepChangesPhysical` | D（安全コア層） |
| 57 | camera stop 完了まで session end completion を待つ（`:1123`） | `CameraScannerAsyncTest.kt::unbind completion waits for an in-flight ML Kit task to drain` + `CameraStopBoundaryTest.kt::sessionEndWaitsForDelayedHostStopCompletion`。CameraX unbindと処理中frame完了後にだけ論理sessionを終了 | D |
| 58 | ViewModel deinit が camera stop を drain するまで待つ（`:1153`） | Androidではcamera hostがViewModel外で資源を所有し、composition disposal時にCameraScannerのunbind/drain後closeを行う。`CameraScannerAsyncTest`はdrainを検査するが、Activity/process破棄を含む一体化lifetime試験ではない | P |
| 59 | Bluetooth disconnect 後も現在工程・QRを保持して camera へ fallback（`:1184`） | `ScanSessionCoordinatorTest.kt::disconnectFallsBackToCameraWithoutDiscardingCurrentQrStep` | D（message 文言は未検査） |
| 60 | 正規化した match を記録して session を終了（`:1246`） | `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 61 | history を保存し再ロード（`:1266`） | `HistoryRepositoryTest.kt::activeSessionIsRestoredAfterDatabaseRecreation` + `::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` | D |
| 62 | 前 session と別の新 session を作成（`:1281`） | `HistoryRepositoryTest.kt::sessionsAreNewestFirstAndRenameBlankBecomesNull` | D |
| 63 | payload を保存し code ごとの count を返す（`:1297`） | `HistoryRepositoryTest.kt::payloadsArePersistedWithEachDuplicateEntry` + `::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` | D |
| 64 | duplicate match を記録し first-seen 順に箱へ group（`:1319`） | `HistoryRepositoryTest.kt::recordMatchTrimsCodeReturnsOneBasedBoxNumberAndPreservesDuplicates` + `HistoryModelsTest.kt::groupedEntriesKeepFirstSeenPartOrderAndEveryDuplicate` | D |
| 65 | 開始時の名前と rename（空白・再保存含む）（`:1346`） | `HistoryRepositoryTest.kt::beginSessionTrimsNameAndReusesActiveSession` + `::sessionsAreNewestFirstAndRenameBlankBecomesNull` + `::nonBlankRenameSurvivesDatabaseReopen` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 66 | 空名を nil/空 display name として扱う（`:1366`） | `HistoryRepositoryTest.kt::blankNameIsStoredAsNull` + `HistoryModelsTest.kt::blankDisplayNameAndEndedStateAreRepresented` | D |
| 67 | session 削除を保存し cascade する（`:1375`） | `HistoryRepositoryTest.kt::deletingSessionCascadesEntries` + `AppFlowInstrumentationTest.kt::completedSessionCanBeRenamedViewedInDetailsAndDeleted` | D |
| 68 | match なし終了は history へ保存せず破棄（`:1395`） | `HistoryRepositoryTest.kt::endingEmptySessionDeletesItAndEndingNonEmptySessionStoresEndedAt` + `AppFlowInstrumentationTest.kt::emptySessionIsNotKeptAfterConfirmedEnd` | D |

## Swift UI テスト（5本）

UI テストは複数の層・起動引数・永続ストレージを一度に検査するため、Android の個別 Compose/reducer/adapter テストを同じ一本の E2E と見なさない。

| # | Swift の意図（テスト） | Android の証拠 | 判定 |
|---:|---|---|:---:|
| 1 | Fake Bluetooth 接続、camera 切替、逆順拒否、QR→Code 128、一致・件数（`CodeMatchUITests.swift:55`） | `AppFlowInstrumentationTest.kt::fakeScannerConnectsCameraSwitchRejectsReverseOrderAndRecordsTwoMatches` は同じdebug Fake、app navigation、ViewModel、repositoryを通す | D（debug Fake） |
| 2 | 設定ガイド3段、拡大表示、検索・接続・切断・既知端末 reconnect（`CodeMatchUITests.swift:103`） | `AppFlowInstrumentationTest.kt::settingsGuideAndFakeScannerReconnectAreConnectedThroughTheApp` + `Code128BarcodeTest` | D（debug Fake） |
| 3 | 一致→duplicate→reset→不一致、件数、終了、history 掲載（`CodeMatchUITests.swift:148`） | `AppFlowInstrumentationTest.kt::fakeScannerMatchDuplicateRereadAndMismatchPreserveCountAndHistory` は同じdebug Fake、app navigation、ViewModel、Roomを通し、2箱だけの保存と不一致非保存を検査 | D（debug Fake） |
| 4 | 音選択、音量、日英切替、再起動後 language 維持（`CodeMatchUITests.swift:202`） | `SettingsScreenTest.kt::scannerActionsAndSoundLanguageCallbacksAreEmitted`、`FeedbackContractTest.kt`、`AppFlowInstrumentationTest.kt::languageSelectionPersistsAcrossActivityRecreation`、`SettingsRepositoryTest.kt::languageSurvivesDataStoreReopen`、`AppLanguageSynchronizerTest` 6件。Activity再生成、DataStore再オープン、Android per-app languageとの双方向no-loop契約は検査済みだが、OS設定画面の実操作とforce-stop/relaunch UIは未検査 | P |
| 5 | auto-advance ON、countdown 表示、次の QR へ遷移（`CodeMatchUITests.swift:265`） | `AppFlowInstrumentationTest.kt::enabledOneSecondAutoAdvanceMovesFromResultToNextQrInRealTime` は設定保存、実時間countdown、次QR工程を同じapp graphで検査 | D |

Swift UI 5本の直接対応とは別に、`NavigationTest`は履歴のsession→group→box選択がActivity再生成とHistory→Settings→Scan→History往復後も復元されること、およびcompact system backがbox→group→session→listを順に戻ることを実app graphで検査する。`HistoryPdfBridgeTest`と`HistoryPdfExporterInstrumentationTest`は、A4複数ページPDFの実render、専用cache、SAF byte保存、共有Intent/FileProvider契約を検査するが、実DocumentProvider/共有先アプリの操作を代替しない。

## 残る物理・手動・未対応の証拠

- AVFoundation の Preview 層 attach/detach、queued shutdown、metadata clamp、screen-capture policy は Android の別 API であり、上表の `—/P` を同等実装とは扱わない。
- Android の camera adapter について、実端末での QR→Code 128 実読取、focus 成否、連続箱、回転、background/foreground、権限の実結果は [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md) に記録する。Compose stage/ROI テストだけでは完了にならない。
- Android BLE は現 checkout では対象 scanner へ接続する production adapter がなく、Fake と SDK/UUID 非依存 safety core のみである。全 symbology inventory の実読取・QR/Code 128 固定・完全復元・timeout・権限・Pixel/Samsung 通信は未完了。
- iOS 固有の legacy Code128-only recoveryとdiagnosticsからの既知端末migrationにはAndroidの証拠がない。service再生成後の既知端末identity保持、fresh inventory、復元完了前Ready禁止は`BleKnownDeviceRecoveryTest` / `BleKnownDeviceStoreTest`で自動化したが、対象scanner実通信の証拠ではない。
- Swift UI 5本のうち、Fake接続、一致→duplicate→読み直し→不一致、設定ガイド、実時間auto-advanceは同じdebug Fake/DI/repositoryを通すapp instrumentationへ昇格した。0件破棄、履歴名称変更・詳細・削除、履歴選択のActivity再生成/画面往復/system back、英語1/2 plural、DataStore/Room再オープン、PDF render/Intent契約も自動化済み。OSのprocess kill/relaunch、実DocumentProvider/共有先、TalkBack/Switch Access、実カメラ、対象BLE、Samsung受け入れは引き続き別ゲートである。

## この監査で追加した純 JVM 証拠

`android/core/model/src/test/kotlin/jp/rimtty/codematch/core/model/SettingsModelsTest.kt::unknownPreferenceValuesUseSafeDefaults` に `AppLanguage.fromCode(null) == JAPANESE` を追加した。これは Swift の「保存値なし」fallback を Android の framework-free model 層で直接固定するもので、production code や BLE/camera/navigation は変更していない。上記のほかは Android に対応する純 JVM 契約が存在しないため、AVCapture lifetime や legacy migration を推測するテストは追加していない。

## 検証

- Swift source の `func test` 数: unit 68、UI 5。fixture は JSON として schemaVersion 1、5 case、ID 重複なし。
- Android の focused Gradle test は Android Studio の JDK と SDK を明示して実行し、次の2系統がともに `BUILD SUCCESSFUL` になった。`./gradlew :core:model:testDebugUnitTest :core:matching:testDebugUnitTest :feature:scan:testDebugUnitTest :scanner:ble:testDebugUnitTest :scanner:fake:testDebugUnitTest`、および `./gradlew :core:export:testDebugUnitTest :feature:history:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest`。
- `:scanner:camera:testDebugUnitTest` は非同期境界13テストを含め `BUILD SUCCESSFUL`、`:scanner:camera:lintDebug` も `BUILD SUCCESSFUL` になった。`BundledMlKitImageDecodeTest` 3件は共有QR/Code 128画像の実decodeと誤形式拒否に成功したが、実機 camera readを意味しない。
- Android JVM testは全211件、Pixel 7/API 36のinstrumentationは80件が成功した。以前のAndroid 17/API 37.1・16KB emulator記録は63件であり、今回追加した17件はまだ同emulatorで再実行していない。
- Android実装の証拠にiOS Simulatorは使用しない。iOS XCTestは、同一リポジトリの既存iOS版を壊していないことを確認するPR CIの回帰ゲートとしてのみ扱う。
- 実行可能だった静的確認は source/test 数、fixture 構造、`git diff --check`。実機 camera/BLE の結果はこの表に含めていない。
