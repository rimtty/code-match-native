# Code Match for iOS

`code-match` SPAの基本機能を、SwiftUIとAVFoundationで再実装したiOSネイティブアプリです。iOSカメラまたはInateck BCST-47 Bluetoothスキャナで、納品書兼現品票のQRコードを先に、現品票のCode 128バーコードを次に読み取ります。一致したコードは作業セッション単位の履歴として端末内だけに保存され、端末外へ送信しません。

プラットフォーム共通の照合ルールは [共通プロダクト仕様](../docs/PRODUCT_SPEC.md) を参照してください。

## すぐに実行する

1. [CodeMatch.xcodeproj](CodeMatch.xcodeproj) をXcodeで開く。
2. Schemeに `CodeMatch`、実行先にiOS 17以降の端末または利用可能なiOSシミュレーターを選ぶ。
3. Bluetoothスキャナを使う実機ビルドでは、先に `./scripts/bootstrap_inateck_sdk.sh` を実行する。
4. 実機へ入れる場合は、Target `CodeMatch` の Signing & Capabilities でTeamを選ぶ。
5. `⌘R` で起動する。

シミュレーターには利用可能な背面カメラがないため、画面下部の「カメラなしで判定をテスト」から一致・不一致のUI、音声、状態遷移を確認できます。実際の読み取りはカメラ搭載のiPhone/iPadで確認してください。

## iPhone実機で確認する

1. ロック解除したiPhoneをUSB-CでMacへ接続し、iPhone側でMacを信頼する。
2. XcodeでTarget `CodeMatch` の Signing & Capabilitiesを開き、自分のTeamを選ぶ。
3. Xcode上部の実行先で接続したiPhoneを選び、`⌘R` を押す。
4. 必要に応じてiPhoneの「設定 > プライバシーとセキュリティ > デベロッパモード」を有効にする。
5. 起動後にカメラを許可し、`../shared/test-fixtures/images` の画像を別画面または紙から読み取る。

初回のDeveloper Mode有効化ではiPhoneの再起動と確認操作が必要です。

## 実装済み

- 「記録を開始する」から始める作業セッションと、照合中に固定表示される一致件数
- 一致したコード・照合時刻・開始/終了時刻の端末内履歴
- 過去のセッション一覧と、一致コードを確認・コピーできる詳細画面
- QR → Code 128 → 自動照合の2スキャンフロー
- `AVCaptureMetadataOutput` による完全ローカル読み取り
- Inateck BCST-47の検索・接続・自動再接続とBluetooth読み取り
- カメラ／Bluetooth入力切替、切断時の読取状態維持とカメラ復帰
- 誤検出抑制、タップフォーカス、連続オートフォーカス
- 成否の音・触覚、権限エラー、アクセシビリティ、Privacy Manifest
- 比較・履歴保存の単体テストと基本フローのUIテスト

## テスト

この `ios/` ディレクトリから実行します。

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild test \
  -project CodeMatch.xcodeproj \
  -scheme CodeMatch \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

設計判断と受け入れ基準は [iOS実装ガイド](../docs/ios/IMPLEMENTATION_GUIDE.md)、BCST-47のSDK準備と実機手順は [Bluetoothスキャナ検証手順](../docs/ios/BLUETOOTH_SCANNER_TEST.md) を参照してください。

シミュレーターで確認済みの画面は [初期画面](../docs/ios/screenshots/initial-screen.png) と [一致結果](../docs/ios/screenshots/match-result.png) に保存しています。
