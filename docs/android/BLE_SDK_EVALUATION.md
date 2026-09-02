# Android BLE SDK評価メモ

## 判定

2026-09-02時点では、InateckのAndroid SDK候補をCode Matchへ採用しません。`scanner:ble` には安全コアと、UUID・endpoint・通知decoderを注入する汎用Android `BluetoothGatt` transportまで実装しました。対象scanner固有profile、vendor SDK、Nearby権限、release接続は保留します。このメモは公開SDKの静的評価であり、対象スキャナー実機の通信成功を示すものではありません。

## 確認した候補

- 公開元: [Inateck-Technology-Inc/android_sdk](https://github.com/Inateck-Technology-Inc/android_sdk)
- 確認日: 2026-09-02
- 配布物: `inateck-scanner-ble-2-0-0.jar`
- 付随依存: FastBle 2.4.0、Gson 2.8.9、JNA、`libscanner_cmd.so`
- native ABI: `arm64-v8a` のみ
- JARのImplementation-Version: `2-0-0`
- 公開APIの候補: scan、connect、`getSettingInfo`、`setSettingInfo`、`setRestart`
- JAR内で確認できるUUID定数: `FF00`、`FF01`、`FF04`、`FF05`
- 例示Manifestが要求する権限には、location、advertise、`INTERNET` などが含まれる

上記のUUIDやAPIは候補SDKの静的情報です。iOSで観測した値をAndroid仕様として確定したり、ここにある定数をproductionへハードコードしたりしません。

## 採用を保留する理由

### ライセンスと供給元

公開元に `licenseInfo` がなく、Licenceファイルも確認できません。再配布許諾、正式ライセンス、バイナリ供給元、固定checksumが確認できるまで、リポジトリやrelease artifactへ同梱しません。

### target SDKとABI

例示プロジェクトはtarget SDK 33で、Code Matchのtarget SDK 37方針と一致しません。native libraryもarm64-v8aだけなので、対応端末の範囲、Play配布条件、他ABIを含める必要性を決めるまで採用できません。

### 権限とプライバシー

例示Manifestの権限は、production BLE未接続段階の最小権限方針を超えています。さらに、候補JARの `BleMessager.connect` はnotify callbackのraw byte配列をログへ出力します。scan payloadや業務データをログ・診断へ残さないCode Matchの要件と両立しないため、ラッパーを追加するだけでは採用済みとは扱いません。

### scan通知の受け渡し

静的評価では、`BleTaskManager.receiveData` が実行中taskなしの場合に即時returnし、公開クラス/API一覧にもunsolicited scan payloadをアプリへ渡す確定callbackが見当たりません。対象scanner実機で、接続・通知・payload decoder・重複抑止を一連で観測できるまで、scan入力のproduction経路として使用しません。

## 次の調査ゲート

1. Inateckから正式ライセンス、再配布条件、現行SDK、依存一覧、checksum、ABI、target SDK対応表を取得する。
2. 対象scannerの型番・firmware・ペアリング要否を記録し、Android 12以降のPixel系とSamsung系で接続可否を確認する。
3. ログを無効化または安全に除去できる供給形態を確認し、scan payloadがログ・診断・crash reportへ出ないことを監査する。
4. Androidでservice/characteristic、設定取得・書込、scan通知、切断・再接続、timeout後のcommand直列化を観測する。
5. 変更前の全symbologyを保存し、QR+Code 128固定mode後に終了・背景・切断・再接続で完全復元できることを確認する。
6. 必要な場合だけ `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` を要求し、release hardening checker、依存グラフ、Manifest、通信観測を同じ変更で更新する。

これらが完了するまで、現在のreleaseはカメラ入力のみです。Fake scannerはdebug test専用で、実機BLEの受け入れ証拠はまだありません。
