import SwiftUI

enum AppTheme {
    static let ink = Color(red: 21 / 255, green: 27 / 255, blue: 24 / 255)
    static let muted = Color(red: 101 / 255, green: 112 / 255, blue: 106 / 255)
    static let paper = Color(red: 244 / 255, green: 243 / 255, blue: 236 / 255)
    static let green = Color(red: 14 / 255, green: 124 / 255, blue: 88 / 255)
    static let lime = Color(red: 200 / 255, green: 243 / 255, blue: 106 / 255)
    static let red = Color(red: 212 / 255, green: 70 / 255, blue: 54 / 255)
    static let amber = Color(red: 224 / 255, green: 150 / 255, blue: 32 / 255)
    static let line = Color(red: 216 / 255, green: 220 / 255, blue: 214 / 255)
}

extension View {
    func scannerCard() -> some View {
        self
            .background(.white)
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 28, style: .continuous)
                    .stroke(AppTheme.ink.opacity(0.08), lineWidth: 1)
            }
            .shadow(color: AppTheme.ink.opacity(0.10), radius: 28, y: 14)
    }
}
