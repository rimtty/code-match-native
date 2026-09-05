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

## 対応対象と検証端末の区別

ユーザー指定により、接続対象はBCST-36固定ではなく、採用した公式SDKに対応するスキャナーです。BCST-36は今回の実機検証端末にすぎません。SDKの探索結果から選択したdevice identityへ接続し、既知端末の保存は再接続用であって機種の許可リストではありません。別端末へ以前の設定snapshotを流用してはいけません。

他機種も選択・接続できる設計を維持しますが、全機種の実機検証済みを意味しません。設定API・必須QR/Code128項目・通知プロトコルが適合しない場合は安全に未Readyとして扱い、機種名だけで成功と判定しません。

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

### 利用可否と設定復元の境界

Androidは[探索と接続に別の権限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)を要求します。SCANだけの不許可と、接続中にCONNECTが失われる場合を同一の接続喪失として扱いません。

PoC hostは既存の250ms tickerと前景復帰・利用者操作の境界でSDK gatewayのreadinessを再確認し、変化を安全コアへ通知します。接続が利用不可になったlinkでは、物理切断のepochを残したまま読み取り・設定応答を無効化し、一時的にReadyへ戻っても古いcallbackを受理しません。切断・再接続後に改めてinventoryとsnapshot復元を確認します。SCANだけを失った場合は探索を停止し、CONNECTが有効な既存linkは失効させません。

安全コアは利用不可通知でも物理linkのidentityとsnapshotを保持します。復元中に手動切断が要求されていた場合、利用不可への変化でその意図を自動再接続へ変更しません。旧linkの切断完了までは別deviceへsettings ownerを付け替えず、Ready通知だけで保存済みsnapshotを消しません。物理linkがないときのReady通知は古い利用不可表示をIdleへ戻すだけで、再接続のdeadline/回数や設定確認結果は変更しません。

接続開始そのものが同期的に拒否された場合は、その要求に付随する切断待ちも解放します。接続中・切断中の新しい探索は禁止し、設定画面も接続中は検索ボタンを無効にします。これらはcallback/権限snapshotを注入する自動testと設定画面のemulator testで検査する境界であり、実Androidでの権限取り消し・Bluetooth OFFやscannerの物理切断を代替する証拠ではありません。

利用者が明示的に探索を始めた場合は、linkがない状態の自動再接続予約を取り消して探索を優先します。adapter由来の探索中にもtimerで接続を重ねません。切断失敗後の再試行可否は表示状態ではなく実際のclose要求待ちで判断し、電源OFF/ONによって失敗表示が変化しても再試行できます。復元中に再接続を予約した後で再度切断を選んだ場合は、最後の手動切断を優先します。

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

## 過去の実機ゲートと受入範囲

以下は初期評価時点の一覧です。最新の個別結果はIssue #19と[`STATUS.md`](STATUS.md)を参照してください。2026-09-05、ユーザーは残る追加確認を省略し、非配布ローカルPoCとしての受入完了を指定しました。未実施の復旧途中Ready境界・正常アプリUIを含む実SDK異常系・保持基準再接続probeの実機実行を成功扱いにはしません。通常releaseのcamera-onlyとSDK対応機種を固定しない契約は維持します。

1. 同一QRの重複抑止、不一致、異なる箱QRの連続照合をBCST-36で確認する。
2. 手動切断・予期しない切断・scanner再起動後の既知端末再接続と完全復元を確認する。アプリ強制終了はQR待機で合格済みだが、Code 128待機・結果表示中の工程復元は別途確認する。
3. 復元中の電源断やtimeoutでReadyにならず、カメラへ安全にfallbackすることを確認する。
4. scanner型番に加えてfirmware revisionを記録する。
5. Samsung系で同じ受け入れを実施する。配付へ進む場合は、その前に正式な再配布条件と対応ABIを確認する。

通常のreleaseは引き続きカメラ入力のみです。`scannerPoc`はローカル運用専用で、今回の受入完了をproduction採用・他機種での実機検証成功・再配布許諾へ読み替えません。
