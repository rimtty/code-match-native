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

// 実ラベル仕様: QRは納品書の固定長レコード、Code 128は現品票の「品番@管理コード」。
//
// 仕向地 澤井製作所: QRは66桁。品番 BCJH-52-81GG のペアが一致し、
// BCJH-55-81GG の現品票は不一致になる。
let referenceQR = "DCLP675300BCJH5281GG020000120000001200L000000000000BLBDILLU92   0*"
try write(qr(referenceQR), name: "reference-qr.png", padding: 48)
try write(code128("BCJH-52-81GG@1N5X0C"), name: "reference-code128.png", padding: 48)
try write(code128("BCJH-55-81GG@1KVV0C"), name: "mismatch-code128.png", padding: 48)

// 仕向地 モルテン: QRは61桁で、末尾の空白まで含めて1レコード（下の文字列を編集する際は
// 末尾の空白を消さないこと）。品番は10桁(4-2-4表記)と9桁(4-2-3表記)の2種類がある。
// 4-2-3側は同じ納品書に対して管理コード違いの現品票が複数枚出る（=別の箱）。
let moltenQR = "AK6805D10E50N10B         U543820000MB    S600700000020908    "
let moltenShortPartQR = "AK6805PAF115422          UAG5560000FA2P5901FEM000012009080000"
try write(qr(moltenQR), name: "molten-qr.png", padding: 48)
try write(qr(moltenShortPartQR), name: "molten-qr-4-2-3.png", padding: 48)
try write(code128("D10E-50-N10B@0UBL00"), name: "molten-code128.png", padding: 48)
try write(code128("PAF1-15-422@0NKD3C"), name: "molten-code128-4-2-3.png", padding: 48)
try write(
    code128("PAF1-15-422@0NLL3C"),
    name: "molten-code128-4-2-3-second-box.png",
    padding: 48
)

print("Generated test codes in \(outputURL.path)")
