# Code Match for iOS

`code-match` SPAの基本機能を、SwiftUIとAVFoundationで再実装したiOSネイティブアプリです。QRコードを先に、Code 128バーコードを次に読み取り、前後の空白・改行だけを除いて完全一致を判定します。カメラ映像と読み取り値は端末外へ送信しません。

## すぐに実行する

1. [CodeMatch.xcodeproj](CodeMatch.xcodeproj) をXcodeで開く。
2. Schemeに `CodeMatch`、実行先にiOS 17以降の端末またはiOS 26.5シミュレーターを選ぶ。
3. 実機へ入れる場合は、Target `CodeMatch` の Signing & Capabilities でTeamを選ぶ。
4. `⌘R` で起動する。

シミュレーターには利用可能な背面カメラがないため、画面下部の「カメラなしで判定をテスト」から一致・不一致のUI、音声、状態遷移を確認できます。実際の読み取りはカメラ搭載のiPhone/iPadで確認してください。

## 実装済み

- QR → Code 128 → 自動照合の迷いにくい2スキャンフロー
- `AVCaptureMetadataOutput` による完全ローカル読み取り
- Code 128の同一値2フレーム確認による誤検出抑制
- タップフォーカス、連続オートフォーカス
- 一致時の成功音・触覚、不一致時の4回警告音・触覚
- カメラ権限拒否、カメラ非搭載時のエラー表示
- Dynamic Type、VoiceOver用ラベル、44pt以上の主要操作領域
- Privacy Manifest、カメラ利用目的、App Icon
- 比較ロジックの単体テストと基本フローのUIテスト

## テスト

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild test \
  -project CodeMatch.xcodeproj \
  -scheme CodeMatch \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

実機読み取り用のサンプルコードは `TestResources/Generated` にあります。再生成する場合:

```sh
swift tools/generate_test_codes.swift TestResources/Generated
```

設計判断、実装手順、受け入れ基準は [docs/IMPLEMENTATION_GUIDE.md](docs/IMPLEMENTATION_GUIDE.md) を参照してください。

シミュレーターで確認済みの画面は [初期画面](docs/screenshots/initial-screen.png) と [一致結果](docs/screenshots/match-result.png) に保存しています。App Store Connect用画像を作る際の構成確認にも利用できます。
