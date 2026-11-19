import AppKit
import Foundation
import Observation
import ServiceManagement

/// User settings persisted in UserDefaults (domain com.agenthud.bridgemanager).
/// Any key can be overridden for one run with a launch argument, e.g.
/// `open "Agent HUD.app" --args -port 8799` (NSArgumentDomain, not persisted).
@MainActor
@Observable
final class AppSettings {
    enum Key {
        static let nodePath = "nodePath"
        static let bridgeFolder = "bridgeFolder"
        static let port = "port"
        static let autoStart = "autoStart"
        static let showDockIcon = "showDockIcon"
        static let hasLaunchedBefore = "hasLaunchedBefore"
    }

    static let defaultPort = 8765
    static var fallbackBridgeFolder: String {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Documents/Projects/Pixel Watch Mod/mac-agent-bridge").path
    }

    @ObservationIgnored private let defaults = UserDefaults.standard

    /// Empty = auto-detect.
    var nodePathOverride: String { didSet { defaults.set(nodePathOverride, forKey: Key.nodePath) } }
    var bridgeFolder: String { didSet { defaults.set(bridgeFolder, forKey: Key.bridgeFolder) } }
    var port: Int { didSet { defaults.set(port, forKey: Key.port) } }
    var autoStart: Bool { didSet { defaults.set(autoStart, forKey: Key.autoStart) } }
    var showDockIcon: Bool {
        didSet {
            defaults.set(showDockIcon, forKey: Key.showDockIcon)
            applyDockIcon()
        }
    }

    // Launch at login (SMAppService) - not stored, read from the system.
    private(set) var loginItemStatus: SMAppService.Status = .notRegistered
    private(set) var loginItemError: String?

    init() {
        defaults.register(defaults: [
            Key.port: Self.defaultPort,
            Key.autoStart: true,
            Key.showDockIcon: false,
        ])
        nodePathOverride = defaults.string(forKey: Key.nodePath) ?? ""
        let storedFolder = defaults.string(forKey: Key.bridgeFolder) ?? ""
        bridgeFolder = storedFolder.isEmpty ? Self.detectBridgeFolder() : storedFolder
        let p = defaults.integer(forKey: Key.port)
        port = (1...65535).contains(p) ? p : Self.defaultPort
        autoStart = defaults.bool(forKey: Key.autoStart)
        showDockIcon = defaults.bool(forKey: Key.showDockIcon)
        refreshLoginItemStatus()
    }

    /// The bundled bridge, else mac-app/build/Agent HUD.app -> ../../mac-agent-bridge when run
    /// from the repo, else the known project path.
    static func detectBridgeFolder() -> String {
        // Prefer the copy bundled inside the app (see build.sh): it avoids the Documents-folder
        // privacy check that stalls a child process started from the repo.
        if let bundled = Bundle.main.resourceURL?.appendingPathComponent("bridge"),
           FileManager.default.fileExists(atPath: bundled.appendingPathComponent("src/server.mjs").path) {
            return bundled.path
        }
        var url = Bundle.main.bundleURL
        for _ in 0..<5 {
            url = url.deletingLastPathComponent()
            let candidate = url.appendingPathComponent("mac-agent-bridge")
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("src/server.mjs").path) {
                return candidate.path
            }
        }
        return fallbackBridgeFolder
    }

    func resetBridgeFolder() { bridgeFolder = Self.detectBridgeFolder() }

    func applyDockIcon() {
        NSApp.setActivationPolicy(showDockIcon ? .regular : .accessory)
    }

    // MARK: Launch at login

    var launchAtLogin: Bool { loginItemStatus == .enabled || loginItemStatus == .requiresApproval }

    var loginItemStatusText: String {
        switch loginItemStatus {
        case .enabled: return "Enabled"
        case .requiresApproval: return "Needs approval in System Settings > General > Login Items"
        case .notRegistered: return "Off"
        case .notFound: return "Not found (app bundle not registered)"
        @unknown default: return "Unknown"
        }
    }

    func refreshLoginItemStatus() {
        loginItemStatus = SMAppService.mainApp.status
    }

    func setLaunchAtLogin(_ on: Bool) {
        loginItemError = nil
        do {
            if on {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            loginItemError = error.localizedDescription
        }
        refreshLoginItemStatus()
    }
}
