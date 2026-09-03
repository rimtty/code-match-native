# Android BLE SDK評価メモ

## 判定

2026-09-04時点で、ユーザー指定により公式Inateck Android SDK 2.0.0を非配付PoCへ採用しました。`scanner:inateck`を`scannerPoc` build typeだけへ接続し、通常のdebugはFake、releaseはカメラ専用を維持します。Pixel 7 / Android 16とBCST-36では検索・接続・設定制限・QR→Code 128一致・背景時復元を実通信で確認しましたが、Samsung、予期しない切断、強制終了、配付条件を含むM4全体は未完了です。

## 確認した候補

- 公開元: [Inateck-Technology-Inc/android_sdk](https://github.com/Inateck-Technology-Inc/android_sdk)
- 確認日: 2026-09-03
- リポジトリの最新code commit: 2025-01-09（`8ce0fd5d25d1`）
- 配布物: `inateck-scanner-ble-2-0-0.jar`
- 付随依存: FastBle 2.4.0、Gson 2.8.9、JNA、`libscanner_cmd.so`
- 通知parser: 公式`scanner_lib` commit `6d8fc093656c`の`libinateck_scanner_cmd.so`
- native ABI: `arm64-v8a` のみ
- JARのImplementation-Version: `2-0-0`
- 公開APIの候補: scan、connect、`getSettingInfo`、`setSettingInfo`、`setRestart`
- JAR内で確認できるUUID定数: `FF00`、`FF01`、`FF04`、`FF05`
- 例示Manifestが要求する権限には、location、advertise、`INTERNET` などが含まれる

上記のUUIDやAPIは候補SDKの静的情報です。iOSで観測した値をAndroid仕様として確定したり、ここにある定数をproductionへハードコードしたりしません。

## PoC限定の採用条件

### ライセンスと供給元

公開元に `licenseInfo` がなく、Licenceファイルも確認できません。SDK binaryはGitへcommitせず、公式commit `8ce0fd5d25d1`からローカル取得してSHA-256を検証します。PoC APKは配付せず、release artifactにも同梱しません。

### target SDKとABI

例示プロジェクトはtarget SDK 33、native libraryはarm64-v8aだけです。Code Match側はcompile/target 37、min 31のまま、PoC APKをarm64-v8a実機専用に限定します。Play配付や他ABI対応には使用しません。

### 権限とプライバシー

PoC ManifestはAPI 31以降の`BLUETOOTH_SCAN`と`BLUETOOTH_CONNECT`だけを許可し、FastBle由来のlegacy Bluetooth、位置情報、advertise、network権限をmerge時に除去します。SDKのraw `android.util.Log`呼び出しは、非debuggable/minified PoCのR8規則で副作用なしとして除去し、FastBle loggingも初期化直後に停止します。

### scan通知の受け渡し

公式サイトの[接続手順](https://docs.inateck.com/scanner-sdk-en/ble/desktop_connect/)にある`set_code_callback`は公開Android 2.0.0 JARにはありません。PoC adapterはSDK接続完了後のFF01 notifyを1本だけ所有し、SDK command実行中は`BleTaskManager.receiveData`へ、アイドル中は公式`scanner_lib`の`inateck_scanner_cmd_notify_data_result`へ振り分けます。status 0で返る断片だけを次回入力へ保持し、status 1まで再構成します。

BCST-36のscanはtype 1で返り、Android command libraryは通知再構成までで、公式iOS SDKにある最終notify-code APIを公開していません。adapterは公式iOS SDK commit `03aa36d0e204`と実機挙動に合わせ、末尾の加算checksumを検証し、先頭2 byteとchecksumを除いた本文だけをstrict UTF-8へ渡します。checksum不一致、短すぎるframe、invalid UTF-8、4096 byte超過はfail closedです。payload、raw frame、byte数は診断・Logcatへ出しません。

### 接続要求と古いcallbackの境界

安全コアが発行したrequest/link generationをtransportへ明示的に渡し、接続・切断・scan eventで同じ値を返します。SDK adapter内部のcallback取消用epochとは分離します。両者を同じcounterとしていた実装では、切断によるepoch更新後に再接続成功のrequest番号がずれ、正しい成功通知を安全コアが拒否する経路がありました。

終端の切断・接続失敗では、pending/active identityとcallback epochを先に無効化してから通知します。接続完了callbackの重複、切断後の遅延成功、失敗した接続開始後の古いcallbackは受理しません。実coordinatorとSDK gatewayのFakeを組み合わせ、手動切断後の再接続、同期開始拒否後の再接続、pending切断、重複通知、明示generationの往復をJVM testで検査します。これはSDKとのソフトウェア境界の証拠であり、BCST-36の手動切断・再接続の実機ゲートを完了させるものではありません。

### 公式flag/value形式の先行実装境界

公式の[General Configuration](https://docs.inateck.com/scanner-sdk-en/ble/desktop_setting/)は、成功応答を`status=0`と`info`配列（`name`、`flag`、`value`）、書込commandを数値の`flag`/`value`配列として定義しています。また[General Configuration List](https://docs.inateck.com/scanner-sdk-en/ble/desktop_setting_list/)はCode 128をflag 2008、QR Codeをflag 2022としています。

`InateckDocumentedFlagValueCodec`はこのSDK-level形式だけを対象にし、UTF-8、成功status、全itemのname/flag/value、flag一意性、0/1値を検証します。2xxxの全reported symbologyを順序付きで保持し、2008/2022をsession対象として識別し、書込時はflag/value以外を送出しません。`area`はiOS形式との変換を仮定せず、内部でのみ`flag:<number>`というprofile-local identityを使います。

このJSONはGATTへ直接書くwire形式として公開されていません。そのためcodecはrelease DIや`AndroidBleTransport`へ接続せず、将来SDK-backed transportが実機応答との一致を確認した場合だけ明示選択します。実機未確認のままproduction adapter完成とは扱いません。

## 2026-09-04に確認した実機範囲

- Pixel 7（Android 16 / API 36）とBCST-36（GATT mode）でSDK検索・接続に成功した。
- `getSettingInfo`の実機inventoryからQR/Code 128を識別し、全barcode symbologyをsession用に制限してfresh readbackが一致した後だけReadyになった。
- 分割されたQR通知とCode 128通知を公式native parserで再構成し、QR→Code 128の順で同一品番の一致まで完了した。
- バックグラウンド移行後、保存した開始前symbologyを復元し、fresh readback後に接続済み・設定済みへ戻った。
- active sessionのQR待機中に`scannerPoc`をOSからforce-stopして再起動し、checkpointの工程を維持したまま保存済みBCST-36へ自動再接続して、設定処理後に接続済み・Readyへ戻った。その状態からQR→Code 128を読み取り、照合件数が1件増えて次のQR待機へ進んだ。
- 安全な段階ログは`incomplete` / `scan` / `delivered`だけで、payload、raw frame、設定値、device IDを含まなかった。

## 残る実機ゲート

1. 同一QRの重複抑止、不一致、異なる箱QRの連続照合をBCST-36で確認する。
2. 手動切断・予期しない切断・scanner再起動後の既知端末再接続と完全復元を確認する。アプリ強制終了はQR待機で合格済みだが、Code 128待機・結果表示中の工程復元は別途確認する。
3. 復元中の電源断やtimeoutでReadyにならず、カメラへ安全にfallbackすることを確認する。
4. scanner型番に加えてfirmware revisionを記録する。
5. Samsung系で同じ受け入れを実施する。配付へ進む場合は、その前に正式な再配布条件と対応ABIを確認する。

これらが完了するまで、通常のreleaseはカメラ入力のみです。`scannerPoc`はローカル実機評価専用で、上記のPixel/BCST-36部分合格をproduction採用やM4完了へ読み替えません。
