import SwiftUI

struct ScannerScreen: View {
    @ObservedObject var historyStore: HistoryStore
    let sessionID: UUID
    @StateObject private var viewModel: ScannerViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showsDemoTools = false
    @State private var showsEndConfirmation = false

    init(historyStore: HistoryStore, sessionID: UUID) {
        self.historyStore = historyStore
        self.sessionID = sessionID
        _viewModel = StateObject(wrappedValue: ScannerViewModel(historyStore: historyStore))
    }

    var body: some View {
        ZStack {
            AppTheme.paper.ignoresSafeArea()
            VStack(spacing: 0) {
                sessionStatusBar

                ScrollView {
                    VStack(spacing: 22) {
                        progress
                        scannerCard
                        privacyNote
                        demoTools
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 18)
                    .padding(.bottom, 30)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active, viewModel.isCameraRunning {
                viewModel.toggleCamera()
            }
        }
        .tint(AppTheme.green)
        .preferredColorScheme(.light)
        .alert("このセッションを終了しますか？", isPresented: $showsEndConfirmation) {
            Button("キャンセル", role: .cancel) {}
            Button("終了する", role: .destructive) {
                viewModel.reset()
                historyStore.endActiveSession()
            }
        } message: {
            Text("一致した\(matchedCount)件は履歴に保存されます。")
        }
    }

    private var sessionStatusBar: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text("照合セッション")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.66))
                Text("\(matchedCount)件照合済み")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.white)
                    .contentTransition(.numericText())
                    .accessibilityIdentifier("sessionMatchCount")
            }

            Spacer()

            Button("終了") {
                showsEndConfirmation = true
            }
            .font(.subheadline.weight(.bold))
            .foregroundStyle(AppTheme.ink)
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(AppTheme.lime, in: Capsule())
            .accessibilityIdentifier("endSessionButton")
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 13)
        .background(AppTheme.ink)
        .accessibilityElement(children: .contain)
    }

    private var matchedCount: Int {
        historyStore.sessions.first(where: { $0.id == sessionID })?.matchedCount ?? 0
    }

    private var progress: some View {
        HStack(spacing: 4) {
            ProgressItem(number: 1, label: "QR", isActive: viewModel.step.progress >= 1, isComplete: !viewModel.qrValue.isEmpty)
            ProgressConnector(isActive: viewModel.step.progress >= 2)
            ProgressItem(number: 2, label: "バーコード", isActive: viewModel.step.progress >= 2, isComplete: !viewModel.barcodeValue.isEmpty)
            ProgressConnector(isActive: viewModel.step.progress >= 3)
            ProgressItem(number: 3, label: "照合", isActive: viewModel.step.progress >= 3, isComplete: viewModel.step.progress >= 3)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("照合の進行状況、ステップ\(viewModel.step.progress)／3")
    }

    private var scannerCard: some View {
        VStack(spacing: 0) {
            scannerHeader

            Group {
                switch viewModel.step {
                case .result(let result):
                    ResultView(result: result)
                        .accessibilityIdentifier("resultView")
                case .qr, .barcode:
                    cameraStage
                }
            }

            messagePanel

            if !viewModel.qrValue.isEmpty || !viewModel.barcodeValue.isEmpty {
                readouts
            }

            actionButtons
        }
        .padding(18)
        .scannerCard()
        .animation(.snappy(duration: 0.35), value: viewModel.step)
    }

    private var scannerHeader: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 5) {
                Text(viewModel.step.progress == 3 ? "RESULT" : "STEP \(viewModel.step.progress) / 2")
                    .font(.caption2.weight(.black))
                    .tracking(1.8)
                    .foregroundStyle(AppTheme.green)
                Text(headerTitle)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                    .accessibilityIdentifier("scannerTitle")
            }
            Spacer()
            if viewModel.expectedCode != nil {
                Label(viewModel.isCameraRunning ? "読取中" : "待機中", systemImage: "circle.fill")
                    .labelStyle(StatusLabelStyle(isRunning: viewModel.isCameraRunning))
            }
        }
        .padding(.bottom, 16)
    }

    private var cameraStage: some View {
        GeometryReader { proxy in
            ZStack {
                if viewModel.isCameraRunning {
                    CameraPreview(session: viewModel.camera.session) { point in
                        viewModel.focus(at: point)
                    }
                    .transition(.opacity)
                } else {
                    AppTheme.ink.opacity(0.06)
                    VStack(spacing: 10) {
                        Image(systemName: "camera.viewfinder")
                            .font(.system(size: 34, weight: .medium))
                        Text("カメラは停止中です")
                            .font(.subheadline.weight(.semibold))
                        Text("開始すると映像がここに表示されます")
                            .font(.caption2)
                    }
                    .foregroundStyle(AppTheme.muted)
                }

                ScanFrame(isSquare: viewModel.expectedCode == .qr, isAnimated: viewModel.isCameraRunning)
                    .padding(viewModel.expectedCode == .qr ? 22 : 14)

                if viewModel.isCameraRunning {
                    Text("タップでピント合わせ")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.black.opacity(0.55), in: Capsule())
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 10)
                }

                if let point = viewModel.focusPoint {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(AppTheme.lime, lineWidth: 2)
                        .frame(width: 58, height: 58)
                        .position(x: point.x * proxy.size.width, y: point.y * proxy.size.height)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .aspectRatio(4 / 3, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityIdentifier("cameraStage")
    }

    private var messagePanel: some View {
        Label {
            Text(viewModel.message)
                .font(.caption)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
        } icon: {
            Image(systemName: "info.circle")
                .font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(AppTheme.muted)
        .padding(12)
        .background(AppTheme.green.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
        .padding(.top, 14)
        .accessibilityIdentifier("statusMessage")
    }

    private var readouts: some View {
        VStack(spacing: 8) {
            CodeReadout(label: "QR CODE", value: viewModel.qrValue)
            CodeReadout(label: "BARCODE", value: viewModel.barcodeValue)
        }
        .padding(.top, 12)
    }

    private var actionButtons: some View {
        VStack(spacing: 10) {
            if viewModel.expectedCode != nil {
                Button(action: viewModel.toggleCamera) {
                    Label(
                        viewModel.isCameraRunning ? "カメラを停止" : startButtonTitle,
                        systemImage: viewModel.isCameraRunning ? "stop.fill" : "viewfinder"
                    )
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(AppTheme.green, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityIdentifier("cameraButton")
            }

            if viewModel.step.progress == 3 || !viewModel.qrValue.isEmpty {
                Button("次のコードを照合", action: viewModel.reset)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(AppTheme.ink.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityIdentifier("resetButton")
            }
        }
        .padding(.top, 16)
    }

    private var privacyNote: some View {
        Label("カメラ映像とコード内容は端末内だけで処理され、外部へ送信されません。", systemImage: "lock.shield.fill")
            .font(.caption)
            .foregroundStyle(AppTheme.muted)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
    }

    private var demoTools: some View {
        DisclosureGroup("カメラなしで判定をテスト", isExpanded: $showsDemoTools) {
            HStack {
                Button("一致をテスト") { viewModel.runDemo(shouldMatch: true) }
                    .accessibilityIdentifier("demoMatchButton")
                Button("不一致をテスト") { viewModel.runDemo(shouldMatch: false) }
                    .tint(AppTheme.red)
                    .accessibilityIdentifier("demoMismatchButton")
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 12)
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(AppTheme.muted)
        .padding(14)
        .background(.white.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
    }

    private var headerTitle: String {
        switch viewModel.step {
        case .qr: "QRコードを読み取る"
        case .barcode: "バーコードを読み取る"
        case .result(.match): "照合OK"
        case .result(.mismatch): "不一致"
        }
    }

    private var startButtonTitle: String {
        viewModel.expectedCode == .qr ? "QRコードを読み取る" : "バーコードを読み取る"
    }
}

private struct ProgressItem: View {
    let number: Int
    let label: String
    let isActive: Bool
    let isComplete: Bool

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(isActive ? AppTheme.ink : .clear)
                    .overlay(Circle().stroke(AppTheme.line))
                if isComplete {
                    Image(systemName: "checkmark")
                        .font(.caption2.weight(.black))
                        .foregroundStyle(isActive ? AppTheme.lime : AppTheme.muted)
                } else {
                    Text("\(number)")
                        .font(.caption2.weight(.black))
                        .foregroundStyle(isActive ? AppTheme.lime : AppTheme.muted)
                }
            }
            .frame(width: 30, height: 30)
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(isActive ? AppTheme.ink : AppTheme.muted)
                .lineLimit(1)
        }
        .frame(minWidth: 56)
    }
}

private struct ProgressConnector: View {
    let isActive: Bool

    var body: some View {
        Rectangle()
            .fill(isActive ? AppTheme.ink : AppTheme.line)
            .frame(height: 1)
            .frame(maxWidth: .infinity)
            .offset(y: -9)
    }
}

private struct StatusLabelStyle: LabelStyle {
    let isRunning: Bool

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon
                .font(.system(size: 7))
                .foregroundStyle(isRunning ? AppTheme.red : AppTheme.muted.opacity(0.6))
            configuration.title
        }
        .font(.caption2.weight(.bold))
        .foregroundStyle(AppTheme.muted)
    }
}

private struct CodeReadout: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label)
                .font(.system(size: 9, weight: .black))
                .tracking(1.5)
                .foregroundStyle(AppTheme.muted)
            Text(value.isEmpty ? "—" : value)
                .font(.system(.caption, design: .monospaced, weight: .bold))
                .foregroundStyle(AppTheme.ink)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: 11).stroke(AppTheme.line))
    }
}

private struct ResultView: View {
    let result: MatchResult

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: result == .match ? "checkmark" : "exclamationmark")
                .font(.system(size: 42, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 88, height: 88)
                .background(result == .match ? AppTheme.green : AppTheme.red, in: Circle())
                .background((result == .match ? AppTheme.green : AppTheme.red).opacity(0.12), in: Circle().inset(by: -12))
                .symbolEffect(.bounce, value: result)

            VStack(spacing: 7) {
                Text(result == .match ? "一致しました" : "一致しません")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                Text(result == .match
                     ? "この組み合わせは正しいです。"
                     : "対象を確認して、もう一度読み取ってください。")
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 255)
        .background((result == .match ? AppTheme.green : AppTheme.red).opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(result == .match ? "照合結果、一致しました" : "照合結果、一致しません")
    }
}

private struct ScanFrame: View {
    let isSquare: Bool
    let isAnimated: Bool
    @State private var linePosition: CGFloat = -1

    var body: some View {
        GeometryReader { proxy in
            let frameWidth = proxy.size.width
            let frameHeight = isSquare ? min(proxy.size.width, proxy.size.height) : proxy.size.height * 0.56

            ZStack {
                CornerFrameShape(cornerLength: 28)
                    .stroke(AppTheme.lime, style: StrokeStyle(lineWidth: 4, lineCap: .square))
                    .frame(width: frameWidth, height: frameHeight)

                if isAnimated {
                    Rectangle()
                        .fill(AppTheme.lime)
                        .frame(width: frameWidth * 0.90, height: 2)
                        .shadow(color: AppTheme.lime, radius: 5)
                        .offset(y: linePosition * frameHeight * 0.42)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onAppear {
                guard isAnimated else { return }
                withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) {
                    linePosition = 1
                }
            }
        }
        .allowsHitTesting(false)
    }
}

private struct CornerFrameShape: Shape {
    let cornerLength: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 0, y: cornerLength))
        path.addLine(to: .zero)
        path.addLine(to: CGPoint(x: cornerLength, y: 0))
        path.move(to: CGPoint(x: rect.maxX - cornerLength, y: 0))
        path.addLine(to: CGPoint(x: rect.maxX, y: 0))
        path.addLine(to: CGPoint(x: rect.maxX, y: cornerLength))
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - cornerLength))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - cornerLength, y: rect.maxY))
        path.move(to: CGPoint(x: cornerLength, y: rect.maxY))
        path.addLine(to: CGPoint(x: 0, y: rect.maxY))
        path.addLine(to: CGPoint(x: 0, y: rect.maxY - cornerLength))
        return path
    }
}

#Preview("Scanning") {
    RootTabView()
}
