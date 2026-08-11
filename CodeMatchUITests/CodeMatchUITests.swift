import XCTest

final class CodeMatchUITests: XCTestCase {
    func testDemoMatchResultAndReset() {
        let app = XCUIApplication()
        app.launchArguments += ["-demoMatch"]
        app.launch()

        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 3))

        app.buttons["resetButton"].tap()
        XCTAssertTrue(app.staticTexts["QRコードを読み取る"].waitForExistence(timeout: 2))
    }
}
