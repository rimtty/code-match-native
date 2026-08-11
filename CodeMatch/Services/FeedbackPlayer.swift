import AVFoundation
import AudioToolbox
import UIKit

@MainActor
final class FeedbackPlayer {
    private struct Tone {
        let frequency: Double
        let duration: TimeInterval
        let amplitude: Float
    }

    private let audioEngine = AVAudioEngine()
    private let audioPlayer = AVAudioPlayerNode()
    private let sampleRate = 44_100.0
    private lazy var audioFormat = AVAudioFormat(
        standardFormatWithSampleRate: sampleRate,
        channels: 1
    )!

    init() {
        audioEngine.attach(audioPlayer)
        audioEngine.connect(audioPlayer, to: audioEngine.mainMixerNode, format: audioFormat)
        audioEngine.prepare()
    }

    func scanAccepted() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        play([
            Tone(frequency: 1_046.50, duration: 0.07, amplitude: 0.30),
            Tone(frequency: 1_568.00, duration: 0.10, amplitude: 0.34)
        ], gap: 0.025)
    }

    func success(after delay: TimeInterval = 0) {
        UINotificationFeedbackGenerator().notificationOccurred(.success)

        let playSuccessChime: () -> Void = { [weak self] in
            guard let self else { return }
            self.play([
                Tone(frequency: 523.25, duration: 0.09, amplitude: 0.30),
                Tone(frequency: 659.25, duration: 0.09, amplitude: 0.32),
                Tone(frequency: 783.99, duration: 0.16, amplitude: 0.36)
            ], gap: 0.035)
        }

        if delay > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: playSuccessChime)
        } else {
            playSuccessChime()
        }
    }

    func failure() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        for index in 0..<4 {
            DispatchQueue.main.asyncAfter(deadline: .now() + (Double(index) * 0.20)) {
                AudioServicesPlaySystemSound(1053)
            }
        }
    }

    private func play(_ tones: [Tone], gap: TimeInterval) {
        guard let buffer = makeBuffer(tones: tones, gap: gap) else { return }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            if !audioEngine.isRunning {
                try audioEngine.start()
            }

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
            let edgeFrames = min(Int(0.008 * sampleRate), max(frameCount / 2, 1))

            for frame in 0..<frameCount {
                let attack = min(Float(frame) / Float(edgeFrames), 1)
                let release = min(Float(frameCount - frame - 1) / Float(edgeFrames), 1)
                let envelope = max(min(attack, release), 0)
                let phase = 2 * Double.pi * tone.frequency * Double(frame) / sampleRate
                samples[frameOffset + frame] = Float(sin(phase)) * tone.amplitude * envelope
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
