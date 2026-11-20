package com.agenthud.watch.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Asks Android for Wi-Fi while the HUD is on screen.
 *
 * Off the charger, Wear OS turns Wi-Fi off a few minutes after the screen goes dark, and with
 * Bluetooth disabled the watch then has no link to the bridge at all. A held network request makes
 * the system bring Wi-Fi back up as soon as the HUD is visible again, instead of minutes later.
 * The request is released when the HUD leaves the screen, so it costs nothing while the watch sleeps.
 * (The foreground service's WifiLock keeps an existing connection fast; it does not stop Wear OS
 * from switching Wi-Fi off.)
 */
class WifiRequester(context: Context, private val onWifiAvailable: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun acquire() {
        val cm = cm ?: return
        if (callback != null) return
        // The bridge is on the local network, so don't wait for an internet check to pass.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Route this app's sockets over Wi-Fi even if another network is the default.
                runCatching { cm.bindProcessToNetwork(network) }
                Log.i(TAG, "Wi-Fi available")
                main.post(onWifiAvailable)
            }

            override fun onLost(network: Network) {
                if (cm.boundNetworkForProcess == network) runCatching { cm.bindProcessToNetwork(null) }
                Log.i(TAG, "Wi-Fi lost")
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
            Log.i(TAG, "Requested Wi-Fi")
        } catch (t: Throwable) {
            Log.w(TAG, "Wi-Fi request failed: ${t.message}")
        }
    }

    fun release() {
        val cb = callback ?: return
        callback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
        runCatching { cm?.bindProcessToNetwork(null) }
        Log.i(TAG, "Released Wi-Fi request")
    }

    private companion object {
        const val TAG = "AgentHUD"
    }
}
