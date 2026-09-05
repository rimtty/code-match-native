# Code Match iOS 実装・設計ガイド

## 1. 移植した仕様

Web版 `/Users/rimd2r/rimtty/code-match/app/page.tsx` を基準にしました。

1. 正方形のQRコードを読み取る。
2. 同じカメラセッションでCode 128バーコードを読み取る。
3. QRの固定長レコード11〜20桁目の品目番号と、バーコードの`@`より前の品番を正規化して一致判定する（共通ルールは [`../PRODUCT_SPEC.md`](../PRODUCT_SPEC.md)）。
4. 一致は緑と成功フィードバック、不一致は赤と4回の警告音・触覚で通知する。
5. バーコード待機中にQRの取り違えへ気づいた場合は「QRを読み取りなおす」でQRだけを破棄し、同じ作業セッションのまま別のQRを読み取る。
6. 「次のコードを照合」で読み取り値とカメラ状態をリセットし、同じ作業セッションを継続する。
7. 一致した場合だけコードと照合時刻を記録し、照合画面上端の件数へ加算する。
8. セッション終了後も、履歴タブから開始・終了時刻、一致件数、コードを確認する。
9. 任意設定で、一致結果を1秒、3秒、または5秒表示した後に次の照合を自動開始する。初期設定はOFFとする。

対象フォーマットをQRとCode 128だけに絞ることで、誤読と不要な分岐を抑えています。画像保存、サーバー通信、ユーザーアカウントはスコープ外です。不一致は作業実績に含めず、一致した照合だけを履歴へ保存します。

## 2. アーキテクチャ

| 層 | 主なファイル | 責務 |
|---|---|---|
| UI | `ScannerScreen.swift` | 進捗、カメラ枠、読み取り値、結果、アクセシビリティ |
| Navigation | `RootTabView.swift` | セッション開始、照合/履歴タブ、作業画面の切り替え |
| History UI | `HistoryScreen.swift` | セッション一覧と一致コードの詳細表示 |
| State | `ScannerViewModel.swift` | QR→バーコード→結果の状態機械、誤読抑制、リセット |
| Camera | `CameraScanner.swift` | 権限、AVCaptureSession、メタデータ、フォーカス |
| Domain | `ScanModels.swift` | ステップ、結果、正規化と完全一致 |
| History | `HistoryModels.swift`, `HistoryStore.swift` | セッションモデル、JSON永続化、件数更新 |
| Feedback | `FeedbackPlayer.swift` | 成功・失敗の音と触覚 |

カメラセッションは専用Serial Queueで開始・停止し、UI状態の更新だけMainActorへ戻します。一致した読み取り値だけをApplication Support内のJSONへ保存し、完全ファイル保護を設定します。カメラ映像と不一致の値は保存しません。

QR・Code 128は `AVCaptureMetadataOutput` でライブ映像から文字列だけを解析します。静止画撮影用の `AVCapturePhotoOutput` は使用しません。各コード受理時は触覚だけを返し、音は最終的な一致・不一致の判定時にだけ鳴らします。

## 3. インターフェース方針

- 最重要の「いま何を読むか」をカード見出し、進捗、枠形状の3箇所で一致させる。
- 作業セッション中は一致件数と終了操作を画面上端に固定し、長い画面をスクロールしても見失わないようにする。
- 照合と履歴を下部タブで分離し、進行中のセッションを保ったまま過去の作業を参照できるようにする。
- QRでは正方形、バーコードでは横長のガイドに変え、文字を読まなくても次の操作を理解できるようにする。
- 撮影操作を要求せず、認識した時点で自動的に次へ進む。
- 読み取り値を結果前にも表示し、現場でラベルの取り違えを視認できるようにする。
- 色だけに依存せず、記号、文言、音、触覚を組み合わせる。
- Web版のチャコール `#151B18`、緑 `#0E7C58`、ライム `#C8F36A` を継承する。

App Iconは「Code 128を想起させる白いバーコード＋ライムの照合チェック」だけに絞り、文字、細かなQRパターン、スキャン枠を避けています。原画は `Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` です。

## 4. Xcodeで実機配布まで進める手順

1. Xcodeでプロジェクトを開き、Bundle Identifierが組織内で一意か確認する。
2. Signing & CapabilitiesでTeamを指定する。
3. 実機で初回カメラ権限、QR、Code 128、一致、不一致、バックグラウンド復帰を確認する。
4. Product > ArchiveでRelease Archiveを作る。
5. App Store Connectでアプリ名、説明、スクリーンショット、プライバシー回答を設定する。
6. TestFlightへアップロードし、照明・距離・印刷品質が異なる現場ラベルで評価する。

### USB-C接続で実機確認する

1. iPhoneのロックを解除してUSB-CケーブルでMacへ接続する。
2. iPhoneに「このコンピュータを信頼しますか？」が出たら「信頼」を選び、パスコードを入力する。
3. Xcodeで `ios/CodeMatch.xcodeproj` を開き、Target `CodeMatch` の Signing & CapabilitiesでApple AccountのTeamを選ぶ。
4. Xcode上部の実行先から接続したiPhoneを選び、`⌘R` を押す。
5. Developer Modeを求められた場合は、iPhoneの「設定 > プライバシーとセキュリティ > デベロッパモード」を有効にして再起動する。
6. 初回起動でカメラを許可し、`shared/test-fixtures/images` のQRとバーコードを別の画面または紙に表示して読み取る。

Macへの接続後は、XcodeのDevices and Simulatorsで「Connect via network」を有効にすれば、同じネットワーク上でのワイヤレス実行も可能です。初回設定とトラブル時はUSB-C接続を推奨します。

### Bluetoothスキャナーの診断ログを取得する

`BluetoothScannerService.trace(_:)` は接続・設定・読取受理の段階を、payload・設定値・device識別子を含めない形で記録します（1回の読取受理につき1行）。記録先は2つあります。

- **アプリ内**: 直近300件を`UserDefaults`へ保持し、設定画面の「接続診断」に直近20件を表示します。同じ欄の「診断ログを共有」で全件をテキストとして共有シートへ渡せるので、長時間セッションで読取が止まったときは、その場でAirDropやメールで書き出してください。「診断ログを消去」で記録を空にできます。
- **統合ログ**: `os.Logger`（subsystem `jp.rimtty.CodeMatch`、category `BluetoothScanner`、`privacy: .public`）にも同じ行を出力します。USB接続したMacから次のように取得できます。

```sh
# 直近30分のログを端末から収集する（端末名はXcodeのDevicesに表示される名前）
sudo log collect --device-name "端末名" --last 30m --output codematch-ble.logarchive
# BluetoothScannerの行だけを取り出す
log show --predicate 'subsystem == "jp.rimtty.CodeMatch" AND category == "BluetoothScanner"' \
  --info --style compact codematch-ble.logarchive
```

Console.appで端末を選び、検索欄に `subsystem:jp.rimtty.CodeMatch` を入れると、接続したままリアルタイムに追跡できます。再現時は「読み取らなくなった時刻」「その直前の操作（背景化・通知・切断）」「スキャナーの読取音の有無」を併せて記録してください。

このアプリは一致履歴を端末内へ保存しますが、収集・追跡や外部送信は行わない構成です。将来、クラウド同期や分析SDKを追加した場合は、Privacy ManifestとApp Store Connectの回答を必ず更新してください。

## 5. 受け入れチェックリスト

- [ ] 初回起動でカメラ利用目的が日本語で表示される
- [ ] QR以外を先に映してもステップが進まない
- [ ] QR読取後はカメラを止めずにCode 128へ進む
- [ ] 同一品番の組み合わせで「一致しました」になる
- [ ] 一致時だけ固定表示の照合済み件数が1増える
- [ ] 品番違い（例: BCJH-52-81GG と BCJH-55-81GG）で「一致しません」になり、4回警告される
- [ ] 不一致時は履歴と照合済み件数が増えない
- [ ] バーコードの`@`以降（管理コード）の違いは判定に影響しない
- [ ] QRの枝番が空白でも品目番号を抽出できる
- [ ] リセット後に値と結果が残らない
- [ ] 自動「次の照合」は初期設定がOFFで、設定画面と照合セッション中の両方からON/OFFできる
- [ ] 自動「次の照合」をONにすると、一致時だけ設定した1秒、3秒、または5秒の残り時間が表示され、0秒後に次のQR読み取りが始まる
- [ ] バーコード待機中に「QRを読み取りなおす」を押すと、照合件数を変えずにQR工程へ戻り、別のQRを読み取れる
- [ ] カウントダウン中にOFFへ切り替えると自動遷移が中止され、手動の「次の照合」は引き続き使える
- [ ] セッション終了後、履歴タブで開始・終了時刻、件数、コードを確認できる
- [ ] アプリ再起動後も過去のセッション履歴が残る
- [ ] 権限拒否時にクラッシュせず案内を表示する
- [ ] VoiceOverで進捗、結果、主要ボタンが理解できる
- [ ] Dynamic Typeの大きな文字でも主要操作へスクロールできる
- [ ] 機内モードで全機能が動作する

### Bluetoothスキャナーの照明（`lighting_lamp_control`）

Android版と同じ規則で扱います。接続して読み取り設定がReadyになった直後にOFF（値2＝常時消灯）を適用し、SDKから再取得した値が一致したときだけ設定画面にスイッチを表示します。スイッチONは値0（読取中点灯）で、値1（常時点灯）は提供しません。設定は機器のinventoryが返した`area`/`name`で書き、汎用flag 1003へ置き換えません。値はスキャナー本体に保存され、切断時には復元しません（工程用のシンボロジー復元とは別）。取得→書込→再取得の3往復に20秒の上限を置き、超過時は読み取り設定と同じくリンクを閉じて再接続します。

実機確認: 接続後にスイッチがOFFで確定表示になる → ONでトリガー中に点灯 → OFFで消灯 → 切断・再接続後に再びOFFへ戻る。

### 読取チューニング（多コード・反転・赤光消灯時間）

照明の確認後、`BluetoothScannerService.tuningProfile`（`qrcode_read_more_code` / `datamatrix_read_multi` / `pdf417_read_more_code` / `read_inverse_color` / `*_read_phase` = 0、`auto_close_mode` = 20）を接続ごとに揃えます。inventoryにある項目だけを対象にし、現在値と異なる項目があるときだけ1コマンドで書いて再取得で確認します（HPRT-4F5Fは多コード・反転が既定でOFFのため、初回は`auto_close_mode`だけが書かれ、以後は書込なし）。値は機器側に保存され、切断時には復元しません。設定画面には「確認済み（変更なし）」「適用済み」「適用しています…」の状態だけを表示します。

HPRT-4F5F（2026-09-05）で確定したinventory nameと汎用flagの対応:

| 目的 | inventory name | 汎用flag | 実測 |
|---|---|---|---|
| 赤光消灯までの時間 | `auto_close_mode`（単位0.2秒、既定10） | 1023 | 20で約3.8秒点灯 |
| 読取モード | `scan_mode`（既定2） | 1006 | 2＝自動クローズモード。変更しない |
| 多コード認識 | `qrcode_read_more_code` / `datamatrix_read_multi` / `pdf417_read_more_code` | 1049 | 既定0（OFF） |
| 反転バーコード | `read_inverse_color`、`qrcode_read_phase` など `*_read_phase` | 1020 | 既定0（OFF） |
| 照明 | `lighting_lamp_control` | 1003 | 0＝読取中点灯、2＝常時消灯 |
| 電源自動OFF時間 | `time_auto_off`（既定10）、`auto_off`=1 | — | 赤光時間には影響しない。触らない |

`time_auto_off`を試行で20にした個体は、現行プロファイル適用前に10へ戻してあります。

## 6. 次の拡張候補

必要になった時だけ、設定画面でGS1-128やEANを選べる機能、懐中電灯、履歴の検索・削除、CSVエクスポートを追加します。現時点ではシンプルさと誤操作防止を優先し、実装していません。
