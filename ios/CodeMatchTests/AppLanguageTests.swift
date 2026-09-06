import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

final class AppLanguageTests: XCTestCase {
    private let languageStorageKey = AppLanguage.storageKey
    private var originalLanguageRawValue: String?

    override func setUp() {
        super.setUp()
        originalLanguageRawValue = UserDefaults.standard.string(forKey: languageStorageKey)
    }

    override func tearDown() {
        if let value = originalLanguageRawValue {
            UserDefaults.standard.set(value, forKey: languageStorageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: languageStorageKey)
        }
        super.tearDown()
    }

    func testCurrentLanguageFallsBackToJapaneseWithoutStoredValue() {
        UserDefaults.standard.removeObject(forKey: languageStorageKey)
        XCTAssertEqual(AppLanguage.current, .japanese)
    }

    func testCurrentLanguageFallsBackToJapaneseForInvalidStoredValue() {
        UserDefaults.standard.set("unsupported", forKey: languageStorageKey)
        XCTAssertEqual(AppLanguage.current, .japanese)
    }

    func testAppLocalizationRespectsCurrentLocaleLanguage() {
        UserDefaults.standard.set(AppLanguage.english.rawValue, forKey: languageStorageKey)
        XCTAssertEqual(AppLocalization.string("設定"), "Settings")

        UserDefaults.standard.set(AppLanguage.japanese.rawValue, forKey: languageStorageKey)
        XCTAssertEqual(AppLocalization.string("言語"), "言語")
    }

    func testEnglishLocalizationUsesNaturalPluralForms() {
        UserDefaults.standard.set(AppLanguage.english.rawValue, forKey: languageStorageKey)

        XCTAssertEqual(AppLocalization.string("\(1)箱"), "1 box")
        XCTAssertEqual(AppLocalization.string("\(2)箱"), "2 boxes")
        XCTAssertEqual(
            AppLocalization.string("これまでに\(1)セッションを端末内へ保存しています"),
            "1 session saved on this device"
        )
        XCTAssertEqual(
            AppLocalization.string("これまでに\(2)セッションを端末内へ保存しています"),
            "2 sessions saved on this device"
        )
    }
}
