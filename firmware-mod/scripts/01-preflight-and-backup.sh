#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STOCK_DIR="$ROOT_DIR/firmware-mod/stock"
TOOLS_DIR="$ROOT_DIR/firmware-mod/tools"
mkdir -p "$STOCK_DIR" "$TOOLS_DIR"

# Prefer USB-attached Pixel Watch 3 (required for fastboot)
USB_SERIAL="${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | grep -v "_adb-tls-connect" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}"
if [ -n "$USB_SERIAL" ]; then
  SERIAL="$USB_SERIAL"
  CONN_TYPE="USB (ready for fastboot)"
else
  SERIAL="$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')"
  CONN_TYPE="Wi-Fi ONLY — plug into stock USB pogo charger before flashing!"
fi

if [ -z "$SERIAL" ]; then
  echo "ERROR: Pixel Watch 3 not found on ADB."
  exit 1
fi

echo "=== Checking Pixel Watch 3 ($SERIAL) ==="
echo "Connection: $CONN_TYPE"
adb -s "$SERIAL" get-state >/dev/null

BATT=$(adb -s "$SERIAL" shell dumpsys battery | awk '/level:/ {print $2; exit}' | tr -d '\r')
BUILD=$(adb -s "$SERIAL" shell getprop ro.build.display.id | tr -d '\r')
DEVICE=$(adb -s "$SERIAL" shell getprop ro.product.device | tr -d '\r')
SDK=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
SLOT=$(adb -s "$SERIAL" shell getprop ro.boot.slot_suffix | tr -d '\r')
LOCKED=$(adb -s "$SERIAL" shell getprop ro.boot.flash.locked | tr -d '\r')
HAS_INIT_BOOT=$(adb -s "$SERIAL" shell 'ls /dev/block/by-name/init_boot_a 2>/dev/null || true' | tr -d '\r')

echo "Device:     $DEVICE (SDK $SDK, slot ${SLOT:-_a})"
echo "Build:      $BUILD"
echo "Battery:    ${BATT}%"
echo "Bootloader: $([ "$LOCKED" = "1" ] && echo "LOCKED" || echo "UNLOCKED")"
echo "init_boot:  $([ -n "$HAS_INIT_BOOT" ] && echo "PRESENT (patch init_boot.img)" || echo "NOT FOUND")"

if [ "$DEVICE" != "selene" ]; then
  echo "WARNING: Expected device 'selene', got '$DEVICE'. Verify factory image codename before proceeding!"
fi

if [ -z "$HAS_INIT_BOOT" ]; then
  echo "ERROR: init_boot_a partition not found. Stop and inspect partition layout before flashing."
  exit 1
fi

if [ "${BATT:-0}" -lt 45 ]; then
  echo "WARNING: Battery is below 45%. Charge to >=50% before unlocking/flashing."
  exit 1
fi

# Extract stock_init_boot.img if official factory image ZIP was placed in firmware-mod/stock/
FACTORY_ZIP=$(ls "$STOCK_DIR"/selene-*.zip "$STOCK_DIR"/seluna-*.zip 2>/dev/null | head -n 1 || true)
if [ -n "$FACTORY_ZIP" ]; then
  echo ""
  echo "Found factory image: $(basename "$FACTORY_ZIP")"
  BUILD_LOWER=$(echo "$BUILD" | tr '[:upper:]' '[:lower:]')
  ZIP_LOWER=$(basename "$FACTORY_ZIP" | tr '[:upper:]' '[:lower:]')
  if [[ "$ZIP_LOWER" != *"$BUILD_LOWER"* ]]; then
    echo "WARNING: Factory image filename does not appear to match current watch build ($BUILD)."
    echo "         Make sure the watch OS and init_boot.img are from the exact same build!"
  fi

  TMP_EXTRACT=$(mktemp -d)
  trap 'rm -rf "$TMP_EXTRACT"' EXIT
  unzip -q "$FACTORY_ZIP" -d "$TMP_EXTRACT"
  INNER_ZIP=$(find "$TMP_EXTRACT" -name "image-*.zip" | head -n 1 || true)
  if [ -n "$INNER_ZIP" ]; then
    if unzip -l "$INNER_ZIP" | grep -qE '[[:space:]]init_boot\.img$'; then
      unzip -q -j "$INNER_ZIP" "init_boot.img" -d "$STOCK_DIR"
      mv "$STOCK_DIR/init_boot.img" "$STOCK_DIR/stock_init_boot.img"
      echo "SUCCESS: Extracted untouched recovery backup -> $STOCK_DIR/stock_init_boot.img"
      shasum -a 256 "$STOCK_DIR/stock_init_boot.img"
    else
      echo "ERROR: init_boot.img not found inside $(basename "$INNER_ZIP")!"
      echo "Contents:"
      unzip -l "$INNER_ZIP" | grep '\.img' || true
      exit 1
    fi
  else
    echo "ERROR: Could not find inner image-*.zip inside $(basename "$FACTORY_ZIP")."
    echo "       If you downloaded a Full OTA (payload.bin) instead of a Factory Image,"
    echo "       download the Factory Image from https://developers.google.com/android/images-watch"
    exit 1
  fi
else
  echo ""
  echo "NEXT STEP: Download the official 'selene' factory image matching '$BUILD' from:"
  echo "  https://developers.google.com/android/images-watch"
  echo "Save the zip into: $STOCK_DIR/"
  echo "Then re-run this script to extract stock_init_boot.img."
fi

MAGISK_APK=$(ls "$TOOLS_DIR"/Magisk*.apk 2>/dev/null | head -n 1 || true)
if [ -n "$MAGISK_APK" ]; then
  echo "Magisk APK: $(basename "$MAGISK_APK") (ready)"
else
  echo "Magisk APK: missing in $TOOLS_DIR/"
fi
