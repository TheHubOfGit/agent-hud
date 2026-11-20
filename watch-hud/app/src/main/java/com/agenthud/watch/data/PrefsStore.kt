package com.agenthud.watch.data

import android.content.Context
import com.agenthud.watch.BuildConfig

class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("agent_hud_prefs", Context.MODE_PRIVATE)

    var homeLayout: HomeLayout
        get() = HomeLayout.fromId(prefs.getString("home_layout", HomeLayout.PULSE.id))
        set(value) = prefs.edit().putString("home_layout", value.id).apply()

    var cachedHost: String
        get() = prefs.getString("cached_host", "") ?: ""
        set(value) = prefs.edit().putString("cached_host", value.trim()).apply()

    var cachedPort: Int
        get() = prefs.getInt("cached_port", BuildConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt("cached_port", value).apply()

    var authToken: String
        get() = prefs.getString("auth_token", BuildConfig.DEFAULT_AUTH_TOKEN) ?: BuildConfig.DEFAULT_AUTH_TOKEN
        set(value) = prefs.edit().putString("auth_token", value.trim()).apply()

    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()

    var mutedUntilMs: Long
        get() = prefs.getLong("muted_until_ms", 0L)
        set(value) = prefs.edit().putLong("muted_until_ms", value).apply()

    var brightnessLevel: Float
        get() = prefs.getFloat("brightness_level", 0.7f)
        set(value) = prefs.edit().putFloat("brightness_level", value.coerceIn(0.12f, 1.0f)).apply()

    fun isMutedNow(): Boolean = System.currentTimeMillis() < mutedUntilMs

    /**
     * How long the screen stays on while the HUD is in front and the watch is off the charger
     * (on the charger it is always held on): an index into [SCREEN_MODES].
     */
    var screenMode: Int
        get() = prefs.getInt("screen_mode", 0).coerceIn(0, SCREEN_MODES.size - 1)
        set(value) = prefs.edit().putInt("screen_mode", value).apply()

    /** Quota page style: false = bars (default), true = concentric rings. */
    var quotaRings: Boolean
        get() = prefs.getBoolean("quota_rings", false)
        set(value) = prefs.edit().putBoolean("quota_rings", value).apply()

    /**
     * Page layout: false = side to side (Quotas left of Home, agents to the right; default),
     * true = vertical (Controls, Quotas, Home, then agents, top to bottom).
     */
    var verticalPages: Boolean
        get() = prefs.getBoolean("vertical_pages", false)
        set(value) = prefs.edit().putBoolean("vertical_pages", value).apply()

    // ---- Vibration & alerts ----
    /** Show a full-screen "done" card (and wake the screen) when an agent finishes its turn. */
    var doneAlert: Boolean
        get() = prefs.getBoolean("done_alert", false)
        set(value) = prefs.edit().putBoolean("done_alert", value).apply()

    /** 0 = off, 1 = light, 2 = medium, 3 = strong. */
    var hapticStrength: Int
        get() = prefs.getInt("haptic_strength", 2)
        set(value) = prefs.edit().putInt("haptic_strength", value.coerceIn(0, 3)).apply()

    fun hapticEnabled(event: HapticEvent): Boolean = prefs.getBoolean("haptic_on_${event.key}", event.defaultOn)
    fun setHapticEnabled(event: HapticEvent, on: Boolean) = prefs.edit().putBoolean("haptic_on_${event.key}", on).apply()

    /** Minutes between repeat buzzes while something waits on you; 0 = never. */
    var hapticReminderMins: Int
        get() = prefs.getInt("haptic_reminder_mins", 0)
        set(value) = prefs.edit().putInt("haptic_reminder_mins", value).apply()

    /** Index into [QUIET_HOURS]; 0 = off. */
    var quietHoursPreset: Int
        get() = prefs.getInt("quiet_hours", 0)
        set(value) = prefs.edit().putInt("quiet_hours", value).apply()

    fun inQuietHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val (start, end) = QUIET_HOURS.getOrNull(quietHoursPreset)?.second ?: return false
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    companion object {
        /** Label to (startHour, endHour); first entry means off. */
        val QUIET_HOURS: List<Pair<String, Pair<Int, Int>?>> = listOf(
            "Off" to null,
            "10 PM–7 AM" to (22 to 7),
            "11 PM–8 AM" to (23 to 8),
            "12 AM–9 AM" to (0 to 9)
        )
        val REMINDER_MINS = listOf(0, 2, 5, 10)
        /** Label to minutes held on after the last touch off the charger: -1 = always, 0 = system timeout. */
        val SCREEN_MODES: List<Pair<String, Int>> = listOf(
            "Always on" to -1,
            "Charging only" to 0,
            "Off charger: 2 min" to 2,
            "Off charger: 5 min" to 5
        )
    }
}
