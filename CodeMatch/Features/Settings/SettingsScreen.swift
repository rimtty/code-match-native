import SwiftUI

struct SettingsScreen: View {
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
                        Text("効果音の設定")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(AppTheme.ink)
                    }
                    .padding(.horizontal, 4)

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
    SettingsScreen()
}
