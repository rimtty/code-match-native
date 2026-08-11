import XCTest
@testable import CodeMatch

final class CodeMatcherTests: XCTestCase {
    func testEqualValuesMatch() {
        XCTAssertEqual(
            CodeMatcher.compare("GA141KR9PA02@092D10", "GA141KR9PA02@092D10"),
            .match
        )
    }

    func testSurroundingWhitespaceIsIgnored() {
        XCTAssertEqual(CodeMatcher.compare("  ABC\n", "ABC"), .match)
    }

    func testComparisonIsCaseSensitive() {
        XCTAssertEqual(CodeMatcher.compare("ABC", "abc"), .mismatch)
    }

    func testDifferentValuesMismatch() {
        XCTAssertEqual(CodeMatcher.compare("GA141KR9PA02@092D10", "GA141KR9PA02@092D11"), .mismatch)
    }
}
