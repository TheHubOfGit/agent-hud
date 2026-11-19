import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let settings = AppSettings()
    let log = LogStore()
    lazy var manager = BridgeManager(settings: settings, log: log)
    private var statusItem: StatusItemController?
    private var dashboard: NSWindow?
    private var terminating = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        settings.applyDockIcon()
        buildMainMenu()
        statusItem = StatusItemController(manager: manager, settings: settings) { [weak self] in
            self?.showDashboard()
        }
        log.note("Agent HUD launched (pid \(getpid()), port setting \(settings.port))")
        manager.reapOrphanFromPreviousRun()

        if settings.autoStart { manager.start() }

        let defaults = UserDefaults.standard
        let firstLaunch = !defaults.bool(forKey: AppSettings.Key.hasLaunchedBefore)
        defaults.set(true, forKey: AppSettings.Key.hasLaunchedBefore)
        if firstLaunch || defaults.bool(forKey: "openDashboard") { showDashboard() }

        // Hidden test hook: -snapshotDashboard /path/to/file.png renders the dashboard window
        // to a PNG after a few seconds (no Screen Recording permission needed).
        if let path = defaults.string(forKey: "snapshotDashboard"), !path.isEmpty {
            showDashboard()
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(defaults.double(forKey: "snapshotDelay") > 0 ? defaults.double(forKey: "snapshotDelay") : 6))
                self?.snapshotDashboard(to: path)
            }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showDashboard()
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard manager.hasChild else { return .terminateNow }
        guard !terminating else { return .terminateLater }
        terminating = true
        log.note("Quitting: stopping bridge")
        var replied = false
        let reply = {
            guard !replied else { return }
            replied = true
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        manager.shutdown { reply() }
        // Hard ceiling: the manager SIGKILLs at 2s; give it a little more, then go anyway.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { reply() }
        return .terminateLater
    }

    // MARK: Dashboard

    func showDashboard() {
        if dashboard == nil {
            let host = NSHostingController(rootView: DashboardView(manager: manager, settings: settings))
            let w = NSWindow(contentViewController: host)
            w.title = "Agent HUD"
            w.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            w.setContentSize(NSSize(width: 880, height: 900))
            w.minSize = NSSize(width: 760, height: 560)
            w.isReleasedWhenClosed = false
            w.setFrameAutosaveName("AgentHUDDashboard")
            w.delegate = self
            w.center()
            dashboard = w
        }
        NSApp.activate(ignoringOtherApps: true)
        dashboard?.makeKeyAndOrderFront(nil)
    }

    private func snapshotDashboard(to path: String) {
        guard let view = dashboard?.contentView else { return }
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        if let data = rep.representation(using: .png, properties: [:]) {
            try? data.write(to: URL(fileURLWithPath: (path as NSString).expandingTildeInPath))
            log.note("Dashboard snapshot written to \(path)")
        }
    }

    // MARK: Menu (needed so Cmd-C/V/A/W/Q work in the dashboard)

    private func buildMainMenu() {
        let main = NSMenu()

        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Open Dashboard", action: #selector(openDashboardAction), keyEquivalent: "d").target = self
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Hide Agent HUD", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Quit Agent HUD", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)

        let windowItem = NSMenuItem()
        let window = NSMenu(title: "Window")
        window.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        window.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        windowItem.submenu = window
        main.addItem(windowItem)

        NSApp.mainMenu = main
    }

    @objc private func openDashboardAction() { showDashboard() }
}
