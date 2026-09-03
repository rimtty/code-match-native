# Code Match Native

`code-match` のモバイル向けネイティブ実装を管理するモノレポです。iOS版に加え、Android版のKotlin / Jetpack Compose実装を段階的に開発しています。両者はそれぞれのネイティブ技術で実装し、照合仕様とテストデータを共有します。

## リポジトリ構成

```text
code-match-native/
├── android/                 # Kotlin / Jetpack ComposeによるAndroidアプリ
├── ios/                     # SwiftUI / AVFoundationによるiOSアプリ
├── shared/
│   ├── test-fixtures/       # 両プラットフォームで使う照合ケースと読取画像
│   └── tools/               # 共有テスト資源の生成ツール
├── docs/
│   ├── PRODUCT_SPEC.md      # プラットフォーム共通の振る舞い
│   ├── android/             # Android/Kotlinポーティング計画
│   └── ios/                 # iOS固有の設計・実機検証手順
└── .github/workflows/
    ├── android-ci.yml       # Androidのbuild、unit、lint、API 31/36 UIテスト
    └── ios-ci.yml           # iOSのdevice buildとSimulatorテスト
```

Android版はルートの独立した `android/` Gradleプロジェクトで開発します。現在は純Kotlinの照合・解析、Room/DataStore、照合・履歴・設定のMaterial 3画面、CameraX/ML Kit入力、PDF出力、音・触覚、日英切替、system/predictive back、debug専用Fake scanner境界まで実装済みです。これはBLE以外の実装と自動テストの基盤を示すもので、実カメラの読取完了やBLE接続完了を意味しません。ビルドとテストの手順は [android/README.md](android/README.md)、監査時点の境界は [Android版の現在地](docs/android/STATUS.md)、Swiftテストとの対応は [Androidテスト対応表](docs/android/TEST_PARITY.md) を参照してください。

Swift版の全機能をAndroidネイティブのUI/UXへ移植する段階的な方針は、[Android/Kotlinポーティング実装計画](docs/android/IMPLEMENTATION_PLAN.md) を参照してください。BLEは公式Inateck Android SDKを非配付`scannerPoc`へ隔離して実装し、対象スキャナーで実通信と設定復元を最終検証します。通常releaseはカメラ専用です。

Android版はAndroid 12（API 31）を対応下限、Android 17（API 37）をtarget SDKとします。BLEをAndroid 12以降のNearby devices権限モデルへ統一し、旧OS向けの位置情報権限分岐を持たない方針です。

Android版のプライバシー境界は [docs/android/PRIVACY.md](docs/android/PRIVACY.md)、実端末でのカメラ・アクセシビリティ・BLE確認は [docs/android/REAL_DEVICE_RUNBOOK.md](docs/android/REAL_DEVICE_RUNBOOK.md) に記録します。公式SDKのPoC限定条件とproduction採用前の残課題は [docs/android/BLE_SDK_EVALUATION.md](docs/android/BLE_SDK_EVALUATION.md) を参照してください。

## iOS版

Xcodeで [ios/CodeMatch.xcodeproj](ios/CodeMatch.xcodeproj) を開きます。詳しい起動方法、実装済み機能、テスト方法は [ios/README.md](ios/README.md) を参照してください。

```sh
cd ios
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild test \
  -project CodeMatch.xcodeproj \
  -scheme CodeMatch \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

## 共通仕様

QR → Code 128 → 照合という業務フローと品番の正規化ルールは [共通プロダクト仕様](docs/PRODUCT_SPEC.md) に定義しています。実データ由来の入力例と期待結果は [shared/test-fixtures/matching-cases.json](shared/test-fixtures/matching-cases.json) にあり、SwiftとKotlinのテストで同じケースを利用します。

テスト用画像を再生成する場合は、macOSでリポジトリルートから次を実行します。

```sh
swift shared/tools/generate_test_codes.swift
```
