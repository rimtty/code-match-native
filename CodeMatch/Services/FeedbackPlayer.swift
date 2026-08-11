import AudioToolbox
import UIKit

@MainActor
final class FeedbackPlayer {
    func scanAccepted() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        AudioServicesPlaySystemSound(1108)
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
