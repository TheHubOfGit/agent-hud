# Agent HUD

A Pixel Watch 3 that sits on your desk and shows what your AI coding agents are doing, so you can glance over instead of switching windows. It tracks **Claude** (Claude Code and Claude Desktop), **Codex**, **Antigravity** and **T3 Code**, plus your plan quotas from CodexBar, and lets you act from the watch: approve, deny, tap an answer to a question, or reply by voice.

Three pieces:

| Folder | What it is |
|---|---|
| `watch-hud/` | The Wear OS app (Kotlin, Jetpack Compose). Runs as the watch's home screen. |
| `mac-agent-bridge/` | A small Node service on your Mac. Watches each agent's local session files and databases and streams one live snapshot to the watch over Wi-Fi. |
| `mac-app/` | **Agent HUD.app**, a menu bar app that runs and supervises the bridge, with a dashboard. |
| `firmware-mod/` | Experimental root/flash tooling and a no-root de-Google script. See the warning below. |

## On the watch

- **Home layouts** (pick in Controls): **Pulse**, one segment per agent around the edge; **Orbit**, each agent's logo travels a hairline track; **Zen**, type only.
- **Provider identity:** each agent's real logo, with color only while it's working. Claude is clay, Codex periwinkle, Antigravity a slowly drifting Gemini gradient, T3 Code platinum. Working agents carry a fading trail (and a small satellite dot in Orbit).
- **Waiting on you:** when an agent needs an approval or an answer, its arc turns amber and an aurora of warm light rises from it toward the center. Idle agents go gray.
- **Agent screen:** the latest step, the question with tappable choices, Proceed / Deny, and voice replies (transcribed on the Mac), with clear Sent / Couldn't send feedback.
- **Quotas:** bars or rings in provider colors, with an even-pace marker (in reserve / in deficit) and reset times for the 5-hour and weekly windows.
- **Alerts & vibration:** strength, which events buzz (needs you, errors, an agent starting or finishing, and more), reminders, quiet hours, and an optional "done" card.
- **Screen:** always on, on while charging only, or auto-off after 2 or 5 minutes off the charger.
- **Crown:** scrolls lists and moves between pages. The HUD is the watch's home app, and Controls has buttons back to the stock watch face, apps and settings.

## Setup

### 1. Mac app and bridge

```bash
cd mac-agent-bridge && npm install && cd ..
./mac-app/scripts/make_signing_identity.sh   # once: local code-signing identity (keeps macOS permissions across rebuilds)
./mac-app/build.sh
cp -R "mac-app/build/Agent HUD.app" /Applications/
open "/Applications/Agent HUD.app"
```

Agent HUD starts the bridge, restarts it if it crashes or hangs, and can launch at login. It runs a copy of the bridge **bundled inside the app**, so after changing anything in `mac-agent-bridge/`, rebuild and reinstall the app.

To type watch replies into your agent apps, grant **Agent HUD** Accessibility access (System Settings > Privacy & Security > Accessibility), and allow it to control System Events when macOS asks. The dashboard shows the status of both.

Voice replies use `whisper-cli` (Homebrew `whisper-cpp`) with the `ggml-base.en` model in `~/Library/Application Support/AgentHUD/models/` (or `mac-agent-bridge/models/`). The model isn't in the repo.

Check the bridge: `curl http://127.0.0.1:8765/health`

### 2. Watch app

```bash
cd watch-hud
./gradlew assembleDebug
../scripts/wadb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build bakes in the bridge's auth token from `~/.agenthud-bridge-token` (created by the bridge on first run). The watch finds the Mac over Bonjour (`_agenthud._tcp`) on the same Wi-Fi network.

`scripts/wadb` is `adb` pointed at the watch only. It finds the Pixel Watch by model, preferring USB and falling back to paired Wi-Fi debugging, so it never touches a phone that's also connected. Set `WATCH_SERIAL` to pin a specific device.

### Try it without a live agent

```bash
curl -X POST http://127.0.0.1:8765/event -H 'Content-Type: application/json' \
  -d '{"id":"demo:1","provider":"codex","providerLabel":"Codex","title":"Deploy preview","status":"waiting_approval","prompt":"Run the deploy?","options":["Proceed","Deny"]}'
# clear it
curl -X POST http://127.0.0.1:8765/event -H 'Content-Type: application/json' -d '{"id":"demo:1","clear":true}'
```

Test threads never type into any app.

## How each agent is read

| Agent | Source |
|---|---|
| Claude Code / Desktop | Session transcripts in `~/.claude/projects` and Claude Desktop's session records. "Working" means a turn is in progress. |
| Codex | Codex's local SQLite databases in `~/.codex`. Replies are queued into Codex directly. |
| Antigravity | The live `steps` table in each conversation's database (`~/.gemini/antigravity/conversations`), plus its transcript for text and questions. |
| T3 Code | T3's local state database (`orchestration_v2_*` tables). |
| Quotas | The CodexBar CLI. |

## Firmware (experimental, not ready)

`firmware-mod/` holds an unfinished plan to unlock, root and flash the watch (Magisk module, charge cap, debloat). **Don't run the flash scripts as they are:** they patch `boot` rather than `init_boot`, the charge-cap paths don't exist on this watch, and recovery assumes a factory image that hasn't been confirmed. Unlocking wipes the watch, and you must never relock the bootloader while it's modified.

`firmware-mod/degoogle/` is separate and safe: a reversible, no-root pass that removes optional Google apps and turns off LTE and Bluetooth (`apply.sh`, undone by `restore.sh`).

See `HANDOFF.md` for the detailed engineering notes and history.
