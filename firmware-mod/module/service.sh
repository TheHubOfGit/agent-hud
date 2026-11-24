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
log "Boot complete — applying Pixel Watch 3 (selene) rooted appliance profile v1.1"

# ------------------------------------------------------------------
# 1. RADIOS, TIME & BACKUP (Phone-Free Wi-Fi Desk Mode)
# ------------------------------------------------------------------
settings put global bluetooth_on 0
cmd bluetooth_manager disable 2>/dev/null || true
settings put global cell_on 0
settings put global mobile_data 0

# Time from public NTP pool instead of Google
settings put global ntp_server pool.ntp.org
settings put global auto_time 1
settings put global auto_time_zone 1
settings put global clockwork_auto_time 0

# Disable Google cloud backup
bmgr enable false 2>/dev/null || true

# ------------------------------------------------------------------
# 2. DISABLE HEALTH SENSORS (FITBIT/MCU), OTA & OPTIONAL GOOGLE BLOAT
#    Keeps Wear OS core (SysUI, Settings, GMS, GSF, Wear Services) intact
#    so system settings and HUD stability are never broken.
# ------------------------------------------------------------------
for pkg in \
  com.fitbit.FitbitMobile \
  com.fitbit.ecg \
  com.google.android.wearable.healthservices \
  com.google.android.wearable.fitbit.mcu.data \
  com.google.android.wearable.fitbit.mcu.updater \
  com.google.android.wearable.fitbit.retaildemo \
  com.google.android.factoryota \
  com.google.android.apps.wearable.retailattractloop \
  com.google.android.wearable.assistant \
  com.google.android.wearable.sysui.overlay.experiences.charging.pixelwatch \
  com.google.android.wearable.diagnosticstool \
  com.google.android.wearable.weather \
  com.google.android.wearable.pixel.aspen \
  com.google.android.GoogleCamera \
  com.google.android.apps.adm \
  com.google.android.apps.chromecast.app \
  com.google.android.apps.maps \
  com.google.android.apps.messaging \
  com.google.android.apps.recorder \
  com.google.android.apps.safetyhub \
  com.google.android.apps.walletnfcrel \
  com.google.android.apps.weather \
  com.google.android.apps.youtube.music \
  com.google.android.apps.photos \
  com.google.android.calendar \
  com.google.android.contacts \
  com.google.android.dialer \
  com.google.android.gm \
  com.google.android.keep \
  com.google.android.marvin.talkback \
  com.google.android.gms.supervision \
  com.google.android.apps.carrier.log \
  com.google.omadm.trigger \
  com.android.hotwordenrollment.okgoogle \
  com.android.hotwordenrollment.xgoogle \
  android.autoinstalls.config.google.wear \
  com.android.vending
do
  pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
done

# ------------------------------------------------------------------
# 3. WI-FI, ADB & ALWAYS-ON DOCK TUNING (Verified selene keys)
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
settings put global adb_wifi_enabled 1
settings put secure screensaver_activate_on_dock 0
settings put secure screensaver_enabled 0
pm disable com.google.android.wearable.sysui/com.google.android.clockwork.sysui.experiences.charging.ui.composable.ChargingComposeActivity >/dev/null 2>&1 || true
cmd wifi set-wifi-enabled enabled 2>/dev/null || true
svc power stayon true 2>/dev/null || true
dumpsys deviceidle disable 2>/dev/null || true

# ------------------------------------------------------------------
# 4. OPTIONAL LAN-ONLY FIREWALL (Blocks all outbound WAN/Google traffic)
#    Enable permanently:  su -c 'touch /data/local/tmp/agenthud_lan_only'
#    Disable permanently: su -c 'rm -f /data/local/tmp/agenthud_lan_only'
# ------------------------------------------------------------------
if [ -f /data/local/tmp/agenthud_lan_only ]; then
  log "Applying LAN-only iptables firewall"
  iptables -N AGENTHUD_OUT 2>/dev/null || iptables -F AGENTHUD_OUT
  iptables -A AGENTHUD_OUT -o lo -j RETURN
  iptables -A AGENTHUD_OUT -m state --state ESTABLISHED,RELATED -j RETURN
  iptables -A AGENTHUD_OUT -d 10.0.0.0/8 -j RETURN
  iptables -A AGENTHUD_OUT -d 172.16.0.0/12 -j RETURN
  iptables -A AGENTHUD_OUT -d 192.168.0.0/16 -j RETURN
  iptables -A AGENTHUD_OUT -d 169.254.0.0/16 -j RETURN
  iptables -A AGENTHUD_OUT -d 224.0.0.0/4 -j RETURN
  iptables -A AGENTHUD_OUT -d 255.255.255.255/32 -j RETURN
  iptables -A AGENTHUD_OUT -j REJECT
  iptables -C OUTPUT -j AGENTHUD_OUT 2>/dev/null || iptables -I OUTPUT 1 -j AGENTHUD_OUT

  ip6tables -N AGENTHUD_OUT6 2>/dev/null || ip6tables -F AGENTHUD_OUT6
  ip6tables -A AGENTHUD_OUT6 -o lo -j RETURN
  ip6tables -A AGENTHUD_OUT6 -d fe80::/10 -j RETURN
  ip6tables -A AGENTHUD_OUT6 -d fc00::/7 -j RETURN
  ip6tables -A AGENTHUD_OUT6 -d ff00::/8 -j RETURN
  ip6tables -A AGENTHUD_OUT6 -j REJECT
  ip6tables -C OUTPUT -j AGENTHUD_OUT6 2>/dev/null || ip6tables -I OUTPUT 1 -j AGENTHUD_OUT6
fi

# ------------------------------------------------------------------
# 5. AGENT HUD PERMISSIONS & LAUNCH (Normal updatable APK)
# ------------------------------------------------------------------
if pm path com.agenthud.watch >/dev/null 2>&1; then
  pm grant com.agenthud.watch android.permission.RECORD_AUDIO 2>/dev/null || true
  pm grant com.agenthud.watch android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
  # Let stock SysUI dismiss its boot-time "Starting..." TrayInitializationOverlay once
  am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.google.android.wearable.sysui >/dev/null 2>&1 || true
  sleep 3
  cmd package set-home-activity com.agenthud.watch/.MainActivity 2>/dev/null || true
  am start -n com.agenthud.watch/.MainActivity >/dev/null 2>&1 || true
  log "AgentHUD permissions granted & launched as home app"
else
  log "AgentHUD APK not installed yet (install via wadb install -r)"
fi

# ------------------------------------------------------------------
# 6. NATIVE 60-65% KERNEL CHARGE LIMIT (Verified selene soc:google,charger)
#    Protects the 307mAh Li-ion cell from swelling on a 24/7 desk dock.
# ------------------------------------------------------------------
CHG_DIR="/sys/devices/platform/soc/soc:google,charger"
START_NODE="$CHG_DIR/charge_start_level"
STOP_NODE="$CHG_DIR/charge_stop_level"
MIN_CAP=60
MAX_CAP=65

apply_charge_cap() {
  if [ -e "$START_NODE" ] && [ -e "$STOP_NODE" ]; then
    echo "$MIN_CAP" > "$START_NODE" 2>/dev/null || true
    echo "$MAX_CAP" > "$STOP_NODE" 2>/dev/null || true
    local s_val e_val cap cur st
    s_val="$(cat "$START_NODE" 2>/dev/null || echo '?')"
    e_val="$(cat "$STOP_NODE" 2>/dev/null || echo '?')"
    cap="$(cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo '?')"
    cur="$(cat /sys/class/power_supply/battery/current_now 2>/dev/null || echo '?')"
    st="$(cat /sys/class/power_supply/battery/status 2>/dev/null || echo '?')"
    log "Kernel charge cap active: start=${s_val}% stop=${e_val}% (now: ${cap}%, ${st}, ${cur}uA)"
    return 0
  fi
  return 1
}

if ! apply_charge_cap; then
  log "WARNING: $STOP_NODE not found; charge cap skipped"
fi

# Lightweight watchdog: re-assert kernel charge limit & keep AgentHUD alive if killed
(
  while true; do
    sleep 300
    if [ -e "$STOP_NODE" ]; then
      CUR_STOP="$(cat "$STOP_NODE" 2>/dev/null || echo 100)"
      if [ "$CUR_STOP" != "$MAX_CAP" ]; then
        apply_charge_cap
      fi
    fi
    if pm path com.agenthud.watch >/dev/null 2>&1 && ! pidof com.agenthud.watch >/dev/null 2>&1; then
      am start -n com.agenthud.watch/.MainActivity >/dev/null 2>&1 || true
    fi
  done
) &
