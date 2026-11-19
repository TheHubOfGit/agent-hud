import AppKit
import Foundation
import Observation

enum BridgeState: Equatable {
    case stopped
    case starting
    case running
    case stopping
    case takingOver
    case portInUse
    case crashed(restartAt: Date)
    case nodeNotFound
    case bridgeNotFound
}

/// Launches `node src/server.mjs` as a child process, supervises it (auto-restart with
/// backoff, exit-code-3 detection, take-over), and polls /health and /status.
/// Running the bridge as our child makes macOS attribute its Accessibility/Automation use
/// (osascript / System Events) to this app.
@MainActor
@Observable
final class BridgeManager {
    let settings: AppSettings
    let log: LogStore

    private(set) var state: BridgeState = .stopped
    private(set) var health: BridgeHealth?
    private(set) var status: BridgeStatus?
    private(set) var childPID: Int32?
    private(set) var resolvedNodePath: String?
    private(set) var nodeVersion: String?
    private(set) var lastExit: String?
    /// Port/folder the current child was started with (settings may have changed since).
    private(set) var runningPort: Int?
    private(set) var runningFolder: String?
    private(set) var runningNode: String?
    private(set) var portHolderPID: Int32?

    @ObservationIgnored private var process: Process?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var launching = false
    @ObservationIgnored private var userStopped = false
    @ObservationIgnored private var restartAfterStop = false
    @ObservationIgnored private var launchedAt: Date?
    @ObservationIgnored private var backoff: TimeInterval = 1
    @ObservationIgnored private var restartTask: Task<Void, Never>?
    @ObservationIgnored private var killTask: Task<Void, Never>?
    @ObservationIgnored private var pollTimer: Timer?
    @ObservationIgnored private var pollInFlight = false
    @ObservationIgnored private var exitWaiters: [() -> Void] = []
    /// Consecutive /health failures while the child is alive (a hung bridge never exits).
    @ObservationIgnored private var healthFailures = 0
    /// Auto take-over is tried once per start attempt, only against another bridge process.
    @ObservationIgnored private var autoTakeoverTried = false

    static let maxBackoff: TimeInterval = 30
    static let healthyResetAfter: TimeInterval = 60
    static let pollInterval: TimeInterval = 3
    /// ~15s without a health answer: stop showing stale data.
    static let staleAfterFailures = 5
    /// ~60s without a health answer: the bridge is hung; restart it.
    static let restartAfterFailures = 20

    init(settings: AppSettings, log: LogStore) {
        self.settings = settings
        self.log = log
    }

    // MARK: Derived state

    var hasChild: Bool { process?.isRunning == true }
    var isActive: Bool { hasChild || launching || state == .takingOver || { if case .crashed = state { return true }; return false }() }

    var watchConnected: Bool { !(health?.connectedClients.isEmpty ?? true) }

    var needsRestartForSettings: Bool {
        guard hasChild else { return false }
        return runningPort != settings.port || runningFolder != settings.bridgeFolder
            || (runningNode != nil && !settings.nodePathOverride.isEmpty && runningNode != (settings.nodePathOverride as NSString).expandingTildeInPath)
    }

    enum Tint { case green, gray, amber, red }

    var tint: Tint {
        switch state {
        case .running: return watchConnected ? .green : .gray
        case .starting, .stopping, .takingOver, .portInUse, .crashed: return .amber
        case .stopped, .nodeNotFound, .bridgeNotFound: return .red
        }
    }

    var statusText: String {
        switch state {
        case .stopped: return "Stopped"
        case .starting: return "Starting…"
        case .stopping: return "Stopping…"
        case .takingOver: return "Taking over port \(settings.port)…"
        case .running: return watchConnected ? "Running · watch connected" : "Running · no watch"
        case .portInUse: return "Port \(runningPort ?? settings.port) in use"
        case .crashed(let at):
            let secs = max(0, Int(at.timeIntervalSinceNow.rounded(.up)))
            return "Crashed · restarting in \(secs)s"
        case .nodeNotFound: return "Node not found"
        case .bridgeNotFound: return "Bridge folder not found"
        }
    }

    var watchLine: String {
        guard state == .running, let clients = health?.connectedClients, !clients.isEmpty else {
            return "No watch connected"
        }
        if clients.count == 1, let c = clients.first {
            return "Watch connected · \(c.ip ?? "?") · \(Fmt.since(c.connectedAt))"
        }
        return "\(clients.count) watches connected · " + clients.map { $0.ip ?? "?" }.joined(separator: ", ")
    }

    var agentsLine: String {
        let n = status?.threadList.count ?? health?.threads ?? 0
        let waiting = status?.approvalQueue?.count ?? health?.waiting ?? 0
        let agents = n == 1 ? "1 agent" : "\(n) agents"
        return waiting > 0 ? "\(agents) · \(waiting) waiting" : agents
    }

    var lowestQuotaLine: String? {
        guard let q = status?.lowestQuota else { return nil }
        var s = "Lowest quota: \(q.name ?? q.id) \(Fmt.percent(q.remainingPct))"
        if let r = q.resetShort, !r.isEmpty { s += " · resets \(r)" }
        return s
    }

    // MARK: Commands

    func start() {
        restartTask?.cancel()
        restartTask = nil
        userStopped = false
        guard !hasChild, !launching else { return }
        Task { await launch() }
    }

    func stop() {
        userStopped = true
        restartAfterStop = false
        restartTask?.cancel()
        restartTask = nil
        guard let p = process, p.isRunning else {
            if state != .takingOver { state = .stopped }
            return
        }
        state = .stopping
        log.note("Stopping bridge (SIGTERM to pid \(p.processIdentifier))")
        p.terminate()
        let pid = p.processIdentifier
        killTask?.cancel()
        killTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self, self.process === p, p.isRunning else { return }
            self.log.note("Bridge did not exit after 2s; sending SIGKILL to pid \(pid)")
            kill(pid, SIGKILL)
        }
    }

    func restart() {
        if hasChild {
            stop()
            restartAfterStop = true
        } else {
            backoff = 1
            start()
        }
    }

    /// Stops the child (if any) and calls `done` once it is gone.
    func shutdown(done: @escaping () -> Void) {
        guard hasChild else { done(); return }
        exitWaiters.append(done)
        stop()
    }

    /// Finds whoever listens on the configured port, SIGTERMs it, waits up to 3s, starts ours.
    func takeOver() {
        guard !hasChild, !launching else { return }
        restartTask?.cancel()
        let port = settings.port
        state = .takingOver
        userStopped = false
        log.note("Take over: looking for the process listening on port \(port)")
        Task { [weak self] in
            let messages = await Task.detached { () -> [String] in
                let pids = NodeLocator.listeningPIDs(port: port).filter { $0 != getpid() }
                var messages: [String] = []
                if pids.isEmpty {
                    messages.append("Take over: nothing is listening on port \(port)")
                }
                for pid in pids {
                    let cmd = NodeLocator.commandLine(of: pid) ?? "?"
                    messages.append("Take over: SIGTERM pid \(pid) (\(cmd))")
                    kill(pid, SIGTERM)
                }
                let deadline = Date().addingTimeInterval(3)
                while Date() < deadline && pids.contains(where: { NodeLocator.isAlive($0) }) {
                    usleep(100_000)
                }
                let survivors = pids.filter { NodeLocator.isAlive($0) }
                if !survivors.isEmpty {
                    messages.append("Take over: still alive after 3s: \(survivors.map(String.init).joined(separator: ", "))")
                }
                return messages
            }.value
            guard let self else { return }
            messages.forEach { self.log.note($0) }
            self.backoff = 1
            self.state = .stopped
            self.start()
        }
    }

    // MARK: Launch / exit

    private func launch() async {
        launching = true
        defer { launching = false }
        state = .starting
        health = nil
        status = nil
        portHolderPID = nil

        let folder = settings.bridgeFolder
        let port = settings.port
        let server = URL(fileURLWithPath: folder).appendingPathComponent("src/server.mjs")
        guard FileManager.default.fileExists(atPath: server.path) else {
            state = .bridgeNotFound
            log.note("Bridge not found: \(server.path)")
            return
        }

        let override = settings.nodePathOverride
        let (node, path) = await Task.detached { () -> (String?, String) in
            (NodeLocator.locate(override: override), NodeLocator.childPATH)
        }.value
        guard let node else {
            resolvedNodePath = nil
            state = .nodeNotFound
            log.note(override.isEmpty ? "Node.js not found (checked common paths and the login shell)"
                                      : "Node.js not found at \(override)")
            return
        }
        if resolvedNodePath != node || nodeVersion == nil {
            resolvedNodePath = node
            nodeVersion = await Task.detached { NodeLocator.version(of: node) }.value
        }
        // A stop/takeover may have been requested while we were resolving node.
        guard !userStopped else { state = .stopped; return }

        var env = ProcessInfo.processInfo.environment
        env["PATH"] = path
        env["AGENT_HUD_PORT"] = String(port)
        if port != AppSettings.defaultPort {
            env["AGENT_HUD_NO_MDNS"] = "1"
        } else {
            env.removeValue(forKey: "AGENT_HUD_NO_MDNS")
        }

        let p = Process()
        p.executableURL = URL(fileURLWithPath: node)
        p.arguments = ["src/server.mjs"]
        p.currentDirectoryURL = URL(fileURLWithPath: folder, isDirectory: true)
        p.environment = env
        p.standardInput = FileHandle.nullDevice
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe

        generation += 1
        let gen = generation
        let logStore = log
        pipe.fileHandleForReading.readabilityHandler = { h in
            let data = h.availableData
            if data.isEmpty {
                h.readabilityHandler = nil
                return
            }
            logStore.ingest(data)
        }
        p.terminationHandler = { [weak self] proc in
            let code = proc.terminationStatus
            let reason = proc.terminationReason
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.childExited(gen: gen, code: code, reason: reason) }
            }
        }

        log.note("Starting \(node) src/server.mjs in \(folder) (port \(port)\(port != AppSettings.defaultPort ? ", mDNS off" : ""))")
        do {
            try p.run()
        } catch {
            pipe.fileHandleForReading.readabilityHandler = nil
            log.note("Failed to launch node: \(error.localizedDescription)")
            scheduleRestart(reason: "launch failed")
            return
        }
        process = p
        healthFailures = 0
        childPID = p.processIdentifier
        launchedAt = Date()
        runningPort = port
        runningFolder = folder
        runningNode = node
        PidFile.write(pid: p.processIdentifier)
        log.note("Bridge pid \(p.processIdentifier)")
        startPolling()
    }

    private func childExited(gen: Int, code: Int32, reason: Process.TerminationReason) {
        guard gen == generation else { return }
        log.flushPartial()
        let pid = childPID
        process = nil
        childPID = nil
        killTask?.cancel()
        stopPolling()
        PidFile.remove()
        health = nil
        status = nil

        let how = reason == .uncaughtSignal ? "signal \(code)" : "code \(code)"
        lastExit = "Exited with \(how) at \(Date().formatted(date: .omitted, time: .standard))"
        log.note("Bridge pid \(pid.map(String.init) ?? "?") exited with \(how)")

        let waiters = exitWaiters
        exitWaiters.removeAll()

        if userStopped {
            state = .stopped
            if restartAfterStop {
                restartAfterStop = false
                backoff = 1
                start()
            }
        } else if reason == .exit && code == 3 {
            state = .portInUse
            let port = runningPort ?? settings.port
            log.note("Port \(port) is already in use (another bridge?). Not restarting; use Take over.")
            Task { [weak self] in
                let (holder, cmd) = await Task.detached { () -> (Int32?, String?) in
                    let pid = NodeLocator.listeningPIDs(port: port).first
                    return (pid, pid.flatMap { NodeLocator.commandLine(of: $0) })
                }.value
                guard let self else { return }
                self.portHolderPID = holder
                // The usual cause is an older bridge started by hand or by a script. This app is
                // meant to own the bridge, so replace another bridge automatically (once); any
                // other program on the port is left alone for the user to decide.
                if !self.autoTakeoverTried, let cmd, cmd.contains("src/server.mjs") {
                    self.autoTakeoverTried = true
                    self.log.note("Port \(port) is held by another bridge (pid \(holder.map(String.init) ?? "?")); taking over automatically")
                    self.takeOver()
                }
            }
        } else {
            scheduleRestart(reason: how)
        }
        waiters.forEach { $0() }
    }

    private func scheduleRestart(reason: String) {
        let delay = backoff
        backoff = min(backoff * 2, Self.maxBackoff)
        state = .crashed(restartAt: Date().addingTimeInterval(delay))
        log.note("Restarting in \(Int(delay))s")
        restartTask?.cancel()
        restartTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            guard case .crashed = self.state else { return }
            self.restartTask = nil
            await self.launch()
        }
    }

    // MARK: Polling

    private func startPolling() {
        stopPolling()
        let t = Timer(timeInterval: Self.pollInterval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
        RunLoop.main.add(t, forMode: .common)
        pollTimer = t
        // First check quickly so "Starting" doesn't linger.
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            self?.poll()
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    func poll() {
        guard hasChild, !pollInFlight, let port = runningPort else { return }
        pollInFlight = true
        let gen = generation
        Task { [weak self] in
            let h: BridgeHealth? = await Self.fetch("/health", port: port)
            let s: BridgeStatus? = h == nil ? nil : await Self.fetch("/status", port: port)
            guard let self else { return }
            self.pollInFlight = false
            guard gen == self.generation, self.hasChild else { return }
            guard let h else {
                self.healthFailures += 1
                if self.healthFailures == Self.staleAfterFailures {
                    self.log.note("Bridge isn't answering /health; hiding stale data")
                    self.health = nil
                    self.status = nil
                    if self.state == .running { self.state = .starting }
                }
                if self.healthFailures >= Self.restartAfterFailures {
                    self.healthFailures = 0
                    self.log.note("Bridge unresponsive for ~\(Int(Self.pollInterval) * Self.restartAfterFailures)s; restarting it")
                    self.restart()
                }
                return
            }
            self.healthFailures = 0
            self.autoTakeoverTried = false
            do {
                self.health = h
                if let s { self.status = s }
                if self.state == .starting { self.state = .running }
                if let launchedAt = self.launchedAt, Date().timeIntervalSince(launchedAt) >= Self.healthyResetAfter {
                    self.backoff = 1
                }
            }
        }
    }

    nonisolated static func fetch<T: Decodable>(_ path: String, port: Int) async -> T? {
        guard let url = URL(string: "http://127.0.0.1:\(port)\(path)") else { return nil }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 2.5)
        req.httpMethod = "GET"
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            return nil
        }
    }

    // MARK: Orphans

    /// If a previous run of this app died without stopping its child, that child is still
    /// holding the port. We only ever touch the PID we recorded ourselves, and only if it is
    /// orphaned (ppid 1) and still looks like the bridge.
    func reapOrphanFromPreviousRun() {
        guard let pid = PidFile.read() else { return }
        PidFile.remove()
        guard NodeLocator.isAlive(pid),
              NodeLocator.parentPID(of: pid) == 1,
              let cmd = NodeLocator.commandLine(of: pid),
              cmd.contains("src/server.mjs") else { return }
        log.note("Stopping orphaned bridge pid \(pid) from a previous Agent HUD run")
        kill(pid, SIGTERM)
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline && NodeLocator.isAlive(pid) { usleep(50_000) }
        if NodeLocator.isAlive(pid) { kill(pid, SIGKILL) }
    }
}

enum PidFile {
    static var url: URL {
        let dir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/AgentHUD", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("bridge-child.pid")
    }

    static func write(pid: Int32) { try? "\(pid)\n".write(to: url, atomically: true, encoding: .utf8) }
    static func remove() { try? FileManager.default.removeItem(at: url) }
    static func read() -> Int32? {
        guard let s = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return Int32(s.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}
