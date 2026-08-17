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
        XCTAssertTrue(scannerTitle.waitForExistence(timeout: 5))
        let resetCompleted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [resetCompleted], timeout: 5), .completed)
    }

    func testDemoMismatchShowsBothPartNumbersAndKeepsCountAtZero() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory"]
        app.launch()

        let startButton = app.buttons["startSessionButton"]
        XCTAssertTrue(startButton.waitForExistence(timeout: 3))
        startButton.tap()

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 2))
        demoToggle.tap()

        let mismatchButton = app.buttons["demoMismatchButton"]
        XCTAssertTrue(mismatchButton.waitForExistence(timeout: 2))
        mismatchButton.tap()

        XCTAssertTrue(app.staticTexts["一致しません"].waitForExistence(timeout: 3))
        // 不一致時は両方の品番が表示され、取り違えを目視確認できる
        XCTAssertTrue(app.staticTexts["BCJH-52-81GG"].exists)
        XCTAssertTrue(app.staticTexts["BCJH-55-81GG"].exists)
        // 不一致は照合済み件数に加算されない
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "0件照合済み")
    }

    func testDuplicateMatchIsFlaggedAndNotRecorded() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory"]
        app.launch()

        let startButton = app.buttons["startSessionButton"]
        XCTAssertTrue(startButton.waitForExistence(timeout: 3))
        startButton.tap()

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 2))
        demoToggle.tap()

        let matchButton = app.buttons["demoMatchButton"]
        XCTAssertTrue(matchButton.waitForExistence(timeout: 2))
        matchButton.tap()
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // 同じ品番をもう一度照合すると「照合済み」表示になり、件数は増えない
        app.swipeUp()
        XCTAssertTrue(matchButton.waitForExistence(timeout: 2))
        matchButton.tap()
        XCTAssertTrue(app.staticTexts["すでに照合済みです"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")
    }

    func testSettingsTabAllowsSoundSelection() {
        let app = XCUIApplication()
        app.launch()

        app.tabBars.buttons["設定"].tap()
        let volumeSlider = app.sliders["volumeSlider"]
        XCTAssertTrue(volumeSlider.waitForExistence(timeout: 3))

        // 成功音をチャイムへ変更すると選択状態が反映される
        let chime = app.buttons["successSound_chime"]
        XCTAssertTrue(chime.waitForExistence(timeout: 2))
        chime.tap()
        XCTAssertTrue(app.buttons["チャイム（3音）、選択中"].waitForExistence(timeout: 2))

        // 失敗音をブザーへ変更
        app.buttons["failureSound_buzzer"].tap()
        XCTAssertTrue(app.buttons["ブブー（ブザー）、選択中"].waitForExistence(timeout: 2))

        // 音量スライダーを操作できる
        volumeSlider.adjust(toNormalizedSliderPosition: 0.6)

        // 確認用スクリーンショットを添付
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "settings-screen"
        attachment.lifetime = .keepAlways
        add(attachment)

        // 既定値へ戻す
        app.buttons["successSound_posBeep"].tap()
        app.buttons["failureSound_alarm"].tap()
        XCTAssertTrue(app.buttons["ピッ（POSレジ風・標準）、選択中"].waitForExistence(timeout: 2))
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
