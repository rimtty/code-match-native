# Android版の現在地

このページは、2026-09-02の `codex/android-m4-ble` 統合監査と、その時点で再実行した証跡を基準にした状態記録です。CIや実機の結果は、このページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

## 実装と証跡の境界

| 領域 | このcheckoutで確認できるもの | まだ完了扱いにしないもの |
|---|---|---|
| Domain / matching | 純Kotlin matcher/parser、shared fixture、JVM test、Swift unit 68本/UI 5本との意図対応表 | Swift/Kotlinの全ケースを同一CI実行で確認した記録 |
| UI / navigation | Composeの照合・履歴・設定、3 destination、system/predictive back境界、保存可能なdestination/履歴選択、debug Fake境界 | predictive gestureの視覚遷移、process kill後の実履歴復元、TalkBack、font scale 2.0、複数OEMの人手確認 |
| History / settings / PDF | Room/DataStore、日英リソース、PDF export、保存/共有の実装 | 保存先・共有先を含む実端末の業務受け入れ |
| Camera | CameraX/ML Kit adapter、ROI、権限・lifecycle・focus、非同期provider/format切替/古いcallback破棄の自動test | Pixel/SamsungでQR→Code 128実読取、連続箱、focus結果の完了記録 |
| Privacy / release | Manifest、backup規則、FileProvider、source/APK/AAB checker | 通信観測、ストア提出回答、署名済み配布物の運用承認 |
| BLE | SDK/UUID非依存の安全コア、Fake/Unavailable境界、snapshot/queue JVM test | Android adapter、権限、対象scanner通信、全symbology復元、Pixel/Samsung受け入れ |

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

- Android Studio付属JDK 25とAndroid SDKを明示し、`testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --no-parallel` を実行した。1,110 tasks、JVM test 162件が成功し、cameraの非同期test 7件を含む全JVM test、lint、debug/release APK、release AABが完了した。
- release dependency reportと生成済みAPK/AABに対してhardening検査を実行し、Fake/analytics/crash依存、不要な権限、FileProvider、backup/D2D resource、全module manifestの検査が成功した。
- USB接続したPixel 7（Android 16 / API 36）1台を明示選択し、自動instrumentationを53件実行した。app 8、core:data 12、feature:history 5、feature:scan 10、feature:settings 11、scanner:camera 3、scanner:ble persistence 4がすべて成功した。appの追加4件はdebug Fakeを同じDI graphから操作し、接続・入力切替・逆順拒否・照合保存・履歴、設定ガイドと再接続、言語のActivity再生成後保持、実時間auto-advanceを検査する。
- `scanner:camera`の3件は複製していない共有QR/Code 128画像を同梱ML Kitへメモリ入力し、各形式のdecodeと誤形式拒否を検査した。画像・frame・payloadの保存やlog出力は行わない。この証拠は実カメラ撮影やCameraX preview frameを意味しない。
- Apple SiliconのAndroid 17/API 37.1・16KB page-size Pixel 6 emulatorでも、同じ自動instrumentation 53件がすべて成功した。英語端末設定で見つかったHistoryテストのlocale依存を修正し、検証後はemulatorだけを正常停止した。
- Swift unit 68本/UI 5本とAndroid証拠の全対応・未対応境界は[`TEST_PARITY.md`](TEST_PARITY.md)に記録した。

この節のPixel結果は自動testの証拠であり、QR/Code 128の実撮影、tap focus、TalkBack、対象BLE scanner通信や設定復元を完了扱いにしない。

実機手順と証跡テンプレートは [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)、プライバシー境界は [`PRIVACY.md`](PRIVACY.md) に分けています。
