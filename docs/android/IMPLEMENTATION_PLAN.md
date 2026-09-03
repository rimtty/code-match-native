# Code Match Android / Kotlin ポーティング実装計画

## 1. 目的と結論

Swift版の全機能をAndroidへ移植し、業務フロー・情報量・結果の分かりやすさを同等に保ちながら、Androidの標準操作、Material 3、画面サイズ適応、戻る操作、権限表示へ自然に合わせる。

実装は次の二段階に分ける。

1. カメラ、照合、履歴、PDF、音・触覚、設定、日英表示を含む「BLE以外の完全パリティ」を先に完成させる。
2. BLEの画面と状態遷移はモックで先行実装し、実機とAndroid向けSDKを入手した後に通信層を実装・検証する。

BLEを除いた中間成果物は開発・評価用として成立するが、Swift版との「全機能パリティ完了」はBLE実機検証が終わるまで宣言しない。

## 2. 移植の基準

### 2.1 振る舞いの正本

優先順位は次のとおりとする。

1. [`docs/PRODUCT_SPEC.md`](../PRODUCT_SPEC.md) と [`shared/test-fixtures/matching-cases.json`](../../shared/test-fixtures/matching-cases.json)
2. 現在のSwiftソースとテスト
3. `docs/ios/screenshots/` の画面イメージ

スクリーンショットはデザインの参考にするが、現在のSwift版は「照合・履歴・設定」の3タブであり、古いスクリーンショットに設定タブがない場合はソースを優先する。

### 2.2 維持するもの

- QR → Code 128 → 自動照合の固定順序
- 一致した結果だけを、作業セッション内の箱単位の記録として保存
- 同一品番の重複を箱数としてすべて保存・集約
- セッション名、開始・終了時刻、品番別・箱別の詳細
- 日本語を初期値とする日本語・英語表示
- 一致、不一致、無効入力を区別する音と触覚
- 自動「次の照合」は初期OFF、1/3/5秒、手動操作を常に優先
- 端末内完結、カメラ画像や読み取り値を外部送信しない方針
- 現場で一目で分かる大きな主要操作、進捗、成功・失敗表示

### 2.3 Androidに合わせて変えるもの

- SwiftUIの部品を外見だけ模倣せず、Jetpack ComposeとMaterial 3の標準部品へ置き換える。
- iPhoneのフローティングTabViewは、スマートフォンではMaterial 3の下部Navigation Bar、横幅が広い端末ではNavigation Railにする。
- iOSのシート、共有、ファイル保存、戻る操作をAndroidの予測型「戻る」、システム共有、ドキュメント作成UIへ置き換える。
- SF SymbolsはMaterial Symbolsへ意味を保って対応させる。
- Androidのシステムバー、エッジ・トゥ・エッジ、表示領域、フォント倍率、画面回転を前提にする。

## 3. Swift版の全機能パリティ表

| 領域 | Swift版の現行機能 | Android実装 | 受け入れ条件 |
|---|---|---|---|
| アプリ構造 | 照合・履歴・設定の3タブ | `NavigationSuiteScaffold`と単一Activity | スマートフォンでは下部バー、横幅拡大時はNavigation Rail。選択状態を維持 |
| セッション開始 | 任意名、開始日時、既存アクティブセッションの再利用 | Compose画面 + Roomトランザクション | 空白名は`null`、二重開始しない、プロセス再生成後も復元 |
| セッション終了 | 確認ダイアログ、0件なら破棄、1件以上なら終了日時を保存 | Material `AlertDialog` | カメラ停止後に終了し、0件の履歴を残さない |
| 進捗 | QR・バーコード・照合の3段階 | 独自Stepper Composable | 現在位置、完了状態、TalkBack読み上げが一致 |
| カメラ | 背面カメラ、開始/停止、QR/Code 128限定、枠、タップフォーカス | CameraX Preview + ImageAnalysis + ML Kit | QR待ちはQRのみ、Code 128待ちはCode 128のみ。枠内解析、開始/停止、権限拒否を実機確認 |
| 読み取り安定化 | QRは即時、カメラCode 128は1.5秒以内に同値2回で確定 | Scan stabilizer | Swiftテストと同じ入力系列で同じ確定結果 |
| 読み直し | QRだけ破棄してQR工程へ戻る | `RereadQr`イベント | セッションと一致件数を維持し、選択入力元で再開 |
| 入力順序 | 逆順を拒否し、値を照合に使わない | ドメイン状態機械で拒否 | 無効音・案内表示、工程・保存件数が変化しない |
| QR解析 | 66文字業務形式、各固定位置フィールド | 純Kotlin parser | Swiftの全fixtureとフィールド解析テストが一致 |
| Code 128解析 | `4-2-4@管理コード`、`@`以前を照合 | 純Kotlin parser | 正規化、管理コード除外、逆順拒否が一致 |
| 照合 | 大文字化、英数字以外除去、標準QR優先、6文字以上の包含fallback | 純Kotlin matcher | 共通JSON fixtureをSwift/Kotlinの両方で通す |
| 結果 | 一致/不一致、両品番表示、同一品番の箱番号 | Result composable | 不一致は保存せず、一致だけ重複を含め保存 |
| 自動次工程 | 初期OFF、1/3/5秒、可視カウントダウン | coroutine + `StateFlow` | 一致時のみ開始。設定OFF、手動、終了、背景化でキャンセル |
| 履歴一覧 | セッション降順、名前、日時、箱数、所要時間、削除 | Room + `LazyColumn` | 再起動後も一致。削除が永続化される |
| セッション詳細 | 名前変更、開始/終了、箱数、品番数、品番グループ | list/detail navigation | 同一品番を集約し、最初/最後の時刻と箱数を表示 |
| 箱詳細 | QR解析値、バーコード解析値、全文、管理コード | Detail composable | 旧データのpayloadなし表示も含めSwiftと同情報 |
| PDF | A4、日英、セッション/品番/箱/全文、複数ページ | `PdfDocument` | 同じデータ項目、改ページ、端末内生成、日英PDFを確認 |
| 保存・共有 | PDF保存と共有シート | `CreateDocument` + `FileProvider`/Sharesheet | ストレージ広域権限なしで保存・共有できる |
| 設定 | 自動次工程、音量、成功5音、失敗4音、試聴、言語 | DataStore + Material controls | 値が再起動後も維持され、照合画面へ即時反映 |
| 音・触覚 | 受理、無効、一致、不一致を区別 | SoundPool/AudioTrack + Android haptics | 4状態を聴覚・触覚で区別。0%音量でも触覚を維持 |
| 言語 | 日本語初期値、英語、日時・数値ロケール | `values-ja`/`values-en` + per-app locales | アプリ内とAndroid 13以降のシステム言語設定が同期 |
| エラー | カメラ/Bluetooth/保存の状態別案内 | 型付きUiState | 技術的な例外文字列を直接表示せず、回復操作を示す |
| ライフサイクル | 背景化でカメラ停止、復帰で論理工程を維持 | Lifecycle-aware state | 背景・回転・プロセス再生成で二重解析や工程消失なし |
| アクセシビリティ | ラベル、結合読み上げ、大きな操作面 | Compose semantics | TalkBack、48dp以上、フォント拡大、色以外の状態表現を確認 |
| プライバシー | ネット送信なし、履歴をバックアップ対象外 | production BLE未接続段階はINTERNET/Nearby権限なし、backup rules | 通信依存なし、画像を保存しない、DBをクラウドバックアップしない。現行の境界は[`PRIVACY.md`](PRIVACY.md)を正本とする |
| BLE | 検索・接続・再接続・診断・設定保存復元・カメラfallback | 抽象層とFakeを先行、実通信は最終フェーズ | 実機入手後の専用受け入れ基準をすべて満たすまで未完扱い |

Swiftテストには、iOS固有の画面収録防御（`TEST_PARITY.md` #6）と、旧iOS版のUserDefaults/診断データを新しい保存形式へ移す互換処理（同 #38、#42）も含まれる。これらはAndroidの現行共通仕様や新規保存形式への移植対象ではなく、根拠リンク付きの`N/A`としてパリティ完了条件から除外する。Androidの実カメラ、対象BLE、保存・共有先、アクセシビリティの手動ゲートは除外しない。

## 4. Android技術方針

### 4.1 基本構成

- Kotlin、Kotlin DSL、Gradle Version Catalog
- Android Gradle Plugin 9.3.2、Gradle 9.5、AGP 9のbuilt-in Kotlinを採用し、alpha版へ依存しない
- `minSdk 31`（Android 12）、`compileSdk 37` / `targetSdk 37`（Android 17）
- API 31を下限にすることで、BLEは`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`のNearby devices権限モデルへ統一し、旧OS向け位置情報権限分岐を持たない
- Gradle実行はCIでJDK 21、ローカルでAndroid Studio同梱JBRを使い、Java/Kotlinの生成bytecodeは17へ固定
- 単一Activity + Jetpack Compose + Material 3
- Navigation 3とMaterial 3 Adaptive
- ViewModel + immutable `UiState` + `StateFlow` + 単方向データフロー
- Kotlin Coroutines / Flow
- Hiltによる依存性注入
- Roomによる履歴保存、Preferences DataStoreによる少量設定保存
- CameraX + ML Kit Barcode Scanningの端末同梱モデル

ML Kitはカスタム読み取り画面が必要なためGoogle Code ScannerではなくBarcode Scanning APIを使う。初回ダウンロード待ちを避け、オフラインの現場でも確実に使えるよう、サイズ増加を許容して端末同梱モデルを選ぶ。

Android公式の現行方針に合わせ、UI層とデータ層を分け、repositoryを介してアクセスし、ComposeではUDFを採用する。

- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- [CameraX overview](https://developer.android.com/media/camera/camerax)
- [ML Kit barcode scanning](https://developers.google.com/ml-kit/vision/barcode-scanning/android)
- [Room](https://developer.android.com/training/data-storage/room)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

### 4.2 ディレクトリ案

現在のAndroid実装は、ルートの独立Gradleプロジェクトとして次の構成から開始している。後続moduleは各マイルストーンで追加する。

```text
android/
├── app/                         # MainActivity、アプリnavigation、DI組み立て
├── core/
│   ├── model/                   # Session、Entry、ScanStep、Result
│   ├── matching/                # QR/Code 128 parser、照合、共通fixture loader
│   ├── data/                    # repositories、Room、DataStore
│   └── designsystem/            # theme、tokens、共通Compose部品
├── feature/
│   ├── scan/                    # セッション開始・照合・結果
│   ├── history/                 # 一覧・品番・箱詳細・PDF
│   └── settings/                # 音、言語、自動次工程、BLE設定UI
├── scanner/
│   ├── api/                     # Camera/BLE共通の入力契約
│   ├── camera/                  # CameraX + ML Kit
│   ├── fake/                    # UI・CI・BLE先行開発用
│   └── ble/                     # SDK/UUID非依存の安全コアと将来のAndroid adapter境界
├── gradle/libs.versions.toml
├── settings.gradle.kts
└── README.md
```

`shared/`にはKotlinコードを置かない。共通仕様、JSON fixture、テスト画像、将来の共通デザイン資源だけを置き、各OSのランタイムコードはネイティブに保つ。

## 5. UI / UXデザイン方針

### 5.1 デザイントークン

Swift版のブランド色をMaterial 3の意味的な役割へ割り当てる。

| Swiftトークン | 値 | Material 3での主用途 |
|---|---:|---|
| ink | `#151B18` | `onSurface`、セッション状態バー |
| muted | `#65706A` | `onSurfaceVariant` |
| paper | `#F4F3EC` | `background` |
| green | `#0E7C58` | `primary`、主要操作、成功 |
| lime | `#C8F36A` | 強調、セッション終了、成功時補助 |
| red | `#D44636` | `error`、不一致 |
| amber | `#E09620` | 警告、BLE案内 |
| line | `#D8DCD6` | `outlineVariant` |

業務中の成功・失敗認識と両OSの一貫性を優先し、Dynamic Colorは初期リリースでは無効にする。ライトテーマをSwift版パリティの初期対象とし、ダークテーマはパリティ完了後の独立要件にする。

### 5.2 Androidらしさを出す箇所

- 主要画面は`Scaffold`、`TopAppBar`、`NavigationBar`/`NavigationRail`を使う。
- ボタン、Switch、Slider、Segmented Button、AlertDialogはMaterial 3標準の押下表現とrippleを使う。
- スマートフォンでは履歴を一覧→詳細、タブレットでは`ListDetailPaneScaffold`で並列表示する。
- 結果後の「次の照合」は`BottomAppBar`相当の固定領域へ置き、ジェスチャーナビゲーション領域と重ねない。
- Androidの予測型「戻る」で、詳細→一覧、ガイド→設定へ戻る。照合中にアプリを終了させる操作には確認を残す。
- 角丸と影はSwift版の柔らかさを残すが、影を過度に重ねずMaterial 3のsurfaceとtonal elevationを優先する。
- 日本語はNoto Sans JP、英数字はRoboto系のシステムフォントを使い、品番とpayloadだけ等幅にする。

### 5.3 画面別構成

#### 照合開始

- `SCAN & VERIFY`、大見出し、説明、セッションカードの情報階層を維持
- 任意名は`OutlinedTextField`
- 「記録を開始する」は画面幅いっぱい、最低56dp、明確なprimary action
- 保存済みセッション件数は補助情報として下部へ表示

#### 照合中

- 上部にセッション名、一致件数、終了ボタン、自動次工程設定
- 3段階Stepperを常時表示
- カメラ領域は4:3を基本にし、QRは正方形、Code 128は横長ガイド
- Camera/Bluetooth切替はBLE ready時だけSegmented Buttonで表示
- 読み取った品番を優先して見せ、全文は折りたたみまたは副次情報
- 一致は緑のチェック、不一致は赤の警告を使い、色だけでなく文言・アイコン・音・触覚を併用
- カウントダウン中も「今すぐ次の照合へ」を常に押せる

#### 履歴

- 空状態、セッション一覧、セッション詳細、品番グループ、箱詳細の5状態
- スワイプ削除に加え、Androidの長押し/overflow操作でも削除可能にし、誤操作防止のUndo Snackbarを検討する
- payloadは選択・コピー可能な等幅表示
- PDF保存はシステムの保存先選択、共有はAndroid Sharesheet

#### 設定

- BLE、成功時の自動次工程、音量、成功音、失敗音、言語をカード/sectionで分離
- 音の行は選択と試聴を1操作で行い、選択状態をradio/checkで明示
- 言語はアプリ内PickerとAndroidのper-app languageを同期
- BLE未実装期間は開発ビルドでFake接続を利用できる。一般向けビルドで動かない接続ボタンは出さず、「カメラ入力を利用」の明確な状態を表示する

### 5.4 アクセシビリティ

- すべての操作を48dp以上、主要操作は56〜64dpとする。
- Stepper、結果、カウントダウン、セッション件数は`semantics`でまとまりとして読み上げる。
- カメラ枠やフォーカス表示には、同等のボタン操作と説明を用意する。
- TalkBack、Switch Access、キーボード/D-pad、フォント倍率1.0/1.3/2.0を試験する。
- WCAG相当のコントラストをトークンごとに測定し、green/lime上の文字色を固定する。
- [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)

## 6. ドメインと状態遷移

### 6.1 純Kotlinの共通ドメイン

次をAndroid frameworkから分離した純Kotlinとして実装する。

- `CodeMatcher.normalize`
- `partNumberFromQr`
- `partNumberFromBarcode`
- `KanbanQrRecord.parse/isValidScanPayload`
- `TagBarcodeRecord.parse/isValidScanPayload`
- `compare`
- `formatPartNumber`
- 品番ごとの箱グルーピング

最初のテストはSwift版の単体テストと同名または対応名にし、`shared/test-fixtures/matching-cases.json`を必ず読み込む。

### 6.2 照合状態機械

```text
Idle
  └─ StartSession → WaitingForQr

WaitingForQr
  ├─ valid QR → WaitingForCode128(qr)
  ├─ Code 128 / invalid → WaitingForQr + invalid feedback
  └─ EndSession → Idle

WaitingForCode128(qr)
  ├─ valid Code 128 → MatchResult(match|mismatch)
  ├─ QR / invalid → WaitingForCode128 + invalid feedback
  ├─ RereadQr → WaitingForQr
  └─ EndSession → Idle

MatchResult
  ├─ manual next → WaitingForQr
  ├─ match + enabled timer elapsed → WaitingForQr
  ├─ mismatch → result remains until manual next
  └─ EndSession → Idle
```

入力元、カメラ稼働、BLE接続、カウントダウンは状態機械に従属させ、Composable内部に業務状態を分散させない。

## 7. 保存、PDF、設定

### 7.1 Room schema

`sessions`

- `id: String`（UUID）
- `startedAt: Long`（UTC epoch millis）
- `endedAt: Long?`
- `name: String?`

`entries`

- `id: String`（UUID）
- `sessionId: String`（外部キー、cascade delete）
- `sequence: Long`（同時刻でも箱順を保持）
- `code: String`
- `matchedAt: Long`
- `qrPayload: String?`
- `barcodePayload: String?`

一致記録はトランザクションで追加する。DB migration testを最初のschemaから用意し、active sessionはアプリ再起動後も継続できるようにする。

### 7.2 DataStore

- `autoAdvanceEnabled = false`
- `autoAdvanceDelaySeconds = 3`
- `feedbackVolume = 1.0`
- `successSound = posBeep`
- `failureSound`の初期値はPhase 0で確定する。Swift版は再生側が`alarm`、設定画面の未保存時表示が`buzzer`で一致していないため、Androidへ不整合を持ち込まずiOS側も同じ決定へ揃える
- per-app language（日本語初期値）
- 将来のBLE既知デバイスIDと安全復旧状態
- BLE symbology recovery stateは`scanner:ble`の
  `BleSymbologySnapshotStore`へ分離する。Preferences DataStoreのファイル名は
  `codematch-ble-symbology.preferences_pb`（`files/datastore/`配下）とし、
  backup/D2D除外ルールにもこのファイル名を明記する。保存するのはprofile
  identity、device ID、captured time、全reported itemの`name`/`area`/`value`/
  optional flag/extrasだけで、scan payloadやraw frameは保存しない。

### 7.3 PDFと共有

- Androidの`PdfDocument`でA4縦、複数ページを生成
- Swift版と同じセッション名、開始/終了、箱数、品番数、QR解析値、管理コード、各payload、端末内生成の注記を出力
- 保存はActivity Result APIの`CreateDocument("application/pdf")`
- 共有は内部cacheへ一時生成し、`FileProvider`の`content://` URIと一時読み取り権限を使う
- [PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)
- [Secure file sharing](https://developer.android.com/training/secure-file-sharing)

## 8. カメラ実装

- CameraX `Preview`と`ImageAnalysis`を同じLifecycleへbindする。
- 背面カメラを初期選択し、`PreviewView`を`AndroidView`でComposeへ埋め込む。
- ML Kitのformatを工程ごとにQRまたはCode 128だけへ限定する。
- ImageAnalysisは常に最新フレーム優先とし、1フレーム処理中は次を捨てる。
- 解析座標をガイド枠へ変換し、枠外候補は受理しない。
- タップ位置をCameraXのmetering pointへ変換し、AF/AEを開始する。
- Code 128はSwift版と同じ「1.5秒以内に同一値2回」で確定する。
- 背景化、入力元切替、セッション終了、画面破棄で確実にunbindし、二重Analyzerを作らない。
- `CAMERA`権限は初めてカメラを開始した時に要求し、拒否・今後表示しない・カメラ非搭載を別状態にする。
- 画面録画中の制限は、iOSの [`CameraScanner.swift`](../../ios/CodeMatch/Services/CameraScanner.swift#L209) とテスト [`CodeMatcherTests.swift`](../../ios/CodeMatchTests/CodeMatcherTests.swift#L85) にあるiOS固有の防御策である。共通 [`PRODUCT_SPEC.md`](../PRODUCT_SPEC.md#L3) にAndroid向け要件はなく、現行Androidのcamera状態・Manifestにも対応するcapture policyはないため、[`TEST_PARITY.md`](TEST_PARITY.md#L62) では`N/A`とする。Android側に別のセキュリティ要件が追加された時だけ、独立した仕様・テスト・実機ゲートとして再評価する。

## 9. BLEを後回しにする設計

### 9.1 今すぐ実装する範囲

ハードウェアがなくても次を先行する。

- `ExternalScanner` interface
- `ScannerDevice`、`ConnectionState`、`ConfigurationState`、診断eventのmodel
- 検索、接続、切断、再接続、scan payload、設定中、失敗のFake実装
- BLE設定カード、初回GATT設定ガイド、接続端末一覧、診断表示
- 接続済み時の初期入力元、ユーザーがカメラを選んだ場合の優先維持
- 切断時に現在工程を保ったままカメラへfallback
- QR/Code 128の逆順拒否、重複callback抑止
- Swift版のBLE関連UIシナリオ（接続照合、設定画面の検索・接続）をCompose testで再現
- SDK/UUIDに依存しないcommand queue、完全snapshot、復元状態、payload decoderをJVM testで固定
- service/read/write/notify UUIDと通知decoderを注入する汎用Android `BluetoothGatt` transport
- `ExternalScanner` facade、複数listener、権限拒否・電源OFF・復元失敗・camera fallbackのFake/UI test

この段階では`scanner:ble`をreleaseアプリへ接続せず、対象scanner固有profileやvendor SDKも組み込まない。実通信形式を観測するまではiOS由来のUUIDや設定JSONをproduction前提にせず、release buildにもFakeを組み込まない。

Android版は独立Gradle projectとして新規に導入しており、旧iOS UserDefaultsを読む互換入口は持たない（[`android/README.md`](../../android/README.md#L1)、[`BleSymbologySnapshotStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleSymbologySnapshotStore.kt#L63)、[`BleKnownDeviceStore.kt`](../../android/scanner/ble/src/main/kotlin/jp/rimtty/codematch/scanner/ble/BleKnownDeviceStore.kt#L90)）。そのため、旧stuck buildのCode128-only recovery（`TEST_PARITY.md` #38）と旧diagnosticsからの既知端末migration（同 #42）はAndroidの未実装機能ではなく、iOSのproduct-history-only互換処理として`N/A`にする。新しいsnapshot/known-device保存、service再生成後の復元、対象scannerの実通信・完全復元ゲートは引き続き必要である。

### 9.2 実機入手後の調査ゲート

対象scanner固有profileとrelease接続を開始する前に次を確定する。

1. スキャナーの正確な型番、firmware、Android対応表
2. InateckのAndroid SDK/API、配布条件、ライセンス、対応ABI、target SDK制約
3. SDK経由と直接GATTのどちらが、設定取得・全種保存復元・scan通知を確実に扱えるか
4. Androidで観測したservice/characteristic UUIDとpayload形式
5. ペアリング要否とAndroid OS/OEM差

iOSで観測した`FF00`/`FF01`〜`FF05`やJSON形式は調査の手掛かりにはするが、Android実機で確認するまで確定仕様としてハードコードしない。

### 9.3 実BLE実装の必須条件

- Android 12以降は`BLUETOOTH_SCAN`と`BLUETOOTH_CONNECT`だけを必要時に要求し、位置推定をしない宣言にする。
- scanは時間制限を設け、対象発見後または画面離脱時に停止する。
- 既知デバイスへの明示的再接続を用意する。
- GATT操作は1本のcommand queueで直列化し、timeout後にcommandを重ねない。
- scannerが返す全symbology設定を`name`、`area`、値の組で保存し、固定のareaを使わない。
- 照合セッション中はQRとCode 128の両方だけを有効にした固定modeとし、論理的な入力順序はアプリ側で検証する。
- 背景化、セッション終了、手動切断、異常切断、再接続時に接続前設定へ正確に復元する。
- 復元を確認できない場合はBLEをreadyにせず、カメラへ安全にfallbackする。
- 診断には接続・設定eventだけを直近20件保存し、scan payloadを記録しない。
- [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Find BLE devices](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
- [Connect to a GATT server](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)

### 9.4 BLE実機受け入れ試験

- 初回GATT設定ガイドの3コードをAndroid画面から読み取れる
- 検索、接続、pairing、scan通知、切断、既知端末再接続
- 標準QR → Code 128で一致、不一致、逆順拒否
- 連続箱、同一payload重複callback、短時間連続トリガー
- 接続前の全symbology値を保存し、終了・背景・切断・再接続で完全復元
- 設定timeout時にcommandが重ならず、カメラへfallback
- アプリ強制終了・再起動時の制限状態回復
- BLE → camera → BLEで工程とQR値を維持
- Androidの最低2系統（Pixel系とSamsung系を優先）で確認

## 10. 音・触覚と言語

- 既存MP3の利用条件を確認し、Android `res/raw`へ同じ音源を配置する。
- 合成音は周波数、長さ、間隔をSwift版と同じcontractにし、PCM生成または同一生成済みassetで端末差を抑える。
- 短い効果音はSoundPool、生成音が必要ならAudioTrackを使い、UI threadを塞がない。
- 音量0でも触覚を残し、端末側の触覚無効設定は尊重する。
- 日本語・英語の全string resourceに同一keyを要求するlint/testを追加する。
- Android 13以降のシステム「アプリの言語」とアプリ内選択を同期し、旧OSはAndroidX互換APIを使う。
- [Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages)

## 11. プライバシーとセキュリティ

現行のAndroid固有のデータ・権限境界、検査コマンド、候補SDKの採用保留理由は、それぞれ[`PRIVACY.md`](PRIVACY.md)と[`BLE_SDK_EVALUATION.md`](BLE_SDK_EVALUATION.md)に整理する。以下の要件は、候補SDKを追加しても緩めない。

- production BLE adapter未接続の現段階では、release manifestに`INTERNET`、`ACCESS_NETWORK_STATE`、BLE/Nearby系権限を追加しない。依存ライブラリが宣言しても、アプリ側のmanifest mergeで除外する。M4で実機adapterを接続する時は、必要時の`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`だけを許可し、位置情報・広告・他のNearby権限は追加しない。
- カメラframeと読み取り画像を保存しない。
- 履歴、payload、BLE診断をanalytics/crash reportへ送らない。
- Room DB、DataStore、BLE既知端末情報をAuto Backupとdevice-to-device transferから除外する。
- BLE recovery DataStoreの実体は
  `files/datastore/codematch-ble-symbology.preferences_pb`。このファイルを
  `backup_rules.xml`と`data_extraction_rules.xml`のcloud/device-transfer双方で
  除外する（汎用`datastore/`除外に依存せず、ファイル名も固定する）。
- PDFはユーザーが保存・共有した時だけアプリ領域外へ出す。
- FileProviderは専用cache subdirectoryだけを公開し、一時読み取り権限に限定する。
- release buildでdebug/Fake scannerの入口を含めない。
- `android/scripts/verify-release-hardening.sh`で、source XML、merged release manifest、APK/AABのpermission/debug/Fake/FileProvider、依存グラフ、production sourceのカメラ画像/frame保存や不意のpayload書き出し・analytics/crash参照を機械検査する。`test-release-hardening.sh`はartifact前の再利用可能なsource-only回帰テストとする。

Gradle dependency verificationとSBOM/ライセンス出力は、現行のVersion Catalog・Gradle Wrapper・CIキャッシュと署名済みartifactの供給元を固定できるまで導入しない。lockfileや巨大な生成物を追加せず、現段階ではWrapper validation、releaseRuntimeClasspathの保存検査、checkerのsource/APK/AAB検査を再現可能なゲートとして先行する。依存verification/SBOMは供給元・検証メタデータ・ライセンスの運用方針が決まった時点で別変更として追加する。

## 12. テスト戦略

### 12.1 JVM unit test

- Swift版のmatcher/parser/format全ケース
- `matching-cases.json`全ケース
- QR 66文字、必須フィールド、数量、枝番、空白
- Code 128業務形式、管理コード、逆順拒否
- 状態機械の全遷移
- 自動次工程の1/3/5秒、停止条件、仮想時間
- 重複箱の保存・グルーピング
- PDF view modelの全項目
- BLE Fakeの接続・切断・fallback・timeout

### 12.2 Android instrumentation / Compose UI test

Swift版の5本のUIテストを少なくとも次のシナリオへ対応させる。

1. Fake BLE接続 → QR → Code 128 → 一致
2. 設定でFake scannerを検索・接続
3. 一致 → 重複箱 → reset → 不一致
4. 成功/失敗音選択 → 言語切替 → 再起動後維持
5. 自動次工程ON → countdown表示 → 次QRへ遷移

追加で、セッション開始/終了、0件破棄、履歴詳細、名前変更、削除、PDF保存/共有Intent、カメラ権限拒否を試験する。

現在のcheckoutでは、0件破棄、完了履歴の詳細・名前変更・削除を同じapp/DI/Room経路で検査し、履歴選択のActivity再生成・destination往復・compact system backも自動化した。PDFは長い履歴を実際に複数ページへ生成して全ページを`PdfRenderer`で確認し、`CreateDocument`、完全一致byte保存、専用FileProviderの共有Intent契約まで検査する。実際の保存先・共有先アプリでの受け入れは実機ゲートに残す。

### 12.3 画面とアクセシビリティ

- Compact phone、foldable相当、tabletでCompose screenshotを保存
- 日本語/英語、フォント倍率1.0/1.3/2.0
- TalkBack読み上げ順、重複読み上げ、操作名、状態
- Accessibility ScannerとCompose semantics tree
- screenshot差分はレイアウト退行の補助証拠とし、機能試験の代わりにはしない
- 現行のCompose testではカメラ枠のsemantics focus action、主要タッチ領域、履歴のcompact/expanded両レイアウトを検査する。TalkBack、font scale、Switch Access、Accessibility Scannerの実端末結果は[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)へ記録する。

### 12.4 実機

- エミュレーターはCIの継続証拠とし、API 31（対応下限）とGitHub Linux x86_64で提供される最新runtime（現時点はAPI 36）の両方で主要フローを確認する。compile/target SDK 37はbuild jobで保証し、API 37 runtimeは提供済みのApple Silicon用imageをローカルで確認する。カメラ完了判定は実Android端末で行う。
- Pixel 7（Android 16 / API 36）または同等の実Android端末でCameraX/ML Kit、タップフォーカス、回転、背景復帰、音・触覚を確認し、実施端末と結果を記録する。未接続時は未実施とする。
- 可能ならSamsung系を加え、カメラと省電力/OEM差を確認する。
- BLE完了判定は対象scanner実機なしでは行わない。
- 実機の手順、証跡テンプレート、未実施項目の扱いは[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)に従う。エミュレーター・CIの成功をカメラ実読取やBLE成功の証拠へ読み替えない。

## 13. CI計画

`.github/workflows/android-ci.yml`をiOS CIと独立して追加する。

必須job:

すべてのGradle実行jobは、Gradleを起動する前に同じ `Gradle wrapper validation` を通過させる。これにより、debug build、instrumentation、release artifactの各経路が未検証のWrapperを実行しない。

1. `android-unit-lint`
   - `assembleDebug`
   - `lintDebug`
   - `testDebugUnitTest`
   - 共通fixtureのSwift/Kotlin期待値一致
2. `android-emulator-test`
   - API 31とhosted Linuxで利用可能な最新runtime（現時点はAPI 36）を固定し、下限互換性と直近runtime互換性を確認
   - compile/target SDK 37はbuild job、API 37 runtimeはローカルApple Silicon emulatorで補完
   - `connectedDebugAndroidTest`
   - Compose UI testとRoom migration test
3. `android-release-build`
   - Fake scannerがrelease dependency graphへ入っていないこと
   - `assembleRelease`と`bundleRelease`
   - `scripts/verify-release-hardening.sh`でrelease APK/AABと`releaseRuntimeClasspath`を検査し、現段階のネットワーク/Nearby権限、debug/Fake入口、広すぎるFileProvider、画像/frame/payload保存、analytics/crash依存を拒否（M4でproduction BLEを接続する場合は`--allow-production-ble-permissions`でSCAN/CONNECTのみ許可）
   - `scripts/test-release-hardening.sh`でartifact前のsource-only規則（backup参照、FileProvider参照、allowBackup、Nearby権限）を回帰検査

Gradle cache key、workflow concurrency、artifact名は`android-` prefixとし、`ios-ci.yml`と相互にcancelしない。

## 14. 実装フェーズと見積り

1人で実装する場合の粗い目安。レビュー、ストア申請、BLE SDK提供待ちは含めない。

| Phase | 内容 | 目安 | 完了ゲート |
|---|---|---:|---|
| 0 | 仕様凍結、画面・状態・文字列・音のinventory | 2〜3人日 | 本文のパリティ表をissueへ分割、未分類機能なし |
| 1 | Gradle、module、Compose、theme、navigation、CI骨格 | 3〜5人日 | debug/release build、3 destination、CI成功 |
| 2 | 純Kotlin matcher/parser、共通fixture | 3〜4人日 | Swift/Kotlin fixture parity 100% |
| 3 | Room、DataStore、session/history repository | 4〜6人日 | 再起動・migration・0件破棄・重複箱テスト成功 |
| 4 | 全画面をFake scannerで実装、日英、adaptive UI | 6〜9人日 | 主要画面と5本のUIパリティテスト成功 |
| 5 | CameraX/ML Kit、権限、focus、lifecycle | 5〜8人日 | Android実機でQR/Code 128連続照合成功 |
| 6 | 音・触覚、PDF、保存/共有、accessibility | 4〜7人日 | 日英PDF、4 feedback状態、TalkBack確認 |
| 7 | 回帰、性能、OEM実機、release hardening | 4〜7人日 | BLE以外のDefinition of Done達成 |
| 8 | BLE調査・実装・対象scanner実機検証 | 8〜15人日+調査 | 9.4の全実機試験成功 |

BLE以外は約31〜49人日、BLEはSDKとfirmwareの不確実性を除き約8〜15人日を想定する。

## 15. マイルストーン

### M1: Domain parity

- Android projectとCIが存在
- 共通fixtureをKotlinで100%通過
- Swift版のmatcher/parser 14テスト意図をKotlin testへ対応付ける
- `core:matching`のproduction sourceに`android.*` / `androidx.*` importがなく、local JVM testで完走する

実装とテスト資産は存在する。Gradle、Lint、instrumentationの結果は実行時のログまたはPRへ紐付ける。API 31/36のhosted CI結果、Swift/Kotlin fixture parityの同一PR実行結果を確認するまでは、この記述だけでM1のCI完了とは扱わない。

### M2: UI parity on Fake

- 照合、履歴、設定の全画面
- 日英、音設定、auto-advance、PDF
- Fake camera/BLEで状態遷移とCompose UI test

M2のCompose/Fake実装、日英リソース、Room/DataStore、PDF、音・触覚、release Fake境界はこのcheckoutに含まれる。app E2Eでは0件破棄、履歴名称変更・詳細・削除、履歴選択のActivity再生成/画面往復/back stackを確認し、PDFは複数ページ実renderとSAF/FileProvider契約、一般化した失敗通知と再試行を自動検査する。scan checkpointはRoom schema v2で工程、受理済み値、結果、件数、入力元を保持し、MATCH記録と同一transactionで更新する。`core:data`のinstrumentationはテスト専用ランダムDBを各段階で閉じて再オープンし、active sessionとWAITING QR、WAITING Code 128、RESULTのcheckpoint、全設定値と言語が復元されることを検査する。これは永続ストレージ契約の証拠であり、OSのforce-stop/process kill後のアプリ再起動を実行した証拠ではない。320dp/840dpとfont scale 1.3/2.0の主要操作到達、per-app language同期も自動化した。ローカル `origin/master` に見えるPR #14を含むM2 merge commitと、現在のM3/M4開発ブランチの結果を混同しない。OS process kill/relaunchの実操作、実DocumentProvider/共有先、TalkBack、Switch Access、複数OEMの人手確認は[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)の未完了ゲートとして残る。

### M3: Camera production ready

- 実Android端末でQR → Code 128
- 権限、背景復帰、回転、focus、連続箱
- BLE以外の完全パリティへ向けたcamera production gate

実装済み: CameraX 1.6.2 Preview/ImageAnalysis、端末同梱ML Kit 17.3.0、QR/Code 128の工程別限定、`KEEP_ONLY_LATEST`とin-flight frame drop、表示枠と共通のROI、変換後四隅判定、elapsed realtime timestamp、AF/AE tap focus、権限状態、lifecycle停止、解析世代による停止後callback破棄、処理中ML Kit taskをdrainしてから論理sessionを終了する境界、同一hostでのQR読み直し後rebind。release APKから`INTERNET` / `ACCESS_NETWORK_STATE`権限が除外されることも確認済み。AABを含む継続的な検査はrelease hardening checkerで行う。

コード、CameraX/ML Kit境界、権限状態、ROI、lifecycle、semantics focus action、instrumentation testは存在する。実Android端末でのQR → Code 128実読取、タップfocus、連続箱、回転・背景復帰の受け入れ記録が揃うまでM3完了とは扱わない。実施時は[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)へ端末情報と証跡を残す。

### M4: Full parity

- 対象BLE scannerの接続、読み取り、完全設定復元
- Pixel系/Samsung系で実機回帰
- Swift版との機能対応表に未完了がない

準備済み: SDK/UUID非依存のBLE safety core、1 command直列化、3秒timeout後のtransport reset必須化、QR+Code 128固定mode、全reported item snapshot、復元完了前Ready禁止、payload parser、750ms重複境界のJVM test。snapshotと既知端末identityはbackup/D2D除外済みの同一DataStoreでversion/profileを検証し、service再生成後もfresh inventory→完全復元が終わるまでReadyにしないAndroid testを持つ。公式文書のSDK-level `status/info/name/flag/value`応答と`flag/value`書込にはstrict codecを用意し、Code 128=2008、QR=2022を識別するが、raw GATT形式とは見なさずrelease未接続にしている。設定画面の3つのCode 128は同梱ML Kitでexact decode済みだが、Android実通信adapter、実測設定profile、versionが一致するSDK契約、releaseアプリ接続、Nearby devices権限、対象scannerでの設定コード読取と受け入れ試験は未完了のため、M4完了とは扱わない。

候補SDKのライセンス、ABI、target SDK、権限、rawログ、scan callbackの静的評価も未解決であり、[`BLE_SDK_EVALUATION.md`](BLE_SDK_EVALUATION.md)に採用保留理由を記録する。対象scanner実機と正式な供給条件が確定するまで、BLE成功やfull parityを宣言しない。

## 16. Definition of Done

Androidポーティング全体は、次をすべて満たした時だけ完了とする。

- パリティ表の全適用行に、テストまたは実機記録の証拠がある。`N/A`行（現行は#6、#38、#42）は、対応不要の根拠リンクを持つ。
- `matching-cases.json`をSwift/Kotlin双方が通過する。
- 現行Swift単体テスト68本とUIテスト5本の意図がAndroid testへ対応付けられている。ただし、[`TEST_PARITY.md`](TEST_PARITY.md)で根拠を示したiOS固有・旧版互換の`N/A`（#6、#38、#42）はAndroid testの対象外とする。
- 日本語・英語、compact/expanded、通常/大フォントで主要フローが完走する。
- CameraXの照合を実Android端末で確認している。
- BLEの9.4を対象scanner実機で確認している。
- 端末外通信、画像保存、不要権限がないことをManifestと通信観測で確認している。
- Android CIと既存iOS CIが同一PRで成功する。
- release buildにFake scanner、debug menu、診断用payloadが含まれない。
- `scripts/verify-release-hardening.sh`がrelease APK/AAB、merged manifest、依存グラフ、backup/D2D規則、専用cache FileProviderを通過する。
- README、[`PRIVACY.md`](PRIVACY.md)、[`REAL_DEVICE_RUNBOOK.md`](REAL_DEVICE_RUNBOOK.md)、[`STATUS.md`](STATUS.md)が現状と一致する。

## 17. 最初に作るissue（計画時の分割）

現在の実装状況と未完了ゲートは[`STATUS.md`](STATUS.md)を正本とする。以下は計画をissueへ分割する際の一覧であり、未着手を意味しない。

1. Android Gradle/Composeプロジェクトと独立CIを追加
2. Material 3 CodeMatch design tokensと3 destination navigation
3. Kotlin matcher/parserとshared fixture parity
4. Room session/history schema、migration、repository
5. DataStore settingsと言語切替
6. Fake scannerと照合状態機械
7. Session start/scan/result Compose UI
8. History list/detail/group/box UI
9. Settings/audio/auto-advance UI
10. CameraX + bundled ML Kit scanner
11. Android音・触覚パリティ
12. A4 PDF保存・共有
13. Accessibility/adaptive UI/実機camera hardening
14. BLE Android SDK・GATT調査（scanner入手後に着手）
15. BLE実装、完全設定復元、実機受け入れ試験
