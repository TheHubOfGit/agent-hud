import AppKit
import Observation

/// Menu bar glyph: a small watch face inside an orbit ring, with one bead on the ring.
/// The ring/face are a template image (adapts to light/dark menu bars); the bead is a
/// separate colored dot that shows the bridge status.
enum MenuBarGlyph {
    static let size: CGFloat = 18
    static let orbitRadius: CGFloat = 7.0
    static let beadAngle: CGFloat = .pi / 4          // upper right
    static let beadRadius: CGFloat = 2.6

    /// Bead center offset from the glyph center, in a y-up coordinate space.
    static var beadOffset: CGPoint {
        CGPoint(x: orbitRadius * cos(beadAngle), y: orbitRadius * sin(beadAngle))
    }

    static func template() -> NSImage {
        let img = NSImage(size: NSSize(width: size, height: size), flipped: false) { _ in
            let c = CGPoint(x: size / 2, y: size / 2)
            NSColor.black.set()

            // Orbit ring
            let ring = NSBezierPath(ovalIn: NSRect(x: c.x - orbitRadius, y: c.y - orbitRadius,
                                                   width: orbitRadius * 2, height: orbitRadius * 2))
            ring.lineWidth = 1.25
            ring.stroke()

            // Watch face
            let faceR: CGFloat = 3.9
            NSBezierPath(ovalIn: NSRect(x: c.x - faceR, y: c.y - faceR, width: faceR * 2, height: faceR * 2)).fill()

            // Gap in the ring where the (separately drawn) status bead sits
            let b = CGPoint(x: c.x + beadOffset.x, y: c.y + beadOffset.y)
            let gapR = beadRadius + 1.3
            NSGraphicsContext.current?.compositingOperation = .clear
            NSBezierPath(ovalIn: NSRect(x: b.x - gapR, y: b.y - gapR, width: gapR * 2, height: gapR * 2)).fill()
            NSGraphicsContext.current?.compositingOperation = .sourceOver
            return true
        }
        img.isTemplate = true
        img.accessibilityDescription = "Agent HUD"
        return img
    }

    static func color(for tint: BridgeManager.Tint) -> NSColor {
        switch tint {
        case .green: return NSColor(srgbRed: 0.20, green: 0.80, blue: 0.40, alpha: 1)
        case .gray: return NSColor(srgbRed: 0.62, green: 0.63, blue: 0.66, alpha: 1)
        case .amber: return NSColor(srgbRed: 1.00, green: 0.70, blue: 0.16, alpha: 1)
        case .red: return NSColor(srgbRed: 0.94, green: 0.33, blue: 0.31, alpha: 1)
        }
    }
}

@MainActor
final class StatusItemController: NSObject, NSMenuDelegate {
    private let manager: BridgeManager
    private let settings: AppSettings
    private let openDashboard: () -> Void
    private let statusItem: NSStatusItem
    private let bead = NSView()
    private let menu = NSMenu()

    init(manager: BridgeManager, settings: AppSettings, openDashboard: @escaping () -> Void) {
        self.manager = manager
        self.settings = settings
        self.openDashboard = openDashboard
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        super.init()

        if let button = statusItem.button {
            button.image = MenuBarGlyph.template()
            button.imagePosition = .imageOnly
            bead.wantsLayer = true
            bead.layer?.cornerRadius = MenuBarGlyph.beadRadius
            bead.translatesAutoresizingMaskIntoConstraints = false
            button.addSubview(bead)
            let d = MenuBarGlyph.beadRadius * 2
            NSLayoutConstraint.activate([
                bead.widthAnchor.constraint(equalToConstant: d),
                bead.heightAnchor.constraint(equalToConstant: d),
                bead.centerXAnchor.constraint(equalTo: button.centerXAnchor, constant: MenuBarGlyph.beadOffset.x),
                // Auto Layout's y axis points down, so "up" is negative.
                bead.centerYAnchor.constraint(equalTo: button.centerYAnchor, constant: -MenuBarGlyph.beadOffset.y),
            ])
        }
        menu.delegate = self
        menu.autoenablesItems = false
        statusItem.menu = menu
        observe()
    }

    /// Re-render the bead/tooltip whenever the observed manager state changes.
    private func observe() {
        withObservationTracking {
            _ = manager.tint
            _ = manager.statusText
        } onChange: { [weak self] in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.refreshButton()
                    self?.observe()
                }
            }
        }
        refreshButton()
    }

    private func refreshButton() {
        bead.layer?.backgroundColor = MenuBarGlyph.color(for: manager.tint).cgColor
        statusItem.button?.toolTip = "Agent HUD: \(manager.statusText)"
    }

    // MARK: Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        settings.refreshLoginItemStatus()
        menu.removeAllItems()

        menu.addItem(infoItem("Bridge: \(manager.statusText)", bold: true))
        menu.addItem(infoItem(manager.watchLine))
        if manager.state == .running {
            menu.addItem(infoItem(manager.agentsLine))
            if let q = manager.lowestQuotaLine { menu.addItem(infoItem(q)) }
        }
        menu.addItem(.separator())

        switch manager.state {
        case .portInUse:
            menu.addItem(actionItem("Take Over Port \(settings.port)", #selector(takeOver)))
            menu.addItem(actionItem("Retry Start", #selector(start)))
        case .running, .starting:
            menu.addItem(actionItem("Stop Bridge", #selector(stop)))
            menu.addItem(actionItem("Restart Bridge", #selector(restart)))
        case .crashed:
            menu.addItem(actionItem("Restart Now", #selector(restart)))
            menu.addItem(actionItem("Stop Bridge", #selector(stop)))
        case .stopping, .takingOver:
            let i = actionItem("Stop Bridge", #selector(stop)); i.isEnabled = false; menu.addItem(i)
        case .stopped, .nodeNotFound, .bridgeNotFound:
            menu.addItem(actionItem("Start Bridge", #selector(start)))
        }
        menu.addItem(.separator())
        menu.addItem(actionItem("Open Dashboard…", #selector(dashboard), key: "d"))
        menu.addItem(actionItem("Open Log File", #selector(openLog)))
        menu.addItem(actionItem("Copy Watch Token", #selector(copyToken)))
        let login = actionItem("Launch at Login", #selector(toggleLogin))
        login.state = settings.launchAtLogin ? .on : .off
        menu.addItem(login)
        menu.addItem(.separator())
        menu.addItem(actionItem("Quit Agent HUD", #selector(quit), key: "q"))
    }

    private func infoItem(_ title: String, bold: Bool = false) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        if bold {
            item.attributedTitle = NSAttributedString(string: title, attributes: [
                .font: NSFont.menuFont(ofSize: 0).withWeight(.semibold),
            ])
        }
        return item
    }

    private func actionItem(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        return item
    }

    @objc private func start() { manager.start() }
    @objc private func stop() { manager.stop() }
    @objc private func restart() { manager.restart() }
    @objc private func takeOver() { manager.takeOver() }
    @objc private func dashboard() { openDashboard() }
    @objc private func openLog() { AppActions.openLogFile(manager.log.logURL) }
    @objc private func copyToken() { AppActions.copyWatchToken() }
    @objc private func toggleLogin() { settings.setLaunchAtLogin(!settings.launchAtLogin) }
    @objc private func quit() { NSApp.terminate(nil) }
}

extension NSFont {
    func withWeight(_ weight: NSFont.Weight) -> NSFont {
        NSFont.systemFont(ofSize: pointSize, weight: weight)
    }
}

@MainActor
enum AppActions {
    static let tokenURL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".agenthud-bridge-token")

    static func openLogFile(_ url: URL) {
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        NSWorkspace.shared.open(url)
    }

    static func revealLog(_ url: URL) {
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else {
            NSWorkspace.shared.open(url.deletingLastPathComponent())
        }
    }

    @discardableResult
    static func copyWatchToken() -> Bool {
        guard let token = try? String(contentsOf: tokenURL, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty else {
            NSSound.beep()
            return false
        }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(token, forType: .string)
        return true
    }

    static func openAccessibilitySettings() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
    }

    static func openAutomationSettings() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")
    }

    private static func open(_ s: String) {
        if let url = URL(string: s) { NSWorkspace.shared.open(url) }
    }
}
