package com.agenthud.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.agenthud.watch.data.LiveState

sealed class LiveVoiceState {
    data object Idle : LiveVoiceState()
    data class Listening(val amplitude: Float, val seconds: Int) : LiveVoiceState()
    data class Sending(val targetApp: String) : LiveVoiceState()
    data class Sent(val targetApp: String, val text: String, val pastedOnly: Boolean = false) : LiveVoiceState()
    data class Error(val message: String) : LiveVoiceState()
}

/**
 * Live Mode screen: follows Agent HUD's Pulse/ProviderStyle visual grammar (pure black OLED,
 * 408px-scaled perimeter track, real provider marks & drifting Gemini gradient, CurvedTopLabel,
 * zero emojis, generous circular safe padding).
 *
 * - Rotating crown steps the focused Mac app's thinking level (segmented outer dial).
 * - Single crown click starts watch-mic listening; second click stops and auto-sends.
 */
@Composable
fun LiveModeScreen(
    liveState: LiveState,
    voiceState: LiveVoiceState,
    previewIndex: Int? = null,
    stepInFlight: Boolean,
    waitingCount: Int,
    voiceTriggerKey: String = "off",
    onToggleVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onDismissError: () -> Unit,
    onOpenWaiting: () -> Unit,
    onExitLive: () -> Unit
) {
    val style = providerStyle(liveState.provider.ifBlank { liveState.appName }, ColorBlue)
    val shownIdx = (previewIndex ?: liveState.index).let { idx ->
        if (liveState.levels.isNotEmpty()) idx.coerceIn(-1, liveState.levels.lastIndex) else idx
    }
    val shownLevel = liveState.levels.getOrNull(shownIdx) ?: liveState.level
    val isPending = previewIndex != null && previewIndex != liveState.index
    val animateDial = liveState.dialSupported && (style.stops != null || stepInFlight || isPending)
    val clock = rememberAnimClock(animateDial, fps = 15)

    val displayApp = liveState.appName
        .removeSuffix(" (Beta)")
        .ifBlank { "Mac" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (voiceState) {
            is LiveVoiceState.Listening -> {
                val sweep = (60f + voiceState.amplitude * 290f).coerceIn(60f, 355f)
                PerimeterArc(sweepDegrees = sweep, color = ColorBlue, strokeDp = 5f)
                CurvedTopLabel(
                    text = "LISTENING",
                    color = ColorBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    logoKey = liveState.provider.ifBlank { null }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onToggleVoice() }
                        .padding(horizontal = 30.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(Modifier.height(14.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size((46 + (voiceState.amplitude * 10)).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF162C46)),
                            contentAlignment = Alignment.Center
                        ) {
                            MicIcon(color = ColorBlue, size = 20.dp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "0:${voiceState.seconds.toString().padStart(2, '0')}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF4F4F5)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (voiceTriggerKey) {
                                "send" -> "Say “send” or click"
                                "go" -> "Say “go” or click"
                                "okay" -> "Say “okay” or click"
                                "all" -> "Say send/go/ok or click"
                                else -> "Click crown to send"
                            },
                            fontSize = 11.sp,
                            color = ColorMuted,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "Cancel",
                        fontSize = 11.sp,
                        color = ColorMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onCancelVoice() }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            is LiveVoiceState.Sending -> {
                OrbitingArc(color = ColorBlue, stalled = false, strokeDp = 4.5f, fps = 30)
                CurvedTopLabel(
                    text = "SENDING",
                    color = ColorBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    logoKey = liveState.provider.ifBlank { null }
                )
                Column(
                    modifier = Modifier.padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Sending…",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFF4F4F5)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = voiceState.targetApp.removeSuffix(" (Beta)"),
                        fontSize = 12.sp,
                        color = ColorMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            is LiveVoiceState.Sent -> {
                // Pasted-only: the front app is not an agent chat, so Return was left for the person.
                val tone = if (voiceState.pastedOnly) SignalAmber else ColorGreen
                PerimeterArc(sweepDegrees = 359f, color = tone, strokeDp = 4.5f)
                CurvedTopLabel(
                    text = if (voiceState.pastedOnly) "PASTED" else "SENT",
                    color = tone,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    logoKey = liveState.provider.ifBlank { null }
                )
                Column(
                    modifier = Modifier.padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CheckIcon(color = tone, size = 20.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = voiceState.targetApp.removeSuffix(" (Beta)"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF4F4F5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (voiceState.pastedOnly) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Press Return on the Mac",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = SignalAmber,
                            maxLines = 1
                        )
                    }
                    if (voiceState.text.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "“${voiceState.text}”",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = ColorMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            is LiveVoiceState.Error -> {
                PerimeterArc(sweepDegrees = 359f, color = ColorRed, strokeDp = 4.5f)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onDismissError() }
                        .padding(horizontal = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Live Mode",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ColorRed
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = voiceState.message,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = Color(0xFFDADCE0),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = "Tap to dismiss", fontSize = 10.sp, color = Color(0xFF5F6368))
                }
            }

            LiveVoiceState.Idle -> {
                // Outer thinking-level segmented dial (same 408px geometry as Pulse in HomeLayouts.kt)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val t = clock.value
                    val k = size.minDimension / 408f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = 190f * k
                    val w = 5f * k

                    // Base ring track
                    drawCircle(Color(0xFF15171B), radius = r, center = c, style = Stroke(w))

                    val count = liveState.levels.size
                    if (liveState.dialSupported && count > 0) {
                        // 252° symmetrical top gauge arc (234° clockwise through 0°/360° to 126°)
                        val startDeg = 234f
                        val totalSpan = 252f
                        val gap = if (count > 1) 7f else 0f
                        val segDeg = (totalSpan - gap * (count - 1)) / count
                        val activeIdx = shownIdx.coerceIn(-1, count - 1)

                        for (i in 0 until count) {
                            val a0 = startDeg + i * (segDeg + gap)
                            val a1 = a0 + segDeg
                            val lit = i <= activeIdx
                            if (lit) {
                                val alpha = if ((stepInFlight || isPending) && i == activeIdx) 0.72f else 1f
                                drawStyledArc(c, r, a0, a1, w, style, t, alpha = alpha)
                                if ((stepInFlight || isPending) && i == activeIdx) {
                                    drawSweep(c, r, a0, a1, w, t, 0f)
                                }
                            } else {
                                drawArc(
                                    color = IdleSegment,
                                    startAngle = a0 - 90f,
                                    sweepAngle = a1 - a0,
                                    useCenter = false,
                                    topLeft = Offset(c.x - r, c.y - r),
                                    size = Size(r * 2, r * 2),
                                    style = Stroke(w, cap = StrokeCap.Round)
                                )
                            }
                        }
                    } else {
                        // Voice-only focused app: calm minimal accent arc at top
                        drawArc(
                            color = ColorBlue.copy(alpha = 0.45f),
                            startAngle = -114f,
                            sweepAngle = 48f,
                            useCenter = false,
                            topLeft = Offset(c.x - r, c.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(w, cap = StrokeCap.Round)
                        )
                    }
                }

                // Curved top label with provider logo (identical to DoneAlert / AgentFocus)
                val hasLogo = liveState.provider.isNotBlank() && providerLogoRes(liveState.provider) != null
                CurvedTopLabel(
                    text = if (hasLogo) "LIVE" else "LIVE · ${displayApp.uppercase().take(12)}",
                    color = if (liveState.dialSupported) style.accent else ColorMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    logoKey = if (hasLogo) liveState.provider else null
                )

                // Center readout + bottom pill inside strict circular safe margins
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(Modifier.height(14.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onToggleVoice() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (liveState.dialSupported && shownLevel.isNotBlank()) {
                            Text(
                                text = shownLevel,
                                fontSize = 26.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF4F4F5),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = if (stepInFlight) "Setting…" else displayApp,
                                fontSize = 11.sp,
                                color = if (stepInFlight || isPending) style.accent else Color(0xFF9A9EA8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                MicIcon(color = ColorMuted, size = 11.dp)
                                Text(
                                    text = "Turn · Click to speak",
                                    fontSize = 10.sp,
                                    color = Color(0xFF6B6F78),
                                    maxLines = 1
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16181C)),
                                contentAlignment = Alignment.Center
                            ) {
                                MicIcon(color = ColorBlue, size = 17.dp)
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = displayApp,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF4F4F5),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Click crown to speak",
                                fontSize = 11.sp,
                                color = Color(0xFF8B8F99),
                                maxLines = 1
                            )
                        }
                    }

                    // Bottom safe-chord pill: non-intrusive waiting alert badge OR subtle Exit pill
                    if (waitingCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF2B2212))
                                .clickable { onOpenWaiting() }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "$waitingCount waiting",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SignalAmber,
                                maxLines = 1
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF16181C))
                                .clickable { onExitLive() }
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Exit",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = ColorMuted,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
