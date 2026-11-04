package com.example.arfas

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Meta Model API features the app uses beyond chat, each only with a saved Meta key and a
 * working internet connection, each with an on-phone fallback:
 *  - Muse Voice Transcribe: voice input (Hindi/English code-switching), when the user turns on
 *    "Meta online voice" in Settings; Whisper on the phone otherwise and on any failure.
 *  - SAM 3.1: "find / highlight X" in the live camera when the phone's DETR, DINOv2 and CLIP
 *    cannot locate X.
 */
object MetaOnline {
    private const val TAG = "MetaOnline"
    @Volatile private var api: MetaModelApi? = null

    fun api(context: Context): MetaModelApi = api ?: synchronized(this) {
        api ?: run {
            val app = context.applicationContext
            MetaModelApi(
                // Always the saved Meta key: getKey() returned the key of the engine selected in Settings, so with Groq
                // selected (or a Groq key also saved) every Meta call (photo reading, SAM, voice) saw "no Meta key".
                apiKey = { runCatching { GroqKeyStore(app).metaKey() }.getOrDefault("") },
                gate = PrivacyGate(File(app.filesDir, "meta_egress.log"))
            ).also { api = it }
        }
    }

    /** A validated internet connection (not just Wi-Fi without internet). */
    fun hasInternet(context: Context): Boolean = runCatching {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** A Meta (not Groq) key is saved and the internet is reachable. */
    fun ready(context: Context): Boolean = runCatching { api(context).isConfigured && hasInternet(context) }
        .onFailure { Log.w(TAG, "META_ONLINE_READY_CHECK_FAILED ${it.message}") }
        .getOrDefault(false)

    /** Mono 16-bit PCM WAV (what Muse Voice Transcribe accepts) from float samples in [-1, 1]. */
    fun writeWav(samples: FloatArray, count: Int, sampleRate: Int, file: File) {
        val n = count.coerceIn(0, samples.size)
        val dataBytes = n * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1)                       // PCM, mono
            putInt(sampleRate); putInt(sampleRate * 2)     // byte rate
            putShort(2); putShort(16)                      // block align, bits
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) body.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        FileOutputStream(file).use { it.write(header.array()); it.write(body.array()) }
    }
}

/** Growable float buffer for one utterance (voice input), capped at [maxSamples]. */
class PcmAccumulator(private val maxSamples: Int) {
    var samples = FloatArray(16_000 * 4); private set
    var size = 0; private set
    fun add(chunk: FloatArray) {
        val room = maxSamples - size
        if (room <= 0) return
        val take = minOf(room, chunk.size)
        if (size + take > samples.size) samples = samples.copyOf(minOf(maxSamples, maxOf(size + take, samples.size * 2)))
        System.arraycopy(chunk, 0, samples, size, take)
        size += take
    }
}
