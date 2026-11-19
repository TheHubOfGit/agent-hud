#!/usr/bin/env bash
# Undo apply.sh: reinstall every removed system app for user 0 and restore radios/settings.
set -u
SERIAL="${WATCH_SERIAL:-$(adb devices -l | grep -E "[[:space:]]device[[:space:]].*model:Pixel_Watch_3" | head -1 | sed -E 's/[[:space:]]+device[[:space:]].*//')}"  # the watch, found by model
W() { adb -s "$SERIAL" shell "$@"; }
HERE="$(cd "$(dirname "$0")" && pwd)"

# Every package that existed before (packages-before.txt) but is missing now gets reinstalled.
NOW=$(W pm list packages | tr -d '\r')
while read -r line; do
  p="${line#package:}"; p="${p%$'\r'}"
  grep -qx "package:$p" <<<"$NOW" || { printf '%-55s ' "$p"; W cmd package install-existing "$p" | tr -d '\r'; }
done < "$HERE/packages-before.txt"

W settings put global cell_on 1
W cmd bluetooth_manager enable
W cmd wifi set-wifi-enabled enabled
W settings delete global ntp_server
W bmgr enable true
echo "restored"
