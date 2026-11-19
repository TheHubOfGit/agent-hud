package com.agenthud.watch.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeClient(
    private val context: Context,
    private val prefs: PrefsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCOVERING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _snapshot = MutableStateFlow(HudSnapshot())
    val snapshot: StateFlow<HudSnapshot> = _snapshot.asStateFlow()

    private val _lastSeenMs = MutableStateFlow(0L)
    val lastSeenMs: StateFlow<Long> = _lastSeenMs.asStateFlow()

    private val _clockOffsetMs = MutableStateFlow(0L)
    val clockOffsetMs: StateFlow<Long> = _clockOffsetMs.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var activeHost: String = prefs.cachedHost
    private var activePort: Int = prefs.cachedPort
    private var stopped = false
    // Bumped on every connect; callbacks from a replaced socket are ignored. Without this, cancelling
    // the old socket fired its onFailure, which flagged OFFLINE and killed the new socket 2.5s later.
    @Volatile private var connGen = 0

    fun start() {
        stopped = false
        startMdnsDiscovery()
        if (activeHost.isNotBlank()) {
            connectTo(activeHost, activePort)
        } else {
            _connectionState.value = ConnectionState.DISCOVERING
        }
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "Stopping")
        webSocket = null
        stopMdnsDiscovery()
    }

    fun forceReconnect() {
        webSocket?.cancel()
        webSocket = null
        stopMdnsDiscovery()
        startMdnsDiscovery()
        if (activeHost.isNotBlank()) {
            connectTo(activeHost, activePort)
        } else {
            _connectionState.value = ConnectionState.DISCOVERING
        }
    }

    fun setManualEndpoint(host: String, port: Int = prefs.cachedPort) {
        activeHost = host.trim()
        activePort = port
        prefs.cachedHost = activeHost
        prefs.cachedPort = activePort
        connectTo(activeHost, activePort)
    }

    fun requestQuotaRefresh() {
        webSocket?.send("""{"type":"refresh_quotas"}""")
    }

    suspend fun sendAction(threadId: String, action: String, text: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            if (activeHost.isBlank()) return@withContext Result.failure(IllegalStateException("Mac not connected"))
            try {
                val payload = JSONObject().apply {
                    put("threadId", threadId)
                    put("action", action)
                    if (!text.isNullOrBlank()) put("text", text)
                }
                val req = Request.Builder()
                    .url("http://$activeHost:$activePort/action")
                    .header("X-HUD-Token", prefs.authToken)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val msg = runCatching { JSONObject(body).optString("message", "Sent") }.getOrDefault("Sent")
                        Result.success(msg)
                    } else {
                        val reason = runCatching {
                            JSONObject(body).let { it.optString("message").ifBlank { it.optString("error") } }
                        }.getOrNull().orEmpty()
                        Result.failure(IllegalStateException(reason.ifBlank { "Mac returned an error (${resp.code})" }))
                    }
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun transcribeVoice(wavBytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            if (activeHost.isBlank()) return@withContext Result.failure(IllegalStateException("Mac not connected"))
            try {
                val req = Request.Builder()
                    .url("http://$activeHost:$activePort/voice-transcribe")
                    .header("X-HUD-Token", prefs.authToken)
                    .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val text = JSONObject(body).optString("text", "").trim()
                        if (text.isNotEmpty()) Result.success(text)
                        else Result.failure(IllegalStateException("No speech detected"))
                    } else {
                        val err = runCatching { JSONObject(body).optString("error", "STT failed") }.getOrDefault("STT failed")
                        Result.failure(IllegalStateException(err))
                    }
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    private fun connectTo(host: String, port: Int) {
        if (stopped || host.isBlank()) return
        reconnectJob?.cancel()
        webSocket?.cancel()
        _connectionState.value = ConnectionState.CONNECTING

        val token = prefs.authToken
        val url = "ws://$host:$port/ws?token=$token"
        val req = Request.Builder().url(url).build()

        val gen = ++connGen
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connGen) return
                _connectionState.value = ConnectionState.CONNECTED
                _lastSeenMs.value = System.currentTimeMillis()
                prefs.cachedHost = host
                prefs.cachedPort = port
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connGen) return
                _lastSeenMs.value = System.currentTimeMillis()
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connGen) return
                handleDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connGen) return
                handleDisconnect()
            }
        })
    }

    private companion object {
        const val OFFLINE_GRACE_MS = 15_000L
    }

    private fun handleDisconnect() {
        if (stopped) return
        // A blip right after live data stays on the HUD (CONNECTING) instead of flashing the
        // offline screen; only a drop lasting past the grace window shows OFFLINE.
        val recentlyLive = _lastSeenMs.value > 0L && System.currentTimeMillis() - _lastSeenMs.value < OFFLINE_GRACE_MS
        _connectionState.value = when {
            recentlyLive -> ConnectionState.CONNECTING
            _lastSeenMs.value > 0L -> ConnectionState.OFFLINE
            else -> ConnectionState.DISCOVERING
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(if (recentlyLive) 1000 else 2500)
            if (!stopped) {
                if (activeHost.isNotBlank()) {
                    connectTo(activeHost, activePort)
                }
                startMdnsDiscovery()
            }
        }
    }

    private fun parseMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val serverTime = json.optLong("serverTimeMs", 0L)
            if (serverTime > 0L) {
                _clockOffsetMs.value = serverTime - System.currentTimeMillis()
            }
            if (json.optString("type") != "snapshot") return
            // Arrival log for latency checks: compare with the bridge's send time (serverTimeMs).
            android.util.Log.d("AgentHUD", "snapshot seq=${json.optLong("seq")} threads=${json.optJSONArray("threads")?.let { a -> (0 until a.length()).joinToString(",") { i -> a.optJSONObject(i)?.let { o -> o.optString("provider").take(6) + ":" + o.optString("status") } ?: "?" } } ?: ""} sentAt=$serverTime recvAt=${System.currentTimeMillis()}")

            val queueArr = json.optJSONArray("approvalQueue") ?: JSONArray()
            val approvalQueue = buildList {
                for (i in 0 until queueArr.length()) add(queueArr.optString(i))
            }

            val threadsArr = json.optJSONArray("threads") ?: JSONArray()
            val threads = buildList {
                for (i in 0 until threadsArr.length()) {
                    val o = threadsArr.optJSONObject(i) ?: continue
                    val optsArr = o.optJSONArray("options") ?: JSONArray()
                    val opts = buildList {
                        for (j in 0 until optsArr.length()) {
                            val s = optsArr.optString(j)
                            if (s.isNotBlank()) add(s)
                        }
                    }
                    add(
                        AgentThread(
                            id = o.optString("id"),
                            provider = o.optString("provider"),
                            providerLabel = o.optString("providerLabel", "Agent"),
                            source = o.optString("source", "desktop"),
                            title = o.optString("title", "Session"),
                            status = o.optString("status", "idle"),
                            step = o.optString("step", ""),
                            prompt = if (o.isNull("prompt")) null else o.optString("prompt", "").ifBlank { null },
                            options = opts,
                            startedAtMs = o.optLong("startedAtMs", 0L),
                            updatedAtMs = o.optLong("updatedAtMs", 0L),
                            colorHex = o.optString("color", "#8ab4f8"),
                            canReply = o.optBoolean("canReply", false),
                            kind = o.optString("kind", ""),
                            multiSelect = o.optBoolean("multiSelect", false),
                            doneAtMs = o.optLong("doneAtMs", 0L)
                        )
                    )
                }
            }

            val quotasArr = json.optJSONArray("quotas") ?: JSONArray()
            val quotas = buildList {
                for (i in 0 until quotasArr.length()) {
                    val q = quotasArr.optJSONObject(i) ?: continue
                    add(
                        QuotaItem(
                            id = q.optString("id"),
                            name = q.optString("name"),
                            colorHex = q.optString("color", "#81c995"),
                            remainingPct = q.optInt("remainingPct", 0),
                            resetShort = q.optString("resetShort", ""),
                            resetClock = q.optString("resetClock", ""),
                            windowLabel = q.optString("windowLabel", "Window"),
                            secondaryRemainingPct = if (q.isNull("secondaryRemainingPct")) null else q.optInt("secondaryRemainingPct"),
                            secondaryResetShort = if (q.isNull("secondaryResetShort")) null else q.optString("secondaryResetShort"),
                            plan = q.optString("plan", ""),
                            windowMinutes = q.optInt("windowMinutes", 0),
                            resetsAtMs = q.optLong("resetsAtMs", 0L),
                            secondaryWindowMinutes = q.optInt("secondaryWindowMinutes", 0),
                            secondaryResetsAtMs = q.optLong("secondaryResetsAtMs", 0L)
                        )
                    )
                }
            }

            _snapshot.value = HudSnapshot(
                seq = json.optLong("seq", 0L),
                serverTimeMs = serverTime,
                macHost = json.optString("macHost", ""),
                activeCount = json.optInt("activeCount", 0),
                errorCount = json.optInt("errorCount", 0),
                approvalQueue = approvalQueue,
                threads = threads,
                quotas = quotas
            )
        } catch (_: Throwable) {
            // Ignore malformed JSON
        }
    }

    private fun startMdnsDiscovery() {
        stopMdnsDiscovery()
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_agenthud")) {
                    @Suppress("DEPRECATION")
                    mgr.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = serviceInfo.host?.hostAddress ?: return
                            val port = serviceInfo.port
                            if (host != activeHost && _connectionState.value != ConnectionState.CONNECTED) {
                                activeHost = host
                                activePort = port
                                connectTo(host, port)
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        try {
            mgr.discoverServices("_agenthud._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
        }
    }

    private fun stopMdnsDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (_: Throwable) {
        }
    }
}
