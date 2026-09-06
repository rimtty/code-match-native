import XCTest
import AVFoundation
import Combine
@testable import CodeMatch

final class AutoAdvanceSettingsTests: XCTestCase {
    func testDefaultsAreOffWithAThreeSecondCountdown() {
        let suiteName = "AutoAdvanceSettingsTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertFalse(AutoAdvanceSettings.isEnabled(in: defaults))
        XCTAssertEqual(AutoAdvanceSettings.delay(in: defaults), .threeSeconds)

        defaults.set(true, forKey: AutoAdvanceSettings.enabledKey)
        defaults.set(5, forKey: AutoAdvanceSettings.delaySecondsKey)

        XCTAssertTrue(AutoAdvanceSettings.isEnabled(in: defaults))
        XCTAssertEqual(AutoAdvanceSettings.delay(in: defaults), .fiveSeconds)
        XCTAssertEqual(AutoAdvanceDelay.allCases.map(\.rawValue), [1, 3, 5])
    }
}
