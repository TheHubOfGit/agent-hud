package com.agenthud.watch.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.agenthud.watch.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Provider style for the Orbit and Pulse home layouts (the "Provider Marks Study" direction):
 * one identity per provider, the real logo as its mark, color only for working agents, amber
 * aurora for waiting, gray for idle. All geometry is authored on a 408 px canvas (the watch's
 * native size) and scaled by `k`, so it matches the study exactly.
 */

val SignalAmber = Color(0xFFF2B441)
val IdleSegment = Color(0xFF2A2D33)

/** How a provider shows up: a flat accent, or a drifting gradient (Antigravity's Gemini look). */
data class ProviderStyle(
    val accent: Color,
    val stops: List<Color>? = null,
    @DrawableRes val markRes: Int? = null
) {
    /** Color at position [x] along the drifting gradient (or the flat accent). */
    fun colorAt(x: Float, alpha: Float = 1f): Color =
        stops?.let { stopColor(it, x, alpha) } ?: accent.copy(alpha = accent.alpha * alpha)
}

private val GEMINI = listOf(Color(0xFF217BFE), Color(0xFF078EFB), Color(0xFFAC87EB), Color(0xFFEE4D5D))

fun providerStyle(provider: String, fallback: Color): ProviderStyle {
    val k = provider.lowercase()
    return when {
        "claude" in k -> ProviderStyle(Color(0xFFD97757), markRes = R.drawable.logo_claude)
        "codex" in k -> ProviderStyle(Color(0xFF7A84FF), markRes = R.drawable.logo_codex_color)
        "antigravity" in k || "gemini" in k -> ProviderStyle(Color(0xFF3D8BFF), GEMINI, R.drawable.logo_antigravity)
        "t3" in k -> ProviderStyle(Color(0xFFD5D8DD), markRes = R.drawable.logo_t3code)
        else -> ProviderStyle(fallback)
    }
}

/** Mirrored position along [stops] so a drifting cycle has no seam. */
fun stopColor(stops: List<Color>, x: Float, alpha: Float = 1f): Color {
    val v = ((x % 1f) + 1f) % 1f
    val m = if (v < 0.5f) v * 2f else (1f - v) * 2f
    val f = m * (stops.size - 1)
    val i = min(stops.size - 2, floor(f).toInt())
    val t = f - i
    val a = stops[i]; val b = stops[i + 1]
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = alpha
    )
}

/** Loads every provider mark once per composition. */
@Composable
fun rememberProviderMarks(): Map<Int, ImageBitmap> {
    val ids = listOf(R.drawable.logo_claude, R.drawable.logo_codex_color, R.drawable.logo_antigravity, R.drawable.logo_t3code)
    return ids.associateWith { ImageBitmap.imageResource(it) }
}

/**
 * Seconds since the layout started animating, advancing every frame only while [active] (an agent
 * is working, waiting or erroring). Read it inside a draw lambda so only drawing re-runs.
 */
@Composable
fun rememberAnimClock(active: Boolean): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        val offset = clock.floatValue
        while (true) {
            withFrameNanos { now -> clock.floatValue = offset + (now - startNanos) / 1_000_000_000f }
        }
    }
    return clock
}

/** Degrees measured clockwise from 12 o'clock. */
fun polar(c: Offset, r: Float, deg: Float): Offset {
    val a = (deg - 90f) * PI.toFloat() / 180f
    return Offset(c.x + r * cos(a), c.y + r * sin(a))
}

private fun DrawScope.arcAt(color: Color, c: Offset, r: Float, a0: Float, sweep: Float, width: Float, cap: StrokeCap, blend: BlendMode = BlendMode.SrcOver) {
    drawArc(
        color = color,
        startAngle = a0 - 90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = width, cap = cap),
        blendMode = blend
    )
}

/** A segment in a flat color, or Antigravity's gradient drifting along it. */
fun DrawScope.drawStyledArc(c: Offset, r: Float, a0: Float, a1: Float, width: Float, style: ProviderStyle, t: Float, alpha: Float = 1f) {
    if (style.stops == null) {
        arcAt(style.accent.copy(alpha = alpha), c, r, a0, a1 - a0, width, StrokeCap.Round)
        return
    }
    val drift = t * 0.07f
    val step = 1.2f
    val n = ((a1 - a0) / step).toInt().coerceAtLeast(1)
    for (s in 0 until n) {
        val aa = a0 + s * step
        arcAt(style.colorAt(s / n.toFloat() * 0.85f + drift, alpha), c, r, aa, min(step + 0.4f, a1 - aa), width, StrokeCap.Butt)
    }
    // Rounded ends in the matching colors.
    for ((ae, u) in listOf(a0 to 0f, a1 to 1f)) {
        drawCircle(style.colorAt(u * 0.85f + drift, alpha), radius = width / 2f, center = polar(c, r, ae))
    }
}

/** Working (Pulse): a soft lighter band sweeps along the segment every few seconds. */
fun DrawScope.drawSweep(c: Offset, r: Float, a0: Float, a1: Float, width: Float, t: Float, phaseOffset: Float) {
    val period = 3.4f
    val ph = ((t / period + phaseOffset) % 1f + 1f) % 1f
    val center = a0 + (a1 - a0) * (ph * 1.3f - 0.15f)
    val band = 22f; val steps = 22
    for (s in 0 until steps) {
        val u = s / (steps - 1f)
        val aa = center - band / 2f + u * band
        if (aa < a0 + 1f || aa > a1 - 1f) continue
        val w = sin(PI.toFloat() * u)
        arcAt(Color.White.copy(alpha = 0.38f * w * w), c, r, aa, band / steps + 0.3f, width, StrokeCap.Butt)
    }
}

/** Working (Orbit): a short trail behind the mark that fades to nothing. */
fun DrawScope.drawFadingTrail(c: Offset, r: Float, markAngle: Float, style: ProviderStyle, t: Float, k: Float) {
    val len = 34f; val steps = 34
    for (s in 0 until steps) {
        val a0 = markAngle - 14f - len + (s / steps.toFloat()) * len
        val fade = (s / steps.toFloat()).pow(1.6f) * 0.9f
        arcAt(style.colorAt(s / steps.toFloat() * 0.75f + t * 0.05f, fade), c, r, a0, len / steps + 0.4f, 2f * k, StrokeCap.Butt)
    }
}

/** Working (Orbit): a small satellite dot circles the mark with a short fading tail. */
fun DrawScope.drawSatellite(mark: Offset, markRadius: Float, style: ProviderStyle, t: Float, index: Int, k: Float) {
    val sr = markRadius + 6.5f * k
    val sa = (t / 2.6f) * 2f * PI.toFloat() + index * 1.7f
    val tail = 6
    for (j in tail downTo 0) {
        val a = sa - j * 0.11f
        val alpha = if (j == 0) 1f else 0.45f * (1f - j / (tail + 1f)).pow(1.5f)
        val color = style.colorAt(t * 0.07f + index * 0.2f, alpha)
        drawCircle(color, radius = (if (j == 0) 2.4f else 1.8f) * k, center = Offset(mark.x + sr * cos(a), mark.y + sr * sin(a)))
    }
}

/** Colors for the waiting (amber) or error (red) aurora. */
data class SignalPalette(val line: Color, val edge: Color, val mid: Color, val tip: Color)

val AuroraAmber = SignalPalette(SignalAmber, Color(0xFFF2B441), Color(0xFFFF8048), Color(0xFFFF6E8C))
val AuroraRed = SignalPalette(Color(0xFFF28B82), Color(0xFFF06A5E), Color(0xFFFF7A6B), Color(0xFFFF8FB0))

/**
 * Waiting / error: an aurora. Fine curtains of light rise from the arc toward the center, their
 * length and brightness flowing along it (layered slow sines), blended additively; every few
 * seconds a crest rolls along the arc and breaks. A crisp line of width [base] anchors it.
 * Returns 0..1 crest strength (for a matching outline pulse on a mark).
 */
fun DrawScope.drawAurora(
    c: Offset, r: Float, a0: Float, a1: Float, t: Float,
    base: Float, reach: Float, k: Float, palette: SignalPalette
): Float {
    val span = a1 - a0
    val period = 3.6f
    val cp = ((t % period) + period) % period / period
    val crestPos = a0 + span * (0.5f - 0.5f * cos(PI.toFloat() * cp))
    val crestStrength = sin(PI.toFloat() * cp) * (if (cp < 0.8f) 1f else 1f - (cp - 0.8f) / 0.2f * 0.6f)
    var a = a0 + 1f
    while (a <= a1 - 1f) {
        val u = (a - a0) / span
        val edgeFade = min(1f, min(u / 0.12f, (1f - u) / 0.12f))
        val flow = 0.5f +
            0.24f * sin(a * 0.11f + t * 1.25f) +
            0.16f * sin(a * 0.27f - t * 2.05f) +
            0.10f * sin(a * 0.05f + t * 0.6f)
        val d = (a - crestPos) / 16f
        val crest = exp(-d * d) * crestStrength
        val len = (reach * (0.35f + 0.55f * flow) + reach * 0.7f * crest) * edgeFade
        if (len >= 2f * k) {
            val alpha = min(1f, 0.22f + 0.38f * flow + 0.5f * crest) * edgeFade
            val p0 = polar(c, r - base / 2f, a)
            val p1 = polar(c, r - base / 2f - len, a)
            drawLine(
                brush = Brush.linearGradient(
                    0f to palette.edge.copy(alpha = 0.55f * alpha),
                    0.45f to palette.mid.copy(alpha = 0.28f * alpha),
                    0.8f to palette.tip.copy(alpha = 0.10f * alpha),
                    1f to Color.Transparent,
                    start = p0, end = p1
                ),
                start = p0, end = p1,
                strokeWidth = 2.2f * k,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus
            )
        }
        a += 1.1f
    }
    arcAt(palette.line, c, r, a0, span, base, StrokeCap.Round)
    return 0.5f + 0.5f * abs(crestStrength)
}

/** The real logo, crisp, centered on [center] at [sizePx]. */
fun DrawScope.drawMark(bitmap: ImageBitmap, center: Offset, sizePx: Float, alpha: Float) {
    val s = sizePx.roundToInt()
    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset((center.x - s / 2f).roundToInt(), (center.y - s / 2f).roundToInt()),
        dstSize = IntSize(s, s),
        alpha = alpha,
        filterQuality = FilterQuality.High
    )
}
