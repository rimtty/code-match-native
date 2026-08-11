import AVFoundation

enum ScanStep: Equatable {
    case qr
    case barcode
    case result(MatchResult)

    var progress: Int {
        switch self {
        case .qr: 1
        case .barcode: 2
        case .result: 3
        }
    }
}

enum MatchResult: Equatable {
    case match
    case mismatch
}

enum ExpectedCode: Equatable {
    case qr
    case barcode

    var metadataType: AVMetadataObject.ObjectType {
        switch self {
        case .qr: .qr
        case .barcode: .code128
        }
    }
}

enum CodeMatcher {
    static func compare(_ first: String, _ second: String) -> MatchResult {
        first.trimmingCharacters(in: .whitespacesAndNewlines)
            == second.trimmingCharacters(in: .whitespacesAndNewlines)
            ? .match
            : .mismatch
    }
}
