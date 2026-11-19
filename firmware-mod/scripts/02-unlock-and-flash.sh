#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}}"  # the watch, found by model
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT_DIR/firmware-mod/out"
STOCK_BOOT="$ROOT_DIR/firmware-mod/stock/stock_boot.img"
PATCHED_BOOT="${PATCHED_BOOT:-$OUT_DIR/magisk_patched_boot.img}"
MODULE_ZIP="$OUT_DIR/pixel-watch-agent-hud.zip"

if [ ! -f "$STOCK_BOOT" ]; then
  echo "ERROR: Missing $STOCK_BOOT backup! Run 01-preflight-and-backup.sh first so you always have a recovery image."
  exit 1
fi

echo "=== STEP 1: Reboot to Fastboot & Unlock Bootloader ==="
echo "Make sure 'OEM unlocking' is ON in Watch Settings -> System -> Developer options."
read -r -p "Press ENTER to reboot watch ($SERIAL) into Fastboot (or Ctrl+C to abort)..."
adb -s "$SERIAL" reboot bootloader
sleep 4
fastboot devices

echo ""
echo "If bootloader is still locked, run: fastboot flashing unlock"
echo "(Confirm on the watch screen using physical buttons — this factory-resets the watch.)"
read -r -p "Unlock bootloader now? [y/N] " UNLOCK_ANS
if [[ "$UNLOCK_ANS" =~ ^[Yy]$ ]]; then
  fastboot flashing unlock
  echo "Watch is wiping and rebooting. Wait for it to boot, enable ADB debugging, then re-run Step 2."
  exit 0
fi

echo ""
echo "=== STEP 2: Flash Magisk-Patched boot.img ==="
if [ ! -f "$PATCHED_BOOT" ]; then
  echo "ERROR: $PATCHED_BOOT not found."
  echo "Patch $STOCK_BOOT using the Magisk app and save the output to: $PATCHED_BOOT"
  exit 1
fi

fastboot flash boot "$PATCHED_BOOT"
fastboot reboot

echo "Waiting for watch to boot with ADB..."
adb wait-for-device
sleep 8

echo "=== STEP 3: Optional Standalone Wi-Fi Setup (No Phone Needed) ==="
read -r -p "Join Wi-Fi over ADB now? Enter SSID (or leave blank to skip): " WIFI_SSID
if [ -n "$WIFI_SSID" ]; then
  read -r -s -p "Enter Wi-Fi password for '$WIFI_SSID': " WIFI_PASS
  echo ""
  adb shell cmd wifi set-wifi-enabled enabled
  adb shell cmd wifi connect-network "$WIFI_SSID" wpa2 "$WIFI_PASS"
fi

echo "=== STEP 4: Install Custom Pixel Watch Agent HUD Magisk Module ==="
adb push "$MODULE_ZIP" /data/local/tmp/pixel-watch-agent-hud.zip
adb shell "su -c 'magisk --install-module /data/local/tmp/pixel-watch-agent-hud.zip'"
adb shell rm -f /data/local/tmp/pixel-watch-agent-hud.zip

echo "=== DONE! Rebooting into standalone Agent HUD Appliance ==="
adb reboot
