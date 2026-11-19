#!/system/bin/sh
MODDIR=${0%/*}
LOG="/data/local/tmp/agenthud_service.log"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOG"
}

# Wait until Android finishes booting
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done
sleep 6
log "Boot complete — applying Pixel Watch 3 (selene) appliance profile"

# ------------------------------------------------------------------
# 1. COMPLETE PHONE-FREE DETACHMENT & OOBE BYPASS
# ------------------------------------------------------------------
settings put global device_provisioned 1
settings put secure user_setup_complete 1
settings put global oem_setup_completed_status 1
settings put global bluetooth_on 0
cmd bluetooth_manager disable 2>/dev/null || true

# NTP over Wi-Fi (no phone required for accurate time)
settings put global auto_time 1
settings put global auto_time_zone 1
settings put global clockwork_auto_time 0

# Disable Wear OS phone companion, charging overlay, health bloat, and OTAs
# (Keeps Settings, Wi-Fi, SystemUI, and core Android intact)
for pkg in \
  com.google.android.wearable.setupwizard \
  com.google.wear.services \
  com.google.android.wearable.app \
  com.google.android.wearable.sysui.overlay.experiences.charging.pixelwatch \
  com.google.android.wearable.media.sessions \
  com.google.android.wearable.assistant \
  com.google.android.factoryota \
  com.fitbit.FitbitMobile \
  com.fitbit.ecg \
  com.google.android.wearable.healthservices \
  com.google.android.wearable.fitbit.mcu.data \
  com.google.android.wearable.fitbit.mcu.updater
do
  pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
done

# ------------------------------------------------------------------
# 2. HIGH-FREQUENCY WI-FI & ALWAYS-ON DOCK TUNING (Verified selene keys)
# ------------------------------------------------------------------
settings put global wifi_on 1
settings put global auto_wifi 0
settings put global wifi_always_requested 1
settings put global wifi_power_save 0
settings put global wifi_sleep_policy 2
settings put global stay_on_while_plugged_in 7
settings put global ambient_plugged_timeout_min 0
settings put global auto_bedtime_mode 0
settings put global bedtime_mode 0
svc wifi enable 2>/dev/null || true
svc power stayon true 2>/dev/null || true
dumpsys deviceidle disable 2>/dev/null || true

# Grant AgentHUD permissions automatically (microphone + notifications)
pm grant com.agenthud.watch android.permission.RECORD_AUDIO 2>/dev/null || true
pm set-home-activity com.agenthud.watch/.MainActivity 2>/dev/null || true
cmd package set-home-activity com.agenthud.watch/.MainActivity 2>/dev/null || true

# Launch AgentHUD immediately on boot
am start -n com.agenthud.watch/.MainActivity >/dev/null 2>&1 || true
log "AgentHUD launched as primary appliance launcher"

# ------------------------------------------------------------------
# 3. 65% BATTERY CHARGE CAP DAEMON (Hysteresis: stop @ 65%, resume @ 60%)
#    Protects Li-ion cell from swelling on a 24/7 wall charging dock.
# ------------------------------------------------------------------
find_charge_node() {
  for p in \
    /sys/class/power_supply/battery/input_suspend \
    /sys/class/power_supply/main/input_suspend \
    /sys/class/power_supply/usb/input_suspend \
    /sys/class/power_supply/battery/charging_enabled \
    /sys/class/power_supply/battery/charge_disable \
    /sys/devices/platform/soc/soc:google,charger/charge_stop_level
  do
    if [ -w "$p" ]; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

CHARGE_NODE="$(find_charge_node)"
log "Detected battery charge control node: ${CHARGE_NODE:-NONE}"

(
  MAX_CAP=65
  MIN_CAP=60
  SUSPENDED=0

  while true; do
    CAP=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo 50)
    if [ -n "$CHARGE_NODE" ] && [ -w "$CHARGE_NODE" ]; then
      case "$CHARGE_NODE" in
        *charge_stop_level)
          echo "$MAX_CAP" > "$CHARGE_NODE" 2>/dev/null || true
          ;;
        *charging_enabled)
          if [ "$CAP" -ge "$MAX_CAP" ] && [ "$SUSPENDED" -eq 0 ]; then
            echo 0 > "$CHARGE_NODE" 2>/dev/null && SUSPENDED=1 && log "Charge paused at ${CAP}%"
          elif [ "$CAP" -le "$MIN_CAP" ] && [ "$SUSPENDED" -eq 1 ]; then
            echo 1 > "$CHARGE_NODE" 2>/dev/null && SUSPENDED=0 && log "Charge resumed at ${CAP}%"
          fi
          ;;
        *input_suspend|*charge_disable)
          if [ "$CAP" -ge "$MAX_CAP" ] && [ "$SUSPENDED" -eq 0 ]; then
            echo 1 > "$CHARGE_NODE" 2>/dev/null && SUSPENDED=1 && log "Charge paused at ${CAP}%"
          elif [ "$CAP" -le "$MIN_CAP" ] && [ "$SUSPENDED" -eq 1 ]; then
            echo 0 > "$CHARGE_NODE" 2>/dev/null && SUSPENDED=0 && log "Charge resumed at ${CAP}%"
          fi
          ;;
      esac
    fi

    # Keep AgentHUD running if it ever gets killed
    if ! pidof com.agenthud.watch >/dev/null 2>&1; then
      am start -n com.agenthud.watch/.MainActivity >/dev/null 2>&1 || true
    fi

    sleep 45
  done
) &
