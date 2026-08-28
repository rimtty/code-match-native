# Inateck BCST-47 Bluetoothスキャナ

## SDKの準備

Inateckの公式iOS SDKはリポジトリへ再配布せず、実機ビルドの前に固定コミットから取得します。

```sh
./scripts/bootstrap_inateck_sdk.sh
```

スクリプトは `03aa36d0e204997130afaca00c2176aa7e5089af` を取得し、framework本体のSHA-256を検証して `Vendor/Inateck` へ配置します。このディレクトリはGit管理対象外です。公式リポジトリにライセンスファイルが見当たらないため、App Store配布やバイナリ同梱の前にInateckへ再配布許諾、正式ライセンス、現行SDK、XCFramework提供可否を確認してください。

SDKのframework本体はarm64のiPhoneOS用です。Simulatorビルドでは `INATECK_SDK` を定義せずframeworkもリンクしないため、アプリ内モックを使用します。

## Simulatorでの確認

Simulatorは独立したBluetoothペアリング先ではなく、Inateck SDKによるBCST-47の検索・接続は検証できません。macOSへHID接続したスキャナのキー入力がSimulatorへ流れる場合もありますが、このアプリのSDK接続経路の合否にはなりません。

1. 設定タブの「Bluetoothスキャナ」で「スキャナを検索」を押す。
2. `BCST-47 (Simulator)` を選び、接続済み表示を確認する。
3. 照合セッションを開始し、「入力元」でBluetoothが自動選択されていることを確認する。
4. 「カメラなしで判定をテスト」を開き、「モックQR」、「モックCode 128」の順に押す。
5. 照合結果と履歴件数を確認する。

UIテスト向け起動引数 `-demoBluetoothConnected` を指定すると、モック端末を起動時から接続済みにできます。

## iPhoneとBCST-47での確認

1. Mac側でBCST-47の接続を解除するか、MacのBluetoothを一時的にオフにして自動再接続を止める。
2. BCST-47の取扱説明書に従い、GATT／APPモードへ切り替える。
3. `./scripts/bootstrap_inateck_sdk.sh` を実行してから、XcodeでiPhone実機へインストールする。
4. 初回起動時にBluetooth利用を許可する。
5. 設定タブからスキャナを検索し、BCST-47を選んで接続する。
6. 照合画面でBluetoothが自動選択されていることを確認し、実ラベルのQR、Code 128の順に読む。
7. 一致、不一致、同一品番の連続箱、履歴保存を確認する。
8. カメラへ戻し、現在ステップに対応したカメラ読取が再開することを確認する。
9. Bluetooth読取中にスキャナの電源を切り、読取済みQRを保持したままカメラへ戻ることを確認する。
10. スキャナを再起動し、アプリのバックグラウンド復帰または再起動で最後の端末へ自動再接続することを確認する。

接続済みスキャナが確認できた照合画面ではBluetoothが既定の入力元になります。その後に利用者がカメラを明示選択した場合は、その照合セッション中の選択を維持します。Bluetooth読取中の切断では現在ステップを保持してカメラへ退避し、再接続できるとBluetoothへ自動復帰します。

発見できない場合は、Macやほかの端末との接続が残っていないか、BCST-47がHIDモードではなくGATT／APPモードかを確認し、Inateck公式アプリでも接続可否とファームウェア要件を確認します。

バックグラウンドでの読取は対象外です。アプリがフォアグラウンドにある間だけ使用し、`bluetooth-central` background modeは有効にしていません。
