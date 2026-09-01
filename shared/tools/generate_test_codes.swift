#!/usr/bin/env swift

import AppKit
import CoreImage
import Foundation

let scriptURL = URL(fileURLWithPath: #filePath).standardizedFileURL
let repositoryRoot = scriptURL
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
let defaultOutputURL = repositoryRoot
    .appendingPathComponent("shared/test-fixtures/images", isDirectory: true)
let outputURL = CommandLine.arguments.dropFirst().first.map {
    URL(fileURLWithPath: $0, isDirectory: true)
} ?? defaultOutputURL
print("Generating test codes in \(outputURL.path)")
try FileManager.default.createDirectory(at: outputURL, withIntermediateDirectories: true)

let context = CIContext()

func write(_ image: CIImage, name: String, padding: CGFloat = 32) throws {
    let translated = image.transformed(by: .init(translationX: padding, y: padding))
    let canvas = CIImage(color: .white).cropped(to: CGRect(
        x: 0,
        y: 0,
        width: image.extent.width + padding * 2,
        height: image.extent.height + padding * 2
    ))
    let composed = translated.composited(over: canvas)
    guard let cgImage = context.createCGImage(composed, from: composed.extent) else {
        throw CocoaError(.fileWriteUnknown)
    }
    let bitmap = NSBitmapImageRep(cgImage: cgImage)
    guard let data = bitmap.representation(using: .png, properties: [:]) else {
        throw CocoaError(.fileWriteUnknown)
    }
    try data.write(to: outputURL.appendingPathComponent(name))
}

func qr(_ value: String) throws -> CIImage {
    guard
        let filter = CIFilter(name: "CIQRCodeGenerator"),
        let data = value.data(using: .utf8)
    else { throw CocoaError(.featureUnsupported) }
    filter.setValue(data, forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    guard let output = filter.outputImage else { throw CocoaError(.featureUnsupported) }
    return output.transformed(by: .init(scaleX: 12, y: 12))
}

func code128(_ value: String) throws -> CIImage {
    guard
        let filter = CIFilter(name: "CICode128BarcodeGenerator"),
        let data = value.data(using: .ascii)
    else { throw CocoaError(.featureUnsupported) }
    filter.setValue(data, forKey: "inputMessage")
    filter.setValue(12, forKey: "inputQuietSpace")
    guard let output = filter.outputImage else { throw CocoaError(.featureUnsupported) }
    return output.transformed(by: .init(scaleX: 4, y: 6))
}

// 実ラベル仕様: QRは納品書兼現品票の固定長レコード、Code 128は現品票の「品番@管理コード」。
// 品番 BCJH-52-81GG のペアが一致し、BCJH-55-81GG の現品票は不一致になる。
let referenceQR = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
try write(qr(referenceQR), name: "reference-qr.png", padding: 48)
try write(code128("BCJH-52-81GG@1N5X0C"), name: "reference-code128.png", padding: 48)
try write(code128("BCJH-55-81GG@1KVV0C"), name: "mismatch-code128.png", padding: 48)

print("Generated test codes in \(outputURL.path)")
