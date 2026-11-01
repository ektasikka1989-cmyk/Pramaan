package com.example.arfas

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Cheap local frame-change gate. No model, no domain rules and no cloud call.
 * It prevents Video mode from uploading an unchanged AR frame repeatedly while
 * still forcing a periodic refresh so slow scene changes are eventually observed.
 */
class VisualFrameChangeDetector {
    private var lastHash: LongArray? = null
    private var lastAnalyzedAtMs: Long = 0L

    fun reset() {
        lastHash = null
        lastAnalyzedAtMs = 0L
    }

    fun shouldAnalyze(bitmap: Bitmap, nowMs: Long = System.currentTimeMillis()): Boolean {
        val hash = differenceHash(bitmap)
        val previous = lastHash
        if (previous != null && nowMs - lastAnalyzedAtMs < MIN_ANALYZE_INTERVAL_MS) return false
        val stale = nowMs - lastAnalyzedAtMs >= FORCE_REFRESH_MS
        val changed = previous == null || hamming(previous, hash) >= MIN_CHANGED_BITS
        if (changed || stale) {
            lastHash = hash
            lastAnalyzedAtMs = nowMs
            return true
        }
        return false
    }

    private fun differenceHash(bitmap: Bitmap): LongArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 17, 16, true)
        return try {
            val pixels = IntArray(17 * 16)
            scaled.getPixels(pixels, 0, 17, 0, 0, 17, 16)
            val out = LongArray(4)
            var bit = 0
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val a = luminance(pixels[y * 17 + x])
                    val b = luminance(pixels[y * 17 + x + 1])
                    if (a > b) out[bit ushr 6] = out[bit ushr 6] or (1L shl (bit and 63))
                    bit++
                }
            }
            out
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    private fun hamming(a: LongArray, b: LongArray): Int {
        var bits = 0
        for (i in a.indices) bits += java.lang.Long.bitCount(a[i] xor b[i])
        return bits
    }

    companion object {
        private const val MIN_CHANGED_BITS = 18
        private const val MIN_ANALYZE_INTERVAL_MS = 4_000L
        private const val FORCE_REFRESH_MS = 10_000L
    }
}
