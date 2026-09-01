# Code Match Native

`code-match` のモバイル向けネイティブ実装を管理するモノレポです。現在はiOS版を提供しており、Android版はまだ追加していません。iOSとAndroidはそれぞれのネイティブ技術で実装し、照合仕様とテストデータを共有します。

## リポジトリ構成

```text
code-match-native/
├── ios/                     # SwiftUI / AVFoundationによるiOSアプリ
├── shared/
│   ├── test-fixtures/       # 両プラットフォームで使う照合ケースと読取画像
│   └── tools/               # 共有テスト資源の生成ツール
├── docs/
│   ├── PRODUCT_SPEC.md      # プラットフォーム共通の振る舞い
│   └── ios/                 # iOS固有の設計・実機検証手順
└── .github/workflows/
    └── ios-ci.yml           # iOSのdevice buildとSimulatorテスト
```

Android開発を開始するときは、ルートに独立した `android/` Gradleプロジェクトと `android-ci.yml` を追加します。iOSプロジェクトを再移動したり、AVFoundationやBluetooth SDKのコードを共有層へ持ち込む必要はありません。

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

QR → Code 128 → 照合という業務フローと品番の正規化ルールは [共通プロダクト仕様](docs/PRODUCT_SPEC.md) に定義しています。実データ由来の入力例と期待結果は [shared/test-fixtures/matching-cases.json](shared/test-fixtures/matching-cases.json) にあり、将来のKotlinテストでも同じケースを利用できます。

テスト用画像を再生成する場合は、macOSでリポジトリルートから次を実行します。

```sh
swift shared/tools/generate_test_codes.swift
```
