import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// セッション詳細をA4のPDFへ書き出す。
enum SessionPDFExporter {
    private static let pageSize = CGSize(width: 595.2, height: 841.8) // A4 @72dpi
    private static let margin: CGFloat = 44

    static func fileName(for session: MatchSession, locale: Locale) -> String {
        let appLanguage = AppLanguage(locale)
        let base = session.displayName.isEmpty
            ? appLanguage.formatDateTime(session.startedAt)
            : session.displayName
        let safe = base
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: " ", with: "_")
        return "\(AppLocalization.string("照合履歴"))_\(safe).pdf"
    }

    static func generatePDF(for session: MatchSession, locale: Locale) -> Data {
        let appLanguage = AppLanguage(locale)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize))
        let contentWidth = pageSize.width - margin * 2

        let titleFont = UIFont.boldSystemFont(ofSize: 20)
        let headFont = UIFont.boldSystemFont(ofSize: 12)
        let bodyFont = UIFont.systemFont(ofSize: 11)
        let monoFont = UIFont.monospacedSystemFont(ofSize: 9.5, weight: .regular)
        let monoBoldFont = UIFont.monospacedSystemFont(ofSize: 12, weight: .bold)
        let gray = UIColor(white: 0.38, alpha: 1)

        return renderer.pdfData { context in
            var cursorY: CGFloat = 0

            func beginPage() {
                context.beginPage()
                cursorY = margin
            }

            func ensureSpace(_ height: CGFloat) {
                if cursorY + height > pageSize.height - margin {
                    beginPage()
                }
            }

            @discardableResult
            func draw(_ text: String, font: UIFont, color: UIColor = .black, spacing: CGFloat = 4) -> CGFloat {
                let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
                let attributed = NSAttributedString(string: text, attributes: attributes)
                let bounds = attributed.boundingRect(
                    with: CGSize(width: contentWidth, height: .greatestFiniteMagnitude),
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    context: nil
                )
                ensureSpace(bounds.height + spacing)
                attributed.draw(
                    with: CGRect(x: margin, y: cursorY, width: contentWidth, height: ceil(bounds.height)),
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    context: nil
                )
                cursorY += ceil(bounds.height) + spacing
                return bounds.height
            }

            func drawDivider() {
                ensureSpace(10)
                let path = UIBezierPath()
                path.move(to: CGPoint(x: margin, y: cursorY + 4))
                path.addLine(to: CGPoint(x: pageSize.width - margin, y: cursorY + 4))
                UIColor(white: 0.82, alpha: 1).setStroke()
                path.lineWidth = 0.7
                path.stroke()
                cursorY += 10
            }

            beginPage()

            draw(AppLocalization.string("照合履歴レポート"), font: titleFont, spacing: 6)
            if !session.displayName.isEmpty {
                draw("\(AppLocalization.string("セッション名")): \(session.displayName)", font: headFont, spacing: 4)
            }
            draw(
                "\(AppLocalization.string("開始")): \(appLanguage.formatDateTime(session.startedAt))",
                font: bodyFont,
                color: gray,
                spacing: 2
            )
            if let endedAt = session.endedAt {
                draw(
                    "\(AppLocalization.string("終了")): \(appLanguage.formatDateTime(endedAt))",
                    font: bodyFont,
                    color: gray,
                    spacing: 2
                )
            } else {
                draw(
                    "\(AppLocalization.string("状態")): \(AppLocalization.string("照合中"))",
                    font: bodyFont,
                    color: gray,
                    spacing: 2
                )
            }
            draw(
                AppLocalization.string(
                    "検査箱数: \(session.matchedCount)箱（品番数: \(session.groupedEntries.count)）"
                ),
                font: bodyFont,
                color: gray,
                spacing: 8
            )
            drawDivider()

            if session.entries.isEmpty {
                draw(AppLocalization.string("一致したコードはありません。"), font: bodyFont, color: gray)
            }

            func quantityText(_ value: Double?) -> String {
                guard let value else { return "-" }
                return appLanguage.formatQuantity(value)
            }

            // 同一品番は1グループにまとめ、何箱検査したかがひと目でわかるようにする
            for (index, group) in session.groupedEntries.enumerated() {
                // 1グループの見出し＋詳細ブロックはまとめて改ページ判定する
                ensureSpace(150)
                let groupBoxCount = AppLocalization.string("\(group.boxCount)箱")
                draw(
                    "#\(appLanguage.formatInteger(index + 1)) \(group.code) (\(groupBoxCount))",
                    font: monoBoldFont,
                    spacing: 2
                )
                if group.boxCount > 1 {
                    draw(
                        AppLocalization.string(
                            "照合時刻: \(appLanguage.formatDateTime(group.firstMatchedAt)) 〜 \(appLanguage.formatDateTime(group.lastMatchedAt))"
                        ),
                        font: bodyFont,
                        color: gray,
                        spacing: 4
                    )
                } else {
                    draw(
                        AppLocalization.string("照合時刻: \(appLanguage.formatDateTime(group.firstMatchedAt))"),
                        font: bodyFont,
                        color: gray,
                        spacing: 4
                    )
                }

                if let qr = group.entries.compactMap({ $0.qrPayload.flatMap(KanbanQRRecord.parse) }).first {
                    let suffix = qr.partSuffix.map { AppLocalization.string("（枝番 \($0)）") } ?? ""
                    draw(AppLocalization.string("納品書情報"), font: headFont, spacing: 2)
                    draw(
                        AppLocalization.string(
                            "品目番号: \(CodeMatcher.format(partNumber: qr.partNumber))\(suffix)　カード番号: \(qr.cardNumber)"
                        ),
                        font: bodyFont,
                        spacing: 2
                    )
                    draw(
                        AppLocalization.string(
                            "納入数量: \(quantityText(qr.deliveryQuantity))　指示数: \(quantityText(qr.instructedQuantity))"
                        ),
                        font: bodyFont,
                        spacing: 2
                    )
                    draw(
                        AppLocalization.string(
                            "工場: \(qr.factoryCode ?? "-")　受入部品庫: \(qr.warehouseCode ?? "-")　供給先: \(qr.supplyPointCode ?? "-")"
                        ),
                        font: bodyFont,
                        spacing: 4
                    )
                }

                draw(AppLocalization.string("各箱の読み取り記録"), font: headFont, spacing: 2)
                for (boxIndex, entry) in group.entries.enumerated() {
                    // 箱ごとの見出し＋全文2行はまとめて改ページ判定する
                    ensureSpace(48)
                    let managementCode = entry.barcodePayload.flatMap(TagBarcodeRecord.parse)?.managementCode
                    draw(
                        AppLocalization.string(
                            "\(boxIndex + 1)箱目　照合時刻: \(appLanguage.formatDateTime(entry.matchedAt))　管理コード: \(managementCode ?? "-")"
                        ),
                        font: bodyFont,
                        spacing: 2
                    )
                    draw(
                        AppLocalization.string(
                            "QR全文: \(entry.qrPayload ?? AppLocalization.string("記録なし（旧バージョンで照合）"))"
                        ),
                        font: monoFont,
                        color: gray,
                        spacing: 2
                    )
                    draw(
                        AppLocalization.string(
                            "Code 128全文: \(entry.barcodePayload ?? AppLocalization.string("記録なし（旧バージョンで照合）"))"
                        ),
                        font: monoFont,
                        color: gray,
                        spacing: 5
                    )
                }
                drawDivider()
            }

            draw(
                AppLocalization.string("CodeMatch により生成 — このレポートは端末内のデータから作成されています。"),
                font: UIFont.systemFont(ofSize: 8.5),
                color: UIColor(white: 0.55, alpha: 1),
                spacing: 0
            )
        }
    }

    /// 共有シート用にPDFを一時ファイルへ書き出してURLを返す。
    static func writeTemporaryPDF(for session: MatchSession, locale: Locale) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(fileName(for: session, locale: locale))
        try generatePDF(for: session, locale: locale).write(to: url, options: .atomic)
        return url
    }
}

/// fileExporter(ローカル保存)用のPDFドキュメント。
struct SessionPDFDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.pdf]

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// UIActivityViewControllerのSwiftUIラッパー。Email・SMS・LINEなどの共有先を選べる。
struct ActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
