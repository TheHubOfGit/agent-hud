#!/system/bin/sh
MODDIR=${0%/*}

# Preserve Mac ADB public key across reboots so USB/LAN ADB stays authorized
if [ -f "$MODDIR/adbkey.pub" ]; then
  mkdir -p /data/misc/adb
  if ! grep -q -F "$(cat "$MODDIR/adbkey.pub")" /data/misc/adb/adb_keys 2>/dev/null; then
    cat "$MODDIR/adbkey.pub" >> /data/misc/adb/adb_keys
  fi
  chmod 0640 /data/misc/adb/adb_keys
  chown system:shell /data/misc/adb/adb_keys
fi
