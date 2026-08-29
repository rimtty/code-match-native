import SwiftUI

@main
struct CodeMatchApp: App {
    @AppStorage(AppLanguage.storageKey) private var appLanguageRawValue = AppLanguage.fallback.rawValue

    private var appLanguage: AppLanguage {
        AppLanguage(rawValue: appLanguageRawValue) ?? .fallback
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environment(\.locale, appLanguage.locale)
        }
    }
}
