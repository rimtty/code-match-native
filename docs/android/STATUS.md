# Android版の現在地

このページは、2026-09-03のBLE事前実装を含む統合監査と、その時点で再実行した証跡を基準にした状態記録です。CIや実機の結果は、このページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

## 実装と証跡の境界

| 領域 | このcheckoutで確認できるもの | まだ完了扱いにしないもの |
|---|---|---|
| Domain / matching | 純Kotlin matcher/parser、shared fixture、JVM test、Swift unit 68本/UI 5本との意図対応表 | Swift/Kotlinの全ケースを同一CI実行で確認した記録 |
| UI / navigation | Composeの照合・履歴・設定、3 destination、system/predictive back境界、保存可能なdestination/履歴選択、履歴のActivity再生成・destination往復・compact back stack自動test、320dp/840dp・font scale 1.3/2.0の主要操作到達test、debug Fake境界 | predictive gestureの視覚遷移、OS process kill/relaunchの実操作、TalkBack、Switch Access、複数OEMの人手確認 |
| History / settings / PDF | Room/DataStore、日英リソース、0件破棄・名称変更・詳細・削除のapp E2E、A4複数ページPDFの実render、SAF保存/専用FileProvider共有の契約test、保存/共有失敗の一般化メッセージと再試行 | 実際の保存先・viewer・共有先アプリを含む実端末の業務受け入れ |
| Camera | CameraX/ML Kit adapter、ROI、権限・lifecycle・focus、非同期provider/format切替/古いcallback破棄、処理中frameをdrainしてからsession終了・terminal closeする自動test | Pixel/SamsungでQR→Code 128実読取、連続箱、focus結果の完了記録 |
| Privacy / release | Manifest、backup規則、FileProvider、source/APK/AAB checker、すべてのAndroid Gradle CI jobでのWrapper validation | 通信観測、ストア提出回答、署名済み配布物の運用承認 |
| BLE | SDK非依存の安全コア、端末選択facade、公式Inateck Android SDK 2.0.0を使う`scannerPoc` adapter、area/name/value全件read/write/readback実装、分割FF01通知router、Nearby最小権限、R8 vendor-log除去、snapshot/queue/lifecycle/既知端末復旧test | 対象scannerでの検索・接続・scan、SDK callbackとexact readbackの実通信、実機の全symbology完全復元、Pixel/Samsung受け入れ、SDK再配付条件を確認したproduction/release採用 |

現在のrelease構成は `UnavailableExternalScanner` によるカメラ入力のみです。公式Inateck SDK adapterは、arm64実機向け・非配付・minifiedの`scannerPoc`だけに接続します。SDK binaryは固定commitからchecksum検証付きでローカル取得し、Gitやreleaseへ同梱しません（詳細は [`BLE_SDK_EVALUATION.md`](BLE_SDK_EVALUATION.md)）。

## パリティ分類の補足

[`TEST_PARITY.md`](TEST_PARITY.md) の `N/A` は、Androidに未実装のまま残した行ではなく、現行Androidの共通仕様に含まれないことをソースと仕様のリンクで確認した行です。現在の対象は、iOS固有の画面収録防御（#6）、旧iOS UserDefaultsのCode128-only recovery移行（#38）、旧iOS diagnosticsからの既知端末migration（#42）です。Android版は独立Gradle projectとして導入され、現行のBLE復旧はversion/profile付きの新規snapshot・known-device envelopeを使うため、これら旧iOS状態を読む入口はありません（[`android/README.md`](../../android/README.md#L1)、[`BleSymbologySnapshotStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L63)、[`BleKnownDeviceStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStore.kt#L90)）。

これは実機・手動ゲートの免除ではありません。ROIのclamp（#5）はiOSとAndroidでpolicyが異なるため`P`のまま、active restriction（#27）は現行session mode文言だけを検査した`P`、camera lifetime（#58）はprocess破棄を含まない`P`です。OS設定画面・force-stop/relaunch（UI #4）、対象scannerのBLE通信・完全復元（#35、#37、#40）も未完了境界として残します。

## 再現可能なチェック

Androidプロジェクトで次を実行できます。

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
bash scripts/run-connected-tests.sh
./gradlew assembleRelease bundleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/codematch-release-dependencies.txt
bash scripts/test-release-hardening.sh
bash scripts/verify-release-hardening.sh \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --dependency-report /tmp/codematch-release-dependencies.txt
```

JDK/SDKがない環境ではGradle結果を推測せず、実行不能として記録します。エミュレーター・CIのinstrumentation成功は、カメラの実読取やBLE通信の実機成功を意味しません。

## 2026-09-02〜03 統合検証記録

- 2026-09-03の公式Inateck SDK PoC統合では、SDK/FastBle/JNA/nativeを`scannerPoc`だけへ隔離し、設定応答とscan通知を共有するFF01をcommand-awareな単一routerへ置換した。分割通知、最大長、終端、250ms idle flush、oversize quarantine、接続・探索世代、pending接続取消、未知JSON破棄、area/name/value codecは自動testで固定した。SDK callback経路と全設定exact readbackは実装済みだが、実通信の証拠は実機ゲートに残す。PoC APKの最小Nearby権限、単一exported launcher、arm64 ABI、vendorログ文字列除去はartifact checkerで確認する。通常releaseにはSDK/native/Nearby権限を入れず、対象scanner実機結果は未記録である。
- 2026-09-03のBLE設定表示・再接続とcamera terminal close統合後、全moduleのJVM test 252件、lint、debug/release APK、release AABが成功した。release source regressionとAPK/AAB/dependency hardeningも成功し、release構成にFake、Nearby権限、analytics/crash SDKがないことを再確認した。USB接続Pixel 7（Android 16 / API 36）では、通常のdebugアプリ保存領域を消去しないmodule testとして`feature:scan` 18件、`feature:settings` 15件、`scanner:camera` 3件の計36件を実行し、失敗・skip 0だった。これは設定中→Ready→QR案内、raw reason非表示、工程を保持したcamera fallback、明示的な非同期再接続、terminal closeの自動証拠であり、対象scanner通信や実カメラ撮影の証拠ではない。
- 2026-09-03の追加hardening後、全moduleのJVM test 249件、lint、debug/release APK、release AABが成功した。release dependency/APK/AAB hardeningはFake、Nearby権限、analytics/crash SDKがない状態で成功し、すべてのAndroid CI jobでGradle起動前にWrapper validationを実行する構成にした。USB接続Pixel 7（Android 16 / API 36）では、通常のdebugアプリ保存領域を消去せず、`core:data` 21件、`feature:scan` 15件、`scanner:camera` 3件の計39件を実行し、失敗・skip 0だった。
- `core:data`の追加testは、ランダムなテスト専用Room DBを各checkpoint段階で閉じて再オープンし、active session、WAITING QR、WAITING Code 128、RESULT、受理済み値、入力元、明示camera選択、箱数を復元する。全設定値と言語もテスト専用DataStore再オープン後に復元する。これらはストレージ復元の証拠であり、OS force-stop/process kill後のアプリ再起動を意味しない。
- カメラの追加testは、PreviewView回転・サイズ変更時の再bind、権限callbackの取り違え防止、停止後の遅延focus結果破棄、停止中のタップ無効化、ROI中間Bitmapの例外時解放を検査する。これはCameraX/Composeの非同期境界の証拠であり、実カメラでの読取・focus成功の証拠ではない。
- 2026-09-03のBLE事前実装後、全moduleのJVM test 242件、lint、debug/release APK、release AABが成功した。`scanner:ble`は68件で、汎用GATT transport 7件、`ExternalScanner` facade/lifecycle 11件を含む。release dependency/APK/AAB hardeningはFake、Nearby権限、analytics/crash SDKがない状態で成功した。
- USB接続Pixel 7（Android 16 / API 36）では、今回変更した`feature:scan` 14件、`feature:settings` 14件、`scanner:ble` 8件の計36件を再実行し、失敗・skip 0だった。これはframework seam、Compose UI、DataStore復旧の証拠であり、対象scannerとのGATT通信証拠ではない。

- Android Studio付属JDK 25とAndroid SDKを明示し、`testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --no-parallel` を実行した。1,117 tasks、JVM test 211件が成功し、cameraの非同期test 13件、appの言語同期test 6件、PDF保存/共有bridge test 12件を含む全JVM test、lint、debug/release APK、release AABが完了した。
- release dependency reportと生成済みAPK/AABに対してhardening検査を実行し、Fake/analytics/crash依存、不要な権限、FileProvider、backup/D2D resource、全module manifestの検査が成功した。
- USB接続したPixel 7（Android 16 / API 36）1台を明示選択し、自動instrumentationを80件実行した。app 15、core:data 19、core:export 2、feature:history 8、feature:scan 12、feature:settings 13、scanner:camera 3、scanner:ble 8がすべて成功した。app testはdebug Fakeを同じDI graphから操作し、接続・入力切替・逆順拒否・一致、duplicate、QR読み直し、不一致非保存、0件破棄、履歴詳細・名称変更・削除、設定ガイドと再接続、言語のActivity再生成後保持、実時間auto-advanceを検査する。さらに履歴のbox詳細選択がActivity再生成とHistory→Settings→Scan→Historyの往復後も保持され、compact system backがbox→group→session→listの順に戻り、PDF失敗Snackbarの再試行が操作できることを確認した。core:dataは言語・履歴名・scan checkpointをDataStore/Room再オープン後にも確認し、v1→v2 migrationで既存session/entryを保持する。
- `core:export`の2件は、長い履歴から生成したPDFが複数ページになり、`PdfRenderer`で全ページに描画内容があること、共有用ファイルが専用`cache/codematch-pdf/`配下だけへ書かれることを検査した。appのJVM testは`CreateDocument(application/pdf)`、完全一致byte保存、失敗伝播、`ACTION_SEND`/MIME/ClipData/read grant/FileProvider URIを検査する。実際のDocumentProviderや共有先アプリでの受け入れはまだ手動ゲートである。
- `scanner:camera`の3件は複製していない共有QR/Code 128画像を同梱ML Kitへメモリ入力し、各形式のdecodeと誤形式拒否を検査した。画像・frame・payloadの保存やlog出力は行わない。この証拠は実カメラ撮影やCameraX preview frameを意味しない。
- `feature:settings`の追加1件は、生成した3つの設定用Code 128を同梱ML Kitへメモリ入力し、`/*EnterSet*/`、`/*BLE_GATT*/`、`/*ExitSave*/`へexact decodeできることを確認した。対象BCST-47が実際に読み取って設定を変更することは、別のBLE実機ゲートで確認する。
- `feature:scan` / `feature:history` / `feature:settings`は320dpのfont scale 1.3/2.0、Historyは840dp expandedでも主要操作の表示、スクロール到達、48dpタッチ領域を検査した。配色は実際に使うsemantic foreground/backgroundの組を4.5:1以上で固定した。TalkBack、Switch Access、OEM固有描画は引き続き手動ゲートである。
- アプリ内言語とAndroid per-app languageは共通synchronizerで双方向に揃え、同値時は再設定しない。OS設定画面からの実変更はまだ手動ゲートである。
- Apple SiliconのAndroid 17/API 37.1・16KB page-size Pixel 6 emulatorでも、同じ自動instrumentation 63件がすべて成功した。英語端末設定で見つかったHistoryテストのlocale依存を修正済みで、検証後はemulatorだけを正常停止した。
- Swift unit 68本/UI 5本とAndroid証拠の全対応・未対応境界は[`TEST_PARITY.md`](TEST_PARITY.md)に記録した。

この節のPixel結果は自動testの証拠であり、QR/Code 128の実撮影、tap focus、TalkBack、対象BLE scanner通信や設定復元を完了扱いにしない。

実機手順と証跡テンプレートは [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)、プライバシー境界は [`PRIVACY.md`](PRIVACY.md) に分けています。
