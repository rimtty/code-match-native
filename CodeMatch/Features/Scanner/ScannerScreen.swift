import Foundation
import SwiftUI

struct ScannerScreen: View {
    @ObservedObject var historyStore: HistoryStore
    @ObservedObject var bluetoothScanner: BluetoothScannerService
    let sessionID: UUID
    @StateObject private var viewModel: ScannerViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var showsDemoTools = false
    @State private var showsEndConfirmation = false
    @AppStorage(AutoAdvanceSettings.enabledKey) private var autoAdvanceEnabled = AutoAdvanceSettings.defaultEnabled
    @AppStorage(AutoAdvanceSettings.delaySecondsKey) private var autoAdvanceDelaySeconds = AutoAdvanceSettings.defaultDelay.rawValue
    @AppStorage(AppLanguage.storageKey) private var selectedAppLanguageRawValue = AppLanguage.fallback.rawValue

    init(
        historyStore: HistoryStore,
        bluetoothScanner: BluetoothScannerService,
        cameraScanner: CameraScanner,
        sessionID: UUID
    ) {
        self.historyStore = historyStore
        self.bluetoothScanner = bluetoothScanner
        self.sessionID = sessionID
        _viewModel = StateObject(
            wrappedValue: ScannerViewModel(
                historyStore: historyStore,
                bluetoothScanner: bluetoothScanner,
                camera: cameraScanner
            )
        )
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
                        // カメラのないシミュレーターでの動作確認・UIテスト専用。実機では表示しない
                        #if targetEnvironment(simulator)
                        demoTools
                        #endif
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 18)
                    .padding(.bottom, 84)
                }
            }
        }
        // フローティングタブバーに重ならないよう、結果表示中は主要操作を下端へ固定する
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if viewModel.step.progress == 3 {
                nextActionBar
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                viewModel.resumeAfterForeground()
            } else {
                // UI状態にかかわらず開始待ちも含めて必ず停止し、Bluetoothは
                // 強制終了されても困らない安全な読取設定へ戻す。
                viewModel.prepareForBackground()
            }
        }
        .onChange(of: bluetoothScanner.state) { _, state in
            viewModel.handleBluetoothConnectionState(state)
        }
        .onChange(of: bluetoothScanner.configurationState) { _, state in
            viewModel.handleBluetoothConfigurationState(state)
        }
        .onChange(of: autoAdvanceEnabled) { _, isEnabled in
            viewModel.setAutoAdvanceEnabled(isEnabled)
        }
        .onChange(of: autoAdvanceDelaySeconds) { _, seconds in
            guard let delay = AutoAdvanceDelay(rawValue: seconds) else { return }
            viewModel.setAutoAdvanceDelay(delay)
        }
        .onChange(of: selectedAppLanguageRawValue) { _, _ in
            viewModel.refreshLocalizedMessage()
        }
        .onAppear {
            viewModel.setAutoAdvanceEnabled(autoAdvanceEnabled)
            if let delay = AutoAdvanceDelay(rawValue: autoAdvanceDelaySeconds) {
                viewModel.setAutoAdvanceDelay(delay)
            }
            // 画面表示前から接続済みの場合にもBluetoothを初期入力として反映する。
            viewModel.handleBluetoothConnectionState(bluetoothScanner.state)
            viewModel.handleBluetoothConfigurationState(bluetoothScanner.configurationState)
            viewModel.refreshLocalizedMessage()
        }
        // 履歴からアクティブセッションが削除された場合など、画面が消えたらカメラを止める
        .onDisappear {
            // 権限コールバック待ちなどUIと実セッションがずれた場合も停止する。
            viewModel.stopCamera()
        }
        .tint(AppTheme.green)
        .preferredColorScheme(.light)
        .alert(AppLocalization.string("このセッションを終了しますか？"), isPresented: $showsEndConfirmation) {
            Button(AppLocalization.string("キャンセル"), role: .cancel) {}
            Button(AppLocalization.string("終了する"), role: .destructive) {
                // stopRunningは完全停止まで待つ同期APIのため、CameraScannerの
                // 専用キューで停止が完了してから画面を閉じる。セッション自体は
                // アプリ内で再利用し、短時間の破棄・再生成を繰り返さない。
                viewModel.prepareForSessionEnd {
                    historyStore.endActiveSession()
                }
            }
        } message: {
            Text(
                matchedCount > 0
                ? AppLocalization.string("一致した\(matchedCount)件は履歴に保存されます。")
                : AppLocalization.string("一致が0件のため、このセッションは履歴に保存されません。")
            )
        }
    }

    private var sessionStatusBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(sessionTitle)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white.opacity(0.66))
                        .lineLimit(1)
                Text(AppLocalization.string("\(matchedCount)件照合済み"))
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.white)
                        .contentTransition(.numericText())
                        .accessibilityIdentifier("sessionMatchCount")
                }

                Spacer()

                Button(viewModel.isEndingSession ? AppLocalization.string("終了中…") : AppLocalization.string("終了")) {
                    showsEndConfirmation = true
                }
                .disabled(viewModel.isEndingSession)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(AppTheme.ink)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(AppTheme.lime, in: Capsule())
                .accessibilityIdentifier("endSessionButton")
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 13)

            Divider()
                .overlay(.white.opacity(0.14))

            HStack(spacing: 10) {
                Label(AppLocalization.string("成功時 自動で次へ"), systemImage: "forward.end.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Spacer(minLength: 4)

                if autoAdvanceEnabled {
                    Menu {
                        ForEach(AutoAdvanceDelay.allCases) { delay in
                            Button {
                                autoAdvanceDelaySeconds = delay.rawValue
                            } label: {
                                if autoAdvanceDelaySeconds == delay.rawValue {
                                    Label(delay.label, systemImage: "checkmark")
                                } else {
                                    Text(delay.label)
                                }
                            }
                        }
                    } label: {
                        Text(AppLocalization.string("\(autoAdvanceDelaySeconds)秒"))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.lime)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(.white.opacity(0.10), in: Capsule())
                    }
                    .accessibilityIdentifier("sessionAutoAdvanceDelayMenu")
                } else {
                    Text(AppLocalization.string("OFF"))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white.opacity(0.56))
                }

                Toggle(AppLocalization.string("成功時に自動で次の照合へ進む"), isOn: $autoAdvanceEnabled)
                    .labelsHidden()
                    .tint(AppTheme.lime)
                    .accessibilityIdentifier("sessionAutoAdvanceToggle")
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 9)
        }
        .background(AppTheme.ink)
        .accessibilityElement(children: .contain)
    }

    private var matchedCount: Int {
        historyStore.sessions.first(where: { $0.id == sessionID })?.matchedCount ?? 0
    }

    private var sessionTitle: String {
        let name = historyStore.sessions.first(where: { $0.id == sessionID })?.displayName ?? ""
        return name.isEmpty ? AppLocalization.string("照合セッション") : name
    }

    private var progress: some View {
            HStack(spacing: 4) {
            ProgressItem(number: 1, label: AppLocalization.string("QR"), isActive: viewModel.step.progress >= 1, isComplete: !viewModel.qrValue.isEmpty)
            ProgressConnector(isActive: viewModel.step.progress >= 2)
            ProgressItem(number: 2, label: AppLocalization.string("バーコード"), isActive: viewModel.step.progress >= 2, isComplete: !viewModel.barcodeValue.isEmpty)
            ProgressConnector(isActive: viewModel.step.progress >= 3)
            ProgressItem(number: 3, label: AppLocalization.string("照合"), isActive: viewModel.step.progress >= 3, isComplete: viewModel.step.progress >= 3)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(AppLocalization.string("照合の進行状況、ステップ\(viewModel.step.progress)／3"))
    }

    private var scannerCard: some View {
        VStack(spacing: 0) {
            scannerHeader

            if bluetoothScanner.isReadyForScanning, viewModel.expectedCode != nil {
                inputSourcePicker
            }

            Group {
                switch viewModel.step {
                case .result(let result):
                    ResultView(
                        result: result,
                        sessionBoxNumber: viewModel.sessionBoxNumber,
                        qrPartNumber: viewModel.qrPartNumber.map { CodeMatcher.format(partNumber: $0) },
                        barcodePartNumber: viewModel.barcodePartNumber.map { CodeMatcher.format(partNumber: $0) }
                    )
                    .accessibilityIdentifier("resultView")
                case .qr, .barcode:
                    ZStack {
                        // View自体は維持するが、停止中はAVCaptureVideoPreviewLayerを
                        // セッションから外してカメラ用IOSurfaceを保持し続けない。
                        cameraStage
                            .opacity(viewModel.inputSource == .camera ? 1 : 0)
                            .allowsHitTesting(viewModel.inputSource == .camera)
                            .accessibilityHidden(viewModel.inputSource != .camera)

                        if viewModel.inputSource == .bluetooth {
                            bluetoothStage
                        }
                    }
                }
            }

            // 結果表示中はResultView内の文言と重複するため省き、品番ボックスを1画面に収める
            if viewModel.step.progress != 3 {
                messagePanel
            }

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
                    Text(
                        viewModel.step.progress == 3
                            ? AppLocalization.string("結果")
                            : AppLocalization.string("ステップ \(viewModel.step.progress) / 2")
                    )
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
                Label(
                    isReading ? AppLocalization.string("読取中") : AppLocalization.string("待機中"),
                    systemImage: "circle.fill"
                )
                    .labelStyle(StatusLabelStyle(isRunning: isReading))
            }
        }
        .padding(.bottom, 16)
    }

    private var inputSourcePicker: some View {
        Picker(AppLocalization.string("入力元"), selection: Binding(
            get: { viewModel.inputSource },
            set: { viewModel.selectInputSource($0) }
        )) {
            ForEach(ScanInputSource.allCases) { source in
                Label(
                    source.label,
                    systemImage: source == .camera ? "camera.fill" : "barcode.viewfinder"
                )
                .tag(source)
            }
        }
        .pickerStyle(.segmented)
        .padding(.bottom, 14)
        .accessibilityIdentifier("scanInputSourcePicker")
    }

    private var bluetoothStage: some View {
        BluetoothScanGuide(
            expectedCode: viewModel.expectedCode ?? .qr,
            deviceName: bluetoothScanner.connectedDevice?.name ?? AppLocalization.string("Bluetoothスキャナ"),
            isConfiguring: bluetoothScanner.configurationState == .configuring
        )
        .frame(maxWidth: .infinity)
        .aspectRatio(4 / 3, contentMode: .fit)
        .background(AppTheme.green.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(AppTheme.green.opacity(0.28), lineWidth: 1.5)
        }
        .accessibilityIdentifier("bluetoothScannerStage")
    }

    private var cameraStage: some View {
        GeometryReader { proxy in
            ZStack {
                // AVCaptureSessionは再利用し、PreviewLayerとの接続だけを
                // 稼働状態に合わせて付け外しする。
                CameraPreview(
                    session: viewModel.camera.session,
                    // stopRunningが完了するまではプレビュー層を接続したままにする。
                    // isEndingSessionだけで先に外すと、実行中セッションからの
                    // PreviewLayer切断がメインスレッドを長時間塞ぐことがある。
                    isActive: viewModel.isCameraRunning,
                    expectedCode: viewModel.expectedCode,
                    onTap: { devicePoint, viewPoint in
                        viewModel.focus(at: devicePoint, displayAt: viewPoint)
                    },
                    onRegionOfInterest: { rect in viewModel.camera.setRegionOfInterest(rect) }
                )
                .opacity(viewModel.isCameraRunning ? 1 : 0)
                .accessibilityIdentifier("cameraPreview")

                if !viewModel.isCameraRunning {
                    AppTheme.ink.opacity(0.06)
                    VStack(spacing: 10) {
                        Image(systemName: "camera.viewfinder")
                            .font(.system(size: 34, weight: .medium))
                        Text(
                            viewModel.isCameraStarting
                                ? AppLocalization.string("カメラを準備中です")
                                : AppLocalization.string("カメラは停止中です")
                        )
                            .font(.subheadline.weight(.semibold))
                        Text(
                            viewModel.isCameraStarting
                                ? AppLocalization.string("映像の開始を待っています")
                                : AppLocalization.string("開始すると映像がここに表示されます")
                        )
                            .font(.caption2)
                    }
                    .foregroundStyle(AppTheme.muted)
                }

                ScanFrame(isSquare: viewModel.expectedCode == .qr, isAnimated: viewModel.isCameraRunning)
                    .padding(viewModel.expectedCode == .qr ? 22 : 14)

                if viewModel.isCameraRunning {
                        Text(AppLocalization.string("タップでピント合わせ"))
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
            CodeReadout(
                label: AppLocalization.string("QR・納品書の品目番号"),
                partNumber: viewModel.qrPartNumber.map { CodeMatcher.format(partNumber: $0) },
                payload: viewModel.qrValue
            )
            CodeReadout(
                label: AppLocalization.string("バーコード・現品票の品番"),
                partNumber: viewModel.barcodePartNumber.map { CodeMatcher.format(partNumber: $0) },
                payload: viewModel.barcodeValue
            )
        }
        .padding(.top, 12)
    }

    private var actionButtons: some View {
        VStack(spacing: 10) {
            if viewModel.expectedCode != nil, viewModel.inputSource == .camera {
                Button(action: viewModel.toggleCamera) {
                    Label(
                        viewModel.isCameraRunning || viewModel.isCameraStarting
                            ? AppLocalization.string("カメラを停止")
                            : startButtonTitle,
                        systemImage: viewModel.isCameraRunning || viewModel.isCameraStarting ? "stop.fill" : "viewfinder"
                    )
                    .font(.title3.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 62)
                    .background(
                        AppTheme.green,
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                    )
                    // plainスタイルでは透明な余白がヒット領域から外れることがあるため、
                    // 見えている緑のボタン全体を明示的にタップ領域へ含める。
                    .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("cameraButton")
            }

            if viewModel.step.progress != 3, !viewModel.qrValue.isEmpty {
                Button {
                    viewModel.rereadQR()
                } label: {
                    Label(AppLocalization.string("QRを読み取りなおす"), systemImage: "arrow.counterclockwise")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppTheme.green)
                .background(
                    AppTheme.green.opacity(0.08),
                    in: RoundedRectangle(cornerRadius: 16)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(AppTheme.green.opacity(0.3), lineWidth: 1)
                }
                .accessibilityIdentifier("rereadQRButton")

                Button(AppLocalization.string("次のコードを照合")) { viewModel.reset() }
                    .font(.headline)
                    .foregroundStyle(AppTheme.ink)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 56)
                    .background(AppTheme.ink.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityIdentifier("resetButton")
            }
        }
        .padding(.top, 16)
    }

    private var nextActionBar: some View {
        VStack(spacing: 10) {
            if let remaining = viewModel.autoAdvanceSecondsRemaining {
                HStack(spacing: 14) {
                    ZStack {
                        Circle()
                            .stroke(AppTheme.green.opacity(0.18), lineWidth: 5)
                        Circle()
                            .trim(
                                from: 0,
                                to: Double(remaining) / Double(viewModel.autoAdvanceDelay.rawValue)
                            )
                            .stroke(AppTheme.green, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text("\(remaining)")
                            .font(.system(.title2, design: .rounded, weight: .black))
                            .foregroundStyle(AppTheme.green)
                            .contentTransition(.numericText(countsDown: true))
                    }
                    .frame(width: 52, height: 52)

                    VStack(alignment: .leading, spacing: 3) {
                    Text(AppLocalization.string("自動で次の照合へ進みます"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                        Text(AppLocalization.string("あと\(remaining)秒・下のボタンで今すぐ進めます"))
                            .font(.caption)
                            .foregroundStyle(AppTheme.muted)
                            .contentTransition(.numericText(countsDown: true))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(12)
                .background(AppTheme.green.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                .accessibilityElement(children: .combine)
                .accessibilityLabel(AppLocalization.string("自動で次の照合へ進みます。あと\(remaining)秒"))
                .accessibilityIdentifier("autoAdvanceCountdown")
            }

            Button {
                viewModel.reset()
            } label: {
                Label(
                    viewModel.autoAdvanceSecondsRemaining == nil
                    ? AppLocalization.string("次のコードを照合")
                    : AppLocalization.string("今すぐ次の照合へ"),
                    systemImage: "qrcode.viewfinder"
                )
                    .font(.title3.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 70)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            .background(AppTheme.green, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .accessibilityIdentifier("resetButton")
        }
        .padding(.horizontal, 18)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background {
            Rectangle()
                .fill(AppTheme.paper.opacity(0.92))
                .ignoresSafeArea()
        }
    }

    private var privacyNote: some View {
        Label(
            AppLocalization.string(
                "カメラ映像とBluetoothで読み取ったコード内容は端末内だけで処理され、外部へ送信されません。"
            ),
            systemImage: "lock.shield.fill"
        )
            .font(.caption)
            .foregroundStyle(AppTheme.muted)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
    }

    #if targetEnvironment(simulator)
    private var demoTools: some View {
        DisclosureGroup(AppLocalization.string("カメラなしで判定をテスト"), isExpanded: $showsDemoTools) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                Button(AppLocalization.string("一致をテスト")) { viewModel.runDemo(shouldMatch: true) }
                    .accessibilityIdentifier("demoMatchButton")
                Button(AppLocalization.string("不一致をテスト")) { viewModel.runDemo(shouldMatch: false) }
                    .tint(AppTheme.red)
                    .accessibilityIdentifier("demoMismatchButton")
                }

                if bluetoothScanner.isConnected, viewModel.inputSource == .bluetooth {
                    HStack {
                        Button(AppLocalization.string("モックQR")) {
                            bluetoothScanner.simulateScan(ScannerViewModel.sampleQRPayload)
                        }
                        .accessibilityIdentifier("demoBluetoothQRButton")
                        Button(AppLocalization.string("モックCode 128")) {
                            bluetoothScanner.simulateScan(ScannerViewModel.sampleBarcodePayload)
                        }
                        .accessibilityIdentifier("demoBluetoothBarcodeButton")
                    }
                }
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
    #endif

    private var headerTitle: String {
        switch viewModel.step {
        case .qr: AppLocalization.string("QRコードを読み取る")
        case .barcode: AppLocalization.string("バーコードを読み取る")
        case .result(.match): AppLocalization.string("照合OK")
        case .result(.mismatch): AppLocalization.string("不一致")
        }
    }

    private var startButtonTitle: String {
        viewModel.expectedCode == .qr ? AppLocalization.string("QRコードを読み取る") : AppLocalization.string("バーコードを読み取る")
    }

    private var isReading: Bool {
        viewModel.inputSource == .bluetooth
            ? bluetoothScanner.isReadyForScanning
            : viewModel.isCameraRunning
    }
}

private struct BluetoothScanGuide: View {
    let expectedCode: ExpectedCode
    let deviceName: String
    let isConfiguring: Bool

    var body: some View {
        VStack(spacing: 8) {
            Text(isConfiguring ? AppLocalization.string("読み取り対象を設定中") : AppLocalization.string("いま読み取るコード"))
                .font(.caption2.weight(.black))
                .tracking(1.4)
                .foregroundStyle(AppTheme.green)

            codeSample

            VStack(spacing: 5) {
                Text(expectedCode == .qr
                     ? AppLocalization.string("1  四角いQRコード")
                     : AppLocalization.string("2  横長のCode 128"))
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                Text(expectedCode == .qr
                     ? AppLocalization.string("納品書兼現品票にある四角いコードへ向けます")
                     : AppLocalization.string("現品票にある縦線が並んだ横長コードへ向けます"))
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                    .multilineTextAlignment(.center)
                }

            Label(
                isConfiguring
                    ? AppLocalization.string("設定が完了するまでお待ちください")
                    : AppLocalization.string("トリガーを1回押して読み取る"),
                systemImage: isConfiguring ? "gearshape.2.fill" : "hand.tap.fill"
            )
            .font(.subheadline.weight(.bold))
            .foregroundStyle(AppTheme.green)

            Text(deviceName)
                .font(.caption2)
                .foregroundStyle(AppTheme.muted)
                .lineLimit(1)
        }
        .padding(12)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
        .accessibilityIdentifier(expectedCode == .qr ? "bluetoothQRGuide" : "bluetoothCode128Guide")
    }

    @ViewBuilder
    private var codeSample: some View {
        if expectedCode == .qr {
            Image(systemName: "qrcode")
                .font(.system(size: 62, weight: .regular))
                .foregroundStyle(AppTheme.ink)
                .frame(width: 112, height: 82)
                .background(.white, in: RoundedRectangle(cornerRadius: 12))
                .overlay {
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(AppTheme.line, lineWidth: 1)
                }
        } else {
            Code128Sample()
                .frame(width: 190, height: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.white, in: RoundedRectangle(cornerRadius: 12))
                .overlay {
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(AppTheme.line, lineWidth: 1)
                }
        }
    }

    private var accessibilityText: String {
        if isConfiguring {
            return AppLocalization.string("Bluetoothスキャナーの読み取り対象を設定中です")
        }
        return expectedCode == .qr
            ? AppLocalization.string("ステップ1、納品書兼現品票の四角いQRコードを読み取ってください")
            : AppLocalization.string("ステップ2、現品票の縦線が並んだ横長のCode 128を読み取ってください")
    }
}

private struct Code128Sample: View {
    private let barWidths: [CGFloat] = [3, 1, 2, 4, 1, 3, 2, 1, 4, 2, 3, 1, 2, 4, 1, 2, 3, 1, 4, 2, 1]

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(barWidths.enumerated()), id: \.offset) { _, width in
                Rectangle()
                    .fill(AppTheme.ink)
                    .frame(width: width)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityHidden(true)
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
    let partNumber: String?
    let payload: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(label)
                .font(.system(size: 9, weight: .black))
                .tracking(1.5)
                .foregroundStyle(AppTheme.muted)
            Text(primaryText)
                .font(.system(.subheadline, design: .monospaced, weight: .bold))
                .foregroundStyle(AppTheme.ink)
                .lineLimit(1)
            if partNumber != nil, !payload.isEmpty {
                Text(payload)
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(AppTheme.muted)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: 11).stroke(AppTheme.line))
    }

    private var primaryText: String {
        if let partNumber { return partNumber }
        return payload.isEmpty ? "—" : payload
    }
}

private struct ResultView: View {
    let result: MatchResult
    /// 一致した品番がこのセッションで何箱目か。2箱目以降のときだけ補足表示する。
    let sessionBoxNumber: Int
    let qrPartNumber: String?
    let barcodePartNumber: String?

    var body: some View {
        // 品番ボックス2つが同一画面に収まるよう、結果表示はコンパクトにまとめる
        VStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.system(size: 27, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(accentColor, in: Circle())
                .background(accentColor.opacity(0.12), in: Circle().inset(by: -7))
                .symbolEffect(.bounce, value: result)

            VStack(spacing: 5) {
                Text(title)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(AppTheme.ink)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                    .multilineTextAlignment(.center)
            }

            if result == .mismatch, qrPartNumber != nil || barcodePartNumber != nil {
                VStack(spacing: 4) {
                    PartNumberRow(label: AppLocalization.string("納品書"), value: qrPartNumber)
                    PartNumberRow(label: AppLocalization.string("現品票"), value: barcodePartNumber)
                }
                .padding(.horizontal, 18)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(accentColor.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(AppLocalization.string("照合結果、\(title)"))
    }

    private var iconName: String {
        switch result {
        case .match: "checkmark"
        case .mismatch: "exclamationmark"
        }
    }

    private var accentColor: Color {
        switch result {
        case .match: AppTheme.green
        case .mismatch: AppTheme.red
        }
    }

    private var title: String {
        switch result {
        case .match: AppLocalization.string("一致しました")
        case .mismatch: AppLocalization.string("一致しません")
        }
    }

    private var subtitle: String {
        switch result {
        case .match:
            let base = (barcodePartNumber ?? qrPartNumber).map {
                AppLocalization.string("品目番号 \($0) の組み合わせは正しいです。")
            } ?? AppLocalization.string("この組み合わせは正しいです。")
            return sessionBoxNumber >= 2 ? AppLocalization.string("\(base)（このセッションで\(sessionBoxNumber)箱目）") : base
        case .mismatch:
            return AppLocalization.string("品目番号が一致しません。対象を確認して、もう一度読み取ってください。")
        }
    }
}

private struct PartNumberRow: View {
    let label: String
    let value: String?

    var body: some View {
        HStack {
            Text(label)
                .font(.caption2.weight(.bold))
                .foregroundStyle(AppTheme.muted)
            Spacer()
            Text(value ?? AppLocalization.string("読取不可"))
                .font(.system(.caption, design: .monospaced, weight: .bold))
                .foregroundStyle(AppTheme.ink)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(.white.opacity(0.65), in: RoundedRectangle(cornerRadius: 9))
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
