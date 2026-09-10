import SwiftUI

struct HistoryScreen: View {
    @ObservedObject var historyStore: HistoryStore
    @Environment(\.locale) private var locale

    @State private var shareItem: ShareItem?
    @State private var showsExportError = false

    private struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    var body: some View {
        NavigationStack {
            Group {
                if historyStore.sessions.isEmpty {
                    ContentUnavailableView(
                        AppLocalization.string("履歴はまだありません"),
                        systemImage: "clock.arrow.circlepath",
                        description: Text(
                            AppLocalization.string(
                                "照合タブで記録を開始すると、一致したコードがセッション単位で保存されます。"
                            )
                        )
                    )
                } else {
                    List {
                        ForEach(historyStore.sessions) { session in
                            NavigationLink {
                                SessionHistoryDetail(historyStore: historyStore, sessionID: session.id)
                            } label: {
                                SessionHistoryRow(session: session)
                            }
                        }
                        .onDelete { offsets in
                            historyStore.deleteSessions(at: offsets)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle(AppLocalization.string("照合履歴"))
            .background(AppTheme.paper)
            .accessibilityIdentifier("historyScreen")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        shareAllHistory()
                    } label: {
                        Label(
                            AppLocalization.string("照合履歴をすべて共有"),
                            systemImage: "square.and.arrow.up"
                        )
                    }
                    .disabled(historyStore.sessions.isEmpty)
                    .accessibilityIdentifier("shareAllHistoryButton")
                }
            }
            .sheet(item: $shareItem) { item in
                ActivityShareSheet(items: [item.url])
                    .presentationDetents([.medium, .large])
            }
            .alert(
                AppLocalization.string("照合履歴の書き出しに失敗しました。"),
                isPresented: $showsExportError
            ) {
                Button(AppLocalization.string("閉じる"), role: .cancel) {}
            }
        }
    }

    /// 全セッションを1つのJSONへ書き出して共有シートに渡す。
    private func shareAllHistory() {
        guard !historyStore.sessions.isEmpty else { return }
        do {
            shareItem = ShareItem(url: try HistoryExporter.writeTemporaryJSON(sessions: historyStore.sessions))
        } catch {
            showsExportError = true
        }
    }
}

private struct SessionHistoryRow: View {
    let session: MatchSession
    @Environment(\.locale) private var locale

    private var appLanguage: AppLanguage {
        locale.appLanguage
    }

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 2) {
                Text(appLanguage.formatInteger(session.matchedCount))
                    .font(.title2.weight(.bold))
                Text(AppLocalization.string("箱"))
                    .font(.caption2.weight(.bold))
            }
            .foregroundStyle(session.isActive ? AppTheme.green : AppTheme.ink)
            .frame(width: 52, height: 52)
            .background(
                (session.isActive ? AppTheme.green : AppTheme.ink).opacity(0.08),
                in: RoundedRectangle(cornerRadius: 13)
            )

            VStack(alignment: .leading, spacing: 5) {
                if !session.displayName.isEmpty {
                    Text(session.displayName)
                        .font(.subheadline.weight(.bold))
                        .lineLimit(1)
                }
                Text(appLanguage.formatDateTime(session.startedAt))
                    .font(session.displayName.isEmpty ? .subheadline.weight(.semibold) : .caption)
                    .foregroundStyle(session.displayName.isEmpty ? AppTheme.ink : AppTheme.muted)
                if let destination = session.resolvedDestination {
                    Text(AppLocalization.string("仕向地: \(destination.displayName)"))
                        .font(.caption)
                        .foregroundStyle(AppTheme.muted)
                }
                Text(session.isActive ? AppLocalization.string("照合中のセッション") : sessionDurationText)
                    .font(.caption)
                    .foregroundStyle(session.isActive ? AppTheme.green : AppTheme.muted)
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilitySummary)
        .accessibilityIdentifier("historySessionRow")
    }

    private var sessionDurationText: String {
        guard let endedAt = session.endedAt else { return "" }
        let minutes = max(1, Int(endedAt.timeIntervalSince(session.startedAt) / 60))
        return AppLocalization.string("終了済み・約\(minutes)分")
    }

    private var accessibilitySummary: String {
        let date = appLanguage.formatDateTime(session.startedAt)
        let boxCount = AppLocalization.string("\(session.matchedCount)箱")
        let status = session.isActive ? AppLocalization.string("照合中") : AppLocalization.string("終了済み")
        switch (session.displayName.isEmpty, session.resolvedDestination?.displayName) {
        case (true, nil):
            return AppLocalization.string("\(date)、\(boxCount)、\(status)")
        case (true, let destination?):
            return AppLocalization.string("\(date)、\(destination)、\(boxCount)、\(status)")
        case (false, nil):
            return AppLocalization.string("\(session.displayName)、\(date)、\(boxCount)、\(status)")
        case (false, let destination?):
            return AppLocalization.string(
                "\(session.displayName)、\(date)、\(destination)、\(boxCount)、\(status)"
            )
        }
    }
}

private struct SessionHistoryDetail: View {
    @ObservedObject var historyStore: HistoryStore
    let sessionID: UUID
    @Environment(\.locale) private var locale

    private var appLanguage: AppLanguage {
        locale.appLanguage
    }

    @State private var editedName = ""
    @State private var shareItem: ShareItem?
    @State private var showsExporter = false
    @State private var exportDocument: SessionPDFDocument?
    /// fileExporter は1つなので、直前に選んだレポートのファイル名をここに持つ。
    @State private var exportFileName: String?
    /// 「共有する」で開くメール作成画面。「メール」が使えない端末では shareItem に切り替える。
    @State private var mailItem: MailItem?

    private struct MailItem: Identifiable {
        let id = UUID()
        let content: ReportMailContent
        let attachment: MailComposeView.Attachment
    }

    private struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    var body: some View {
        Group {
            if let session {
                List {
                    Section(AppLocalization.string("セッション名")) {
                        TextField(
                            AppLocalization.string("名前を入力（任意）"),
                            text: $editedName
                        )
                        .submitLabel(.done)
                        .onSubmit { commitName() }
                        .accessibilityIdentifier("sessionNameEditField")
                    }

                    Section {
                        LabeledContent(
                            AppLocalization.string("開始"),
                            value: appLanguage.formatDateTime(session.startedAt)
                        )
                        if let endedAt = session.endedAt {
                            LabeledContent(
                                AppLocalization.string("終了"),
                                value: appLanguage.formatDateTime(endedAt)
                            )
                        } else {
                            LabeledContent(
                                AppLocalization.string("状態"),
                                value: AppLocalization.string("照合中")
                            )
                            .foregroundStyle(AppTheme.green)
                        }
                        if let destination = session.resolvedDestination {
                            LabeledContent(
                                AppLocalization.string("仕向地"),
                                value: destination.displayName
                            )
                            .accessibilityIdentifier("historySessionDestination")
                        }
                        LabeledContent(
                            AppLocalization.string("検査箱数"),
                            value: AppLocalization.string("\(session.matchedCount)箱")
                        )
                        LabeledContent(
                            AppLocalization.string("品番数"),
                            value: AppLocalization.string("\(session.groupedEntries.count)品番")
                        )
                        // モルテンは同じ品番でも納品番号ごとに納品書が分かれるため、種類数も添える
                        if session.resolvedDestination == .molten {
                            LabeledContent(
                                AppLocalization.string("納品番号数"),
                                value: appLanguage.formatInteger(session.deliveryNumberCount)
                            )
                        }
                    }

                    Section {
                        // 検品レポートを先に置く。紙の検品表と突き合わせる帳票で、照合履歴PDFは証跡。
                        PDFActionRow(
                            caption: AppLocalization.string("検品レポート"),
                            saveIdentifier: "saveInspectionReportButton",
                            shareIdentifier: "shareInspectionReportButton",
                            onSave: {
                                exportFileName = InspectionPDFExporter.fileName(for: session, locale: locale)
                                exportDocument = SessionPDFDocument(
                                    data: InspectionPDFExporter.generatePDF(for: session, locale: locale)
                                )
                                showsExporter = true
                            },
                            onShare: { shareReport(.inspection, session: session) }
                        )
                        PDFActionRow(
                            caption: AppLocalization.string("照合履歴レポート"),
                            saveIdentifier: "savePDFButton",
                            shareIdentifier: "sharePDFButton",
                            onSave: {
                                exportFileName = SessionPDFExporter.fileName(for: session, locale: locale)
                                exportDocument = SessionPDFDocument(
                                    data: SessionPDFExporter.generatePDF(for: session, locale: locale)
                                )
                                showsExporter = true
                            },
                            onShare: { shareReport(.matchHistory, session: session) }
                        )
                    }

                    Section(AppLocalization.string("一致したコード")) {
                        if session.entries.isEmpty {
                            ContentUnavailableView(
                                AppLocalization.string("一致履歴はありません"),
                                systemImage: "barcode",
                                description: Text(AppLocalization.string("このセッションではまだ一致したコードがありません。"))
                            )
                        } else {
                            // 同一品番のラベルが複数箱に貼られる運用のため、品番ごとにまとめて箱数を表示する
                            ForEach(Array(session.groupedEntries.enumerated()), id: \.element.id) { index, group in
                                NavigationLink {
                                    GroupedMatchDetail(group: group, number: index + 1)
                                } label: {
                                    VStack(alignment: .leading, spacing: 7) {
                                        HStack {
                                            Text("#\(index + 1)")
                                                .font(.caption.weight(.bold))
                                                .foregroundStyle(AppTheme.green)
                                            Spacer()
                                            Text(matchedTimeText(for: group))
                                                .font(.caption)
                                                .foregroundStyle(AppTheme.muted)
                                        }
                                        HStack {
                                            Text(group.code)
                                                .font(.system(.body, design: .monospaced, weight: .semibold))
                                            Spacer()
                                            Text(AppLocalization.string("\(group.boxCount)箱"))
                                                .font(.caption.weight(.bold))
                                                .foregroundStyle(AppTheme.green)
                                                .padding(.horizontal, 9)
                                                .padding(.vertical, 4)
                                                .background(AppTheme.green.opacity(0.1), in: Capsule())
                                        }
                                    }
                                    .padding(.vertical, 5)
                                }
                                .accessibilityIdentifier("matchEntryRow")
                            }
                        }
                    }
                }
            } else {
                ContentUnavailableView(AppLocalization.string("履歴が見つかりません"), systemImage: "exclamationmark.triangle")
            }
        }
        .navigationTitle(navigationTitleText)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { editedName = session?.displayName ?? "" }
        .onDisappear { commitName() }
        .fileExporter(
            isPresented: $showsExporter,
            document: exportDocument,
            contentType: .pdf,
            defaultFilename: exportFileName
                ?? session.map { SessionPDFExporter.fileName(for: $0, locale: locale) }
                ?? "\(AppLocalization.string("照合履歴")).pdf"
        ) { _ in
            exportDocument = nil
            exportFileName = nil
        }
        .sheet(item: $shareItem) { item in
            ActivityShareSheet(items: [item.url])
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $mailItem) { item in
            MailComposeView(content: item.content, attachment: item.attachment) {
                mailItem = nil
            }
            .ignoresSafeArea()
        }
    }

    private var session: MatchSession? {
        historyStore.sessions.first(where: { $0.id == sessionID })
    }

    /// 「共有する」: 宛先・件名・本文・PDF添付を埋めたメール作成画面を開く。
    /// 「メール」にアカウントがない端末では従来どおりの共有シートに切り替える。
    private func shareReport(_ kind: ReportKind, session: MatchSession) {
        if MailComposeView.canSendMail {
            let data: Data
            let fileName: String
            switch kind {
            case .inspection:
                data = InspectionPDFExporter.generatePDF(for: session, locale: locale)
                fileName = InspectionPDFExporter.fileName(for: session, locale: locale)
            case .matchHistory:
                data = SessionPDFExporter.generatePDF(for: session, locale: locale)
                fileName = SessionPDFExporter.fileName(for: session, locale: locale)
            }
            mailItem = MailItem(
                content: ReportMailContent.make(session: session, kind: kind, fileName: fileName, locale: locale),
                attachment: MailComposeView.Attachment(data: data, fileName: fileName)
            )
            return
        }
        let url: URL?
        switch kind {
        case .inspection: url = try? InspectionPDFExporter.writeTemporaryPDF(for: session, locale: locale)
        case .matchHistory: url = try? SessionPDFExporter.writeTemporaryPDF(for: session, locale: locale)
        }
        shareItem = url.map { ShareItem(url: $0) }
    }

    private var navigationTitleText: String {
        let name = session?.displayName ?? ""
        return name.isEmpty ? AppLocalization.string("セッション詳細") : name
    }

    private func commitName() {
        guard let session,
              session.displayName != editedName.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        historyStore.renameSession(id: sessionID, name: editedName)
    }

    private func matchedTimeText(for group: GroupedMatchEntry) -> String {
        group.boxCount > 1
            ? AppLocalization.string("開始") + ": \(appLanguage.formatTime(group.firstMatchedAt)) 〜 " +
            AppLocalization.string("終了") + ": \(appLanguage.formatTime(group.lastMatchedAt))"
            : AppLocalization.string("照合時刻") + ": \(appLanguage.formatTime(group.firstMatchedAt))"
    }
}

/// 同一品番のグループ詳細。何箱検査したかと、各箱の照合記録を一覧する。
private struct GroupedMatchDetail: View {
    let group: GroupedMatchEntry
    let number: Int
    @Environment(\.locale) private var locale

    private var appLanguage: AppLanguage {
        locale.appLanguage
    }

    var body: some View {
        // deliveryGroups はアクセスのたびにQR全文を解析し直すため、body先頭で1度だけ取り出す
        let deliveryGroups = group.deliveryGroups
        // 納品番号を持たない記録が1件でも混ざるグループは、記録を落とさないよう従来どおり1節にまとめる
        let showsDeliveryGroups = !deliveryGroups.isEmpty
            && deliveryGroups.reduce(0) { $0 + $1.entries.count } == group.entries.count
        // デンソーは同じ品番の箱がかんばん連番でしか区別できないので、管理コードを添えて見分けやすくする
        let showsManagementCodePerBox = group.entries.contains { $0.densoRecord != nil }

        List {
            Section {
                LabeledContent(AppLocalization.string("番号"), value: "#\(number)")
                LabeledContent(
                    AppLocalization.string("検査箱数"),
                    value: AppLocalization.string("\(group.boxCount)箱")
                )
                LabeledContent(
                    AppLocalization.string("最初の照合"),
                    value: appLanguage.formatDateTime(group.firstMatchedAt)
                )
                if group.boxCount > 1 {
                    LabeledContent(
                        AppLocalization.string("最後の照合"),
                        value: appLanguage.formatDateTime(group.lastMatchedAt)
                    )
                }
            }

            Section(AppLocalization.string("品目番号")) {
                Text(group.code)
                    .font(.system(.title3, design: .monospaced, weight: .bold))
                    .textSelection(.enabled)
            }

            if showsDeliveryGroups {
                // モルテンは同じ品番でも納品書(納品番号)ごとに箱数と累計が分かれる
                ForEach(deliveryGroups) { deliveryGroup in
                    Section(
                        AppLocalization.string(
                            "納品番号 \(deliveryGroup.deliveryNumber)（\(deliveryGroup.boxCount)箱・累計 \(deliveryGroup.totalQuantity)個）"
                        )
                    ) {
                        ForEach(Array(deliveryGroup.entries.enumerated()), id: \.element.id) { index, entry in
                            boxEntryRow(entry: entry, number: index + 1, showsManagementCode: true)
                        }
                    }
                }
            } else {
                Section(AppLocalization.string("各箱の照合記録")) {
                    ForEach(Array(group.entries.enumerated()), id: \.element.id) { index, entry in
                        boxEntryRow(
                            entry: entry,
                            number: index + 1,
                            showsManagementCode: showsManagementCodePerBox
                        )
                    }
                }
            }
        }
        .navigationTitle(group.code)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// 1箱分の行。モルテンとデンソーは箱の見分けが付きにくいため、管理コードを添える。
    private func boxEntryRow(
        entry: MatchHistoryEntry,
        number: Int,
        showsManagementCode: Bool = false
    ) -> some View {
        let managementCode = showsManagementCode
            ? entry.barcodePayload.flatMap(TagBarcodeRecord.parse)?.managementCode
            : nil

        return NavigationLink {
            MatchEntryDetail(entry: entry, number: number)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(AppLocalization.string("\(number)箱目"))
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(AppTheme.green)
                    Spacer()
                    Text(appLanguage.formatTime(entry.matchedAt))
                        .font(.caption)
                        .foregroundStyle(AppTheme.muted)
                }
                if let managementCode {
                    Text(AppLocalization.string("管理コード") + ": \(managementCode)")
                        .font(.caption)
                        .foregroundStyle(AppTheme.muted)
                }
            }
            .padding(.vertical, 3)
        }
        .accessibilityIdentifier("boxEntryRow")
    }
}

private struct MatchEntryDetail: View {
    let entry: MatchHistoryEntry
    let number: Int
    @Environment(\.locale) private var locale

    private var appLanguage: AppLanguage {
        locale.appLanguage
    }

    var body: some View {
        List {
            Section {
                LabeledContent(
                    AppLocalization.string("箱"),
                    value: AppLocalization.string("#\(number)箱目")
                )
                LabeledContent(AppLocalization.string("照合時刻"), value: appLanguage.formatDateTime(entry.matchedAt))
            }

            Section(AppLocalization.string("品目番号")) {
                Text(entry.code)
                    .font(.system(.title3, design: .monospaced, weight: .bold))
                    .textSelection(.enabled)
            }

            // 澤井製作所の解析は寛容でデンソーのQRも受理してしまうため、
            // 仕向地ガード付きの `kanbanRecord` から順に分岐する。
            if let qr = entry.kanbanRecord {
                Section(AppLocalization.string("納品書情報（QR解析）")) {
                    LabeledContent(
                        AppLocalization.string("カード番号"),
                        value: qr.cardNumber
                    )
                    LabeledContent(
                        AppLocalization.string("品目番号"),
                        value: CodeMatcher.format(partNumber: qr.partNumber, destination: .sawai)
                            + (qr.partSuffix.map { "（\(AppLocalization.string("枝番")) \($0)）" } ?? "")
                    )
                    LabeledContent(AppLocalization.string("納入数量"), value: quantityText(qr.deliveryQuantity))
                    LabeledContent(AppLocalization.string("指示数"), value: quantityText(qr.instructedQuantity))
                    LabeledContent(AppLocalization.string("工場"), value: qr.factoryCode ?? "-")
                    LabeledContent(AppLocalization.string("受入部品庫"), value: qr.warehouseCode ?? "-")
                    LabeledContent(AppLocalization.string("供給先"), value: qr.supplyPointCode ?? "-")
                }
            } else if let qr = entry.moltenRecord {
                Section(AppLocalization.string("納品書情報（QR解析）")) {
                    LabeledContent(AppLocalization.string("受注者"), value: qr.ordererCode)
                    LabeledContent(
                        AppLocalization.string("部品番号"),
                        value: CodeMatcher.format(partNumber: qr.partNumber, destination: .molten)
                    )
                    LabeledContent(AppLocalization.string("納品番号"), value: qr.deliveryNumber)
                    LabeledContent(AppLocalization.string("納入先"), value: qr.deliveryDestination)
                    LabeledContent(AppLocalization.string("TYロケーション"), value: qr.tyLocation ?? "-")
                    LabeledContent(AppLocalization.string("供給先"), value: qr.supplyPoint)
                    LabeledContent(
                        AppLocalization.string("収容数"),
                        value: appLanguage.formatInteger(qr.packQuantity)
                    )
                    LabeledContent(
                        AppLocalization.string("納入指示日(JUMP)"),
                        value: qr.formattedInstructionDate
                    )
                    LabeledContent(
                        AppLocalization.string("時刻"),
                        value: qr.formattedInstructionTime ?? "-"
                    )
                }
            } else if let qr = entry.densoRecord {
                // デンソーのかんばんは項目構成が変わりうるので、無い項目は行ごと出さない。
                Section(AppLocalization.string("納品書情報（QR解析）")) {
                    if let formType = qr.formType {
                        LabeledContent(AppLocalization.string("帳票区分"), value: formType)
                    }
                    LabeledContent(
                        AppLocalization.string("部品番号"),
                        value: CodeMatcher.format(partNumber: qr.partNumber, destination: .denso)
                    )
                    if let packagingCode = qr.packagingCode {
                        LabeledContent(AppLocalization.string("包装"), value: packagingCode)
                    }
                    LabeledContent(
                        AppLocalization.string("収容数"),
                        value: appLanguage.formatInteger(qr.packQuantity)
                    )
                    if let nextProcess = qr.nextProcess {
                        LabeledContent(AppLocalization.string("次区"), value: nextProcess)
                    }
                    if let instructionCode = qr.instructionCode {
                        LabeledContent(AppLocalization.string("指示"), value: instructionCode)
                    }
                    LabeledContent(
                        AppLocalization.string("かんばん連番"),
                        value: qr.kanbanSerial
                    )
                    if let managementNumber = qr.managementNumber {
                        LabeledContent(AppLocalization.string("管理番号"), value: managementNumber)
                    }
                    if let deliveryDate = qr.formattedDeliveryDate {
                        LabeledContent(AppLocalization.string("納入日"), value: deliveryDate)
                    }
                    if let deliveryRun = qr.deliveryRun {
                        LabeledContent(AppLocalization.string("便"), value: deliveryRun)
                    }
                    if let instructedQuantity = qr.instructedQuantity {
                        LabeledContent(
                            AppLocalization.string("指示数"),
                            value: appLanguage.formatInteger(instructedQuantity)
                        )
                    }
                    if let itemNumber = qr.itemNumber {
                        LabeledContent(AppLocalization.string("アイテムNo"), value: itemNumber)
                    }
                    if let receivingCode = qr.receivingCode {
                        LabeledContent(AppLocalization.string("受入"), value: receivingCode)
                    }
                }
            }

            if let tag = entry.barcodePayload.flatMap(TagBarcodeRecord.parse) {
                Section(AppLocalization.string("現品票情報（バーコード解析）")) {
                    LabeledContent(AppLocalization.string("品番"), value: tag.partNumber)
                    LabeledContent(AppLocalization.string("管理コード"), value: tag.managementCode ?? "-")
                }
            }

            Section(AppLocalization.string("QRコード（納品書兼現品票）全文")) {
                PayloadText(payload: entry.qrPayload)
            }

            Section(AppLocalization.string("Code 128（現品票）全文")) {
                PayloadText(payload: entry.barcodePayload)
            }
        }
        .navigationTitle(entry.code)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func quantityText(_ value: Double?) -> String {
        guard let value else { return "-" }
        return appLanguage.formatQuantity(value)
    }
}

private struct PayloadText: View {
    let payload: String?

    var body: some View {
        if let payload {
            Text(payload)
                .font(.system(.footnote, design: .monospaced))
                .textSelection(.enabled)
                .lineSpacing(3)
        } else {
            Text(AppLocalization.string("記録なし（旧バージョンで照合）"))
                .font(.footnote)
                .foregroundStyle(AppTheme.muted)
        }
    }
}


/// 1種類のPDFの「保存」「共有」ボタンの組。2つのレポートがボタン文言を共有するので、
/// どのPDFを書き出す行かは上のキャプションで示す。
private struct PDFActionRow: View {
    let caption: String
    let saveIdentifier: String
    let shareIdentifier: String
    let onSave: () -> Void
    let onShare: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(caption)
                .font(.caption.weight(.bold))
                .foregroundStyle(AppTheme.muted)
                .padding(.leading, 4)
            HStack(spacing: 10) {
                Button(action: onSave) {
                    Label(
                        AppLocalization.string("PDFで保存"),
                        systemImage: "arrow.down.doc.fill"
                    )
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(AppTheme.green, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier(saveIdentifier)

                Button(action: onShare) {
                    Label(
                        AppLocalization.string("共有する"),
                        systemImage: "square.and.arrow.up"
                    )
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppTheme.green)
                .background(AppTheme.green.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier(shareIdentifier)
            }
        }
        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
        .listRowBackground(Color.clear)
    }
}
