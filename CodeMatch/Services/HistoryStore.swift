import Combine
import Foundation

@MainActor
final class HistoryStore: ObservableObject {
    @Published private(set) var sessions: [MatchSession]
    @Published private(set) var storageError: String?

    private let storageURL: URL
    private let encoder: JSONEncoder

    var activeSession: MatchSession? {
        sessions.first(where: \.isActive)
    }

    init(storageURL: URL? = nil) {
        self.storageURL = storageURL ?? Self.defaultStorageURL
        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601

        if ProcessInfo.processInfo.arguments.contains("-resetHistory") {
            try? FileManager.default.removeItem(at: self.storageURL)
            sessions = []
        } else {
            sessions = Self.load(from: self.storageURL)
        }
    }

    @discardableResult
    func beginSession(name: String? = nil, at date: Date = Date()) -> UUID {
        if let activeSession { return activeSession.id }

        let trimmedName = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let session = MatchSession(
            startedAt: date,
            name: (trimmedName?.isEmpty ?? true) ? nil : trimmedName
        )
        sessions.insert(session, at: 0)
        persist()
        return session.id
    }

    func recordMatch(
        code: String,
        qrPayload: String? = nil,
        barcodePayload: String? = nil,
        at date: Date = Date()
    ) {
        guard let index = sessions.firstIndex(where: \.isActive) else { return }
        let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines)
        sessions[index].entries.append(
            MatchHistoryEntry(
                code: normalized,
                matchedAt: date,
                qrPayload: qrPayload,
                barcodePayload: barcodePayload
            )
        )
        persist()
    }

    /// アクティブセッションでこの品番が照合済みかどうか。
    func activeSessionHasMatch(code: String) -> Bool {
        activeSession?.hasMatch(code: code.trimmingCharacters(in: .whitespacesAndNewlines)) ?? false
    }

    func renameSession(id: UUID, name: String?) {
        guard let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        sessions[index].name = (trimmed?.isEmpty ?? true) ? nil : trimmed
        persist()
    }

    func endActiveSession(at date: Date = Date()) {
        guard let index = sessions.firstIndex(where: \.isActive) else { return }
        sessions[index].endedAt = date
        persist()
    }

    func deleteSessions(at offsets: IndexSet) {
        sessions.remove(atOffsets: offsets)
        persist()
    }

    private func persist() {
        do {
            let directory = storageURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )

            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            var protectedDirectory = directory
            try? protectedDirectory.setResourceValues(resourceValues)

            let data = try encoder.encode(sessions)
            try data.write(to: storageURL, options: [.atomic, .completeFileProtection])
            storageError = nil
        } catch {
            storageError = "履歴を保存できませんでした。端末の空き容量を確認してください。"
        }
    }

    private static func load(from url: URL) -> [MatchSession] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([MatchSession].self, from: data)) ?? []
    }

    private static var defaultStorageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CodeMatch", isDirectory: true)
            .appendingPathComponent("match-history.json")
    }
}
