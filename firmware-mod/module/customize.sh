#!/system/bin/sh
ui_print "============================================"
ui_print " Pixel Watch 3 (selene) AI Agent HUD Mod"
ui_print "============================================"
ui_print "- Preserving Mac ADB authorization key"
ui_print "- Enabling native 60-65% kernel charge cap"
ui_print "- Disabling Fitbit, Health, OTA & bloat"
ui_print "- Tuning Wi-Fi, Doze & AgentHUD permissions"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
