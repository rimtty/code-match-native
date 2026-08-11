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
                    List(historyStore.sessions) { session in
                        NavigationLink {
                            SessionHistoryDetail(historyStore: historyStore, sessionID: session.id)
                        } label: {
                            SessionHistoryRow(session: session)
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
                Text(session.startedAt.formatted(date: .abbreviated, time: .shortened))
                    .font(.subheadline.weight(.semibold))
                Text(session.isActive ? "照合中のセッション" : sessionDurationText)
                    .font(.caption)
                    .foregroundStyle(session.isActive ? AppTheme.green : AppTheme.muted)
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(session.startedAt.formatted(date: .abbreviated, time: .shortened))、\(session.matchedCount)件、\(session.isActive ? "照合中" : "終了済み")"
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

    var body: some View {
        Group {
            if let session {
                List {
                    Section {
                        LabeledContent("開始", value: session.startedAt.formatted(date: .long, time: .shortened))
                        if let endedAt = session.endedAt {
                            LabeledContent("終了", value: endedAt.formatted(date: .omitted, time: .shortened))
                        } else {
                            LabeledContent("状態", value: "照合中")
                                .foregroundStyle(AppTheme.green)
                        }
                        LabeledContent("一致件数", value: "\(session.matchedCount)件")
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
                                VStack(alignment: .leading, spacing: 7) {
                                    HStack {
                                        Text("#\(index + 1)")
                                            .font(.caption.weight(.bold))
                                            .foregroundStyle(AppTheme.green)
                                        Spacer()
                                        Text(entry.matchedAt.formatted(date: .omitted, time: .standard))
                                            .font(.caption)
                                            .foregroundStyle(AppTheme.muted)
                                    }
                                    Text(entry.code)
                                        .font(.system(.body, design: .monospaced, weight: .semibold))
                                        .textSelection(.enabled)
                                }
                                .padding(.vertical, 5)
                            }
                        }
                    }
                }
            } else {
                ContentUnavailableView("履歴が見つかりません", systemImage: "exclamationmark.triangle")
            }
        }
        .navigationTitle("セッション詳細")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var session: MatchSession? {
        historyStore.sessions.first(where: { $0.id == sessionID })
    }
}
