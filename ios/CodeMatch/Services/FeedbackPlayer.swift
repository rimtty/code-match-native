import AVFoundation
import UIKit

/// 効果音設定。UserDefaultsで永続化し、設定画面とFeedbackPlayerで共有する。
enum FeedbackSettings {
    static let volumeKey = "feedbackVolume"
    static let successSoundKey = "successSound"
    static let failureSoundKey = "failureSound"
    static let defaultVolume = 1.0

    static var volume: Double {
        UserDefaults.standard.object(forKey: volumeKey) as? Double ?? defaultVolume
    }

    static var successSound: SuccessSound {
        SuccessSound(rawValue: UserDefaults.standard.string(forKey: successSoundKey) ?? "") ?? .posBeep
    }

    static var failureSound: FailureSound {
        FailureSound(rawValue: UserDefaults.standard.string(forKey: failureSoundKey) ?? "") ?? .alarm
    }
}

enum SuccessSound: String, CaseIterable, Identifiable {
    case sample1
    case sample2
    case posBeep
    case doubleBeep
    case chime

    var id: String { rawValue }

    var label: String {
        switch self {
        case .sample1: AppLocalization.string("サウンド1")
        case .sample2: AppLocalization.string("サウンド2")
        case .posBeep: AppLocalization.string("ピッ（POSレジ風・標準）")
        case .doubleBeep: AppLocalization.string("ピピッ（2回）")
        case .chime: AppLocalization.string("チャイム（3音）")
        }
    }

    /// バンドル内の音源ファイル名。nilなら合成音を使う。
    var fileName: String? {
        switch self {
        case .sample1: "success1"
        case .sample2: "success2"
        case .posBeep, .doubleBeep, .chime: nil
        }
    }
}

enum FailureSound: String, CaseIterable, Identifiable {
    case failSample
    case buzzer
    case alarm
    case descend

    var id: String { rawValue }

    var label: String {
        switch self {
        case .failSample: AppLocalization.string("サウンド1")
        case .buzzer: AppLocalization.string("ブブー（ブザー）")
        case .alarm: AppLocalization.string("ピピピピ（アラーム・標準）")
        case .descend: AppLocalization.string("ブーー（下降音）")
        }
    }

    /// バンドル内の音源ファイル名。nilなら合成音を使う。
    var fileName: String? {
        switch self {
        case .failSample: "Fail1"
        case .buzzer, .alarm, .descend: nil
        }
    }
}

@MainActor
final class FeedbackPlayer {
    private struct Tone {
        let frequency: Double
        let duration: TimeInterval
        let amplitude: Float
        /// trueのとき奇数倍音を加えてPOSレジのような鋭く通る音にする
        var piercing: Bool = false
    }

    private let audioEngine = AVAudioEngine()
    private let audioPlayer = AVAudioPlayerNode()
    private let sampleRate = 44_100.0
    private lazy var audioFormat = AVAudioFormat(
        standardFormatWithSampleRate: sampleRate,
        channels: 1
    )!
    /// バンドル音源のプレイヤーキャッシュ(ファイル名 → プレイヤー)
    private var filePlayers: [String: AVAudioPlayer] = [:]

    /// AVAudioEngineのセットアップは重く、生成のたびにオーディオ通知を発生させて
    /// SwiftUIの再描画ループを誘発しうるため、アプリ全体で1インスタンスを共有する。
    static let shared = FeedbackPlayer()

    private init() {
        audioEngine.attach(audioPlayer)
        audioEngine.connect(audioPlayer, to: audioEngine.mainMixerNode, format: audioFormat)
        audioEngine.prepare()
    }

    func scanAccepted() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        // 各コード受理時は控えめな短いブリップ(最終判定音より小さめ)
        play([
            Tone(frequency: 1_567.98, duration: 0.06, amplitude: 0.45, piercing: true)
        ], gap: 0, volumeScale: 0.6)
    }

    /// 入力順序または業務フォーマットが違う読取を、最終照合の不一致音とは区別して通知する。
    func invalidScan() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        play([
            Tone(frequency: 330, duration: 0.09, amplitude: 0.65),
            Tone(frequency: 330, duration: 0.09, amplitude: 0.65)
        ], gap: 0.06, volumeScale: 0.7)
    }

    func success(after delay: TimeInterval = 0) {
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        let playSound: () -> Void = { [weak self] in
            guard let self else { return }
            let sound = FeedbackSettings.successSound
            if let fileName = sound.fileName {
                self.playFile(named: fileName)
                return
            }
            switch sound {
            case .sample1, .sample2:
                break
            case .posBeep:
                self.play([
                    Tone(frequency: 2_600, duration: 0.12, amplitude: 0.95, piercing: true)
                ], gap: 0)
            case .doubleBeep:
                self.play([
                    Tone(frequency: 2_600, duration: 0.08, amplitude: 0.95, piercing: true),
                    Tone(frequency: 2_600, duration: 0.08, amplitude: 0.95, piercing: true)
                ], gap: 0.06)
            case .chime:
                self.play([
                    Tone(frequency: 523.25, duration: 0.09, amplitude: 0.80),
                    Tone(frequency: 659.25, duration: 0.09, amplitude: 0.85),
                    Tone(frequency: 783.99, duration: 0.18, amplitude: 0.90)
                ], gap: 0.035)
            }
        }

        if delay > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: playSound)
        } else {
            playSound()
        }
    }

    func failure() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        let sound = FeedbackSettings.failureSound
        if let fileName = sound.fileName {
            playFile(named: fileName)
            return
        }
        switch sound {
        case .failSample:
            break
        case .buzzer:
            play([
                Tone(frequency: 165, duration: 0.16, amplitude: 0.95, piercing: true),
                Tone(frequency: 165, duration: 0.42, amplitude: 0.95, piercing: true)
            ], gap: 0.07)
        case .alarm:
            play([
                Tone(frequency: 980, duration: 0.11, amplitude: 0.92, piercing: true),
                Tone(frequency: 980, duration: 0.11, amplitude: 0.92, piercing: true),
                Tone(frequency: 980, duration: 0.11, amplitude: 0.92, piercing: true),
                Tone(frequency: 980, duration: 0.11, amplitude: 0.92, piercing: true)
            ], gap: 0.09)
        case .descend:
            play([
                Tone(frequency: 440, duration: 0.18, amplitude: 0.92, piercing: true),
                Tone(frequency: 220, duration: 0.45, amplitude: 0.95, piercing: true)
            ], gap: 0.04)
        }
    }

    /// 設定画面のプレビュー再生用
    func preview(success sound: SuccessSound) {
        let saved = FeedbackSettings.successSound
        UserDefaults.standard.set(sound.rawValue, forKey: FeedbackSettings.successSoundKey)
        success()
        if saved != sound {
            UserDefaults.standard.set(saved.rawValue, forKey: FeedbackSettings.successSoundKey)
        }
    }

    func preview(failure sound: FailureSound) {
        let saved = FeedbackSettings.failureSound
        UserDefaults.standard.set(sound.rawValue, forKey: FeedbackSettings.failureSoundKey)
        failure()
        if saved != sound {
            UserDefaults.standard.set(saved.rawValue, forKey: FeedbackSettings.failureSoundKey)
        }
    }

    /// バンドル内の音源ファイルを設定音量で再生する
    private func playFile(named name: String) {
        guard let url = Bundle.main.url(forResource: name, withExtension: "mp3") else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            // 工場現場向け: マナーモード(サイレントスイッチ)でも判定音を再生する
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            let player: AVAudioPlayer
            if let cached = filePlayers[name] {
                player = cached
            } else {
                player = try AVAudioPlayer(contentsOf: url)
                player.prepareToPlay()
                filePlayers[name] = player
            }
            player.volume = Float(FeedbackSettings.volume)
            player.currentTime = 0
            player.play()
        } catch {
            // Haptic feedback still communicates the result if audio is unavailable.
        }
    }

    private func play(_ tones: [Tone], gap: TimeInterval, volumeScale: Float = 1.0) {
        guard let buffer = makeBuffer(tones: tones, gap: gap) else { return }

        do {
            let session = AVAudioSession.sharedInstance()
            // 工場現場向け: マナーモード(サイレントスイッチ)でも判定音を再生する
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            if !audioEngine.isRunning {
                try audioEngine.start()
            }

            audioPlayer.volume = Float(FeedbackSettings.volume) * volumeScale
            audioPlayer.stop()
            audioPlayer.scheduleBuffer(buffer, at: nil, options: .interrupts)
            audioPlayer.play()
        } catch {
            // Haptic feedback still communicates the result if audio is unavailable.
        }
    }

    private func makeBuffer(tones: [Tone], gap: TimeInterval) -> AVAudioPCMBuffer? {
        let gapFrames = Int(gap * sampleRate)
        let toneFrames = tones.map { Int($0.duration * sampleRate) }
        let totalFrames = toneFrames.reduce(0, +) + (max(tones.count - 1, 0) * gapFrames)
        guard totalFrames > 0,
              let buffer = AVAudioPCMBuffer(
                pcmFormat: audioFormat,
                frameCapacity: AVAudioFrameCount(totalFrames)
              ),
              let samples = buffer.floatChannelData?[0]
        else { return nil }

        buffer.frameLength = AVAudioFrameCount(totalFrames)
        var frameOffset = 0

        for (index, tone) in tones.enumerated() {
            let frameCount = toneFrames[index]
            let edgeFrames = min(Int(0.006 * sampleRate), max(frameCount / 2, 1))

            for frame in 0..<frameCount {
                let attack = min(Float(frame) / Float(edgeFrames), 1)
                let release = min(Float(frameCount - frame - 1) / Float(edgeFrames), 1)
                let envelope = max(min(attack, release), 0)
                let phase = 2 * Double.pi * tone.frequency * Double(frame) / sampleRate

                var sample = sin(phase)
                if tone.piercing {
                    // 奇数倍音を加えた擬似矩形波。小さなスピーカーでも通る音になる
                    sample += sin(phase * 3) / 3 + sin(phase * 5) / 5
                    sample *= 0.85
                }
                samples[frameOffset + frame] = Float(sample) * tone.amplitude * envelope
            }

            frameOffset += frameCount
            if index < tones.count - 1 {
                for frame in 0..<gapFrames {
                    samples[frameOffset + frame] = 0
                }
                frameOffset += gapFrames
            }
        }

        return buffer
    }
}
