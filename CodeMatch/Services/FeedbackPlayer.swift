import AudioToolbox
import UIKit

@MainActor
final class FeedbackPlayer {
    func scanAccepted() {
        // A phase transition should feel responsive without sounding like a
        // camera shutter. Live metadata scanning never captures a still image.
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        AudioServicesPlaySystemSound(1057)
    }

    func failure() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        for index in 0..<4 {
            DispatchQueue.main.asyncAfter(deadline: .now() + (Double(index) * 0.20)) {
                AudioServicesPlaySystemSound(1053)
            }
        }
    }
}
