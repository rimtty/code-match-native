# Android BLE SDK評価メモ

## 判定

2026-09-03時点で、ユーザー指定により公式Inateck Android SDK 2.0.0を非配付PoCへ採用しました。`scanner:inateck`を`scannerPoc` build typeだけへ接続し、通常のdebugはFake、releaseはカメラ専用を維持します。この採用は実機評価を始める判断であり、対象スキャナーの通信成功やM4完了を示しません。

## 確認した候補

- 公開元: [Inateck-Technology-Inc/android_sdk](https://github.com/Inateck-Technology-Inc/android_sdk)
- 確認日: 2026-09-03
- リポジトリの最新code commit: 2025-01-09（`8ce0fd5d25d1`）
- 配布物: `inateck-scanner-ble-2-0-0.jar`
- 付随依存: FastBle 2.4.0、Gson 2.8.9、JNA、`libscanner_cmd.so`
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

公式サイトの[接続手順](https://docs.inateck.com/scanner-sdk-en/ble/desktop_connect/)にある`set_code_callback`は公開Android 2.0.0 JARにはありません。PoC adapterはSDK接続完了後のFF01 notifyを1本だけ所有し、SDK command実行中は`BleTaskManager.receiveData`へ、アイドル中は最大4096 bytesのscan frame assemblerへ振り分けます。CR/LF/NUL終端と短いidle flushを扱い、切断・command開始・overflowでは断片を破棄します。実機観測が終わるまでproduction経路にはしません。

### 公式flag/value形式の先行実装境界

公式の[General Configuration](https://docs.inateck.com/scanner-sdk-en/ble/desktop_setting/)は、成功応答を`status=0`と`info`配列（`name`、`flag`、`value`）、書込commandを数値の`flag`/`value`配列として定義しています。また[General Configuration List](https://docs.inateck.com/scanner-sdk-en/ble/desktop_setting_list/)はCode 128をflag 2008、QR Codeをflag 2022としています。

`InateckDocumentedFlagValueCodec`はこのSDK-level形式だけを対象にし、UTF-8、成功status、全itemのname/flag/value、flag一意性、0/1値を検証します。2xxxの全reported symbologyを順序付きで保持し、2008/2022をsession対象として識別し、書込時はflag/value以外を送出しません。`area`はiOS形式との変換を仮定せず、内部でのみ`flag:<number>`というprofile-local identityを使います。

このJSONはGATTへ直接書くwire形式として公開されていません。そのためcodecはrelease DIや`AndroidBleTransport`へ接続せず、将来SDK-backed transportが実機応答との一致を確認した場合だけ明示選択します。実機未確認のままproduction adapter完成とは扱いません。

## 残る実機ゲート

1. 対象scannerの型番・firmwareを記録し、Pixelで検索・接続・認証を確認する。
2. SDKの`getSettingInfo`が返す全`area/name/value`を読み、QR/Code 128だけを有効にした後、exact readbackが一致することを確認する。
3. QR→Code 128、分割通知、重複抑止、切断・既知端末再接続を確認する。
4. 終了・背景・予期しない切断・再起動で変更前の全設定が復元され、完了前はReadyにならないことを確認する。
5. payload、raw frame、設定値がLogcatや診断へ出ないことを値を表示せず監査する。
6. Samsung系で同じ受け入れを実施する。配付へ進む場合は、その前に正式な再配布条件と対応ABIを確認する。

これらが完了するまで、通常のreleaseはカメラ入力のみです。`scannerPoc`はローカル実機評価専用で、実機BLEの受け入れ証拠はまだありません。
