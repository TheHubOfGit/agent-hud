package com.agenthud.watch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.agenthud.watch.audio.VoiceRecorder
import com.agenthud.watch.data.BridgeClient
import com.agenthud.watch.data.PrefsStore
import com.agenthud.watch.service.HapticsEngine
import com.agenthud.watch.service.HudForegroundService
import com.agenthud.watch.service.WifiRequester
import com.agenthud.watch.ui.HudRoot

class MainActivity : ComponentActivity() {
    private lateinit var prefs: PrefsStore
    private lateinit var bridge: BridgeClient
    private lateinit var haptics: HapticsEngine
    private lateinit var wifi: WifiRequester
    private val voiceRecorder = VoiceRecorder()

    private var batteryPct by mutableIntStateOf(65)
    private var batteryTempC by mutableFloatStateOf(32.0f)
    private var isDocked by mutableStateOf(true)
    private var liveRecording by mutableStateOf(false)
    /** Bumped on every crown press (Home) while the HUD is the home app; HudRoot goes home. */
    private var homePresses by mutableIntStateOf(0)
    private var screenMode by mutableIntStateOf(0)
    @Volatile private var lastInteractionMs = System.currentTimeMillis()
    private val screenHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val screenTimeoutCheck = Runnable { updateScreenKeepOn() }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result checked dynamically */ }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100) / scale
            }
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320)
            batteryTempC = tempTenths / 10f

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val nowDocked = plugged != 0
            if (nowDocked != isDocked) {
                isDocked = nowDocked
                updateScreenKeepOn()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PrefsStore(this)
        screenMode = prefs.screenMode
        haptics = HapticsEngine(this)
        bridge = BridgeClient(applicationContext, prefs)
        wifi = WifiRequester(applicationContext) { bridge.reconnectIfDown() }

        // Fullscreen immersive appliance mode (hide system bars & status overlays)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        updateScreenKeepOn()
        applyBrightness(if (prefs.nightMode) 0.12f else prefs.brightnessLevel)

        // Start foreground service for persistent low-latency Wi-Fi + wake lock
        runCatching {
            startForegroundService(Intent(this, HudForegroundService::class.java))
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val explicitHost = intent?.getStringExtra("host")
        if (!explicitHost.isNullOrBlank()) {
            prefs.cachedHost = explicitHost
        }
        bridge.start()

        setContent {
            HudRoot(
                bridge = bridge,
                prefs = prefs,
                haptics = haptics,
                voiceRecorder = voiceRecorder,
                batteryPct = batteryPct,
                batteryTempC = batteryTempC,
                isDocked = isDocked,
                screenHeldOn = isDocked || PrefsStore.SCREEN_MODES[screenMode].second != 0,
                screenModeLabel = PrefsStore.SCREEN_MODES[screenMode].first,
                onCycleScreenMode = {
                    screenMode = (screenMode + 1) % PrefsStore.SCREEN_MODES.size
                    prefs.screenMode = screenMode
                    lastInteractionMs = System.currentTimeMillis()
                    updateScreenKeepOn()
                },
                hasMicPermission = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
                requestMicPermission = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onApplyBrightness = { applyBrightness(it) },
                onWakeScreen = { wakeDisplayForAlert() },
                onLiveRecordingChanged = { active ->
                    if (liveRecording != active) {
                        liveRecording = active
                        updateScreenKeepOn()
                    }
                },
                homePresses = homePresses,
                onOpenWatchFace = { openStockLauncher() },
                onOpenWatchApps = {
                    // The stock app list, explicitly (the crown no longer opens it).
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_ALL_APPS)
                                .setClassName(
                                    "com.google.android.wearable.sysui",
                                    "com.google.android.clockwork.sysui.experiences.globallauncher.RecentsActivity"
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onOpenWatchSettings = {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            )
        }
    }

    /** The stock Pixel Watch launcher (watch face, tiles, app list). Crown press comes back here. */
    private fun openStockLauncher() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage("com.google.android.wearable.sysui")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With the HUD set as the home app, pressing the crown delivers a HOME intent here.
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (isHome || intent.action == Intent.ACTION_ALL_APPS) {
            homePresses++
        }
        val explicitHost = intent.getStringExtra("host")
        if (!explicitHost.isNullOrBlank()) {
            bridge.setManualEndpoint(explicitHost)
        }
    }

    // Any touch or crown turn counts as interaction for the timed screen modes.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        noteInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        noteInteraction()
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun noteInteraction() {
        lastInteractionMs = System.currentTimeMillis()
        if (!isDocked && PrefsStore.SCREEN_MODES[screenMode].second > 0 &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0
        ) {
            updateScreenKeepOn()
        }
    }

    // Wi-Fi is requested only while the HUD is on screen and the display is awake (see WifiRequester).
    // Screen-off on this watch is ambient mode: the activity stays resumed, so onStop alone never
    // fires. The screen on/off broadcasts cover that case.
    private var started = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateWifiRequest()
    }

    private fun updateWifiRequest() {
        val awake = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (started && awake) wifi.acquire() else wifi.release()
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        updateWifiRequest()
    }

    override fun onStop() {
        started = false
        runCatching { unregisterReceiver(screenReceiver) }
        wifi.release()
        super.onStop()
    }

    override fun onDestroy() {
        screenHandler.removeCallbacks(screenTimeoutCheck)
        runCatching { unregisterReceiver(batteryReceiver) }
        bridge.stop()
        super.onDestroy()
    }

    /**
     * Smart Dock vs Off-Dock Portable Power Management:
     * - Plugged into wall dock: Always-on 24/7 (FLAG_KEEP_SCREEN_ON).
     * - Carried off-dock into another room: Allows idle screen dim to preserve battery,
     *   while wakeDisplayForAlert() instantly wakes the screen when an approval/question arrives.
     */
    private fun updateScreenKeepOn() {
        // FLAG_KEEP_SCREEN_ON only applies while this window is showing, so other apps and the
        // stock watch face still time out normally. Timed modes hold it for N minutes after the
        // last touch/crown turn, then release it so the system's own timeout takes over.
        screenHandler.removeCallbacks(screenTimeoutCheck)
        val mins = PrefsStore.SCREEN_MODES[screenMode].second
        val holdOn = when {
            isDocked || liveRecording || mins < 0 -> true
            mins == 0 -> false
            else -> {
                val remaining = mins * 60_000L - (System.currentTimeMillis() - lastInteractionMs)
                if (remaining > 0) screenHandler.postDelayed(screenTimeoutCheck, remaining)
                remaining > 0
            }
        }
        if (holdOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyBrightness(level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.08f, 1.0f)
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun wakeDisplayForAlert() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AgentHUD:AlertWake"
            )
            wl.acquire(15_000L)
        }
        applyBrightness(prefs.brightnessLevel)
    }
}
