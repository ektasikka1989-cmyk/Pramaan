package com.example.arfas

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "StreamingTTSManager"

/**
 * Hardened streaming TTS boundary.
 *
 * Responsibilities:
 * 1. Accept only spoken content.
 * 2. Never speak JSON metadata / model scaffolding.
 * 3. Suppress exact user-utterance echo.
 * 4. Serialize Android TTS calls onto the main thread.
 * 5. Invalidate old queued callbacks when a stream is interrupted.
 * 6. Keep exactly one utterance active in the Android TTS engine.
 */
class StreamingTTSManager(
    private val tts: TextToSpeech,
    private val audioManager: AudioManager
) {
    var onFirstSentenceStarted: (() -> Unit)? = null
    var onAllDone: (() -> Unit)? = null

    private data class QueuedSentence(
        val generation: Long,
        val text: String
    )

    private val sentenceQueue = ConcurrentLinkedQueue<QueuedSentence>()

    private val isSpeaking = AtomicBoolean(false)
    private val hasFiredFirstSentence = AtomicBoolean(false)
    private val isStreamActive = AtomicBoolean(false)

    private val utteranceCounter = AtomicInteger(0)
    private val streamGeneration = AtomicLong(0L)
    private val allDoneGeneration = AtomicLong(-1L)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastUserUtteranceNormalized: String = ""

    @Volatile
    private var lastSpokenNormalized: String = ""

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                if (!isCurrentUtterance(utteranceId)) return

                if (hasFiredFirstSentence.compareAndSet(false, true)) {
                    onFirstSentenceStarted?.invoke()
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!isCurrentUtterance(utteranceId)) return
                processNextInQueue()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!isCurrentUtterance(utteranceId)) return
                Log.e(TAG, "TTS utterance error: $utteranceId")
                isSpeaking.set(false)
                processNextInQueue()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!isCurrentUtterance(utteranceId)) return
                Log.e(TAG, "TTS utterance error id=$utteranceId code=$errorCode")
                isSpeaking.set(false)
                processNextInQueue()
            }
        })
    }

    /**
     * Tell the TTS layer what the user most recently said.
     *
     * This is intentionally separate from STT so the TTS manager can reject
     * an exact/near-exact echo even if an upstream model accidentally returns it.
     */
    fun setLastUserUtterance(text: String) {
        lastUserUtteranceNormalized = normalizeForComparison(text)
    }

    fun beginStream() {
        interrupt()

        val generation = streamGeneration.incrementAndGet()
        allDoneGeneration.set(-1L)

        isStreamActive.set(true)
        hasFiredFirstSentence.set(false)
        lastSpokenNormalized = ""

        Log.d(TAG, "beginStream generation=$generation")
    }

    /**
     * Accepts a model-produced chunk.
     *
     * The caller may pass:
     * - already-clean sentence text
     * - complete JSON
     * - partial streamed JSON fragments
     * - model scaffolding / metadata
     *
     * Only speech-safe text is allowed into the queue.
     */
    fun speakSentence(rawChunk: String) {
        if (rawChunk.isBlank()) return

        val generation = streamGeneration.get()
        val candidates = extractSpeechCandidates(rawChunk)

        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            val clean = sanitizeSpeechText(candidate)

            if (clean.isBlank()) continue
            if (looksLikeModelMetadata(clean)) continue
            if (isUserEcho(clean)) {
                Log.d(TAG, "Suppressed TTS echo: ${clean.take(80)}")
                continue
            }

            val normalized = normalizeForComparison(clean)
            if (normalized.isNotBlank() && normalized == lastSpokenNormalized) {
                Log.d(TAG, "Suppressed duplicate TTS: ${clean.take(80)}")
                continue
            }

            sentenceQueue.add(
                QueuedSentence(
                    generation = generation,
                    text = clean
                )
            )
            lastSpokenNormalized = normalized
        }

        if (!isSpeaking.get()) {
            processNextInQueue()
        }
    }

    /**
     * Marks the current model stream complete.
     *
     * onAllDone is fired only after:
     * - this stream is inactive
     * - this generation's queue is empty
     * - no utterance is speaking
     */
    fun onStreamComplete() {
        val generation = streamGeneration.get()

        isStreamActive.set(false)

        // Remove any queue entries belonging to an obsolete generation.
        removeStaleQueueEntries(generation)

        if (!isSpeaking.get() && sentenceQueue.isEmpty()) {
            notifyAllDoneOnce(generation)
        }
    }

    fun setLanguageMode(mode: LanguageMode) {
        val locale = when (mode) {
            LanguageMode.HINDI -> Locale("hi", "IN")
            LanguageMode.HINGLISH -> Locale("hi", "IN")
            LanguageMode.ENGLISH -> Locale.US
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            tts.language = locale
        } else {
            mainHandler.post {
                if (streamGeneration.get() >= 0) {
                    tts.language = locale
                }
                Unit
            }
        }
    }


    fun setLanguageTag(languageTag: String) {
        val clean = languageTag.trim()
        if (clean.isBlank()) return
        val locale = Locale.forLanguageTag(clean)
        if (locale.language.isBlank()) return
        val applyLocale: () -> Unit = {
            val availability = tts.isLanguageAvailable(locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            } else {
                Log.w(TAG, "TTS language unavailable tag=$clean")
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) applyLocale()
        else mainHandler.post(applyLocale)
    }

    /**
     * Immediately invalidates the current generation.
     *
     * This is important: Android TTS callbacks are asynchronous. A queued
     * main-thread speak() from a previous turn must not survive an interrupt.
     */
    fun interrupt() {
        val invalidatedGeneration = streamGeneration.incrementAndGet()

        sentenceQueue.clear()
        isSpeaking.set(false)
        hasFiredFirstSentence.set(false)
        isStreamActive.set(false)
        allDoneGeneration.set(-1L)

        val stopAction: () -> Unit = {
            try {
                if (tts.isSpeaking) {
                    tts.stop()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tts.stop failed: ${t.message}")
            }
            Unit
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopAction()
        } else {
            mainHandler.post(stopAction)
        }

        Log.d(
            TAG,
            "interrupt invalidated generation=$invalidatedGeneration"
        )
    }

    fun speakFull(text: String) {
        beginStream()
        speakSentence(text)
        onStreamComplete()
    }

    private fun processNextInQueue() {
        val generation = streamGeneration.get()

        removeStaleQueueEntries(generation)

        val next = sentenceQueue.poll()

        if (next == null) {
            isSpeaking.set(false)

            if (
                !isStreamActive.get() &&
                generation == streamGeneration.get()
            ) {
                notifyAllDoneOnce(generation)
            }

            return
        }

        // A newer stream invalidated this item while it was queued.
        if (next.generation != generation) {
            isSpeaking.set(false)
            processNextInQueue()
            return
        }

        isSpeaking.set(true)

        val utteranceId =
            "stream_${generation}_utt_${utteranceCounter.incrementAndGet()}"

        val params = Bundle().apply {
            putFloat(
                TextToSpeech.Engine.KEY_PARAM_VOLUME,
                1.0f
            )
            putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                AudioManager.STREAM_MUSIC
            )
        }

        val speakAction: () -> Unit = speakAction@{
            if (next.generation != streamGeneration.get()) {
                isSpeaking.set(false)
                return@speakAction
            }
            try {
                val rc = tts.speak(
                    next.text,
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    utteranceId
                )
                Log.d(TAG, "tts.speak id=$utteranceId rc=$rc text=${next.text.take(80)}")
                if (rc == TextToSpeech.ERROR) {
                    isSpeaking.set(false)
                    retryCurrentUtterance(next.text, next.generation)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "tts.speak failed: ${t.message}", t)
                isSpeaking.set(false)
                if (next.generation == streamGeneration.get()) processNextInQueue()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            speakAction()
        } else {
            mainHandler.post {
                if (next.generation != streamGeneration.get()) {
                    isSpeaking.set(false)
                    return@post
                }

                try {
                    val rc = tts.speak(
                        next.text,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        utteranceId
                    )
                    Log.d(TAG, "tts.speak id=$utteranceId rc=$rc text=${next.text.take(80)}")
                    if (rc == TextToSpeech.ERROR) {
                        isSpeaking.set(false)
                        retryCurrentUtterance(next.text, next.generation)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "tts.speak failed: ${t.message}", t)
                    isSpeaking.set(false)

                    if (next.generation == streamGeneration.get()) {
                        processNextInQueue()
                    }
                }
            }
        }
    }

    private fun retryCurrentUtterance(text: String, generation: Long) {
        if (generation != streamGeneration.get()) return
        mainHandler.postDelayed({
            if (generation != streamGeneration.get()) return@postDelayed
            if (isSpeaking.get()) return@postDelayed
            isSpeaking.set(true)
            val retryId = "stream_${generation}_retry_${utteranceCounter.incrementAndGet()}"
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            try {
                val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, retryId)
                Log.d(TAG, "tts.retry id=$retryId rc=$rc text=${text.take(80)}")
                if (rc == TextToSpeech.ERROR) {
                    isSpeaking.set(false)
                    if (!isStreamActive.get() && sentenceQueue.isEmpty()) {
                        notifyAllDoneOnce(generation)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "tts.retry failed: ${t.message}", t)
                isSpeaking.set(false)
                if (!isStreamActive.get() && sentenceQueue.isEmpty()) {
                    notifyAllDoneOnce(generation)
                }
            }
        }, 250L)
    }

    private fun isCurrentUtterance(utteranceId: String?): Boolean {
        if (utteranceId.isNullOrBlank()) return false

        val generation = streamGeneration.get()
        val current = utteranceId.startsWith("stream_${generation}_utt_") ||
            utteranceId.startsWith("stream_${generation}_retry_")

        if (!current) {
            Log.d(
                TAG,
                "Ignoring stale TTS callback: $utteranceId"
            )
            return false
        }

        return true
    }

    private fun notifyAllDoneOnce(generation: Long) {
        if (
            generation != streamGeneration.get() ||
            isStreamActive.get() ||
            isSpeaking.get() ||
            sentenceQueue.isNotEmpty()
        ) {
            return
        }

        if (allDoneGeneration.compareAndSet(-1L, generation)) {
            onAllDone?.invoke()
        }
    }

    private fun removeStaleQueueEntries(currentGeneration: Long) {
        val retained = ArrayList<QueuedSentence>()

        while (true) {
            val item = sentenceQueue.poll() ?: break

            if (item.generation == currentGeneration) {
                retained.add(item)
            }
        }

        retained.forEach { sentenceQueue.add(it) }
    }

    /**
     * Converts both normal text and streamed JSON fragments into speech-safe
     * candidates.
     *
     * We intentionally do not try to "speak through" incomplete JSON metadata.
     * That is the source of leaks such as:
     *   <   >   0.8   true   notebook   confidence
     */
    private fun extractSpeechCandidates(raw: String): List<String> {
        var text = raw
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        if (text.isBlank()) return emptyList()

        val spokenMarker = "\"spoken\""
        val spokenIndex = text.indexOf(
            spokenMarker,
            ignoreCase = true
        )

        if (spokenIndex >= 0) {
            val colon = text.indexOf(':', spokenIndex + spokenMarker.length)

            if (colon >= 0) {
                val afterColon = text.substring(colon + 1).trimStart()

                if (afterColon.startsWith("\"")) {
                    val spoken = extractJsonStringValue(
                        afterColon,
                        1
                    )

                    if (spoken.isNotBlank()) {
                        return listOf(spoken)
                    }

                    // Incomplete streamed JSON: wait for a later chunk.
                    return emptyList()
                }
            }
        }

        /*
         * Complete/raw JSON without a spoken field visible yet:
         * never send it to Android TTS.
         */
        if (
            text.startsWith("{") ||
            text.startsWith("[") ||
            text.contains("\"notebook\"") ||
            text.contains("\"confidence\"") ||
            text.contains("\"escalate\"") ||
            text.contains("\"progress\"")
        ) {
            return emptyList()
        }

        return listOf(text)
    }

    /**
     * Extract a JSON string value starting at the opening quote.
     * Handles escaped quotes and common JSON escapes.
     */
    private fun extractJsonStringValue(
        source: String,
        openingQuoteIndex: Int
    ): String {

        if (
            openingQuoteIndex !in source.indices ||
            source[openingQuoteIndex] != '"'
        ) {
            return ""
        }

        val out = StringBuilder()
        var escaped = false

        for (i in openingQuoteIndex + 1 until source.length) {
            val c = source[i]

            if (escaped) {
                when (c) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    else -> out.append(c)
                }
                escaped = false
                continue
            }

            if (c == '\\') {
                escaped = true
                continue
            }

            if (c == '"') {
                return out.toString()
            }

            out.append(c)
        }

        return ""
    }

    private fun sanitizeSpeechText(input: String): String {
        var clean = input

        clean = clean
            .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace("\\t", " ")

        // AndroLLM-style presentation normalization: remove visual markdown
        // while preserving the actual words that should be spoken.
        clean = clean
            .replace(Regex("\\[([^\\]]+)]\\((?:https?://)?[^)]+\\)"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
            .replace("**", "")
            .replace("__", "")
            .replace(Regex("(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)"), "")
            .replace(Regex("\\s+"), " ")

        // Common spoken-value normalization. These are format rules, not
        // topic/domain routing.
        clean = clean
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%"), "$1 percent")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*°\\s*C\\b", RegexOption.IGNORE_CASE), "$1 degrees Celsius")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*°\\s*F\\b", RegexOption.IGNORE_CASE), "$1 degrees Fahrenheit")
            .replace(Regex("\\bhttps?://", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bwww\\.", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")

        /*
         * Remove obvious JSON punctuation only when it is scaffolding.
         * Do not globally delete angle brackets or digits because valid
         * technical speech may contain "<5 V" or "0.8 volts".
         */
        clean = clean
            .replace(Regex("^\\s*[,}:]+"), "")
            .replace(Regex("[\\s,}:]+$"), "")
            .trim()

        return clean
    }

    private fun looksLikeModelMetadata(text: String): Boolean {
        val normalized = text
            .trim()
            .lowercase(Locale.US)

        if (normalized.isBlank()) return true

        if (
            normalized == "true" ||
            normalized == "false" ||
            normalized == "null" ||
            normalized == "reply" ||
            normalized == "spoken" ||
            normalized == "answer" ||
            normalized == "status"
        ) {
            return true
        }

        if (
            normalized == "<" ||
            normalized == ">" ||
            normalized == "{}" ||
            normalized == "[]" ||
            normalized == ":" ||
            normalized == ","
        ) {
            return true
        }

        // Pure numbers / punctuation are metadata, not spoken output.
        if (
            normalized.matches(
                Regex("""^[<>≤≥=+\-*/.,:%\s0-9]+$""")
            )
        ) {
            return true
        }

        // Private retrieval/context scaffolding is never valid spoken output.
        // These are structural markers, not domain/topic keywords.
        if (
            normalized.startsWith("keywords:") ||
            normalized.startsWith("passage 1") ||
            normalized.startsWith("passage 2") ||
            normalized.startsWith("passage 3") ||
            normalized.contains("[reference evidence]") ||
            normalized.contains("[end reference evidence]") ||
            normalized.contains("<knowledge>") ||
            normalized.contains("</knowledge>") ||
            normalized.contains("<internal_context>") ||
            normalized.contains("<internal_visual_context>")
        ) {
            Log.w(TAG, "Suppressed private-context TTS: ${text.take(80)}")
            return true
        }

        val metadataKeys = listOf(
            "\"notebook\"",
            "\"confidence\"",
            "\"escalate\"",
            "\"progress\"",
            "\"status\"",
            "\"fact\"",
            "\"theory\"",
            "\"goal\"",
            "\"checked\"",
            "\"action\"",
            "\"rejected\""
        )

        return metadataKeys.any {
            normalized.contains(it)
        }
    }

    private fun isUserEcho(text: String): Boolean {
        val user = lastUserUtteranceNormalized

        if (user.isBlank()) return false

        val candidate = normalizeForComparison(text)

        if (candidate.isBlank()) return false

        /*
         * Exact echo.
         */
        if (candidate == user) return true

        /*
         * Very short model responses that merely repeat the user's phrase
         * are also treated as echo. We deliberately avoid blocking long,
         * explanatory responses.
         */
        if (
            user.length >= 8 &&
            candidate.length <= user.length + 12 &&
            (
                candidate.startsWith(user) ||
                user.startsWith(candidate)
            )
        ) {
            return true
        }

        return false
    }

    private fun normalizeForComparison(text: String): String {
        return text
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u0900-\\u097F]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
