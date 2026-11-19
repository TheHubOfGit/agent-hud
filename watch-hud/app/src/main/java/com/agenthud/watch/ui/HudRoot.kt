package com.agenthud.watch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.focusable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import com.agenthud.watch.audio.VoiceRecorder
import com.agenthud.watch.data.AgentThread
import com.agenthud.watch.data.BridgeClient
import com.agenthud.watch.data.ConnectionState
import com.agenthud.watch.data.HomeLayout
import com.agenthud.watch.data.PrefsStore
import com.agenthud.watch.service.HapticsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HudRoot(
    bridge: BridgeClient,
    prefs: PrefsStore,
    haptics: HapticsEngine,
    voiceRecorder: VoiceRecorder,
    batteryPct: Int,
    batteryTempC: Float,
    isDocked: Boolean,
    screenHeldOn: Boolean = isDocked,
    screenModeLabel: String = "",
    onCycleScreenMode: () -> Unit = {},
    hasMicPermission: () -> Boolean,
    requestMicPermission: () -> Unit,
    onApplyBrightness: (Float) -> Unit,
    onWakeScreen: () -> Unit,
    homePresses: Int = 0,
    onOpenWatchFace: () -> Unit = {},
    onOpenWatchApps: () -> Unit = {},
    onOpenWatchSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val connState by bridge.connectionState.collectAsState()
    val snapshot by bridge.snapshot.collectAsState()
    val lastSeenMs by bridge.lastSeenMs.collectAsState()
    val clockOffsetMs by bridge.clockOffsetMs.collectAsState()

    var currentLayout by remember { mutableStateOf(prefs.homeLayout) }
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var mutedUntilMs by remember { mutableLongStateOf(prefs.mutedUntilMs) }
    var brightnessLevel by remember { mutableStateOf(prefs.brightnessLevel) }
    var showControls by remember { mutableStateOf(false) }
    var showVibration by remember { mutableStateOf(false) }
    var quotaRings by remember { mutableStateOf(prefs.quotaRings) }

    var voiceState by remember { mutableStateOf<VoiceUiState>(VoiceUiState.Hidden) }
    var voiceTargetThread by remember { mutableStateOf<AgentThread?>(null) }
    var voiceRecordJob by remember { mutableStateOf<Job?>(null) }

    // Live clock synced with Mac bridge epoch
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis() + clockOffsetMs
            delay(5000L)
        }
    }
    val clockText = remember(nowMs / 30000L) {
        SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(nowMs))
    }

    // Periodic ±2dp OLED pixel-shift even on active dock face (every 6 minutes)
    val dockOffsets = remember { listOf(0 to 0, 2 to 1, -2 to 2, -1 to -2, 2 to -1) }
    var dockShiftIdx by remember { mutableIntStateOf(0) }
    // Burn-in protection: nudge the layout a few pixels whenever the screen is held on.
    LaunchedEffect(screenHeldOn) {
        while (screenHeldOn) {
            delay(6 * 60_000L)
            dockShiftIdx = (dockShiftIdx + 1) % dockOffsets.size
        }
    }

    // Pager layout:
    // Page 0 = Quotas (Swipe Left from Home)
    // Page 1 = Home (Center default)
    // Page 2..(1 + N) = Individual Agent Focus screens (Swipe Right from Home)
    val threadCount = snapshot.threads.size.coerceAtLeast(1)
    val totalPages = 2 + threadCount
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { totalPages })

    // Keep an agent page pinned to the same thread when the bridge reorders the list,
    // so the screen never flips to a different agent under the viewer.
    var viewedThreadId by remember { mutableStateOf<String?>(null) }
    val threadIds = snapshot.threads.map { it.id }
    val latestThreadIds by rememberUpdatedState(threadIds)
    LaunchedEffect(pagerState.settledPage) {
        viewedThreadId = latestThreadIds.getOrNull(pagerState.settledPage - 2)
    }
    LaunchedEffect(threadIds) {
        val id = viewedThreadId ?: return@LaunchedEffect
        val idx = threadIds.indexOf(id)
        if (idx >= 0 && pagerState.currentPage >= 2 && pagerState.currentPage != 2 + idx && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(2 + idx)
        }
    }

    // Strict FIFO Approval Interrupt Engine:
    // - Auto-jumps to the FIRST pending approval in approvalQueue
    // - Stays locked on the first when a 2nd/3rd arrives (micro-tick only)
    // - Auto-advances when the first resolves
    var lastFirstApprovalId by remember { mutableStateOf<String?>(null) }
    var lastApprovalQueueSize by remember { mutableIntStateOf(0) }
    var lastErrorCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(snapshot.approvalQueue, snapshot.errorCount) {
        val queue = snapshot.approvalQueue
        val firstId = queue.firstOrNull()
        val isMuted = System.currentTimeMillis() < mutedUntilMs

        if (firstId != null) {
            val targetIdx = snapshot.threads.indexOfFirst { it.id == firstId }
            if (firstId != lastFirstApprovalId) {
                // Brand new oldest approval (either first arrival or advanced after resolving previous)
                nightMode = false
                showControls = false
                onWakeScreen()
                if (!isMuted) haptics.buzzApproval()
                if (targetIdx >= 0) {
                    pagerState.animateScrollToPage(2 + targetIdx)
                }
            } else if (queue.size > lastApprovalQueueSize) {
                // A 2nd/3rd approval arrived while 1st is still pending:
                // STAY LOCKED on 1st agent's screen! Just give a gentle micro-tick.
                if (!isMuted) haptics.buzzQueuedTick()
            }
        } else if (lastFirstApprovalId != null) {
            // Queue cleared cleanly! Soft confirmation & return Home
            if (!isMuted) haptics.buzzCleared()
            pagerState.animateScrollToPage(1)
        } else if (snapshot.errorCount > lastErrorCount) {
            // New build/agent error
            nightMode = false
            onWakeScreen()
            if (!isMuted) haptics.buzzError()
            val errIdx = snapshot.threads.indexOfFirst { it.isError }
            if (errIdx >= 0) pagerState.animateScrollToPage(2 + errIdx)
        }

        lastFirstApprovalId = firstId
        lastApprovalQueueSize = queue.size
        lastErrorCount = snapshot.errorCount
    }

    // "Agent finished": the bridge stamps doneAtMs only when a turn genuinely ends, so a
    // session that merely went quiet never counts. The first value seen per thread is recorded
    // without alerting, so reconnects and app restarts don't replay old finishes.
    val seenDoneAt = remember { mutableMapOf<String, Long>() }
    var doneAlertThread by remember { mutableStateOf<AgentThread?>(null) }
    LaunchedEffect(snapshot.threads) {
        val macNow = System.currentTimeMillis() + clockOffsetMs
        val fresh = snapshot.threads.filter { t ->
            val prev = seenDoneAt[t.id]
            prev != null && t.doneAtMs > prev && macNow - t.doneAtMs < 5 * 60_000L
        }
        snapshot.threads.forEach { seenDoneAt[it.id] = it.doneAtMs }
        val latest = fresh.maxByOrNull { it.doneAtMs } ?: return@LaunchedEffect
        if (System.currentTimeMillis() < mutedUntilMs) return@LaunchedEffect
        haptics.buzzFinished()
        if (prefs.doneAlert && !prefs.inQuietHours() && snapshot.approvalQueue.isEmpty()) {
            doneAlertThread = latest
            onWakeScreen()
        }
    }
    LaunchedEffect(doneAlertThread) {
        if (doneAlertThread != null) {
            kotlinx.coroutines.delay(15_000L)
            doneAlertThread = null
        }
    }

    // Crown press (Home): close any popup and return to the HUD's main page.
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        showControls = false
        showVibration = false
        doneAlertThread = null
        if (pagerState.currentPage != 1) pagerState.animateScrollToPage(1)
    }

    // "Agent started": a thread goes from idle (or not yet seen) to running. The first snapshot
    // after launch/reconnect only records state, so existing work doesn't buzz.
    // Resuming after you answer a question (waiting -> running) is not a "start".
    val lastRunState = remember { mutableMapOf<String, String>() }
    var startPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.threads) {
        val started = startPrimed && snapshot.threads.any { t ->
            val prev = lastRunState[t.id]
            t.isRunning && prev != "running" && prev?.startsWith("waiting") != true
        }
        lastRunState.clear()
        snapshot.threads.forEach { lastRunState[it.id] = it.status }
        if (snapshot.threads.isNotEmpty() || snapshot.seq > 0) startPrimed = true
        if (started && System.currentTimeMillis() >= mutedUntilMs) haptics.buzzStarted()
    }

    // Reminder: repeat the "needs you" buzz while the same request keeps waiting.
    LaunchedEffect(snapshot.approvalQueue.firstOrNull()) {
        if (snapshot.approvalQueue.isEmpty()) return@LaunchedEffect
        var waitedMs = 0L
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            waitedMs += 30_000L
            val mins = prefs.hapticReminderMins
            if (mins > 0 && waitedMs >= mins * 60_000L) {
                waitedMs = 0L
                if (System.currentTimeMillis() >= mutedUntilMs) haptics.buzzApproval()
            }
        }
    }

    // Refresh CodexBar quotas whenever user swipes left to Quotas page
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 0) {
            bridge.requestQuotaRefresh()
        }
    }

    fun startVoiceCapture(target: AgentThread?) {
        if (!hasMicPermission()) {
            requestMicPermission()
            voiceState = VoiceUiState.Error("Grant Mic permission and tap again")
            return
        }
        voiceTargetThread = target
        val label = target?.providerLabel ?: "Agent"
        voiceState = VoiceUiState.Recording(label, 0.1f, 0)
        haptics.buzzSoftConfirm()

        voiceRecordJob?.cancel()
        voiceRecordJob = scope.launch {
            val start = System.currentTimeMillis()
            val wav = voiceRecorder.recordUntilStopped { amp ->
                val sec = ((System.currentTimeMillis() - start) / 1000L).toInt()
                voiceState = VoiceUiState.Recording(label, amp, sec)
            }
            if (voiceState !is VoiceUiState.Hidden) {
                voiceState = VoiceUiState.Transcribing(label)
                val res = bridge.transcribeVoice(wav)
                res.onSuccess { text ->
                    haptics.tapTick()
                    voiceState = VoiceUiState.Review(label, text)
                }.onFailure { err ->
                    voiceState = VoiceUiState.Error(err.message ?: "Transcription failed")
                }
            }
        }
    }

    val hasPendingApproval = snapshot.approvalQueue.isNotEmpty()
    val hasError = snapshot.errorCount > 0

    // One router per app (the CompositionLocal default is a lazily created singleton).
    val crownRouter = LocalCrownRouter.current
    val crownFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { crownFocus.requestFocus() } }
    // Overlays come and go; make sure the root keeps focus so the crown keeps working.
    LaunchedEffect(showControls, showVibration, voiceState is VoiceUiState.Hidden, doneAlertThread) {
        runCatching { crownFocus.requestFocus() }
    }

    // Root container with OLED shift, swipe-down controls, and rotary crown support
    val (dx, dy) = if (screenHeldOn && !nightMode) dockOffsets[dockShiftIdx] else (0 to 0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Crown: the root holds focus (rotary events only reach a focused node) and hands
            // each turn to the CrownRouter, which scrolls the active screen or pages.
            .onRotaryScrollEvent { ev ->
                crownRouter.onRotate(ev.verticalScrollPixels)?.let { work -> scope.launch { work() } }
                true
            }
            .focusRequester(crownFocus)
            .focusable()
            .pointerInput(Unit) {
                var totalDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        totalDragY += dragAmount
                        if (totalDragY > 56f && !showControls && voiceState is VoiceUiState.Hidden) {
                            showControls = true
                        }
                    }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize().offset(x = dx.dp, y = dy.dp)) {
            when {
                voiceState !is VoiceUiState.Hidden -> {
                    VoiceOverlay(
                        state = voiceState,
                        onStopRecording = { voiceRecorder.stop() },
                        onConfirmSend = { text ->
                            val target = voiceTargetThread ?: snapshot.threads.firstOrNull()
                            if (target == null) {
                                voiceState = VoiceUiState.Error("No agent to send to", title = "Couldn't send")
                            } else {
                                // Show the outcome: a silent failure looked like it had sent.
                                voiceState = VoiceUiState.Sending(target.providerLabel)
                                scope.launch {
                                    bridge.sendAction(target.id, "reply", text)
                                        .onSuccess {
                                            haptics.buzzSoftConfirm()
                                            voiceState = VoiceUiState.Sent(target.providerLabel)
                                            kotlinx.coroutines.delay(1400)
                                            if (voiceState is VoiceUiState.Sent) voiceState = VoiceUiState.Hidden
                                        }
                                        .onFailure { err ->
                                            haptics.buzzError()
                                            voiceState = VoiceUiState.Error(err.message ?: "Send failed", title = "Couldn't send")
                                        }
                                }
                            }
                        },
                        onCancel = {
                            voiceRecorder.stop()
                            voiceRecordJob?.cancel()
                            voiceState = VoiceUiState.Hidden
                        }
                    )
                }

                showVibration -> {
                    VibrationSettingsScreen(
                        prefs = prefs,
                        haptics = haptics,
                        onDone = { showVibration = false }
                    )
                }

                showControls -> {
                    QuickControlsOverlay(
                        vibrationLabel = VIBRATION_STRENGTHS[prefs.hapticStrength],
                        quotaRings = quotaRings,
                        screenModeLabel = screenModeLabel,
                        onCycleScreenMode = onCycleScreenMode,
                        onOpenWatchFace = {
                            showControls = false
                            onOpenWatchFace()
                        },
                        onOpenWatchSettings = {
                            showControls = false
                            onOpenWatchSettings()
                        },
                        onOpenWatchApps = {
                            showControls = false
                            onOpenWatchApps()
                        },
                        onToggleQuotaStyle = {
                            quotaRings = !quotaRings
                            prefs.quotaRings = quotaRings
                            haptics.tapTick()
                        },
                        onOpenVibration = { showVibration = true },
                        currentLayout = currentLayout,
                        nightMode = nightMode,
                        isMuted = System.currentTimeMillis() < mutedUntilMs,
                        brightnessLevel = brightnessLevel,
                        batteryPct = batteryPct,
                        batteryTempC = batteryTempC,
                        onSelectLayout = {
                            currentLayout = it
                            prefs.homeLayout = it
                            haptics.tapTick()
                        },
                        onToggleNight = {
                            nightMode = !nightMode
                            prefs.nightMode = nightMode
                            onApplyBrightness(if (nightMode) 0.12f else brightnessLevel)
                            showControls = false
                        },
                        onToggleMute = {
                            val now = System.currentTimeMillis()
                            mutedUntilMs = if (now < mutedUntilMs) 0L else now + 30 * 60_000L
                            prefs.mutedUntilMs = mutedUntilMs
                        },
                        onCycleBrightness = {
                            brightnessLevel = when {
                                brightnessLevel < 0.4f -> 0.55f
                                brightnessLevel < 0.75f -> 0.90f
                                else -> 0.30f
                            }
                            prefs.brightnessLevel = brightnessLevel
                            if (!nightMode) onApplyBrightness(brightnessLevel)
                        },
                        onReconnect = { bridge.forceReconnect() },
                        onDismiss = { showControls = false }
                    )
                }

                doneAlertThread != null && !hasPendingApproval -> {
                    val t = doneAlertThread!!
                    DoneAlertScreen(
                        thread = t,
                        nowMs = nowMs,
                        onOpen = {
                            doneAlertThread = null
                            val idx = snapshot.threads.indexOfFirst { it.id == t.id }
                            if (idx >= 0) scope.launch { pagerState.scrollToPage(2 + idx) }
                        },
                        onDismiss = { doneAlertThread = null }
                    )
                }

                nightMode && !hasPendingApproval && !hasError -> {
                    val running = snapshot.threads.count { it.isRunning }
                    NightOledScreen(
                        clockText = clockText,
                        summaryText = if (running > 0) "$running running · $batteryPct%" else "All quiet · $batteryPct%",
                        summaryColor = if (running > 0) ColorBlue else ColorGreen,
                        onWake = {
                            nightMode = false
                            prefs.nightMode = false
                            onApplyBrightness(brightnessLevel)
                        }
                    )
                }

                connState == ConnectionState.DISCOVERING || connState == ConnectionState.OFFLINE -> {
                    val ago = if (lastSeenMs > 0L) {
                        val mins = ((System.currentTimeMillis() - lastSeenMs) / 60_000L).coerceAtLeast(1)
                        "Last seen ${mins}m ago"
                    } else ""
                    OfflineOrDiscoverScreen(
                        isDiscovering = connState == ConnectionState.DISCOVERING,
                        clockText = clockText,
                        lastSeenText = ago,
                        onRetry = { bridge.forceReconnect() }
                    )
                }

                else -> {
                    DisposableEffect(pagerState, totalPages) {
                        crownRouter.pager = { dir ->
                            val target = (pagerState.currentPage + dir).coerceIn(0, totalPages - 1)
                            if (target != pagerState.currentPage) {
                                haptics.tapTick()
                                pagerState.animateScrollToPage(target)
                            }
                        }
                        onDispose { crownRouter.pager = null }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> QuotasTile(
                                rings = quotaRings,
                                quotas = snapshot.quotas,
                                nowMs = nowMs,
                                onRefresh = { bridge.requestQuotaRefresh() }
                            )
                            1 -> HomeTile(
                                layout = currentLayout,
                                snapshot = snapshot,
                                clockText = clockText,
                                batteryPct = batteryPct,
                                onSelectThread = { idx ->
                                    scope.launch {
                                        pagerState.animateScrollToPage((2 + idx).coerceAtMost(totalPages - 1))
                                    }
                                }
                            )
                            else -> {
                                val threadIdx = page - 2
                                val thread = snapshot.threads.getOrNull(threadIdx)
                                val moreWaiting = if (thread != null && snapshot.approvalQueue.firstOrNull() == thread.id) {
                                    (snapshot.approvalQueue.size - 1).coerceAtLeast(0)
                                } else 0
                                AgentFocusTile(
                                    active = pagerState.settledPage == page,
                                    thread = thread,
                                    pageIndex = threadIdx,
                                    totalCount = snapshot.threads.size,
                                    moreWaitingCount = moreWaiting,
                                    nowMs = nowMs,
                                    onProceed = { t ->
                                        scope.launch {
                                            bridge.sendAction(t.id, "proceed")
                                                .onSuccess { haptics.buzzSoftConfirm() }
                                                .onFailure { err ->
                                                    haptics.buzzError()
                                                    voiceState = VoiceUiState.Error(err.message ?: "Send failed", title = "Couldn't send")
                                                }
                                        }
                                    },
                                    onDeny = { t ->
                                        scope.launch {
                                            bridge.sendAction(t.id, "deny")
                                                .onSuccess { haptics.buzzSoftConfirm() }
                                                .onFailure { err ->
                                                    haptics.buzzError()
                                                    voiceState = VoiceUiState.Error(err.message ?: "Send failed", title = "Couldn't send")
                                                }
                                        }
                                    },
                                    onVoiceReply = { t -> startVoiceCapture(t) },
                                    onChoose = { t, label ->
                                        scope.launch {
                                            bridge.sendAction(t.id, "reply", label)
                                                .onSuccess { haptics.buzzSoftConfirm() }
                                                .onFailure { err ->
                                                    haptics.buzzError()
                                                    voiceState = VoiceUiState.Error(err.message ?: "Send failed", title = "Couldn't send")
                                                }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Glowing circular perimeter edge-flash overlay whenever an approval or error is active
        // Not on the Orbit/Pulse home page: there the waiting agent's own aurora is the signal.
        val homeHasOwnSignal = pagerState.currentPage == 1 && currentLayout != HomeLayout.ZEN &&
            !showControls && !showVibration && doneAlertThread == null
        if ((hasPendingApproval || hasError) && voiceState is VoiceUiState.Hidden && !homeHasOwnSignal) {
            PerimeterEdgeFlash(color = if (hasPendingApproval) ColorAmber else ColorRed)
        }
    }
}

@Composable
private fun PerimeterEdgeFlash(color: Color) {
    // Thin solid ring on the same track as the other rings, breathing slowly. Replaces the old
    // fast radial glow, which lit up the bezel.
    val infinite = rememberInfiniteTransition(label = "edgeFlash")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgeAlpha"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2f + 3.dp.toPx()
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension / 2f - inset,
            style = Stroke(width = stroke)
        )
    }
}
