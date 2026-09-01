# Code Match Android

Android版Code Matchの独立Gradleプロジェクトです。iOS版と同じ照合契約を、Android標準の操作とJetpack Compose / Material 3で実装します。

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
./gradlew assembleRelease
```

エミュレーターは状態遷移とCompose UIの継続検証に使います。カメラの読み取り完了判定はPixelなどの実Android端末で行います。USBデバッグ端末を接続した実機確認では、QR → Code 128、権限、回転、背景復帰、音・触覚を確認します。

GitHub ActionsではAPI 31と、Linux x86_64向けに提供される最新runtime（現時点はAPI 36）を実行します。compile/target SDK 37はbuild jobで保証し、API 37 runtimeはApple Silicon上のローカルエミュレーターで補完します。

## Fake scannerの境界

Fake scannerは`scanner/fake`へ置き、`app`からは`debugImplementation`だけで参照します。`releaseImplementation`や`implementation`では参照しないため、リリース依存グラフとAPKにFake入口を含めない構成です。CIの`android-release-build` jobがこの境界を確認します。

`scanner/ble`には、command直列化、timeout後の停止、完全設定snapshot、復元前Ready禁止、payload正規化と重複抑制だけをSDK/UUID非依存で置いています。Android BluetoothGattまたはInateck SDKへ接続するproduction adapterは、対象scanner、firmware、Android向けSDKと実通信形式の調査が完了するまで追加しません。カメラは実行時`CAMERA`権限だけを要求し、端末同梱ML Kitを使います。依存ライブラリ由来の`INTERNET` / `ACCESS_NETWORK_STATE`宣言もmanifest mergeで除外し、release APKをオフライン境界に保ちます。

## 現在の検証境界

M3ではCameraX 1.6.2と端末同梱ML Kit Barcode Scanning 17.3.0を接続し、QR / Code 128限定解析、共通ROI、タップfocus、権限拒否、背景停止、古いcallback破棄を実装しています。API 37エミュレーターでは全モジュール計31件のinstrumentation test、初回拒否からの再要求、許可後のCameraX preview開始と背景復帰を確認済みです。Pixel 7（Android 16 / API 36）でも全モジュール計31件、CameraX preview開始、背景化後と横画面再構成後のpreview再開を確認しました。M3完了判定にはPixel 7でのQR → Code 128実読取、タップfocus、連続箱確認を残しています。M4準備としてBLE安全コアのJVM検証を追加しましたが、対象scannerとの実通信、設定形式の確定、永続snapshot接続、Pixel/Samsung実機復元は未完了です。Release版にFake scanner、未検証BLE adapter、開発用操作は含めません。
