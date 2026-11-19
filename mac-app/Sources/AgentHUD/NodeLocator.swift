import Foundation

/// Finds a usable `node` binary and a sensible PATH for the child. GUI apps start with a
/// minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin), so we check common install locations first
/// and then ask the user's login shell.
enum NodeLocator {
    static func isExecutable(_ path: String) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: path, isDirectory: &isDir) && !isDir.boolValue
            && FileManager.default.isExecutableFile(atPath: path)
    }

    static func candidatePaths() -> [String] {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        var paths = [
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            "\(home)/.volta/bin/node",
            "\(home)/.local/share/mise/shims/node",
            "\(home)/.asdf/shims/node",
            "/usr/bin/node",
        ]
        // nvm: newest installed version first
        let nvmDir = "\(home)/.nvm/versions/node"
        if let versions = try? FileManager.default.contentsOfDirectory(atPath: nvmDir) {
            let sorted = versions.sorted { $0.compare($1, options: .numeric) == .orderedDescending }
            paths.append(contentsOf: sorted.map { "\(nvmDir)/\($0)/bin/node" })
        }
        return paths
    }

    /// Blocking; call off the main thread.
    static func locate(override: String) -> String? {
        let trimmed = override.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            let expanded = (trimmed as NSString).expandingTildeInPath
            return isExecutable(expanded) ? expanded : nil
        }
        if let hit = candidatePaths().first(where: isExecutable) { return hit }
        if let out = runLoginShell("command -v node"),
           let line = out.split(separator: "\n").last.map(String.init),
           line.hasPrefix("/"), isExecutable(line) {
            return line
        }
        return nil
    }

    /// The login shell PATH (cached), merged with common bin dirs, for the child environment.
    static let childPATH: String = {
        var parts: [String] = []
        if let out = runLoginShell("printf %s \"$PATH\""),
           let line = out.split(separator: "\n").last {
            parts = line.split(separator: ":").map(String.init)
        }
        for extra in ["/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"]
        where !parts.contains(extra) {
            parts.append(extra)
        }
        return parts.joined(separator: ":")
    }()

    static func version(of node: String) -> String? {
        guard let out = run(node, ["--version"], timeout: 5) else { return nil }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func runLoginShell(_ command: String) -> String? {
        run("/bin/zsh", ["-lc", command], timeout: 8)
    }

    /// Runs a short-lived command and returns stdout, or nil on failure/timeout.
    static func run(_ exe: String, _ args: [String], timeout: TimeInterval) -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: exe)
        p.arguments = args
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        p.standardInput = FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        let deadline = Date().addingTimeInterval(timeout)
        // Read concurrently so a chatty child can't block on a full pipe.
        var data = Data()
        let reader = DispatchQueue(label: "agenthud.run.reader")
        let group = DispatchGroup()
        group.enter()
        reader.async {
            data = out.fileHandleForReading.readDataToEndOfFile()
            group.leave()
        }
        while p.isRunning && Date() < deadline { usleep(20_000) }
        if p.isRunning {
            p.terminate()
            return nil
        }
        _ = group.wait(timeout: .now() + 1)
        guard p.terminationStatus == 0 else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// PIDs listening on a TCP port (lsof -nP -iTCP:PORT -sTCP:LISTEN -t).
    static func listeningPIDs(port: Int) -> [Int32] {
        guard let out = run("/usr/sbin/lsof", ["-nP", "-iTCP:\(port)", "-sTCP:LISTEN", "-t"], timeout: 5) else { return [] }
        return out.split(separator: "\n").compactMap { Int32($0.trimmingCharacters(in: .whitespaces)) }
    }

    static func commandLine(of pid: Int32) -> String? {
        run("/bin/ps", ["-o", "command=", "-p", "\(pid)"], timeout: 3)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func parentPID(of pid: Int32) -> Int32? {
        guard let s = run("/bin/ps", ["-o", "ppid=", "-p", "\(pid)"], timeout: 3) else { return nil }
        return Int32(s.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    static func isAlive(_ pid: Int32) -> Bool {
        kill(pid, 0) == 0 || errno == EPERM
    }
}
