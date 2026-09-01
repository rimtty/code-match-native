#!/usr/bin/env swift
// Set the TestFlight "What to Test" (whatsNew) text for a build via the
// App Store Connect API, so releases need no browser.
//
// Usage:
//   swift ios/tools/set_testflight_whats_new.swift <buildNumber> <notesFile> [locale]
//
//   <buildNumber>  CFBundleVersion as shown in App Store Connect (e.g. 3)
//   <notesFile>    path to a UTF-8 text file with the What to Test notes
//   [locale]       defaults to "ja"
//
// Requires ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8.

import Foundation
import CryptoKit

let appID = "6803057393" // CodeMatch
let keyID = "6A5T6F2445"
let issuerID = "ac1a8863-ddd2-4fbf-8618-7d74eccd4e2f"

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data(("error: " + message + "\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count >= 3 else {
    fail("usage: set_testflight_whats_new.swift <buildNumber> <notesFile> [locale]")
}
let buildNumber = CommandLine.arguments[1]
let notesPath = CommandLine.arguments[2]
let locale = CommandLine.arguments.count > 3 ? CommandLine.arguments[3] : "ja"

guard let whatsNew = try? String(contentsOfFile: notesPath, encoding: .utf8),
      !whatsNew.isEmpty else {
    fail("could not read notes file at \(notesPath)")
}

// --- JWT (ES256) ---
let keyPath = ("~/.appstoreconnect/private_keys/AuthKey_\(keyID).p8" as NSString).expandingTildeInPath
guard let pem = try? String(contentsOfFile: keyPath, encoding: .utf8),
      let key = try? P256.Signing.PrivateKey(pemRepresentation: pem) else {
    fail("could not load private key at \(keyPath)")
}

func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

let now = Int(Date().timeIntervalSince1970)
let header = ["alg": "ES256", "kid": keyID, "typ": "JWT"]
let payload: [String: Any] = ["iss": issuerID, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"]
let signingInput = base64URL(try! JSONSerialization.data(withJSONObject: header))
    + "." + base64URL(try! JSONSerialization.data(withJSONObject: payload))
let signature = try! key.signature(for: Data(signingInput.utf8))
let jwt = signingInput + "." + base64URL(signature.rawRepresentation)

// --- Minimal synchronous ASC API client ---
func request(_ method: String, _ url: String, body: [String: Any]? = nil) -> [String: Any] {
    var req = URLRequest(url: URL(string: url)!)
    req.httpMethod = method
    req.setValue("Bearer \(jwt)", forHTTPHeaderField: "Authorization")
    if let body {
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try! JSONSerialization.data(withJSONObject: body)
    }
    var result: [String: Any] = [:]
    var status = 0
    let semaphore = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { data, response, error in
        if let error { fail("request failed: \(error.localizedDescription)") }
        status = (response as! HTTPURLResponse).statusCode
        if let data, !data.isEmpty,
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            result = json
        }
        semaphore.signal()
    }.resume()
    semaphore.wait()
    guard (200..<300).contains(status) else {
        fail("\(method) \(url) returned \(status): \(result)")
    }
    return result
}

// 1. Find the build.
let buildsURL = "https://api.appstoreconnect.apple.com/v1/builds?filter[app]=\(appID)&filter[version]=\(buildNumber)&limit=1"
guard let builds = request("GET", buildsURL)["data"] as? [[String: Any]],
      let buildID = builds.first?["id"] as? String else {
    fail("no build with number \(buildNumber) found (still processing?)")
}

// 2. Find or create the localization for the locale.
let locsURL = "https://api.appstoreconnect.apple.com/v1/builds/\(buildID)/betaBuildLocalizations?limit=50"
let locs = (request("GET", locsURL)["data"] as? [[String: Any]]) ?? []
let existing = locs.first {
    (($0["attributes"] as? [String: Any])?["locale"] as? String) == locale
}

if let locID = existing?["id"] as? String {
    _ = request("PATCH", "https://api.appstoreconnect.apple.com/v1/betaBuildLocalizations/\(locID)", body: [
        "data": ["type": "betaBuildLocalizations", "id": locID,
                 "attributes": ["whatsNew": whatsNew]]
    ])
} else {
    _ = request("POST", "https://api.appstoreconnect.apple.com/v1/betaBuildLocalizations", body: [
        "data": ["type": "betaBuildLocalizations",
                 "attributes": ["locale": locale, "whatsNew": whatsNew],
                 "relationships": ["build": ["data": ["type": "builds", "id": buildID]]]]
    ])
}

print("What to Test (\(locale)) set for build \(buildNumber).")
