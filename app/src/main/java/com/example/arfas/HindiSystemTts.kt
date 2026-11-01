package com.example.arfas

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hindi speech for Devanagari answers. The bundled offline voice (sherpa-onnx VITS) is English;
 * Hindi text goes to Android's own hi-IN voice (Google / Samsung speech engine, works offline
 * once the Hindi voice data is on the phone, which it is on most Indian phones).
 */
class HindiSystemTts(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS && setHindi()
        Log.i(TAG, "HINDI_TTS_INIT ok=$ok")
        ready.complete(ok)
    }
    private val pending = HashMap<String, CompletableDeferred<Boolean>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId, false) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId, false) }
        })
    }

    private fun setHindi(): Boolean {
        val hi = Locale("hi", "IN")
        val availability = tts.isLanguageAvailable(hi)
        if (availability < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "HINDI_TTS_VOICE_MISSING availability=$availability")
            return false
        }
        tts.language = hi
        return true
    }

    private fun finish(id: String?, ok: Boolean) {
        synchronized(pending) { pending.remove(id ?: return) }?.complete(ok)
    }

    suspend fun isAvailable(): Boolean = withTimeoutOrNull(3_000) { ready.await() } == true

    /** Speaks [text] and returns when it has finished (false: not available or failed). */
    suspend fun speakAndWait(text: String, speed: Float = 1.0f): Boolean {
        if (text.isBlank() || !isAvailable()) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[id] = done }
        tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
        val rc = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (rc != TextToSpeech.SUCCESS) {
            synchronized(pending) { pending.remove(id) }
            return false
        }
        // About 12 characters a second for Hindi speech, plus margin.
        return withTimeoutOrNull(10_000L + text.length * 150L) { done.await() } ?: run { tts.stop(); false }
    }

    fun stop() { runCatching { tts.stop() } }

    fun release() { runCatching { tts.stop(); tts.shutdown() } }

    companion object {
        private const val TAG = "HindiSystemTts"
        fun hasDevanagari(text: String): Boolean = text.count { it.code in 0x0900..0x097F } >= 2
    }
}
