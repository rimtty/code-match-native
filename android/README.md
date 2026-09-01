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
app/                  # MainActivity、Material 3 navigation skeleton
core/model/           # framework-free domain model（Android library境界）
core/matching/        # parser / matcher（fixture parity）
core/designsystem/    # Code MatchのMaterial 3 tokensとtheme
scanner/fake/         # 開発用Fakeの隔離先（debug専用）
```

`core/model`と`core/matching`の本体ロジックはAndroid APIに依存しません。共通fixtureは`../shared/test-fixtures`をテストリソースとしてクラスパスへ追加し、テストからは`ClassLoader.getResourceAsStream`で読み込みます。

## ビルドとテスト

```bash
./gradlew assembleDebug
./gradlew lintDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

エミュレーターは状態遷移とCompose UIの継続検証に使います。カメラの読み取り完了判定はPixelなどの実Android端末で行います。USBデバッグ端末を接続した実機確認では、QR → Code 128、権限、回転、背景復帰、音・触覚を確認します。

## Fake scannerの境界

Fake scannerは`scanner/fake`へ置き、`app`からは`debugImplementation`だけで参照します。`releaseImplementation`や`implementation`では参照しないため、リリース依存グラフとAPKにFake入口を含めない構成です。CIの`android-release-build` jobがこの境界を確認します。

BLEのproduction実装は対象scanner、firmware、Android向けSDKの調査が完了するまで追加しません。現段階ではネットワーク権限やカメラ権限も宣言していません。
