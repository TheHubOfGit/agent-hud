package com.agenthud.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.agenthud.watch.data.QuotaItem
import kotlin.math.roundToInt

@Composable
fun QuotasTile(
    rings: Boolean = false,
    quotas: List<QuotaItem>,
    nowMs: Long,
    onRefresh: () -> Unit
) {
    var detailQuota by remember { mutableStateOf<QuotaItem?>(null) }
    val selected = detailQuota

    if (selected != null) {
        QuotaDetailScreen(quota = selected, nowMs = nowMs, onBack = { detailQuota = null })
        return
    }
    if (rings && quotas.isNotEmpty()) {
        QuotaRingsView(quotas = quotas.take(3), nowMs = nowMs, onSelect = { detailQuota = it })
        return
    }

    // Ring tracks the most-constrained provider, colored by health rather than brand.
    val lowest = quotas.minByOrNull { it.remainingPct }
    val ringColor = lowest?.let { quotaHealthColor(it.remainingPct) } ?: ColorBlue
    val header = when {
        lowest == null -> "AI QUOTAS"
        lowest.remainingPct >= 50 -> "AI QUOTAS"
        else -> "LOWEST · ${lowest.remainingPct}% LEFT"
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        PerimeterArc(
            sweepDegrees = if (lowest == null) 45f else (lowest.remainingPct / 100f) * 360f,
            color = ringColor,
            strokeDp = 4f
        )
        CurvedTopLabel(
            text = header,
            color = if (lowest != null && lowest.remainingPct < 50) ringColor else ColorMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 170.dp, height = 34.dp)
                .clickable { onRefresh() }
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Room for the curved header drawn along the top edge.
            Spacer(Modifier.height(12.dp))

            if (quotas.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Syncing CodexBar…", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(text = "Tap to refresh", fontSize = 11.sp, color = ColorMuted, modifier = Modifier.clickable { onRefresh() })
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    quotas.take(3).forEach { q ->
                        val health = quotaHealthColor(q.remainingPct)
                        val healthy = q.remainingPct >= 50
                        val delta = q.paceDelta(nowMs)
                        val pace = paceState(delta)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailQuota = q }
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = q.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (healthy) Color.White else health
                                    )
                                    ProviderLogo(key = q.id + " " + q.name, size = 12.dp)
                                }
                                Text(
                                    text = when {
                                        pace != PaceState.ON_PACE && delta != null ->
                                            paceShort(delta) + if (q.resetShort.isNotBlank()) " · ${q.resetShort}" else ""
                                        healthy && q.resetShort.isNotBlank() -> "resets ${q.resetShort}"
                                        healthy -> "${q.remainingPct}% left"
                                        q.resetShort.isNotBlank() -> "${q.remainingPct}% · ${q.resetShort}"
                                        else -> "${q.remainingPct}% left"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        pace == PaceState.DEFICIT -> ColorAmber
                                        pace == PaceState.RESERVE -> ColorReserve
                                        healthy -> ColorMuted
                                        else -> health
                                    }
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            UsageBar(
                                remainingPct = q.remainingPct,
                                paceRemainingPct = q.paceRemainingPct(nowMs),
                                style = providerStyle(q.id + " " + q.name, health),
                                markerColor = pace.markerColor
                            )
                        }
                    }
                }
            }

            Text(text = "Tap a row for weekly", fontSize = 10.sp, color = Color(0xFF5F6368))
        }
    }
}

@Composable
private fun QuotaDetailScreen(quota: QuotaItem, nowMs: Long, onBack: () -> Unit) {
    val health = quotaHealthColor(quota.remainingPct)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        PerimeterArc(sweepDegrees = (quota.remainingPct / 100f) * 360f, color = health)
        CurvedTopLabel(
            text = quota.name.uppercase(),
            color = ColorMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            logoKey = quota.id + " " + quota.name
        )
        // Two stacked windows, kept inside the circle's chord at their height.
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WindowSection(
                style = providerStyle(quota.id + " " + quota.name, health),
                label = windowName(quota.windowMinutes, fallback = quota.windowLabel),
                remainingPct = quota.remainingPct,
                paceRemainingPct = quota.paceRemainingPct(nowMs),
                paceDelta = quota.paceDelta(nowMs),
                resetText = formatReset(quota.resetsAtMs, nowMs).ifBlank { quota.resetClock.ifBlank { quota.resetShort } }
            )
            quota.secondaryRemainingPct?.let { sec ->
                WindowSection(
                    style = providerStyle(quota.id + " " + quota.name, health),
                    label = windowName(quota.secondaryWindowMinutes, fallback = "Weekly"),
                    remainingPct = sec,
                    paceRemainingPct = quota.secondaryPaceRemainingPct(nowMs),
                    paceDelta = quota.secondaryPaceDelta(nowMs),
                    resetText = formatReset(quota.secondaryResetsAtMs, nowMs).ifBlank { quota.secondaryResetShort ?: "" }
                )
            }
        }
        // Plan name lives at the bottom so the curved header stays short.
        Text(
            text = quota.plan.replaceFirstChar { it.uppercase() }.ifBlank { "Tap to return" },
            fontSize = 10.sp,
            color = Color(0xFF5F6368),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)
        )
    }
}

@Composable
private fun WindowSection(
    style: ProviderStyle,
    label: String,
    remainingPct: Int,
    paceRemainingPct: Float?,
    paceDelta: Int?,
    resetText: String
) {
    val health = quotaHealthColor(remainingPct)
    val pace = paceState(paceDelta)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ColorMuted)
            Text(text = "$remainingPct% left", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = health)
        }
        Spacer(Modifier.height(2.dp))
        UsageBar(
            remainingPct = remainingPct,
            paceRemainingPct = paceRemainingPct,
            style = style,
            markerColor = pace.markerColor
        )
        Spacer(Modifier.height(1.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = paceDelta?.let { paceLabel(it) } ?: "",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = pace.textColor,
                maxLines = 1
            )
            if (resetText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    RefreshIcon(color = ColorMuted, size = 9.dp)
                    Text(text = resetText, fontSize = 10.sp, color = ColorMuted, maxLines = 1)
                }
            }
        }
    }
}

private fun windowName(minutes: Int, fallback: String): String = when {
    minutes == 10080 -> "Weekly"
    minutes in 1..1439 && minutes % 60 == 0 -> "${minutes / 60}-hour"
    minutes == 1440 -> "Daily"
    minutes > 0 -> "${minutes / 60}h window"
    else -> fallback
}

/** Reset moment, shown after a reset icon: "12:30 AM" within a day, "Sun 5 AM" / "Tue 10:40 PM" further out. */
private fun formatReset(resetsAtMs: Long, nowMs: Long): String {
    if (resetsAtMs <= 0L) return ""
    if (resetsAtMs <= nowMs) return "now"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = resetsAtMs }
    val time = if (cal.get(java.util.Calendar.MINUTE) == 0) "h a" else "h:mm a"
    val pattern = if (resetsAtMs - nowMs < 24 * 3600_000L) time else "EEE $time"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(resetsAtMs))
}

private val ColorReserve = Color(0xFF81C995)

/** Within this many points of even pace counts as "on pace". */
private const val PACE_SLACK = 3

private enum class PaceState(val markerColor: Color, val textColor: Color) {
    RESERVE(ColorReserve, ColorReserve),
    DEFICIT(ColorAmber, ColorAmber),
    ON_PACE(Color.White, ColorMuted)
}

private fun paceState(delta: Int?): PaceState = when {
    delta == null -> PaceState.ON_PACE
    delta >= PACE_SLACK -> PaceState.RESERVE
    delta <= -PACE_SLACK -> PaceState.DEFICIT
    else -> PaceState.ON_PACE
}

private fun paceShort(delta: Int): String = if (delta >= 0) "+$delta%" else "\u2212${-delta}%"

private fun paceLabel(delta: Int): String = when (paceState(delta)) {
    PaceState.RESERVE -> "$delta% reserve"
    PaceState.DEFICIT -> "${-delta}% deficit"
    PaceState.ON_PACE -> "On pace"
}

/**
 * Remaining-quota bar with an even-pace tick (where the fill would be if usage were spread evenly
 * across the window). Geometry is snapped to whole pixels so the top and bottom edges anti-alias
 * identically.
 */
@Composable
private fun UsageBar(
    remainingPct: Int,
    paceRemainingPct: Float?,
    style: ProviderStyle,
    markerColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(12.dp)) {
        val barH = 6.dp.toPx().roundToInt().toFloat()
        val top = ((size.height - barH) / 2f).roundToInt().toFloat()
        val width = size.width.roundToInt().toFloat()
        val radius = CornerRadius(barH / 2f)
        drawRoundRect(Color(0xFF202124), Offset(0f, top), Size(width, barH), radius)
        val fillW = (width * (remainingPct / 100f).coerceIn(0.04f, 1f)).roundToInt().toFloat()
        // Fill in the provider's identity (same as its Pulse segment): flat color, or Antigravity's
        // Gemini gradient across the filled length. Health shows in the text, not the bar.
        val stops = style.stops
        if (stops != null) {
            drawRoundRect(Brush.horizontalGradient(stops, startX = 0f, endX = fillW), Offset(0f, top), Size(fillW, barH), radius)
        } else {
            drawRoundRect(style.accent, Offset(0f, top), Size(fillW, barH), radius)
        }

        if (paceRemainingPct != null) {
            val w = 2.dp.toPx().roundToInt().toFloat()
            val halo = 1.dp.toPx().roundToInt().toFloat()
            val x = (width * (paceRemainingPct / 100f)).roundToInt().toFloat()
                .coerceIn(w / 2f + halo, width - w / 2f - halo)
            // Black halo cuts a notch so the tick reads against a light fill.
            drawRect(Color.Black, Offset(x - w / 2f - halo, 0f), Size(w + halo * 2f, size.height))
            drawRoundRect(markerColor, Offset(x - w / 2f, 0f), Size(w, size.height), CornerRadius(w / 2f))
        }
    }
}

/**
 * Alternate quota page: one concentric ring per provider around the edge (outermost = first),
 * each in its brand color, filled to the remaining % from 12 o'clock with an even-pace tick.
 * The center lists the same providers in ring order; tap a row for the detail screen.
 */
@Composable
private fun QuotaRingsView(quotas: List<QuotaItem>, nowMs: Long, onSelect: (QuotaItem) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val gap = 4.dp.toPx()
            quotas.forEachIndexed { i, q ->
                val inset = 3.dp.toPx() + stroke / 2f + i * (stroke + gap)
                val topLeft = Offset(inset, inset)
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(Color(0xFF1F2229), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                val sweep = (q.remainingPct / 100f).coerceIn(0f, 1f) * 360f
                if (sweep > 0f) {
                    val colors = providerBrandColors(q.id + " " + q.name) ?: listOf(q.accentColor)
                    drawArc(
                        brush = brandBrush(colors, topLeft, Offset(topLeft.x + arcSize.width, topLeft.y + arcSize.height)),
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                // Even-pace tick: a short radial bar across the ring, with a black halo.
                q.paceRemainingPct(nowMs)?.let { pace ->
                    val angle = Math.toRadians((-90f + pace / 100f * 360f).toDouble())
                    val r = arcSize.width / 2f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dir = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                    val inner = Offset(cx + dir.x * (r - stroke / 2f - 1.dp.toPx()), cy + dir.y * (r - stroke / 2f - 1.dp.toPx()))
                    val outer = Offset(cx + dir.x * (r + stroke / 2f + 1.dp.toPx()), cy + dir.y * (r + stroke / 2f + 1.dp.toPx()))
                    val delta = q.paceDelta(nowMs) ?: 0
                    val tick = when {
                        delta <= -3 -> ColorAmber
                        delta >= 3 -> Color(0xFF81C995)
                        else -> Color.White
                    }
                    drawLine(Color.Black, inner, outer, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(tick, inner, outer, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 46.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            quotas.forEach { q ->
                val delta = q.paceDelta(nowMs)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(q) }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProviderLogo(key = q.id + " " + q.name, size = 14.dp)
                    Text(
                        text = "${q.remainingPct}%",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (q.remainingPct >= 50) Color.White else quotaHealthColor(q.remainingPct)
                    )
                    Text(
                        text = when {
                            delta != null && delta <= -3 -> "\u2212${-delta}%"
                            delta != null && delta >= 3 -> "+$delta%"
                            else -> q.resetShort
                        },
                        fontSize = 11.sp,
                        color = when {
                            delta != null && delta <= -3 -> ColorAmber
                            delta != null && delta >= 3 -> Color(0xFF81C995)
                            else -> ColorMuted
                        }
                    )
                }
            }
        }
    }
}
