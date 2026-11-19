package com.agenthud.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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

/** An agent that hasn't reported progress for this long shows a frozen comet. */
private const val STALL_MS = 120_000L

@Composable
fun AgentFocusTile(
    active: Boolean = true,
    thread: AgentThread?,
    pageIndex: Int,
    totalCount: Int,
    moreWaitingCount: Int,
    nowMs: Long,
    onProceed: (AgentThread) -> Unit,
    onDeny: (AgentThread) -> Unit,
    onVoiceReply: (AgentThread) -> Unit,
    onChoose: (AgentThread, String) -> Unit = { _, _ -> }
) {
    if (thread == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(text = "No active agents", fontSize = 14.sp, color = ColorMuted)
        }
        return
    }

    val stateLabel = when (thread.status) {
        "waiting_approval" -> "APPROVAL"
        "waiting_input" -> "QUESTION"
        "error" -> "ERROR"
        "running" -> "RUNNING"
        else -> "READY"
    }
    val sourceLabel = if (thread.source == "desktop") "Desktop" else "CLI"
    val elapsed = formatElapsed(nowMs - thread.startedAtMs)
    val stalled = thread.isRunning && thread.updatedAtMs > 0 && nowMs - thread.updatedAtMs > STALL_MS

    // Rolling log of the last two distinct steps, kept on-watch so the bridge needn't change.
    val recentSteps = remember(thread.id) { mutableStateListOf<String>() }
    LaunchedEffect(thread.id, thread.step) {
        val step = thread.step.trim()
        if (step.isNotEmpty() && recentSteps.lastOrNull() != step) {
            recentSteps.add(step)
            while (recentSteps.size > 2) recentSteps.removeAt(0)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val diameter = maxWidth

        when {
            thread.isRunning -> OrbitingArc(color = thread.accentColor, stalled = stalled)
            thread.isWaiting -> PerimeterArc(sweepDegrees = 360f, color = ColorAmber, strokeDp = 4f)
            thread.isError -> PerimeterArc(sweepDegrees = 360f, color = ColorRed, strokeDp = 4f)
            else -> PerimeterArc(sweepDegrees = 0f, color = ColorTrack, strokeDp = 4f)
        }

        val headerColor = when {
            thread.isWaiting -> ColorAmber
            thread.isError -> ColorRed
            else -> thread.accentColor
        }
        CurvedTopLabel(
            // The logo identifies the provider; spell the name out only when there's no logo.
            text = if (providerLogoRes(thread.provider) != null) stateLabel else "$stateLabel · ${thread.providerLabel.uppercase()}",
            color = headerColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            logoKey = thread.provider
        )

        if (thread.isChoice) {
            ChoiceQuestion(thread = thread, active = active, onChoose = onChoose, onVoiceReply = onVoiceReply)
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 28.dp, end = 28.dp, top = 34.dp, bottom = if (thread.isWaiting) 52.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val stepScroll = rememberScrollState()
            CrownTarget(stepScroll, active = active)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(stepScroll),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
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
                Spacer(Modifier.height(5.dp))
                if (thread.isWaiting || thread.isError) {
                    Text(
                        text = thread.prompt ?: thread.step,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = Color(0xFFF1F3F4),
                        textAlign = TextAlign.Center,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Newest step bright, previous step dimmed beneath it.
                    val steps = if (recentSteps.isEmpty()) listOf(thread.step) else recentSteps.reversed()
                    steps.forEachIndexed { i, s ->
                        Text(
                            text = s,
                            fontSize = if (i == 0) 12.sp else 11.sp,
                            lineHeight = 15.sp,
                            color = if (i == 0) Color(0xFFBDC1C6) else Color(0xFF5F6368),
                            textAlign = TextAlign.Center,
                            // Newest step gets room for a full sentence; the area scrolls.
                            maxLines = if (i == 0) 4 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (moreWaitingCount > 0 && thread.isWaiting) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "+$moreWaitingCount more waiting",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = ColorAmber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF3B2F12))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                } else if (!thread.isWaiting) {
                    val meta = when {
                        stalled -> "Quiet for ${formatElapsed(nowMs - thread.updatedAtMs)}"
                        elapsed.isNotEmpty() -> "$elapsed · $sourceLabel"
                        else -> sourceLabel
                    }
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(text = meta, fontSize = 11.sp, color = if (stalled) ColorAmber else Color(0xFF70757A))
                    }
                }
            }

            if (thread.isWaiting) {
                // Deny and mic as 44dp targets, spread apart so neither is hit by accident.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canDeny = thread.options.any { it.equals("Deny", ignoreCase = true) }
                    if (canDeny) {
                        RoundAction(bg = Color(0xFF3E1A1A), onClick = { onDeny(thread) }) {
                            CloseIcon(color = ColorRed, size = 16.dp)
                        }
                    }
                    RoundAction(bg = Color(0xFF202124), onClick = { onVoiceReply(thread) }) {
                        MicIcon(color = Color.White, size = 18.dp)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (thread.canReply) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF16181C))
                                .clickable { onVoiceReply(thread) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MicIcon(color = ColorBlue, size = 13.dp)
                            Text(text = "Speak", fontSize = 12.sp, color = ColorBlue, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until totalCount.coerceAtMost(5)) {
                            Box(
                                modifier = Modifier
                                    .size(width = if (i == pageIndex) 10.dp else 4.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(if (i == pageIndex) thread.accentColor else Color(0xFF3F3F46))
                            )
                        }
                    }
                }
            }
        }

        if (thread.isWaiting) {
            EdgeButton(
                screenDiameter = diameter,
                color = ColorGreen,
                onClick = { onProceed(thread) },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CheckIcon(color = Color(0xFF0D2416), size = 14.dp)
                    Text(text = "Proceed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D2416))
                }
            }
        }
    }
}

/**
 * A multiple-choice question answered right on the watch: the question, then one tappable
 * row per option, then a voice fallback for an answer that isn't listed. Scrolls when long.
 */
@Composable
private fun ChoiceQuestion(
    thread: AgentThread,
    active: Boolean,
    onChoose: (AgentThread, String) -> Unit,
    onVoiceReply: (AgentThread) -> Unit
) {
    var sent by remember(thread.id, thread.prompt) { mutableStateOf<String?>(null) }
    val listScroll = rememberScrollState()
    CrownTarget(listScroll, active = active)
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scroll viewport starts below the curved header so options never slide under it.
            .padding(top = 38.dp)
            .verticalScroll(listScroll)
            .padding(start = 30.dp, end = 30.dp, top = 6.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = thread.prompt ?: thread.step,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        if (thread.multiSelect) {
            Text(text = "Pick one here; say more by voice", fontSize = 10.sp, color = ColorMuted)
        }
        Spacer(Modifier.height(2.dp))
        thread.options.forEach { raw ->
            val recommended = raw.contains("(Recommended)", ignoreCase = true)
            val label = raw.replace("(Recommended)", "", ignoreCase = true).trim()
            val chosen = sent == label
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) Color(0xFF1E3A2A) else Color(0xFF202124))
                    .clickable(enabled = sent == null) {
                        sent = label
                        onChoose(thread, label)
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chosen) {
                    CheckIcon(color = ColorGreen, size = 12.dp)
                } else if (recommended) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(ColorGreen))
                }
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sent != null && !chosen) Color(0xFF5F6368) else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onVoiceReply(thread) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MicIcon(color = ColorBlue, size = 13.dp)
            Text(text = "Other answer", fontSize = 12.sp, color = ColorBlue, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RoundAction(bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

private fun formatElapsed(deltaMs: Long): String {
    if (deltaMs < 1000L || deltaMs > 24 * 3600_000L) return ""
    val sec = deltaMs / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
