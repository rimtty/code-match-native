# Code Match for iOS

`code-match` SPAの基本機能を、SwiftUIとAVFoundationで再実装したiOSネイティブアプリです。iOSカメラまたはInateck BCST-47 Bluetoothスキャナで、納品書兼現品票のQRコードを先に、現品票のCode 128バーコードを次に読み取ります。QRの固定長レコードから抽出した品目番号(10桁)とバーコードの`@`より前の品番を照合します（データ仕様は [docs/qr-barcode-spec-analysis.html](docs/qr-barcode-spec-analysis.html) を参照）。一致したコードは作業セッション単位の履歴として端末内だけに保存され、端末外へ送信しません。

## すぐに実行する

1. [CodeMatch.xcodeproj](CodeMatch.xcodeproj) をXcodeで開く。
2. Schemeに `CodeMatch`、実行先にiOS 17以降の端末またはiOS 26.5シミュレーターを選ぶ。
3. Bluetoothスキャナを使う実機ビルドでは、先に `./scripts/bootstrap_inateck_sdk.sh` を実行する。
4. 実機へ入れる場合は、Target `CodeMatch` の Signing & Capabilities でTeamを選ぶ。
5. `⌘R` で起動する。

シミュレーターには利用可能な背面カメラがないため、画面下部の「カメラなしで判定をテスト」から一致・不一致のUI、音声、状態遷移を確認できます。実際の読み取りはカメラ搭載のiPhone/iPadで確認してください。

## iPhone実機で確認する

1. ロック解除したiPhoneをUSB-CでMacへ接続し、iPhone側でMacを信頼する。
2. XcodeでTarget `CodeMatch` の Signing & Capabilitiesを開き、自分のTeamを選ぶ。
3. Xcode上部の実行先で接続したiPhoneを選び、`⌘R` を押す。
4. 必要に応じてiPhoneの「設定 > プライバシーとセキュリティ > デベロッパモード」を有効にする。
5. 起動後にカメラを許可し、`TestResources/Generated` の画像を別画面または紙から読み取る。

初回のDeveloper Mode有効化ではiPhoneの再起動と確認操作が必要です。

## 実装済み

- 「記録を開始する」から始める作業セッションと、照合中に固定表示される一致件数
- 一致したコード・照合時刻・開始/終了時刻の端末内履歴
- 過去のセッション一覧と、一致コードを確認・コピーできる詳細画面
- QR → Code 128 → 自動照合の迷いにくい2スキャンフロー
- `AVCaptureMetadataOutput` による完全ローカル読み取り
- Inateck BCST-47の検索・接続・自動再接続と、BluetoothによるQR／Code 128読み取り
- カメラ／Bluetooth入力切替、切断時の読取状態維持とカメラ復帰
- Code 128の同一値2フレーム確認による誤検出抑制
- タップフォーカス、連続オートフォーカス
- 一致時の成功音・触覚、不一致時の4回警告音・触覚
- カメラ権限拒否、カメラ非搭載時のエラー表示
- Dynamic Type、VoiceOver用ラベル、44pt以上の主要操作領域
- Privacy Manifest、カメラ利用目的、App Icon
- 比較・履歴保存の単体テストと、セッション開始を含む基本フローのUIテスト

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

BCST-47のSDK準備、Simulatorモック、iPhone実機での検証手順は [docs/BLUETOOTH_SCANNER_TEST.md](docs/BLUETOOTH_SCANNER_TEST.md) を参照してください。

シミュレーターで確認済みの画面は [初期画面](docs/screenshots/initial-screen.png) と [一致結果](docs/screenshots/match-result.png) に保存しています。App Store Connect用画像を作る際の構成確認にも利用できます。
