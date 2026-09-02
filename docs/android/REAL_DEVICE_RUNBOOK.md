# Android実機確認ランブック

この手順は、エミュレーターやCIでは代替できないAndroid端末の確認を同じ条件で再実行するためのものです。実機確認済みと記録できるのは、実際に接続した端末で該当項目を完了し、証跡を保存した項目だけです。現在のcheckoutでは、この文書だけでQR/Code 128の実読取やBLE成功を宣言しません。

## 0. 対象と前提

- Android 12（API 31）以上。主対象はPixel系、比較対象はSamsung系。
- USBデバッグを有効にした実端末。カメラ付き端末を使う。
- リポジトリの `shared/test-fixtures/images/` を端末とは別の表示端末または紙に表示する。実ラベルを使う場合は、値をログやスクリーンショットへ残さない。
- `android/` は独立Gradleプロジェクト。JDK 21とAndroid SDK 37を用意する。
- M4 BLE adapterをまだ接続していない版では、releaseはカメラ入力のみ。設定画面のBluetooth接続成功は受け入れ条件にしない。

端末情報、OS/API、アプリversion、ビルドcommit、実施日時、テスト結果、未実施理由を記録します。失敗時のログにはpayload、カメラ画像、端末アカウント情報を含めないでください。

## 1. ビルドとインストール

```sh
cd android
./gradlew assembleDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

テスト用端末で既存データの影響を除いて再確認する場合だけ、端末を確認してからアプリのデータを消去し、再インストールします。業務端末では実行しないでください。

```sh
adb shell pm clear jp.rimtty.codematch
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

自動化されたドメイン・Compose・Room検査も先に実行します。

```sh
./gradlew testDebugUnitTest lintDebug
bash scripts/run-connected-tests.sh
```

### PDF保存・共有の自動証拠と手動境界

自動testは、長い履歴からA4複数ページPDFを生成し、全ページに描画内容があること、共有用ファイルが専用`cache/codematch-pdf/`配下だけへ作られることを確認します。さらに`CreateDocument(application/pdf)`、保存byteの完全一致、`ACTION_SEND`、PDF MIME、ClipData、read URI grant、FileProvider URIを検査します。

これは実際の保存先や共有先アプリの受け入れを意味しません。最終実機確認では、1件と複数ページになる履歴について保存先を選び、保存したPDFを端末のviewerで開き、共有シートから少なくとも1つの受け取り先へ渡せることを確認します。履歴内容やpayloadをスクリーンショット・外部ログへ残さず、検証用データだけを使用してください。

## 2. カメラ受け入れゲート（M3）

### 権限とライフサイクル

1. 初回起動で日本語表示を確認し、照合セッションを開始する。
2. カメラ権限を一度拒否し、画面に回復案内が出てクラッシュしないことを確認する。
3. 再試行で権限を許可し、CameraX previewが開始することを確認する。
4. 「今後表示しない」相当の拒否では、設定を開く導線を確認する。
5. カメラ待機中にアプリを背景化→復帰し、previewと論理工程が二重にならず再開することを確認する。
6. QR待機中、Code 128待機中、結果表示中に画面回転を行い、工程と表示が失われないことを確認する。

### 実読取と業務フロー

1. QR fixtureを正方形ガイド内で読む。QR工程から進み、Code 128以外では進まないことを確認する。
2. Code 128 fixtureを横長ガイド内で読む。同値を連続して読み、一致結果になることを確認する。
3. 品番違いのCode 128を読み、不一致表示になり履歴・件数へ保存されないことを確認する。
4. 一致した同一品番を連続して2箱以上読み、箱数と履歴詳細が増えることを確認する。
5. Code 128待機中に「QRを読み取りなおす」を押し、既存件数を変えずQR工程へ戻ることを確認する。
6. 結果表示中の手動「次の照合」が、設定に関係なく使えることを確認する。
7. 照合中にpreviewをタップし、タップ位置へのfocus要求が発生することを端末上で確認する。focusの成否は照明・端末差があるため、端末名と結果を記録する。

### 記録すべき証跡

- 端末メーカー/モデル、Android version/API、背面カメラ、アプリversion/commit。
- 権限拒否・再要求・背景復帰・回転の各結果。
- QR→Code 128、一致、不一致、連続箱、読み直し、手動次工程の結果。
- カメラ開始/停止と工程表示のスクリーンショット。payloadやカメラ画像そのものは保存しない。
- 失敗時は再現手順、時刻、端末状態、payloadを含まないlogcat抜粋。

このセクションを完了しても、対象端末で実ラベルを使った連続照合が確認されるまでは、M3完了やカメラ実機成功を宣言しません。

## 3. アクセシビリティと適応レイアウト

端末の設定を一つずつ変更し、変更後に照合・履歴・設定の主要操作へ到達できることを確認します。

1. TalkBackを有効にし、トップレベル移動、Stepperの現在工程、結果、一致件数、終了、手動次工程を読み上げる。
2. カメラ枠の説明と、座標を持たない利用者向けの中央focusアクションが読み上げられることを確認する。
3. 設定でSwitch、遅延選択、音選択、試聴、言語、scanner行が操作でき、選択状態が色だけでなく文言/roleで分かることを確認する。
4. 画面フォント倍率を1.0、1.3、2.0相当に変更し、主要ボタン、結果、戻る操作、保存・共有へスクロールで到達する。
5. compact phone、横長またはfoldable相当、tablet幅で履歴を開き、狭幅では一覧→詳細、広幅では一覧と詳細の同時表示になることを確認する。
6. Switch Accessまたはキーボード/D-padで、カメラ以外の主要操作を順番に実行する。

Compose側には、主要画面のsemanticsと最小タッチ領域、履歴のcompact/expanded表示を検査するUI testがあります。Accessibility Scannerの指摘と、実端末でのみ確認できるTalkBack/フォント倍率の結果は別々に記録してください。

## 4. プライバシーとrelease確認

実機確認用debug APKと配布候補release artifactを混同しないでください。release検証は端末へインストールする前に行います。

```sh
./gradlew assembleRelease bundleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/codematch-release-dependencies.txt
bash scripts/test-release-hardening.sh
bash scripts/verify-release-hardening.sh \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --dependency-report /tmp/codematch-release-dependencies.txt
```

確認する境界は次のとおりです。

- release Manifest/APK/AABに `INTERNET`、`ACCESS_NETWORK_STATE`、Nearby/Bluetooth、位置情報、広告権限がない（production BLE adapter未接続段階）。
- releaseにFake scanner、debug demo、診断用payloadがない。
- カメラ画像/frame、不一致値、scan payloadをファイル・ログ・analytics/crash SDKへ書き出さない。
- Room/DataStoreとBLE復旧状態がcloud backupとdevice-to-device transferから除外される。
- FileProviderは専用 `cache/codematch-pdf/` だけを一時共有する。

checker通過は静的・artifact証拠です。端末外通信がないことの最終確認や、ストア向け回答の更新は依存ライブラリ変更時にも再実施します。詳細は [`PRIVACY.md`](PRIVACY.md) を参照してください。

## 5. BLE実機ゲート（M4、現在は保留）

対象scanner、firmware、Android向けSDK、ライセンス、ABI、target SDK、実通信形式が確定するまで、次を実行しません。候補SDKの静的評価と保留理由は [`BLE_SDK_EVALUATION.md`](BLE_SDK_EVALUATION.md) に記録しています。

設定画面が生成する3つのCode 128は、自動instrumentationで同梱ML Kitから `/*EnterSet*/` → `/*BLE_GATT*/` → `/*ExitSave*/` の順にexact decodeできることを確認済みです。これは画像生成と復号の証拠であり、BCST-47が実際に読み取って設定を変更・保存した証拠ではありません。

実装が用意された後だけ、次を対象端末で行います。

1. Android 12以降のPixel系とSamsung系で、必要時だけNearby devices権限を要求する。
2. 初回設定ガイドの3コードを順に実機scannerで読み、GATT modeが保存されたことを確認してから、検索、接続、pairing、scan通知、切断、既知端末再接続を確認する。
3. QR→Code 128の一致、不一致、逆順拒否、重複callback抑止、連続箱を確認する。
4. 接続前のscanner報告を全symbology保存し、QR+Code 128固定mode後、終了・背景・切断・再接続で完全復元する。
5. timeout後にGATT commandが重ならず、安全にカメラへfallbackすることを確認する。
6. BLE→camera→BLEで工程と読み取り済みQRを維持し、scan payloadが診断やlogcatへ出ないことを確認する。

これらの実機結果がすべて保存されるまで、M4/full parity/BLE成功とは扱いません。

## 6. 証跡テンプレート

```text
実施日:
実施者:
端末（メーカー/モデル）:
Android version / API:
アプリ version / commit:
カメラ権限（拒否・再要求・設定）:
QR → Code 128 実読取:
一致 / 不一致 / 連続箱 / 読み直し:
背景復帰 / 回転 / focus:
TalkBack / font scale / Switch Access:
compact / expanded レイアウト:
PDF保存先 / viewer / 共有先:
release hardening checker:
未実施項目と理由:
添付証跡（payloadを含めない）:
```
