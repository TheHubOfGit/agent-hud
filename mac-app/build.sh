#!/usr/bin/env bash
# Builds "build/Agent HUD.app" (release, ad-hoc signed).
#   ./build.sh            build the app
#   ./build.sh --icon     regenerate the icon first (needs python3 + Pillow)
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
APP="$ROOT/build/Agent HUD.app"
EXE_NAME="AgentHUD"

if [[ "${1:-}" == "--icon" || ! -f "$ROOT/Resources/AppIcon.icns" ]]; then
  echo "==> Generating icon"
  python3 "$ROOT/scripts/make_icon.py"
fi

echo "==> swift build -c release"
swift build -c release --package-path "$ROOT"
BIN_DIR="$(swift build -c release --package-path "$ROOT" --show-bin-path)"

echo "==> Assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/$EXE_NAME" "$APP/Contents/MacOS/$EXE_NAME"
cp "$ROOT/Resources/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
printf 'APPL????' > "$APP/Contents/PkgInfo"

# Bundle a copy of the bridge inside the app. Running it from the repo (in ~/Documents) makes
# macOS hold the child at a "Documents folder" privacy check, which re-prompts after every
# ad-hoc rebuild. The bundled copy reads nothing protected. Rebuild to pick up bridge changes.
BRIDGE_SRC="$ROOT/../mac-agent-bridge"
echo "==> Bundling bridge from $BRIDGE_SRC"
mkdir -p "$APP/Contents/Resources/bridge/bin"
rsync -a --delete "$BRIDGE_SRC/src" "$BRIDGE_SRC/package.json" "$BRIDGE_SRC/node_modules" "$APP/Contents/Resources/bridge/"
echo "==> Compiling live-ctrl Swift helper"
swiftc -O "$BRIDGE_SRC/swift/live-ctrl.swift" \
  -framework AppKit -framework ApplicationServices \
  -o "$APP/Contents/Resources/bridge/bin/live-ctrl"
# The 141 MB speech model goes to Application Support once (voice transcription looks there).
MODEL_DIR="$HOME/Library/Application Support/AgentHUD/models"
if [[ -f "$BRIDGE_SRC/models/ggml-base.en.bin" && ! -f "$MODEL_DIR/ggml-base.en.bin" ]]; then
  mkdir -p "$MODEL_DIR"
  cp "$BRIDGE_SRC/models/ggml-base.en.bin" "$MODEL_DIR/"
fi
plutil -lint "$APP/Contents/Info.plist" >/dev/null

# Sign with the local self-signed identity when it exists, so macOS privacy permissions
# (Accessibility, Automation) follow the certificate and survive rebuilds. Without it, fall back
# to ad-hoc signing, which macOS ties to each build's exact hash (permissions reset every build).
# Create the identity once with: ./scripts/make_signing_identity.sh
SIGN_ID_NAME="Agent HUD Local Signing"
SIGN_ID="$(security find-identity -p codesigning 2>/dev/null | awk -v n="\"$SIGN_ID_NAME\"" 'index($0, n) {print $2; exit}')"
if [[ -n "$SIGN_ID" ]]; then
  echo "==> Signing with \"$SIGN_ID_NAME\" ($SIGN_ID)"
  codesign --force --deep --timestamp=none -s "$SIGN_ID" "$APP"
else
  echo "==> Ad-hoc signing (no \"$SIGN_ID_NAME\" identity; permissions will reset each build)"
  codesign --force --deep -s - "$APP"
fi
codesign --verify --strict "$APP" || echo "    (verify reports the self-signed certificate as untrusted; that's expected)"

echo "==> Done: $APP"
echo "    Run:  open \"$APP\""
echo "    Test on another port:  open \"$APP\" --args -port 8799"
