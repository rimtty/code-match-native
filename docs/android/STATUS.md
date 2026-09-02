# Android版の現在地

このページは、2026-09-02の `codex/android-m4-ble` 統合監査と、その時点で再実行した証跡を基準にした状態記録です。CIや実機の結果は、このページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

## 実装と証跡の境界

| 領域 | このcheckoutで確認できるもの | まだ完了扱いにしないもの |
|---|---|---|
| Domain / matching | 純Kotlin matcher/parser、shared fixture、JVM test、Swift unit 68本/UI 5本との意図対応表 | Swift/Kotlinの全ケースを同一CI実行で確認した記録 |
| UI / navigation | Composeの照合・履歴・設定、3 destination、system/predictive back境界、保存可能なdestination/履歴選択、履歴のActivity再生成・destination往復・compact back stack自動test、320dp/840dp・font scale 1.3/2.0の主要操作到達test、debug Fake境界 | predictive gestureの視覚遷移、OS process kill/relaunchの実操作、TalkBack、Switch Access、複数OEMの人手確認 |
| History / settings / PDF | Room/DataStore、日英リソース、0件破棄・名称変更・詳細・削除のapp E2E、A4複数ページPDFの実render、SAF保存/専用FileProvider共有の契約test、保存/共有失敗の一般化メッセージと再試行 | 実際の保存先・viewer・共有先アプリを含む実端末の業務受け入れ |
| Camera | CameraX/ML Kit adapter、ROI、権限・lifecycle・focus、非同期provider/format切替/古いcallback破棄、処理中frameをdrainしてからsession終了する自動test | Pixel/SamsungでQR→Code 128実読取、連続箱、focus結果の完了記録 |
| Privacy / release | Manifest、backup規則、FileProvider、source/APK/AAB checker | 通信観測、ストア提出回答、署名済み配布物の運用承認 |
| BLE | SDK/UUID非依存の安全コア、Fake/Unavailable境界、snapshot/queue JVM test、backup除外DataStoreでのsnapshot/既知端末identity再オープンと復元前Ready禁止test | Android adapter、権限、対象scanner通信、実機の全symbology復元、Pixel/Samsung受け入れ |

ローカル `origin/master` には PR #14 を含むM2 merge commit（`a7573e8`）が見える一方、現在のM3/M4開発ブランチの作業結果とは分けて扱います。現在のrelease構成は `UnavailableExternalScanner` によるカメラ入力のみです。候補Inateck SDKはライセンス、ABI/target SDK、権限、rawログ、scan callbackの評価が未解決で採用保留です（詳細は [`BLE_SDK_EVALUATION.md`](BLE_SDK_EVALUATION.md)）。

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

## 2026-09-02 統合検証記録

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
