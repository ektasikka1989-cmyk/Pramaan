package com.example.arfas

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Shared helpers for the ArogyaDoot device tests (androidTest, debug only). */
object ArogyaSelfTest {
    const val TAG = "ArogyaSelfTest"
    data class Check(val area: String, val name: String, val ok: Boolean, val ms: Long, val detail: String)

    fun ctx(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    fun args(): Map<String, String> = runCatching {
        val b = InstrumentationRegistry.getArguments()
        b.keySet().associateWith { b.getString(it).orEmpty() }
    }.getOrDefault(emptyMap())

    fun mark(s: String) = Log.i(TAG, "AROGYA_SELFTEST $s")

    /** Same wiring as MainActivity: process graph + saved key + Settings order. */
    fun newAi(c: Context): ArogyaVisitAi {
        val graph = FixSightRuntimeGraph.get(c)
        val ai = ArogyaVisitAi(graph.runtime, graph.phase1Features, { runCatching { GroqKeyStore(c).getKey() }.getOrDefault("") },
            graph.attachmentManager, { graph.ensureLocalModelReady() }, c.applicationContext)
        ai.cloudChoice = { ArogyaCloud.choice(c.applicationContext) }
        return ai
    }

    fun policy(c: Context): AnswerPolicy = AnswerPolicyStore(c.applicationContext).get()
    fun setPolicy(c: Context, p: AnswerPolicy) = AnswerPolicyStore(c.applicationContext).set(p)

    fun resultsDir(c: Context): File = File(c.getExternalFilesDir(null), "selftest").apply { mkdirs() }

    fun writeResults(c: Context, name: String, json: JSONObject, md: String) {
        val dir = resultsDir(c)
        File(dir, "$name.json").writeText(json.toString(2))
        File(dir, "$name.md").writeText(md)
        mark("$name written (${json.optJSONArray("checks")?.length() ?: 0} checks)")
    }

    fun checksJson(checks: List<Check>): JSONArray = JSONArray().apply {
        checks.forEach { put(JSONObject().put("area", it.area).put("name", it.name).put("ok", it.ok).put("ms", it.ms).put("detail", it.detail.take(4000))) }
    }

    fun mdReport(title: String, meta: Map<String, String>, checks: List<Check>, extra: String = ""): String = buildString {
        append("# $title\n\n")
        meta.forEach { (k, v) -> append("- $k: $v\n") }
        val fails = checks.filter { !it.ok }
        append("- checks: ${checks.size - fails.size}/${checks.size} passed\n\n")
        append("| area | check | ok | ms | detail |\n|---|---|---|---|---|\n")
        checks.forEach { append("| ${it.area} | ${it.name} | ${if (it.ok) "PASS" else "FAIL"} | ${it.ms} | ${(it.detail.take(160).replace("\n", " "))} |\n") }
        if (extra.isNotBlank()) append("\n$extra\n")
    }

    /** Own-process logcat lines (same UID can read its own logs). Best effort; "" when unavailable. */
    fun ownLogLines(vararg needles: String, tail: Int = 2000): List<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", tail.toString(), "-v", "brief"))
        if (!p.waitFor(15, TimeUnit.SECONDS)) return emptyList()
        p.inputStream.bufferedReader().readLines().filter { line -> needles.any { line.contains(it) } }
    }.getOrDefault(emptyList())

    // ── Synthetic voice (Android TTS → 16 kHz mono PCM) ──────────────────────
    // Labelled "synthetic voice" everywhere. This tests the STT engines, not the microphone.
    data class Synth(val pcm16k: FloatArray, val wavFile: File, val engine: String, val langStatus: String)

    fun synthSpeech(c: Context, text: String, locale: Locale, tag: String): Synth = runBlocking {
        val initLatch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var initStatus = -99
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            tts = TextToSpeech(c.applicationContext) { status -> initStatus = status; initLatch.countDown() }
        }
        if (!initLatch.await(20, TimeUnit.SECONDS)) error("TTS init timed out")
        val engine = tts ?: error("TTS engine missing")
        try {
            check(initStatus == TextToSpeech.SUCCESS) { "TTS init failed: $initStatus" }
            val langStatus = engine.setLanguage(locale)
            val langStr = when (langStatus) {
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "ok($langStatus)"
                else -> "missing($langStatus)"
            }
            val wav = File(c.cacheDir, "selftest_$tag.wav").apply { delete() }
            val done = CountDownLatch(1)
            var utterErr = ""
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { done.countDown() }
                override fun onError(id: String) { utterErr = "utterance error"; done.countDown() }
                @Deprecated("deprecated") override fun onError(id: String, code: Int) { utterErr = "utterance error $code"; done.countDown() }
            })
            val utterId = UUID.randomUUID().toString()
            val rc = engine.synthesizeToFile(text, null, wav, utterId)
            check(rc == TextToSpeech.SUCCESS) { "synthesizeToFile rejected: $rc" }
            check(done.await(60, TimeUnit.SECONDS)) { "TTS synth timed out" }
            check(utterErr.isEmpty()) { utterErr }
            check(wav.exists() && wav.length() > 1000) { "TTS produced no audio (${wav.length()} bytes)" }
            withContext(Dispatchers.Default) { Synth(wavToPcm16k(wav), wav, engine.defaultEngine ?: "?", langStr) }
        } finally {
            runCatching { engine.stop() }; runCatching { engine.shutdown() }
        }
    }

    /** Minimal 16-bit PCM WAV decode + linear resample to 16 kHz mono float. */
    fun wavToPcm16k(wav: File): FloatArray {
        val b = wav.readBytes(); check(b.size > 44 && b[0] == 'R'.code.toByte()) { "not a WAV file" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var sampleRate = 0; var channels = 0; var bits = 0; var dataAt = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII); val len = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> { check(bb.getShort(pos + 8).toInt() == 1) { "TTS WAV is not PCM" }; channels = bb.getShort(pos + 10).toInt(); sampleRate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
                "data" -> { dataAt = pos + 8; dataLen = len }
            }
            pos += 8 + len
        }
        check(sampleRate > 0 && dataAt > 0 && bits == 16) { "unsupported WAV fmt sr=$sampleRate bits=$bits" }
        val frames = dataLen / 2 / channels.coerceAtLeast(1)
        val mono = FloatArray(frames) { i ->
            var s = 0.0; for (ch in 0 until channels) s += bb.getShort(dataAt + (i * channels + ch) * 2).toDouble() / 32768.0
            (s / channels).toFloat()
        }
        if (sampleRate == 16000) return mono
        val outLen = (mono.size.toLong() * 16000 / sampleRate).toInt().coerceAtLeast(1)
        return FloatArray(outLen) { i ->
            val src = i.toDouble() * sampleRate / 16000; val j = src.toInt().coerceIn(0, mono.size - 1); val k = (j + 1).coerceIn(0, mono.size - 1)
            (mono[j] * (1 - (src - j)) + mono[k] * (src - j)).toFloat()
        }
    }
}
