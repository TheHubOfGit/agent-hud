#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STOCK_BOOT="$ROOT_DIR/firmware-mod/stock/stock_boot.img"

if [ ! -f "$STOCK_BOOT" ]; then
  echo "ERROR: $STOCK_BOOT not found."
  echo "You can always restore 100% stock online via https://flash.android.com"
  exit 1
fi

echo "=== Pixel Watch 3 (selene) 30-Second Stock Boot Recovery ==="
echo "Ensure watch is in Fastboot mode (hold crown + side button to reboot, then tap top-left & bottom-right corners on Google logo)."
fastboot devices
read -r -p "Press ENTER to flash untouched stock_boot.img and reboot..."
fastboot flash boot "$STOCK_BOOT"
fastboot reboot
echo "Restored untouched stock boot.img! Watch is rebooting normally."
