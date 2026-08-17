import SwiftUI

struct RootTabView: View {
    @StateObject private var historyStore: HistoryStore

    init() {
        let store = HistoryStore()
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-demoMatch") || arguments.contains("-demoMismatch") {
            store.beginSession()
        }
        _historyStore = StateObject(wrappedValue: store)
    }

    var body: some View {
        TabView {
            ScannerFlowView(historyStore: historyStore)
                .tabItem {
                    Label("照合", systemImage: "barcode.viewfinder")
                }

            HistoryScreen(historyStore: historyStore)
                .tabItem {
                    Label("履歴", systemImage: "clock.arrow.circlepath")
                }

            SettingsScreen()
                .tabItem {
                    Label("設定", systemImage: "gearshape.fill")
                }
        }
        .tint(AppTheme.green)
        .preferredColorScheme(.light)
    }
}

private struct ScannerFlowView: View {
    @ObservedObject var historyStore: HistoryStore

    var body: some View {
        Group {
            if let session = historyStore.activeSession {
                ScannerScreen(historyStore: historyStore, sessionID: session.id)
                    .id(session.id)
            } else {
                SessionStartView(historyStore: historyStore)
            }
        }
    }
}

private struct SessionStartView: View {
    @ObservedObject var historyStore: HistoryStore

    var body: some View {
        ZStack {
            AppTheme.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("SCAN & VERIFY")
                            .font(.caption2.weight(.black))
                            .tracking(2.2)
                            .foregroundStyle(AppTheme.green)

                        Text("照合作業を、\n記録して始める。")
                            .font(.system(size: 36, weight: .bold, design: .rounded))
                            .tracking(-1.2)
                            .foregroundStyle(AppTheme.ink)

                        Text("作業単位ごとにセッションを開始します。一致したコードと時刻が端末内の履歴へ記録されます。")
                            .font(.subheadline)
                            .foregroundStyle(AppTheme.muted)
                            .lineSpacing(5)
                    }

                    VStack(spacing: 18) {
                        Image(systemName: "list.clipboard.fill")
                            .font(.system(size: 42))
                            .foregroundStyle(AppTheme.green)
                            .frame(width: 86, height: 86)
                            .background(AppTheme.green.opacity(0.10), in: Circle())

                        VStack(spacing: 6) {
                            Text("新しい照合セッション")
                                .font(.title3.weight(.bold))
                            Text("開始後は照合済み件数を常に確認できます")
                                .font(.caption)
                                .foregroundStyle(AppTheme.muted)
                        }

                        Button {
                            historyStore.beginSession()
                        } label: {
                            Label("記録を開始する", systemImage: "play.fill")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.white)
                        .background(AppTheme.green, in: RoundedRectangle(cornerRadius: 14))
                        .accessibilityIdentifier("startSessionButton")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(22)
                    .scannerCard()

                    if !historyStore.sessions.isEmpty {
                        Label(
                            "これまでに\(historyStore.sessions.count)セッションを端末内へ保存しています",
                            systemImage: "clock.arrow.circlepath"
                        )
                        .font(.caption)
                        .foregroundStyle(AppTheme.muted)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 24)
                .padding(.bottom, 32)
            }
        }
    }
}
