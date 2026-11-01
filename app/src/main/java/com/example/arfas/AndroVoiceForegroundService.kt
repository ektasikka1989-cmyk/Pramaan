package com.example.arfas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AndroLLM-style foreground voice owner for FixSight.
 *
 * Stability contract:
 * - this service owns microphone/STT, active turn Job, sentence assembly, TTS,
 *   playback, interruption and voice state;
 * - MainActivity never calls native LiteRT/Whisper/Piper teardown;
 * - all potentially blocking model/native work executes on worker dispatchers;
 * - one accepted turn is serialized through [turnMutex];
 * - cancellation cancels the owning coroutine Job and lets the engine unwind;
 * - attachments are snapshotted/consumed by the same turn owner;
 * - Notebook/RCA observes only accepted turns through the app-scoped runtime.
 */
class AndroVoiceForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val turnMutex = Mutex()
    private val ownershipLock = Any()

    private lateinit var graph: FixSightRuntimeGraph

    @Volatile private var voiceLoop: AndroVoiceLoop? = null
    @Volatile private var activeTurnJob: Job? = null
    @Volatile private var sessionActive = false
    @Volatile private var visionMode = false
    @Volatile private var troubleshootingMode = false
    @Volatile private var stopping = false
    @Volatile private var cancelledTurnShouldRearm = true
    @Volatile private var lastVoiceActivityAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        graph = FixSightRuntimeGraph.get(this)
        createNotificationChannel()
        Log.i(TAG, "ANDRO_SERVICE_CREATED owner=voice_runtime")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: AndroVoiceAssistant.ACTION_START_SESSION
        when (action) {
            AndroVoiceAssistant.ACTION_START_VOICE_INPUT -> promoteToForeground(microphone = true)
            // Troubleshooting and a photo/file pick: kept alive as a foreground service WITHOUT the
            // microphone type. startForeground() here used the manifest's "microphone" type, which
            // Android 14+ refuses with a SecurityException (an app crash) when the microphone
            // permission was not granted: tapping Troubleshoot crashed the app for typing users.
            AndroVoiceAssistant.ACTION_START_TROUBLESHOOTING,
            AndroVoiceAssistant.ACTION_HOLD_FOREGROUND -> promoteToForeground(microphone = false)
        }
        if (action == AndroVoiceAssistant.ACTION_HOLD_FOREGROUND) return START_NOT_STICKY
        serviceScope.launch {
            try {
                when (action) {
                    AndroVoiceAssistant.ACTION_START_SESSION -> {
                        val requestedVision = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_VISION_MODE, false) == true
                        val requestedKnowledge = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_KNOWLEDGE_MODE, false) == true
                        val startNewChat = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_START_NEW_CHAT, false) == true
                        beginSession(requestedVision, requestedKnowledge, requestedTroubleshooting = false, startNewChat = startNewChat)
                    }
                    AndroVoiceAssistant.ACTION_START_TROUBLESHOOTING -> {
                        val requestedVision = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_VISION_MODE, false) == true
                        val requestedKnowledge = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_KNOWLEDGE_MODE, false) == true
                        val startNewChat = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_START_NEW_CHAT, false) == true
                        beginSession(requestedVision, requestedKnowledge, requestedTroubleshooting = true, startNewChat = startNewChat)
                    }
                    AndroVoiceAssistant.ACTION_SET_TROUBLESHOOTING_MODE -> {
                        val enabled = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_TROUBLESHOOTING_MODE, false) == true
                        setTroubleshootingModeInPlace(enabled)
                    }
                    AndroVoiceAssistant.ACTION_OPEN_CONVERSATION -> {
                        val id = intent?.getStringExtra(AndroVoiceAssistant.EXTRA_CONVERSATION_ID).orEmpty().trim()
                        if (id.isNotBlank()) openConversationSession(id)
                    }
                    AndroVoiceAssistant.ACTION_STOP_SESSION -> stopSession("user_end_session")
                    AndroVoiceAssistant.ACTION_SUBMIT_TEXT -> {
                        val text = intent?.getStringExtra(AndroVoiceAssistant.EXTRA_TEXT).orEmpty().trim()
                        val forceExternalInformation = intent?.getBooleanExtra(
                            AndroVoiceAssistant.EXTRA_FORCE_EXTERNAL_INFORMATION, false
                        ) == true
                        if (text.isNotBlank()) submitTypedTurn(text, forceExternalInformation)
                    }
                    AndroVoiceAssistant.ACTION_INTERRUPT -> {
                        val rearm = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_REARM_LISTENING, false) == true
                        interruptActiveTurn("user_interrupt", rearm)
                    }
                    AndroVoiceAssistant.ACTION_SET_VISION_MODE -> {
                        val enabled = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_VISION_MODE, false) == true
                        setVisionMode(enabled)
                    }
                    AndroVoiceAssistant.ACTION_SET_KNOWLEDGE_MODE -> {
                        val enabled = intent?.getBooleanExtra(AndroVoiceAssistant.EXTRA_KNOWLEDGE_MODE, false) == true
                        setKnowledgeMode(enabled)
                    }
                    AndroVoiceAssistant.ACTION_START_VOICE_INPUT -> startManualVoiceInput()
                    AndroVoiceAssistant.ACTION_STOP_VOICE_INPUT -> stopManualVoiceInput()
                    AndroVoiceAssistant.ACTION_STOP_SPEECH -> stopSpeechPresentation()
                    AndroVoiceAssistant.ACTION_SPEAK_TEXT -> {
                        val text = intent?.getStringExtra(AndroVoiceAssistant.EXTRA_TEXT).orEmpty().trim()
                        if (text.isNotBlank()) speakTextOnDemand(text)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                containVoiceFailure("command:$action", t)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15+: a dataSync foreground service past its daily limit must leave the foreground or the app is stopped. */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "FOREGROUND_TIMEOUT startId=$startId")
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
    }

    /** Whole-session failure boundary equivalent to AndroLLM VoiceAssistantService.runLoop(). */
    private suspend fun beginSession(
        requestedVision: Boolean,
        requestedKnowledge: Boolean = false,
        requestedTroubleshooting: Boolean = false,
        startNewChat: Boolean = false
    ) {
        if (sessionActive && !stopping) {
            if (startNewChat) {
                // Troubleshooting is a new case/conversation, but it does not require
                // tearing down the foreground service. Switch conversation ownership
                // atomically so Notebook and accepted turns are scoped to the new case.
                interruptActiveTurn("new_troubleshooting_chat", rearmListening = false)
                val conversationId = graph.runtime.startNewChat(
                    clearHistory = true,
                    mode = if (requestedTroubleshooting) "TROUBLESHOOTING" else "NORMAL"
                )
                graph.phase1Features.setConversationId(conversationId)
                graph.phase1Features.notebookObserver.clear()
                graph.attachmentManager.clearPending()
                graph.attachmentRecallProvider.clear()
                graph.attachmentRecallProvider.bindConversation(conversationId)

                visionMode = requestedVision
                troubleshootingMode = requestedTroubleshooting
                val knowledgeAccepted = if (requestedKnowledge) {
                    graph.phase1Features.ensureKnowledgeReady() &&
                        graph.phase1Features.setKnowledgeEnabled(true)
                } else {
                    graph.phase1Features.setKnowledgeEnabled(false)
                    false
                }
                AndroVoiceController.beginSession(requestedVision, requestedTroubleshooting)
                AndroVoiceController.setKnowledgeMode(knowledgeAccepted)
                graph.sessionAuditTrail.startSession()
                Log.i(
                    TAG,
                    "TROUBLESHOOTING_NEW_CHAT_BOUND id=${conversationId.take(12)} vision=$requestedVision " +
                        "kb=$knowledgeAccepted notebookFresh=true"
                )
                if (requestedTroubleshooting) startTroubleshootingIntro()
                return
            }

            setVisionMode(requestedVision)
            if (requestedTroubleshooting != troubleshootingMode) {
                setTroubleshootingModeInPlace(requestedTroubleshooting)
            }
            val currentKnowledge = graph.phase1Features.isKnowledgeEnabled()
            if (requestedKnowledge != currentKnowledge) {
                Log.w(TAG, "KB_SESSION_MODE_LOCKED requested=$requestedKnowledge current=$currentKnowledge action=start_session")
            }
            AndroVoiceController.setKnowledgeMode(currentKnowledge)
            return
        }

        stopping = false
        sessionActive = true
        visionMode = requestedVision
        troubleshootingMode = requestedTroubleshooting
        graph.phase1Features.setKnowledgeEnabled(false)
        AndroVoiceController.beginSession(requestedVision, requestedTroubleshooting)
        // KB admission is session-critical. If the user requested KB, the service
        // itself must ensure the process-scoped KB is ready before accepting the
        // session. Never silently downgrade a requested KB session to ordinary
        // KB-OFF Local chat merely because readiness was still lazy/stale here.
        val knowledgeAccepted = if (requestedKnowledge) {
            graph.phase1Features.ensureKnowledgeReady() &&
                graph.phase1Features.setKnowledgeEnabled(true)
        } else {
            false
        }
        AndroVoiceController.setKnowledgeMode(knowledgeAccepted)
        Log.i(TAG, "KB_SESSION_START requested=$requestedKnowledge accepted=$knowledgeAccepted")

        if (requestedKnowledge && !knowledgeAccepted) {
            Log.e(TAG, "KB_SESSION_ADMISSION_FAILED requested=true failClosed=true")
            stopSession("kb_admission_failed")
            AndroVoiceController.setError(
                "Knowledge Base could not be enabled. Please wait for the KB to become ready and start the session again.",
                keepSession = false
            )
            return
        }

        // Preserve a just-staged first-turn attachment. MainActivity explicitly clears
        // stale attachment state when the user chooses New Chat / switches conversation;
        // session startup itself must not erase the source the user just selected.
        graph.attachmentRecallProvider.clear()

        try {
            val conversationId = graph.runtime.startNewChat(
                clearHistory = startNewChat,
                mode = if (requestedTroubleshooting) "TROUBLESHOOTING" else "NORMAL"
            )
            graph.phase1Features.setConversationId(conversationId)
            graph.attachmentRecallProvider.bindConversation(conversationId)
            val validConversationIds = graph.runtime.conversationSessions(5).map { it.id }.toSet()
            graph.phase1Features.pruneConversationScopes(validConversationIds)
            graph.attachmentRecallProvider.pruneConversationScopes(validConversationIds)
            if (startNewChat) {
                graph.phase1Features.notebookObserver.clear()
            }
            graph.sessionAuditTrail.startSession()
            Log.i(TAG, "CONVERSATION_BIND id=${conversationId.take(12)} new=$startNewChat")

            // Phase 23: text-first admission. Starting a FixSight conversation never
            // initializes or opens the microphone/TTS stack. Voice and speech are
            // capabilities loaded only after the user explicitly taps their controls.
            AndroVoiceController.setVoiceReady(false, sttReady = false, ttsReady = false, initComplete = true)
            updateHealth()
            graph.initialiseAsync { progress -> Log.d(TAG, "ANDRO_SERVICE_MODEL_PROGRESS $progress") }
            Log.i(TAG, "ANDRO_SERVICE_SESSION_START mode=${if (troubleshootingMode) "TROUBLESHOOTING" else "TEXT_FIRST"} vision=$visionMode voiceOnDemand=true vad=false bargeIn=false")
            if (troubleshootingMode) startTroubleshootingIntro()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            containVoiceFailure("session_start", t)
        }
    }

    private suspend fun openConversationSession(conversationId: String) {
        if (stopping) return
        interruptActiveTurn("open_conversation", rearmListening = false)
        voiceLoop?.stop()
        troubleshootingMode = false
        visionMode = false
        val opened = graph.runtime.openConversation(conversationId)
        if (!opened) {
            AndroVoiceController.setError("That conversation is no longer available.", keepSession = sessionActive)
            return
        }
        sessionActive = true
        graph.phase1Features.setConversationId(conversationId)
        graph.attachmentManager.clearPending()
        graph.attachmentRecallProvider.clear()
        graph.attachmentRecallProvider.bindConversation(conversationId)
        AndroVoiceController.beginSession(visionMode = false, troubleshootingMode = false)
        AndroVoiceController.setKnowledgeMode(false)
        AndroVoiceController.setPendingAttachment("")
        graph.phase1Features.setKnowledgeEnabled(false)
        graph.sessionAuditTrail.startSession()
        runCatching { graph.runtime.ensureConversationOpen() }
        updateHealth()
        Log.i(TAG, "CONVERSATION_OPENED id=${conversationId.take(12)} messages=${graph.runtime.currentConversationMessages().size}")
    }

    private suspend fun setTroubleshootingModeInPlace(enabled: Boolean) {
        if (stopping) return
        if (!sessionActive) {
            // Mode commands are expected during an active visible conversation. If the
            // service was recreated first, bind/reopen the existing conversation rather
            // than manufacturing a new one, then apply the requested mode.
            beginSession(
                requestedVision = AndroVoiceController.state.value.visionMode,
                requestedKnowledge = graph.phase1Features.isKnowledgeEnabled(),
                requestedTroubleshooting = enabled,
                startNewChat = false
            )
            return
        }
        if (troubleshootingMode == enabled) {
            AndroVoiceController.setTroubleshootingMode(enabled)
            return
        }
        interruptActiveTurn("troubleshooting_mode_change", rearmListening = false)
        troubleshootingMode = enabled
        AndroVoiceController.setTroubleshootingMode(enabled)
        Log.i(
            TAG,
            "TROUBLESHOOTING_MODE_SET enabled=$enabled conversation=${graph.runtime.currentConversationId().take(12)} " +
                "vision=$visionMode kb=${graph.phase1Features.isKnowledgeEnabled()} preserved=true"
        )
        if (enabled) startTroubleshootingIntro()
    }

    private suspend fun startTroubleshootingIntro() {
        if (!sessionActive || !troubleshootingMode || stopping) return
        // Troubleshooting is a session mode, not a permanently-open microphone mode.
        // Voice remains available through the same explicit mic button as Normal Chat.
        AndroVoiceController.updateStreamingAnswer("Tell me what problem you are facing. You can type or tap the microphone, and I will guide you step by step.")
        AndroVoiceController.setListening(false)
        Log.i(TAG, "TROUBLESHOOTING_STARTED input=explicit_text_or_mic vad=false bargeIn=false autoRearm=false")
    }

    private suspend fun presentTroubleshootingAnswer(text: String) {
        if (!sessionActive || !troubleshootingMode || stopping || text.isBlank()) return
        val loop = voiceLoopOrCreate()
        if (!loop.ttsReady) loop.initializeTtsOnly()
        if (!loop.ttsReady) {
            // Speech is presentation only. Never destroy or pause a valid troubleshooting
            // turn because TTS is unavailable; the complete answer remains visible as text.
            Log.w(TAG, "TROUBLESHOOTING_TTS_UNAVAILABLE textPreserved=true")
            loop.setListening(false)
            return
        }
        // Strict half-duplex ownership: no microphone exists while FixSight speaks.
        // Playback is allowed to finish completely unless the user explicitly taps Stop.
        loop.prepareTypedTurn()
        loop.beginResponse()
        loop.speak(text)
        loop.endResponseAndAwaitDrain()
        loop.setListening(false)
        Log.i(TAG, "TROUBLESHOOTING_TTS_COMPLETE autoRearm=false chars=${text.length}")
    }

    private fun voiceLoopOrCreate(): AndroVoiceLoop {
        voiceLoop?.let { return it }
        val loop = AndroVoiceLoop(
            context = applicationContext,
            onPartial = { partial ->
                if (partial.isNotBlank() && !AndroVoiceController.state.value.turnActive)
                    AndroVoiceController.setTranscriptPreview(partial)
            },
            onFinal = { text -> if (text.isNotBlank()) launchAcceptedTurn(text, TurnOrigin.VOICE) },
            onListeningChanged = { active ->
                AndroVoiceController.setListening(active)
                if (!active) runCatching { stopForegroundCompat() }
            },
            onSpeakingChanged = { active -> AndroVoiceController.setSpeaking(active) },
            onFatalError = { error -> serviceScope.launch { containVoiceFailure("voice_loop", error) } }
        )
        voiceLoop = loop
        return loop
    }

    private suspend fun startManualVoiceInput() {
        if (!sessionActive || stopping) return
        val snapshot = AndroVoiceController.currentTurnSnapshot()
        if (snapshot.generationActive) {
            // Explicit mic tap owns the interaction. Cancel the in-flight response and
            // immediately hand control to microphone capture; accepted text is preserved.
            interruptActiveTurn("mic_takeover", rearmListening = false)
        } else if (snapshot.speakingAcceptedAnswer || AndroVoiceController.state.value.speaking) {
            stopSpeechPresentation()
        }
        val loop = voiceLoopOrCreate()
        val ready = loop.initializeSttOnly()
        AndroVoiceController.setVoiceReady(ready, loop.sttReady, loop.ttsReady, initComplete = true)
        if (!ready) {
            AndroVoiceController.setError("Voice input is unavailable. You can continue typing.", keepSession = true)
            return
        }
        loop.start()
        loop.beginManualVoiceInput()
        Log.i(TAG, "ANDRO_MANUAL_VOICE_INPUT_ACTIVE vad=false")
    }


    private suspend fun stopSpeechPresentation() {
        if (!sessionActive || stopping) return
        val state = AndroVoiceController.state.value
        if (!state.speaking) return
        // Stop playback only. Do not cancel an already accepted answer, clear text,
        // end the session, or automatically reopen the microphone.
        voiceLoop?.interrupt(rearmListening = false)
        AndroVoiceController.setSpeaking(false)
        Log.i(TAG, "ANDRO_SPEECH_STOPPED user=true acceptedAnswerPreserved=true")
    }

    private suspend fun stopManualVoiceInput() {
        voiceLoop?.requestManualVoiceStop()
    }

    private suspend fun speakTextOnDemand(text: String) {
        if (!sessionActive || stopping || text.isBlank()) return
        val loop = voiceLoopOrCreate()
        val ready = loop.initializeTtsOnly()
        AndroVoiceController.setVoiceReady(loop.sttReady, loop.sttReady, ready, initComplete = true)
        if (!ready) {
            AndroVoiceController.setError("Speech output is unavailable; the answer remains available as text.", keepSession = true)
            return
        }
        loop.prepareTypedTurn()
        loop.beginResponse()
        loop.speak(text)
        loop.endResponseAndAwaitDrain()
        loop.setListening(false)
        Log.i(TAG, "ANDRO_TTS_ON_DEMAND_COMPLETE chars=${text.length}")
    }

    private suspend fun submitTypedTurn(text: String, forceExternalInformation: Boolean = false) {
        if (!sessionActive || stopping) return
        voiceLoop?.prepareTypedTurn()
        interruptActiveTurn("typed_input", rearmListening = false, keepTypedGate = true)
        launchAcceptedTurn(text, TurnOrigin.TYPED, forceExternalInformation)
    }

    private fun launchAcceptedTurn(
        query: String,
        origin: TurnOrigin,
        forceExternalInformation: Boolean = false
    ) {
        if (!sessionActive || stopping || query.isBlank()) return
        val admittedQuery = query.trim()
        if (visionMode) Log.d(TAG, "VIDEO_TURN_POLICY oneFreshFrame=true backgroundVlm=false")
        synchronized(ownershipLock) {
            if (activeTurnJob?.isActive == true) {
                Log.w(TAG, "ANDRO_SERVICE_TURN_DROPPED reason=active_turn origin=$origin")
                return
            }
            // V22: publish the accepted user turn before coroutine scheduling / preprocessing.
            // This makes the next troubleshooting question visible immediately instead of
            // waiting for transcript repair, camera capture, model inference, or final commit.
            AndroVoiceController.beginTurn(admittedQuery)
            activeTurnJob = serviceScope.launch {
                turnMutex.withLock {
                    handleTurn(admittedQuery, origin, forceExternalInformation)
                }
            }
        }
    }

    private fun shouldRunContextualTranscriptResolver(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        if ('\uFFFD' in clean || '\u0000' in clean) return true
        // Typical Whisper entity artifact such as "A-TEL". Mixed-case normal terms
        // such as Wi-Fi / X-ray are intentionally not matched.
        if (Regex("""\b[A-Z]{1,3}-[A-Z]{2,}\b""").containsMatchIn(clean)) return true
        // Repeated punctuation / replacement-like surfaces are recovery candidates.
        if (Regex("""[?!.]{4,}""").containsMatchIn(clean)) return true
        return false
    }

    private suspend fun handleTurn(
        query: String,
        origin: TurnOrigin,
        forceExternalInformation: Boolean = false
    ) {
        val thisJob = currentCoroutineContext()[Job]
        // Saved messages written from here on belong to this turn (see saveTurnForDisplay).
        val turnStartedMs = System.currentTimeMillis()
        val loop = voiceLoop
        val turnVisionMode = visionMode
        val turnAttachments = graph.attachmentManager.claimPendingAttachmentsForTurn()
        val attachmentNames = turnAttachments.joinToString(", ") { it.displayName }
        // Camera and documents may compose in the same troubleshooting turn. The live
        // frame is independent evidence; staged documents remain available to runtime.
        val useLiveFrame = turnVisionMode
        var attachmentLeaseReleased = false
        val provisionalLocalPreview = StringBuilder()
        var lastLocalPreviewPublishAtMs = 0L
        var lastLocalPreviewPublishedChars = 0
        val localSpeechAssembler = AndroSentenceAssembler()
        val usedLiveLocalSpeech = AtomicBoolean(false)

        // The UI must leave LISTENING/READY as soon as Whisper has produced the
        // final user turn. Any exceptional preprocessing happens while THINKING,
        // never during a misleading READY pause.
        val uiStateAtTurnStart = AndroVoiceController.state.value
        if (!uiStateAtTurnStart.turnActive || uiStateAtTurnStart.transcript.trim() != query.trim()) {
            AndroVoiceController.beginTurn(query)
        }
        CrashBreadcrumbs.add("VOICE_TURN_BEGIN origin=$origin vision=$turnVisionMode attachments=$attachmentNames")
        VisionWorkCoordinator.memorySnapshot("turn_begin")

        // No static capability/topic pre-router lives in the voice service. Keep Local
        // ready before every turn, including camera/VLM turns and selected-KB turns.
        // This uses the same application-scoped load boundary for visual and text paths,
        // preventing a first visual turn from racing model warm-up and unnecessarily
        // falling through to Cloud/offline handling.
        val lm = graph.runtime.localModelManager
        if (lm != null && (!lm.isReady || !lm.isTextWarm) && !lm.permanentlyDisabled) {
            // Session startup already preloads Local asynchronously. If a first turn
            // arrives before preload completes, wait on the single application-scoped
            // model-load boundary rather than imposing an arbitrary cutoff that could
            // force an unnecessary Cloud fallback on a healthy slow load.
            val localReady = graph.ensureLocalModelReady()
            Log.i(
                TAG,
                "ANDRO_SERVICE_LOCAL_FIRST_READY beforeTurn=$localReady state=${lm.state} " +
                    "visualTurn=$turnVisionMode"
            )
        }

        // Ordinary KB-OFF voice keeps AndroLLM's exceptional-only transcript repair.
        // Selected-KB follow-ups additionally get one detached context check once an
        // investigation already has case state. This is intentionally KB-scoped: a
        // fluent but wildly unrelated Whisper transcript must not contaminate an active
        // SOP investigation, while the first KB turn and normal generic chat remain on
        // the proven fast path.
        val knowledgeEnabledForTurn = graph.phase1Features.isKnowledgeEnabled()
        val kbNotebook = if (knowledgeEnabledForTurn) graph.phase1Features.notebookSnapshot() else null
        val kbHasCaseState = kbNotebook != null && (
            kbNotebook.problem.isNotBlank() ||
                kbNotebook.confirmedFacts.isNotEmpty() ||
                kbNotebook.userResponses.isNotEmpty() ||
                kbNotebook.questionsAlreadyAsked.isNotEmpty()
            )
        val suspiciousSurface = shouldRunContextualTranscriptResolver(query)
        val kbLikelyClarificationAnswer =
            knowledgeEnabledForTurn &&
                kbHasCaseState &&
                query.length <= 64 &&
                !query.trimEnd().endsWith("?") &&
                kbNotebook?.questionsAlreadyAsked?.isNotEmpty() == true
        val shouldResolveTranscript = origin == TurnOrigin.VOICE &&
            !turnVisionMode &&
            !kbLikelyClarificationAnswer &&
            (suspiciousSurface || (knowledgeEnabledForTurn && kbHasCaseState))
        val transcriptResolution = if (shouldResolveTranscript) {
            Log.i(
                TAG,
                "STT_CONTEXT_RESOLVER_TRIGGER reason=" +
                    if (knowledgeEnabledForTurn && kbHasCaseState) "kb_followup_context_check" else "suspicious_surface"
            )
            graph.runtime.resolveVoiceTranscript(query)
        } else {
            Log.i(
                TAG,
                "STT_CONTEXT_RESOLVER_SKIPPED reason=" +
                    when {
                        kbLikelyClarificationAnswer -> "kb_short_clarification_answer"
                        knowledgeEnabledForTurn -> "kb_first_turn_no_case_state"
                        else -> "clean_surface"
                    }
            )
            VoiceTranscriptResolution(action = "keep", text = query, confidence = 1.0)
        }
        val effectiveQuery = if (transcriptResolution.corrected) transcriptResolution.text.trim() else query
        if (effectiveQuery != query) {
            Log.i(
                TAG,
                "ANDRO_SERVICE_TRANSCRIPT_CORRECTED raw='${query.take(100)}' " +
                    "resolved='${effectiveQuery.take(100)}' confidence=${transcriptResolution.confidence}"
            )
            // Keep the visible turn text aligned with what actually enters accepted KV.
            AndroVoiceController.beginTurn(effectiveQuery)
        }

        Log.i(
            TAG,
            "ANDRO_SERVICE_TURN_BEGIN origin=$origin mode=${if (useLiveFrame) "AUDIO_VIDEO" else if (turnAttachments.isNotEmpty()) "ATTACHMENT" else "AUDIO_ONLY"} " +
                "chars=${effectiveQuery.length} attachments=${attachmentNames.ifBlank { "none" }}"
        )

        try {
            if (transcriptResolution.needsClarification) {
                val clarification = transcriptResolution.clarification.trim()
                val clarifyResult = AndroBaselineRuntime.TurnResult(
                    text = clarification,
                    provider = AndroBaselineRuntime.Provider.NONE,
                    elapsedMs = 0L,
                    streamedLocally = false
                )
                AndroVoiceController.updateStreamingAnswer(clarification)
                AndroVoiceController.finishTurn(clarifyResult)
                Log.i(
                    TAG,
                    "ANDRO_SERVICE_TRANSCRIPT_CLARIFY confidence=${transcriptResolution.confidence} " +
                        "question='${clarification.take(120)}'"
                )
                if (troubleshootingMode) presentTroubleshootingAnswer(clarification)
                return
            }

            val visualContext = buildString {
                if (troubleshootingMode) {
                    appendLine("<troubleshooting_mode>")
                    appendLine("Guide the user interactively toward diagnosis or resolution.")
                    appendLine("Use facts already established in this conversation and Notebook.")
                    appendLine("Keep troubleshooting replies short and action-oriented: normally 1 to 3 short sentences and under about 60 words.")
                    appendLine("Give only ONE concrete next diagnostic action OR ask ONE focused next question per turn.")
                    appendLine("Only expand into a longer explanation or full procedure when the user explicitly asks for details, steps, or a full list.")
                    appendLine("Do not repeat a question already answered. Do not restart the investigation from the beginning.")
                    appendLine("When enough evidence exists, state the likely cause and the next safe action clearly.")
                    // This case's working diagnosis and step results (written by camera troubleshooting),
                    // so the case continues if the camera is switched off. Troubleshooting mode only.
                    val caseNotes = runCatching { graph.phase1Features.notebookObserver.troubleshootingNotes() }.getOrDefault("")
                    if (caseNotes.isNotBlank()) appendLine(caseNotes.take(1_500))
                    appendLine("</troubleshooting_mode>")
                }
                if (useLiveFrame) {
                    val live = AndroVoiceAssistant.currentVisualContext().trim()
                    if (live.isNotBlank()) append(live)
                }
                val durable = buildDurableNotebookContext().trim()
                if (durable.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(durable)
                }
            }
            // Turn-owned camera capture: exactly one capture request per committed
            // user turn.  The resulting Bitmap is immutable for this turn and is
            // passed to runtime.chat(); no second camera capture is requested.
            val activeFrame = if (useLiveFrame) {
                Log.i(TAG, "VIDEO_TURN_FRAME_CAPTURE_BEGIN oneFramePerTurn=true")
                try {
                    AndroVoiceAssistant.captureVisualFrame()
                } catch (ce: CancellationException) {
                    // Cancellation is a user turn interruption, not a missing frame.
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "VIDEO_ACTIVE_FRAME_CAPTURE_FAILED ${t.message}")
                    null
                }
            } else null

            if (useLiveFrame) {
                Log.i(
                    TAG,
                    if (activeFrame != null) "VIDEO_ACTIVE_FRAME_CAPTURED ${activeFrame.width}x${activeFrame.height}"
                    else "VIDEO_ACTIVE_FRAME_MISSING"
                )
            }

            val result = try {
                graph.runtime.chat(
                userText = effectiveQuery,
                bitmap = activeFrame,
                forceExternalInformation = forceExternalInformation,
                visionModeActive = useLiveFrame,
                visualContext = visualContext,
                visualFrameProvider = null,
                turnAttachments = turnAttachments,
                onLocalDelta = { delta ->
                    // Ordinary KB-OFF Local turns already arrive here as clean incremental
                    // text deltas from the resident Gemma conversation. Surface that
                    // candidate text immediately so the UI reflects the model's ~first-token
                    // latency instead of waiting for EOS. Runtime only emits these deltas when
                    // livePresentationAllowed=true, so grounded KB/attachment/visual turns and
                    // freshness-planner-owned turns keep their existing final-commit boundary.
                    //
                    // UI publishing is throttled by time/character growth to avoid recomposing
                    // Compose for every token. This is content-agnostic: no topic keywords or
                    // domain rules participate in streaming.
                    // LiteRT can briefly surface a structured-content placeholder as
                    // a plain `text` fragment while a resident stream is warming. It
                    // is not part of the accepted answer (the completed result is
                    // already sanitized), so never put a protocol-only fragment in
                    // the transient UI preview. This guard is deliberately limited
                    // to the ordinary Local preview boundary; final text, history,
                    // TTS and every KB/grounded path remain unchanged.
                    if (delta.isNotBlank() && !isStreamingProtocolPlaceholder(delta)) {
                        val preview = synchronized(provisionalLocalPreview) {
                            provisionalLocalPreview.append(delta)
                            provisionalLocalPreview.toString()
                        }
                        val now = System.currentTimeMillis()
                        val firstPublish = lastLocalPreviewPublishAtMs == 0L
                        val enoughTime = now - lastLocalPreviewPublishAtMs >= 60L
                        val enoughGrowth = preview.length - lastLocalPreviewPublishedChars >= 24
                        if (firstPublish || enoughTime || enoughGrowth) {
                            AndroVoiceController.updateStreamingAnswer(preview)
                            lastLocalPreviewPublishAtMs = now
                            lastLocalPreviewPublishedChars = preview.length
                        }
                    }
                },
                onLocalSpeechDelta = { _ ->
                    // Phase 23 text-first: model generation never starts TTS. Speech is
                    // a separate user-requested presentation action.
                },
                onLocalAbort = {
                    synchronized(provisionalLocalPreview) { provisionalLocalPreview.setLength(0) }
                    lastLocalPreviewPublishAtMs = 0L
                    lastLocalPreviewPublishedChars = 0
                    // A streamed Local draft is provisional until runtime accepts the turn.
                    // If deterministic validation rejects it and retry/Cloud takes ownership,
                    // clear only the preview while preserving the same user turn.
                    AndroVoiceController.beginTurn(effectiveQuery)
                    synchronized(localSpeechAssembler) { localSpeechAssembler.flush(force = true) }
                    usedLiveLocalSpeech.set(false)
                    loop?.abortPresentationForRetry()
                    Log.w(TAG, "ANDRO_SERVICE_LOCAL_ATTEMPT_ABORT streamedPreviewCleared=true")
                }
                )
            } finally {
                // The single Live Video frame is turn-scoped. Runtime/cloud/local
                // inference is complete when chat() returns; retaining this Bitmap
                // across turns caused heap growth and session crashes.
                if (activeFrame != null && !activeFrame.isRecycled) {
                    activeFrame.recycle()
                    Log.i(TAG, "VIDEO_TURN_FRAME_RELEASED rev=V9_0_STABLE_AR")
                }
            }

            // Durable multimodal memory: accepted camera observations survive far beyond
            // the transient VisualStateStore and remain available on later audio-only
            // turns. Raw pixels are never persisted.
            if (result.provider != AndroBaselineRuntime.Provider.NONE && result.visualObservation.isNotBlank()) {
                val source = if (result.provider == AndroBaselineRuntime.Provider.CLOUD) {
                    "CLOUD_LIVE_VISION"
                } else {
                    "LOCAL_LIVE_VISION"
                }
                graph.phase1Features.notebookObserver.recordVerifiedVisualObservation(
                    result.visualObservation,
                    source = source
                )
                graph.runtime.onVisualObservation?.invoke(result.visualObservation)
                Log.i(TAG, "LIVE_VISUAL_NOTEBOOK_PROMOTED source=$source chars=${result.visualObservation.length}")
            }

            if (result.provider != AndroBaselineRuntime.Provider.NONE && turnAttachments.isNotEmpty()) {
                for (attachment in turnAttachments) {
                    graph.phase1Features.notebookObserver.recordAttachmentSource(attachment.displayName)
                    if (attachment.localEvidenceSubstantial && attachment.localVisualContext.isNotBlank()) {
                        graph.phase1Features.notebookObserver.recordVerifiedVisualObservation(
                            attachment.localVisualContext,
                            source = "ML_KIT_ATTACHMENT"
                        )
                    }
                    graph.attachmentRecallProvider.rememberAccepted(attachment)
                    graph.phase1Features.capabilityProvider.scheduleAttachmentContinuationPrewarm()
                }
                graph.attachmentManager.releaseTurnAttachments(turnAttachments.map { it.id }, consumed = true)
                attachmentLeaseReleased = true
                AndroVoiceController.setPendingAttachment("")
            }

            // Phase 23 text-first: every accepted Local/Cloud answer is committed as
            // text only. The user may tap Listen afterward; cloud text stays untouched.
            val streamedLocalSpeech = false
            if (result.text.isNotBlank()) AndroVoiceController.updateStreamingAnswer(result.text)

            Log.i(TAG, "ANDRO_SERVICE_GENERATION_END provider=${result.provider} chars=${result.text.length}")

            // PHASE16: generation ownership and speech ownership are deliberately separated.
            // Once the model has produced an accepted final answer, commit it to the UI and
            // accepted-turn observers immediately. TTS is presentation only and must never
            // be able to roll back/delete an already generated answer.
            AndroVoiceController.finishTurn(result)
            val visibleTroubleshootingFallback = troubleshootingMode &&
                result.provider == AndroBaselineRuntime.Provider.NONE &&
                result.text.isNotBlank()
            val visibleGeneralFailure = result.provider == AndroBaselineRuntime.Provider.NONE &&
                result.text.isNotBlank()
            if (result.provider != AndroBaselineRuntime.Provider.NONE || visibleGeneralFailure) {
                if (visibleTroubleshootingFallback) {
                    // A fully exhausted local/cloud turn still needs a durable UI
                    // event. Without this, the user's prompt disappears after a
                    // visual failure even though the troubleshooting session remains
                    // alive for the next turn.
                    Log.w(TAG, "ANDRO_SERVICE_TROUBLESHOOTING_FALLBACK_VISIBLE chars=${result.text.length}")
                }
                if (visibleGeneralFailure && !troubleshootingMode) {
                    // General chat and attachment failures must remain visible as a
                    // completed turn too. This is a UI/audit event only: Provider.NONE
                    // is not promoted to Notebook or accepted conversation memory.
                    Log.w(TAG, "ANDRO_SERVICE_GENERAL_FAILURE_VISIBLE chars=${result.text.length}")
                }
                if (result.provider != AndroBaselineRuntime.Provider.NONE) {
                    graph.sessionAuditTrail.recordAcceptedTurn(
                        userText = effectiveQuery,
                        result = result,
                        attachmentName = attachmentNames
                    )
                }
                // Saved before the screen is told: if the app is in the background (or its screen was
                // closed by Android), the turn, its sources and badges are restored from here.
                graph.runtime.saveTurnForDisplay(effectiveQuery, result, turnStartedMs)
                AndroVoiceController.emitAcceptedTurn(
                    AndroVoiceTurnEvent(query = effectiveQuery, result = result, attachmentName = attachmentNames.ifBlank { null })
                )
            }
            Log.i(TAG, "ANDRO_SERVICE_FINAL_COMMITTED_BEFORE_TTS_DRAIN rev=V6_0_DURABLE_FINAL chars=${result.text.length}")

            Log.i(TAG, "ANDRO_SERVICE_TEXT_COMMITTED provider=${result.provider} speech=${if (troubleshootingMode) "automatic_troubleshooting" else "on_demand"}")
            if (troubleshootingMode) presentTroubleshootingAnswer(result.text)
            updateHealth()
            Log.i(
                TAG,
                "ANDRO_SERVICE_TURN_END provider=${result.provider} elapsed=${result.elapsedMs}ms " +
                    "chars=${result.text.length} streamedSpoken=$streamedLocalSpeech finalOnly=${!streamedLocalSpeech}"
            )
        } catch (ce: CancellationException) {
            val rearm = false
            loop?.settleCancelledResponse(rearmListening = rearm)
            AndroVoiceController.cancelTurn(rearmListening = rearm)
            Log.i(TAG, "ANDRO_SERVICE_TURN_CANCELLED origin=$origin rearm=$rearm")
            throw ce
        } catch (t: Throwable) {
            loop?.abortPresentationForRetry()
            Log.e(TAG, "ANDRO_SERVICE_TURN_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            runCatching { graph.runtime.resetNativeConversationForRecovery() }
            val fallback = AndroBaselineRuntime.TurnResult(
                text = "I couldn't complete that turn. Please try again.",
                provider = AndroBaselineRuntime.Provider.NONE,
                elapsedMs = 0L,
                streamedLocally = false
            )
            AndroVoiceController.finishTurn(fallback)
            // Preserve the user's prompt in the visible turn timeline even when the
            // runtime throws before it can return a normal TurnResult. This is a UI
            // retry event only; the failed Provider.NONE result is not accepted into
            // Notebook or authoritative conversation memory.
            graph.runtime.saveTurnForDisplay(query, fallback, turnStartedMs)
            AndroVoiceController.emitAcceptedTurn(
                AndroVoiceTurnEvent(
                    query = query,
                    result = fallback,
                    attachmentName = attachmentNames.ifBlank { null }
                )
            )
            if (troubleshootingMode) presentTroubleshootingAnswer(fallback.text)
            updateHealth()
        } finally {
            if (turnAttachments.isNotEmpty() && !attachmentLeaseReleased) {
                graph.attachmentManager.releaseTurnAttachments(turnAttachments.map { it.id }, consumed = false)
                Log.i(TAG, "ATTACHMENT_TURN_RELEASED count=${turnAttachments.size} consumed=false")
            }
            VisionWorkCoordinator.memorySnapshot("turn_end")
            CrashBreadcrumbs.add("VOICE_TURN_END vision=$turnVisionMode attachments=${turnAttachments.size} attachmentReleased=$attachmentLeaseReleased")

            // Recovery is intentionally outside the user turn: never make a KB,
            // attachment, visual or ordinary answer wait for a model reload.  If
            // memory pressure or a quarantined native session made Local unavailable,
            // restore it in the app-scoped serialized loader for the next turn.
            graph.runtime.localModelManager?.let { lm ->
                if (lm.shouldRecoverQuarantinedEngineInBackground) {
                    serviceScope.launch {
                        val recovered = runCatching { graph.ensureLocalModelReady() }.getOrDefault(false)
                        Log.i(
                            TAG,
                            "ANDRO_SERVICE_LOCAL_BACKGROUND_RECOVERY recovered=$recovered state=${lm.state} " +
                                "quarantined=${lm.permanentlyDisabled}"
                        )
                    }
                }
            }
            synchronized(ownershipLock) {
                if (activeTurnJob === thisJob) activeTurnJob = null
            }
        }
    }

    /**
     * Compact durable investigation state for 30-40 turn continuity. The raw
     * conversation remains token-bounded in AndroConversationStore; Notebook carries
     * older established user facts and accepted visual findings after those raw turns
     * leave the hot context window.
     */
    private fun buildDurableNotebookContext(): String {
        val notebook = graph.phase1Features.notebookSnapshot()
        val hasState = notebook.problem.isNotBlank() ||
            notebook.confirmedFacts.isNotEmpty() ||
            notebook.visualFindings.isNotEmpty() ||
            notebook.userResponses.isNotEmpty()
        if (!hasState) return ""
        return buildString {
            append("<durable_case_notebook>\n")
            if (notebook.problem.isNotBlank()) append("Problem: ${notebook.problem.take(260)}\n")
            if (notebook.currentGoal.isNotBlank()) append("Current goal: ${notebook.currentGoal.take(260)}\n")
            if (notebook.confirmedFacts.isNotEmpty()) {
                append("Established user facts:\n")
                notebook.confirmedFacts.takeLast(12).forEach { append("- ${it.take(240)}\n") }
            }
            if (notebook.userStatements.isNotEmpty()) {
                append("Reported user statements (not visually verified):\n")
                notebook.userStatements.takeLast(12).forEach { append("- ${it.take(240)}\n") }
            }
            if (notebook.visualFindings.isNotEmpty()) {
                append("Historical visual findings from earlier camera views:\n")
                notebook.visualFindings.takeLast(12).forEach { append("- ${it.take(260)}\n") }
            }
            if (notebook.visualConflicts.isNotEmpty()) {
                append("Visual conflicts requiring verification:\n")
                notebook.visualConflicts.takeLast(8).forEach { append("- ${it.take(320)}\n") }
            }
            if (notebook.userResponses.isNotEmpty()) {
                append("Relevant prior follow-ups:\n")
                notebook.userResponses.takeLast(6).forEach { append("- ${it.take(260)}\n") }
            }
            if (notebook.questionsAlreadyAsked.isNotEmpty()) {
                append("Questions already asked:\n")
                notebook.questionsAlreadyAsked.takeLast(6).forEach { append("- ${it.take(220)}\n") }
            }
            append("Use this only as prior case memory. Do not treat an old visual finding or a user report as current visual fact unless the current frame confirms it. If they conflict, preserve both, state the uncertainty, and ask one focused clarification or escalate the same frame.\n")
            append("Do not treat an old visual finding as current without present-frame confirmation.\n")
            append("</durable_case_notebook>")
        }.take(3_400)
    }

    /**
     * Visual AI is turn-triggered. The camera preview may remain continuously active,
     * but expensive local VLM inference is intentionally never scheduled in the
     * background. Each committed user turn owns exactly one fresh frame.
     */
    private suspend fun interruptActiveTurn(
        reason: String,
        rearmListening: Boolean,
        keepTypedGate: Boolean = false
    ) {
        cancelledTurnShouldRearm = rearmListening && !keepTypedGate
        val job = synchronized(ownershipLock) { activeTurnJob }
        val interrupted = AndroVoiceController.currentTurnSnapshot()
        if (job != null && job.isActive) {
            if (interrupted.generationActive && interrupted.query.isNotBlank()) {
                graph.runtime.recordInterruptedTurn(interrupted.query, interrupted.partialAnswer)
                // Shown in the chat after a restart too (display only, never model memory).
                graph.runtime.saveTurnForDisplay(
                    interrupted.query,
                    AndroBaselineRuntime.TurnResult(
                        text = interrupted.partialAnswer.trim().let { if (it.isBlank()) "Response interrupted before completion." else "$it\n\n[Interrupted]" },
                        provider = AndroBaselineRuntime.Provider.NONE,
                        elapsedMs = 0L,
                        streamedLocally = false,
                        sourceIds = listOf("interrupted:$reason")
                    ),
                    turnStartedMs = Long.MAX_VALUE
                )
                AndroVoiceController.emitInterruptedTurn(
                    AndroInterruptedTurnEvent(
                        query = interrupted.query,
                        partialAnswer = interrupted.partialAnswer,
                        reason = reason
                    )
                )
                graph.sessionAuditTrail.recordInterruptedTurn(
                    userText = interrupted.query,
                    partialAnswer = interrupted.partialAnswer,
                    reason = reason
                )
                CrashBreadcrumbs.add("TURN_INTERRUPTED reason=$reason partialChars=${interrupted.partialAnswer.length}")
                Log.i(TAG, "ANDRO_SERVICE_INTERRUPTED_TURN_PRESERVED reason=$reason queryChars=${interrupted.query.length} partialChars=${interrupted.partialAnswer.length}")
            }
            if (interrupted.speakingAcceptedAnswer) {
                Log.i(TAG, "ANDRO_SERVICE_SPEECH_ONLY_INTERRUPT reason=$reason acceptedTurnPreserved=true")
            }
            Log.i(TAG, "ANDRO_SERVICE_CANCEL_REQUEST reason=$reason")
            job.cancel(CancellationException(reason))
            // Job cancellation is the public cancellation primitive. Joining occurs on
            // this worker scope, never the Android Main thread, and guarantees the old
            // native conversation finishes unwinding before another turn can enter.
            job.join()
        }
        synchronized(ownershipLock) {
            if (activeTurnJob === job) activeTurnJob = null
        }
        if (keepTypedGate) {
            voiceLoop?.interrupt(rearmListening = false)
            AndroVoiceController.cancelTurn(rearmListening = false)
        } else {
            voiceLoop?.interrupt(rearmListening = rearmListening && sessionActive)
            AndroVoiceController.cancelTurn(rearmListening = rearmListening && sessionActive)
        }
        cancelledTurnShouldRearm = true
    }

    private suspend fun setVisionMode(enabled: Boolean) {
        if (!sessionActive || stopping) return
        val snapshot = AndroVoiceController.currentTurnSnapshot()
        if (snapshot.generationActive) {
            Log.w(TAG, "ANDRO_SERVICE_MODE_SWITCH_REJECT generationActive=true")
            return
        }
        // Accepted-answer playback is presentation only. Camera mode may change while
        // TTS continues; the new mode applies to the next committed turn.

        visionMode = enabled
        AndroVoiceController.setVisionMode(enabled)
        if (enabled) {
            Log.i(TAG, "VIDEO_VISUAL_POLICY preview=continuous inference=one_frame_per_committed_turn")
        }
        Log.i(
            TAG,
            if (enabled) "MODE_SWITCH AUDIO_ONLY_TO_AUDIO_VIDEO preserveConversation=true"
            else "MODE_SWITCH AUDIO_VIDEO_TO_AUDIO_ONLY preserveConversation=true"
        )
    }

    private suspend fun setKnowledgeMode(enabled: Boolean) {
        if (!sessionActive || stopping) return
        val current = graph.phase1Features.isKnowledgeEnabled()
        if (enabled != current) {
            // V1 invariant: Knowledge mode is chosen before session start and is
            // immutable for the lifetime of that investigation. UI may end the
            // session and preselect the opposite mode for the next case.
            Log.w(TAG, "KB_SESSION_MODE_LOCKED requested=$enabled current=$current")
            AndroVoiceController.setKnowledgeMode(current)
            return
        }
        // Idempotent mirror only; never mutate the routing gate mid-session.
        AndroVoiceController.setKnowledgeMode(current)
        Log.i(TAG, "KB_SESSION_MODE_CONFIRMED enabled=$current")
    }

    private suspend fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        sessionActive = false
        troubleshootingMode = false
        Log.i(TAG, "ANDRO_SERVICE_SESSION_STOP reason=$reason")

        val job = synchronized(ownershipLock) { activeTurnJob }
        if (job != null && job.isActive) {
            job.cancel(CancellationException(reason))
            job.join()
        }
        synchronized(ownershipLock) { activeTurnJob = null }

        val loop = voiceLoop
        voiceLoop = null
        loop?.stop()
        runCatching { graph.runtime.endChat() }
        graph.attachmentManager.clearPending()
        graph.attachmentRecallProvider.clear()
        graph.phase1Features.setKnowledgeEnabled(false)
        AndroVoiceController.setKnowledgeMode(false)
        AndroVoiceController.setPendingAttachment("")
        AndroVoiceController.endSession()

        if (loop != null) {
            // Native release remains on this worker scope; Activity never waits for it.
            runCatching { loop.releaseAsync() }
                .onFailure { Log.w(TAG, "ANDRO_SERVICE_VOICE_RELEASE_FAILED ${it.message}") }
        }

        stopping = false
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun containVoiceFailure(stage: String, t: Throwable) {
        if (t is CancellationException) throw t
        Log.e(TAG, "ANDRO_SERVICE_FATAL_BOUNDARY stage=$stage ${t.javaClass.simpleName}: ${t.message}", t)
        runCatching { voiceLoop?.stop() }
        runCatching { graph.runtime.resetNativeConversationForRecovery() }
        updateHealth()
        AndroVoiceController.setError(
            "Voice assistant recovered from an internal error. Please try again.",
            keepSession = sessionActive
        )
    }

    private fun updateHealth() {
        AndroVoiceController.setRuntimeHealth(graph.runtime.healthSnapshot().compactLabel())
    }

    /**
     * Returns true only for a stream fragment made entirely from the protocol word
     * `text` (optionally repeated or surrounded by JSON punctuation). Natural
     * prose such as "text is missing" is not filtered. This affects provisional
     * rendering only; the accepted model result is never rewritten here.
     */
    private fun isStreamingProtocolPlaceholder(delta: String): Boolean =
        STREAMING_TEXT_PLACEHOLDER_REGEX.matches(delta.trim())

    override fun onDestroy() {
        // Android invokes this on Main. Never wait for native resources here.
        sessionActive = false
        val turn = synchronized(ownershipLock) {
            val current = activeTurnJob
            activeTurnJob = null
            current
        }
        turn?.cancel(CancellationException("service_destroyed"))

        val loop = voiceLoop
        voiceLoop = null
        loop?.stop()
        serviceScope.cancel()

        if (loop != null || turn != null) {
            cleanupScope.launch {
                try {
                    if (turn != null) runCatching { turn.join() }
                    if (loop != null) runCatching { loop.releaseAsync() }
                } finally {
                    cleanupScope.cancel()
                }
            }
        } else {
            cleanupScope.cancel()
        }
        AndroVoiceController.endSession()
        Log.i(TAG, "ANDRO_SERVICE_DESTROYED mainBlocked=false")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ArogyaDoot", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /**
     * Foreground status (the app is not killed while the user is in the photo picker or camera
     * app). Microphone type only when the microphone permission is granted; otherwise dataSync.
     * Never throws: a refused promotion only means the service runs as a normal service.
     */
    private fun promoteToForeground(microphone: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val type = if (microphone && micGranted) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FOREGROUND_PROMOTION_REFUSED microphone=$microphone ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ArogyaDoot session")
            .setContentText("Text assistant active • voice on demand")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    private enum class TurnOrigin { VOICE, TYPED }

    companion object {
        private const val TAG = "AndroVoiceService"
        private const val CHANNEL_ID = "fixsight_voice"
        private const val NOTIFICATION_ID = 4102
        private val STREAMING_TEXT_PLACEHOLDER_REGEX = Regex(
            """(?i)^[\s\p{Punct}]*text(?:[\s\p{Punct}]+text)*[\s\p{Punct}]*$"""
        )
    }
}
