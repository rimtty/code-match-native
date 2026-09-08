// Driver for ios/scripts/verify_label_pairs.sh.
//
// Reads the TSV written by shared/tools/decode_label_photos.swift, pairs the QR
// and the Code 128 found in the same photo, and runs each pair through the
// production matching core (ios/CodeMatch/Models/ScanModels.swift, compiled
// into this executable unchanged). Photos that carry only a Code 128 are
// reported as tag-only checks. The summary at the end is what to look at
// first: every real pair must be "<destination>/tag=true/match".
import Foundation

// The app's localization helper is UI code; the matching core only needs the
// identity mapping to compile outside the app target.
enum AppLocalization {
    static func string(_ key: String) -> String { key }
    static func string(_ key: String.LocalizationValue) -> String { "\(key)" }
}

guard CommandLine.arguments.count >= 2 else {
    FileHandle.standardError.write(Data("usage: verify_label_pairs <decoded.tsv>\n".utf8))
    exit(2)
}

let tsv = try String(contentsOfFile: CommandLine.arguments[1], encoding: .utf8)
var qrs: [String: [String]] = [:]
var tags: [String: [String]] = [:]
var order: [String] = []
for line in tsv.split(separator: "\n") {
    let columns = line.split(separator: "\t", omittingEmptySubsequences: false).map(String.init)
    guard columns.count == 4, columns[3].hasPrefix("["), columns[3].hasSuffix("]") else { continue }
    let photo = columns[0]
    let payload = String(columns[3].dropFirst().dropLast())
    if !order.contains(photo) { order.append(photo) }
    if columns[1].hasSuffix("QR") { qrs[photo, default: []].append(payload) }
    if columns[1].hasSuffix("Code128") { tags[photo, default: []].append(payload) }
}

var summary: [String: Int] = [:]
print("photo\tdestination\tqrPart\ttagValid\tcompare\tboxIdentity")
for photo in order {
    let qrList = qrs[photo] ?? []
    let tagList = tags[photo] ?? []
    if qrList.isEmpty {
        for tag in tagList {
            let valid = TagBarcodeRecord.isValidScanPayload(tag, destination: nil)
            print("\(photo)\t(tag only)\t-\t\(valid)\t\(tag)\t-")
            summary["tag-only/valid=\(valid)", default: 0] += 1
        }
        continue
    }
    for qr in qrList {
        let destination = Destination.detect(qrPayload: qr)
        let name = destination?.rawValue ?? "nil"
        let part = CodeMatcher.partNumber(fromQR: qr) ?? "-"
        let box = BoxIdentity.make(qrPayload: qr, barcodePayload: tagList.first).map { String($0.prefix(12)) } ?? "-"
        if tagList.isEmpty {
            print("\(photo)\t\(name)\t\(part)\t(no tag)\t-\t\(box)")
            summary["qr-only/\(name)", default: 0] += 1
        }
        for tag in tagList {
            let valid = TagBarcodeRecord.isValidScanPayload(tag, destination: destination)
            let result = CodeMatcher.compare(qrPayload: qr, barcodePayload: tag)
            print("\(photo)\t\(name)\t\(part)\t\(valid)\t\(result)\t\(box)")
            summary["\(name)/tag=\(valid)/\(result)", default: 0] += 1
        }
    }
}

print("--- summary ---")
for (key, count) in summary.sorted(by: { $0.key < $1.key }) {
    print("\(key)\t\(count)")
}

// Guards that must keep holding whatever the acceptance rules become.
print("--- guards ---")
print("X*66 as QR:", Destination.detect(qrPayload: String(repeating: "X", count: 66)).map { $0.rawValue } ?? "nil")
print("sawai tag as QR:", Destination.detect(qrPayload: "BCJH-52-81GG@1N5X0C").map { $0.rawValue } ?? "nil")
print("6-4 tag in sawai:", TagBarcodeRecord.isValidScanPayload("860150-7722@1DZ50O", destination: .sawai))
print("4-2-3 tag in denso:", TagBarcodeRecord.isValidScanPayload("PAF1-15-422@0NKD3C", destination: .denso))
