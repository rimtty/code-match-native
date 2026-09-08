// Decode every barcode (QR / Code 128 / Code 39 …) in a directory of label photos
// with Vision and print one TSV line per symbol:
//
//   <file>\t<symbology>\tlen=<count>\t[<payload>]
//
// Control characters are escaped (\r \n \0 \xNN) so trailing spaces and
// transport terminators stay visible. Usage (macOS, Xcode installed):
//
//   swiftc -O shared/tools/decode_label_photos.swift -o /tmp/decode_label_photos
//   /tmp/decode_label_photos <photo-directory> > decoded.tsv
//
// The photos are customer data: keep them outside the repository (for example
// the git-ignored tmp/ directory) and commit only the decoded payload strings,
// as shared/test-fixtures/matching-cases.json does. See
// docs/label-variation-playbook.html for the full procedure.
import AppKit
import Foundation
import Vision

guard CommandLine.arguments.count >= 2 else {
    FileHandle.standardError.write(Data("usage: decode_label_photos <photo-directory>\n".utf8))
    exit(2)
}

let directory = URL(fileURLWithPath: CommandLine.arguments[1])
let files = try FileManager.default.contentsOfDirectory(atPath: directory.path)
    .filter { name in
        let lower = name.lowercased()
        return lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") || lower.hasSuffix(".png") || lower.hasSuffix(".heic")
    }
    .sorted()

func escape(_ value: String) -> String {
    value.unicodeScalars.map { scalar -> String in
        switch scalar {
        case "\r": return "\\r"
        case "\n": return "\\n"
        case "\0": return "\\0"
        default:
            return scalar.value < 32 || scalar.value == 127
                ? String(format: "\\x%02X", scalar.value)
                : String(scalar)
        }
    }.joined()
}

for file in files {
    let url = directory.appendingPathComponent(file)
    guard let image = NSImage(contentsOf: url),
          let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        print("\(file)\tLOAD_FAIL")
        continue
    }
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr, .code128, .code39, .code93, .ean13, .itf14, .dataMatrix, .pdf417]
    let handler = VNImageRequestHandler(cgImage: cgImage, orientation: .up, options: [:])
    try handler.perform([request])
    let results = request.results ?? []
    if results.isEmpty {
        print("\(file)\tNONE")
        continue
    }
    for result in results {
        let payload = result.payloadStringValue ?? ""
        print("\(file)\t\(result.symbology.rawValue)\tlen=\(payload.count)\t[\(escape(payload))]")
    }
}
