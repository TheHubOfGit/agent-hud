#!/system/bin/sh
ui_print "============================================"
ui_print " Pixel Watch 3 (selene) AI Agent HUD Mod"
ui_print "============================================"
ui_print "- Installing privileged AgentHUD launcher"
ui_print "- Configuring phone-free standalone mode"
ui_print "- Enabling 65% dock charge cap & Wi-Fi lock"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
