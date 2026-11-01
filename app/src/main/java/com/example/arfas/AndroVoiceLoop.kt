package com.example.arfas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/**
 * FixSight voice loop aligned to AndroLLM's executable voice implementation.
 *
 * Phase 23 runtime contract:
 * - text-first; voice is explicit tap-to-record / tap-to-stop;
 * - acoustic VAD and automatic barge-in are disabled;
 * - recorder ownership is one-shot and released after each voice capture;
 * - whisper.cpp receives only the explicitly bounded manual utterance;
 * - TTS playback uses USAGE_MEDIA + transient audio focus + a small paced buffer,
 *   matching AndroLLM's AudioPlayer behavior on OEM devices;
 * - no keyword/domain routing.
 */
class AndroVoiceLoop(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onFatalError: (Throwable) -> Unit = {}
) {
    private val whisper = AndroWhisperRecognizer(context)
    private val tts = AndroOfflineTts(context)
    private val hindiTts by lazy { HindiSystemTts(context) }
    private val audioPlayer = AndroAudioPlayer(context)
    @Volatile private var settings = FixSightVoiceSettings.load(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val running = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val responseOpen = AtomicBoolean(false)
    private val recognitionEpoch = AtomicLong(0L)
    private val manualStopRequested = AtomicBoolean(false)

    @Volatile private var recorder: AndroAudioRecorder? = null
    @Volatile private var listenJob: Job? = null
    @Volatile private var ttsJob: Job? = null

    private val speechQueue = ArrayDeque<Pair<String, Float>>()
    private val queueLock = Any()

    @Volatile var sttReady: Boolean = false
        private set
    @Volatile var ttsReady: Boolean = false
        private set

    val ready: Boolean get() = ttsReady || sttReady
    val sttEngineLabel: String
        get() = if (sttReady) {
            "whisper.cpp/${whisper.activeModel}; endpoint=manual"
        } else "unavailable"

    suspend fun initializeSttOnly(): Boolean = withContext(Dispatchers.IO) {
        if (sttReady) return@withContext true
        settings = FixSightVoiceSettings.load(context)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "FixSightSttInit").apply { isDaemon = true } }
        val future = executor.submit(Callable { runBlocking { runCatching { whisper.ensureInitialized() }.getOrDefault(false) } })
        sttReady = try {
            future.get(VOICE_INIT_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true); Log.w(TAG, "ANDRO_STT_INIT_TIMEOUT timeoutMs=$VOICE_INIT_TOTAL_TIMEOUT_MS"); false
        } catch (t: Throwable) {
            Log.e(TAG, "ANDRO_STT_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}"); false
        } finally { executor.shutdownNow() }
        Log.i(TAG, "ANDRO_STT_INIT_ON_DEMAND ready=$sttReady endpoint=manual")
        sttReady
    }

    suspend fun initializeTtsOnly(): Boolean = withContext(Dispatchers.IO) {
        if (ttsReady) return@withContext true
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "FixSightTtsInit").apply { isDaemon = true } }
        val future = executor.submit(Callable { runCatching { tts.ensureInitialized() }.getOrDefault(false) })
        ttsReady = try {
            future.get(VOICE_INIT_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true); Log.w(TAG, "ANDRO_TTS_INIT_TIMEOUT timeoutMs=$VOICE_INIT_TOTAL_TIMEOUT_MS"); false
        } catch (t: Throwable) {
            Log.e(TAG, "ANDRO_TTS_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}"); false
        } finally { executor.shutdownNow() }
        Log.i(TAG, "ANDRO_TTS_INIT_ON_DEMAND ready=$ttsReady")
        ttsReady
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        settings = FixSightVoiceSettings.load(context)

        // Native STT/TTS initializers are allowed to block internally. Do not let one
        // wedged native initializer hold the whole FixSight session in "Starting…".
        // Run them on dedicated daemon workers and apply an absolute startup deadline.
        val executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "FixSightVoiceInit").apply { isDaemon = true }
        }
        val whisperFuture: Future<Boolean> = executor.submit(Callable {
            runBlocking { runCatching { whisper.ensureInitialized() }.getOrDefault(false) }
        })
        val ttsFuture: Future<Boolean> = executor.submit(Callable {
            runCatching { tts.ensureInitialized() }.getOrDefault(false)
        })

        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VOICE_INIT_TOTAL_TIMEOUT_MS)
        fun awaitBounded(label: String, future: Future<Boolean>): Boolean {
            // Both initializers start in parallel. If one completed while we were
            // waiting for the other, consume its completed result even after the
            // shared deadline rather than falsely marking a ready component timed out.
            if (future.isDone) {
                return try {
                    future.get()
                } catch (t: Throwable) {
                    Log.e(TAG, "ANDRO_VOICE_INIT_FAILED component=$label ${t.javaClass.simpleName}: ${t.message}")
                    false
                }
            }
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) {
                Log.w(TAG, "ANDRO_VOICE_INIT_TIMEOUT component=$label timeoutMs=$VOICE_INIT_TOTAL_TIMEOUT_MS")
                future.cancel(true)
                return false
            }
            return try {
                future.get(remainingNs, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                Log.w(TAG, "ANDRO_VOICE_INIT_TIMEOUT component=$label timeoutMs=$VOICE_INIT_TOTAL_TIMEOUT_MS")
                future.cancel(true)
                false
            } catch (t: Throwable) {
                Log.e(TAG, "ANDRO_VOICE_INIT_FAILED component=$label ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }

        try {
            // Await STT first because it is the input capability; both workers began in parallel.
            sttReady = awaitBounded("STT", whisperFuture)
            ttsReady = awaitBounded("TTS", ttsFuture)
        } finally {
            whisperFuture.cancel(true)
            ttsFuture.cancel(true)
            executor.shutdownNow()
        }

        Log.i(
            TAG,
            "ANDRO_VOICE_INIT_BOUNDED ttsReady=$ttsReady sttReady=$sttReady " +
                "stt=${if (sttReady) "whisper.cpp/${whisper.activeModel}" else "unavailable"} " +
                "endpoint=manual timeoutMs=$VOICE_INIT_TOTAL_TIMEOUT_MS"
        )
        ready
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!sttReady) return

        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WHISPER_MIC_NOT_STARTED permission=false")
            sttReady = false
            return
        }

        settings = FixSightVoiceSettings.load(context)
        val rec = AndroAudioRecorder(
            noiseSuppression = settings.noiseSuppression,
            echoCancellation = settings.echoCancellation
        )
        if (!rec.start()) {
            Log.w(TAG, "WHISPER_MIC_NOT_STARTED recorder=false")
            sttReady = false
            return
        }
        recorder = rec
        listenJob = scope.launch {
            try {
                listenLoop(rec)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "ANDRO_VOICE_LOOP_FATAL ${t.javaClass.simpleName}: ${t.message}", t)
                running.set(false)
                listening.set(false)
                onListeningChanged(false)
                runCatching { rec.stop() }
                recorder = null
                onFatalError(t)
            }
        }
    }

    fun setListening(enabled: Boolean) {
        val active = enabled && running.get() && sttReady
        listening.set(active)
        if (active) {
            manualStopRequested.set(false)
        }
        onListeningChanged(active)
        if (active) Log.i(TAG, "ANDRO_LISTENING_ARMED")
    }

    fun beginManualVoiceInput() {
        manualStopRequested.set(false)
        setListening(true)
        Log.i(TAG, "ANDRO_MANUAL_VOICE_CAPTURE_BEGIN endpoint=manual vad=false")
    }


    fun requestManualVoiceStop() {
        manualStopRequested.set(true)
        Log.i(TAG, "ANDRO_MANUAL_VOICE_CAPTURE_STOP_REQUESTED")
    }

    /**
     * Called before a typed turn is accepted. It synchronously invalidates any
     * Whisper decode already in flight so an old voice final cannot become a
     * second turn after the typed message.
     */
    fun prepareTypedTurn() {
        recognitionEpoch.incrementAndGet()
        listening.set(false)
        onListeningChanged(false)
        manualStopRequested.set(true)
        Log.i(TAG, "ANDRO_STT_INVALIDATED reason=typed_input")
    }

    private suspend fun listenLoop(rec: AndroAudioRecorder) {
        while (currentCoroutineContext().isActive && running.get()) {
            if (speaking.get()) {
                // TTS owns mic draining while audio is playing. Never create a second
                // consumer of the recorder channel during playback.
                delay(40L)
                continue
            }
            if (responseOpen.get()) {
                // Phase 23: acoustic VAD/barge-in is disabled everywhere. Drain any
                // recorder data while presentation owns the channel; only explicit UI
                // actions may interrupt a turn.
                withTimeoutOrNull(120L) { rec.chunks.receive() }
                continue
            }
            if (!listening.get()) {
                withTimeoutOrNull(120L) { rec.chunks.receive() }
                continue
            }

            val text = try {
                recognizeUtterance(rec)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "WHISPER_TURN_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
                ""
            }

            if (!running.get()) break
            val repeatRequired = text == STT_REPEAT_REQUIRED
            val acceptedText = if (repeatRequired) "" else sanitizeTranscript(text)
            listening.set(false)
            onListeningChanged(false)

            // Explicit-turn ownership: every microphone tap owns exactly one recorder.
            // Release it before any answer/TTS begins so assistant playback can never be
            // mistaken for a new utterance and no acoustic path can interrupt the turn.
            running.set(false)
            runCatching { rec.stop() }
            if (recorder === rec) recorder = null

            if (repeatRequired) {
                Log.w(TAG, "STT_SANITY_REJECT repeat_required=true")
                speak(STT_REPEAT_PROMPT)
            } else if (acceptedText.isNotBlank()) {
                Log.i(TAG, "ANDRO_ASR_FINAL text='${acceptedText.take(120)}'")
                onFinal(acceptedText)
            } else if (text.isNotBlank()) {
                Log.w(TAG, "WHISPER_TRANSCRIPT_REJECTED text='${text.take(120)}'")
            } else {
                Log.d(TAG, "WHISPER_EMPTY_MANUAL_TURN")
            }
            Log.i(TAG, "ANDRO_MANUAL_VOICE_CAPTURE_RELEASED recorder=false vad=false bargeIn=false")
            break
        }
    }

    /**
     * AndroLLM-derived capture path. Whisper remains the sole transcription owner.
     * Capture is explicitly bounded by the user: tap to record, tap again to stop.
     * No acoustic VAD, silence endpointing, or barge-in participates in turn control.
     */
    private suspend fun recognizeUtterance(rec: AndroAudioRecorder): String {
        val recognitionId = recognitionEpoch.get()
        settings = FixSightVoiceSettings.load(context)
        val session = whisper.newSession(
            language = settings.sttLanguage,
            maxSeconds = settings.sttMaxSeconds,
            streaming = settings.sttStreaming,
            translate = settings.sttTranslate,
            numThreads = settings.sttThreads,
            beamSize = settings.sttBeamSize,
            temperature = settings.sttTemperature
        )
        val startedAt = SystemClock.elapsedRealtime()
        var chunkCount = 0L
        var sampleCount = 0L
        var stopReason = "cancelled"
        // The same audio is kept for Meta Muse Voice (online voice, Settings); Whisper is the fallback.
        val onlineVoice = runCatching { AnswerPolicyStore(context).onlineVoice() }.getOrDefault(false)
        val pcm = if (onlineVoice) PcmAccumulator(rec.actualSampleRate * settings.sttMaxSeconds.coerceAtMost(120)) else null

        Log.i(TAG, "STT_ENDPOINT_MODE mode=manual vad=false maxSeconds=${settings.sttMaxSeconds} onlineVoice=$onlineVoice")

        while (currentCoroutineContext().isActive && running.get() && listening.get() &&
            !speaking.get() && !responseOpen.get()) {
            if (manualStopRequested.get()) { stopReason = "manual-stop"; break }
            val chunk = withTimeoutOrNull(120L) { rec.chunks.receiveCatching().getOrNull() }
            if (chunk != null) {
                session.append(chunk)
                pcm?.add(chunk)
                chunkCount++
                sampleCount += chunk.size

            }
            if (SystemClock.elapsedRealtime() - startedAt >= settings.sttMaxSeconds * 1000L) {
                stopReason = "max-duration"
                break
            }
        }
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "STT_RECORDING_STOPPED reason=$stopReason duration=${durationMs}ms chunks=$chunkCount samples=$sampleCount endpoint=manual vad=false")
        listening.set(false)
        onListeningChanged(false)
        manualStopRequested.set(false)
        if (sampleCount <= 0L) { session.cancel(); return "" }

        if (pcm != null) {
            val online = metaOnlineTranscript(pcm, rec.actualSampleRate)
            if (recognitionId != recognitionEpoch.get()) { session.cancel(); return "" }
            if (online != null) {
                session.cancel()
                return collapseExactRepeatedTranscript(online)
            }
        }

        var text = runCatching { session.finish().trim() }.getOrElse {
            Log.e(TAG, "STT_TRANSCRIPTION_FAILED ${it.javaClass.simpleName}: ${it.message}", it); ""
        }
        Log.i(TAG, "STT_RAW_TRANSCRIPT text='${text.take(120)}' duration=${durationMs}ms")
        text = stripWhisperNonSpeechMarkers(text)
        if (recognitionId != recognitionEpoch.get()) { session.cancel(); return "" }
        if (settings.sttLanguage.equals("auto", ignoreCase = true) &&
            "hi" in settings.sttAutoAllowedLanguages && containsArabicUrduScript(text)) {
            val retry = runCatching { stripWhisperNonSpeechMarkers(session.finishWithLanguage("hi").trim()) }.getOrDefault("")
            if (isUsableTranscript(retry) && !containsArabicUrduScript(retry) && !isLikelyWhisperArtifact(retry)) text = retry
        }
        if (settings.sttLanguage.equals("auto", ignoreCase = true)) {
            text = validateSupportedLanguageHypothesis(session, text)
            if (text == STT_REPEAT_REQUIRED) { session.cancel(); return STT_REPEAT_REQUIRED }
        }
        if (recognitionId != recognitionEpoch.get()) { session.cancel(); return "" }
        if (isLikelyWhisperArtifact(text)) { session.cancel(); return STT_REPEAT_REQUIRED }
        text = collapseExactRepeatedTranscript(text)
        session.cancel()
        return text
    }



    /**
     * Removes only Whisper/non-speech caption artifacts. This is decoder cleanup,
     * not natural-language rewriting. A marker-only hypothesis becomes blank and is
     * rejected; a mixed hypothesis such as "[M] What is this? [M]" retains the
     * spoken words and can continue through normal language/sanity validation.
     */
    private fun stripWhisperNonSpeechMarkers(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(WHISPER_NONSPEECH_MARKER, " ")
            .replace(Regex("\\s+([.!?,;:।！？])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isUsableTranscript(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        if (t.equals("[BLANK_AUDIO]", ignoreCase = true)) return false
        if (t.equals("[NO_SPEECH]", ignoreCase = true)) return false
        if (t.startsWith("[") && t.endsWith("]") && t.length <= 40) return false
        return true
    }

    /**
     * Product-language guard for Whisper auto-LID. This is not a word/intent
     * classifier: it only detects a high-confidence language outside the runtime
     * voice profile and re-decodes the exact SAME PCM with the allowed languages.
     * Low-confidence/ambiguous language ID keeps Whisper's original hypothesis.
     */
    private suspend fun validateSupportedLanguageHypothesis(
        session: AndroWhisperRecognizer.Session,
        autoText: String
    ): String {
        val clean = autoText.trim()
        if (!isUsableTranscript(clean)) return STT_REPEAT_REQUIRED

        val allowed = settings.sttAutoAllowedLanguages
        if (allowed.isEmpty()) return clean
        // Whisper's auto language ID sometimes picks a language the user never speaks (e.g. Korean
        // "-추롬 -네" for unclear English/Hindi speech). Text that is too short for language ID passed
        // straight through. Letters in a script none of the allowed languages use are decisive on
        // their own: re-decode the same audio in the allowed languages, at any length or confidence.
        val foreignScript = hasScriptOutsideProfile(clean, allowed)
        if (!foreignScript && containsDevanagari(clean) && "hi" in allowed) return clean

        val autoLanguage = if (foreignScript) null else (detectLanguageEvidence(clean) ?: return clean)
        val top = if (foreignScript) "script" else autoLanguage?.topLanguage
        if (!foreignScript) {
            if (top == null || top in allowed) return clean
            val evidence = autoLanguage ?: return clean
            if (evidence.topConfidence < LANGUAGE_REDECODE_CONFIDENCE) {
                Log.d(
                    TAG,
                    "STT_LANGUAGE_UNCERTAIN top=$top confidence=${"%.3f".format(evidence.topConfidence)}; keeping auto"
                )
                return clean
            }
        }

        Log.w(
            TAG,
            "STT_LANGUAGE_SUSPICIOUS top=$top confidence=${"%.3f".format(autoLanguage?.topConfidence ?: 1f)} " +
                "allowed=${allowed.joinToString(",")} auto='${clean.take(100)}'"
        )

        val candidates = ArrayList<LanguageCandidate>(allowed.size)
        for (language in allowed) {
            val decoded = runCatching {
                stripWhisperNonSpeechMarkers(session.finishWithLanguage(language).trim())
            }.getOrDefault("")
            if (!isUsableTranscript(decoded) || isLikelyWhisperArtifact(decoded) || containsArabicUrduScript(decoded)) continue
            if (hasScriptOutsideProfile(decoded, allowed)) continue
            val evidence = detectLanguageEvidence(decoded)
            val score = when {
                // Too short for language ID: after a script rejection, a clean re-decode in an
                // allowed language is the best available reading (the first allowed language wins ties).
                evidence == null -> if (foreignScript) LANGUAGE_CANDIDATE_ACCEPT_CONFIDENCE else 0f
                evidence.confidenceFor(language) > 0f -> evidence.confidenceFor(language)
                else -> evidence.supportedConfidence(allowed)
            }
            candidates += LanguageCandidate(language, decoded, score)
        }

        val best = candidates.maxByOrNull { it.supportConfidence }
        if (best != null && best.supportConfidence >= LANGUAGE_CANDIDATE_ACCEPT_CONFIDENCE) {
            Log.i(
                TAG,
                "STT_REDECODE_ACCEPT language=${best.language} confidence=${"%.3f".format(best.supportConfidence)} " +
                    "text='${best.text.take(120)}'"
            )
            return best.text
        }

        Log.w(
            TAG,
            "STT_LANGUAGE_REJECT top=$top confidence=${"%.3f".format(autoLanguage?.topConfidence ?: 1f)} " +
                "candidateScores=${candidates.joinToString { "${it.language}:${"%.3f".format(it.supportConfidence)}" }}"
        )
        return STT_REPEAT_REQUIRED
    }

    private data class LanguageCandidate(
        val language: String,
        val text: String,
        val supportConfidence: Float
    )

    private data class LanguageEvidence(
        val topLanguage: String?,
        val topConfidence: Float,
        val confidences: Map<String, Float>
    ) {
        fun supportedConfidence(allowed: Set<String>): Float =
            allowed.maxOfOrNull { confidences[it] ?: 0f } ?: 0f

        fun confidenceFor(language: String): Float = confidences[language] ?: 0f
    }

    /**
     * Uses Android's built-in on-device language detector (API 29+). The call is
     * bounded so language validation cannot become a multi-second voice blocker.
     */
    private suspend fun detectLanguageEvidence(text: String): LanguageEvidence? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text.length < MIN_LANGUAGE_ID_CHARS) return null
        return withTimeoutOrNull(LANGUAGE_ID_TIMEOUT_MS) {
            runCatching {
                val manager = context.getSystemService(TextClassificationManager::class.java)
                    ?: return@runCatching null
                val result = manager.textClassifier.detectLanguage(
                    TextLanguage.Request.Builder(text).build()
                )
                val count = result.localeHypothesisCount
                if (count <= 0) return@runCatching null

                val confidences = LinkedHashMap<String, Float>(count)
                var topLanguage: String? = null
                var topConfidence = 0f
                for (index in 0 until count) {
                    val locale = result.getLocale(index)
                    val language = locale.language.lowercase()
                    val confidence = result.getConfidenceScore(locale)
                    if (index == 0) {
                        topLanguage = language
                        topConfidence = confidence
                    }
                    val prior = confidences[language] ?: 0f
                    if (confidence > prior) confidences[language] = confidence
                }
                LanguageEvidence(topLanguage, topConfidence, confidences)
            }.getOrElse {
                Log.d(TAG, "STT_LANGUAGE_ID_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        }
    }

    private fun containsDevanagari(text: String): Boolean = text.any { it.code in 0x0900..0x097F }

    /**
     * True when the text has letters in a script that none of the allowed voice languages are
     * written in (Korean, Chinese, Japanese, Cyrillic, Thai… for an English/Hindi profile).
     * Script check only, no word lists. Fails open (false) when an allowed language's script
     * is not known here.
     */
    private fun hasScriptOutsideProfile(text: String, allowed: Set<String>): Boolean {
        val scripts = HashSet<Character.UnicodeScript>()
        for (language in allowed) {
            scripts += PROFILE_SCRIPTS[language] ?: return false
        }
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            if (Character.isLetter(cp)) {
                val script = Character.UnicodeScript.of(cp)
                if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED &&
                    script !in scripts) return true
            }
            index += Character.charCount(cp)
        }
        return false
    }

    /**
     * Script-only guard for the supported English/Hindi/Hinglish voice profile.
     * This deliberately does not guess a language from words or maintain a
     * dictionary; it only detects Arabic-script output from Whisper auto-LID.
     */
    private fun containsArabicUrduScript(text: String): Boolean = text.any { c ->
        val cp = c.code
        cp in 0x0600..0x06FF ||
            cp in 0x0750..0x077F ||
            cp in 0x08A0..0x08FF ||
            cp in 0xFB50..0xFDFF ||
            cp in 0xFE70..0xFEFF
    }

    /**
     * Rejects well-known Whisper failure shapes before they can become an authoritative
     * conversation turn. This is intentionally structural rather than semantic: normal
     * repeated words remain valid, but pathological one-token loops such as the observed
     * "Ai, ai, ai..." transcript are discarded.
     */
    private fun isLikelyWhisperArtifact(raw: String): Boolean {
        val t = raw.trim()
        if (!isUsableTranscript(t)) return true

        val captionMarkers = WHISPER_NONSPEECH_MARKER
        val markerCount = captionMarkers.findAll(t).count()
        if (markerCount >= 2) return true

        val words = t.lowercase()
            .replace(captionMarkers, " ")
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size >= 10) {
            val counts = words.groupingBy { it }.eachCount()
            val dominant = counts.values.maxOrNull() ?: 0
            if (dominant >= 8 && dominant.toDouble() / words.size.toDouble() >= 0.55) return true
        }
        return false
    }

    /**
     * Collapses only exact repeated sentence blocks. This is structural cleanup, not
     * semantic rewriting. Example: "A. B? A. B?" -> "A. B?".
     */
    /**
     * Meta Muse Voice Transcribe for one utterance (Hindi/English code-switching). Null when the
     * phone is offline, no Meta key is saved, the rate is not 16/24 kHz, or the call fails: the
     * caller then uses Whisper on the same audio.
     */
    private suspend fun metaOnlineTranscript(pcm: PcmAccumulator, sampleRate: Int): String? {
        if (pcm.size < sampleRate / 4 || (sampleRate != 16_000 && sampleRate != 24_000)) return null
        if (!MetaOnline.ready(context)) return null
        val started = SystemClock.elapsedRealtime()
        val wav = java.io.File(context.cacheDir, "voice_turn.wav")
        return try {
            withContext(Dispatchers.IO) { MetaOnline.writeWav(pcm.samples, pcm.size, sampleRate, wav) }
            val transcript = withTimeoutOrNull(META_VOICE_TIMEOUT_MS) {
                MetaOnline.api(context).transcribe("voice_input", wav, diarize = false)
            }?.text?.let { stripWhisperNonSpeechMarkers(it) }.orEmpty()
            Log.i(TAG, "STT_META_VOICE ms=${SystemClock.elapsedRealtime() - started} chars=${transcript.length} text='${transcript.take(120)}'")
            transcript.takeIf { isUsableTranscript(it) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "STT_META_VOICE_FAILED ${t.javaClass.simpleName}: ${t.message?.take(160)} fallback=whisper")
            null
        } finally {
            runCatching { wav.delete() }
        }
    }

    private fun collapseExactRepeatedTranscript(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return text
        val units = Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (units.size < 2) return text

        fun norm(value: String): String = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()

        for (repeatCount in 3 downTo 2) {
            if (units.size % repeatCount != 0) continue
            val blockSize = units.size / repeatCount
            val base = units.take(blockSize).map(::norm)
            if (base.all { it.isNotBlank() } && (1 until repeatCount).all { index ->
                    units.subList(index * blockSize, (index + 1) * blockSize).map(::norm) == base
                }) {
                return units.take(blockSize).joinToString(" ").trim()
            }
        }
        return text
    }

    private fun sanitizeTranscript(raw: String): String {
        val text = raw.trim().trim('"', '\'', ' ')
        if (text.isBlank()) return ""
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size >= 10) {
            val counts = words.groupingBy { it }.eachCount()
            val dominant = counts.values.maxOrNull() ?: 0
            val uniqueRatio = counts.size.toDouble() / words.size.toDouble()
            if (dominant >= 8 && dominant.toDouble() / words.size >= 0.55) return ""
            if (words.size >= 20 && uniqueRatio < 0.18) return ""
        }
        return text
    }

    fun beginResponse() {
        recognitionEpoch.incrementAndGet()
        responseOpen.set(true)
        listening.set(false)
        onListeningChanged(false)
    }

    fun endResponse() {
        responseOpen.set(false)
        synchronized(queueLock) {
            if (speechQueue.isNotEmpty() && ttsJob == null) startTtsWorkerLocked()
        }
    }

    /**
     * Close the response producer and do not return until every queued speech chunk,
     * the currently synthesizing/playing chunk, and the final speaker-echo drain are done.
     *
     * This mirrors AndroLLM VoiceAssistantService's `speechQueue.close(); ttsJob.join()`
     * contract. Generation completion is NOT speech completion. The service must await
     * this barrier before it marks the turn finished or re-arms Whisper.
     */
    suspend fun endResponseAndAwaitDrain() {
        responseOpen.set(false)
        Log.i(TAG, "ANDRO_RESPONSE_PRODUCER_CLOSED")

        while (currentCoroutineContext().isActive) {
            val job = synchronized(queueLock) {
                if (speechQueue.isNotEmpty() && ttsJob == null) startTtsWorkerLocked()
                ttsJob
            } ?: break
            job.join()
        }

        val idle = synchronized(queueLock) {
            speechQueue.isEmpty() && ttsJob == null && !speaking.get()
        }
        Log.i(
            TAG,
            "ANDRO_TTS_DRAIN_COMPLETE idle=$idle listening=${listening.get()} queue=${synchronized(queueLock) { speechQueue.size }}"
        )
    }

    /**
     * Compatibility path for non-streamed full answers. It may split a long final
     * response into bounded synthesis units. Live local streaming must use
     * [speakChunk], where AndroSentenceAssembler is the sole segmentation authority.
     */
    fun speak(sentence: String, speed: Float = settings.speakingSpeed) {
        val spoken = FixSightOutputSanitizer.sanitizeForSpeech(sentence)
        if (spoken.isBlank()) return
        enqueueSpeech(splitSpeechUnits(spoken), speed)
    }

    /** Queue exactly one already-assembled AndroLLM speech chunk without re-segmenting it. */
    fun speakChunk(chunk: String, speed: Float = settings.speakingSpeed) {
        val spoken = FixSightOutputSanitizer.sanitizeForSpeech(chunk).trim()
        if (spoken.isBlank()) return
        enqueueSpeech(listOf(spoken), speed)
    }

    private fun enqueueSpeech(units: List<String>, speed: Float) {
        if (units.isEmpty()) return
        if (!ttsReady) {
            Log.w(TAG, "ANDRO_TTS_SKIP ready=false units=${units.size}")
            return
        }

        synchronized(queueLock) {
            units.filter { it.isNotBlank() }.forEach { speechQueue.addLast(it to speed) }
            if (speechQueue.isEmpty()) return
            // A non-null worker owns the native TTS engine even after cancellation until
            // its finally block completes. Never start a second Piper/Sherpa synthesis
            // worker against the same native engine during that unwind window.
            if (ttsJob == null) startTtsWorkerLocked()
        }
    }

    /** Caller holds [queueLock]. */
    private fun startTtsWorkerLocked() {
        if (speechQueue.isEmpty() || ttsJob != null || !ttsReady) return
        val job = scope.launch(start = CoroutineStart.LAZY) { runTtsWorker() }
        ttsJob = job
        job.start()
    }

    private suspend fun runTtsWorker() {
        val thisTtsJob = currentCoroutineContext()[Job]
        var shouldRestart = false
        try {
            listening.set(false)
            onListeningChanged(false)
            if (speaking.compareAndSet(false, true)) onSpeakingChanged(true)
            audioPlayer.beginUtterance()
            try {
                while (currentCoroutineContext().isActive) {
                    val item = synchronized(queueLock) {
                        if (speechQueue.isEmpty()) null else speechQueue.removeFirst()
                    }

                    if (item == null) {
                        // Critical streaming contract: an empty queue while generation is
                        // still producing is only a temporary gap. AndroLLM keeps its
                        // Channel consumer alive until the producer closes the queue.
                        if (responseOpen.get()) {
                            delay(TTS_STREAM_QUEUE_POLL_MS)
                            continue
                        }
                        break
                    }

                    // PHASE16: isolate every speech unit. A single native synthesis or
                    // playback failure must not destroy the remaining long-answer queue.
                    // Retry the same unit once; if it still fails, skip only that unit and
                    // continue speaking subsequent units. The generated text is already
                    // durably committed by the service before this worker is drained.
                    var unitCompleted = false
                    var unitAttempt = 0
                    while (!unitCompleted && unitAttempt < TTS_UNIT_MAX_ATTEMPTS && currentCoroutineContext().isActive) {
                        unitAttempt++
                        try {
                            Log.i(TAG, "ANDRO_TTS_SYNTH_BEGIN chars=${item.first.length} attempt=$unitAttempt")
                            // Hindi (Devanagari) sentence: the bundled voice is English, so use the
                            // phone's own Hindi voice. Falls through to the bundled voice if absent.
                            if (HindiSystemTts.hasDevanagari(item.first) && hindiTts.speakAndWait(item.first, item.second)) {
                                Log.i(TAG, "ANDRO_TTS_HINDI_SYSTEM_VOICE chars=${item.first.length}")
                                unitCompleted = true
                                continue
                            }
                            val samples = tts.synthesize(item.first, item.second)
                            if (samples == null || samples.isEmpty()) {
                                throw IllegalStateException("empty_tts_samples")
                            }
                            Log.i(TAG, "ANDRO_TTS_SYNTH_OK samples=${samples.size} rate=${tts.sampleRate} attempt=$unitAttempt")

                            val playbackCompleted = audioPlayer.play(samples, tts.sampleRate)
                            if (!playbackCompleted) {
                                Log.w(TAG, "ANDRO_TTS_UNIT_PLAYBACK_INCOMPLETE chars=${item.first.length} attempt=$unitAttempt")
                                throw IllegalStateException("tts_playback_incomplete")
                            }
                            Log.i(TAG, "ANDRO_TTS_UNIT_PLAYBACK_COMPLETE chars=${item.first.length} attempt=$unitAttempt")
                            unitCompleted = true
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(
                                TAG,
                                "ANDRO_TTS_UNIT_FAILED rev=V6_0_RESILIENT_LONG_TTS attempt=$unitAttempt chars=${item.first.length} " +
                                    "${t.javaClass.simpleName}: ${t.message}"
                            )
                            runCatching { audioPlayer.stopNow() }
                            if (unitAttempt < TTS_UNIT_MAX_ATTEMPTS) delay(TTS_UNIT_RETRY_DELAY_MS)
                        }
                    }
                    if (!unitCompleted) {
                        Log.e(TAG, "ANDRO_TTS_UNIT_SKIPPED rev=V6_0_RESILIENT_LONG_TTS chars=${item.first.length}")
                    }
                }

            } finally {
                audioPlayer.endUtterance()
                if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // PHASE16: TTS is a presentation subsystem. Never escalate a speech-only
            // failure through the service-wide fatal boundary because that boundary
            // replaces the already generated answer text with an error message.
            Log.e(TAG, "ANDRO_TTS_LOOP_DEGRADED rev=V6_0_RESILIENT_LONG_TTS ${t.javaClass.simpleName}: ${t.message}", t)
            if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
            // Keep any queued units. The finally block will restart the worker when safe.
        } finally {
            synchronized(queueLock) {
                // A newer worker may already have replaced a cancelled one. Never let an
                // older worker clear the new worker's ownership reference.
                if (ttsJob === thisTtsJob) {
                    ttsJob = null
                    shouldRestart = speechQueue.isNotEmpty()
                    if (shouldRestart) startTtsWorkerLocked()
                }
            }

        }
    }

    private fun splitSpeechUnits(text: String): List<String> {
        val sentences = text.trim()
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentences.isEmpty()) return listOf(text.trim())

        // Pack adjacent short sentences together. The previous implementation
        // emitted tiny units such as "Okay!" as their own AudioTrack cycle,
        // causing repeated synthesis/focus/playback gaps. Keep units bounded,
        // but do not fragment normal prose unnecessarily.
        val out = mutableListOf<String>()
        val packed = StringBuilder()

        fun flushPacked() {
            if (packed.isNotEmpty()) {
                out += packed.toString().trim()
                packed.clear()
            }
        }

        fun addLongSentence(sentence: String) {
            val words = sentence.split(Regex("\\s+"))
            val chunk = StringBuilder()
            for (word in words) {
                if (chunk.isNotEmpty() && chunk.length + 1 + word.length > TTS_UNIT_MAX_CHARS) {
                    out += chunk.toString().trim()
                    chunk.clear()
                }
                if (chunk.isNotEmpty()) chunk.append(' ')
                chunk.append(word)
            }
            if (chunk.isNotEmpty()) out += chunk.toString().trim()
        }

        for (sentence in sentences) {
            if (sentence.length > TTS_UNIT_MAX_CHARS) {
                flushPacked()
                addLongSentence(sentence)
                continue
            }
            val extra = if (packed.isEmpty()) sentence.length else sentence.length + 1
            if (packed.isNotEmpty() && packed.length + extra > TTS_UNIT_MAX_CHARS) flushPacked()
            if (packed.isNotEmpty()) packed.append(' ')
            packed.append(sentence)
        }
        flushPacked()
        return out.ifEmpty { listOf(text.trim()) }
    }

    /** Direct adaptation of AndroLLM's post-speech echo drain. */

    /**
     * Abort only the current presentation while an answer turn is still unresolved.
     *
     * IMPORTANT: unlike interrupt(), this MUST keep responseOpen=true and listening=false.
     * A local retry/cloud fallback is still part of the same assistant turn. Re-arming STT
     * here creates the race seen in the 2026-09-02 trace where the microphone restarted
     * while the local retry was still decoding.
     */
    fun abortPresentationForRetry() {
        ttsJob?.cancel()
        synchronized(queueLock) { speechQueue.clear() }
        stopPlayback()
        if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
        listening.set(false)
        onListeningChanged(false)
        Log.i(TAG, "ANDRO_PRESENTATION_ABORT_KEEP_GATE responseOpen=${responseOpen.get()}")
    }

    /**
     * Called when an obsolete response coroutine is cancelled because another input already
     * owns the interaction. Unlike interrupt(), this does NOT bump the recognition epoch,
     * so a newly started explicit decode cannot be invalidated by the old coroutine unwinding.
     */
    fun settleCancelledResponse(rearmListening: Boolean) {
        responseOpen.set(false)
        ttsJob?.cancel()
        synchronized(queueLock) { speechQueue.clear() }
        stopPlayback()
        if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
        if (rearmListening && running.get() && sttReady) {
            if (!listening.get()) setListening(true)
        } else {
            listening.set(false)
            onListeningChanged(false)
        }
        Log.i(TAG, "ANDRO_CANCELLED_RESPONSE_SETTLED rearm=$rearmListening listening=${listening.get()}")
    }

    /**
     * User-visible interruption: the current answer turn is abandoned and listening may reopen.
     * Use abortPresentationForRetry() for internal local-retry/cloud-fallback transitions.
     */
    fun interrupt(rearmListening: Boolean = false) {
        recognitionEpoch.incrementAndGet()
        responseOpen.set(false)
        ttsJob?.cancel()
        synchronized(queueLock) { speechQueue.clear() }
        stopPlayback()
        if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
        if (rearmListening && running.get() && sttReady) setListening(true)
        else {
            listening.set(false)
            onListeningChanged(false)
        }
    }

    private fun stopPlayback() {
        audioPlayer.stopNow()
        runCatching { hindiTts.stop() }
    }

    fun stop() {
        running.set(false)
        responseOpen.set(false)
        listening.set(false)
        onListeningChanged(false)
        listenJob?.cancel()
        listenJob = null
        ttsJob?.cancel()
        ttsJob = null
        synchronized(queueLock) { speechQueue.clear() }
        stopPlayback()
        recorder?.stop()
        recorder = null
        if (speaking.compareAndSet(true, false)) onSpeakingChanged(false)
    }

    /**
     * Native cleanup boundary. The service invokes this only from its worker scope;
     * MainActivity must never wait for Whisper/Piper/native release.
     */
    suspend fun releaseAsync() {
        stop()
        runCatching { whisper.release() }
        runCatching { tts.release() }
        runCatching { hindiTts.release() }
        runCatching { audioPlayer.release() }
        scope.cancel()
        Log.i(TAG, "ANDRO_VOICE_RELEASED worker=true")
    }

    /** Legacy non-blocking compatibility entry point. Prefer service-owned releaseAsync(). */
    fun release() {
        scope.launch { releaseAsync() }
    }

    companion object {
        private const val VOICE_INIT_TOTAL_TIMEOUT_MS = 20_000L
        private const val TTS_STREAM_QUEUE_POLL_MS = 40L
        private const val TAG = "AndroVoiceLoop"
        // Runtime product-language profile guard. These are confidence/latency
        // thresholds for language-ID mechanics, not conversational semantics.
        private const val MIN_LANGUAGE_ID_CHARS = 8
        /** Scripts each supported voice language is written in (Hinglish uses Latin too). */
        private val PROFILE_SCRIPTS: Map<String, Set<Character.UnicodeScript>> = mapOf(
            "en" to setOf(Character.UnicodeScript.LATIN),
            "hi" to setOf(Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.LATIN)
        )
        private const val LANGUAGE_ID_TIMEOUT_MS = 300L
        private const val LANGUAGE_REDECODE_CONFIDENCE = 0.72f
        private const val LANGUAGE_CANDIDATE_ACCEPT_CONFIDENCE = 0.45f

        private val WHISPER_NONSPEECH_MARKER = Regex(
            "(?i)(\\[\\s*m\\s*]|\\[\\s*music\\s*]|\\(\\s*music\\s*\\)|\\*\\s*song\\s*\\*|" +
                "\\[\\s*applause\\s*]|\\[\\s*laughter\\s*]|\\[\\s*blank_audio\\s*]|\\[\\s*no_speech\\s*])"
        )

        private const val STT_REPEAT_REQUIRED = "__FIXSIGHT_STT_REPEAT_REQUIRED__"
        private const val META_VOICE_TIMEOUT_MS = 15_000L
        private const val STT_REPEAT_PROMPT = "Sorry, I didn't catch that. Please say it again."
        private const val TTS_UNIT_MAX_CHARS = 180
        private const val TTS_UNIT_MAX_ATTEMPTS = 2
        private const val TTS_UNIT_RETRY_DELAY_MS = 90L
    }
}
