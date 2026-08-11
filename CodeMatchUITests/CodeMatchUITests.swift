import XCTest

final class CodeMatchUITests: XCTestCase {
    func testDemoMatchResultAndReset() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory", "-demoMatch"]
        app.launch()

        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        app.swipeUp()
        let resetButton = app.buttons["resetButton"]
        XCTAssertTrue(resetButton.waitForExistence(timeout: 2))
        resetButton.tap()
        let scannerTitle = app.staticTexts["scannerTitle"]
        XCTAssertTrue(scannerTitle.waitForExistence(timeout: 2))
        let resetCompleted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [resetCompleted], timeout: 2), .completed)
    }

    func testSessionCanStartAndShowsMatchCount() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory"]
        app.launch()

        let startButton = app.buttons["startSessionButton"]
        XCTAssertTrue(startButton.waitForExistence(timeout: 3))
        startButton.tap()

        let count = app.staticTexts["sessionMatchCount"]
        XCTAssertTrue(count.waitForExistence(timeout: 2))
        XCTAssertEqual(count.label, "0件照合済み")
        XCTAssertTrue(app.buttons["endSessionButton"].exists)

        app.buttons["endSessionButton"].tap()
        app.alerts.buttons["終了する"].tap()
        XCTAssertTrue(app.buttons["startSessionButton"].waitForExistence(timeout: 2))

        app.tabBars.buttons["履歴"].tap()
        XCTAssertTrue(app.navigationBars["照合履歴"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["historySessionRow"].exists)
    }
}
