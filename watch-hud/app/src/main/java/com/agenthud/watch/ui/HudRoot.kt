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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.focusable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Longest Live Mode dictation. Past this the recording stops and is sent. It bounds mic-on time,
 * the audio buffer, and how long the Mac takes to transcribe (the watch gives that call 20 s).
 */
private const val LIVE_MAX_RECORD_SECONDS = 90

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
    onLiveRecordingChanged: (Boolean) -> Unit = {},
    homePresses: Int = 0,
    onOpenWatchFace: () -> Unit = {},
    onOpenWatchApps: () -> Unit = {},
    onOpenWatchSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val connState by bridge.connectionState.collectAsState()
    val snapshot by bridge.snapshot.collectAsState()
    val liveState by bridge.liveState.collectAsState()
    val lastSeenMs by bridge.lastSeenMs.collectAsState()
    val clockOffsetMs by bridge.clockOffsetMs.collectAsState()

    var currentLayout by remember { mutableStateOf(prefs.homeLayout) }
    var nightMode by remember { mutableStateOf(prefs.nightMode) }
    var mutedUntilMs by remember { mutableLongStateOf(prefs.mutedUntilMs) }
    var brightnessLevel by remember { mutableStateOf(prefs.brightnessLevel) }
    var showControls by remember { mutableStateOf(false) }
    var showVibration by remember { mutableStateOf(false) }
    var quotaRings by remember { mutableStateOf(prefs.quotaRings) }
    var verticalPages by remember { mutableStateOf(prefs.verticalPages) }
    var autoOpenAgent by remember { mutableStateOf(prefs.autoOpenAgent) }
    var liveMode by remember { mutableStateOf(prefs.liveMode) }
    var voiceTriggerMode by remember { mutableIntStateOf(prefs.voiceTriggerMode) }
    var liveVoiceState by remember { mutableStateOf<LiveVoiceState>(LiveVoiceState.Idle) }
    var liveRecordJob by remember { mutableStateOf<Job?>(null) }
    var liveStepReqId by remember { mutableIntStateOf(0) }
    var liveStepInFlight by remember { mutableStateOf(false) }
    var livePreviewIndex by remember { mutableStateOf<Int?>(null) }
    var liveCommitJob by remember { mutableStateOf<Job?>(null) }
    var lastCrownClickMs by remember { mutableLongStateOf(0L) }

    fun commitLiveTargetIfNeeded(targetIdx: Int?) {
        val target = targetIdx ?: return
        if (liveStepInFlight) return
        if (!liveState.dialSupported || liveState.levels.isEmpty()) {
            livePreviewIndex = null
            return
        }
        val clamped = target.coerceIn(0, liveState.levels.lastIndex)
        if (clamped == liveState.index) {
            livePreviewIndex = null
            return
        }
        val delta = if (clamped > liveState.index) 1 else -1
        val targetLevel = liveState.levels.getOrNull(clamped)
        liveStepInFlight = true
        liveStepReqId = bridge.stepLiveThinking(delta, targetIndex = clamped, targetLevel = targetLevel)
    }

    LaunchedEffect(liveMode) {
        bridge.setLiveMode(liveMode)
        if (!liveMode) {
            voiceRecorder.stop()
            liveRecordJob?.cancel()
            liveCommitJob?.cancel()
            liveVoiceState = LiveVoiceState.Idle
            liveStepInFlight = false
            livePreviewIndex = null
        }
    }

    // Clear any local dial preview when switching to a different Mac app.
    LaunchedEffect(liveState.bundleId, liveState.appName) {
        liveCommitJob?.cancel()
        livePreviewIndex = null
        liveStepInFlight = false
    }

    LaunchedEffect(liveVoiceState is LiveVoiceState.Listening) {
        onLiveRecordingChanged(liveVoiceState is LiveVoiceState.Listening)
    }

    // Lock-in confirmation once the Mac commits the final target level (or chain if user turned again).
    LaunchedEffect(liveState.reqId, liveState.updatedAtMs) {
        val reqId = liveState.reqId
        if (reqId != 0 && reqId == liveStepReqId) {
            liveStepInFlight = false
            val wanted = livePreviewIndex
            if (liveState.confirmed && wanted != null && wanted != liveState.index && liveState.levels.isNotEmpty()) {
                // User kept turning while the previous step was in flight — jump straight to final target!
                commitLiveTargetIfNeeded(wanted)
                return@LaunchedEffect
            }
            livePreviewIndex = null
            when {
                liveState.confirmed && liveState.changed -> haptics.buzzLevelConfirmed(liveState.index, liveState.levels.size)
                liveState.boundary -> haptics.buzzBoundaryBump()
                !liveState.confirmed || liveState.error != null -> haptics.buzzError()
            }
        }
    }

    // Safety timeout if a thinking-level step never gets a response (e.g. brief Wi-Fi blip). Longer
    // than the bridge's own 5 s limit, so a slow step is not shown as failed and then land anyway.
    LaunchedEffect(liveStepInFlight, liveStepReqId) {
        if (liveStepInFlight) {
            delay(6000L)
            liveStepInFlight = false
            livePreviewIndex = null
        }
    }

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

    // Pager layout.
    // Side (default):  0 = Quotas, 1 = Home, 2.. = one page per agent (left to right).
    // Vertical:        0 = Controls, 1 = Quotas, 2 = Home, 3.. = agents (top to bottom).
    val controlsPage = if (verticalPages) 0 else -1
    val quotaPage = if (verticalPages) 1 else 0
    val homePage = quotaPage + 1
    val agentBase = homePage + 1
    val threadCount = snapshot.threads.size.coerceAtLeast(1)
    val totalPages = agentBase + threadCount
    // A fresh pager (starting on Home) whenever the layout changes.
    val pagerState = key(verticalPages) { rememberPagerState(initialPage = homePage, pageCount = { totalPages }) }

    // Keep an agent page pinned to the same thread when the bridge reorders the list,
    // so the screen never flips to a different agent under the viewer.
    var viewedThreadId by remember { mutableStateOf<String?>(null) }
    val threadIds = snapshot.threads.map { it.id }
    val latestThreadIds by rememberUpdatedState(threadIds)
    LaunchedEffect(pagerState.settledPage) {
        viewedThreadId = latestThreadIds.getOrNull(pagerState.settledPage - agentBase)
    }
    LaunchedEffect(threadIds) {
        val id = viewedThreadId ?: return@LaunchedEffect
        val idx = threadIds.indexOf(id)
        if (idx >= 0 && pagerState.currentPage >= agentBase && pagerState.currentPage != agentBase + idx && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(agentBase + idx)
        }
    }

    // Strict FIFO Approval Interrupt Engine:
    // - Auto-jumps to the FIRST pending approval in approvalQueue
    // - Stays locked on the first when a 2nd/3rd arrives (micro-tick only)
    // - Auto-advances when the first resolves
    var lastFirstApprovalId by remember { mutableStateOf<String?>(null) }
    var lastApprovalQueueSize by remember { mutableIntStateOf(0) }
    var lastErrorCount by remember { mutableIntStateOf(0) }
    // True once "Open agent" took the screen to an agent's page; cleared when we send it back Home.
    var autoOpened by remember { mutableStateOf(false) }

    LaunchedEffect(snapshot.approvalQueue, snapshot.errorCount) {
        val queue = snapshot.approvalQueue
        val firstId = queue.firstOrNull()
        val isMuted = System.currentTimeMillis() < mutedUntilMs

        if (liveMode) {
            // Stay pinned in Live Mode so background agent alerts never hijack the crown mid-dictation.
            // A subtle tappable waiting pill is shown at the bottom of LiveModeScreen instead.
            if (firstId != null && firstId != lastFirstApprovalId && !isMuted) {
                haptics.buzzQueuedTick()
            }
        } else if (firstId != null) {
            val targetIdx = snapshot.threads.indexOfFirst { it.id == firstId }
            if (firstId != lastFirstApprovalId) {
                // Brand new oldest approval (either first arrival or advanced after resolving previous).
                // The alert always happens. Taking you to the agent's page only when "Open agent" is
                // chosen: otherwise Home shows the amber aurora and "N waiting on you", and a tap opens it.
                nightMode = false
                onWakeScreen()
                if (!isMuted) haptics.buzzApproval()
                if (autoOpenAgent) {
                    showControls = false
                    if (targetIdx >= 0) {
                        autoOpened = true
                        pagerState.animateScrollToPage(agentBase + targetIdx)
                    }
                }
            } else if (queue.size > lastApprovalQueueSize) {
                // A 2nd/3rd approval arrived while 1st is still pending:
                // STAY LOCKED on 1st agent's screen! Just give a gentle micro-tick.
                if (!isMuted) haptics.buzzQueuedTick()
            }
        } else if (lastFirstApprovalId != null) {
            // Queue cleared cleanly! Soft confirmation, and back Home if we had taken you away.
            if (!isMuted) haptics.buzzCleared()
            // Answered here or on the laptop: back to the main Home screen.
            if (autoOpenAgent && autoOpened) {
                autoOpened = false
                pagerState.animateScrollToPage(homePage)
            }
        } else if (snapshot.errorCount > lastErrorCount) {
            // New build/agent error
            nightMode = false
            onWakeScreen()
            if (!isMuted) haptics.buzzError()
            // Errors wake and buzz, and Home turns red; they never move the page.
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

    fun startLiveVoice() {
        if (!hasMicPermission()) {
            requestMicPermission()
            liveVoiceState = LiveVoiceState.Error("Grant Mic permission and click again")
            return
        }
        val targetApp = liveState.appName.ifBlank { "Mac" }
        val triggerKey = PrefsStore.VOICE_TRIGGER_MODES[voiceTriggerMode].second
        liveVoiceState = LiveVoiceState.Listening(0.1f, 0)
        haptics.buzzVoiceStart()

        liveRecordJob?.cancel()
        liveRecordJob = scope.launch {
            val start = System.currentTimeMillis()
            var heardSpeech = false
            var lastSpeechMs = start
            var autoTriggered = false

            val triggerWatcher = if (triggerKey != "off") {
                launch {
                    var lastCheckedBytes = 0
                    while (liveVoiceState is LiveVoiceState.Listening) {
                        delay(160L)
                        val now = System.currentTimeMillis()
                        if (heardSpeech && now - lastSpeechMs >= 520L) {
                            val curBytes = voiceRecorder.currentPcmBytes()
                            if (curBytes > lastCheckedBytes + 6400) {
                                lastCheckedBytes = curBytes
                                val tailWav = voiceRecorder.snapshotTailWav(2.2f)
                                if (bridge.checkLiveVoiceTrigger(tailWav, triggerKey) &&
                                    liveVoiceState is LiveVoiceState.Listening
                                ) {
                                    autoTriggered = true
                                    haptics.buzzSoftConfirm()
                                    voiceRecorder.stop()
                                    break
                                }
                            }
                        }
                    }
                }
            } else null

            val wav = voiceRecorder.recordUntilStopped { amp ->
                val now = System.currentTimeMillis()
                val sec = ((now - start) / 1000L).toInt()
                // Hard stop (the clip is sent as normal): see LIVE_MAX_RECORD_SECONDS.
                if (sec >= LIVE_MAX_RECORD_SECONDS) voiceRecorder.stop()
                if (now - start > 220L && amp >= 0.055f) {
                    heardSpeech = true
                    lastSpeechMs = now
                }
                if (liveVoiceState is LiveVoiceState.Listening) {
                    liveVoiceState = LiveVoiceState.Listening(amp, sec)
                }
            }
            triggerWatcher?.cancel()

            if (liveVoiceState !is LiveVoiceState.Idle) {
                val appNow = liveState.appName.ifBlank { targetApp }
                liveVoiceState = LiveVoiceState.Sending(appNow)
                val strip = if (autoTriggered) triggerKey else null
                bridge.sendLiveVoice(wav, stripTrigger = strip, expectBundleId = liveState.bundleId)
                    .onSuccess { sent ->
                        haptics.buzzCleared()
                        liveVoiceState = LiveVoiceState.Sent(sent.appName.ifBlank { appNow }, sent.text, sent.pastedOnly)
                        // Pasted-only needs the person to act, so stay up long enough to read it.
                        delay(if (sent.pastedOnly) 2600L else 1100L)
                        if (liveVoiceState is LiveVoiceState.Sent) liveVoiceState = LiveVoiceState.Idle
                    }
                    .onFailure { err ->
                        haptics.buzzError()
                        liveVoiceState = LiveVoiceState.Error(err.message ?: "Send failed")
                        delay(2200L)
                        if (liveVoiceState is LiveVoiceState.Error) liveVoiceState = LiveVoiceState.Idle
                    }
            }
        }
    }

    fun toggleLiveVoice() {
        when (liveVoiceState) {
            is LiveVoiceState.Listening -> {
                haptics.buzzSoftConfirm()
                voiceRecorder.stop()
            }
            is LiveVoiceState.Sending -> Unit
            else -> startLiveVoice()
        }
    }

    fun cancelLiveVoice() {
        voiceRecorder.stop()
        liveRecordJob?.cancel()
        liveVoiceState = LiveVoiceState.Idle
    }

    // Crown press (Home):
    // - If an overlay (Controls/Vibration) is open, close it.
    // - In Live Mode: 1st click starts watch-mic listening; 2nd click stops & auto-sends to focused Mac app.
    // - In normal HUD mode: close popups and return to Home.
    LaunchedEffect(homePresses) {
        if (homePresses == 0) return@LaunchedEffect
        if (showControls || showVibration) {
            showControls = false
            showVibration = false
            return@LaunchedEffect
        }
        if (liveMode) {
            val now = System.currentTimeMillis()
            if (now - lastCrownClickMs < 350L) return@LaunchedEffect
            lastCrownClickMs = now
            toggleLiveVoice()
            return@LaunchedEffect
        }
        doneAlertThread = null
        if (pagerState.currentPage != homePage) pagerState.animateScrollToPage(homePage)
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
        if (pagerState.currentPage == quotaPage) {
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
    LaunchedEffect(showControls, showVibration, voiceState is VoiceUiState.Hidden, doneAlertThread, liveMode, liveVoiceState) {
        runCatching { crownFocus.requestFocus() }
    }

    // Controls: a pop-up in the side layout, the top page in the vertical layout.
    val controlsContent: @Composable (Boolean, Boolean) -> Unit = { asPage, pageActive ->
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
            onDismiss = { if (asPage) scope.launch { pagerState.animateScrollToPage(homePage) } else showControls = false },
            liveMode = liveMode,
            onToggleLiveMode = {
                liveMode = !liveMode
                prefs.liveMode = liveMode
                showControls = false
                nightMode = false
                haptics.tapTick()
            },
            voiceTriggerLabel = PrefsStore.VOICE_TRIGGER_MODES[voiceTriggerMode].first,
            onCycleVoiceTrigger = {
                voiceTriggerMode = (voiceTriggerMode + 1) % PrefsStore.VOICE_TRIGGER_MODES.size
                prefs.voiceTriggerMode = voiceTriggerMode
                haptics.tapTick()
            },
            verticalPages = verticalPages,
            onTogglePages = {
                verticalPages = !verticalPages
                prefs.verticalPages = verticalPages
                showControls = false
                haptics.tapTick()
            },
            autoOpen = autoOpenAgent,
            onToggleAutoOpen = {
                autoOpenAgent = !autoOpenAgent
                prefs.autoOpenAgent = autoOpenAgent
                haptics.tapTick()
            },
            asPage = asPage,
            pageActive = pageActive
        )
    }

    // Root container with OLED shift, swipe-down controls, and rotary crown support
    val (dx, dy) = if (screenHeldOn && !nightMode) dockOffsets[dockShiftIdx] else (0 to 0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Rotate 90° clockwise in Live Mode only so the physical crown sits at the top (12 o'clock).
            .graphicsLayer { rotationZ = if (liveMode) 90f else 0f }
            // Crown: the root holds focus (rotary events only reach a focused node) and hands
            // each turn to the CrownRouter, which scrolls the active screen or pages.
            .onRotaryScrollEvent { ev ->
                crownRouter.onRotate(ev.verticalScrollPixels)?.let { work -> scope.launch { work() } }
                true
            }
            .focusRequester(crownFocus)
            .focusable()
            .pointerInput(verticalPages, liveMode) {
                // Swipe down opens Controls in the side layout — and always in Live Mode so you can exit/toggle.
                if (verticalPages && !liveMode) return@pointerInput
                var totalDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        totalDragY += dragAmount
                        if (totalDragY > 56f && !showControls && voiceState is VoiceUiState.Hidden && liveVoiceState is LiveVoiceState.Idle) {
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

                showControls && (!verticalPages || liveMode) -> {
                    controlsContent(false, true)
                }

                liveMode && connState != ConnectionState.DISCOVERING && connState != ConnectionState.OFFLINE -> {
                    val latestLiveVoice by rememberUpdatedState(liveVoiceState)
                    val latestLiveState by rememberUpdatedState(liveState)
                    val latestPreview by rememberUpdatedState(livePreviewIndex)
                    DisposableEffect(Unit) {
                        var accum = 0f
                        crownRouter.rawListener = { dy ->
                            if (latestLiveVoice is LiveVoiceState.Idle) {
                                if (accum != 0f && (accum > 0f) != (dy > 0f)) accum = 0f
                                accum += dy
                                val stepPx = 124f
                                var target = latestPreview ?: latestLiveState.index
                                var stepped = false
                                while (kotlin.math.abs(accum) >= stepPx) {
                                    val dir = if (accum > 0f) 1 else -1
                                    accum -= dir * stepPx
                                    val count = latestLiveState.levels.size
                                    if (!latestLiveState.dialSupported || count == 0) {
                                        haptics.buzzBoundaryBump()
                                        accum = 0f
                                        break
                                    }
                                    val cur = target.coerceIn(0, count - 1)
                                    val next = (cur + dir).coerceIn(0, count - 1)
                                    if (next != cur) {
                                        target = next
                                        livePreviewIndex = next
                                        haptics.buzzDialDetent(next, count)
                                        stepped = true
                                    } else {
                                        haptics.buzzBoundaryBump()
                                        accum = 0f
                                        break
                                    }
                                }
                                if (stepped) {
                                    val finalTarget = target
                                    liveCommitJob?.cancel()
                                    liveCommitJob = scope.launch {
                                        delay(320L)
                                        commitLiveTargetIfNeeded(finalTarget)
                                    }
                                }
                            }
                        }
                        onDispose { crownRouter.rawListener = null }
                    }
                    LiveModeScreen(
                        liveState = liveState,
                        voiceState = liveVoiceState,
                        previewIndex = livePreviewIndex,
                        stepInFlight = liveStepInFlight,
                        waitingCount = snapshot.approvalQueue.size,
                        voiceTriggerKey = PrefsStore.VOICE_TRIGGER_MODES[voiceTriggerMode].second,
                        onToggleVoice = { toggleLiveVoice() },
                        onCancelVoice = { cancelLiveVoice() },
                        onDismissError = { liveVoiceState = LiveVoiceState.Idle },
                        onOpenWaiting = {
                            liveMode = false
                            prefs.liveMode = false
                            val firstId = snapshot.approvalQueue.firstOrNull()
                            val idx = snapshot.threads.indexOfFirst { it.id == firstId }
                            if (idx >= 0) scope.launch { pagerState.scrollToPage(agentBase + idx) }
                        },
                        onExitLive = {
                            liveMode = false
                            prefs.liveMode = false
                            haptics.tapTick()
                        }
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
                            if (idx >= 0) scope.launch { pagerState.scrollToPage(agentBase + idx) }
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
                    val pageContent: @Composable (Int) -> Unit = { page ->
                        when (page) {
                            controlsPage -> controlsContent(true, pagerState.settledPage == controlsPage)
                            quotaPage -> QuotasTile(
                                rings = quotaRings,
                                quotas = snapshot.quotas,
                                nowMs = nowMs,
                                onRefresh = { bridge.requestQuotaRefresh() }
                            )
                            homePage -> HomeTile(
                                layout = currentLayout,
                                snapshot = snapshot,
                                clockText = clockText,
                                batteryPct = batteryPct,
                                onSelectThread = { idx ->
                                    scope.launch {
                                        pagerState.animateScrollToPage((agentBase + idx).coerceAtMost(totalPages - 1))
                                    }
                                }
                            )
                            else -> {
                                val threadIdx = page - agentBase
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
                    if (verticalPages) {
                        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page -> pageContent(page) }
                    } else {
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page -> pageContent(page) }
                    }
                }
            }
        }

        // Glowing circular perimeter edge-flash overlay whenever an approval or error is active
        // Not on the Orbit/Pulse home page: there the waiting agent's own aurora is the signal.
        val homeHasOwnSignal = pagerState.currentPage == homePage && currentLayout != HomeLayout.ZEN &&
            !showControls && !showVibration && doneAlertThread == null
        if ((hasPendingApproval || hasError) && voiceState is VoiceUiState.Hidden && !homeHasOwnSignal && !liveMode) {
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
