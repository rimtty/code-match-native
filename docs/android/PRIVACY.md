# Android版プライバシー境界

この文書は、Android版の現在の構成（#56でreleaseへ公式Inateck SDKを同梱した2026-09-05時点）で確認できるデータと権限の境界を記録します。Android版は手元利用専用でストアへ提出しないため、これはストア向けのプライバシー回答ではありません。実装や依存ライブラリを変更した場合は、Manifest、生成artifact、この文書を同時に見直します。

## 現在の境界

| 対象 | 現在の扱い | 端末外への扱い |
|---|---|---|
| カメラ映像・解析フレーム | CameraX/ML Kitの解析中だけ一時利用 | 保存・送信しない |
| 一致履歴 | Roomへ端末内保存。一致したQR/Code 128のpayloadを箱詳細とPDF生成に使う | analytics、クラッシュレポート、サーバーへ送信しない |
| 不一致・無効入力 | 照合状態とフィードバックにだけ使う | 履歴、診断、外部送信へ保存しない |
| BLE診断 | 接続・設定の種別と連番だけを最大20件表示 | scan payloadを保存・表示・送信しない |
| BLE復旧snapshot | `release`が公式SDK adapterへ接続し、開始前のsymbology設定を端末内に保存する | Auto Backupとdevice-to-device transferから除外する |
| BLE既知端末identity | 同じ除外DataStoreへversion/profile、device ID、表示名だけを保存。設定値・scan payload・raw frameは含めない | Auto Backupとdevice-to-device transferから除外する |
| PDF | ユーザーが保存を選んだ時は選択先へ、共有を選んだ時は専用cacheからSharesheetへ渡す | 明示操作の時だけアプリ領域外へ出る |

履歴のpayloadは「カメラ画像」ではありませんが、業務データとして扱います。端末内保存が不要な環境では、履歴の削除と端末管理ポリシーを利用してください。

## 現行releaseの権限

releaseは公式Inateck SDKのBLE adapterを同梱します。releaseのアプリ側で宣言するのは次の範囲です。

- `CAMERA`: カメラ入力を開始する時に要求する。
- `VIBRATE`: 成功・不一致・無効入力の触覚フィードバックに使う。
- `INTERNET`、`ACCESS_NETWORK_STATE`、legacy Bluetooth、位置情報、広告、その他のNearby権限は要求しない。releaseだけが`BLUETOOTH_SCAN`（neverForLocation）と`BLUETOOTH_CONNECT`を要求する。

依存ライブラリ由来のネットワーク権限は、アプリManifestの `tools:node="remove"` でrelease mergeから除外します。`release`は`app/src/release/AndroidManifest.xml`で`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`を要求し、SDKのManifestが持ち込むlegacy Bluetooth、位置情報、advertise、network権限を明示除去します。公式SDKは固定commitからローカル取得し、Gitへは含めません。releaseのAPKはストアへ提出せず、手元の端末へ直接入れて使います。

Room DB、設定DataStore、将来のBLE復旧・既知端末状態は、`android/app/src/main/res/xml/backup_rules.xml` と `data_extraction_rules.xml` のcloud/device-transfer双方で除外します。BLE snapshotのファイル名は `files/datastore/codematch-ble-symbology.preferences_pb` に固定し、汎用の `datastore/` 除外だけに依存しません。

PDF共有の `FileProvider` は `cache/codematch-pdf/` だけを公開し、provider自体は非exportedで一時読み取り権限に限定します。広いfiles/external/root pathは公開しません。

## 確認方法

Androidプロジェクトから次を実行します。

```sh
cd android
./gradlew assembleRelease bundleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/codematch-release-dependencies.txt
bash scripts/test-release-hardening.sh
bash scripts/verify-release-hardening.sh \
  --apk app/build/outputs/apk/release/app-release.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --dependency-report /tmp/codematch-release-dependencies.txt
```

`test-release-hardening.sh` はartifact前のsource-only回帰、`verify-release-hardening.sh` はsource XML、merged release Manifest、APK/AAB、backup規則、FileProvider、依存グラフ、production sourceを検査します。checkerが通っても、これは通信観測や対象スキャナー実機試験の代わりにはなりません。

## 配付する場合の再審査

Android版は手元利用専用で、releaseに公式SDKを同梱したAPKを配付・ストア提出しません（#56）。将来、第三者へ配付する場合は、実施前に次を別変更として記録します。

1. Inateckの正式ライセンスと再配布条件、依存artifactのchecksumを確認する。
2. SDKのABI（arm64-v8aのみ）とnative libraryのpage alignment（現行SDKは4KB。16KB page size端末では動作しない）、target SDK、Manifest要求を確認する。
3. 新しいManifest、APK/AAB、依存グラフ、通信・ログ観測、プライバシー説明を同じPRで更新する。
