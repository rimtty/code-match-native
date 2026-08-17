import SwiftUI

struct HistoryScreen: View {
    @ObservedObject var historyStore: HistoryStore

    var body: some View {
        NavigationStack {
            Group {
                if historyStore.sessions.isEmpty {
                    ContentUnavailableView(
                        "履歴はまだありません",
                        systemImage: "clock.arrow.circlepath",
                        description: Text("照合タブで記録を開始すると、一致したコードがセッション単位で保存されます。")
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
            .navigationTitle("照合履歴")
            .background(AppTheme.paper)
            .accessibilityIdentifier("historyScreen")
        }
    }
}

private struct SessionHistoryRow: View {
    let session: MatchSession

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 2) {
                Text("\(session.matchedCount)")
                    .font(.title2.weight(.bold))
                Text("件")
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
                Text(JPDate.dateTime(session.startedAt))
                    .font(session.displayName.isEmpty ? .subheadline.weight(.semibold) : .caption)
                    .foregroundStyle(session.displayName.isEmpty ? AppTheme.ink : AppTheme.muted)
                Text(session.isActive ? "照合中のセッション" : sessionDurationText)
                    .font(.caption)
                    .foregroundStyle(session.isActive ? AppTheme.green : AppTheme.muted)
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(session.displayName.isEmpty ? "" : "\(session.displayName)、")\(JPDate.dateTime(session.startedAt))、\(session.matchedCount)件、\(session.isActive ? "照合中" : "終了済み")"
        )
        .accessibilityIdentifier("historySessionRow")
    }

    private var sessionDurationText: String {
        guard let endedAt = session.endedAt else { return "" }
        let minutes = max(1, Int(endedAt.timeIntervalSince(session.startedAt) / 60))
        return "終了済み・約\(minutes)分"
    }
}

private struct SessionHistoryDetail: View {
    @ObservedObject var historyStore: HistoryStore
    let sessionID: UUID

    @State private var editedName = ""
    @State private var shareItem: ShareItem?
    @State private var showsExporter = false
    @State private var exportDocument: SessionPDFDocument?

    private struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    var body: some View {
        Group {
            if let session {
                List {
                    Section("セッション名") {
                        TextField("名前を入力（任意）", text: $editedName)
                            .submitLabel(.done)
                            .onSubmit { commitName() }
                            .accessibilityIdentifier("sessionNameEditField")
                    }

                    Section {
                        LabeledContent("開始", value: JPDate.dateTime(session.startedAt))
                        if let endedAt = session.endedAt {
                            LabeledContent("終了", value: JPDate.dateTime(endedAt))
                        } else {
                            LabeledContent("状態", value: "照合中")
                                .foregroundStyle(AppTheme.green)
                        }
                        LabeledContent("一致件数", value: "\(session.matchedCount)件")
                    }

                    Section {
                        HStack(spacing: 10) {
                            Button {
                                exportDocument = SessionPDFDocument(
                                    data: SessionPDFExporter.generatePDF(for: session)
                                )
                                showsExporter = true
                            } label: {
                                Label("PDFで保存", systemImage: "arrow.down.doc.fill")
                                    .font(.subheadline.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.white)
                            .background(AppTheme.green, in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("savePDFButton")

                            Button {
                                shareItem = (try? SessionPDFExporter.writeTemporaryPDF(for: session))
                                    .map { ShareItem(url: $0) }
                            } label: {
                                Label("共有する", systemImage: "square.and.arrow.up")
                                    .font(.subheadline.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(AppTheme.green)
                            .background(AppTheme.green.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityIdentifier("sharePDFButton")
                        }
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                    }

                    Section("一致したコード") {
                        if session.entries.isEmpty {
                            ContentUnavailableView(
                                "一致履歴はありません",
                                systemImage: "barcode",
                                description: Text("このセッションではまだ一致したコードがありません。")
                            )
                        } else {
                            ForEach(Array(session.entries.enumerated()), id: \.element.id) { index, entry in
                                NavigationLink {
                                    MatchEntryDetail(entry: entry, number: index + 1)
                                } label: {
                                    VStack(alignment: .leading, spacing: 7) {
                                        HStack {
                                            Text("#\(index + 1)")
                                                .font(.caption.weight(.bold))
                                                .foregroundStyle(AppTheme.green)
                                            Spacer()
                                            Text(JPDate.time(entry.matchedAt))
                                                .font(.caption)
                                                .foregroundStyle(AppTheme.muted)
                                        }
                                        Text(entry.code)
                                            .font(.system(.body, design: .monospaced, weight: .semibold))
                                    }
                                    .padding(.vertical, 5)
                                }
                                .accessibilityIdentifier("matchEntryRow")
                            }
                        }
                    }
                }
            } else {
                ContentUnavailableView("履歴が見つかりません", systemImage: "exclamationmark.triangle")
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
            defaultFilename: session.map { SessionPDFExporter.fileName(for: $0) } ?? "照合履歴.pdf"
        ) { _ in
            exportDocument = nil
        }
        .sheet(item: $shareItem) { item in
            ActivityShareSheet(items: [item.url])
                .presentationDetents([.medium, .large])
        }
    }

    private var session: MatchSession? {
        historyStore.sessions.first(where: { $0.id == sessionID })
    }

    private var navigationTitleText: String {
        let name = session?.displayName ?? ""
        return name.isEmpty ? "セッション詳細" : name
    }

    private func commitName() {
        guard let session, session.displayName != editedName.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        historyStore.renameSession(id: sessionID, name: editedName)
    }
}

private struct MatchEntryDetail: View {
    let entry: MatchHistoryEntry
    let number: Int

    var body: some View {
        List {
            Section {
                LabeledContent("番号", value: "#\(number)")
                LabeledContent("照合時刻", value: JPDate.dateTime(entry.matchedAt))
            }

            Section("品目番号") {
                Text(entry.code)
                    .font(.system(.title3, design: .monospaced, weight: .bold))
                    .textSelection(.enabled)
            }

            if let qr = entry.qrPayload.flatMap(KanbanQRRecord.parse) {
                Section("納品書情報（QR解析）") {
                    LabeledContent("カード番号", value: qr.cardNumber)
                    LabeledContent(
                        "品目番号",
                        value: CodeMatcher.format(partNumber: qr.partNumber)
                            + (qr.partSuffix.map { "（枝番 \($0)）" } ?? "")
                    )
                    LabeledContent("納入数量", value: quantityText(qr.deliveryQuantity))
                    LabeledContent("指示数", value: quantityText(qr.instructedQuantity))
                    LabeledContent("工場", value: qr.factoryCode ?? "-")
                    LabeledContent("受入部品庫", value: qr.warehouseCode ?? "-")
                    LabeledContent("供給先", value: qr.supplyPointCode ?? "-")
                }
            }

            if let tag = entry.barcodePayload.flatMap(TagBarcodeRecord.parse) {
                Section("現品票情報（バーコード解析）") {
                    LabeledContent("品番", value: tag.partNumber)
                    LabeledContent("管理コード", value: tag.managementCode ?? "-")
                }
            }

            Section("QRコード（納品書兼現品票）全文") {
                PayloadText(payload: entry.qrPayload)
            }

            Section("Code 128（現品票）全文") {
                PayloadText(payload: entry.barcodePayload)
            }
        }
        .navigationTitle(entry.code)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func quantityText(_ value: Double?) -> String {
        guard let value else { return "-" }
        return value.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(value))
            : String(format: "%.2f", value)
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
            Text("記録なし（旧バージョンで照合）")
                .font(.footnote)
                .foregroundStyle(AppTheme.muted)
        }
    }
}
