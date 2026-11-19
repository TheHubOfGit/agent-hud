#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}}"  # the watch, found by model
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STOCK_DIR="$ROOT_DIR/firmware-mod/stock"
mkdir -p "$STOCK_DIR"

echo "=== Checking Watch ($SERIAL) ==="
adb -s "$SERIAL" get-state >/dev/null

BATT=$(adb -s "$SERIAL" shell dumpsys battery | awk '/level:/ {print $2; exit}')
BUILD=$(adb -s "$SERIAL" shell getprop ro.build.display.id | tr -d '\r')
DEVICE=$(adb -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')
LOCKED=$(adb -s "$SERIAL" shell getprop ro.boot.flash.locked | tr -d '\r')

echo "Device:     $DEVICE"
echo "Build:      $BUILD"
echo "Battery:    ${BATT}%"
echo "Bootloader: $([ "$LOCKED" = "1" ] && echo "LOCKED" || echo "UNLOCKED")"

if [ "${BATT:-0}" -lt 45 ]; then
  echo "WARNING: Battery is below 45%. Let the watch charge to >=50% before unlocking/flashing!"
  exit 1
fi

# Extract stock_boot.img if official factory image ZIP was placed in firmware-mod/stock/
FACTORY_ZIP=$(ls "$STOCK_DIR"/selene-*.zip 2>/dev/null | head -n 1 || true)
if [ -n "$FACTORY_ZIP" ]; then
  echo "Found factory image: $FACTORY_ZIP"
  TMP_EXTRACT=$(mktemp -d)
  unzip -q "$FACTORY_ZIP" -d "$TMP_EXTRACT"
  INNER_ZIP=$(find "$TMP_EXTRACT" -name "image-selene-*.zip" | head -n 1 || true)
  if [ -n "$INNER_ZIP" ]; then
    unzip -q -j "$INNER_ZIP" "boot.img" -d "$STOCK_DIR"
    mv "$STOCK_DIR/boot.img" "$STOCK_DIR/stock_boot.img"
    echo "SUCCESS: Extracted untouched recovery backup -> $STOCK_DIR/stock_boot.img"
  fi
  rm -rf "$TMP_EXTRACT"
else
  echo "NEXT STEP: Download the official 'selene' ($BUILD) factory image ZIP from:"
  echo "  https://developers.google.com/android/images-watch"
  echo "Save it into: $STOCK_DIR/"
  echo "Then re-run this script to automatically extract stock_boot.img."
fi
