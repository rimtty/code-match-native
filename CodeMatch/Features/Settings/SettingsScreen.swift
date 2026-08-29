import SwiftUI

struct SettingsScreen: View {
    @ObservedObject var bluetoothScanner: BluetoothScannerService
    @AppStorage(FeedbackSettings.volumeKey) private var volume = FeedbackSettings.defaultVolume
    @AppStorage(FeedbackSettings.successSoundKey) private var successSound = SuccessSound.posBeep.rawValue
    @AppStorage(FeedbackSettings.failureSoundKey) private var failureSound = FailureSound.buzzer.rawValue
    @State private var player = FeedbackPlayer()

    var body: some View {
        ZStack {
            AppTheme.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("SETTINGS")
                            .font(.caption2.weight(.black))
                            .tracking(2.2)
                            .foregroundStyle(AppTheme.green)
                        Text("アプリの設定")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                    }
                    .padding(.horizontal, 4)

                    bluetoothScannerCard
                    volumeCard
                    soundCard(
                        title: "成功音",
                        subtitle: "品目番号が一致したときに鳴ります",
                        icon: "checkmark.circle.fill",
                        tint: AppTheme.green
                    ) {
                        ForEach(SuccessSound.allCases) { sound in
                            SoundOptionRow(
                                label: sound.label,
                                isSelected: successSound == sound.rawValue,
                                tint: AppTheme.green
                            ) {
                                successSound = sound.rawValue
                                player.preview(success: sound)
                            }
                            .accessibilityIdentifier("successSound_\(sound.rawValue)")
                        }
                    }
                    soundCard(
                        title: "失敗音",
                        subtitle: "品目番号が一致しないときに鳴ります",
                        icon: "exclamationmark.triangle.fill",
                        tint: AppTheme.red
                    ) {
                        ForEach(FailureSound.allCases) { sound in
                            SoundOptionRow(
                                label: sound.label,
                                isSelected: failureSound == sound.rawValue,
                                tint: AppTheme.red
                            ) {
                                failureSound = sound.rawValue
                                player.preview(failure: sound)
                            }
                            .accessibilityIdentifier("failureSound_\(sound.rawValue)")
                        }
                    }

                    Label(
                        "マナーモード中でも効果音は再生されます。実際の音量は端末の音量ボタンの設定にも依存します。",
                        systemImage: "speaker.wave.2.bubble.left.fill"
                    )
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                    .lineSpacing(3)
                    .padding(.horizontal, 6)
                }
                .padding(.horizontal, 18)
                .padding(.top, 20)
                .padding(.bottom, 84)
            }
        }
        .preferredColorScheme(.light)
        .accessibilityIdentifier("settingsScreen")
    }

    private var bluetoothScannerCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "barcode.viewfinder")
                    .font(.title3)
                    .foregroundStyle(AppTheme.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Bluetoothスキャナ")
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(bluetoothScanner.state.statusText)
                        .font(.caption2)
                        .foregroundStyle(scannerStatusColor)
                        .accessibilityIdentifier("bluetoothScannerStatus")
                }
                Spacer()
                if case .searching = bluetoothScanner.state {
                    ProgressView()
                        .tint(AppTheme.green)
                }
            }

            if bluetoothScanner.isConnected {
                configurationStatus

                Button(role: .destructive) {
                    bluetoothScanner.disconnect()
                } label: {
                    Label("スキャナを切断", systemImage: "xmark.circle")
                        .font(.subheadline.weight(.bold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("disconnectBluetoothScannerButton")
            } else {
                Button {
                    bluetoothScanner.startDiscovery()
                } label: {
                    Label(
                        isSearching ? "検索中…" : "スキャナを検索",
                        systemImage: "antenna.radiowaves.left.and.right"
                    )
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.green)
                .disabled(isSearching)
                .accessibilityIdentifier("searchBluetoothScannerButton")

                if !bluetoothScanner.devices.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(bluetoothScanner.devices) { device in
                            Button {
                                bluetoothScanner.connect(device)
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "barcode")
                                        .foregroundStyle(AppTheme.green)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(device.name)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(AppTheme.ink)
                                        Text("タップして接続")
                                            .font(.caption2)
                                            .foregroundStyle(AppTheme.muted)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(AppTheme.muted)
                                }
                                .padding(12)
                                .background(AppTheme.ink.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                            .disabled(isConnecting)
                            .accessibilityIdentifier("bluetoothScannerDevice_\(device.id)")
                        }
                    }
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Label(
                    "iPhoneの「設定 > Bluetooth」ではペアリングせず、この画面から接続してください。",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .foregroundStyle(AppTheme.amber)

                Text("BCST-47をGATT／APPモードにして接続します。見つからない場合は、iOSに登録済みのBCST-47を解除してから本体の電源を入れ直してください。")
                    .foregroundStyle(AppTheme.muted)
            }
            .font(.caption2)
            .lineSpacing(3)
            .accessibilityIdentifier("bluetoothScannerConnectionHelp")

            if !bluetoothScanner.diagnosticEvents.isEmpty {
                DisclosureGroup("接続診断（直近20件）") {
                    VStack(alignment: .leading, spacing: 7) {
                        ForEach(bluetoothScanner.diagnosticEvents.reversed()) { event in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Text(event.date, style: .time)
                                    .foregroundStyle(AppTheme.muted)
                                Text(event.message)
                                    .foregroundStyle(AppTheme.ink)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    .font(.caption2.monospaced())
                    .padding(.top, 8)
                    .accessibilityIdentifier("bluetoothDiagnosticEvents")
                }
                .font(.caption.weight(.bold))
                .tint(AppTheme.green)
                .accessibilityIdentifier("bluetoothDiagnosticsDisclosure")
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    @ViewBuilder
    private var configurationStatus: some View {
        switch bluetoothScanner.configurationState {
        case .ready:
            Label(
                bluetoothScanner.persistedSymbologyMode.statusText,
                systemImage: "checkmark.circle.fill"
            )
                .foregroundStyle(AppTheme.green)
        case .configuring:
            Label("QR／Code 128の読み取り設定を確認中です…", systemImage: "gearshape.2.fill")
                .foregroundStyle(AppTheme.muted)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(AppTheme.red)
        case .unavailable:
            EmptyView()
        }
    }

    private var isSearching: Bool {
        if case .searching = bluetoothScanner.state { return true }
        return false
    }

    private var isConnecting: Bool {
        if case .connecting = bluetoothScanner.state { return true }
        return false
    }

    private var scannerStatusColor: Color {
        switch bluetoothScanner.state {
        case .connected: AppTheme.green
        case .failed, .unavailable: AppTheme.red
        default: AppTheme.muted
        }
    }

    private var volumeCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label {
                    Text("音量")
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                } icon: {
                    Image(systemName: "speaker.wave.3.fill")
                        .foregroundStyle(AppTheme.green)
                }
                Spacer()
                Text("\(Int(volume * 100))%")
                    .font(.system(.subheadline, design: .monospaced, weight: .bold))
                    .foregroundStyle(AppTheme.green)
                    .contentTransition(.numericText())
            }

            HStack(spacing: 12) {
                Image(systemName: "speaker.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
                Slider(value: $volume, in: 0...1, step: 0.05) { editing in
                    if !editing {
                        player.success()
                    }
                }
                .tint(AppTheme.green)
                .accessibilityIdentifier("volumeSlider")
                .accessibilityLabel("効果音の音量")
                Image(systemName: "speaker.wave.3.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.muted)
            }

            Text("スライダーを離すと現在の成功音でプレビューされます")
                .font(.caption2)
                .foregroundStyle(AppTheme.muted)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }

    private func soundCard(
        title: String,
        subtitle: String,
        icon: String,
        tint: Color,
        @ViewBuilder rows: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundStyle(tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(AppTheme.ink)
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(AppTheme.muted)
                }
            }
            VStack(spacing: 8, content: rows)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .scannerCard()
    }
}

private struct SoundOptionRow: View {
    let label: String
    let isSelected: Bool
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? tint : AppTheme.line)
                Text(label)
                    .font(.subheadline.weight(isSelected ? .bold : .regular))
                    .foregroundStyle(AppTheme.ink)
                Spacer()
                Image(systemName: "play.circle")
                    .font(.title3)
                    .foregroundStyle(AppTheme.muted.opacity(0.7))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(
                isSelected ? tint.opacity(0.08) : AppTheme.ink.opacity(0.03),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? tint.opacity(0.5) : .clear, lineWidth: 1.5)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label)\(isSelected ? "、選択中" : "")")
        .accessibilityHint("タップで選択して試聴します")
    }
}

#Preview {
    SettingsScreen(bluetoothScanner: BluetoothScannerService())
}
