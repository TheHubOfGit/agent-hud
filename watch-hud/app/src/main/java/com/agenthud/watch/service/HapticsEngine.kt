package com.agenthud.watch.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.agenthud.watch.data.HapticEvent
import com.agenthud.watch.data.PrefsStore

/**
 * Every buzz goes through [play], which applies the user's Vibration settings: per-event
 * switches, overall strength, and quiet hours (which silence everything except button taps).
 */
class HapticsEngine(context: Context) {
    private val prefs = PrefsStore(context)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 2 crisp pulses: an agent needs approval or an answer. */
    fun buzzApproval() = play(HapticEvent.NEEDS_YOU, longArrayOf(0, 85, 65, 130), intArrayOf(0, 220, 0, 255))

    /** Subtle tick: another request queued behind the one on screen. */
    fun buzzQueuedTick() = play(HapticEvent.MORE_WAITING, longArrayOf(0, 40), intArrayOf(0, 150))

    /** 3 sharp ticks: an agent hit an error. */
    fun buzzError() = play(HapticEvent.ERROR, longArrayOf(0, 60, 50, 60, 50, 80), intArrayOf(0, 240, 0, 240, 0, 255))

    /** Two quick light taps: an agent started working. */
    fun buzzStarted() = play(HapticEvent.STARTED, longArrayOf(0, 30, 70, 30), intArrayOf(0, 140, 0, 140))

    /** One soft, longer pulse: an agent finished its turn. */
    fun buzzFinished() = play(HapticEvent.FINISHED, longArrayOf(0, 140), intArrayOf(0, 160))

    /** Soft confirm: the waiting queue emptied. */
    fun buzzCleared() = play(HapticEvent.CLEARED, longArrayOf(0, 45), intArrayOf(0, 130))

    /** Feedback for the user's own taps (send, approve, voice start). */
    fun buzzSoftConfirm() = play(HapticEvent.TAPS, longArrayOf(0, 45), intArrayOf(0, 130))
    fun tapTick() = play(HapticEvent.TAPS, longArrayOf(0, 40), intArrayOf(0, 150))

    /** Live Mode: immediate crisp detent tick per level crossed while turning the crown, scaled by level. */
    fun buzzDialDetent(index: Int, total: Int) {
        val frac = if (total > 1) index.coerceIn(0, total - 1).toFloat() / (total - 1) else 0.5f
        val amp = (115 + (frac * 125).toInt()).coerceIn(100, 240)
        val dur = (15L + (frac * 15).toLong())
        play(HapticEvent.TAPS, longArrayOf(0, dur), intArrayOf(0, amp))
    }

    /** Live Mode: crisp lock-in confirmation pulse once the Mac commits the final thinking level. */
    fun buzzLevelConfirmed(index: Int, total: Int) {
        val frac = if (total > 1) index.coerceIn(0, total - 1).toFloat() / (total - 1) else 0.5f
        val amp = (145 + (frac * 105).toInt()).coerceIn(130, 255)
        play(HapticEvent.TAPS, longArrayOf(0, 34), intArrayOf(0, amp))
    }

    /** Live Mode: soft double micro-bump when already at lowest or highest thinking level. */
    fun buzzBoundaryBump() = play(HapticEvent.TAPS, longArrayOf(0, 18, 38, 18), intArrayOf(0, 110, 0, 110))

    /** Live Mode: crisp rising two-tick when single crown click starts watch-mic listening. */
    fun buzzVoiceStart() = play(HapticEvent.TAPS, longArrayOf(0, 22, 34, 34), intArrayOf(0, 130, 0, 210))

    /** Plays the "needs you" pattern at [strength] regardless of switches, for settings previews. */
    fun preview(strength: Int) = vibrate(longArrayOf(0, 85, 65, 130), intArrayOf(0, 220, 0, 255), strength)

    private fun play(event: HapticEvent, timings: LongArray, amplitudes: IntArray) {
        if (!prefs.hapticEnabled(event)) return
        if (event != HapticEvent.TAPS && prefs.inQuietHours()) return
        vibrate(timings, amplitudes, prefs.hapticStrength)
    }

    private fun vibrate(timings: LongArray, amplitudes: IntArray, strength: Int) {
        val scale = when (strength) {
            0 -> return
            1 -> 0.45f
            2 -> 0.75f
            else -> 1f
        }
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val scaled = IntArray(amplitudes.size) { i ->
            if (amplitudes[i] == 0) 0 else (amplitudes[i] * scale).toInt().coerceIn(1, 255)
        }
        try {
            v.vibrate(VibrationEffect.createWaveform(timings, scaled, -1))
        } catch (_: Throwable) {
        }
    }
}
