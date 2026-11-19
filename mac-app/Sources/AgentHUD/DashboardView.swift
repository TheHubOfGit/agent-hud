import AppKit
import ApplicationServices
import Combine
import SwiftUI

// MARK: - Root

struct DashboardView: View {
    @Bindable var manager: BridgeManager
    @Bindable var settings: AppSettings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HeaderView(manager: manager)
                Grid(horizontalSpacing: 16, verticalSpacing: 16) {
                    GridRow {
                        WatchCard(manager: manager)
                        AgentsCard(manager: manager)
                    }
                    GridRow {
                        QuotasCard(manager: manager)
                        PermissionsCard()
                    }
                }
                LogCard(log: manager.log)
                SettingsCard(manager: manager, settings: settings)
            }
            .padding(20)
        }
        .frame(minWidth: 760, minHeight: 560)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

// MARK: - Shared pieces

extension BridgeManager.Tint {
    var color: Color {
        switch self {
        case .green: return Color(red: 0.20, green: 0.78, blue: 0.40)
        case .gray: return Color.secondary
        case .amber: return Color(red: 1.0, green: 0.66, blue: 0.12)
        case .red: return Color(red: 0.92, green: 0.32, blue: 0.30)
        }
    }
}

struct Card<Content: View>: View {
    let title: String
    var trailing: AnyView? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title.uppercased())
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .tracking(0.6)
                Spacer()
                if let trailing { trailing }
            }
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(nsColor: .controlBackgroundColor))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color(nsColor: .separatorColor).opacity(0.6), lineWidth: 0.5)
        )
    }
}

struct StatusPill: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.system(size: 12, weight: .medium))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().fill(color.opacity(0.14)))
        .overlay(Capsule().strokeBorder(color.opacity(0.35), lineWidth: 0.5))
    }
}

struct EmptyNote: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 6)
    }
}

extension Color {
    init?(hex: String?) {
        guard var s = hex?.trimmingCharacters(in: .whitespaces), s.hasPrefix("#") else { return nil }
        s.removeFirst()
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

// MARK: - Header

struct HeaderView: View {
    let manager: BridgeManager

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 10) {
                    Text("Agent HUD").font(.system(size: 22, weight: .semibold))
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        StatusPill(text: manager.statusText, color: manager.tint.color)
                    }
                }
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    Text(detailLine).font(.system(size: 12)).foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }
            Spacer()
            controls
        }
    }

    private var detailLine: String {
        var parts: [String] = []
        if let h = manager.health, manager.state == .running {
            if let started = h.startedAt { parts.append("Up \(Fmt.since(started))") }
            if let v = h.version { parts.append("v\(v)") }
            if let pid = h.pid { parts.append("PID \(pid)") }
            parts.append("Port \(h.port ?? manager.runningPort ?? 0)")
            parts.append(h.mdns == true ? "Bonjour on" : "Bonjour off")
        } else if let pid = manager.childPID {
            parts.append("PID \(pid)")
            parts.append("Port \(manager.runningPort ?? manager.settings.port)")
        } else if manager.state == .portInUse {
            parts.append("Another process is listening on port \(manager.runningPort ?? manager.settings.port)")
            if let holder = manager.portHolderPID { parts.append("PID \(holder)") }
        } else if let exit = manager.lastExit {
            parts.append(exit)
        } else {
            parts.append("Port \(manager.settings.port)")
        }
        return parts.joined(separator: "  ·  ")
    }

    @ViewBuilder private var controls: some View {
        HStack(spacing: 8) {
            switch manager.state {
            case .portInUse:
                Button("Take Over") { manager.takeOver() }
                    .help("Stop whatever listens on the port (SIGTERM) and start this app's bridge")
                Button("Retry") { manager.start() }
            case .running, .starting, .crashed:
                Button("Restart") { manager.restart() }
                Button("Stop") { manager.stop() }
            case .stopping, .takingOver:
                ProgressView().controlSize(.small)
            case .stopped, .nodeNotFound, .bridgeNotFound:
                Button("Start") { manager.start() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .controlSize(.large)
    }
}

// MARK: - Watch

struct WatchCard: View {
    let manager: BridgeManager

    var body: some View {
        Card(title: "Watch") {
            let clients = manager.state == .running ? (manager.health?.connectedClients ?? []) : []
            if clients.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Image(systemName: "applewatch.slash").foregroundStyle(.secondary)
                        Text("No watch connected").font(.system(size: 14, weight: .medium))
                    }
                    Text(manager.state == .running
                         ? "Open Agent HUD on the watch. It finds this Mac over Bonjour or a saved IP."
                         : "The bridge is not running.")
                        .font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                TimelineView(.periodic(from: .now, by: 10)) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(clients.enumerated()), id: \.offset) { _, c in
                            HStack(spacing: 8) {
                                Image(systemName: "applewatch.radiowaves.left.and.right")
                                    .foregroundStyle(BridgeManager.Tint.green.color)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(c.ip ?? "Unknown address")
                                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                                        .textSelection(.enabled)
                                    Text("Connected for \(Fmt.since(c.connectedAt))")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Agents

struct AgentsCard: View {
    let manager: BridgeManager

    var body: some View {
        let threads = manager.status?.threadList ?? []
        let waiting = manager.status?.approvalQueue?.count ?? 0
        Card(title: "Agents", trailing: AnyView(
            Text(threads.isEmpty ? "" : (waiting > 0 ? "\(threads.count) · \(waiting) waiting" : "\(threads.count)"))
                .font(.caption).foregroundStyle(.secondary)
        )) {
            if threads.isEmpty {
                EmptyNote(text: manager.state == .running ? "No active agents" : "Bridge not running")
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(threads.prefix(8)) { t in
                        AgentRow(thread: t)
                    }
                    if threads.count > 8 {
                        Text("+\(threads.count - 8) more").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

struct AgentRow: View {
    let thread: BridgeStatus.Thread

    /// Dot shows state, not brand: the bridge's color field is constant per provider.
    private var dotColor: Color {
        let st = thread.status ?? ""
        if st.hasPrefix("waiting") { return .orange }
        if st == "error" { return .red }
        if st == "running" { return .green }
        return Color.secondary.opacity(0.5)
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle().fill(dotColor).frame(width: 7, height: 7)
                .alignmentGuide(.firstTextBaseline) { d in d[.bottom] - 1 }
            VStack(alignment: .leading, spacing: 1) {
                Text(thread.title?.isEmpty == false ? thread.title! : "Untitled")
                    .font(.system(size: 13, weight: .medium)).lineLimit(1)
                Text([thread.providerLabel ?? thread.provider, thread.step].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            StatusChip(status: thread.status ?? "idle")
        }
    }
}

struct StatusChip: View {
    let status: String

    var body: some View {
        Text(label)
            .font(.system(size: 10.5, weight: .semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(color.opacity(0.15)))
    }

    private var label: String {
        switch status {
        case "running": return "Running"
        case "waiting_approval": return "Needs approval"
        case "waiting_input": return "Needs input"
        case "error": return "Error"
        case "idle": return "Idle"
        default: return status.capitalized
        }
    }

    private var color: Color {
        switch status {
        case "running": return .blue
        case "waiting_approval", "waiting_input": return BridgeManager.Tint.amber.color
        case "error": return BridgeManager.Tint.red.color
        default: return .secondary
        }
    }
}

// MARK: - Quotas

struct QuotasCard: View {
    let manager: BridgeManager

    var body: some View {
        Card(title: "Quotas") {
            let quotas = manager.status?.quotaList ?? []
            if quotas.isEmpty {
                EmptyNote(text: manager.state == .running ? "No quota data yet" : "Bridge not running")
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(quotas) { q in
                        QuotaRow(quota: q)
                    }
                }
            }
        }
    }
}

struct QuotaRow: View {
    let quota: BridgeStatus.Quota

    var body: some View {
        let pct = max(0, min(100, quota.remainingPct ?? 0))
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(quota.name ?? quota.id).font(.system(size: 13, weight: .medium))
                Spacer()
                Text(Fmt.percent(quota.remainingPct)).font(.system(size: 12, weight: .semibold).monospacedDigit())
                if let r = quota.resetShort, !r.isEmpty {
                    Text("· resets \(r)").font(.caption).foregroundStyle(.secondary)
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    Capsule().fill(barColor(pct))
                        .frame(width: max(4, geo.size.width * pct / 100))
                }
            }
            .frame(height: 6)
        }
    }

    private func barColor(_ pct: Double) -> Color {
        if pct < 10 { return BridgeManager.Tint.red.color }
        if pct < 25 { return BridgeManager.Tint.amber.color }
        return Color(hex: quota.color) ?? .accentColor
    }
}

// MARK: - Permissions

struct PermissionsCard: View {
    @State private var trusted = AXIsProcessTrusted()
    private let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        Card(title: "Permissions") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: trusted ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(trusted ? BridgeManager.Tint.green.color : BridgeManager.Tint.amber.color)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Accessibility").font(.system(size: 13, weight: .medium))
                        Text(trusted ? "Granted" : "Not granted: replies can't be typed")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Open Settings") { AppActions.openAccessibilitySettings() }
                        .controlSize(.small)
                }
                HStack(spacing: 8) {
                    Image(systemName: "gearshape.2").foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Automation").font(.system(size: 13, weight: .medium))
                        Text("macOS asks the first time the bridge controls System Events")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Open Settings") { AppActions.openAutomationSettings() }
                        .controlSize(.small)
                }
                Text("The bridge runs as a child of Agent HUD, so grant these to Agent HUD.")
                    .font(.caption).foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onReceive(timer) { _ in trusted = AXIsProcessTrusted() }
    }
}

// MARK: - Log

struct LogCard: View {
    let log: LogStore
    @State private var autoscroll = true

    var body: some View {
        Card(title: "Log", trailing: AnyView(trailing)) {
            ScrollViewReader { proxy in
                ScrollView([.vertical, .horizontal]) {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(log.lines) { line in
                            Text(line.text)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(line.text.hasPrefix("[agent-hud]") ? Color.accentColor : Color.primary)
                                .fixedSize(horizontal: true, vertical: false)
                                .id(line.id)
                        }
                    }
                    .padding(8)
                    .textSelection(.enabled)
                }
                .frame(height: 220)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color(nsColor: .textBackgroundColor))
                )
                .onChange(of: log.lines.last?.id) { _, newID in
                    guard autoscroll, let newID else { return }
                    proxy.scrollTo(newID, anchor: .bottomLeading)
                }
                .onAppear {
                    if let id = log.lines.last?.id { proxy.scrollTo(id, anchor: .bottomLeading) }
                }
            }
            Text(log.logURL.path)
                .font(.caption2).foregroundStyle(.tertiary)
                .textSelection(.enabled)
        }
    }

    private var trailing: some View {
        HStack(spacing: 10) {
            Toggle("Autoscroll", isOn: $autoscroll).toggleStyle(.checkbox).controlSize(.small)
            Button("Clear") { log.clearView() }.controlSize(.small)
            Button("Reveal in Finder") { AppActions.revealLog(log.logURL) }.controlSize(.small)
        }
    }
}

// MARK: - Settings

struct SettingsCard: View {
    @Bindable var manager: BridgeManager
    @Bindable var settings: AppSettings
    @State private var portText = ""

    var body: some View {
        Card(title: "Settings") {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 10) {
                GridRow {
                    label("Node")
                    HStack {
                        TextField(manager.resolvedNodePath ?? "Auto-detect", text: $settings.nodePathOverride)
                            .textFieldStyle(.roundedBorder)
                        Button("Choose…") { chooseNode() }
                        if !settings.nodePathOverride.isEmpty {
                            Button("Auto") { settings.nodePathOverride = "" }
                        }
                    }
                }
                GridRow {
                    Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                    Text(nodeCaption).font(.caption).foregroundStyle(.secondary)
                }
                GridRow {
                    label("Bridge folder")
                    HStack {
                        TextField("mac-agent-bridge folder", text: $settings.bridgeFolder)
                            .textFieldStyle(.roundedBorder)
                        Button("Choose…") { chooseFolder() }
                    }
                }
                GridRow {
                    label("Port")
                    HStack {
                        TextField("8765", text: $portText)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 80)
                            .onSubmit(commitPort)
                        Button("Apply") { commitPort() }.disabled(Int(portText) == settings.port)
                        Text(settings.port == AppSettings.defaultPort
                             ? "Default port; Bonjour advertising on"
                             : "Non-default port; Bonjour advertising off (AGENT_HUD_NO_MDNS=1)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Divider().gridCellColumns(2)
                GridRow {
                    label("Startup")
                    VStack(alignment: .leading, spacing: 6) {
                        Toggle("Start the bridge when Agent HUD launches", isOn: $settings.autoStart)
                        Toggle("Launch Agent HUD at login", isOn: Binding(
                            get: { settings.launchAtLogin },
                            set: { settings.setLaunchAtLogin($0) }
                        ))
                        Text("Login item: \(settings.loginItemStatusText)" + (settings.loginItemError.map { " · \($0)" } ?? ""))
                            .font(.caption)
                            .foregroundStyle(settings.loginItemError == nil ? Color.secondary : BridgeManager.Tint.red.color)
                            .fixedSize(horizontal: false, vertical: true)
                        Toggle("Show Dock icon", isOn: $settings.showDockIcon)
                    }
                }
            }
            if manager.needsRestartForSettings {
                HStack {
                    Image(systemName: "arrow.clockwise.circle").foregroundStyle(BridgeManager.Tint.amber.color)
                    Text("Settings changed. Restart the bridge to apply.").font(.callout)
                    Spacer()
                    Button("Restart Now") { manager.restart() }
                }
                .padding(.top, 4)
            }
        }
        .onAppear {
            portText = String(settings.port)
            settings.refreshLoginItemStatus()
        }
        .onChange(of: settings.port) { _, p in portText = String(p) }
    }

    private var nodeCaption: String {
        if let path = manager.resolvedNodePath {
            return "Using \(path)" + (manager.nodeVersion.map { " (\($0))" } ?? "")
        }
        return manager.state == .nodeNotFound ? "Node.js was not found. Install it or choose the binary." : "Detected on start"
    }

    private func label(_ s: String) -> some View {
        Text(s).font(.system(size: 13)).foregroundStyle(.secondary)
            .gridColumnAlignment(.trailing)
    }

    private func commitPort() {
        guard let p = Int(portText.trimmingCharacters(in: .whitespaces)), (1...65535).contains(p) else {
            portText = String(settings.port)
            NSSound.beep()
            return
        }
        settings.port = p
    }

    private func chooseNode() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.showsHiddenFiles = true
        panel.directoryURL = URL(fileURLWithPath: "/opt/homebrew/bin")
        panel.message = "Choose the node executable"
        if panel.runModal() == .OK, let url = panel.url { settings.nodePathOverride = url.path }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.directoryURL = URL(fileURLWithPath: settings.bridgeFolder)
        panel.message = "Choose the mac-agent-bridge folder (contains src/server.mjs)"
        if panel.runModal() == .OK, let url = panel.url { settings.bridgeFolder = url.path }
    }
}
