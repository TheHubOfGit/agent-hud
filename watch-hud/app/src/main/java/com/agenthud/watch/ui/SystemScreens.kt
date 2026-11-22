package com.agenthud.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.agenthud.watch.data.AgentThread
import com.agenthud.watch.data.HapticEvent
import com.agenthud.watch.data.HomeLayout
import com.agenthud.watch.data.PrefsStore
import com.agenthud.watch.service.HapticsEngine
import kotlinx.coroutines.delay

@Composable
fun QuickControlsOverlay(
    vibrationLabel: String,
    onOpenVibration: () -> Unit,
    quotaRings: Boolean,
    onToggleQuotaStyle: () -> Unit,
    screenModeLabel: String,
    onCycleScreenMode: () -> Unit,
    onOpenWatchFace: () -> Unit,
    onOpenWatchSettings: () -> Unit,
    onOpenWatchApps: () -> Unit,
    currentLayout: HomeLayout,
    nightMode: Boolean,
    isMuted: Boolean,
    brightnessLevel: Float,
    batteryPct: Int,
    batteryTempC: Float,
    onSelectLayout: (HomeLayout) -> Unit,
    onToggleNight: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleBrightness: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    liveMode: Boolean = false,
    onToggleLiveMode: () -> Unit = {},
    voiceTriggerLabel: String = "Off",
    onCycleVoiceTrigger: () -> Unit = {},
    verticalPages: Boolean = false,
    onTogglePages: () -> Unit = {},
    /** Shown as a page of the vertical pager rather than as a pop-up over the HUD. */
    asPage: Boolean = false,
    pageActive: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val controlsScroll = rememberScrollState()
        // As a pop-up the crown only scrolls it; as a page it scrolls, then moves to the next page.
        CrownTarget(controlsScroll, active = !asPage || pageActive, modal = !asPage)
        // Generous circular safe margins so cards never clip the round screen edge
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(controlsScroll)
                .padding(horizontal = 30.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "CONTROLS",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = ColorMuted,
                modifier = Modifier.clickable { onDismiss() }
            )

            // Chooseable Home Layouts: A (Pulse - default), B (Orbit), C (Zen)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                HomeLayout.entries.forEach { layout ->
                    val selected = layout == currentLayout
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(0xFF162C46) else Color(0xFF16181C))
                            .clickable { onSelectLayout(layout) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = layout.label.substringBefore(" "),
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) ColorBlue else Color.White,
                            maxLines = 1
                        )
                    }
                }
            }

            // Live Mode: turns the watch into a live Mac macro dial + voice auto-send puck
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (liveMode) Color(0xFF162C46) else Color(0xFF16181C))
                    .clickable { onToggleLiveMode() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    DialIcon(color = if (liveMode) ColorBlue else Color(0xFFDADCE0), size = 14.dp)
                    Text(
                        text = "Live Mode",
                        fontSize = 12.sp,
                        color = if (liveMode) ColorBlue else Color(0xFFDADCE0),
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = if (liveMode) "On" else "Off",
                    fontSize = 11.sp,
                    color = if (liveMode) ColorBlue else ColorMuted,
                    fontWeight = if (liveMode) FontWeight.SemiBold else FontWeight.Normal
                )
            }

            // Optional end-of-dictation trigger word for Live Mode (default Off: click to start & click to send)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16181C))
                    .clickable { onCycleVoiceTrigger() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Voice send", fontSize = 12.sp, color = Color(0xFFDADCE0), fontWeight = FontWeight.Medium)
                Text(
                    text = voiceTriggerLabel,
                    fontSize = 11.sp,
                    color = if (voiceTriggerLabel == "Off") ColorMuted else ColorBlue,
                    maxLines = 1
                )
            }

            // 2x2 Vector Icon Action Tiles (zero emojis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlTile(
                    modifier = Modifier.weight(1f),
                    label = if (isMuted) "Muted" else "Mute",
                    active = isMuted,
                    onClick = onToggleMute,
                    icon = { c -> BellIcon(color = c, muted = isMuted) }
                )
                ControlTile(
                    modifier = Modifier.weight(1f),
                    label = "Night",
                    active = nightMode,
                    onClick = onToggleNight,
                    icon = { c -> MoonIcon(color = c) }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlTile(
                    modifier = Modifier.weight(1f),
                    label = "${(brightnessLevel * 100).toInt()}%",
                    active = false,
                    onClick = onCycleBrightness,
                    icon = { c -> SunIcon(color = c) }
                )
                ControlTile(
                    modifier = Modifier.weight(1f),
                    label = "Sync",
                    active = false,
                    onClick = {
                        onReconnect()
                        onDismiss()
                    },
                    icon = { c -> RefreshIcon(color = c) }
                )
            }

            // Keep the screen on whenever the HUD is open, or only while charging.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16181C))
                    .clickable { onCycleScreenMode() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Screen", fontSize = 12.sp, color = Color(0xFFDADCE0), fontWeight = FontWeight.Medium)
                Text(text = screenModeLabel.replace("Off charger: ", "Auto-off ").replace(" min", "m"), fontSize = 11.sp, color = ColorBlue, maxLines = 1)
            }

            // Quota page style: bars (default) or concentric rings.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16181C))
                    .clickable { onToggleQuotaStyle() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Quota view", fontSize = 12.sp, color = Color(0xFFDADCE0), fontWeight = FontWeight.Medium)
                Text(text = if (quotaRings) "Rings" else "Bars", fontSize = 11.sp, color = ColorBlue)
            }

            // Page layout: side to side (default) or vertical (Controls, Quotas, Home, agents).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16181C))
                    .clickable { onTogglePages() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Pages", fontSize = 12.sp, color = Color(0xFFDADCE0), fontWeight = FontWeight.Medium)
                Text(text = if (verticalPages) "Vertical" else "Side", fontSize = 11.sp, color = ColorBlue)
            }

            // Opens the full Vibration settings screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16181C))
                    .clickable { onOpenVibration() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Alerts", fontSize = 12.sp, color = Color(0xFFDADCE0), fontWeight = FontWeight.Medium)
                Text(text = vibrationLabel, fontSize = 11.sp, color = ColorMuted)
            }

            // The HUD is the home app, so these are the way back to the stock watch.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Face" to onOpenWatchFace, "Apps" to onOpenWatchApps, "Settings" to onOpenWatchSettings).forEach { (label, action) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF16181C))
                            .clickable { action() }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = label, fontSize = 11.sp, color = Color(0xFFDADCE0), maxLines = 1)
                    }
                }
            }

            Text(
                text = "Batt $batteryPct% · ${"%.1f".format(batteryTempC)}°C",
                fontSize = 10.sp,
                color = ColorGreen
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF202124))
                    .clickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 5.dp)
            ) {
                Text(text = "Done", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ControlTile(
    modifier: Modifier,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    val fg = if (active) ColorBlue else Color(0xFFDADCE0)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Color(0xFF162C46) else Color(0xFF16181C))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon(fg)
        Spacer(Modifier.height(4.dp))
        Text(text = label, fontSize = 10.sp, color = fg, maxLines = 1)
    }
}

@Composable
fun NightOledScreen(
    clockText: String,
    summaryText: String,
    summaryColor: Color,
    onWake: () -> Unit
) {
    val offsets = remember { listOf(0 to 0, 3 to 2, -3 to 3, -2 to -3, 4 to -2) }
    var shiftIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60_000L)
            shiftIdx = (shiftIdx + 1) % offsets.size
        }
    }
    val (dx, dy) = offsets[shiftIdx]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onWake() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.offset(x = dx.dp, y = dy.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = clockText,
                fontSize = 46.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF9AA0A6)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = summaryText,
                fontSize = 12.sp,
                color = summaryColor.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
fun OfflineOrDiscoverScreen(
    isDiscovering: Boolean,
    clockText: String,
    lastSeenText: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onRetry() },
        contentAlignment = Alignment.Center
    ) {
        PerimeterArc(
            sweepDegrees = if (isDiscovering) 95f else 35f,
            color = if (isDiscovering) ColorBlue else Color(0xFF3F3F46),
            strokeDp = 3.5f
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isDiscovering) "AGENT HUD" else "OFFLINE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = if (isDiscovering) ColorBlue else ColorMuted
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = clockText, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDADCE0))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isDiscovering) "Finding Mac…" else "Waiting for Mac",
                    fontSize = 12.sp,
                    color = ColorMuted,
                    textAlign = TextAlign.Center
                )
                if (lastSeenText.isNotBlank()) {
                    Text(text = lastSeenText, fontSize = 10.sp, color = Color(0xFF5F6368))
                }
            }
            Text(text = "Tap to retry", fontSize = 10.sp, color = Color(0xFF5F6368))
        }
    }
}

val VIBRATION_STRENGTHS = listOf("Off", "Light", "Medium", "Strong")

/** Vibration settings: strength, which events buzz, a waiting reminder, and quiet hours. */
@Composable
fun VibrationSettingsScreen(
    prefs: PrefsStore,
    haptics: HapticsEngine,
    onDone: () -> Unit
) {
    var strength by remember { mutableIntStateOf(prefs.hapticStrength) }
    val enabled = remember { mutableStateMapOf<HapticEvent, Boolean>().apply { HapticEvent.entries.forEach { put(it, prefs.hapticEnabled(it)) } } }
    var reminder by remember { mutableIntStateOf(prefs.hapticReminderMins) }
    var quiet by remember { mutableIntStateOf(prefs.quietHoursPreset) }
    var doneAlert by remember { mutableStateOf(prefs.doneAlert) }
    val off = strength == 0

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CurvedTopLabel(text = "ALERTS & VIBRATION", color = ColorMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        val settingsScroll = rememberScrollState()
        CrownTarget(settingsScroll, modal = true)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 34.dp)
                .verticalScroll(settingsScroll)
                .padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Strength: tapping one plays a preview at that strength.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VIBRATION_STRENGTHS.forEachIndexed { i, label ->
                    val selected = i == strength
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(0xFF162C46) else Color(0xFF16181C))
                            .clickable {
                                strength = i
                                prefs.hapticStrength = i
                                haptics.preview(i)
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (label == "Medium") "Med" else label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) ColorBlue else Color.White,
                            maxLines = 1
                        )
                    }
                }
            }

            SectionLabel("When an agent starts")
            ToggleRow(
                label = "Vibrate",
                on = enabled[HapticEvent.STARTED] == true,
                dimmed = off,
                onToggle = {
                    val next = enabled[HapticEvent.STARTED] != true
                    enabled[HapticEvent.STARTED] = next
                    prefs.setHapticEnabled(HapticEvent.STARTED, next)
                }
            )

            SectionLabel("When done")
            ToggleRow(
                label = "Vibrate",
                on = enabled[HapticEvent.FINISHED] == true,
                dimmed = off,
                onToggle = {
                    val next = enabled[HapticEvent.FINISHED] != true
                    enabled[HapticEvent.FINISHED] = next
                    prefs.setHapticEnabled(HapticEvent.FINISHED, next)
                }
            )
            ToggleRow(
                label = "Show alert",
                on = doneAlert,
                dimmed = false,
                onToggle = {
                    doneAlert = !doneAlert
                    prefs.doneAlert = doneAlert
                }
            )

            SectionLabel("Buzz when")
            HapticEvent.entries.filter { it != HapticEvent.FINISHED && it != HapticEvent.STARTED }.forEach { ev ->
                ToggleRow(
                    label = ev.label,
                    on = enabled[ev] == true,
                    dimmed = off,
                    onToggle = {
                        val next = enabled[ev] != true
                        enabled[ev] = next
                        prefs.setHapticEnabled(ev, next)
                    }
                )
            }

            SectionLabel("Timing")
            CycleRow(
                label = "Reminders",
                value = if (reminder == 0) "Off" else "Every ${reminder}m",
                dimmed = off,
                onTap = {
                    val list = PrefsStore.REMINDER_MINS
                    reminder = list[(list.indexOf(reminder) + 1) % list.size]
                    prefs.hapticReminderMins = reminder
                }
            )
            CycleRow(
                label = "Quiet hours",
                value = PrefsStore.QUIET_HOURS[quiet].first,
                dimmed = off,
                onTap = {
                    quiet = (quiet + 1) % PrefsStore.QUIET_HOURS.size
                    prefs.quietHoursPreset = quiet
                }
            )
            Text(
                text = "Quiet hours silence everything except your own taps, and hold done alerts.",
                fontSize = 10.sp,
                color = Color(0xFF5F6368),
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF202124))
                    .clickable { onDone() }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(text = "Done", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = ColorMuted,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun ToggleRow(label: String, on: Boolean, dimmed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16181C))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = if (dimmed) Color(0xFF5F6368) else Color(0xFFDADCE0))
        // Small switch: track plus knob.
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(if (on && !dimmed) Color(0xFF1E4D7A) else Color(0xFF2A2C31)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (on && !dimmed) ColorBlue else Color(0xFF70757A))
            )
        }
    }
}

@Composable
private fun CycleRow(label: String, value: String, dimmed: Boolean, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16181C))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = if (dimmed) Color(0xFF5F6368) else Color(0xFFDADCE0), maxLines = 1)
        Text(text = value, fontSize = 11.sp, color = if (dimmed) Color(0xFF5F6368) else ColorBlue, maxLines = 1)
    }
}

/**
 * Full-screen "done" card shown when an agent finishes its turn (if enabled). Calm solid ring,
 * no glow. Tap Open to jump to that agent; tap anywhere else to dismiss. Auto-hides after 15s.
 */
@Composable
fun DoneAlertScreen(
    thread: AgentThread,
    nowMs: Long,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val took = (thread.doneAtMs - thread.startedAtMs).takeIf { it in 5_000L..(12 * 3600_000L) }
    val ago = ((nowMs - thread.doneAtMs) / 1000L).coerceAtLeast(0L)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        PerimeterArc(sweepDegrees = 360f, color = ColorGreen, strokeDp = 4f)
        CurvedTopLabel(
            text = if (providerLogoRes(thread.provider) != null) "DONE" else "DONE · ${thread.providerLabel.uppercase()}",
            color = ColorGreen,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            logoKey = thread.provider
        )
        Column(
            modifier = Modifier.padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = thread.title,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = thread.step,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = Color(0xFFBDC1C6),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (took != null) append("Took ${formatDuration(took)}")
                    if (ago >= 60) { if (isNotEmpty()) append(" · "); append("${ago / 60}m ago") }
                },
                fontSize = 11.sp,
                color = Color(0xFF70757A)
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF1E3A2A))
                    .clickable { onOpen() }
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CheckIcon(color = ColorGreen, size = 12.dp)
                    Text(text = "Open", fontSize = 12.sp, color = ColorGreen, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec % 60}s"
        else -> "${sec}s"
    }
}
