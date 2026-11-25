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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedComposable
import androidx.wear.compose.material.curvedText
import com.agenthud.watch.R
import com.agenthud.watch.data.AgentThread
import kotlin.math.atan2
import kotlin.math.hypot

/** Off-white used for idle/static text so 24/7 docked pixels age slower than pure white. */
val ColorIdleText = Color(0xFFB8BBBF)

/** Shared ring geometry so every ring, comet, and segment lands on the same track. */
private fun DrawScope.ringGeometry(strokeDp: Float): Triple<Float, Offset, Size> {
    val stroke = strokeDp.dp.toPx()
    val inset = stroke / 2f + 3.dp.toPx()
    return Triple(stroke, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2))
}

/** Drawable for a provider's logo, matched loosely so "Claude Code" and "claude" both resolve. */
fun providerLogoRes(key: String): Int? {
    val k = key.lowercase()
    return when {
        "claude" in k -> R.drawable.logo_claude
        "codex" in k -> R.drawable.logo_codex
        "antigravity" in k -> R.drawable.logo_antigravity
        "t3" in k -> R.drawable.logo_t3code
        else -> null
    }
}

@Composable
fun ProviderLogo(key: String, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val res = providerLogoRes(key) ?: return
    Image(painter = painterResource(res), contentDescription = null, modifier = modifier.size(size))
}

/**
 * Text that follows the top curve of the screen, just inside the perimeter ring.
 * Optionally places a provider logo after [text], followed by [suffix].
 */
@Composable
fun CurvedTopLabel(
    text: String,
    color: Color,
    fontSize: TextUnit = 11.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    edgePadding: Dp = 13.dp,
    logoKey: String? = null,
    suffix: String = ""
) {
    val style = CurvedTextStyle(
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        fontStyle = FontStyle.Normal,
        fontSynthesis = FontSynthesis.None
    )
    val hasLogo = logoKey != null && providerLogoRes(logoKey) != null
    CurvedLayout(modifier = Modifier.fillMaxSize().padding(edgePadding), anchor = 270f) {
        curvedText(text = text, style = style)
        if (hasLogo) {
            curvedComposable(radialAlignment = CurvedAlignment.Radial.Center) {
                ProviderLogo(key = logoKey!!, size = fontSize.value.dp, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
            }
        }
        if (suffix.isNotEmpty()) curvedText(text = suffix, style = style)
    }
}

/**
 * Calm "working" indicator: a short comet head with a faint tail making one lap every 8s.
 * When the agent has gone quiet ([stalled]) the comet freezes and dims so a hung agent
 * reads differently from a busy one. [fps] caps the redraw rate (see rememberAnimClock).
 */
@Composable
fun OrbitingArc(color: Color, stalled: Boolean, strokeDp: Float = 4f, fps: Int = 60) {
    val clock = rememberAnimClock(active = !stalled, fps = fps)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val (stroke, topLeft, arcSize) = ringGeometry(strokeDp)
        drawArc(ColorTrack, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        val angle = (clock.value / 8f % 1f) * 360f // one lap every 8 s
        val head = if (stalled) -90f else angle - 90f
        val headColor = if (stalled) color.copy(alpha = 0.4f) else color
        drawArc(headColor.copy(alpha = headColor.alpha * 0.22f), head - 62f, 62f, false, topLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(headColor, head - 26f, 26f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Brand palettes sampled from each provider's logo; one color = solid, several = gradient. */
// Same identities as the Orbit/Pulse design (ProviderStyle.kt): flat clay, flat periwinkle,
// Gemini gradient for Antigravity, platinum for T3 Code.
private val BrandClaude = listOf(Color(0xFFD97757))
private val BrandCodex = listOf(Color(0xFF7A84FF))
private val BrandAntigravity = listOf(Color(0xFF217BFE), Color(0xFF078EFB), Color(0xFFAC87EB), Color(0xFFEE4D5D))
private val BrandT3 = listOf(Color(0xFFD5D8DD))

fun providerBrandColors(provider: String): List<Color>? {
    val k = provider.lowercase()
    return when {
        "claude" in k -> BrandClaude
        "codex" in k -> BrandCodex
        "antigravity" in k || "gemini" in k -> BrandAntigravity
        "t3" in k -> BrandT3
        else -> null
    }
}

fun AgentThread.brandColors(): List<Color> = providerBrandColors(provider) ?: listOf(accentColor)

/** Solid for single-color brands, otherwise a linear gradient running from [start] to [end]. */
fun brandBrush(colors: List<Color>, start: Offset, end: Offset, alpha: Float = 1f): Brush {
    val c = colors.map { it.copy(alpha = it.alpha * alpha) }
    return if (c.size == 1) SolidColor(c[0]) else Brush.linearGradient(c, start, end)
}

/** Ring color for one thread on the segmented home ring. */
fun AgentThread.ringColor(): Color = when {
    isWaiting -> ColorAmber
    isError -> ColorRed
    status == "idle" -> Color(0xFF2A2C31)
    else -> accentColor
}

/**
 * Home ring split into one segment per agent (max 6), colored by status. Tapping near a segment
 * opens that agent; waiting segments breathe gently.
 */
@Composable
fun SegmentedRing(
    threads: List<AgentThread>,
    emptyColor: Color,
    onSelect: (Int) -> Unit,
    strokeDp: Float = 5f
) {
    val shown = threads.take(6)
    val onSelectState by rememberUpdatedState(onSelect)
    val infinite = rememberInfiniteTransition(label = "segBreath")
    val breath by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "segBreathAlpha"
    )
    val count = shown.size
    val gapDeg = if (count > 1) 7f else 0f
    val segDeg = if (count == 0) 0f else 360f / count
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(count) {
                if (count == 0) return@pointerInput
                detectTapGestures { pos ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = hypot(pos.x - cx, pos.y - cy)
                    // Only the outer band selects a segment; the center stays free for other taps.
                    if (r < size.width / 2f - 34.dp.toPx()) return@detectTapGestures
                    var deg = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat() + 90f
                    if (deg < 0f) deg += 360f
                    onSelectState(((deg / segDeg).toInt()).coerceIn(0, count - 1))
                }
            }
    ) {
        val (stroke, topLeft, arcSize) = ringGeometry(strokeDp)
        drawArc(ColorTrack, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        if (count == 0) {
            drawArc(emptyColor, -90f - 18f, 36f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            return@Canvas
        }
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = arcSize.width / 2f
        fun pointAt(deg: Float): Offset {
            val rad = Math.toRadians(deg.toDouble())
            return Offset(center.x + r * kotlin.math.cos(rad).toFloat(), center.y + r * kotlin.math.sin(rad).toFloat())
        }
        shown.forEachIndexed { i, t ->
            val start = -90f + i * segDeg + gapDeg / 2f
            val sweep = (segDeg - gapDeg).coerceAtLeast(4f)
            // Waiting/error keep their status color; otherwise the segment wears the provider's brand,
            // dimmed when that agent is idle. The gradient runs along the segment's own chord.
            val brush = when {
                t.isWaiting -> SolidColor(ColorAmber.copy(alpha = breath))
                t.isError -> SolidColor(ColorRed)
                else -> {
                    val (a, b) = if (sweep > 180f) Offset.Zero to Offset(size.width, size.height)
                        else pointAt(start) to pointAt(start + sweep)
                    brandBrush(t.brandColors(), a, b, alpha = if (t.status == "idle") 0.35f else 1f)
                }
            }
            drawArc(
                brush = brush,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = if (count > 1) StrokeCap.Round else StrokeCap.Butt)
            )
        }
    }
}

/**
 * Wear-style edge button shape: a pill top that melts into the bottom curve of the screen.
 * [ringClearance] keeps the bottom edge off the perimeter ring.
 */
class EdgeButtonShape(
    private val screenDiameter: Dp,
    private val topWidth: Dp = 148.dp,
    private val ringClearance: Dp = 11.dp,
    private val cornerRadius: Dp = 22.dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        with(density) {
            val screenR = screenDiameter.toPx() / 2f
            val cx = size.width / 2f
            // Box is bottom-aligned, so the screen center sits screenR above the box bottom.
            val cy = size.height - screenR
            val circleR = screenR - ringClearance.toPx()
            val halfW = topWidth.toPx() / 2f
            val pill = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(cx - halfW, 0f, cx + halfW, size.height),
                        topLeft = CornerRadius(cornerRadius.toPx()),
                        topRight = CornerRadius(cornerRadius.toPx())
                    )
                )
            }
            val circle = Path().apply { addOval(Rect(Offset(cx, cy), circleR)) }
            return Outline.Generic(Path().apply { op(pill, circle, PathOperation.Intersect) })
        }
    }
}

@Composable
fun EdgeButton(
    screenDiameter: Dp,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(EdgeButtonShape(screenDiameter))
            .background(color)
            .clickable { onClick() }
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Quota health: neutral when comfortable, amber under half, red when nearly exhausted. */
fun quotaHealthColor(remainingPct: Int): Color = when {
    remainingPct < 15 -> ColorRed
    remainingPct < 50 -> ColorAmber
    else -> Color(0xFFE8EAED)
}
