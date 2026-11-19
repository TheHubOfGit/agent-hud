# Pixel Watch 3 (`selene`) AI Agent HUD — Project Handoff & Status

*Last updated: 2026-11-18*  
*Workspace:* `~/Documents/Projects/Pixel Watch Mod`

---

## 1. Project Goal
Turn a **Google Pixel Watch 3 (41mm)** into a dedicated, **phone-free** (severed from the Pixel Watch phone app / Bluetooth permanently off), always-on wall-docked desk companion—with smart off-dock carry support on Wi-Fi—that displays live status and two-way prompts for local Mac AI agents and **CodexBar** subscription quotas.

### Verified Target Hardware
- **Device:** Google Pixel Watch 3 41mm (`selene`, Snapdragon W5 / `monaco`)
- **ADB Serial:** `<watch-serial>` *(Note: user also has a Pixel 10 Pro on wireless ADB, so ALWAYS target the watch explicitly with `adb -s <watch-serial>`.)*
- **Wi-Fi ADB:** paired with the Mac (Wireless debugging, TLS). Over Wi-Fi the serial is `adb-<watch-serial>-<id>._adb-tls-connect._tcp` and the adb server auto-connects via mDNS. Android turns Wireless debugging off on every reboot; the HUD app turns it back on at boot and every 60s (`service/AdbWifiKeeper.kt`), which needs `pm grant com.agenthud.watch android.permission.WRITE_SECURE_SETTINGS` (re-grant after an uninstall). your Wi-Fi network is set to "always allow" for debugging; a new network needs that prompt accepted once. `scripts/wadb` picks USB or Wi-Fi automatically.
- **OS / Build:** Android 16 / Wear OS 6 (`google/selene/selene:16/CP1A.260305.014.W4/14949561:user/release-keys`, SDK 36)
- **Display:** `408×408` circular OLED (`320 dpi` = `204×204 dp`), realistic ~4.5mm curved black bezel
- **Bootloader:** Currently **LOCKED** (`verifiedbootstate: green`) — we are live-testing the app before unlocking/wiping.
- **Rooting Image Note:** Pixel Watch 3 uses **`boot.img`** (not `init_boot.img`) for Magisk patching.

---

## 2. Locked-In UI & UX Requirements
- **Typography:** Native Wear OS **Google Sans**; low-density, airy, readable from across a desk.
- **STRICT RULE — ZERO EMOJIS:** User hates emojis. Use only clean, minimal custom Compose vector/Canvas icons (`HudIcons.kt`).
- **STRICT RULE — ZERO RING OVERLAP:** Circular safe padding (`28–30dp` horizontal, `22–26dp` vertical); top and bottom controls must stay narrow/stacked inside the circular chord so nothing touches the perimeter ring.
- **Three Choosable Home Layouts** (switchable in Swipe-Down Quick Controls, persisted in `SharedPreferences`):
  - **Pulse (A) — Default:** Material Expressive big numeral, perimeter arc, tactile status pill, clickable agent dots.
  - **Orbit (B):** Orbital Constellation — luminous glowing agent beads orbiting perimeter, clean centered clock/headline.
  - **Zen (C):** Typographic Zen — oversized Google Sans headline + ambient radial edge glow for 5-foot legibility.
- **Navigation Map:**
  - **Swipe Left (from Home):** Live **CodexBar AI Quotas** (`Claude`, `Codex`, `Antigravity`, etc.) with tap-through 5h vs Weekly breakdown.
  - **Center:** Home Overview (A/B/C).
  - **Swipe Right / Rotary Crown:** Single-Agent Focus (1 thread per screen, showing real thread titles + `Desktop` / `CLI` tag).
  - **Swipe Down:** Quick Controls Drawer (Layout A/B/C picker, Mute 30m, Night OLED mode, Brightness cycle, Sync Mac, battery/temp readout).
- **Strict FIFO Approval Interrupt Engine:**
  - The instant an agent requests approval or asks a continuing question, the watch wakes (even off-dock), jumps directly to that agent's screen, flashes the circular OLED perimeter **amber** (**red** on error), and vibrates (`2× crisp pulses`).
  - If a 2nd/3rd approval arrives while the 1st is unresolved, **stay locked on the 1st agent's screen**, show a `+N more waiting` pill, and give a tiny micro-tick (`40ms`).
  - When the 1st resolves, auto-advance to the 2nd; when empty, soft confirmation and return Home.
- **Two-Way Actions & Built-In Mic Voice Replies (Phone-Free):**
  - Tap **`Proceed`**, **`Deny`**, or **`Speak` / mic icon** directly on the watch.
  - Captures raw 16kHz 16-bit mono WAV from Pixel Watch 3 mic (`AudioRecord` — zero Google Assistant dependency), POSTs to Mac (`POST /voice-transcribe`), transcribes locally on Apple Silicon Metal GPU via **`whisper-cpp`** (`ggml-base.en.bin`), shows transcript review (`Send` / `Cancel`), and dispatches back to the thread.
- **Smart On-Dock vs. Off-Dock Power Mode:**
  - **On Dock:** `FLAG_KEEP_SCREEN_ON` 24/7 + periodic OLED pixel-shift + Magisk 65% battery charge cap daemon (60–65% hysteresis).
  - **Off Dock (carried to another room on Wi-Fi):** Releases `FLAG_KEEP_SCREEN_ON` when idle to preserve battery while keeping persistent WebSocket live; instantly wakes + flashes edges + vibrates on incoming approval/question.

---

## 3. What Is Built & Verified

### A. `mac-agent-bridge/` (Node.js v25, `ws`, native `node:sqlite`)
- **Entrypoint:** `mac-agent-bridge/src/server.mjs`
  - HTTP + WebSocket on `:8765`, Bonjour mDNS `_agenthud._tcp.local.` via `dns-sd`, shared auth token auto-generated at `~/.agenthud-bridge-token` (baked into `AgentHUD.apk` at build time), 5s Ping/Pong heartbeat, 120ms coalesced broadcasts, strict canonical `approvalQueue`.
- **Verified Unified Desktop + Terminal Collectors (Desktop prioritized, deduplicated):**
  1. `src/collectors/antigravity.mjs` — FSEvents + byte-offset tailer on `~/.gemini/antigravity/brain/*/.system_generated/logs/transcript.jsonl` (strips `<ADDITIONAL_METADATA>` timestamps).
  2. `src/collectors/codex.mjs` — Read-only SQLite on `~/.codex/state_5.sqlite` + `thread_history_1.sqlite` + `~/.codex/thread-writer-locks/` (Desktop vs CLI).
  3. `src/collectors/claude.mjs` — Watches `~/Library/Application Support/Claude/claude-code-sessions/`, `local-agent-mode-sessions/`, `~/.claude/projects/*/*.jsonl`, plus synchronous hook endpoint (`POST /hook/claude?sync=1`) paired with `hooks/claude-hook.mjs`.
  4. `src/collectors/t3code.mjs` — Read-only SQLite on `~/.t3/userdata/statev2.sqlite` (`projection_threads`, `orchestration_v2_projection_runs`, `orchestration_v2_projection_runtime_requests`, `orchestration_v2_projection_plans`) + cross-provider session deduplication via `orchestration_v2_projection_provider_sessions`.
  5. `src/collectors/codexbar.mjs` — Calls `/Applications/CodexBar.app/Contents/Helpers/CodexBarCLI usage --json` + watches `~/Library/Application Support/CodexBar/usage-history.jsonl`. Verified returning live **Claude**, **Codex**, and **Antigravity** 5h & weekly quotas.
- **Voice STT (`src/stt.mjs`):** Uses `/opt/homebrew/bin/whisper-cli` + `mac-agent-bridge/models/ggml-base.en.bin` (141 MB, Metal GPU). **Verified end-to-end from Pixel Watch 3 mic** (`"Test. This is a test."`).
- **Reply Dispatch (`src/dispatch.mjs`):**
  - Claude Code `PreToolUse` hook synchronous Allow/Deny
  - Codex native SQLite queue via `~/.gemini/config/skills/codex-monitor/scripts/codex_monitor.py queue`
  - macOS Accessibility / AppleScript fallback for Antigravity, Claude Desktop, and T3 Code GUI windows
- **Auto-start installer:** `mac-agent-bridge/install-launchd.sh` (not yet installed into `~/Library/LaunchAgents` — currently run ad-hoc during dev).

### B. `watch-hud/` (Native Wear OS 6 Kotlin + Jetpack Compose)
- **Built Release APK:** `watch-hud/app/build/outputs/apk/release/app-release.apk` (compiled cleanly with JDK 21 + Gradle 8.13, zero warnings).
- **Installed & Visually Verified on Pixel Watch 3:**
  - `MainActivity.kt` — Fullscreen launcher (`CATEGORY_HOME`), supports `--es host <ip>` for USB `127.0.0.1` testing or manual LAN IP, smart dock/off-dock wake handling.
  - `data/BridgeClient.kt` — `NsdManager` mDNS discovery (`_agenthud._tcp.`), cached IP reconnect, 5s ping/pong WebSocket, Mac clock sync, `/action` & `/voice-transcribe` HTTP calls.
  - `service/HudForegroundService.kt` — Holds `WIFI_MODE_FULL_LOW_LATENCY` + `PARTIAL_WAKE_LOCK` + `BootReceiver`.
  - `service/HapticsEngine.kt` — Custom `VibrationEffect.createWaveform` patterns (approval 2×, queued micro-tick, error 3×, soft confirm).
  - `audio/VoiceRecorder.kt` — Direct 16kHz PCM/WAV mic capture.
  - `ui/HudIcons.kt` — Custom vector Canvas icons (zero emojis).
  - `ui/HomeLayouts.kt`, `ui/QuotasScreen.kt`, `ui/AgentFocusScreen.kt`, `ui/VoiceScreens.kt`, `ui/SystemScreens.kt`, `ui/HudRoot.kt`.

### D. `mac-app/` — Agent HUD.app (bridge manager, menu bar + dashboard)
- SwiftPM app, ad-hoc signed; build with `mac-app/build.sh`, install by copying `mac-app/build/Agent HUD.app` to `/Applications`. Icon generated by `mac-app/scripts/make_icon.py`.
- Runs the bridge as its own child process (auto-restart with backoff, restarts a hung bridge after ~60s without /health, auto-replaces another bridge holding the port, stops it on quit). Logs: `~/Library/Logs/AgentHUD/bridge.log` (rotated).
- **It runs a copy of the bridge bundled inside the app** (`Contents/Resources/bridge`), not the repo copy: running from `~/Documents` stalls the child at macOS's Documents-folder privacy check after every rebuild. **After editing `mac-agent-bridge/`, rebuild + reinstall the app** (or point the dashboard's bridge-folder setting at the repo and accept the prompt). The Whisper model lives in `~/Library/Application Support/AgentHUD/models/`.
- Launch at Login is on (SMAppService). Typing watch replies into apps needs **Accessibility (and Automation for System Events) granted to Agent HUD**; ad-hoc signing means macOS may ask again after each rebuild.
- Bridge endpoints: `/health` (loopback, no token), `/status`; requests carrying a browser `Origin` header are rejected.

### C. `firmware-mod/` (Flashable Magisk Systemless Firmware Package)
- **Built Module:** `firmware-mod/out/pixel-watch-agent-hud.zip` (6.8 MB) — contains `system/priv-app/AgentHUD/AgentHUD.apk`, Mac's `adbkey.pub`, `post-fs-data.sh`, and `service.sh`.
- **Verified `selene` Settings & Packages Hardcoded in `module/service.sh`:**
  - Disables phone companion/bloat/OTA: `com.google.android.wearable.setupwizard`, `com.google.wear.services`, `com.google.android.wearable.app`, `com.google.android.wearable.sysui.overlay.experiences.charging.pixelwatch`, `com.google.android.wearable.media.sessions`, `com.google.android.wearable.assistant`, `com.google.android.factoryota`, `com.fitbit.FitbitMobile`, `com.fitbit.ecg`, `com.google.android.wearable.healthservices`, `com.google.android.wearable.fitbit.mcu.data`, `com.google.android.wearable.fitbit.mcu.updater`.
  - Kills hidden Wear OS Wi-Fi/sleep timers discovered on this watch: `wifi_power_save=0` (stock was `360000` = 6 min!), `auto_wifi=0`, `wifi_always_requested=1`, `wifi_sleep_policy=2`, `stay_on_while_plugged_in=7`, `ambient_plugged_timeout_min=0` (stock was `1` min!), `auto_bedtime_mode=0`, `bluetooth_on=0`, `dumpsys deviceidle disable`.
  - Runs 65% battery charge-cap loop probing `/sys/class/power_supply/` sysfs nodes + foreground watchdog.
- **Scripts:** `scripts/build-module.sh`, `scripts/01-preflight-and-backup.sh`, `scripts/02-unlock-and-flash.sh`, `scripts/99-recover-stock.sh`.

---

## 4. Critical Gotchas Already Solved (Don't Repeat!)
1. **Gradle JDK Version:** Default Mac `java` is OpenJDK 27 (breaks Gradle/AGP). Always export `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"`. (`build-module.sh` already sets this.)
2. **`CodexBarCLI usage --json` Exit Code 1:** Exits `1` if *any* configured provider (e.g., `cursor`, `museai`) isn't signed in, while still printing valid JSON for working providers to stdout. `codexbar.mjs` intentionally parses stdout whenever it starts with `[` regardless of exit code.
3. **macOS Bare Swift `SFSpeechRecognizer` Crash:** Bare CLI binaries crash with `SIGABRT` (`EXIT=134`) under macOS TCC without an app bundle `Info.plist`. Replaced with local `whisper-cpp` (`/opt/homebrew/bin/whisper-cli`) + `ggml-base.en.bin` — works headless/locked with zero permission prompts.
4. **Watch ADB Unauthorized Prompt:** Wear OS hides the RSA Allow prompt while screen-locked or on the charging screen. Wake/unlock the watch, or toggle ADB debugging off/on.
5. **Never Relock Bootloader While Patched:** Only ever run `fastboot flashing lock` after restoring 100% stock firmware (`99-recover-stock.sh` / `flash.android.com`).

---

## 5. Pending Decisions & Next Steps (Pick Up Here!)

### Phase A — Finish UI/UX Polish (In Progress)
1. **Outer Ring Semantics (Open Question):** User just asked what the outer ring fill % represents. Pending user's preference:
   - *Agent Focus (`RUNNING`):* Change static 67% arc into a calm slowly orbiting indeterminate activity arc (or map to a real metric).
   - *Quotas Overview:* Map outer ring to **lowest remaining quota** across providers, or remove ring from the 3-bar list view (keep only on single-provider detail).
   - *Home (Pulse A):* Keep mapped to active agent count, or switch to lowest AI quota / watch battery %.
2. **Additional Collector Polish (Optional):**
   - Clean up idle Claude Desktop session deduplication (filter out non-chat UUID JSON files in `claude-code-sessions/` if multiple show as `"Claude Desktop"`).
   - Wire optional Claude Code hooks (`mac-agent-bridge/hooks/claude-hook.mjs`) into `~/.claude/settings.json` for ~5ms synchronous `PreToolUse` Allow/Deny from the watch.
   - Verify two-way reply dispatch live against real Codex, Claude Desktop, T3 Code, and Antigravity sessions (grant macOS Accessibility permission to terminal/Node if using GUI window injection).
   - Test **live Wi-Fi connection** (remove USB `adb reverse`, connect watch to home Wi-Fi, verify Bonjour `_agenthud._tcp` auto-discovery & off-dock carry behavior).

### Phase B — Bootloader Unlock, Root & Standalone Firmware Flash (Not Yet Started)
1. Confirm **Settings → System → Developer options → OEM unlocking** is toggled **ON** on the watch.
2. Download official `selene` factory image (`CP1A.260305.014.W4`) from https://developers.google.com/android/images-watch into `firmware-mod/stock/`.
3. Run `./firmware-mod/scripts/01-preflight-and-backup.sh` to verify battery ≥45% and archive untouched `firmware-mod/stock/stock_boot.img`.
4. Patch `firmware-mod/stock/stock_boot.img` with Magisk → save to `firmware-mod/out/magisk_patched_boot.img`.
5. Run `./firmware-mod/scripts/02-unlock-and-flash.sh` (unlocks bootloader [wipes watch], flashes patched `boot.img`, joins Wi-Fi over ADB without phone, installs `pixel-watch-agent-hud.zip` via Magisk, reboots into standalone appliance).
6. **Post-Root Verification:** Check `cat /data/local/tmp/agenthud_service.log` on the watch to confirm the exact `selene` battery charge-limit sysfs node was found and caps charging cleanly at ~65% on the wall dock (if `CHARGE_NODE=NONE`, inspect `/sys/class/power_supply/` with `su` and add the exact `selene` sysfs path to `service.sh`).
7. Install Mac auto-start daemon: `./mac-agent-bridge/install-launchd.sh`.

---

## 6. Quick Commands Cheat Sheet

```bash
# 1. Start Mac bridge manually (or install launchd with ./mac-agent-bridge/install-launchd.sh)
cd "~/Documents/Projects/Pixel Watch Mod/mac-agent-bridge" && node src/server.mjs

# 2. Check live bridge JSON (threads + CodexBar quotas)
curl -s http://127.0.0.1:8765/status | python3 -m json.tool

# 3. Rebuild APK + Magisk module ZIP, install on watch, and connect over USB tunnel
"~/Documents/Projects/Pixel Watch Mod/firmware-mod/scripts/build-module.sh"
adb -s <watch-serial> reverse tcp:8765 tcp:8765
adb -s <watch-serial> install -r "~/Documents/Projects/Pixel Watch Mod/watch-hud/app/build/outputs/apk/release/app-release.apk"
adb -s <watch-serial> shell pm grant com.agenthud.watch android.permission.RECORD_AUDIO
adb -s <watch-serial> shell am start -n com.agenthud.watch/.MainActivity --es host 127.0.0.1

# 4. Capture live watch screenshot
adb -s <watch-serial> exec-out screencap -p > /tmp/watch.png

# 5. Fire a simulated approval alert to the watch
curl -s -X POST http://127.0.0.1:8765/event \
  -H 'Content-Type: application/json' \
  -d '{"id":"demo:1","provider":"codex","providerLabel":"Codex","source":"desktop","title":"Test Approval","status":"waiting_approval","prompt":"Approve command?","options":["Proceed","Deny"],"canReply":true}'

# 6. Emergency 30-second stock recovery (from Fastboot)
"~/Documents/Projects/Pixel Watch Mod/firmware-mod/scripts/99-recover-stock.sh"
```
