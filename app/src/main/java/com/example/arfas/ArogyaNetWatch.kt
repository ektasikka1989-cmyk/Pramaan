package com.example.arfas

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * One connectivity watcher for instant offline fallback. Every cloud funnel
 * ([ArogyaCloud.withDeadline]) records [generation] when it starts; when the network
 * drops mid-call, [generation] moves and the funnel returns immediately so the phone
 * continues instead of waiting out the timeout. The in-flight socket is also cancelled
 * via [onLost] (cloud calls only — local generation is untouched).
 */
object ArogyaNetWatch {
    private const val TAG = "ArogyaDemo"
    @Volatile var online = true
        private set
    @Volatile var generation = 0L
        private set
    @Volatile var lastLostAt = 0L
        private set
    /** Set by the screen: cancel in-flight cloud calls (never local work). */
    @Volatile var onLost: (() -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True when the network dropped within the last [windowMs] (a cancel then means fallback, not abort). */
    fun justLost(windowMs: Long = 10_000L): Boolean = System.currentTimeMillis() - lastLostAt < windowMs

    @Synchronized
    fun register(app: Context) {
        if (callback != null) return
        val cm = app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        online = MetaOnline.hasInternet(app)
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) {
                online = false; generation++; lastLostAt = System.currentTimeMillis()
                Log.i(TAG, "AROGYA_NET_LOST gen=$generation")
                runCatching { onLost?.invoke() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback!!) }
    }

    @Synchronized
    fun unregister(app: Context) {
        val cb = callback ?: return
        callback = null
        runCatching { (app.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
    }
}
