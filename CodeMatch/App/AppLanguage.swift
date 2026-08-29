import Foundation
import SwiftUI

enum AppLanguage: String, CaseIterable, Identifiable {
    case japanese = "ja"
    case english = "en"

    static let storageKey = "CodeMatch.AppLanguage"
    static let uiTestLanguageResetArgument = "-resetLanguage"

    static let fallback: AppLanguage = .japanese

    static func prepareForLaunch() {
        guard ProcessInfo.processInfo.arguments.contains(uiTestLanguageResetArgument) else {
            return
        }
        UserDefaults.standard.removeObject(forKey: storageKey)
    }

    private static let englishLocalizationBundle: Bundle? = {
        guard let path = Bundle.main.path(forResource: AppLanguage.english.rawValue, ofType: "lproj") else {
            return nil
        }
        return Bundle(path: path)
    }()

    static var current: AppLanguage {
        AppLanguage(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? fallback.rawValue) ?? fallback
    }

    var id: String { rawValue }

    init(_ locale: Locale) {
        if locale.identifier.hasPrefix("ja") {
            self = .japanese
        } else {
            self = .english
        }
    }

    var locale: Locale {
        switch self {
        case .japanese:
            Locale(identifier: "ja_JP")
        case .english:
            Locale(identifier: "en_US")
        }
    }

    var localizationBundle: Bundle {
        switch self {
        case .japanese:
            // The string catalog's source language is Japanese, so its source
            // values are resolved directly from the main bundle.
            return .main
        case .english:
            return Self.englishLocalizationBundle ?? .main
        }
    }

    var displayName: String {
        switch self {
        case .japanese:
            "日本語"
        case .english:
            "English"
        }
    }

    func formatInteger(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.locale = locale
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    func formatQuantity(_ value: Double) -> String {
        if value.truncatingRemainder(dividingBy: 1) == 0 {
            return formatInteger(Int(value))
        }
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.locale = locale
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    func formatDateTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    func formatTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}

enum AppLocalization {
    static func string(_ value: String.LocalizationValue) -> String {
        let language = AppLanguage.current
        return String(
            localized: value,
            bundle: language.localizationBundle,
            locale: language.locale
        )
    }
}

extension Locale {
    var appLanguage: AppLanguage {
        AppLanguage(self)
    }
}
