#!/usr/bin/env bash
# No-root de-Google pass for the Pixel Watch 3 (selene). Reversible with restore.sh.
# Removes optional Google apps for user 0, turns off LTE and Bluetooth, and moves time sync off Google.
# Core pieces (Play services, GSF, Wear OS app, Wear services, SysUI, Gboard, Google TTS) are kept:
# Wear OS and the HUD's voice reply depend on them.
set -u
SERIAL="${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}"  # the watch, found by model
W() { adb -s "$SERIAL" shell "$@"; }

REMOVE=(
  com.google.android.GoogleCamera
  com.google.android.apps.adm
  com.google.android.apps.chromecast.app
  com.google.android.apps.maps
  com.google.android.apps.messaging
  com.google.android.apps.recorder
  com.google.android.apps.safetyhub
  com.google.android.apps.walletnfcrel
  com.google.android.apps.weather
  com.google.android.apps.youtube.music
  com.google.android.apps.photos
  com.google.android.calendar
  com.google.android.contacts
  com.google.android.dialer
  com.google.android.gm
  com.google.android.keep
  com.google.android.marvin.talkback
  com.google.android.wearable.assistant
  com.google.android.wearable.weather
  com.google.android.wearable.pixel.aspen
  com.google.android.wearable.diagnosticstool
  com.google.android.wearable.fitbit.retaildemo
  com.google.android.apps.wearable.retailattractloop
  com.google.android.gms.supervision
  com.google.android.apps.carrier.log
  com.google.omadm.trigger
  com.android.hotwordenrollment.okgoogle
  com.android.hotwordenrollment.xgoogle
  android.autoinstalls.config.google.wear
  com.android.vending
)

for p in "${REMOVE[@]}"; do
  printf '%-55s ' "$p"; W pm uninstall --user 0 "$p" 2>&1 | tr -d '\r'
done

# Radios: cellular and Bluetooth off, Wi-Fi on. Airplane mode is NOT used: on Wear OS the shell
# can't turn Wi-Fi back on while it's active, which would cut the HUD off from the bridge.
W settings put global cell_on 0
W settings put global mobile_data 0
W cmd bluetooth_manager disable
W cmd wifi set-wifi-enabled enabled

# Time from a public NTP pool instead of time.android.com.
W settings put global ntp_server pool.ntp.org

# No cloud backup.
W bmgr enable false

echo "done"
