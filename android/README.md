# Code Match Android

Android版Code Matchの独立Gradleプロジェクトです。iOS版と同じ照合契約を、Android標準の操作とJetpack Compose / Material 3で実装します。現在のreleaseはカメラ入力のみで、BLE安全コアは対象scanner実機とAndroid向けSDKの調査が終わるまで接続しません。

## 開発環境

- Android Gradle Plugin 9.3.2
- Gradle 9.5.0（Wrapperで固定）
- Android SDK 37（compile / target）
- Android 12（API 31）以上
- JDK 21（ソース／バイトコードの互換性はJava 17）

AGP 9のbuilt-in Kotlinを使うため、Androidモジュールへ`kotlin-android`プラグインは追加しません。Composeを使うモジュールだけCompose Compiler Gradle pluginを適用します。

## モジュール

```text
app/                  # Hilt統合、Material 3 navigation、Android system bridge
core/model/           # framework-free domain model（Android library境界）
core/matching/        # parser / matcher（fixture parity）
core/designsystem/    # Code MatchのMaterial 3 tokensとtheme
core/data/            # Room履歴とPreferences DataStore設定
core/export/          # 日英PDF生成、保存・共有用export
feature/scan/         # 照合状態機械とstateless Compose UI
feature/history/      # 履歴一覧・詳細のadaptive Compose UI
feature/settings/     # scanner、auto-advance、音、言語の設定UI
scanner/api/          # カメラ/BLEから独立したscanner契約
scanner/camera/       # CameraX + bundled ML Kit（QR / Code 128）
scanner/fake/         # 開発用Fakeの隔離先（debug専用）
scanner/ble/          # SDK/UUID非依存のBLE安全コア（release未接続）
```

`core/model`と`core/matching`の本体ロジックはAndroid APIに依存しません。共通fixtureは`../shared/test-fixtures`をテストリソースとしてクラスパスへ追加し、テストからは`ClassLoader.getResourceAsStream`で読み込みます。アプリアイコンは通常・round・adaptive・monochromeを持ち、Android 13以降のper-app languageにも日本語と英語を公開します。

## ビルドとテスト

```bash
./gradlew assembleDebug
./gradlew lintDebug testDebugUnitTest
bash scripts/run-connected-tests.sh
./gradlew assembleRelease bundleRelease
mkdir -p tmp
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > tmp/release-dependencies.txt
bash scripts/test-release-hardening.sh
bash scripts/verify-release-hardening.sh \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --dependency-report tmp/release-dependencies.txt
```

Release検証は、まず`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`で解決済み依存グラフを出力し、その後source規則と
生成したAPK/AABを検査します。ネットワーク／Nearby権限、debug/Fake入口、広すぎる
`FileProvider`、カメラ画像/frameの保存や不意のpayload書き出し、analytics/crash SDKの依存を検出した時は失敗します。
Room、Preferences DataStore、将来のBLE復旧／既知端末状態はcloud Auto Backupと
device-to-device transferの両方から除外します。BLE snapshotとversion/profile付き既知端末identityは
`files/datastore/codematch-ble-symbology.preferences_pb`だけを使い、scan payloadや設定値を既知端末envelopeへ含めません。PDF共有で
`FileProvider`が公開するのは専用の`cache/codematch-pdf/`だけです。

checkerは依存ライブラリを追加せず、lockfile、SBOM、その他の生成物をリポジトリへ
作りません。Gradle dependency verificationとSBOM／ライセンス出力は、依存artifactの
供給元と署名ポリシーを固定してから別途導入します。現時点の再現可能なゲートは
Gradle Wrapper validation、release依存グラフ検査、checkerによるsource/APK/AAB検査です。

エミュレーターは状態遷移とCompose UIの継続検証に使います。カメラの読み取り完了判定はPixelなどの実Android端末で行います。実端末で行う確認項目、証跡、未実施の扱いは [実機確認ランブック](../docs/android/REAL_DEVICE_RUNBOOK.md) に従ってください。現時点では、このREADMEや自動テストの結果だけでQR/Code 128の実読取、focus、連続箱、BLE通信の成功を宣言しません。

GitHub ActionsではAPI 31と、Linux x86_64向けに提供される最新runtime（現時点はAPI 36）を実行します。compile/target SDK 37はbuild jobで保証し、API 37 runtimeはApple Silicon上のローカルエミュレーターで補完します。

## 現在の検証境界

実装と証跡の対応表は [Android版の現在地](../docs/android/STATUS.md) にまとめています。プライバシー・権限・backup・FileProviderの境界は [Android版プライバシー境界](../docs/android/PRIVACY.md) を正本とします。候補SDKのライセンス、ABI、target SDK、rawログ、scan callbackの評価は [Android BLE SDK評価メモ](../docs/android/BLE_SDK_EVALUATION.md) に記録しており、未解決のためproduction adapterを同梱しません。

## Fake scannerの境界

Fake scannerは`scanner/fake`へ置き、`app`からは`debugImplementation`だけで参照します。`releaseImplementation`や`implementation`では参照しないため、リリース依存グラフとAPKにFake入口を含めない構成です。CIの`android-release-build` jobがこの境界を確認します。

`scanner/ble`には、command直列化、timeout後の停止、完全設定snapshot、復元前Ready禁止、payload正規化と重複抑制だけをSDK/UUID非依存で置いています。Android BluetoothGattまたはInateck SDKへ接続するproduction adapterは、対象scanner、firmware、Android向けSDKと実通信形式の調査が完了するまで追加しません。カメラは実行時`CAMERA`権限だけを要求し、端末同梱ML Kitを使います。依存ライブラリ由来の`INTERNET` / `ACCESS_NETWORK_STATE`宣言もmanifest mergeで除外し、release APK/AABをオフライン境界に保ちます。Fakeはdebugだけで、production BLE未接続の現段階ではNearby/Bluetooth権限を宣言しません。M4で実機adapterを追加する場合は、必要時の`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`だけを許可し、checkerの`--allow-production-ble-permissions`モードを使います。
