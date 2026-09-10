import UIKit

/// A4縦1文書分の描画カーソル。ページ送り、本文テキスト、表の行、各ページのフッターをまとめて扱う。
///
/// `onPageStart` は2ページ目以降の先頭で（表ヘッダーの再描画に）、`onPageEnd` は各ページを閉じる
/// 直前で（フッターとページ番号に）呼ばれる。フッター帯 `footerHeight` には本文が入り込まない。
final class PDFPageWriter {
    struct Column {
        enum Alignment {
            case leading
            case trailing
            case center
        }

        let title: String
        let width: CGFloat
        let alignment: Alignment
        let font: UIFont
    }

    private let context: UIGraphicsPDFRendererContext
    let pageSize: CGSize
    let margin: CGFloat
    let footerHeight: CGFloat
    private(set) var cursorY: CGFloat = 0
    /// 1始まり。`beginPage()` 前は 0。
    private(set) var pageNumber = 0

    var onPageStart: ((PDFPageWriter) -> Void)?
    var onPageEnd: ((PDFPageWriter) -> Void)?

    var contentWidth: CGFloat { pageSize.width - margin * 2 }
    var contentBottom: CGFloat { pageSize.height - margin - footerHeight }

    init(context: UIGraphicsPDFRendererContext, pageSize: CGSize, margin: CGFloat, footerHeight: CGFloat) {
        self.context = context
        self.pageSize = pageSize
        self.margin = margin
        self.footerHeight = footerHeight
    }

    func beginPage() {
        if pageNumber > 0 { onPageEnd?(self) }
        context.beginPage()
        pageNumber += 1
        cursorY = margin
        onPageStart?(self)
    }

    /// 最後のページを閉じる。`onPageEnd` を最終ページにも適用する。
    func finish() {
        if pageNumber > 0 { onPageEnd?(self) }
    }

    func ensureSpace(_ height: CGFloat) {
        if cursorY + height > contentBottom {
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

    /// 表ヘッダー。薄いグレーの帯に列名を置き、下に罫線を引く。
    func drawTableHeader(_ columns: [Column], height: CGFloat = 20) {
        ensureSpace(height)
        let band = CGRect(x: margin, y: cursorY, width: contentWidth, height: height)
        UIColor(white: 0.93, alpha: 1).setFill()
        UIBezierPath(rect: band).fill()
        let font = UIFont.boldSystemFont(ofSize: 11)
        var x = margin
        for column in columns {
            drawSingleLine(
                column.title,
                in: CGRect(x: x, y: cursorY, width: column.width, height: height),
                font: font,
                alignment: column.alignment,
                color: .black
            )
            x += column.width
        }
        strokeRule(at: cursorY + height, color: UIColor(white: 0.82, alpha: 1), lineWidth: 0.7)
        cursorY += height
    }

    /// 表の1行。`checkboxColumn` の列は文字列の代わりに手書き用の空の四角を描く。
    func drawTableRow(_ cells: [String], columns: [Column], height: CGFloat = 22, checkboxColumn: Int? = nil) {
        precondition(cells.count == columns.count, "row has \(cells.count) cells for \(columns.count) columns")
        ensureSpace(height)
        var x = margin
        for (index, column) in columns.enumerated() {
            let cell = CGRect(x: x, y: cursorY, width: column.width, height: height)
            if index == checkboxColumn {
                drawCheckbox(in: cell)
            } else {
                drawSingleLine(cells[index], in: cell, font: column.font, alignment: column.alignment, color: .black)
            }
            x += column.width
        }
        strokeRule(at: cursorY + height, color: UIColor(white: 0.88, alpha: 1), lineWidth: 0.4)
        cursorY += height
    }

    /// 1行だけを枠内に描き、収まらない分は末尾を省略する。カーソルは動かさない。
    func drawSingleLine(
        _ text: String,
        in rect: CGRect,
        font: UIFont,
        alignment: Column.Alignment,
        color: UIColor
    ) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        switch alignment {
        case .leading: paragraph.alignment = .left
        case .trailing: paragraph.alignment = .right
        case .center: paragraph.alignment = .center
        }
        let attributed = NSAttributedString(
            string: text,
            attributes: [.font: font, .foregroundColor: color, .paragraphStyle: paragraph]
        )
        let inset = rect.insetBy(dx: Self.cellPadding, dy: 0)
        let textHeight = ceil(font.lineHeight)
        let y = inset.midY - textHeight / 2
        attributed.draw(
            with: CGRect(x: inset.minX, y: y, width: inset.width, height: textHeight),
            options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
            context: nil
        )
    }

    private func drawCheckbox(in cell: CGRect) {
        let size = Self.checkboxSize
        let box = CGRect(x: cell.midX - size / 2, y: cell.midY - size / 2, width: size, height: size)
        let path = UIBezierPath(rect: box)
        UIColor(white: 0.35, alpha: 1).setStroke()
        path.lineWidth = 0.8
        path.stroke()
    }

    private func strokeRule(at y: CGFloat, color: UIColor, lineWidth: CGFloat) {
        let path = UIBezierPath()
        path.move(to: CGPoint(x: margin, y: y))
        path.addLine(to: CGPoint(x: pageSize.width - margin, y: y))
        color.setStroke()
        path.lineWidth = lineWidth
        path.stroke()
    }

    private static let cellPadding: CGFloat = 4
    private static let checkboxSize: CGFloat = 9
}
