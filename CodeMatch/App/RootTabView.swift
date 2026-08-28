import SwiftUI

struct RootTabView: View {
    @StateObject private var historyStore: HistoryStore
    @StateObject private var bluetoothScanner: BluetoothScannerService
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let store = HistoryStore()
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-demoMatch") || arguments.contains("-demoMismatch") {
            store.beginSession()
        }
        _historyStore = StateObject(wrappedValue: store)
        _bluetoothScanner = StateObject(wrappedValue: BluetoothScannerService())
    }

    var body: some View {
        TabView {
            ScannerFlowView(
                historyStore: historyStore,
                bluetoothScanner: bluetoothScanner
            )
                .tabItem {
                    Label("照合", systemImage: "barcode.viewfinder")
                }

            HistoryScreen(historyStore: historyStore)
                .tabItem {
                    Label("履歴", systemImage: "clock.arrow.circlepath")
                }

            SettingsScreen(bluetoothScanner: bluetoothScanner)
                .tabItem {
                    Label("設定", systemImage: "gearshape.fill")
                }
        }
        .tint(AppTheme.green)
        .preferredColorScheme(.light)
        .task {
            bluetoothScanner.setApplicationActive(scenePhase == .active)
        }
        .onChange(of: scenePhase) { _, phase in
            bluetoothScanner.setApplicationActive(phase == .active)
        }
    }
}

private struct ScannerFlowView: View {
    @ObservedObject var historyStore: HistoryStore
    @ObservedObject var bluetoothScanner: BluetoothScannerService

    var body: some View {
        Group {
            if let session = historyStore.activeSession {
                ScannerScreen(
                    historyStore: historyStore,
                    bluetoothScanner: bluetoothScanner,
                    sessionID: session.id
                )
                    .id(session.id)
            } else {
                SessionStartView(historyStore: historyStore)
            }
        }
    }
}

private struct SessionStartView: View {
    @ObservedObject var historyStore: HistoryStore
    @State private var sessionName = ""

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

                        TextField("セッション名（任意）", text: $sessionName)
                            .font(.subheadline)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(AppTheme.ink.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
                            .overlay {
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(AppTheme.line, lineWidth: 1)
                            }
                            .submitLabel(.done)
                            .accessibilityIdentifier("sessionNameField")

                        Button {
                            historyStore.beginSession(name: sessionName)
                            sessionName = ""
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
