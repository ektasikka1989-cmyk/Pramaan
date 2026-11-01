package com.example.arfas

import android.os.Debug
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes raster-heavy work so attachment OCR/VLM and Live Video VLM do not peak together. */
object VisionWorkCoordinator {
    private const val TAG = "VisionWork"
    private val mutex = Mutex()

    suspend fun <T> withVisionWork(stage: String, block: suspend () -> T): T = mutex.withLock {
        memorySnapshot("before_$stage")
        try {
            block()
        } finally {
            memorySnapshot("after_$stage")
        }
    }

    fun memorySnapshot(stage: String) {
        val rt = Runtime.getRuntime()
        val javaUsed = rt.totalMemory() - rt.freeMemory()
        Log.i(
            TAG,
            "MEMORY_SNAPSHOT rev=V10_0_STABILITY stage=$stage " +
                "javaUsedMb=${javaUsed / (1024 * 1024)} javaMaxMb=${rt.maxMemory() / (1024 * 1024)} " +
                "nativeHeapMb=${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)}"
        )
    }
}
