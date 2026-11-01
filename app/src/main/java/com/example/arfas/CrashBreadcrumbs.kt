package com.example.arfas

import android.util.Log
import java.util.ArrayDeque

/** Tiny in-memory ring buffer used only for diagnostics around native/vision/session failures. */
object CrashBreadcrumbs {
    private const val TAG = "CrashBreadcrumbs"
    private const val MAX = 24
    private val lock = Any()
    private val events = ArrayDeque<String>(MAX)

    fun add(event: String) {
        val line = "${System.currentTimeMillis()} ${event.take(220)}"
        synchronized(lock) {
            if (events.size >= MAX) events.removeFirst()
            events.addLast(line)
        }
        Log.d(TAG, line)
    }

    fun snapshot(): String = synchronized(lock) { events.joinToString(" | ") }
}
