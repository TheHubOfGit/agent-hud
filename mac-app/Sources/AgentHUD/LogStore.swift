import Foundation
import Observation

/// Appends bridge output to ~/Library/Logs/AgentHUD/bridge.log (rotating at 5 MB, keeping
/// bridge.log.1 ... bridge.log.3) and keeps the last ~500 lines in memory for the UI.
@MainActor
@Observable
final class LogStore {
    struct Line: Identifiable, Equatable {
        let id: Int
        let text: String
    }

    static let maxLines = 500

    private(set) var lines: [Line] = []
    private var nextID = 0

    let logURL: URL
    @ObservationIgnored private let writer: LogFileWriter

    init() {
        let dir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Logs/AgentHUD", isDirectory: true)
        logURL = dir.appendingPathComponent("bridge.log")
        writer = LogFileWriter(url: logURL)
    }

    /// Raw output from the child process. Safe to call from any thread.
    nonisolated func ingest(_ data: Data) {
        writer.ingest(data) { [weak self] newLines in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.appendToRing(newLines) }
            }
        }
    }

    /// Flushes an unterminated trailing line (call when the child exits).
    nonisolated func flushPartial() {
        writer.flushPartial { [weak self] newLines in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.appendToRing(newLines) }
            }
        }
    }

    /// A line from the manager itself (start/stop/exit events).
    func note(_ message: String) {
        let line = "[agent-hud] \(message)"
        writer.writeLines([line])
        appendToRing([line])
    }

    func clearView() { lines.removeAll() }

    private func appendToRing(_ newLines: [String]) {
        guard !newLines.isEmpty else { return }
        for text in newLines {
            lines.append(Line(id: nextID, text: text))
            nextID += 1
        }
        if lines.count > Self.maxLines { lines.removeFirst(lines.count - Self.maxLines) }
    }
}

/// File side of the log. All work happens on a private serial queue.
final class LogFileWriter: @unchecked Sendable {
    static let rotateBytes: UInt64 = 5 * 1024 * 1024
    static let keepOld = 3

    private let url: URL
    private let queue = DispatchQueue(label: "agenthud.logwriter")
    private var handle: FileHandle?
    private var size: UInt64 = 0
    private var partial = Data()
    private let stampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    init(url: URL) {
        self.url = url
    }

    func ingest(_ data: Data, completion: @escaping @Sendable ([String]) -> Void) {
        queue.async {
            self.partial.append(data)
            var out: [String] = []
            while let nl = self.partial.firstIndex(of: 0x0A) {
                let lineData = self.partial[self.partial.startIndex..<nl]
                self.partial.removeSubrange(self.partial.startIndex...nl)
                out.append(Self.decode(lineData))
            }
            // Guard against a child that never prints a newline.
            if self.partial.count > 64 * 1024 {
                out.append(Self.decode(self.partial))
                self.partial.removeAll()
            }
            self.writeLinesLocked(out)
            if !out.isEmpty { completion(out) }
        }
    }

    func flushPartial(completion: @escaping @Sendable ([String]) -> Void) {
        queue.async {
            guard !self.partial.isEmpty else { return }
            let line = Self.decode(self.partial)
            self.partial.removeAll()
            self.writeLinesLocked([line])
            completion([line])
        }
    }

    func writeLines(_ lines: [String]) {
        queue.async { self.writeLinesLocked(lines) }
    }

    private static func decode(_ d: Data) -> String {
        var s = String(decoding: d, as: UTF8.self)
        if s.hasSuffix("\r") { s.removeLast() }
        return s
    }

    private func writeLinesLocked(_ lines: [String]) {
        guard !lines.isEmpty else { return }
        let stamp = stampFormatter.string(from: Date())
        var blob = ""
        for l in lines { blob += "\(stamp) \(l)\n" }
        let data = Data(blob.utf8)
        openIfNeeded()
        if size + UInt64(data.count) > Self.rotateBytes { rotate() }
        guard let handle else { return }
        do {
            try handle.write(contentsOf: data)
            size += UInt64(data.count)
        } catch {
            try? handle.close()
            self.handle = nil
        }
    }

    private func openIfNeeded() {
        guard handle == nil else { return }
        let fm = FileManager.default
        try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !fm.fileExists(atPath: url.path) {
            fm.createFile(atPath: url.path, contents: nil)
        }
        handle = try? FileHandle(forWritingTo: url)
        size = (try? handle?.seekToEnd()) ?? 0
    }

    private func rotate() {
        try? handle?.close()
        handle = nil
        let fm = FileManager.default
        func numbered(_ n: Int) -> URL { URL(fileURLWithPath: url.path + ".\(n)") }
        try? fm.removeItem(at: numbered(Self.keepOld))
        if Self.keepOld > 1 {
            for n in stride(from: Self.keepOld - 1, through: 1, by: -1) where fm.fileExists(atPath: numbered(n).path) {
                try? fm.moveItem(at: numbered(n), to: numbered(n + 1))
            }
        }
        try? fm.moveItem(at: url, to: numbered(1))
        openIfNeeded()
    }
}
