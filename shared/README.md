# Shared contracts and fixtures

このディレクトリにはプラットフォーム非依存の仕様確認資源だけを置きます。Swift、Kotlin、AVFoundation、CameraX、各社Bluetooth SDKなどの実装コードは置きません。

- `test-fixtures/matching-cases.json`: Swift/Kotlin共通の照合入力と期待結果。`schemaVersion` 2、63ケースで、各ケースはQRの仕向地を示す`destination`（`sawai` / `molten` / `denso`）を持つ。いずれの様式でもないQRを扱う2ケースだけが`destination`を持たない
- `test-fixtures/images/`: 実機またはエミュレーターへ表示するQR・Code 128画像。3仕向地ぶんを置き、モルテンは `molten-` で始まる5点、デンソーは `denso-` で始まる3点
- `tools/generate_test_codes.swift`: macOSで画像を再生成する補助ツール
- `tools/decode_label_photos.swift`: 現場ラベルの写真を Vision で一括デコードし、1シンボル1行の TSV を出す（写真は git-ignored の `tmp/` に置き、コミットしない）。`ios/scripts/verify_label_pairs.sh` と組み合わせて使う（手順は `docs/label-variation-playbook.html`）

照合ルールを変える場合は、先に共通仕様とfixtureを更新し、両プラットフォームのテストへ同じ変更を反映します。
