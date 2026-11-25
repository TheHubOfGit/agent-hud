import AppKit
import ApplicationServices
import Carbon.HIToolbox
import Foundation

// MARK: - Helpers

struct AXNodeInfo {
    let element: AXUIElement
    let role: String
    let subrole: String
    let title: String
    let desc: String
    let value: String
    let text: String
    let frame: CGRect
    let actions: [String]
}

func axString(_ el: AXUIElement, _ attr: String) -> String {
    var ref: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &ref) == .success else { return "" }
    if let s = ref as? String { return s }
    if let n = ref as? NSNumber { return n.stringValue }
    return ""
}

func axBool(_ el: AXUIElement, _ attr: String) -> Bool? {
    var ref: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, attr as CFString, &ref) == .success else { return nil }
    if let b = ref as? Bool { return b }
    if let n = ref as? NSNumber { return n.boolValue }
    return nil
}

func axFrame(_ el: AXUIElement) -> CGRect {
    var posRef: CFTypeRef?
    var szRef: CFTypeRef?
    var pt = CGPoint.zero
    var sz = CGSize.zero
    if AXUIElementCopyAttributeValue(el, kAXPositionAttribute as CFString, &posRef) == .success,
       let pv = posRef, CFGetTypeID(pv) == AXValueGetTypeID() {
        AXValueGetValue(pv as! AXValue, .cgPoint, &pt)
    }
    if AXUIElementCopyAttributeValue(el, kAXSizeAttribute as CFString, &szRef) == .success,
       let sv = szRef, CFGetTypeID(sv) == AXValueGetTypeID() {
        AXValueGetValue(sv as! AXValue, .cgSize, &sz)
    }
    return CGRect(origin: pt, size: sz)
}

func axChildren(_ el: AXUIElement) -> [AXUIElement] {
    var ref: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &ref) == .success,
          let arr = ref as? NSArray else { return [] }
    var out: [AXUIElement] = []
    out.reserveCapacity(arr.count)
    for item in arr {
        let cf = item as CFTypeRef
        if CFGetTypeID(cf) == AXUIElementGetTypeID() {
            out.append(cf as! AXUIElement)
        }
    }
    return out
}

func axActions(_ el: AXUIElement) -> [String] {
    var ref: CFArray?
    guard AXUIElementCopyActionNames(el, &ref) == .success, let arr = ref as? [String] else { return [] }
    return arr
}

func collectLabel(_ el: AXUIElement, depth: Int = 0) -> String {
    if depth > 4 { return "" }
    let t = axString(el, kAXTitleAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
    let d = axString(el, kAXDescriptionAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
    let v = axString(el, kAXValueAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
    var parts: [String] = []
    for s in [t, d, v] where !s.isEmpty && !parts.contains(s) {
        parts.append(s)
    }
    if !parts.isEmpty { return parts.joined(separator: " ") }
    for child in axChildren(el).prefix(8) {
        let ct = collectLabel(child, depth: depth + 1)
        if !ct.isEmpty && !parts.contains(ct) { parts.append(ct) }
    }
    return parts.joined(separator: " ")
}

func allWindows(of axApp: AXUIElement) -> [AXUIElement] {
    var out: [AXUIElement] = []
    var focusedRef: CFTypeRef?
    if AXUIElementCopyAttributeValue(axApp, kAXFocusedWindowAttribute as CFString, &focusedRef) == .success,
       let w = focusedRef, CFGetTypeID(w) == AXUIElementGetTypeID() {
        out.append(w as! AXUIElement)
    }
    var winsRef: CFTypeRef?
    if AXUIElementCopyAttributeValue(axApp, kAXWindowsAttribute as CFString, &winsRef) == .success,
       let arr = winsRef as? NSArray {
        for item in arr {
            let cf = item as CFTypeRef
            if CFGetTypeID(cf) == AXUIElementGetTypeID() {
                let el = cf as! AXUIElement
                if !out.contains(where: { CFEqual($0, el) }) { out.append(el) }
            }
        }
    }
    return out
}

func frontmostWindow(of axApp: AXUIElement) -> AXUIElement? {
    return allWindows(of: axApp).first
}

func enableManualAccessibility(_ axApp: AXUIElement) {
    AXUIElementSetAttributeValue(axApp, "AXManualAccessibility" as CFString, kCFBooleanTrue)
    AXUIElementSetAttributeValue(axApp, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)
}

func walkInteractive(
    _ root: AXUIElement,
    roles: Set<String>,
    maxNodes: Int = 3800,
    maxDepth: Int = 38,
    reverseChildren: Bool = true
) -> [AXNodeInfo] {
    var results: [AXNodeInfo] = []
    var visited = 0
    var stack: [(AXUIElement, Int)] = [(root, 0)]

    while let (el, depth) = stack.popLast() {
        if visited >= maxNodes { break }
        if depth > maxDepth { continue }
        let role = axString(el, kAXRoleAttribute)
        if role == "AXMenuBar" || role == "AXMenuBarItem" || role == "AXApplication" { continue }
        visited += 1

        if roles.contains(role) {
            let subrole = axString(el, kAXSubroleAttribute)
            let title = axString(el, kAXTitleAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
            let desc = axString(el, kAXDescriptionAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
            let val = axString(el, kAXValueAttribute).trimmingCharacters(in: .whitespacesAndNewlines)
            let text = collectLabel(el)
            let frame = axFrame(el)
            let actions = axActions(el)
            results.append(AXNodeInfo(
                element: el, role: role, subrole: subrole,
                title: title, desc: desc, value: val, text: text,
                frame: frame, actions: actions
            ))
        }

        let kids = axChildren(el)
        if !kids.isEmpty {
            if reverseChildren {
                for c in kids { stack.append((c, depth + 1)) }
            } else {
                for c in kids.reversed() { stack.append((c, depth + 1)) }
            }
        }
    }
    return results
}

func providerForApp(bundleId: String, name: String, pid: pid_t) -> String {
    let b = bundleId.lowercased()
    let n = name.lowercased()
    if b.contains("antigravity") || n.contains("antigravity") { return "antigravity" }
    if b.contains("claudefordesktop") || n == "claude" { return "claude" }
    if b.contains("openai.codex") || n == "codex" { return "codex" }
    if b.contains("t3code") || n.contains("t3 code") { return "t3code" }
    if b.contains("cursor") || n == "cursor" { return "cursor" }
    if isTerminalApp(bundleId: b) {
        // What is running in the terminal decides it. One kind of agent CLI: that CLI. Several kinds
        // (Claude Code in one tab, Codex in another): the front tab cannot be told apart, so none of
        // them is assumed. Nothing agent-like: a plain terminal.
        let kinds = terminalAgentKinds(pid: pid)
        if kinds.count > 1 { return "agent_cli_mixed" }
        if let only = kinds.first { return only + "_cli" }   // claude_cli, codex_cli, agy_cli
        return "terminal"
    }
    return ""
}

/// Dedicated terminal emulators. Editors that embed a terminal (VS Code, Cursor) are left out on
/// purpose: their own helper processes, such as the Claude Code extension's, would read as a CLI.
let terminalBundleMarkers = ["ghostty", "apple.terminal", "iterm2", "dev.warp", "kovidgoyal.kitty", "wez.wezterm", "alacritty"]
func isTerminalApp(bundleId: String) -> Bool { terminalBundleMarkers.contains { bundleId.contains($0) } }

/// Which agent CLI an executable path belongs to. Exact names only, never a substring ("agy" must not
/// match "legacy" or "pagy"), and never "something inside a folder called claude": the program itself
/// must be `claude` / `claude.exe`, `codex` (Codex's native binary, which the npm `codex` Node launcher
/// starts) or `agy`, or sit in the native installers' layout `.../<tool>/versions/<version>`.
func agentKind(forExecutable path: String) -> String? {
    let parts = path.lowercased().split(separator: "/").map(String.init)
    guard let last = parts.last else { return nil }
    func kind(_ name: String) -> String? {
        switch name {
        case "claude", "claude-code": return "claude"
        case "codex": return "codex"
        case "agy": return "agy"
        default: return nil
        }
    }
    if let k = kind(last.hasSuffix(".exe") ? String(last.dropLast(4)) : last) { return k }
    if parts.count >= 3, parts[parts.count - 2] == "versions", let k = kind(parts[parts.count - 3]) { return k }
    return nil
}

/// The agent CLIs running under a terminal process, from one `ps` snapshot. An agent's own children
/// are not searched: Claude Code starting a Codex tool does not make the tab "mixed".
func agentKindsInSubtree(rootPid: pid_t, ps: String) -> Set<String> {
    var children: [Int32: [Int32]] = [:]
    var comm: [Int32: String] = [:]
    for line in ps.split(separator: "\n") {
        // "  pid  ppid /path/with spaces/exe": the path is the rest of the line.
        let parts = line.split(maxSplits: 2, omittingEmptySubsequences: true, whereSeparator: { $0 == " " || $0 == "\t" })
        guard parts.count == 3, let c = Int32(parts[0]), let p = Int32(parts[1]) else { continue }
        children[p, default: []].append(c)
        comm[c] = String(parts[2])
    }
    var kinds: Set<String> = []
    var queue: [Int32] = [rootPid]
    var seen: Set<Int32> = [rootPid]
    while let cur = queue.popLast() {
        for child in children[cur] ?? [] {
            if !seen.insert(child).inserted { continue }
            if let path = comm[child], let kind = agentKind(forExecutable: path) { kinds.insert(kind); continue }
            queue.append(child)
        }
    }
    return kinds
}

func psSnapshot() -> String {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/bin/ps")
    p.arguments = ["-eo", "pid=,ppid=,comm="]
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = FileHandle.nullDevice
    do { try p.run() } catch { return "" }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    return String(data: data, encoding: .utf8) ?? ""
}

func terminalAgentKinds(pid: pid_t) -> Set<String> {
    return agentKindsInSubtree(rootPid: pid, ps: psSnapshot())
}

/// Name shown on the watch (and sent back after a paste) when the front app is a terminal running a CLI.
func cliDisplayName(provider: String) -> String? {
    switch provider {
    case "claude_cli": return "Claude Code"
    case "codex_cli": return "Codex CLI"
    case "agy_cli": return "Antigravity CLI"
    default: return nil
    }
}

// MARK: - Mouse / Key / Clipboard Injection

func postKey(keyCode: CGKeyCode, flags: CGEventFlags = []) {
    guard let src = CGEventSource(stateID: .hidSystemState),
          let down = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: true),
          let up = CGEvent(keyboardEventSource: src, virtualKey: keyCode, keyDown: false) else { return }
    down.flags = flags
    up.flags = flags
    down.post(tap: .cghidEventTap)
    usleep(22_000)
    up.post(tap: .cghidEventTap)
}

func moveMouse(to pt: CGPoint) {
    CGWarpMouseCursorPosition(pt)
    CGAssociateMouseAndMouseCursorPosition(1)
    if let src = CGEventSource(stateID: .hidSystemState) {
        src.localEventsSuppressionInterval = 0.0
        if let move = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: pt, mouseButton: .left) {
            move.post(tap: .cghidEventTap)
        }
    }
}

func currentMouseLocation() -> CGPoint {
    return CGEvent(source: nil)?.location ?? .zero
}

func clickPoint(_ pt: CGPoint) {
    moveMouse(to: pt)
    usleep(30_000)
    if let src = CGEventSource(stateID: .hidSystemState) {
        src.localEventsSuppressionInterval = 0.0
        if let down = CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: pt, mouseButton: .left),
           let up = CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: pt, mouseButton: .left) {
            down.post(tap: .cghidEventTap)
            usleep(30_000)
            up.post(tap: .cghidEventTap)
        }
    }
}

func ensureComposerFocused(pid: pid_t, provider: String) {
    guard ["antigravity", "claude", "codex", "t3code", "cursor"].contains(provider) else { return }
    let axApp = AXUIElementCreateApplication(pid)
    enableManualAccessibility(axApp)

    // Check if an editable text input is already focused
    var focusedRef: CFTypeRef?
    if AXUIElementCopyAttributeValue(axApp, kAXFocusedUIElementAttribute as CFString, &focusedRef) == .success,
       let f = focusedRef, CFGetTypeID(f) == AXUIElementGetTypeID() {
        let fRole = axString(f as! AXUIElement, kAXRoleAttribute)
        if fRole == "AXTextArea" || fRole == "AXTextField" {
            return
        }
    }

    guard let win = frontmostWindow(of: axApp) else { return }
    let winFrame = axFrame(win)
    let minY = winFrame.height > 100 ? (winFrame.minY + winFrame.height * 0.55) : 0
    let textAreas = walkInteractive(win, roles: ["AXTextArea", "AXTextField"], maxNodes: 3000, maxDepth: 36, reverseChildren: true)
        .filter { $0.frame.width >= 120 && $0.frame.height >= 16 && $0.frame.minY >= minY }
        .sorted { $0.frame.minY > $1.frame.minY }

    if let composer = textAreas.first {
        let savedCursor = currentMouseLocation()
        AXUIElementSetAttributeValue(composer.element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
        let clickPt = CGPoint(x: composer.frame.minX + min(60, composer.frame.width / 2), y: composer.frame.midY)
        clickPoint(clickPt)
        moveMouse(to: savedCursor)
        usleep(140_000)
    }
}

/// Apps where a dictated message is meant to be submitted. Everywhere else (a plain terminal, mail,
/// chat, a browser, an editor) the text is pasted but Return is left to the person: a misheard or
/// stray dictation must never be sent, or run as a shell command, without a human pressing Return.
let autoSendProviders: Set<String> = ["antigravity", "claude", "codex", "t3code", "cursor", "claude_cli", "codex_cli", "agy_cli", "agent_cli_mixed"]

func returnAllowed(provider: String) -> Bool { autoSendProviders.contains(provider) }

/// The watch says which app it was showing. If the front app is a different one by the time the
/// text arrives, nothing is pasted: it would land somewhere the person did not see.
func focusMatches(expected: String?, actual: String) -> Bool {
    guard let expected = expected, !expected.isEmpty else { return true }
    return expected == actual
}

func pasteAndSend(text: String, pressReturn: Bool = true, expectBundleId: String? = nil) -> [String: Any] {
    guard AXIsProcessTrusted() else {
        return ["ok": false, "error": "Mac needs Accessibility permission for Agent HUD"]
    }
    guard let front = NSWorkspace.shared.frontmostApplication else {
        return ["ok": false, "error": "No focused app on Mac"]
    }
    let rawName = front.localizedName ?? "Mac"
    let bundleId = front.bundleIdentifier ?? ""
    if bundleId == "com.apple.loginwindow" || rawName.lowercased() == "loginwindow" {
        return ["ok": false, "error": "Mac is locked"]
    }
    let provider = providerForApp(bundleId: bundleId, name: rawName, pid: front.processIdentifier)
    if !focusMatches(expected: expectBundleId, actual: bundleId) {
        return ["ok": false, "appName": rawName, "bundleId": bundleId,
                "error": "Focus moved to \(rawName), so nothing was sent"]
    }
    let sendReturn = pressReturn && returnAllowed(provider: provider)

    // Guarantee the chat composer text input has focus before pasting
    ensureComposerFocused(pid: front.processIdentifier, provider: provider)

    let pb = NSPasteboard.general
    var savedItems: [NSPasteboardItem] = []
    if let items = pb.pasteboardItems {
        for it in items {
            let copy = NSPasteboardItem()
            for t in it.types {
                if let d = it.data(forType: t) { copy.setData(d, forType: t) }
            }
            savedItems.append(copy)
        }
    }

    pb.clearContents()
    pb.setString(text, forType: .string)
    usleep(70_000)

    // Paste with Cmd+V
    postKey(keyCode: CGKeyCode(kVK_ANSI_V), flags: .maskCommand)
    if sendReturn {
        // Give React/Electron controlled textareas enough time to flush pasted state before Return
        usleep(220_000)
        postKey(keyCode: CGKeyCode(kVK_Return), flags: [])
    }
    usleep(350_000)

    pb.clearContents()
    if !savedItems.isEmpty {
        pb.writeObjects(savedItems)
    }
    return ["ok": true, "appName": cliDisplayName(provider: provider) ?? rawName, "bundleId": bundleId, "provider": provider,
            "returned": sendReturn, "pastedOnly": pressReturn && !sendReturn]
}

/// What Live Mode would do for an app, without touching anything (also used by the tests).
func checkTarget(bundleId: String, name: String, expect: String?, pid: pid_t = -1) -> [String: Any] {
    let provider = providerForApp(bundleId: bundleId, name: name, pid: pid)
    return ["ok": true, "provider": provider, "returnAllowed": returnAllowed(provider: provider),
            "focusOk": focusMatches(expected: expect, actual: bundleId)]
}

// MARK: - Thinking Level Parsing & Detection

struct ThinkingMatch {
    let button: AXNodeInfo
    let baseModel: String       // e.g. "Gemini 4 Neon", "Sonnet 5.5", "Gemini 4 Hydrogen"
    let currentLevel: String    // e.g. "Medium", "High"
    let knownLevels: [String]
    let currentIndex: Int
    let isToggle: Bool
}

func normalizeLevelLabel(_ raw: String) -> String? {
    let low = raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    if low.contains("extra high") || low.contains("xhigh") { return "Extra High" }
    if low.range(of: #"\bextra\b"#, options: .regularExpression) != nil { return "Extra" }
    if low.range(of: #"\bmax\b"#, options: .regularExpression) != nil { return "Max" }
    if low.range(of: #"\bhigh\b"#, options: .regularExpression) != nil { return "High" }
    if low.range(of: #"\bmed(ium)?\b"#, options: .regularExpression) != nil { return "Medium" }
    if low.range(of: #"\blow\b"#, options: .regularExpression) != nil { return "Low" }
    if low.range(of: #"\bminimal\b"#, options: .regularExpression) != nil { return "Minimal" }
    if low.range(of: #"\boff\b"#, options: .regularExpression) != nil { return "Off" }
    if low.range(of: #"\bfast\b"#, options: .regularExpression) != nil { return "Fast" }
    if low.range(of: #"\bplanning\b"#, options: .regularExpression) != nil { return "Planning" }
    return nil
}

func extractBaseModel(_ rawLabel: String) -> String {
    var s = rawLabel.trimmingCharacters(in: .whitespacesAndNewlines)
    let prefixes = [
        "select model, current:",
        "model:",
        "current:",
    ]
    for p in prefixes {
        if s.lowercased().hasPrefix(p) {
            s = String(s.dropFirst(p.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }
    // Strip trailing effort words and badges like "(High)", "Medium", "Extra", "New"
    let pattern = #"(?i)(\s*\(?(extra\s+high|extra|xhigh|minimal|medium|med|high|low|max|off|new|unavailable|notice)\)?)+$"#
    if let r = s.range(of: pattern, options: .regularExpression) {
        s = String(s[..<r.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    return s
}

func defaultLevelsFor(provider: String, current: String) -> [String] {
    switch provider {
    case "antigravity":
        return ["Low", "Medium", "High", "Extra"]
    case "claude":
        return ["Low", "Medium", "High", "Extra", "Max"]
    case "codex":
        return ["Low", "Medium", "High", "Extra High"]
    case "t3code":
        return ["Low", "Medium", "High", "Extra High"]
    default:
        return ["Low", "Medium", "High"]
    }
}

func findThinkingButton(in win: AXUIElement, provider: String) -> ThinkingMatch? {
    let winFrame = axFrame(win)
    let interactiveRoles: Set<String> = ["AXPopUpButton", "AXButton", "AXMenuButton", "AXCheckBox", "AXComboBox"]
    let nodes = walkInteractive(win, roles: interactiveRoles, maxNodes: 3500, maxDepth: 36, reverseChildren: true)
        .filter { $0.frame.width >= 24 && $0.frame.height >= 14 }

    // Composer lives in the bottom 30% of the window
    let composerMinY = winFrame.height > 120 ? (winFrame.minY + winFrame.height * 0.65) : 0
    let sorted = nodes.sorted { a, b in
        let aBot = a.frame.minY >= composerMinY
        let bBot = b.frame.minY >= composerMinY
        if aBot != bBot { return aBot }
        return a.frame.minY > b.frame.minY
    }

    for node in sorted {
        if node.frame.minY < composerMinY { continue }
        let label = [node.title, node.desc, node.text]
            .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) ?? ""
        let low = label.lowercased()
        if low.isEmpty { continue }

        // Skip non-model buttons (usage, permissions, etc.)
        if low.contains("usage") || low.contains("permission") || low.contains("organization") { continue }

        let level = normalizeLevelLabel(low)
        let isModelSelector = low.contains("select model") || low.contains("model:") ||
            low.contains("gemini") || low.contains("claude") || low.contains("sonnet") ||
            low.contains("opus") || low.contains("haiku") || low.contains("gpt") ||
            low.contains("codex") || low.contains("effort") || low.contains("reason") || low.contains("think")

        if let lvl = level, (isModelSelector || node.role == "AXPopUpButton") {
            // Avoid matching generic "Auto" permissions buttons unless they are a real effort level
            if !isModelSelector && !["Low", "Medium", "High", "Extra High", "Max", "Minimal"].contains(lvl) { continue }
            let base = extractBaseModel(label)
            let levels = defaultLevelsFor(provider: provider, current: lvl)
            let idx = levels.firstIndex(of: lvl) ?? 1
            return ThinkingMatch(button: node, baseModel: base, currentLevel: lvl, knownLevels: levels, currentIndex: idx, isToggle: false)
        }
    }
    return nil
}

// MARK: - Inspect & Step

/// Claude Code's thinking toggle (Option+T) cannot be read back from a terminal, so this is the state
/// Live Mode last set, assumed On until then. A focus event used to report "On" again after we had
/// turned it Off, so the dial and the terminal disagreed.
let cliStateLock = NSLock()
var cliThinkingOnValue = true
func cliThinking() -> Bool { cliStateLock.lock(); defer { cliStateLock.unlock() }; return cliThinkingOnValue }
func setCliThinking(_ on: Bool) { cliStateLock.lock(); cliThinkingOnValue = on; cliStateLock.unlock() }

func inspectFrontmost() -> [String: Any] {
    guard let front = NSWorkspace.shared.frontmostApplication else {
        return ["ok": true, "appName": "Mac", "bundleId": "", "provider": "", "dialSupported": false, "level": "", "levels": [], "index": -1]
    }
    let rawName = front.localizedName ?? "Mac"
    let bundleId = front.bundleIdentifier ?? ""
    let name = (bundleId == "com.apple.loginwindow" || rawName.lowercased() == "loginwindow") ? "Mac Locked" : rawName
    let pid = front.processIdentifier
    let provider = providerForApp(bundleId: bundleId, name: name, pid: pid)

    if !AXIsProcessTrusted() {
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": false, "level": "", "levels": [], "index": -1,
            "error": "Accessibility permission required"
        ]
    }

    if provider == "claude_cli" {
        return [
            "ok": true, "appName": "Claude Code", "bundleId": bundleId, "provider": "claude",
            "dialSupported": true, "level": cliThinking() ? "On" : "Off", "levels": ["Off", "On"], "index": cliThinking() ? 1 : 0
        ]
    }

    // Terminal CLIs other than Claude Code have no level control to drive: dictation only. They show
    // the tool's own logo and name instead of "Ghostty".
    if provider == "codex_cli" || provider == "agy_cli" {
        return [
            "ok": true, "appName": cliDisplayName(provider: provider) ?? name, "bundleId": bundleId,
            "provider": provider == "agy_cli" ? "antigravity" : "codex",
            "dialSupported": false, "level": "", "levels": [], "index": -1
        ]
    }

    guard ["antigravity", "claude", "codex", "t3code"].contains(provider) else {
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": false, "level": "", "levels": [], "index": -1
        ]
    }

    let axApp = AXUIElementCreateApplication(pid)
    enableManualAccessibility(axApp)
    guard let win = frontmostWindow(of: axApp) else {
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": false, "level": "", "levels": [], "index": -1
        ]
    }

    if let match = findThinkingButton(in: win, provider: provider) {
        return [
            "ok": true,
            "appName": name,
            "bundleId": bundleId,
            "provider": provider,
            "dialSupported": true,
            "baseModel": match.baseModel,
            "level": match.currentLevel,
            "levels": match.knownLevels,
            "index": match.currentIndex
        ]
    }

    return [
        "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
        "dialSupported": false, "level": "", "levels": [], "index": -1
    ]
}

let effortOrderRank: [String: Int] = [
    "Off": 0, "Fast": 1, "Minimal": 2, "Low": 3, "Medium": 4,
    "Planning": 5, "High": 6, "Extra": 7, "Extra High": 7, "Max": 8
]

func linkedElements(of el: AXUIElement) -> [AXUIElement] {
    var ref: CFTypeRef?
    guard AXUIElementCopyAttributeValue(el, "AXLinkedUIElements" as CFString, &ref) == .success,
          let arr = ref as? NSArray else { return [] }
    var out: [AXUIElement] = []
    for it in arr {
        let cf = it as CFTypeRef
        if CFGetTypeID(cf) == AXUIElementGetTypeID() {
            out.append(cf as! AXUIElement)
        }
    }
    return out
}

/// Matches an effort-only option label (even if it has a subtitle like "High · Deeper reasoning" or "Medium 1"),
/// while rejecting rows that belong to a different model (e.g. "Claude Opus 5.5 Medium").
func matchStandaloneEffort(_ raw: String) -> String? {
    let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if clean.isEmpty || clean.count > 110 { return nil }
    let low = clean.lowercased()
    let modelWords = ["gemini", "claude", "sonnet", "opus", "haiku", "gpt", "codex", "orchestrator", "argon", "hydrogen", "neon", "carbon", "flash"]
    if modelWords.contains(where: { low.contains($0) }) { return nil }

    let trimmed = low
        .replacingOccurrences(of: #"^(effort|thinking|reasoning)\s*:?\s*"#, with: "", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.hasPrefix("extra high") || trimmed.hasPrefix("xhigh") { return "Extra High" }
    if trimmed.range(of: #"^extra(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Extra" }
    if trimmed.range(of: #"^max(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Max" }
    if trimmed.range(of: #"^high(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "High" }
    if trimmed.range(of: #"^med(ium)?(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Medium" }
    if trimmed.range(of: #"^low(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Low" }
    if trimmed.range(of: #"^minimal(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Minimal" }
    if trimmed.range(of: #"^(off|none)(\b|[\s·\-—(01])"#, options: .regularExpression) != nil { return "Off" }
    return nil
}

/// Collects selectable effort options from explicit AX root containers (e.g. AXLinkedUIElements submenus)
/// or from all windows of `axApp`. Always restricts to standalone effort items or `baseModel`-matched items.
func collectEffortOptions(in roots: [AXUIElement], excluding buttonEl: AXUIElement, baseModel: String) -> [(label: String, node: AXNodeInfo)] {
    let menuRoles: Set<String> = ["AXMenuItem", "AXRadioButton", "AXButton", "AXCheckBox"]
    let baseLow = baseModel.lowercased()
    var standalone: [(label: String, node: AXNodeInfo)] = []
    var modelMatched: [(label: String, node: AXNodeInfo)] = []
    var seenStandalone: Set<String> = []
    var seenModel: Set<String> = []

    for root in roots {
        let nodes = walkInteractive(root, roles: menuRoles, maxNodes: 1200, maxDepth: 36, reverseChildren: true)
        for n in nodes {
            if CFEqual(n.element, buttonEl) { continue }
            let raw = [n.title, n.desc, n.text]
                .first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) ?? ""
            let clean = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if clean.isEmpty || clean.count > 110 { continue }

            if let lvl = matchStandaloneEffort(clean) {
                if !seenStandalone.contains(lvl) {
                    seenStandalone.insert(lvl)
                    standalone.append((lvl, n))
                }
                continue
            }

            let low = clean.lowercased()
            if !baseLow.isEmpty && low.contains(baseLow), let lvl = normalizeLevelLabel(low) {
                if !seenModel.contains(lvl) {
                    seenModel.insert(lvl)
                    modelMatched.append((lvl, n))
                }
            }
        }
    }

    var chosen = standalone.count >= 2 ? standalone : modelMatched
    chosen.sort { (effortOrderRank[$0.label] ?? 50) < (effortOrderRank[$1.label] ?? 50) }
    return chosen
}

func pressOrClick(_ node: AXNodeInfo) {
    let err = AXUIElementPerformAction(node.element, kAXPressAction as CFString)
    if err != .success && node.frame.width >= 6 && node.frame.height >= 6 {
        clickPoint(CGPoint(x: node.frame.midX, y: node.frame.midY))
    }
}

/// Polls `check()` every `stepUs` microseconds up to `maxUs`, returning the first non-nil result immediately.
func pollUntil<T>(maxUs: UInt32, stepUs: UInt32 = 25_000, _ check: () -> T?) -> T? {
    var elapsed: UInt32 = 0
    while elapsed <= maxUs {
        if let v = check() { return v }
        usleep(stepUs)
        elapsed += stepUs
    }
    return nil
}

func openPopover(for button: AXNodeInfo, provider: String, in axApp: AXUIElement, movedCursor: inout Bool) -> [AXUIElement] {
    // If the popover is already open and visible (not a stale closing container), reuse it instead of toggling it closed.
    let existing = linkedElements(of: button.element).filter {
        let f = axFrame($0)
        return f.width >= 20 && f.height >= 20
    }
    if !existing.isEmpty { return existing }

    // Antigravity opens cleanly via AXPress; Claude Desktop / Radix onPointerDown popovers need a real click.
    if provider == "antigravity" {
        AXUIElementPerformAction(button.element, kAXPressAction as CFString)
    } else if button.frame.width >= 6 && button.frame.height >= 6 {
        movedCursor = true
        clickPoint(CGPoint(x: button.frame.midX, y: button.frame.midY))
    } else {
        AXUIElementPerformAction(button.element, kAXPressAction as CFString)
    }

    if let linked = pollUntil(maxUs: 220_000, stepUs: 20_000, {
        let l = linkedElements(of: button.element)
        return l.isEmpty ? nil : l
    }) {
        // Brief settle so Radix menu items finish mounting and become focusable
        usleep(45_000)
        return linked
    }
    return linkedElements(of: button.element)
}

func stepThinking(delta: Int, targetLevel: String? = nil, targetIndex: Int? = nil, retry: Bool = true) -> [String: Any] {
    guard AXIsProcessTrusted() else {
        return ["ok": false, "confirmed": false, "changed": false, "error": "Accessibility permission required"]
    }
    guard let front = NSWorkspace.shared.frontmostApplication else {
        return ["ok": false, "confirmed": false, "changed": false, "error": "No focused app"]
    }
    let name = front.localizedName ?? "Mac"
    let bundleId = front.bundleIdentifier ?? ""
    let pid = front.processIdentifier
    let provider = providerForApp(bundleId: bundleId, name: name, pid: pid)

    if provider == "claude_cli" {
        let wantOn: Bool
        if let l = targetLevel { wantOn = l.caseInsensitiveCompare("On") == .orderedSame }
        else if let i = targetIndex { wantOn = i >= 1 }
        else { wantOn = delta > 0 }
        let already = wantOn == cliThinking()
        // Option+T flips the setting, so only press it when the state has to change.
        if !already {
            postKey(keyCode: CGKeyCode(kVK_ANSI_T), flags: .maskAlternate)
            setCliThinking(wantOn)
        }
        return [
            "ok": true, "appName": "Claude Code", "bundleId": bundleId, "provider": "claude",
            "dialSupported": true, "confirmed": true, "changed": !already, "boundary": already,
            "level": wantOn ? "On" : "Off", "levels": ["Off", "On"], "index": wantOn ? 1 : 0
        ]
    }

    let axApp = AXUIElementCreateApplication(pid)
    enableManualAccessibility(axApp)
    guard let win = frontmostWindow(of: axApp),
          let match = findThinkingButton(in: win, provider: provider) else {
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": false, "confirmed": false, "changed": false, "boundary": false,
            "level": "", "levels": [], "index": -1, "error": "No thinking control in \(name)"
        ]
    }

    let dir = delta >= 0 ? 1 : -1
    let savedCursor = currentMouseLocation()
    var movedCursor = false
    defer {
        if movedCursor {
            moveMouse(to: savedCursor)
        }
    }

    // Step 1: Open the composer model/effort dropdown
    let btnLinked = openPopover(for: match.button, provider: provider, in: axApp, movedCursor: &movedCursor)
    let searchRoots = btnLinked.isEmpty ? allWindows(of: axApp) : btnLinked

    // Step 2: Check direct effort options inside the open popover (reverseChildren=true hits React portals at end of <body> first!)
    var options = collectEffortOptions(in: searchRoots, excluding: match.button.element, baseModel: match.baseModel)

    // Step 3: Nested submenu — either a dedicated "Effort ..." submenu row (Claude Desktop)
    // OR the current model's row (Antigravity's "Gemini 4 Neon Medium New", AXHasPopup=1)
    if options.count < 2 {
        let baseLow = match.baseModel.lowercased()
        var subTriggerRow: AXNodeInfo? = nil
        for root in searchRoots {
            let items = walkInteractive(root, roles: ["AXMenuItem", "AXButton", "AXRadioButton"], maxNodes: 900, maxDepth: 32, reverseChildren: true)
                .filter { $0.frame.width >= 20 && $0.frame.height >= 12 && !CFEqual($0.element, match.button.element) }
            // Prefer explicit "Effort ..." submenu trigger (Claude Desktop)
            if let effortRow = items.first(where: {
                let t = ($0.text.isEmpty ? $0.title : $0.text).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                return t.hasPrefix("effort") || t.hasPrefix("reasoning") || t.hasPrefix("thinking")
            }) {
                subTriggerRow = effortRow
                break
            }
            if !baseLow.isEmpty, let modelRow = items.first(where: {
                $0.text.lowercased().contains(baseLow) || $0.title.lowercased().contains(baseLow)
            }) {
                subTriggerRow = modelRow
                break
            }
        }
        if let row = subTriggerRow {
            var subLinked: [AXUIElement] = linkedElements(of: row.element)

            // Fast path: focus the SubTrigger row and press RightArrow (Radix opens submenus synchronously, 0ms hover delay!)
            if subLinked.isEmpty {
                AXUIElementSetAttributeValue(row.element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
                usleep(20_000)
                postKey(keyCode: CGKeyCode(kVK_RightArrow))
                subLinked = pollUntil(maxUs: 130_000, stepUs: 20_000, {
                    let l = linkedElements(of: row.element)
                    return l.isEmpty ? nil : l
                }) ?? []
            }

            // Fallback path: hover-trigger the submenu with suppression-free cursor moves
            if subLinked.isEmpty {
                movedCursor = true
                let rf = axFrame(row.element)
                let frame = (rf.width >= 20 && rf.height >= 12) ? rf : row.frame
                moveMouse(to: CGPoint(x: max(1, frame.minX - 24), y: frame.midY))
                usleep(35_000)
                moveMouse(to: CGPoint(x: frame.midX, y: frame.midY))
                usleep(45_000)
                moveMouse(to: CGPoint(x: frame.midX + 8, y: frame.midY))
                var nudged = false
                var waited: UInt32 = 0
                subLinked = pollUntil(maxUs: 460_000, stepUs: 25_000, {
                    let l = linkedElements(of: row.element)
                    if !l.isEmpty { return l }
                    waited += 25_000
                    if !nudged && waited >= 150_000 {
                        nudged = true
                        moveMouse(to: CGPoint(x: frame.midX - 8, y: frame.midY))
                        usleep(20_000)
                        moveMouse(to: CGPoint(x: frame.midX + 12, y: frame.midY))
                    }
                    return nil
                }) ?? []
            }

            if !subLinked.isEmpty {
                options = collectEffortOptions(in: subLinked, excluding: match.button.element, baseModel: match.baseModel)
            }
        }
    }

    // Step 4: Check for an AXSlider (e.g. Claude Desktop effort slider) inside the popover
    if options.count < 2 {
        for root in searchRoots {
            let sliders = walkInteractive(root, roles: ["AXSlider"], maxNodes: 800, maxDepth: 24, reverseChildren: false)
                .filter { $0.frame.width >= 20 && $0.frame.height >= 6 }
            if let slider = sliders.first {
                let action = dir > 0 ? kAXIncrementAction : kAXDecrementAction
                AXUIElementPerformAction(slider.element, action as CFString)
                usleep(120_000)
                postKey(keyCode: CGKeyCode(kVK_Escape))
                let newLvl = pollUntil(maxUs: 180_000, stepUs: 25_000, {
                    let lbl = collectLabel(match.button.element)
                    if let l = normalizeLevelLabel(lbl), l != match.currentLevel { return l }
                    return nil
                }) ?? match.currentLevel
                let levels = defaultLevelsFor(provider: provider, current: newLvl)
                let newIdx = levels.firstIndex(of: newLvl) ?? match.currentIndex
                let changed = newLvl != match.currentLevel
                return [
                    "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
                    "dialSupported": true, "confirmed": true, "changed": changed, "boundary": !changed,
                    "baseModel": match.baseModel,
                    "level": newLvl, "levels": levels, "index": newIdx
                ]
            }
        }
    }

    if options.count < 2 {
        // Log popover contents so any unmatched app UI is immediately diagnosable in bridge.log
        var dbg: [String] = []
        var menuOpen = false
        for root in searchRoots {
            let items = walkInteractive(root, roles: ["AXMenuItem", "AXRadioButton", "AXButton", "AXSlider", "AXCheckBox", "AXPopUpButton"], maxNodes: 1500, maxDepth: 28, reverseChildren: false)
                .filter { $0.frame.width >= 10 && $0.frame.height >= 10 }
            if items.contains(where: { $0.role == "AXMenuItem" }) { menuOpen = true }
            for n in items.prefix(18) {
                let t = [n.title, n.desc, n.value, n.text].first(where: { !$0.isEmpty }) ?? ""
                if !t.isEmpty { dbg.append("[\(n.role)]\(t.prefix(35))") }
            }
        }
        FileHandle.standardError.write(Data("step failed in \(name) (base='\(match.baseModel)', linked=\(btnLinked.count))\(retry ? ", retrying" : ""): \(dbg.joined(separator: " | "))\n".utf8))
        // Close the menu only if one is open. Escape sent into an app with no menu can interrupt a
        // running agent turn.
        if menuOpen || !btnLinked.isEmpty { postKey(keyCode: CGKeyCode(kVK_Escape)) }
        if retry {
            // The submenu is slow to appear now and then; a second try almost always finds it.
            usleep(200_000)
            return stepThinking(delta: delta, targetLevel: targetLevel, targetIndex: targetIndex, retry: false)
        }
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": true, "confirmed": false, "changed": false, "boundary": false,
            "baseModel": match.baseModel,
            "level": match.currentLevel, "levels": match.knownLevels, "index": match.currentIndex,
            "error": "Could not read effort options in \(name)"
        ]
    }

    let levelLabels = options.map { $0.label }
    let curIdx = levelLabels.firstIndex(of: match.currentLevel) ?? 0
    let targetIdx: Int
    if let wantedLvl = targetLevel, !wantedLvl.isEmpty, let matchedIdx = levelLabels.firstIndex(where: { $0.caseInsensitiveCompare(wantedLvl) == .orderedSame }) {
        targetIdx = matchedIdx
    } else if let wantedIdx = targetIndex, wantedIdx >= 0 {
        targetIdx = wantedIdx.clamped(to: 0...(options.count - 1))
    } else {
        targetIdx = (curIdx + dir).clamped(to: 0...(options.count - 1))
    }

    if targetIdx == curIdx {
        postKey(keyCode: CGKeyCode(kVK_Escape))
        return [
            "ok": true, "appName": name, "bundleId": bundleId, "provider": provider,
            "dialSupported": true, "confirmed": true, "changed": false, "boundary": true,
            "baseModel": match.baseModel,
            "level": levelLabels[curIdx], "levels": levelLabels, "index": curIdx
        ]
    }

    pressOrClick(options[targetIdx].node)

    // Fast 0.5ms confirmation: poll the composer button's own label directly instead of re-walking the whole window!
    let expectedLevel = levelLabels[targetIdx]
    let finalLevel = pollUntil(maxUs: 220_000, stepUs: 25_000, {
        let lbl = collectLabel(match.button.element)
        if let l = normalizeLevelLabel(lbl), l == expectedLevel || l != match.currentLevel {
            return l
        }
        return nil
    }) ?? expectedLevel
    let finalIdx = levelLabels.firstIndex(of: finalLevel) ?? targetIdx
    let confirmed = (finalLevel == expectedLevel) || (finalLevel != match.currentLevel)

    return [
        "ok": true,
        "appName": name,
        "bundleId": bundleId,
        "provider": provider,
        "dialSupported": true,
        "confirmed": confirmed,
        "changed": finalLevel != match.currentLevel,
        "boundary": false,
        "baseModel": match.baseModel,
        "level": finalLevel,
        "levels": levelLabels,
        "index": finalIdx
    ]
}

// ---- Agent Threads (wrist app): answer a question / reply in a specific conversation ----------

func runningApp(named wanted: String) -> NSRunningApplication? {
    let apps = NSWorkspace.shared.runningApplications
    return apps.first { ($0.localizedName ?? "") == wanted }
        ?? apps.first { ($0.localizedName ?? "").lowercased().hasPrefix(wanted.lowercased()) }
}

func normLabel(_ s: String) -> String {
    s.lowercased().split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
}

/// Presses the enabled button labelled [label] in [appName] (an answer to an open question). When
/// several match (an old answered question can still show the same choice), the lowest one on screen,
/// the newest, wins. Nothing is pressed if no button carries that label.
func pressChoice(appName: String, label: String, windowTitle: String = "") -> [String: Any] {
    guard AXIsProcessTrusted() else { return ["ok": false, "error": "Mac needs Accessibility permission for Agent HUD"] }
    guard let app = runningApp(named: appName) else { return ["ok": false, "error": "\(appName) isn't open on the Mac"] }
    let axApp = AXUIElementCreateApplication(app.processIdentifier)
    enableManualAccessibility(axApp)
    let want = normLabel(label)
    guard !want.isEmpty else { return ["ok": false, "error": "No choice given"] }
    // With a title, only the window showing that conversation counts: the same label ("Yes") in another
    // conversation must never be pressed.
    var windows = allWindows(of: axApp)
    if !windowTitle.isEmpty {
        windows = windows.filter { axString($0, kAXTitleAttribute) == windowTitle }
        if windows.isEmpty { return ["ok": false, "notShown": true, "error": "That conversation isn't open in \(appName)"] }
    }
    for win in windows {
        let nodes = walkInteractive(win, roles: ["AXButton", "AXRadioButton", "AXCheckBox"], maxNodes: 9000, maxDepth: 44)
            .filter { axBool($0.element, kAXEnabledAttribute) != false }
        func labels(_ n: AXNodeInfo) -> [String] { [n.title, n.desc, n.text].map(normLabel) }
        let exact = nodes.filter { labels($0).contains(want) }
        let prefixed = nodes.filter { labels($0).contains { $0.hasPrefix(want + " ") } }
        if let n = (exact.isEmpty ? prefixed : exact).max(by: { $0.frame.minY < $1.frame.minY }) {
            // AXPress only. Falling back to a mouse click could land on whatever app is in front.
            let err = AXUIElementPerformAction(n.element, kAXPressAction as CFString)
            if err != .success { return ["ok": false, "error": "\(appName) didn't accept the press"] }
            return ["ok": true, "pressed": String(n.text.prefix(80))]
        }
    }
    return ["ok": false, "notShown": true, "error": "That question isn't on screen in \(appName)"]
}

/// Types [text] into the message box of the conversation [appName]'s front window shows, and sends
/// it, only if the window title is exactly [windowTitle] and the box is empty (never sends a draft
/// the person left there). The app that was in front gets focus back afterward.
/// Text compared loosely: editors turn line breaks and spaces into their own variants.
func normText(_ s: String) -> String {
    s.replacingOccurrences(of: "\u{00A0}", with: " ")
        .components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
}

/// [anyBox]: the app has one window for every chat (no title to check), so use its chat box: the
/// lowest wide text box in the bottom part of the window. [dryRun]: find the box, report, change nothing.
func composeIn(appName: String, windowTitle: String, text: String, anyBox: Bool = false, dryRun: Bool = false) -> [String: Any] {
    guard AXIsProcessTrusted() else { return ["ok": false, "error": "Mac needs Accessibility permission for Agent HUD"] }
    guard let app = runningApp(named: appName) else { return ["ok": false, "notShown": true, "error": "\(appName) isn't open"] }
    let axApp = AXUIElementCreateApplication(app.processIdentifier)
    enableManualAccessibility(axApp)
    // With a title: the window showing that conversation, wherever it is in the window stack (a panel
    // in front shouldn't force the fallback). Without one: the front window.
    let candidate: AXUIElement? = anyBox
        ? frontmostWindow(of: axApp)
        : allWindows(of: axApp).first { axString($0, kAXTitleAttribute) == windowTitle }
    guard let win = candidate else {
        let front = frontmostWindow(of: axApp).map { axString($0, kAXTitleAttribute) } ?? ""
        return ["ok": false, "notShown": true, "error": anyBox ? "\(appName) has no window" : "That conversation isn't open in \(appName)", "windowTitle": front]
    }
    let title = axString(win, kAXTitleAttribute)
    let all = walkInteractive(win, roles: ["AXTextArea", "AXTextField"], maxNodes: 9000, maxDepth: 44)
    let winFrame = axFrame(win)
    let boxes: [AXNodeInfo]
    if anyBox {
        let lowerHalf = winFrame.minY + winFrame.height * 0.45
        boxes = all.filter { $0.frame.width >= 160 && $0.frame.height >= 16 && $0.frame.minY >= lowerHalf }
    } else {
        boxes = all.filter { $0.desc.hasPrefix("Message") }
    }
    guard let box = boxes.max(by: { $0.frame.minY < $1.frame.minY }) else {
        return ["ok": false, "notShown": true, "error": "No message box", "windowTitle": title]
    }
    let existing = axString(box.element, kAXValueAttribute)
    if dryRun {
        return ["ok": true, "dryRun": true, "windowTitle": title, "boxDesc": box.desc, "boxRole": box.role,
                "boxEmpty": normText(existing).isEmpty, "frame": ["x": Int(box.frame.minX), "y": Int(box.frame.minY), "w": Int(box.frame.width), "h": Int(box.frame.height)]]
    }
    if !normText(existing).isEmpty {
        return ["ok": false, "notShown": true, "draft": true, "error": "A draft is in the message box"]
    }
    let previous = NSWorkspace.shared.frontmostApplication
    if previous?.processIdentifier != app.processIdentifier {
        app.activate(options: [])
        _ = pollUntil(maxUs: 1_200_000) { NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier ? true : nil }
    }
    guard NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier else {
        return ["ok": false, "notShown": true, "error": "\(appName) didn't come to the front"]
    }
    // Bring that window forward, then make sure it really is the one in front before typing.
    AXUIElementPerformAction(win, kAXRaiseAction as CFString)
    AXUIElementSetAttributeValue(win, kAXMainAttribute as CFString, kCFBooleanTrue)
    usleep(120_000)
    if !anyBox, let front = frontmostWindow(of: axApp), axString(front, kAXTitleAttribute) != windowTitle {
        if let prev = previous, prev.processIdentifier != app.processIdentifier { prev.activate(options: []) }
        return ["ok": false, "notShown": true, "error": "Couldn't bring that conversation forward"]
    }
    AXUIElementSetAttributeValue(box.element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
    usleep(150_000)

    let pb = NSPasteboard.general
    var saved: [NSPasteboardItem] = []
    for it in pb.pasteboardItems ?? [] {
        let copy = NSPasteboardItem()
        for t in it.types { if let d = it.data(forType: t) { copy.setData(d, forType: t) } }
        saved.append(copy)
    }
    func restore() {
        pb.clearContents()
        if !saved.isEmpty { pb.writeObjects(saved) }
        if let prev = previous, prev.processIdentifier != app.processIdentifier { prev.activate(options: []) }
    }
    pb.clearContents()
    pb.setString(text, forType: .string)
    usleep(70_000)
    postKey(keyCode: CGKeyCode(kVK_ANSI_V), flags: .maskCommand)
    let want = normText(text)
    let landed = pollUntil(maxUs: 900_000, stepUs: 50_000) { () -> Bool? in
        normText(axString(box.element, kAXValueAttribute)) == want ? true : nil
    } ?? false
    if !landed {
        // It went somewhere else, or only partly: take back what we pasted (only from this box, and
        // only while it has focus) and let the caller fall back.
        var focusedRef: CFTypeRef?
        let focusedIsBox = AXUIElementCopyAttributeValue(axApp, kAXFocusedUIElementAttribute as CFString, &focusedRef) == .success
            && focusedRef != nil && CFEqual(focusedRef!, box.element)
        if focusedIsBox && normText(axString(box.element, kAXValueAttribute)).hasPrefix(String(want.prefix(12))) {
            postKey(keyCode: CGKeyCode(kVK_ANSI_A), flags: .maskCommand)
            postKey(keyCode: CGKeyCode(kVK_Delete), flags: [])
        }
        restore()
        return ["ok": false, "notShown": true, "error": "The text didn't land in the message box"]
    }
    usleep(200_000)
    postKey(keyCode: CGKeyCode(kVK_Return), flags: [])
    usleep(350_000)
    restore()
    return ["ok": true, "typed": true]
}

func debugAX(targetAppName: String? = nil) -> [String: Any] {
    let trusted = AXIsProcessTrusted()
    let target: NSRunningApplication?
    if let wanted = targetAppName, !wanted.isEmpty {
        target = NSWorkspace.shared.runningApplications.first { ($0.localizedName ?? "").lowercased().contains(wanted.lowercased()) }
    } else {
        target = NSWorkspace.shared.frontmostApplication
    }
    guard let app = target else {
        return ["ok": false, "trusted": trusted, "error": "Target app not found"]
    }
    let axApp = AXUIElementCreateApplication(app.processIdentifier)
    enableManualAccessibility(axApp)
    usleep(180_000)
    guard let win = frontmostWindow(of: axApp) else {
        return ["ok": false, "trusted": trusted, "appName": app.localizedName ?? "", "error": "No window accessible"]
    }
    let winFrame = axFrame(win)
    let roles: Set<String> = ["AXButton", "AXPopUpButton", "AXMenuButton", "AXCheckBox", "AXComboBox", "AXRadioButton", "AXTextArea", "AXTextField"]
    let nodes = walkInteractive(win, roles: roles, maxNodes: 4000, maxDepth: 38, reverseChildren: true)
        .filter { $0.frame.width >= 10 && $0.frame.height >= 10 }
        .sorted { $0.frame.minY > $1.frame.minY }

    let items: [[String: Any]] = nodes.prefix(45).map { n in
        [
            "role": n.role,
            "subrole": n.subrole,
            "title": String(n.title.prefix(60)),
            "desc": String(n.desc.prefix(60)),
            "value": String(n.value.prefix(40)),
            "text": String(n.text.prefix(80)),
            "x": Int(n.frame.minX),
            "y": Int(n.frame.minY),
            "w": Int(n.frame.width),
            "h": Int(n.frame.height),
            "actions": n.actions
        ]
    }
    return [
        "ok": true,
        "trusted": trusted,
        "appName": app.localizedName ?? "",
        "bundleId": app.bundleIdentifier ?? "",
        "provider": providerForApp(bundleId: app.bundleIdentifier ?? "", name: app.localizedName ?? "", pid: app.processIdentifier),
        "windowFrame": ["x": Int(winFrame.minX), "y": Int(winFrame.minY), "w": Int(winFrame.width), "h": Int(winFrame.height)],
        "totalFound": nodes.count,
        "elements": items
    ]
}

func debugMenu(targetAppName: String? = nil) -> [String: Any] {
    guard AXIsProcessTrusted() else { return ["ok": false, "error": "Not trusted"] }
    let target: NSRunningApplication?
    if let wanted = targetAppName, !wanted.isEmpty {
        target = NSWorkspace.shared.runningApplications.first { ($0.localizedName ?? "").lowercased().contains(wanted.lowercased()) }
    } else {
        target = NSWorkspace.shared.frontmostApplication
    }
    guard let app = target else { return ["ok": false, "error": "App not found"] }
    let name = app.localizedName ?? ""
    let bundleId = app.bundleIdentifier ?? ""
    let provider = providerForApp(bundleId: bundleId, name: name, pid: app.processIdentifier)
    let axApp = AXUIElementCreateApplication(app.processIdentifier)
    enableManualAccessibility(axApp)
    guard let win = frontmostWindow(of: axApp),
          let match = findThinkingButton(in: win, provider: provider) else {
        return ["ok": false, "appName": name, "error": "No thinking/model button found"]
    }

    pressOrClick(match.button)
    usleep(300_000)

    var rowAttrs: [String: String] = [:]
    if !match.baseModel.isEmpty {
        let baseLow = match.baseModel.lowercased()
        for w in allWindows(of: axApp) {
            let rows = walkInteractive(w, roles: ["AXMenuItem"], maxNodes: 3000, maxDepth: 36, reverseChildren: true)
                .filter { $0.frame.width >= 20 && $0.frame.height >= 12 }
            if let row = rows.first(where: { $0.text.lowercased().contains(baseLow) || $0.title.lowercased().contains(baseLow) }) {
                var attrNamesRef: CFArray?
                if AXUIElementCopyAttributeNames(row.element, &attrNamesRef) == .success, let names = attrNamesRef as? [String] {
                    rowAttrs["names"] = names.joined(separator: ",")
                }
                rowAttrs["hasPopup"] = axString(row.element, "AXHasPopup")
                rowAttrs["expanded"] = axString(row.element, "AXExpanded")
                let savedCursor = currentMouseLocation()
                // Real cursor warp + pointer move triggers Chromium/Radix SubTrigger hover!
                moveMouse(to: CGPoint(x: max(1, row.frame.minX - 24), y: row.frame.midY))
                usleep(60_000)
                moveMouse(to: CGPoint(x: row.frame.midX, y: row.frame.midY))
                usleep(80_000)
                moveMouse(to: CGPoint(x: row.frame.midX + 6, y: row.frame.midY))
                usleep(320_000)
                rowAttrs["expandedAfterHover"] = axString(row.element, "AXExpanded")
                // Inspect AXLinkedUIElements (aria-controls -> submenu!) BEFORE moving mouse away!
                var linkedRef: CFTypeRef?
                if AXUIElementCopyAttributeValue(row.element, "AXLinkedUIElements" as CFString, &linkedRef) == .success,
                   let arr = linkedRef as? NSArray {
                    var linkedDesc: [String] = []
                    for it in arr {
                        let cf = it as CFTypeRef
                        if CFGetTypeID(cf) == AXUIElementGetTypeID() {
                            let lel = cf as! AXUIElement
                            let sub = walkInteractive(lel, roles: ["AXMenu", "AXMenuItem", "AXRadioButton", "AXButton", "AXCheckBox", "AXGroup", "AXStaticText"], maxNodes: 200, maxDepth: 12, reverseChildren: false)
                            linkedDesc.append("linked[\(axString(lel, kAXRoleAttribute))](\(sub.count)): " + sub.prefix(15).map { "[\($0.role)]\($0.text.prefix(25))" }.joined(separator: ","))
                        }
                    }
                    rowAttrs["linked"] = linkedDesc.joined(separator: " ; ")
                }
                // Also inspect ALL roles in the right-hand region (x >= 870) BEFORE moving mouse away!
                let rightNodes = walkInteractive(w, roles: ["AXMenu", "AXMenuItem", "AXRadioButton", "AXButton", "AXCheckBox", "AXStaticText", "AXGroup"], maxNodes: 4000, maxDepth: 38, reverseChildren: true)
                    .filter { $0.frame.minX >= 865 && $0.frame.minX <= 1200 && $0.frame.minY >= 680 && $0.frame.minY <= 1040 && $0.frame.width >= 10 && $0.frame.height >= 10 }
                rowAttrs["rightRegion"] = rightNodes.prefix(20).map { "x=\(Int($0.frame.minX)),y=\(Int($0.frame.minY))[\($0.role)]\($0.text.prefix(25))" }.joined(separator: " | ")
                moveMouse(to: savedCursor)
                break
            }
        }
    }

    let menuRoles: Set<String> = ["AXMenuItem", "AXRadioButton", "AXButton", "AXCheckBox", "AXPopUpButton", "AXSlider", "AXMenu"]
    var items: [[String: Any]] = []
    let wins = allWindows(of: axApp)
    for (wi, w) in wins.enumerated() {
        let nodes = walkInteractive(w, roles: menuRoles, maxNodes: 4000, maxDepth: 38, reverseChildren: true)
            .filter { $0.frame.width >= 10 && $0.frame.height >= 10 && $0.frame.minY >= 500 }
        for n in nodes {
            let txt = [n.title, n.desc, n.value, n.text].first(where: { !$0.isEmpty }) ?? ""
            if n.role == "AXMenu" || n.role == "AXMenuItem" || n.role == "AXRadioButton" || n.role == "AXSlider" {
                items.append([
                    "win": wi, "role": n.role, "text": String(txt.prefix(75)),
                    "val": String(n.value.prefix(30)), "x": Int(n.frame.minX), "y": Int(n.frame.minY),
                    "w": Int(n.frame.width), "h": Int(n.frame.height)
                ])
            }
        }
    }
    postKey(keyCode: CGKeyCode(kVK_Escape))
    usleep(80_000)
    postKey(keyCode: CGKeyCode(kVK_Escape))
    return ["ok": true, "appName": name, "baseModel": match.baseModel, "level": match.currentLevel, "rowAttrs": rowAttrs, "items": Array(items.prefix(60))]
}

extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        return Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - JSON I/O

let writeLock = NSLock()
func emitJSON(_ dict: [String: Any]) {
    writeLock.lock()
    defer { writeLock.unlock() }
    guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []),
          var line = String(data: data, encoding: .utf8) else { return }
    line.append("\n")
    FileHandle.standardOutput.write(Data(line.utf8))
    fflush(stdout)
}

// MARK: - Daemon & CLI Entrypoint

let args = CommandLine.arguments
let mode = args.count > 1 ? args[1] : "inspect"

if mode == "watch" {
    let app = NSApplication.shared
    app.setActivationPolicy(.prohibited)

    NSWorkspace.shared.notificationCenter.addObserver(
        forName: NSWorkspace.didActivateApplicationNotification,
        object: nil,
        queue: .main
    ) { _ in
        DispatchQueue.global(qos: .userInteractive).async {
            usleep(120_000)
            var state = inspectFrontmost()
            state["event"] = "focus"
            emitJSON(state)
        }
    }

    DispatchQueue.global(qos: .userInteractive).async {
        while let line = readLine() {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            guard let data = trimmed.data(using: .utf8),
                  let req = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            let cmd = req["cmd"] as? String ?? ""
            let reqId = req["reqId"]
            var resp: [String: Any]
            // The bridge gives up on a command after its timeout. If one waited in line past that,
            // drop it instead of acting late: the caller was told it failed and may have retried,
            // so running it now would double-send or double-step.
            if cmd == "paste_send" || cmd == "step" || cmd == "press_choice" || cmd == "compose_in",
               let deadline = (req["deadlineMs"] as? NSNumber)?.doubleValue,
               Date().timeIntervalSince1970 * 1000 > deadline {
                resp = ["ok": false, "stale": true, "error": "Request expired before it ran"]
                if let id = reqId { resp["reqId"] = id }
                emitJSON(resp)
                continue
            }
            switch cmd {
            case "inspect":
                resp = inspectFrontmost()
            case "step":
                let delta = req["delta"] as? Int ?? 1
                let targetLevel = req["targetLevel"] as? String
                let targetIndex = req["targetIndex"] as? Int
                resp = stepThinking(delta: delta, targetLevel: targetLevel, targetIndex: targetIndex)
            case "paste_send":
                let text = req["text"] as? String ?? ""
                let pressReturn = req["pressReturn"] as? Bool ?? true
                let expect = req["expectBundleId"] as? String
                resp = pasteAndSend(text: text, pressReturn: pressReturn, expectBundleId: expect)
            case "check_target":
                let pid = (req["pid"] as? NSNumber)?.int32Value ?? -1
                resp = checkTarget(bundleId: req["bundleId"] as? String ?? "", name: req["name"] as? String ?? "", expect: req["expect"] as? String, pid: pid)
            case "press_choice":
                resp = pressChoice(appName: req["app"] as? String ?? "", label: req["label"] as? String ?? "", windowTitle: req["windowTitle"] as? String ?? "")
            case "compose_in":
                resp = composeIn(appName: req["app"] as? String ?? "", windowTitle: req["windowTitle"] as? String ?? "", text: req["text"] as? String ?? "",
                                 anyBox: req["anyBox"] as? Bool ?? false, dryRun: req["dryRun"] as? Bool ?? false)
            case "debug_ax":
                let targetApp = req["app"] as? String
                resp = debugAX(targetAppName: targetApp)
            case "debug_menu":
                let targetApp = req["app"] as? String
                resp = debugMenu(targetAppName: targetApp)
            default:
                resp = ["ok": false, "error": "Unknown command: \(cmd)"]
            }
            if let id = reqId { resp["reqId"] = id }
            emitJSON(resp)
        }
        exit(0)
    }

    DispatchQueue.global(qos: .utility).async {
        var state = inspectFrontmost()
        state["event"] = "ready"
        emitJSON(state)
    }

    RunLoop.main.run()
} else if mode == "inspect" {
    emitJSON(inspectFrontmost())
} else if mode == "step" {
    let delta = args.count > 2 ? (Int(args[2]) ?? 1) : 1
    emitJSON(stepThinking(delta: delta))
} else if mode == "debug-ax" {
    let target = args.count > 2 ? args[2] : nil
    emitJSON(debugAX(targetAppName: target))
} else if mode == "paste-send" {
    let text = args.count > 2 ? args[2] : ""
    emitJSON(pasteAndSend(text: text))
}
