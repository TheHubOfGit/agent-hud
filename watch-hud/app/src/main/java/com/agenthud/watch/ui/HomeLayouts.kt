package com.agenthud.watch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.agenthud.watch.data.AgentThread
import com.agenthud.watch.data.HomeLayout
import com.agenthud.watch.data.HudSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput

val ColorBlue = Color(0xFF8AB4F8)
val ColorGreen = Color(0xFF81C995)
val ColorAmber = Color(0xFFFDD663)
val ColorRed = Color(0xFFF28B82)
val ColorPurple = Color(0xFFC58AF9)
val ColorMuted = Color(0xFF9AA0A6)
val ColorTrack = Color(0xFF16181C)

@Composable
fun HomeTile(
    layout: HomeLayout,
    snapshot: HudSnapshot,
    clockText: String,
    batteryPct: Int,
    onSelectThread: (Int) -> Unit
) {
    when (layout) {
        HomeLayout.PULSE -> HomeLayoutPulseA(snapshot, clockText, batteryPct, onSelectThread)
        HomeLayout.ORBIT -> HomeLayoutOrbitB(snapshot, clockText, batteryPct, onSelectThread)
        HomeLayout.ZEN -> HomeLayoutZenC(snapshot, clockText, batteryPct)
    }
}

/** A thread's place in the Orbit/Pulse design: its provider style and its state. */
private enum class MarkState { RUNNING, WAITING, ERROR, IDLE }

private fun AgentThread.markState(): MarkState = when {
    isWaiting -> MarkState.WAITING
    isError -> MarkState.ERROR
    isRunning -> MarkState.RUNNING
    else -> MarkState.IDLE
}

/** Center copy shared by both layouts. */
@Composable
private fun HomeCenterText(snapshot: HudSnapshot, clockText: String, pulseCount: Boolean) {
    val threads = snapshot.threads.take(6)
    val waiting = snapshot.threads.firstOrNull { it.id == snapshot.approvalQueue.firstOrNull() }
    val erroring = threads.firstOrNull { it.isError }
    val running = threads.filter { it.isRunning }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 44.dp)) {
        when {
            pulseCount && (running.isNotEmpty() || waiting != null) -> {
                val active = running.size + snapshot.approvalQueue.size
                Text(text = "$active", fontSize = 52.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF4F4F5), letterSpacing = (-2).sp)
                Text(
                    text = when {
                        waiting != null -> "${snapshot.approvalQueue.size} waiting on you"
                        running.size == 1 -> "agent working"
                        else -> "agents working"
                    },
                    fontSize = 13.sp,
                    color = if (waiting != null) SignalAmber else Color(0xFF9A9EA8)
                )
            }
            waiting != null -> {
                Text(text = clockText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8B8F99))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${waiting.providerLabel} needs you",
                    fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFFF4F4F5), textAlign = TextAlign.Center, maxLines = 2
                )
                Spacer(Modifier.height(4.dp))
                Text(text = "Tap to answer", fontSize = 12.sp, color = SignalAmber)
            }
            erroring != null -> {
                Text(text = clockText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8B8F99))
                Spacer(Modifier.height(4.dp))
                Text(text = "${erroring.providerLabel} hit an error", fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFFF4F4F5), textAlign = TextAlign.Center, maxLines = 2)
            }
            running.isNotEmpty() -> {
                Text(text = clockText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8B8F99))
                Spacer(Modifier.height(4.dp))
                Text(text = "${running.size} working", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF4F4F5))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = running.joinToString("  ·  ") { it.providerLabel.removeSuffix(" Code").removeSuffix(" Desktop") },
                    fontSize = 11.sp, color = Color(0xFF8B8F99), textAlign = TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> {
                Text(text = clockText, fontSize = 40.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB8BBC0), letterSpacing = (-1).sp)
                Text(text = "All quiet", fontSize = 12.sp, color = Color(0xFF6B6F78))
            }
        }
    }
}

/**
 * Pulse: one segment per agent around the edge. Working segments wear the provider's color (a
 * drifting Gemini gradient for Antigravity) with a soft sweep; waiting/error segments thicken
 * and raise an aurora; idle segments go gray. The provider's logo sits inside its segment.
 */
@Composable
private fun HomeLayoutPulseA(
    snapshot: HudSnapshot,
    clockText: String,
    @Suppress("UNUSED_PARAMETER") batteryPct: Int,
    onSelectThread: (Int) -> Unit
) {
    val threads = snapshot.threads.take(6)
    val styles = threads.map { providerStyle(it.provider, it.accentColor) }
    val marks = rememberProviderMarks()
    val anyActive = threads.any { it.markState() != MarkState.IDLE }
    val clock = rememberAnimClock(anyActive)
    val onSelect by rememberUpdatedState(onSelectThread)
    val waitingIdx = snapshot.threads.indexOfFirst { it.id == snapshot.approvalQueue.firstOrNull() }
    val count = threads.size
    val segDeg = if (count == 0) 360f else 360f / count
    val startDeg = 30f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(count, waitingIdx) {
                detectTapGestures { pos ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val dist = (pos - c).getDistance()
                    if (count > 0 && dist >= size.width / 2f - 34.dp.toPx()) {
                        // Outer band: open the segment that was tapped.
                        var deg = Math.toDegrees(kotlin.math.atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())).toFloat() + 90f
                        deg = ((deg - startDeg) % 360f + 360f) % 360f
                        onSelect((deg / segDeg).toInt().coerceIn(0, count - 1))
                    } else if (waitingIdx >= 0) {
                        onSelect(waitingIdx)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = clock.value
            val k = size.minDimension / 408f
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = 190f * k
            val w = 5f * k
            drawCircle(Color(0xFF15171B), radius = r, center = c, style = Stroke(w))
            val gap = if (count > 1) 4f else 0f
            threads.forEachIndexed { i, th ->
                val st = styles[i]
                val a0 = startDeg + i * segDeg + gap / 2f
                val a1 = a0 + segDeg - gap
                when (th.markState()) {
                    MarkState.WAITING -> drawAurora(c, r, a0, a1, t, w + 3.5f * k, 54f * k, k, AuroraAmber)
                    MarkState.ERROR -> drawAurora(c, r, a0, a1, t, w + 3.5f * k, 54f * k, k, AuroraRed)
                    MarkState.RUNNING -> {
                        drawStyledArc(c, r, a0, a1, w, st, t)
                        drawSweep(c, r, a0, a1, w, t, i * 0.31f)
                    }
                    MarkState.IDLE -> drawArc(
                        color = IdleSegment, startAngle = a0 - 90f, sweepAngle = a1 - a0, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                        style = Stroke(w, cap = StrokeCap.Round)
                    )
                }
                val bmp = st.markRes?.let { marks[it] }
                if (bmp != null) {
                    drawMark(bmp, polar(c, r - 26f * k, (a0 + a1) / 2f), 18f * k, if (th.markState() == MarkState.IDLE) 0.4f else 1f)
                }
            }
        }
        HomeCenterText(snapshot, clockText, pulseCount = true)
    }
}

/**
 * Orbit: each agent's real logo travels a hairline track. Working marks leave a fading trail
 * and carry a small satellite dot; a waiting/error mark raises an aurora from the track around
 * it; idle marks sit still at reduced opacity. The orbit turns only while something is active.
 */
@Composable
private fun HomeLayoutOrbitB(
    snapshot: HudSnapshot,
    clockText: String,
    @Suppress("UNUSED_PARAMETER") batteryPct: Int,
    onSelectThread: (Int) -> Unit
) {
    val threads = snapshot.threads.take(6)
    val styles = threads.map { providerStyle(it.provider, it.accentColor) }
    val marks = rememberProviderMarks()
    val anyActive = threads.any { it.markState() != MarkState.IDLE }
    val clock = rememberAnimClock(anyActive)
    val onSelect by rememberUpdatedState(onSelectThread)
    val waitingIdx = snapshot.threads.indexOfFirst { it.id == snapshot.approvalQueue.firstOrNull() }
    val count = threads.size
    val lapSeconds = 40f
    fun markAngle(i: Int, t: Float): Float =
        (if (anyActive) t / lapSeconds * 360f else 0f) + i * (360f / count.coerceAtLeast(1)) + 18f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(count, waitingIdx, anyActive) {
                detectTapGestures { pos ->
                    if (count == 0) return@detectTapGestures
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val k = size.width / 408f
                    val t = clock.value
                    // Hit-test each mark where it is right now (generous 26dp radius).
                    val hit = (0 until count).minBy { i -> (polar(c, 168f * k, markAngle(i, t)) - pos).getDistance() }
                    when {
                        (polar(c, 168f * k, markAngle(hit, t)) - pos).getDistance() < 26.dp.toPx() -> onSelect(hit)
                        waitingIdx >= 0 -> onSelect(waitingIdx)
                        else -> onSelect(0)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = clock.value
            val k = size.minDimension / 408f
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = 168f * k
            drawCircle(Color.White.copy(alpha = 0.07f), radius = r, center = c, style = Stroke(1.5f * k))
            threads.forEachIndexed { i, th ->
                val st = styles[i]
                val ang = markAngle(i, t)
                val state = th.markState()
                var pulse = 0f
                when (state) {
                    MarkState.RUNNING -> drawFadingTrail(c, r, ang, st, t, k)
                    MarkState.WAITING -> pulse = drawAurora(c, r, ang - 56f, ang + 56f, t, 4.5f * k, 46f * k, k, AuroraAmber)
                    MarkState.ERROR -> pulse = drawAurora(c, r, ang - 56f, ang + 56f, t, 4.5f * k, 46f * k, k, AuroraRed)
                    MarkState.IDLE -> Unit
                }
                // Mark tile: black disc, hairline outline (amber/red when blocked), the real logo.
                val p = polar(c, r, ang)
                val mr = 19f * k
                val dim = if (state == MarkState.IDLE) 0.4f else 1f
                drawCircle(Color(0xFF0A0B0D).copy(alpha = dim), radius = mr, center = p)
                val (outline, ow) = when (state) {
                    MarkState.WAITING -> SignalAmber.copy(alpha = 0.75f + 0.25f * pulse) to 1.75f * k
                    MarkState.ERROR -> AuroraRed.line.copy(alpha = 0.75f + 0.25f * pulse) to 1.75f * k
                    else -> Color.White.copy(alpha = 0.12f * dim) to 1f * k
                }
                drawCircle(outline, radius = mr - ow / 2f, center = p, style = Stroke(ow))
                val bmp = st.markRes?.let { marks[it] }
                if (bmp != null) drawMark(bmp, p, 22f * k, dim)
                else drawCircle(st.accent.copy(alpha = dim), radius = 7f * k, center = p)
                if (state == MarkState.RUNNING) drawSatellite(p, mr, st, t, i, k)
            }
        }
        HomeCenterText(snapshot, clockText, pulseCount = false)
    }
}

/**
 * Concept C: Typographic Zen — zero widgets, oversized Google Sans typography + subtle ambient
 * edge glow for maximum 5-foot desk legibility.
 */
@Composable
private fun HomeLayoutZenC(
    snapshot: HudSnapshot,
    clockText: String,
    batteryPct: Int
) {
    val waitingCount = snapshot.approvalQueue.size
    val hasError = snapshot.errorCount > 0
    val isIdle = snapshot.activeCount == 0 && waitingCount == 0 && !hasError

    val headline = when {
        waitingCount > 0 -> "Approve\n${snapshot.threads.firstOrNull { it.id == snapshot.approvalQueue.first() }?.providerLabel ?: "Agent"}"
        hasError -> "Build\nError"
        isIdle -> "All\nQuiet"
        snapshot.activeCount == 1 -> "1 Agent\nWorking"
        else -> "${snapshot.activeCount} Agents\nWorking"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = clockText, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ColorMuted)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = headline,
                    fontSize = 27.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (waitingCount > 0) ColorAmber else if (hasError) ColorRed else if (isIdle) ColorIdleText else Color.White,
                    textAlign = TextAlign.Center
                )
                if (!isIdle) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = (
                            snapshot.threads.firstOrNull { it.id == snapshot.approvalQueue.firstOrNull() }
                                ?: snapshot.threads.firstOrNull { it.isError }
                                ?: snapshot.threads.firstOrNull { it.isRunning }
                                ?: snapshot.threads.firstOrNull()
                            )?.title ?: "",
                        fontSize = 11.sp,
                        color = ColorMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = "← Quotas  ·  Agents →",
                fontSize = 10.sp,
                color = Color(0xFF5F6368)
            )
        }
    }
}

@Composable
fun PerimeterArc(
    sweepDegrees: Float,
    color: Color,
    trackColor: Color = ColorTrack,
    strokeDp: Float = 5f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = strokeDp.dp.toPx()
        val inset = stroke / 2f + 3.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = sweepDegrees.coerceIn(6f, 359f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
