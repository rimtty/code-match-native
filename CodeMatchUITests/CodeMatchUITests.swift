import XCTest

final class CodeMatchUITests: XCTestCase {
    func testMockBluetoothScannerConnectsAndCompletesMatch() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory", "-demoBluetoothConnected"]
        app.launch()

        app.buttons["startSessionButton"].tap()
        let inputPicker = app.segmentedControls["scanInputSourcePicker"]
        XCTAssertTrue(inputPicker.waitForExistence(timeout: 5))
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)
        XCTAssertTrue(app.staticTexts["BCST-47 (Simulator)"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["1  四角いQRコード"].waitForExistence(timeout: 3))

        // Bluetoothからカメラへ切り替えた際にもプレビュー層が表示され、
        // 再びBluetoothへ戻して照合を継続できることを確認する。
        inputPicker.buttons["カメラ"].tap()
        XCTAssertTrue(inputPicker.buttons["カメラ"].isSelected)
        XCTAssertTrue(app.descendants(matching: .any)["cameraStage"].waitForExistence(timeout: 3))
        inputPicker.buttons["Bluetooth"].tap()
        XCTAssertTrue(inputPicker.buttons["Bluetooth"].isSelected)

        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()

        // Code 128を先に読んでもQR工程のままで、誤った値を照合へ進めない。
        app.buttons["demoBluetoothBarcodeButton"].tap()
        XCTAssertTrue(app.staticTexts["読み取り順序が違います。先に納品書兼現品票のQRコードを読み取ってください。 読み取った値は照合に使用していません。"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["scannerTitle"].label, "QRコードを読み取る")

        app.buttons["demoBluetoothQRButton"].tap()
        XCTAssertTrue(app.staticTexts["バーコードを読み取る"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["2  横長のCode 128"].waitForExistence(timeout: 3))
        app.buttons["demoBluetoothBarcodeButton"].tap()

        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")
    }

    func testSettingsDiscoversAndConnectsMockScanner() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory", "-resetBluetoothScanner"]
        app.launch()

        app.tabBars.buttons["設定"].tap()
        let searchButton = app.buttons["searchBluetoothScannerButton"]
        XCTAssertTrue(searchButton.waitForExistence(timeout: 5))
        searchButton.tap()
        let device = app.buttons["bluetoothScannerDevice_SIMULATOR-BCST-47"]
        XCTAssertTrue(device.waitForExistence(timeout: 3))
        device.tap()

        XCTAssertTrue(app.staticTexts["bluetoothScannerStatus"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.staticTexts["bluetoothScannerStatus"].label, "BCST-47 (Simulator) 接続済み")
        XCTAssertTrue(app.buttons["disconnectBluetoothScannerButton"].exists)
    }

    /// スキャナーの主要フローを1回のアプリ起動でまとめて検証する。
    /// 起動が最も時間を要するため、一致→重複→リセット→不一致を連続で確認する。
    func testScannerFlowMatchDuplicateResetAndMismatch() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory", "-demoMatch"]
        app.launch()

        // 起動引数による一致状態と件数
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "1件照合済み")

        // 同じ品番を再照合すると2箱目としてそのまま記録され、件数も増える
        app.swipeUp()
        let demoToggle = app.staticTexts["カメラなしで判定をテスト"]
        XCTAssertTrue(demoToggle.waitForExistence(timeout: 3))
        demoToggle.tap()
        let matchButton = app.buttons["demoMatchButton"]
        XCTAssertTrue(matchButton.waitForExistence(timeout: 3))
        matchButton.tap()
        XCTAssertTrue(app.staticTexts["一致しました"].waitForExistence(timeout: 5))
        let countIncremented = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "2件照合済み"),
            object: app.staticTexts["sessionMatchCount"]
        )
        XCTAssertEqual(XCTWaiter.wait(for: [countIncremented], timeout: 5), .completed)

        // リセットでQR読み取りステップへ戻る
        let resetButton = app.buttons["resetButton"]
        XCTAssertTrue(resetButton.waitForExistence(timeout: 5))
        resetButton.tap()
        let scannerTitle = app.staticTexts["scannerTitle"]
        XCTAssertTrue(scannerTitle.waitForExistence(timeout: 5))
        let resetCompleted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "QRコードを読み取る"),
            object: scannerTitle
        )
        XCTAssertEqual(XCTWaiter.wait(for: [resetCompleted], timeout: 5), .completed)

        // 不一致は両方の品番を表示し、件数に加算されない
        app.swipeUp()
        let mismatchButton = app.buttons["demoMismatchButton"]
        XCTAssertTrue(mismatchButton.waitForExistence(timeout: 3))
        mismatchButton.tap()
        XCTAssertTrue(app.staticTexts["一致しません"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["BCJH-52-81GG"].exists)
        XCTAssertTrue(app.staticTexts["BCJH-55-81GG"].exists)
        XCTAssertEqual(app.staticTexts["sessionMatchCount"].label, "2件照合済み")
    }

    func testSessionCanStartAndShowsMatchCount() {
        let app = XCUIApplication()
        app.launchArguments += ["-resetHistory"]
        app.launch()

        let startButton = app.buttons["startSessionButton"]
        XCTAssertTrue(startButton.waitForExistence(timeout: 5))
        // 緑のボタン中央だけでなく、左端寄りでも操作できることを確認する。
        startButton.coordinate(withNormalizedOffset: CGVector(dx: 0.08, dy: 0.5)).tap()

        let count = app.staticTexts["sessionMatchCount"]
        XCTAssertTrue(count.waitForExistence(timeout: 3))
        XCTAssertEqual(count.label, "0件照合済み")
        XCTAssertTrue(app.buttons["endSessionButton"].exists)

        app.buttons["endSessionButton"].tap()
        app.alerts.buttons["終了する"].tap()
        XCTAssertTrue(app.buttons["startSessionButton"].waitForExistence(timeout: 3))

        // 照合0件のセッションは履歴に掲載されない
        app.tabBars.buttons["履歴"].tap()
        XCTAssertTrue(app.navigationBars["照合履歴"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["履歴はまだありません"].waitForExistence(timeout: 3))
        XCTAssertFalse(app.buttons["historySessionRow"].exists)
    }

    func testSettingsTabAllowsSoundSelection() {
        let app = XCUIApplication()
        app.launch()

        app.tabBars.buttons["設定"].tap()
        let volumeSlider = app.sliders["volumeSlider"]
        XCTAssertTrue(volumeSlider.waitForExistence(timeout: 5))

        // 成功音をチャイムへ変更すると選択状態が反映される
        let chime = app.buttons["successSound_chime"]
        XCTAssertTrue(chime.waitForExistence(timeout: 3))
        chime.tap()
        XCTAssertTrue(app.buttons["チャイム（3音）、選択中"].waitForExistence(timeout: 3))

        // 失敗音をブザーへ変更
        app.buttons["failureSound_buzzer"].tap()
        XCTAssertTrue(app.buttons["ブブー（ブザー）、選択中"].waitForExistence(timeout: 3))

        // 音量スライダーを操作できる
        volumeSlider.adjust(toNormalizedSliderPosition: 0.6)

        // 既定値へ戻す
        app.buttons["successSound_posBeep"].tap()
        app.buttons["failureSound_alarm"].tap()
        XCTAssertTrue(app.buttons["ピッ（POSレジ風・標準）、選択中"].waitForExistence(timeout: 3))
    }
}
