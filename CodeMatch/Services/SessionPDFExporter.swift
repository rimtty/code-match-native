import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// セッション詳細をA4のPDFへ書き出す。
enum SessionPDFExporter {
    private static let pageSize = CGSize(width: 595.2, height: 841.8) // A4 @72dpi
    private static let margin: CGFloat = 44

    static func fileName(for session: MatchSession) -> String {
        let base = session.displayName.isEmpty
            ? JPDate.dateTime(session.startedAt)
            : session.displayName
        let safe = base
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: " ", with: "_")
        return "照合履歴_\(safe).pdf"
    }

    static func generatePDF(for session: MatchSession) -> Data {
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

            draw("照合履歴レポート", font: titleFont, spacing: 6)
            if !session.displayName.isEmpty {
                draw("セッション名: \(session.displayName)", font: headFont, spacing: 4)
            }
            draw("開始: \(JPDate.dateTime(session.startedAt))", font: bodyFont, color: gray, spacing: 2)
            if let endedAt = session.endedAt {
                draw("終了: \(JPDate.dateTime(endedAt))", font: bodyFont, color: gray, spacing: 2)
            } else {
                draw("状態: 照合中", font: bodyFont, color: gray, spacing: 2)
            }
            draw("一致件数: \(session.matchedCount)件", font: bodyFont, color: gray, spacing: 8)
            drawDivider()

            if session.entries.isEmpty {
                draw("一致したコードはありません。", font: bodyFont, color: gray)
            }

            func quantityText(_ value: Double?) -> String {
                guard let value else { return "-" }
                return value.truncatingRemainder(dividingBy: 1) == 0
                    ? String(Int(value))
                    : String(format: "%.2f", value)
            }

            for (index, entry) in session.entries.enumerated() {
                // 1エントリの見出し＋詳細ブロックはまとめて改ページ判定する
                ensureSpace(150)
                draw("#\(index + 1)  \(entry.code)", font: monoBoldFont, spacing: 2)
                draw("照合時刻: \(JPDate.dateTime(entry.matchedAt))", font: bodyFont, color: gray, spacing: 4)

                if let qr = entry.qrPayload.flatMap(KanbanQRRecord.parse) {
                    let suffix = qr.partSuffix.map { "（枝番 \($0)）" } ?? ""
                    draw("納品書情報", font: headFont, spacing: 2)
                    draw(
                        "品目番号: \(CodeMatcher.format(partNumber: qr.partNumber))\(suffix)　カード番号: \(qr.cardNumber)",
                        font: bodyFont, spacing: 2
                    )
                    draw(
                        "納入数量: \(quantityText(qr.deliveryQuantity))　指示数: \(quantityText(qr.instructedQuantity))",
                        font: bodyFont, spacing: 2
                    )
                    draw(
                        "工場: \(qr.factoryCode ?? "-")　受入部品庫: \(qr.warehouseCode ?? "-")　供給先: \(qr.supplyPointCode ?? "-")",
                        font: bodyFont, spacing: 4
                    )
                }

                if let tag = entry.barcodePayload.flatMap(TagBarcodeRecord.parse) {
                    draw("現品票情報", font: headFont, spacing: 2)
                    draw(
                        "品番: \(tag.partNumber)　管理コード: \(tag.managementCode ?? "-")",
                        font: bodyFont, spacing: 4
                    )
                }

                draw("QR全文: \(entry.qrPayload ?? "記録なし（旧バージョンで照合）")", font: monoFont, color: gray, spacing: 2)
                draw("Code 128全文: \(entry.barcodePayload ?? "記録なし（旧バージョンで照合）")", font: monoFont, color: gray, spacing: 6)
                drawDivider()
            }

            draw(
                "CodeMatch により生成 — このレポートは端末内のデータから作成されています。",
                font: UIFont.systemFont(ofSize: 8.5),
                color: UIColor(white: 0.55, alpha: 1),
                spacing: 0
            )
        }
    }

    /// 共有シート用にPDFを一時ファイルへ書き出してURLを返す。
    static func writeTemporaryPDF(for session: MatchSession) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(fileName(for: session))
        try generatePDF(for: session).write(to: url, options: .atomic)
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
