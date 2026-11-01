package com.example.arfas

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton OkHttp client shared by all AI providers.
 *
 * readTimeout 45s: reasoning_effort=none means responses arrive in 2–8s normally,
 * but mobile network variance needs headroom. If we hit 45s, the fallback()
 * speaks a message — the app never silently hangs.
 *
 * Connection pool: reuses the TLS session across turns (~150ms saved per turn).
 */
object NetworkClient {
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(3, 90, TimeUnit.SECONDS))
            .connectTimeout(3, TimeUnit.SECONDS)   // fast-fail offline: 3s not 10s
            .readTimeout(45,   TimeUnit.SECONDS)
            .writeTimeout(20,  TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)       // no retry in OkHttp — we handle retries in makeRequestWithRetry
            .build()
    }
}