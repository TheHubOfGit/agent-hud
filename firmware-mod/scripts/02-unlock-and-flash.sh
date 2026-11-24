#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STOCK_INIT_BOOT="$ROOT_DIR/firmware-mod/stock/stock_init_boot.img"
OUT_DIR="$ROOT_DIR/firmware-mod/out"
TOOLS_DIR="$ROOT_DIR/firmware-mod/tools"
PATCHED_INIT_BOOT="${PATCHED_INIT_BOOT:-$OUT_DIR/magisk_patched_init_boot.img}"
MODULE_ZIP="$OUT_DIR/pixel-watch-agent-hud.zip"
HUD_APK="$ROOT_DIR/watch-hud/app/build/outputs/apk/release/app-release.apk"
MAGISK_APK="$(ls "$TOOLS_DIR"/Magisk*.apk 2>/dev/null | head -n 1 || true)"

# Require USB serial (fastboot only works over USB, and we must never touch a connected phone)
SERIAL="${1:-${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | grep -v "_adb-tls-connect" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}}"

if [ -z "$SERIAL" ]; then
  echo "ERROR: No USB-connected Pixel Watch 3 found."
  echo "       Place the watch on its stock USB pogo charger plugged directly into your Mac."
  exit 1
fi

if [ ! -f "$STOCK_INIT_BOOT" ]; then
  echo "ERROR: Missing $STOCK_INIT_BOOT backup! Run 01-preflight-and-backup.sh first."
  exit 1
fi

LOCKED="$(adb -s "$SERIAL" shell getprop ro.boot.flash.locked | tr -d '\r')"

echo "=== Target Watch: $SERIAL (Bootloader: $([ "$LOCKED" = "1" ] && echo LOCKED || echo UNLOCKED)) ==="

if [ "$LOCKED" = "1" ]; then
  echo ""
  echo "=== STEP 1: Unlock Bootloader (FACTORY WIPES WATCH) ==="
  echo "Confirm Settings -> System -> Developer options -> OEM unlocking is ON."
  read -r -p "Press ENTER to reboot watch ($SERIAL) into Fastboot (or Ctrl+C to abort)..."
  adb -s "$SERIAL" reboot bootloader
  sleep 4

  echo "Waiting for fastboot device $SERIAL..."
  fastboot devices
  if ! fastboot devices | awk '{print $1}' | grep -qx "$SERIAL"; then
    echo "ERROR: Watch $SERIAL did not appear in fastboot devices."
    exit 1
  fi

  read -r -p "Run 'fastboot -s $SERIAL flashing unlock' now? (WIPES WATCH) [y/N] " UNLOCK_ANS
  if [[ "$UNLOCK_ANS" =~ ^[Yy]$ ]]; then
    fastboot -s "$SERIAL" flashing unlock
    echo ""
    echo "Confirm unlock on the watch screen using the physical buttons."
    echo "After the wipe finishes:"
    echo "  1. Complete minimal initial setup & connect to Wi-Fi."
    echo "  2. Re-enable Developer options -> ADB debugging (and Wireless debugging)."
    echo "  3. Re-run this script to flash magisk_patched_init_boot.img and restore Agent HUD."
    exit 0
  else
    echo "Aborted before unlocking. Rebooting watch normally..."
    fastboot -s "$SERIAL" reboot
    exit 0
  fi
fi

echo ""
echo "=== STEP 2: Flash Magisk-Patched init_boot.img ==="
if [ ! -f "$PATCHED_INIT_BOOT" ]; then
  echo "ERROR: $PATCHED_INIT_BOOT not found."
  echo "Patch $STOCK_INIT_BOOT in the Magisk app and save the output to: $PATCHED_INIT_BOOT"
  exit 1
fi

read -r -p "Flash $(basename "$PATCHED_INIT_BOOT") to init_boot on $SERIAL? [y/N] " FLASH_ANS
if [[ "$FLASH_ANS" =~ ^[Yy]$ ]]; then
  adb -s "$SERIAL" reboot bootloader
  sleep 4
  if ! fastboot devices | awk '{print $1}' | grep -qx "$SERIAL"; then
    echo "ERROR: Watch $SERIAL did not appear in fastboot devices."
    exit 1
  fi
  fastboot -s "$SERIAL" flash init_boot "$PATCHED_INIT_BOOT"
  fastboot -s "$SERIAL" reboot
  echo "Waiting for watch ($SERIAL) to boot back into ADB..."
  adb -s "$SERIAL" wait-for-device
  sleep 8
fi

echo ""
echo "=== STEP 3: Install Magisk App, Agent HUD APK & Appliance Module ==="
if [ -n "$MAGISK_APK" ] && [ -f "$MAGISK_APK" ]; then
  echo "Installing $(basename "$MAGISK_APK")..."
  adb -s "$SERIAL" install -r "$MAGISK_APK" || true
fi

if [ -f "$HUD_APK" ]; then
  echo "Installing Agent HUD release APK..."
  adb -s "$SERIAL" install -r "$HUD_APK"
  adb -s "$SERIAL" shell pm grant com.agenthud.watch android.permission.RECORD_AUDIO || true
  adb -s "$SERIAL" shell pm grant com.agenthud.watch android.permission.WRITE_SECURE_SETTINGS || true
  adb -s "$SERIAL" shell cmd package set-home-activity com.agenthud.watch/.MainActivity || true
else
  echo "WARNING: $HUD_APK not found — run firmware-mod/scripts/build-module.sh first."
fi

if [ -f "$MODULE_ZIP" ]; then
  echo "Verifying root (approve the Magisk su prompt on the watch screen if shown)..."
  adb -s "$SERIAL" shell "su -c 'id'"
  echo "Installing Magisk appliance module..."
  adb -s "$SERIAL" push "$MODULE_ZIP" /data/local/tmp/pixel-watch-agent-hud.zip
  adb -s "$SERIAL" shell "su -c 'magisk --install-module /data/local/tmp/pixel-watch-agent-hud.zip'"
  adb -s "$SERIAL" shell rm -f /data/local/tmp/pixel-watch-agent-hud.zip
  echo "=== DONE! Rebooting into rooted Agent HUD ==="
  adb -s "$SERIAL" reboot
else
  echo "WARNING: $MODULE_ZIP not found — run firmware-mod/scripts/build-module.sh first."
fi
