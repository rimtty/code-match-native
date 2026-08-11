import Foundation

struct MatchHistoryEntry: Identifiable, Codable, Equatable {
    let id: UUID
    let code: String
    let matchedAt: Date

    init(id: UUID = UUID(), code: String, matchedAt: Date = Date()) {
        self.id = id
        self.code = code
        self.matchedAt = matchedAt
    }
}

struct MatchSession: Identifiable, Codable, Equatable {
    let id: UUID
    let startedAt: Date
    var endedAt: Date?
    var entries: [MatchHistoryEntry]

    init(
        id: UUID = UUID(),
        startedAt: Date = Date(),
        endedAt: Date? = nil,
        entries: [MatchHistoryEntry] = []
    ) {
        self.id = id
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.entries = entries
    }

    var isActive: Bool { endedAt == nil }
    var matchedCount: Int { entries.count }
}
