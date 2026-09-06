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
        destination: Destination? = nil,
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
        // 仕向地はセッションで最初に確定した値を保つ。後続の記録では上書きしない。
        if sessions[index].destination == nil,
           let resolved = destination ?? qrPayload.flatMap(Destination.detect(qrPayload:)) {
            sessions[index].destination = resolved
        }
        persist()
    }

    /// アクティブセッションの仕向地を確定する。すでに確定していれば何もしない。
    /// 一致を記録する前でも、QRを受理した時点で確定できるようにするための入口。
    func setActiveSessionDestinationIfNeeded(_ destination: Destination) {
        guard let index = sessions.firstIndex(where: \.isActive),
              sessions[index].destination == nil
        else { return }
        sessions[index].destination = destination
        persist()
    }

    /// アクティブセッションでこの品番が照合された回数（=検査済みの箱数）。
    func activeSessionMatchCount(code: String) -> Int {
        activeSession?.matchCount(code: code.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
    }

    /// アクティブセッションの成功履歴に、今回の箱固有QRがすでに含まれるかを確認する。
    /// Code 128は同一品番の全箱で共通になるため、重複判定には使用しない。
    func activeSessionContainsMatchedQRPayload(_ qrPayload: String) -> Bool {
        let normalizedQR = Self.normalizedPayload(qrPayload)
        guard !normalizedQR.isEmpty else { return false }

        return activeSession?.entries.contains { entry in
            entry.qrPayload.map(Self.normalizedPayload) == normalizedQR
        } ?? false
    }

    /// アクティブセッションの成功履歴に、今回の箱がすでに含まれるかを確認する。
    /// 澤井製作所のQRは箱ごとにカード番号が異なるためQR全文だけで箱を識別できるが、
    /// モルテックのQRは同じ納品番号の全箱で同一なので、現品票のCode 128全文まで含めて識別する。
    func activeSessionContainsMatchedBox(qrPayload: String, barcodePayload: String) -> Bool {
        guard Destination.detect(qrPayload: qrPayload) == .moltec else {
            return activeSessionContainsMatchedQRPayload(qrPayload)
        }
        guard
            let identity = BoxIdentity.make(qrPayload: qrPayload, barcodePayload: barcodePayload)
        else { return false }

        return activeSession?.entries.contains { $0.boxIdentity == identity } ?? false
    }

    /// アクティブセッションでこの納品番号（モルテック）を何箱検査し、収容数が何個になったか。
    /// アクティブセッションがなければ0箱・0個を返す。
    func activeSessionDeliverySummary(deliveryNumber: String) -> DeliveryBoxSummary {
        activeSession?.deliverySummary(deliveryNumber: deliveryNumber)
            ?? DeliveryBoxSummary.empty(deliveryNumber: deliveryNumber)
    }

    func renameSession(id: UUID, name: String?) {
        guard let index = sessions.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        sessions[index].name = (trimmed?.isEmpty ?? true) ? nil : trimmed
        persist()
    }

    func endActiveSession(at date: Date = Date()) {
        guard let index = sessions.firstIndex(where: \.isActive) else { return }
        if sessions[index].entries.isEmpty {
            // 照合0件のセッションは履歴に残さない
            sessions.remove(at: index)
        } else {
            sessions[index].endedAt = date
        }
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
            storageError = AppLocalization.string("履歴を保存できませんでした。端末の空き容量を確認してください。")
        }
    }

    private static func normalizedPayload(_ payload: String) -> String {
        payload.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    private static func load(from url: URL) -> [MatchSession] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let sessions = (try? decoder.decode([MatchSession].self, from: data)) ?? []
        // 旧バージョンで保存された照合0件の終了済みセッションは履歴に載せない
        return sessions.filter { $0.isActive || !$0.entries.isEmpty }
    }

    private static var defaultStorageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CodeMatch", isDirectory: true)
            .appendingPathComponent("match-history.json")
    }
}
