# Android版の現在地

このページは、2026-09-02の監査開始時点で `codex/android-m4-ble` に存在した差分ベース `bc30b21` と、同監査で追加した証跡を基準にした状態記録です。CIや実機の結果は、このページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

## 実装と証跡の境界

| 領域 | このcheckoutで確認できるもの | まだ完了扱いにしないもの |
|---|---|---|
| Domain / matching | 純Kotlin matcher/parser、shared fixture、JVM test | Swift/Kotlinの全ケースを同一CI実行で確認した記録 |
| UI / navigation | Composeの照合・履歴・設定、3 destination、debug Fake境界 | TalkBack、font scale 2.0、複数OEMの人手確認 |
| History / settings / PDF | Room/DataStore、日英リソース、PDF export、保存/共有の実装 | 保存先・共有先を含む実端末の業務受け入れ |
| Camera | CameraX/ML Kit adapter、ROI、権限・lifecycle・focusのコードとinstrumentation test | Pixel/SamsungでQR→Code 128実読取、連続箱、focus結果の完了記録 |
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

実機手順と証跡テンプレートは [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)、プライバシー境界は [`PRIVACY.md`](PRIVACY.md) に分けています。
