#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STOCK_INIT_BOOT="$ROOT_DIR/firmware-mod/stock/stock_init_boot.img"

if [ ! -f "$STOCK_INIT_BOOT" ]; then
  echo "ERROR: $STOCK_INIT_BOOT not found."
  echo "You can always restore 100% stock online via https://flash.android.com"
  exit 1
fi

echo "=== Pixel Watch 3 (selene) Stock init_boot Recovery ==="
echo "Ensure watch is in Fastboot mode (hold crown + side button to reboot, then tap top-left & bottom-right corners on Google logo)."
fastboot devices

SERIAL="${1:-${WATCH_SERIAL:-$(fastboot devices | awk 'NR==1 {print $1}')}}"
if [ -z "$SERIAL" ]; then
  echo "ERROR: No device found in fastboot."
  exit 1
fi

echo "Target fastboot serial: $SERIAL"
read -r -p "Press ENTER to flash untouched stock_init_boot.img to init_boot on $SERIAL (or Ctrl+C to abort)..."
fastboot -s "$SERIAL" flash init_boot "$STOCK_INIT_BOOT"
fastboot -s "$SERIAL" reboot
echo "Restored untouched stock init_boot.img! Watch is rebooting normally."
