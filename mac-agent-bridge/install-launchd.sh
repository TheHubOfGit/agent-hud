#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NODE_BIN="$(command -v node)"
PLIST="$HOME/Library/LaunchAgents/com.agenthud.bridge.plist"
LOG_DIR="$HOME/Library/Logs/AgentHUD"
mkdir -p "$LOG_DIR"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.agenthud.bridge</string>
  <key>ProgramArguments</key>
  <array>
    <string>${NODE_BIN}</string>
    <string>${SCRIPT_DIR}/src/server.mjs</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${SCRIPT_DIR}</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/bridge.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/bridge.err.log</string>
</dict>
</plist>
EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load -w "$PLIST"
echo "Installed & started com.agenthud.bridge launchd service."
echo "Logs: $LOG_DIR/bridge.out.log"
