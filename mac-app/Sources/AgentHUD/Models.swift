import Foundation

/// GET /health
struct BridgeHealth: Decodable, Equatable {
    struct Client: Decodable, Equatable, Hashable {
        var ip: String?
        var connectedAtMs: Double?

        var connectedAt: Date? {
            guard let ms = connectedAtMs, ms > 0 else { return nil }
            return Date(timeIntervalSince1970: ms / 1000)
        }
    }

    var ok: Bool?
    var version: String?
    var pid: Int?
    var port: Int?
    var startedAtMs: Double?
    var uptimeSec: Double?
    var mdns: Bool?
    var clients: [Client]?
    var threads: Int?
    var waiting: Int?
    var quotas: Int?

    var startedAt: Date? {
        guard let ms = startedAtMs, ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }

    var connectedClients: [Client] { clients ?? [] }
}

/// GET /status (only the fields the app shows)
struct BridgeStatus: Decodable, Equatable {
    struct Thread: Decodable, Equatable, Identifiable {
        var id: String
        var provider: String?
        var providerLabel: String?
        var title: String?
        var status: String?
        var step: String?
        var updatedAtMs: Double?
        var color: String?
    }

    struct Quota: Decodable, Equatable, Identifiable {
        var id: String
        var name: String?
        var remainingPct: Double?
        var resetShort: String?
        var windowLabel: String?
        var color: String?
    }

    var threads: [Thread]?
    var approvalQueue: [String]?
    var quotas: [Quota]?

    var threadList: [Thread] { threads ?? [] }
    var quotaList: [Quota] { quotas ?? [] }

    var lowestQuota: Quota? {
        quotaList.filter { $0.remainingPct != nil }.min { ($0.remainingPct ?? 0) < ($1.remainingPct ?? 0) }
    }
}

enum Fmt {
    /// "45s", "12m", "1h 5m", "3d 2h"
    static func duration(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        if s < 60 { return "\(s)s" }
        let m = s / 60
        if m < 60 { return "\(m)m" }
        let h = m / 60
        if h < 24 { return m % 60 == 0 ? "\(h)h" : "\(h)h \(m % 60)m" }
        let d = h / 24
        return h % 24 == 0 ? "\(d)d" : "\(d)d \(h % 24)h"
    }

    static func since(_ date: Date?) -> String {
        guard let date else { return "?" }
        return duration(Date().timeIntervalSince(date))
    }

    static func percent(_ v: Double?) -> String {
        guard let v else { return "?" }
        return "\(Int(v.rounded()))%"
    }
}
