package com.agenthud.watch.service

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Keeps Wireless debugging on so the Mac can always reach the watch over Wi-Fi ADB.
 * Android turns it off on every reboot (and when Wi-Fi drops); this flips it back on.
 * Needs WRITE_SECURE_SETTINGS, granted once over USB:
 *   adb shell pm grant com.agenthud.watch android.permission.WRITE_SECURE_SETTINGS
 * The current Wi-Fi network must already be "always allowed" for debugging, or Android
 * shows a confirmation prompt instead of enabling it.
 */
object AdbWifiKeeper {
    private const val KEY = "adb_wifi_enabled"

    fun ensure(context: Context) {
        val cr = context.contentResolver
        runCatching {
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) return
            if (Settings.Global.getInt(cr, KEY, 0) == 1) return
            Settings.Global.putInt(cr, KEY, 1)
            Log.i("AgentHUD", "Re-enabled wireless debugging")
        }.onFailure { Log.w("AgentHUD", "Can't enable wireless debugging: ${it.message}") }
    }
}
