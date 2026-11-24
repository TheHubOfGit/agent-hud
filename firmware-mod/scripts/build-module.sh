#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WATCH_DIR="$ROOT_DIR/watch-hud"
MOD_DIR="$ROOT_DIR/firmware-mod/module"
OUT_DIR="$ROOT_DIR/firmware-mod/out"
mkdir -p "$OUT_DIR"

# Remove legacy /system/priv-app directory so AgentHUD stays a normal updatable APK
rm -rf "$MOD_DIR/system"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

if [ "${SKIP_APK:-0}" != "1" ]; then
  echo "=== 1. Building AgentHUD Wear OS Release APK ==="
  cd "$WATCH_DIR"
  ./gradlew :app:assembleRelease --no-daemon
  APK_PATH="$WATCH_DIR/app/build/outputs/apk/release/app-release.apk"
  echo "Built APK:    $APK_PATH"
fi

# Embed Mac ADB public key so ADB stays authorized across reboots
if [ -f "$HOME/.android/adbkey.pub" ]; then
  cp "$HOME/.android/adbkey.pub" "$MOD_DIR/adbkey.pub"
fi

echo "=== 2. Packaging Magisk Appliance Module ==="
ZIP_OUT="$OUT_DIR/pixel-watch-agent-hud.zip"
rm -f "$ZIP_OUT"
cd "$MOD_DIR"
zip -r9 "$ZIP_OUT" . -x "*.DS_Store"

echo "Built Module: $ZIP_OUT"
