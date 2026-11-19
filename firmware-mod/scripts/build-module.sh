#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WATCH_DIR="$ROOT_DIR/watch-hud"
MOD_DIR="$ROOT_DIR/firmware-mod/module"
OUT_DIR="$ROOT_DIR/firmware-mod/out"
mkdir -p "$OUT_DIR" "$MOD_DIR/system/priv-app/AgentHUD"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

echo "=== 1. Building AgentHUD Wear OS APK ==="
cd "$WATCH_DIR"
./gradlew :app:assembleRelease --no-daemon

APK_PATH="$WATCH_DIR/app/build/outputs/apk/release/app-release.apk"
cp "$APK_PATH" "$MOD_DIR/system/priv-app/AgentHUD/AgentHUD.apk"

# Embed Mac ADB public key so ADB stays authorized across factory resets/reboots
if [ -f "$HOME/.android/adbkey.pub" ]; then
  cp "$HOME/.android/adbkey.pub" "$MOD_DIR/adbkey.pub"
fi

echo "=== 2. Packaging Magisk Firmware Module ==="
ZIP_OUT="$OUT_DIR/pixel-watch-agent-hud.zip"
rm -f "$ZIP_OUT"
cd "$MOD_DIR"
zip -r9 "$ZIP_OUT" . -x "*.DS_Store"

echo "Built APK:    $APK_PATH"
echo "Built Module: $ZIP_OUT"
