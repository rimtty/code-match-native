# Android版の到達点（手元利用版）

2026-09-05の判断（Issue #57）により、Android版は手元利用専用（ストア提出なし）とし、下記「打ち切った確認項目」に挙げる実機・手動ゲートはこれ以上確認しません。打ち切りは検証成功を意味しません。このページは、その時点の到達点と打ち切り項目の一覧であり、CIや実機の結果はこのページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

## 運用形態

- 配付: 手元でビルドした`release` APK（arm64-v8a、minified、既定はdebug keystore署名）を自分のPixel 7へ`adb install`する。ストア提出、署名済み配付物の運用承認、第三者への配付は行わない。
- release構成: カメラ入力（CameraX + bundled ML Kit）に加え、公式Inateck Android SDK 2.0.0のBLE adapter（`scanner:inateck`）を同梱する（#56、`scannerPoc` build typeは廃止）。SDK binaryは固定commitからchecksum検証付きでローカル取得し、Gitへは同梱しない。SDKの`.so`は4KB page alignmentのため、16KB page size端末では動作しない。
- debug構成: `scanner/fake`のFake scannerで照合フローを駆動する。emulator・CIのinstrumentationはこの構成で実行する。
- 接続対象: 採用SDKに対応するスキャナー。BCST-36（firmware `V2.6.16 AI JP`、Bluetooth `OTA_D_V0.3.7`）とHPRT-4F5Fは検証機種であり、機種の許可リストではない。
- 接続時の機器設定（2026-09-06、#59）: 照明OFF（`lighting_lamp_control`=2、設定画面のトグルで読取中点灯へ）の確認後に、読取チューニング（多コード`*_read_more_code`/`*_read_multi`=0、反転`read_inverse_color`/`*_read_phase`=0、赤光消灯時間`auto_close_mode`=20≒4秒）をinventoryと比較し、差分のある項目だけを書いて再取得で確認する（PR #74）。値は機器側に保存され、切断時に復元しない。name/flag対応表は[`../ios/IMPLEMENTATION_GUIDE.md`](../ios/IMPLEMENTATION_GUIDE.md)。
- 診断: BLE診断イベントを300件保持し、設定画面の「接続診断」から共有シートまたはSAF保存先へテキストで書き出せる（PR #76）。段階名と版情報のみで読取値は含まない。

## 到達点

| 領域 | 確認済み（自動test・artifact検査・Pixel 7実機） |
|---|---|
| Domain / matching | 純Kotlin matcher/parser、shared fixture（`matching-cases.json`）、JVM test、Swift unit 71本/UI 5本との意図対応表（[`TEST_PARITY.md`](TEST_PARITY.md)） |
| UI / navigation | Composeの照合・履歴・設定、3 destination、system/predictive backの完了・無効・cancel境界、履歴選択のActivity再生成・destination往復・compact back stack、320dp/840dp・font scale 1.3/2.0の主要操作到達、動的案内・結果のpolite live region、emulatorでのQR待機・Code 128待機・一致結果のOS force-stop後UI復元。Pixel 7ではfont scale 1.3/2.0の主要表示・操作をユーザーが承認 |
| History / settings / PDF | Room（schema v2）/DataStore、日英リソースとper-app locale双方向同期、0件破棄・名称変更・詳細・削除のapp E2E、A4複数ページPDFの実render、SAF保存/専用FileProvider共有の契約test。Pixel 7では日英切替、1ページ/複数ページPDFのDownloads保存と共有先での表示、音量0/通常音量の音・触覚をユーザーが承認 |
| Camera | CameraX/ML Kit adapter、工程別ROI、権限・lifecycle・focus・format切替の非同期境界test。Pixel 7縦画面で実ラベルのQR→Code 128一致、復帰後のCode 128、タップfocus、権限の拒否・恒久拒否・再許可、ガイド枠内外の読取境界、無関係QR拒否、不一致の表示・音・振動・非加算をユーザーが承認 |
| BLE | SDK非依存の安全コア（command直列化、全設定snapshot、復元前Ready禁止、known-device store、再接続予算）、公式SDK adapter、公式native通知parser、工程別symbology制限（QR待機はQRのみ、Code 128待機はCode 128のみ）、照明の接続時OFF適用、読取チューニング（差分時のみ書込・readback確認）、診断ログの共有・保存、R8 vendor-log除去。Pixel 7 / BCST-36では検索・接続・fresh readback、QR→Code 128一致、背景復元、QR待機中のapp force-stop後の自動再接続、手動切断後の工程保持と再接続、電源OFF→ONの自動再接続、通常終了・手動切断・電源再起動後の開始前設定との一致（独立probe）、照明の初期OFFと手動ON/OFFをユーザーが承認。2026-09-05に`release` APKでBCST-36と接続し、QR→Code 128の照合完了をユーザーが確認（#56）。2026-09-06にPixel 7で読取チューニング「適用済み」と赤光約4秒、診断ログの共有・保存をユーザーが確認 |
| Privacy / release | Manifest、backup/D2D除外規則、専用FileProvider、`test-release-hardening.sh` / `verify-release-hardening.sh` / `verify-release-scanner-apk.sh`によるsource/APK/AAB/依存グラフ検査（Fake・analytics・INTERNET・legacy Bluetooth・位置情報の不在、`:scanner:inateck`とarm64 native libraryの同梱、vendor raw-log除去、ML Kit registrar保持）。Pixel 7のnetstatsで当該UIDの通信量エントリなし |

個別の端末・commit・ユーザー承認と自動確認の区別は、[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)の実施記録、Issue #19 / #23 / #56、および本ページ末尾の履歴を参照してください。

## 打ち切った確認項目（対象外）

Issue #57の判断で、以下は未実施のまま確認を打ち切りました。打ち切りは検証成功を意味しません。

- 配付・OEM: Samsung / 他OEMでのカメラ・BLE・レイアウト受入、ストア提出回答、署名済み配付物の運用承認、SDK再配付条件の確認、通信のパケット監査（netstatsの限定観測のみ）。
- アクセシビリティ・OS操作: TalkBack、Switch Access、Accessibility Scanner、OS設定画面からの言語変更、予測型「戻る」の視覚遷移、OEM依存のランチャー表示。
- PDF: 外部viewerでの全viewer互換、実共有先アプリ側での受け取り確認（Pixel 7で1件の共有先表示は確認済み）。
- カメラ: 画面回転中の工程保持、連続箱の実ラベル確認、OEM省電力挙動。
- BLE: 同一箱重複・不一致・連続箱の実機確認、予期しない切断、scanner再起動、Bluetooth権限変化中のlive link、Code 128待機 / 結果表示中のforce-stop、timeout復旧とカメラfallback、電源OFF→ON後の独立設定比較、復旧途中のReady境界、正常アプリUIを含む実SDK異常系の統合確認、PR #54の保持基準再接続経路の実機実行、firmware revisionの継続記録。
- 工程別symbology（[`STEP_SYMBOLOGY_CHECK.md`](STEP_SYMBOLOGY_CHECK.md)）の受入手順、照明（[`SCANNER_ILLUMINATION_CHECK.md`](SCANNER_ILLUMINATION_CHECK.md)）の残手順（QR→Code 128との組み合わせ、背景・切断競合）。
- 書込途中の強制終了の耐久性、Swift/Kotlinの全ケースを同一CI実行で確認した記録。

## パリティ分類の補足

[`TEST_PARITY.md`](TEST_PARITY.md) の `N/A` は、Androidに未実装のまま残した行ではなく、現行Androidの共通仕様に含まれないことをソースと仕様のリンクで確認した行です。現在の対象は、iOS固有の画面収録防御（#6）、旧iOS UserDefaultsのCode128-only recovery移行（#38）、旧iOS diagnosticsからの既知端末migration（#42）です。Android版は独立Gradle projectとして導入され、現行のBLE復旧はversion/profile付きの新規snapshot・known-device envelopeを使うため、これら旧iOS状態を読む入口はありません（[`android/README.md`](../../android/README.md#L1)、[`BleSymbologySnapshotStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L63)、[`BleKnownDeviceStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStore.kt#L90)）。

ROIのclamp（#5）はiOSとAndroidでpolicyが異なるため`P`のまま、active restriction（#27）は現行session mode文言だけを検査した`P`、camera lifetime（#58）はprocess破棄を含まない`P`です。`P`や`—`の行に残る実機・手動確認は、上記「打ち切った確認項目」に含まれる場合はこれ以上実施しません。

## 再現可能なチェック

Androidプロジェクトで次を実行できます（`rg`、JDK 21、Android SDK 37が必要）。

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
bash scripts/run-connected-tests.sh
bash scripts/setup-inateck-sdk.sh
./gradlew assembleRelease bundleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > /tmp/codematch-release-dependencies.txt
bash scripts/test-release-hardening.sh
bash scripts/verify-release-scanner-apk.sh
bash scripts/verify-release-hardening.sh \
  --apk app/build/outputs/apk/release/app-release.apk \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --dependency-report /tmp/codematch-release-dependencies.txt
```

JDK/SDKがない環境ではGradle結果を推測せず、実行不能として記録します。エミュレーター・CIのinstrumentation成功は、カメラの実読取やBLE通信の実機成功を意味しません。

## 履歴

以下は各時点の記述をそのまま残した記録です。「未完了」「実機ゲートに残す」「非配付`scannerPoc`」などの表現は当時のものであり、現在の扱いは上記「運用形態」「打ち切った確認項目」が優先します。

### 2026-09-05 受入更新（当時の記述）

非BLEのPixel 7受入（Issue #23）は、カメラ各工程のprocess再起動、権限の恒久拒否と復帰、日英切替、PDF保存・共有・最終ページ、音・触覚、font scale 2.0まで確認済みです。個別の端末・commit・ユーザー承認と自動確認の区別は [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md) とIssue #23を参照してください。PR #48（`c7e1419`）で記録を統合し、Android CIの4ジョブが成功しました。

今回の受入はPixel 7での非配布ローカル運用です。Samsung、ストア提出、TalkBack、Switch Accessはユーザー指定で対象外であり、検証成功を意味しません。接続対象はSDK対応スキャナーで、BCST-36は検証機種にすぎません。

BLE（Issue #19）は2026-09-05のユーザー指示により、残る追加確認を省略し、今回の非配布ローカルPoCとして受入完了とします。通常終了・手動切断・電源再起動後の終了時には、独立probeで開始前の全返却設定との一致を確認済みです。無操作中の電源OFF→起動後の自動再接続とQR工程保持も観測しましたが、その試験後の独立設定比較は未実施です。復旧途中のReady境界、正常アプリのUIを含む実SDK異常系の統合確認、およびPR #54の新しい保持基準再接続経路の実機実行は、未実施のままユーザー承認で追加受入対象から除外しました。省略は検証成功を意味しません。実施済みの限定的なSDK異常注入試験とその限界は[検証用APKの記録](../../android/tools/sdk-fault-probe/README.md)に残します。

独立SDK probeで取得した本体firmwareは `BCST-36 V2.6.16 AI JP`、Bluetooth版は `OTA_D_V0.3.7` です。旧SDKの不完全な最初の通知による完了を、同一コマンド・公式解析を維持した分割再構成で切り分けました。本番アプリへの変更はなく、採用profileは `inateck-android-sdk-2.0.0-area-name-v1` のままです（[実験と証拠](../../android/tools/sdk-probe/README.md)）。以下の2026-09-04時点の表は履歴であり、上記とランブックの追加受入が優先します。

このページは、2026-09-04のPixel 7 / BCST-36 BLE部分実機確認を含む統合監査と、その時点で再実行した証跡を基準にした状態記録です。CIや実機の結果は、このページの文言だけでは更新されません。PRの実行結果、端末記録、生成artifactを証跡として紐付けてください。

### 2026-09-02〜04 統合検証記録（当時の記述）

- 2026-09-04、Android 17/API 37.1・16KBのread-only emulatorで、専用application IDの`ProcessRecoveryInstrumentationTest`をhost runnerから実行した。QR待機・Code 128待機・一致結果の3ケース（各seed/verify、計6回のinstrumentation）がすべて成功した。公開ViewModel actionで合成checkpointを永続化し、実際に起動したアプリのPIDを確認してOS force-stop、PID消失後の新しいApplication/MainActivityで工程・session名・受理済み値・件数・全設定値・英語UIを確認した。一致結果は保存済み5秒delayを超えてもRESULTのまま、履歴entryは1件でcountdownを再開しない。これは完了済み保存からのOS process復元の証拠であり、書込途中の強制終了・実カメラ・BLE復元・OEM省電力挙動は検査していない。
- 同変更でJVM test 351件（失敗・error・skip 0）、全module lint、debug/release/非配付PoC APKとrelease AAB、release source/APK/AAB/dependency hardening、PoC artifact検査が成功した。実ContentProviderのUUID cache write/read 1件も通常debug testで再成功。runnerは物理端末・対象未指定・既存recovery app/test・通常APK・異なるinstrumentation targetを拒否し、10件のguard regressionが成功した。通常アプリとテスト専用アプリを同居させてもtest providerが衝突しない。物理端末・scannerには接続していない。専用debug manifestではCAMERA権限を除去し、API 31にない権限flag shell commandを使わない。

- 2026-09-04、公式SDK gatewayのAndroid利用可否をPoC hostの既存ticker・前景復帰・操作境界から安全コアへ通知するbridgeを追加した。SCANだけの喪失は探索停止、CONNECT/電源等の利用不可は旧linkのcallback失効として分離し、物理切断確認までidentityとsnapshotを保持する。利用可否が戻っても古いread/write/scanを受理せず、fresh inventoryと復元確認が必要となる。復元中の手動切断意図、旧linkがある間のdevice owner固定、接続中の探索禁止、同期接続拒否時の切断待ち解放も回帰testへ追加した。
- 同bridge統合とレビュー指摘の修正後、全moduleのJVM test 351件（`scanner:ble` 104、`scanner:inateck` 61を含む）、lint、debug/release APK、release AAB、非配付`scannerPoc` APK、app androidTest compileが成功した。release source/APK/AAB/dependency hardeningとPoC artifact検査も成功した。再接続予約中の明示探索、探索中のtimer抑止、電源変化を挟んだ失敗closeの明示再試行、復元中の最後の手動切断優先も回帰testに含む。Android 17/API 37.1・16KBのread-only emulatorでは`SettingsScreenTest` 14件を実行し、接続中・接続済みの検索無効化とIdleでの再有効化を含めて成功した。物理端末・scannerへは接続していない。これはFake gatewayと自動UIによる証拠であり、実Androidの権限取り消し・Bluetooth OFF、実scannerの切断/復元・再接続は引き続き実機ゲートに残す。
- 2026-09-04の接続境界の追加修正で、安全コアのrequest/link generationとSDK内部のcallback取消epochを分離した。これにより同一processの手動切断後や接続開始拒否後でも、正しい再接続成功を古い通知として拒否しない。pending中の切断、重複した接続完了、例外後の古いcallbackもJVM testで検査する。
- 同じ追加修正では、同期false/例外となった切断要求も旧linkを保持したclose-onlyの有限再試行へ進める。利用不可通知だけではpending/active identityや手動切断意図を消さず、Readyへの復帰も切断完了とは扱わない。明示的な再試行は新しい有限回数の回復処理を開始し、同期callbackのbackoffは呼出元の論理時刻を使う。全moduleのJVM test 329件（`scanner:ble` 91、`scanner:inateck` 52を含む）、lint、debug/release APK、release AAB、非配付`scannerPoc` APK、release source/APK/AAB/dependency hardeningとPoC artifact検査が成功した。この追加検証では物理端末・scannerに接続していない。利用不可通知のtestは安全コアへのevent注入であり、公式SDK adapterの実Bluetooth OFF/権限変化を検証した証拠ではない。実scannerの手動/予期しない切断・再接続とlive linkの権限変化は引き続き実機ゲートに残す。
- 2026-09-04の追加統合では、全moduleのJVM test 314件、lint、debug/release APK、release AAB、非配付`scannerPoc` APKが成功し、release source/APK/AAB/dependency hardeningとscanner PoC artifact検査も成功した。Android 17/API 37.1・16KB emulatorではapp 21、`core:data` 21、`core:export` 2、`feature:history` 9、`feature:scan` 19、`feature:settings` 16、`scanner:camera` 3、`scanner:ble` 8の計99件を実行し、失敗・skip 0だった。これは端末非依存境界の証拠であり、スリープ中のBCST-36や実カメラを起こしていない。
- 2026-09-04の端末非依存追加検証で、一時的なBluetooth OFF/権限不許可後も既知端末の有限再接続予約を維持し、論理時刻に基づくbackoffと最大試行回数で停止することを確認した。さらに公式SDK adapterが切断失敗を型付きeventとして安全コアへ渡し、同期/非同期の切断失敗後も旧linkを保持したままcloseだけを有限再試行し、`Disconnected`確認前には新しいconnectを開始しないことを追加した。`scanner:ble` JVM test 82件、`scanner:inateck` JVM test 46件と両moduleのlintが成功した。実BCST-36の電源/権限復旧、GATT復元、異常切断は実機待ちである。
- Android 17/API 37.1・16KB emulatorで、予測型「戻る」の無効時fallbackとgesture cancel時のno-op、および実`LocaleManager`を使う日本語/英語の双方向同期と再生成loop防止を計4件実行して成功した。言語testは製品DataStoreを使わず、変更前のpackage localeを`finally`で復元する。予測型「戻る」の視覚遷移とOS設定画面の実操作は人手ゲートに残す。
- 照合画面の動的な案内と一致/不一致/重複結果にTalkBack用のpolite live regionを追加し、Android 17/API 37.1・16KB emulatorで`ScanScreenTest` 11件を実行して成功した。同じemulatorで、test専用のUUID cacheとContentProviderを通したPDF write/read 1件も成功した。実TalkBack serviceの読み上げと外部viewer/共有先は人手ゲートに残す。
- Pixel 7の履歴1件画面からDocumentsUIのDownloadsへPDFを保存し、空ではなくPDF headerを持つことと、Androidの共有先選択画面が開くことを確認した。ファイル名や履歴payloadは記録していない。外部viewerの内容確認、複数ページ、実共有先への受け渡しは未実施である。
- 通常release APKのcompiled resourcesに通常/round/adaptive/monochromeのランチャーリソースが含まれ、最終Manifestが`mipmap/ic_launcher`と`mipmap/ic_launcher_round`を参照することをAndroid build toolsで確認した。ランチャー上の最終表示はOEM・テーマ依存のため実端末確認に残す。

- 2026-09-04、Pixel 7（Android 16 / API 36）とBCST-36（GATT mode）へ非配付`scannerPoc`を導入した。公式SDKで検索・接続し、実機inventoryを基準にQR/Code 128だけを有効化してfresh readback後にReadyとなった。BCST-36のtype-1分割通知は公式`scanner_lib`で再構成後、公式iOS SDK互換のchecksum/header処理を通し、QR→Code 128の一致まで成功した。背景移行では開始前symbologyを復元し、fresh readback後に設定済みへ戻った。さらにQR待機のactive sessionでOS force-stop→再起動を行い、工程とBLE選択を保ったまま保存済みBCST-36へ自動再接続し、接続済み・設定済みへ戻った。その状態からQR→Code 128の一致を完了し、照合件数が1件増えて次のQR待機へ進んだ。安全なログは段階名だけでpayload、raw frame、設定値、device IDを含まない。通常release APK/AAB/dependency hardeningも成功した。未実施の重複・不一致・連続箱・手動/予期しない切断・scanner再起動・QR待機以外のforce-stop・timeout・Samsung・配付条件は完了扱いにしない。

- 2026-09-04、同じPixel 7の縦画面と実ラベルでCameraXのQR→Code 128一致を確認した。解析対象を白いガイド枠内へ限定し、QRは正方形、Code 128は横長の工程別ROIへ調整した後に安定して一致した。不一致・連続箱・tap focus・回転・背景復帰・Samsungは未実施のため、M3全体の完了とは扱わない。

- 2026-09-03の公式Inateck SDK PoC統合では、SDK/FastBle/JNA/nativeを`scannerPoc`だけへ隔離し、設定応答とscan通知を共有するFF01をcommand-awareな単一routerへ置換した。分割通知、最大長、終端、250ms idle flush、oversize quarantine、接続・探索世代、pending接続取消、未知JSON破棄、area/name/value codecは自動testで固定した。SDK callback経路と全設定exact readbackは実装済みだが、実通信の証拠は実機ゲートに残す。PoC APKの最小Nearby権限、単一exported launcher、arm64 ABI、vendorログ文字列除去はartifact checkerで確認する。通常releaseにはSDK/native/Nearby権限を入れず、対象scanner実機結果は未記録である。
- 2026-09-03のBLE設定表示・再接続とcamera terminal close統合後、全moduleのJVM test 252件、lint、debug/release APK、release AABが成功した。release source regressionとAPK/AAB/dependency hardeningも成功し、release構成にFake、Nearby権限、analytics/crash SDKがないことを再確認した。USB接続Pixel 7（Android 16 / API 36）では、通常のdebugアプリ保存領域を消去しないmodule testとして`feature:scan` 18件、`feature:settings` 15件、`scanner:camera` 3件の計36件を実行し、失敗・skip 0だった。これは設定中→Ready→QR案内、raw reason非表示、工程を保持したcamera fallback、明示的な非同期再接続、terminal closeの自動証拠であり、対象scanner通信や実カメラ撮影の証拠ではない。
- 2026-09-03の追加hardening後、全moduleのJVM test 249件、lint、debug/release APK、release AABが成功した。release dependency/APK/AAB hardeningはFake、Nearby権限、analytics/crash SDKがない状態で成功し、すべてのAndroid CI jobでGradle起動前にWrapper validationを実行する構成にした。USB接続Pixel 7（Android 16 / API 36）では、通常のdebugアプリ保存領域を消去せず、`core:data` 21件、`feature:scan` 15件、`scanner:camera` 3件の計39件を実行し、失敗・skip 0だった。
- `core:data`の追加testは、ランダムなテスト専用Room DBを各checkpoint段階で閉じて再オープンし、active session、WAITING QR、WAITING Code 128、RESULT、受理済み値、入力元、明示camera選択、箱数を復元する。全設定値と言語もテスト専用DataStore再オープン後に復元する。これらはストレージ復元の証拠であり、OS force-stop/process kill後のアプリ再起動を意味しない。
- `app`の`ScanViewModelCheckpointInstrumentationTest`は、通常アプリの保存領域と分離したUUID付きRoom/DataStoreを使い、公開UI actionでQRを受理した後にDBを閉じて新しい`ScanViewModel`を生成し、WAITING Code 128と入力元が戻ることを検査する。MATCH済みRESULTの再生成では履歴entryを二重登録しない。これはrepository単体より上位のapp復元契約を証明するが、OSが実processをkillした操作そのものではない。
- カメラの追加testは、PreviewView回転・サイズ変更時の再bind、権限callbackの取り違え防止、停止後の遅延focus結果破棄、停止中のタップ無効化、ROI中間Bitmapの例外時解放を検査する。これはCameraX/Composeの非同期境界の証拠であり、実カメラでの読取・focus成功の証拠ではない。
- 2026-09-03のBLE事前実装後、全moduleのJVM test 242件、lint、debug/release APK、release AABが成功した。`scanner:ble`は68件で、汎用GATT transport 7件、`ExternalScanner` facade/lifecycle 11件を含む。release dependency/APK/AAB hardeningはFake、Nearby権限、analytics/crash SDKがない状態で成功した。
- USB接続Pixel 7（Android 16 / API 36）では、今回変更した`feature:scan` 14件、`feature:settings` 14件、`scanner:ble` 8件の計36件を再実行し、失敗・skip 0だった。これはframework seam、Compose UI、DataStore復旧の証拠であり、対象scannerとのGATT通信証拠ではない。

- Android Studio付属JDK 25とAndroid SDKを明示し、`testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease --no-parallel` を実行した。1,117 tasks、JVM test 211件が成功し、cameraの非同期test 13件、appの言語同期test 6件、PDF保存/共有bridge test 12件を含む全JVM test、lint、debug/release APK、release AABが完了した。
- release dependency reportと生成済みAPK/AABに対してhardening検査を実行し、Fake/analytics/crash依存、不要な権限、FileProvider、backup/D2D resource、全module manifestの検査が成功した。
- USB接続したPixel 7（Android 16 / API 36）1台を明示選択し、自動instrumentationを80件実行した。app 15、core:data 19、core:export 2、feature:history 8、feature:scan 12、feature:settings 13、scanner:camera 3、scanner:ble 8がすべて成功した。app testはdebug Fakeを同じDI graphから操作し、接続・入力切替・逆順拒否・一致、duplicate、QR読み直し、不一致非保存、0件破棄、履歴詳細・名称変更・削除、設定ガイドと再接続、言語のActivity再生成後保持、実時間auto-advanceを検査する。さらに履歴のbox詳細選択がActivity再生成とHistory→Settings→Scan→Historyの往復後も保持され、compact system backがbox→group→session→listの順に戻り、PDF失敗Snackbarの再試行が操作できることを確認した。core:dataは言語・履歴名・scan checkpointをDataStore/Room再オープン後にも確認し、v1→v2 migrationで既存session/entryを保持する。
- `core:export`の2件は、長い履歴から生成したPDFが複数ページになり、`PdfRenderer`で全ページに描画内容があること、共有用ファイルが専用`cache/codematch-pdf/`配下だけへ書かれることを検査した。appのJVM testは`CreateDocument(application/pdf)`、完全一致byte保存、失敗伝播、`ACTION_SEND`/MIME/ClipData/read grant/FileProvider URIを検査する。実際のDocumentProviderや共有先アプリでの受け入れはまだ手動ゲートである。
- `scanner:camera`の3件は複製していない共有QR/Code 128画像を同梱ML Kitへメモリ入力し、各形式のdecodeと誤形式拒否を検査した。画像・frame・payloadの保存やlog出力は行わない。この証拠は実カメラ撮影やCameraX preview frameを意味しない。
- `feature:settings`の追加1件は、生成した3つの設定用Code 128を同梱ML Kitへメモリ入力し、`/*EnterSet*/`、`/*BLE_GATT*/`、`/*ExitSave*/`へexact decodeできることを確認した。対象BCST-36がこれら3コードでGATT modeを変更・保存する操作は、別のBLE実機ゲートで確認する。
- `feature:scan` / `feature:history` / `feature:settings`は320dpのfont scale 1.3/2.0、Historyは840dp expandedでも主要操作の表示、スクロール到達、48dpタッチ領域を検査した。配色は実際に使うsemantic foreground/backgroundの組を4.5:1以上で固定した。TalkBack、Switch Access、OEM固有描画は引き続き手動ゲートである。
- アプリ内言語とAndroid per-app languageは共通synchronizerで双方向に揃え、同値時は再設定しない。OS設定画面からの実変更はまだ手動ゲートである。
- Apple SiliconのAndroid 17/API 37.1・16KB page-size Pixel 6 emulatorでも、同じ自動instrumentation 63件がすべて成功した。英語端末設定で見つかったHistoryテストのlocale依存を修正済みで、検証後はemulatorだけを正常停止した。
- Swift unit 71本/UI 5本とAndroid証拠の全対応・未対応境界は[`TEST_PARITY.md`](TEST_PARITY.md)に記録した。

この節のうち2026-09-04のCameraX実ラベル読取とBCST-36項目は部分的な実機証拠で、それ以外のPixel件数は自動testの証拠です。未記録のtap focus、TalkBack、切断・timeout等を、確認済みの実撮影・BLE通信から推測して完了扱いにはしません。

実機手順と証跡テンプレートは [`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)、プライバシー境界は [`PRIVACY.md`](PRIVACY.md) に分けています。
