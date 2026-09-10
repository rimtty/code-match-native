import Foundation
import UIKit

/// 検品レポート（1品番=1行の表）をA4縦のPDFへ書き出す。
///
/// 紙の検品表と突き合わせるための帳票なので、1箱ごとの証跡は `SessionPDFExporter` に任せ、
/// ここでは箱数と数量だけを品番順に並べる。完了判定や不足数は出さない。
enum InspectionPDFExporter {
    private static let pageSize = CGSize(width: 595.2, height: 841.8) // A4 @72dpi
    private static let margin: CGFloat = 44
    private static let footerHeight: CGFloat = 30

    /// `検品レポート_<仕向地>_<開始日時>.pdf`。セッション名ではなく仕向地と開始日時で並ぶようにする。
    static func fileName(for session: MatchSession, locale: Locale) -> String {
        SessionPDFExporter.reportFileName(prefix: AppLocalization.string("検品レポート"), session: session, locale: locale)
    }

    /// ページ番号 `n / N` を全ページに置くため2回描画する。1回目でページ数を数え、2回目で総数を印字する。
    /// 表の行は固定高さなので、両方の描画は同じ位置で改ページする。
    static func generatePDF(for session: MatchSession, locale: Locale) -> Data {
        let report = InspectionReport.make(session: session)
        let firstPass = render(report, session: session, locale: locale, totalPages: nil)
        return render(report, session: session, locale: locale, totalPages: firstPass.pageCount).data
    }

    /// 共有シート用にPDFを一時ファイルへ書き出してURLを返す。
    static func writeTemporaryPDF(for session: MatchSession, locale: Locale) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(fileName(for: session, locale: locale))
        try generatePDF(for: session, locale: locale).write(to: url, options: .atomic)
        return url
    }

    // MARK: - Rendering

    private static func render(
        _ report: InspectionReport,
        session: MatchSession,
        locale: Locale,
        totalPages: Int?
    ) -> (data: Data, pageCount: Int) {
        let appLanguage = AppLanguage(locale)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize))

        let titleFont = UIFont.boldSystemFont(ofSize: 20)
        let headFont = UIFont.boldSystemFont(ofSize: 12)
        let bodyFont = UIFont.systemFont(ofSize: 11)
        let footerFont = UIFont.systemFont(ofSize: 8.5)
        let gray = UIColor(white: 0.38, alpha: 1)
        let footerGray = UIColor(white: 0.55, alpha: 1)

        var pageCount = 0
        let data = renderer.pdfData { context in
            let writer = PDFPageWriter(context: context, pageSize: pageSize, margin: margin, footerHeight: footerHeight)
            let columns = columns(for: report.layout)

            writer.onPageEnd = { writer in
                let top = pageSize.height - margin - footerHeight + 4
                let pageText = "\(appLanguage.formatInteger(writer.pageNumber)) / \(appLanguage.formatInteger(totalPages ?? writer.pageNumber))"
                let pageWidth = ceil((pageText as NSString).size(withAttributes: [.font: footerFont]).width) + 4
                let lineHeight = ceil(footerFont.lineHeight)
                writer.drawSingleLine(
                    AppLocalization.string("検品表にあってこの一覧にない品番は、このセッションで照合されていません。"),
                    in: CGRect(x: margin - 4, y: top, width: writer.contentWidth - pageWidth - 12 + 8, height: lineHeight),
                    font: footerFont,
                    alignment: .leading,
                    color: footerGray
                )
                writer.drawSingleLine(
                    AppLocalization.string("CodeMatch により生成 — このレポートは端末内のデータから作成されています。"),
                    in: CGRect(x: margin - 4, y: top + lineHeight + 2, width: writer.contentWidth + 8, height: lineHeight),
                    font: footerFont,
                    alignment: .leading,
                    color: footerGray
                )
                writer.drawSingleLine(
                    pageText,
                    in: CGRect(x: pageSize.width - margin - pageWidth - 4, y: top, width: pageWidth + 8, height: lineHeight),
                    font: UIFont.systemFont(ofSize: 9),
                    alignment: .trailing,
                    color: footerGray
                )
            }

            writer.beginPage()

            writer.draw(AppLocalization.string("検品レポート"), font: titleFont, spacing: 6)
            if !session.displayName.isEmpty {
                writer.draw("\(AppLocalization.string("セッション名")): \(session.displayName)", font: headFont, spacing: 4)
            }
            writer.draw(
                "\(AppLocalization.string("開始")): \(appLanguage.formatDateTime(session.startedAt))",
                font: bodyFont,
                color: gray,
                spacing: 2
            )
            if let endedAt = session.endedAt {
                writer.draw(
                    "\(AppLocalization.string("終了")): \(appLanguage.formatDateTime(endedAt))",
                    font: bodyFont,
                    color: gray,
                    spacing: 2
                )
            } else {
                writer.draw(
                    "\(AppLocalization.string("状態")): \(AppLocalization.string("照合中"))",
                    font: bodyFont,
                    color: gray,
                    spacing: 2
                )
            }
            if let destination = session.resolvedDestination {
                writer.draw(
                    AppLocalization.string("仕向地: \(destination.displayName)"),
                    font: bodyFont,
                    color: gray,
                    spacing: 2
                )
            }
            writer.draw(
                AppLocalization.string("検査箱数: \(session.matchedCount)箱"),
                font: bodyFont,
                color: gray,
                spacing: 2
            )
            let countLine: String
            switch report.layout {
            case .sawai: countLine = AppLocalization.string("品番数（枝番別）: \(report.rowCount)")
            case .molten: countLine = AppLocalization.string("納品番号数: \(report.rowCount)")
            case .denso: countLine = AppLocalization.string("品番数: \(report.rowCount)")
            }
            writer.draw(countLine, font: bodyFont, color: gray, spacing: 8)
            writer.drawDivider()

            if report.rows.isEmpty {
                writer.draw(AppLocalization.string("一致したコードはありません。"), font: bodyFont, color: gray)
            } else {
                writer.drawTableHeader(columns)
                // 2ページ目以降は表ヘッダーを繰り返す。1ページ目は上で描いたので二重にならない。
                writer.onPageStart = { writer in writer.drawTableHeader(columns) }
                let checkboxColumn = columns.count - 1
                for (index, row) in report.rows.enumerated() {
                    writer.drawTableRow(
                        cells(for: row, index: index, layout: report.layout, appLanguage: appLanguage),
                        columns: columns,
                        checkboxColumn: checkboxColumn
                    )
                }
                writer.onPageStart = nil
            }

            writer.finish()
            pageCount = writer.pageNumber
        }
        return (data, pageCount)
    }

    // MARK: - Columns

    /// 列幅はポイント。本文幅 507pt に収まるよう Android と同じ値を使う。
    private static func columns(for layout: InspectionReport.Layout) -> [PDFPageWriter.Column] {
        let body = UIFont.systemFont(ofSize: 11)
        let key = UIFont.monospacedSystemFont(ofSize: 12, weight: .bold)
        func column(
            _ title: String,
            _ width: CGFloat,
            _ alignment: PDFPageWriter.Column.Alignment,
            font: UIFont? = nil
        ) -> PDFPageWriter.Column {
            PDFPageWriter.Column(title: title, width: width, alignment: alignment, font: font ?? body)
        }
        switch layout {
        case .sawai:
            return [
                column(AppLocalization.string("No"), 28, .trailing),
                column(AppLocalization.string("品番（検品列）"), 170, .leading, font: key),
                column(AppLocalization.string("箱数"), 52, .trailing, font: key),
                column(AppLocalization.string("納入数量/箱"), 90, .trailing),
                column(AppLocalization.string("数量計"), 90, .trailing),
                column(AppLocalization.string("確認"), 77, .center),
            ]
        case .denso:
            return [
                column(AppLocalization.string("No"), 28, .trailing),
                column(AppLocalization.string("品番（検品列）"), 170, .leading, font: key),
                column(AppLocalization.string("箱数"), 52, .trailing, font: key),
                column(AppLocalization.string("収容数/箱"), 90, .trailing),
                column(AppLocalization.string("数量計"), 90, .trailing),
                column(AppLocalization.string("確認"), 77, .center),
            ]
        case .molten:
            return [
                column(AppLocalization.string("No"), 28, .trailing),
                column(AppLocalization.string("納品番号（検品列）"), 82, .leading, font: key),
                column(AppLocalization.string("品番（検品列）"), 96, .leading, font: key),
                column(AppLocalization.string("納入先（検品列）"), 52, .leading),
                column(AppLocalization.string("箱数"), 44, .trailing, font: key),
                column(AppLocalization.string("収容数/箱"), 72, .trailing),
                column(AppLocalization.string("累計"), 72, .trailing),
                column(AppLocalization.string("確認"), 61, .center),
            ]
        }
    }

    private static func cells(
        for row: InspectionReportRow,
        index: Int,
        layout: InspectionReport.Layout,
        appLanguage: AppLanguage
    ) -> [String] {
        let number = appLanguage.formatInteger(index + 1)
        let boxes = appLanguage.formatInteger(row.boxCount)
        let perBox = row.quantityPerBox.map(appLanguage.formatQuantity) ?? "-"
        let total = row.totalQuantity.map(appLanguage.formatQuantity) ?? "-"
        switch layout {
        case .sawai, .denso:
            return [number, row.keyText, boxes, perBox, total, ""]
        case .molten:
            return [
                number,
                row.keyText,
                row.partNumber ?? "-",
                row.deliveryDestination ?? "-",
                boxes,
                perBox,
                total,
                "",
            ]
        }
    }
}
