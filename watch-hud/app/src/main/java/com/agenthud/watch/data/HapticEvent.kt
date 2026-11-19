package com.agenthud.watch.data

/** Things the watch can buzz for; each can be switched on or off in Vibration settings. */
enum class HapticEvent(val key: String, val label: String, val defaultOn: Boolean) {
    NEEDS_YOU("needs_you", "Needs you", true),
    MORE_WAITING("more_waiting", "More waiting", true),
    ERROR("error", "Errors", true),
    STARTED("started", "Agent started", false),
    FINISHED("finished", "Agent finished", false),
    CLEARED("cleared", "All cleared", true),
    TAPS("taps", "Button taps", true)
}
