package com.agenthud.watch.data

import androidx.compose.ui.graphics.Color

enum class HomeLayout(val id: String, val label: String) {
    PULSE("A", "Pulse (A)"),   // Default: Concept A Material Expressive
    ORBIT("B", "Orbit (B)"),   // Concept B Orbital Constellation
    ZEN("C", "Zen (C)");       // Concept C Typographic Zen

    companion object {
        fun fromId(id: String?): HomeLayout = entries.firstOrNull { it.id == id } ?: PULSE
    }
}

enum class ConnectionState {
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    OFFLINE
}

data class AgentThread(
    val id: String,
    val provider: String,
    val providerLabel: String,
    val source: String, // "desktop" | "cli"
    val title: String,
    val status: String, // "running" | "waiting_approval" | "waiting_input" | "error" | "idle"
    val step: String,
    val prompt: String?,
    val options: List<String>,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val colorHex: String,
    val canReply: Boolean,
    val kind: String = "",          // "choice" (multiple-choice question), "input", or ""
    val multiSelect: Boolean = false,
    /** Mac time the agent's last turn genuinely finished; 0 while working or unknown. */
    val doneAtMs: Long = 0L
) {
    /** A waiting multiple-choice question whose options can be answered from the watch. */
    val isChoice: Boolean get() = isWaiting && kind == "choice" && options.isNotEmpty()

    val isWaiting: Boolean get() = status == "waiting_approval" || status == "waiting_input"
    val isError: Boolean get() = status == "error"
    val isRunning: Boolean get() = status == "running"

    val accentColor: Color
        get() = parseHexColor(colorHex, Color(0xFF8AB4F8))
}

data class QuotaItem(
    val id: String,
    val name: String,
    val colorHex: String,
    val remainingPct: Int,
    val resetShort: String,
    val resetClock: String,
    val windowLabel: String,
    val secondaryRemainingPct: Int?,
    val secondaryResetShort: String?,
    val plan: String,
    val windowMinutes: Int = 0,
    val resetsAtMs: Long = 0L,
    val secondaryWindowMinutes: Int = 0,
    val secondaryResetsAtMs: Long = 0L
) {
    val accentColor: Color
        get() = parseHexColor(colorHex, Color(0xFF81C995))

    /** Where the bar would sit if usage were spread evenly over the window (remaining %). */
    fun paceRemainingPct(nowMs: Long): Float? = paceFor(windowMinutes, resetsAtMs, nowMs)

    /** Positive = in reserve (used less than pace), negative = in deficit. Whole percent. */
    fun paceDelta(nowMs: Long): Int? = paceRemainingPct(nowMs)?.let { Math.round(remainingPct - it) }

    fun secondaryPaceRemainingPct(nowMs: Long): Float? = paceFor(secondaryWindowMinutes, secondaryResetsAtMs, nowMs)

    fun secondaryPaceDelta(nowMs: Long): Int? {
        val rem = secondaryRemainingPct ?: return null
        return secondaryPaceRemainingPct(nowMs)?.let { Math.round(rem - it) }
    }
}

private fun paceFor(windowMinutes: Int, resetsAtMs: Long, nowMs: Long): Float? {
    if (windowMinutes <= 0 || resetsAtMs <= 0L) return null
    val windowMs = windowMinutes * 60_000L
    val leftMs = (resetsAtMs - nowMs).coerceIn(0L, windowMs)
    return leftMs * 100f / windowMs
}

data class HudSnapshot(
    val seq: Long = 0,
    val serverTimeMs: Long = 0,
    val macHost: String = "",
    val activeCount: Int = 0,
    val errorCount: Int = 0,
    val approvalQueue: List<String> = emptyList(),
    val threads: List<AgentThread> = emptyList(),
    val quotas: List<QuotaItem> = emptyList()
)

fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        val clean = hex.removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color((0xFF000000L or value).toInt())
            8 -> Color(value.toInt())
            else -> fallback
        }
    } catch (_: Throwable) {
        fallback
    }
}
