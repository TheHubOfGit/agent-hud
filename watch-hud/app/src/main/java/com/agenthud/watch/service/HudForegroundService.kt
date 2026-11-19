package com.agenthud.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.agenthud.watch.MainActivity

class HudForegroundService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val adbWifiTick = object : Runnable {
        override fun run() {
            AdbWifiKeeper.ensure(this@HudForegroundService)
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channelId = "agent_hud_link"
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Agent HUD Link", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Agent HUD")
            .setContentText("Live Mac telemetry link active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(101, notification)

        // Hold low-latency Wi-Fi lock + partial wake lock so Wear OS never suspends the WebSocket
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AgentHUD:WifiLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentHUD:CpuLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        // Wireless debugging resets on reboot and Wi-Fi drops; keep turning it back on.
        handler.post(adbWifiTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(adbWifiTick)
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            AdbWifiKeeper.ensure(context)
            runCatching {
                context.startForegroundService(Intent(context, HudForegroundService::class.java))
            }
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { context.startActivity(launch) }
        }
    }
}
