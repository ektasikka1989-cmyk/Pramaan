package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale


/**
 * FixSight's thin shell over the AndroLLM-style local conversation engine.
 *
 * Audio-only design contract (Stabilization Patches A + B):
 *
 * P0-A: KB/memory evidence is now delivered to local Gemma via an ephemeral
 *   grounded Conversation (LocalModelManager.inferTextGrounded). The resident
 *   KV session is never mutated. Only the accepted answer enters shared history.
 *
 * P0-B: Single acceptance gate. Everything — commit, TTS callback, history
 *   write — happens AFTER the gate. Rejected output retires the native session
 *   so cloud's answer reseeds cleanly.
 *
 * P0-C: Adequacy gate replaces lexical novelty gate. Measures whether the
 *   response contains a concrete question, grounded content, or substantive
 *   length — not whether its word stems overlap with the query.
 *
 * P0-D: Context rollover implemented using actual container maxNumTokens.
 *   maybeRollNativeConversationForTokenBudget() is no longer a no-op.
 *
 * Live Camera is model-based. A ready local vision-capable model owns ordinary
 * camera turns first; Cloud is the direct fallback when Local VLM fails or when a
 * capability explicitly requires online/external handling. Visual, safety and
 * fresh/external requirements can coexist in the same turn, and both paths commit
 * to one shared accepted history.
 */
class AndroBaselineRuntime(
    private val context: Context,
    apiKey: String,
    private val modelContextWindowTokens: Int = DEFAULT_MODEL_CONTEXT_WINDOW_TOKENS,
    private val contextProviders: List<BaselineContextProvider> = emptyList(),
    private val acceptedTurnObservers: List<AcceptedTurnObserver> = emptyList()
) {
    companion object {
        private const val TAG = "AndroBaselineRuntime"
        /** Saved KB turns used to rebuild follow-up memory after a restart or when a conversation is reopened. */
        private const val KB_SEED_TURNS = 6
        private const val DEFAULT_MODEL_CONTEXT_WINDOW_TOKENS = 4_096
        private const val RESERVED_OUTPUT_TOKENS = 1_024
        private const val CONTEXT_SAFETY_TOKENS = 512
        private const val CLOUD_TURN_TIMEOUT_MS = 18_000L
        private const val EXTERNAL_CLOUD_TIMEOUT_MS = 90_000L
        /** A general (non-knowledge-base) answer for an out-of-scope question. */
        private const val GENERAL_ANSWER_TIMEOUT_MS = 60_000L
        // Detached AndroLLM-style ToolPlanner is used only for semantic ambiguity,
        // never as an always-on gate in front of normal Local chat.
        private const val LOCAL_PLANNER_TIMEOUT_MS = 8_000L
        private const val CAPABILITY_FRESH_REQUIRED = "fresh_required"
        private const val CAPABILITY_FRESH_PREFERRED = "fresh_preferred"
        // V3.5 uses one compact global source-bounded adjudication request, so it
        // shares the same bounded latency envelope as other cloud turns.
        private const val KB_SELECTOR_CLOUD_TIMEOUT_MS = 18_000L
        private const val LOCAL_TURN_TIMEOUT_MS = 20_000L
        private const val SAM_ONLINE_TIMEOUT_MS = 20_000L
        private const val PROOF_TIMEOUT_MS = 12_000L
        /** Evidence budget for "Check online": the online model reads much more than the phone's 6k context. */
        private const val CHECK_ONLINE_CONTEXT_TOKENS = 24_000
        private const val VISIT_NOTE_MODEL_TIMEOUT_MS = 30_000L
        private const val VISIT_NOTE_MAX_TOKENS = 320
        private const val GENERIC_KB_LOCAL_TIMEOUT_MS = 26_000L
        private const val GENERIC_KB_RETRY_TIMEOUT_MS = 12_000L
        private const val ATTACHMENT_ONLINE_LOCAL_TIMEOUT_MS = 20_000L
        private const val ATTACHMENT_HIERARCHICAL_TIMEOUT_MS = 90_000L
        private const val LOCAL_VLM_TURN_TIMEOUT_MS = 45_000L
        // Troubleshooting prioritizes a complete evidence contract over the
        // shortest possible first token. The previous 12s/160-token envelope
        // frequently cut off headings and caused answer-only acceptance.
        private const val TROUBLESHOOTING_VLM_TIMEOUT_MS = 18_000L
        /** Local frame review: room for the 300-token seven-field contract on a phone GPU. */
        private const val TROUBLESHOOTING_REVIEW_TIMEOUT_MS = 26_000L
        private const val TROUBLESHOOTING_REVIEW_TEMPERATURE = 0.2
        private const val TROUBLESHOOTING_VLM_MAX_OUTPUT_TOKENS = 224
        private const val TROUBLESHOOTING_VLM_HIGH_DETAIL_MAX_OUTPUT_TOKENS = 256
        private const val TROUBLESHOOTING_VLM_INVENTORY_MAX_OUTPUT_TOKENS = 384
        private const val TROUBLESHOOTING_EVIDENCE_DEFAULT_LIMIT = 6
        private const val TROUBLESHOOTING_EVIDENCE_HIGH_DETAIL_LIMIT = 10
        private const val TROUBLESHOOTING_EVIDENCE_INVENTORY_LIMIT = 16
        // Live-assist vision is perception, not essay generation. A short bounded
        // observer keeps the camera interaction responsive on-device; full-image
        // attachment analysis can still use the larger VLM envelope above.
        private const val LIVE_VISUAL_OBSERVER_TIMEOUT_MS = 15_000L
        private const val LIVE_VISUAL_OBSERVER_MAX_TOKENS = 96
        private const val LIVE_VISUAL_REASONING_TIMEOUT_MS = 12_000L
        private const val LIVE_VISUAL_REASONING_MAX_TOKENS = 320
        private const val ATTACHMENT_OFFLINE_RETRY_TIMEOUT_MS = 12_000L

        // System prompt token estimate for context budget calculation.
        // Gemma system prompt is ~100 words / ~133 tokens. Add template overhead.
        private const val SYSTEM_PROMPT_TOKEN_ESTIMATE = 180
        /** Evidence tokens for a turn about the user's own attached file(s) (see budgetLocalEvidence). */
        private const val USER_ATTACHMENT_EVIDENCE_TOKENS = 1_600
        /** Earlier messages (user + assistant) kept with a plain-chat follow-up question. */
        private const val PLAIN_CHAT_HISTORY_MESSAGES = 4

        // Fraction of model context available for history after reserving
        // output budget and system prompt. Conservative to prevent overflow.
        private const val HISTORY_BUDGET_FRACTION = 0.65
        private const val CROSS_TURN_DUPLICATE_JACCARD = 0.82
        // Hold a short prefix of ordinary live local speech until it is clearly
        // not just the user's own question echoed back by the model.
        private const val LIVE_ECHO_PREFIX_MAX_CHARS = 96
        // Recovery retry is exceptional; normal turns do not run a second LLM checker.
        private const val LOCAL_PLAIN_RETRY_TIMEOUT_MS = 5_000L
        private const val KB_GROUNDED_RETRY_TIMEOUT_MS = 8_000L
    }

    enum class Provider { LOCAL, CLOUD, NONE }

    data class TurnResult(
        val text: String,
        val provider: Provider,
        val elapsedMs: Long,
        val streamedLocally: Boolean,
        val visualObservation: String = "",
        val sourceIds: List<String> = emptyList(),
        val troubleshootingResolutionPrompt: Boolean = false,
        val resolutionSummary: String = ""
    )

    private data class TurnCapabilities(
        val externalRequired: Boolean = false,
        val deterministicExternalRequired: Boolean = false,
        val externalAmbiguous: Boolean = false,
        val externalFreshLean: Boolean = false,
        val externalScore: Float = 0f,
        val localScore: Float = 0f,
        val presentStateScore: Float = 0f,
        val stableStateScore: Float = 0f,
        val verifiedGroundingRequired: Boolean = false,
        val safetyRequired: Boolean = false,
        val visualRequired: Boolean = false,
        val cameraEvidenceRelevant: Boolean = false,
        val spatialLocalizationRequired: Boolean = false,
        val arOverlayRequested: Boolean = false,
        val factualVerificationRequired: Boolean = false,
        val semanticOperational: Boolean = false,
        val fallbackUsed: Boolean = false
    )

    private data class AnswerPolicyDecision(
        val policy: AnswerPolicy,
        val cloudPreferred: Boolean,
        val offerOnlineVerification: Boolean,
        val reason: String
    )

    val deviceProfile: DeviceCapabilityChecker.DeviceProfile =
        DeviceCapabilityChecker.getProfile(context)

    val localModelManager: LocalModelManager? =
        if (deviceProfile.canRunLocalModel) LocalModelManager(context.applicationContext) else null

    val modelFileManager: ModelFileManager = ModelFileManager(context.applicationContext)

    /** Meta vision models (DETR, CLIP, DINOv2, SAM) + ML Kit OCR, all on-device. */
    val metaVision = MetaVisionEngine(context.applicationContext, modelFileManager, MLKitProcessor())

    /** The camera has on-device eyes: a vision-language model, or the Meta vision stack with Llama. */
    val localCameraReady: Boolean
        get() = localModelManager?.isReadyForVision == true ||
            (isMetaStackActive && metaVision.isAvailable && localModelManager?.isReady == true)

    /**
     * Meta Stack: Meta Llama 3.2 (GGUF, llama.cpp) answers, and the camera goes through the Meta
     * vision models (DETR, CLIP, DINOv2, SAM) instead of a vision-language model. Active whenever
     * the Llama model is installed, unless the user forced Gemma or disconnected Llama.
     */
    val isMetaStackActive: Boolean
        get() {
            val force = modelFileManager.getForceModelMode()
            if (force == ForceModelMode.GEMMA_ONLY) return false
            if (modelFileManager.isLlamaDisconnected()) return false
            if (force == ForceModelMode.LLAMA_ONLY || force == ForceModelMode.ENSEMBLE_ONLY) return true
            return modelFileManager.isModelInstalled(ModelFileManager.ModelType.LLAMA3B)
        }

    fun recordAnswerSource(source: ModelSource, prompt: String = "") {
        modelFileManager.recordLastAnswerSource(source)
        val gemmaAttached = !modelFileManager.isGemmaDisconnected()
        val llamaAttached = !modelFileManager.isLlamaDisconnected()
        Log.d(
            "FixSightSource",
            "Answer source=$source, gemmaAttached=$gemmaAttached, llamaAttached=$llamaAttached, prompt=${prompt.take(50)}"
        )
    }

    private val chatStore = AndroConversationStore(context.applicationContext)
    private val turnMutex = Mutex()
    private val visionCapabilityMutex = Mutex()
    // Isolated live-troubleshooting case memory. This is deliberately not the
    // general conversation store and never changes KB/RAG/general-chat behavior.
    private val troubleshootingSessionState = TroubleshootingSessionState()

    // Routing-only memory for provider-aware freshness recovery. This is not a second
    // conversation store and never replaces accepted SQLite history.
    @Volatile private var lastAcceptedProviderForRouting: Provider = Provider.NONE
    @Volatile private var lastAcceptedUserForRouting: String = ""
    @Volatile private var lastAcceptedAssistantForRouting: String = ""
    @Volatile private var lastAcceptedExternalIntentForRouting: Boolean = false
    @Volatile private var currentTurnExternalIntentForRouting: Boolean = false
    @Volatile private var currentTurnKnowledgeSelectedForRouting: Boolean = false
    @Volatile private var currentTurnTroubleshootingForRouting: Boolean = false
    @Volatile private var currentTurnContinuesCloudEpisodeForRouting: Boolean = false

    // Final architecture migration: FixSight owns the conversation state; Local and
    // Cloud are interchangeable executors. The existing routing code remains intact
    // and compilable. This flag controls only the provider-neutral context adapter.
    private val useProviderNeutralTurnContext = true
    @Volatile private var activeEscalationEpisode: EscalationEpisode? = null

    private val notebookObserver: PassiveNotebookObserver?
        get() = acceptedTurnObservers.filterIsInstance<PassiveNotebookObserver>().firstOrNull()

    @Volatile private var groqProvider = GroqChatProvider(apiKey.trim())
    private val runtimeHealth = Phase1RuntimeHealth()
    private val answerPolicyStore = AnswerPolicyStore(context.applicationContext)

    fun answerPolicy(): AnswerPolicy = answerPolicyStore.get()
    fun onlineBackup(): Boolean = answerPolicyStore.onlineBackup()
    fun setOnlineBackup(enabled: Boolean) = answerPolicyStore.setOnlineBackup(enabled)
    fun onlineVoice(): Boolean = answerPolicyStore.onlineVoice()
    fun setOnlineVoice(enabled: Boolean) = answerPolicyStore.setOnlineVoice(enabled)

    fun setAnswerPolicy(policy: AnswerPolicy) {
        answerPolicyStore.set(policy)
        Log.i(TAG, "ANSWER_POLICY_CHANGED mode=${policy.name}")
    }

    // Existing context providers may also expose semantic validation/capability
    // decisions. This keeps one shared E5 engine and requires no extra ONNX model.
    private val responseValidators: List<BaselineResponseValidator>
        get() = contextProviders
            .filterIsInstance<BaselineResponseValidator>()
            .filterNot { it is SelectedKnowledgeContextProvider && !it.isSelected() }
    private val capabilityDeciders: List<BaselineCapabilityDecider>
        get() = contextProviders
            .filterIsInstance<BaselineCapabilityDecider>()
            .filterNot { it is SelectedKnowledgeContextProvider && !it.isSelected() }

    private val selectedKnowledgeProvider: SelectedKnowledgeContextProvider?
        get() = contextProviders.filterIsInstance<SelectedKnowledgeContextProvider>().firstOrNull()
    private val attachmentContextProvider: AttachmentSessionContextProvider?
        get() = contextProviders.filterIsInstance<AttachmentSessionContextProvider>().firstOrNull()
    private val semanticCapabilityProvider: SemanticCapabilityContextProvider?
        get() = contextProviders.filterIsInstance<SemanticCapabilityContextProvider>().firstOrNull()
    private val pocketRagSessions = PocketRagSessionStore(maxTurnsPerKb = 40)
    private val pocketRagEvidenceAssembler = PocketRagEvidenceAssembler { effectiveContextWindowTokens }
    // Generic selected-KB packets use the (possibly larger) KB window; ANM keeps the old budget.
    private val genericKbEvidenceAssembler = PocketRagEvidenceAssembler { kbContextWindowTokens }

    var onApiKeyFailed: (() -> Unit)? = null
    var onVisualObservation: ((String) -> Unit)? = null
    var onArPlacement: (suspend (ARIntent, DetectedObject) -> ARPlacementResult)? = null
    @Volatile private var lastArLocalizationProvider: Provider = Provider.NONE

    /**
     * Effective context token budget for local history.
     * Uses the container-resolved maxNumTokens when available, falls back to
     * the constructor parameter. This fixes the hardcoded 32,768 assumption.
     */
    private val effectiveContextWindowTokens: Int
        get() {
            val resolved = localModelManager?.resolvedModelContextTokens ?: 0
            return if (resolved > 0) resolved else modelContextWindowTokens
        }

    /**
     * Selected-KB window: the larger KV cache the local engine was opened with when the device
     * supports it (KB_CONTEXT_EXTENSION logs), otherwise exactly [effectiveContextWindowTokens].
     * Used only by the selected-KB retrieval budget, evidence packet and KB answer; every other
     * mode keeps [effectiveContextWindowTokens].
     */
    private val kbContextWindowTokens: Int
        get() = maxOf(effectiveContextWindowTokens, localModelManager?.kbContextTokens ?: 0)

    /**
     * Llama 3.2 3B runs on the phone CPU (llama.cpp) and writes ~3x slower than a GPU LiteRT
     * bundle; deadlines tuned for LiteRT would cut healthy Llama answers off.
     */
    private fun engineScaledTimeout(baseMs: Long): Long =
        if (localModelManager?.engineName == "llama.cpp") baseMs * 3 else baseMs

    /** KB local timeouts grow with the KB window (prompt processing time scales with evidence). */
    private fun kbScaledTimeout(baseMs: Long): Long {
        val base = effectiveContextWindowTokens.coerceAtLeast(1)
        val ratio = kbContextWindowTokens.toDouble() / base
        return if (ratio <= 1.0) baseMs else (baseMs * ratio).toLong()
    }

    private val historyTokenBudget: Int
        get() = ((effectiveContextWindowTokens - RESERVED_OUTPUT_TOKENS - SYSTEM_PROMPT_TOKEN_ESTIMATE)
            * HISTORY_BUDGET_FRACTION).toInt().coerceAtLeast(512)

    fun updateGroqApiKey(newKey: String) {
        groqProvider.cancelActiveCall()
        groqProvider = GroqChatProvider(newKey.trim())
    }

    fun prepareActiveVisualTurn() {
        groqProvider.cancelActiveCall()
        Log.i(TAG, "VIDEO_ACTIVE_TURN_PREEMPT_PASSIVE")
    }

    suspend fun validateGroqConnection(): Boolean =
        if (!canAttemptCloud()) {
            Log.i(
                TAG,
                "CLOUD_VALIDATE_SKIPPED rev=V3_5_OFFLINE_FAST_FAIL " +
                    "network=${networkDiagnosticHint()} hasKey=${groqProvider.hasApiKey()} status=${groqProvider.status()}"
            )
            false
        } else {
            groqProvider.validateModel()
        }

    /** Current pair-aware history budget used to seed/reseed local Gemma. */
    fun currentHistoryTokenBudget(): Int = historyTokenBudget

    fun groqProviderStatus(): GroqProviderStatus = groqProvider.status()
    fun isGroqKeyConfigured(): Boolean = groqProvider.hasApiKey()
    /** "Groq · Qwen3.8 27B", "Meta · Muse Spark" or "none". */
    fun onlineEngineLabel(): String = groqProvider.engineLabel


    /**
     * V3.8 selected-KB applicability policy.
     *
     * Local remains the complete offline path. Cloud is source-bounded and is
     * invoked when Local fails technically, cannot admit a MEDIUM/HIGH record,
     * or proposes a transition away from an already-active SOP.
     * A transient network/rate-limit state never permanently disables later
     * cloud attempts; only missing/failed authentication does.
     */
    suspend fun selectKnowledgeRecords(
        notebookContext: String,
        currentUser: String,
        candidates: List<ANMDatabaseHelper.KbRecordCandidate>,
        activeRecordIds: List<Int>
    ): KbRecordSelection? {
        Log.i(
            TAG,
            "KB_SELECTOR_POLICY_REV V3_7_EVIDENCE_TRANSITION " +
                "candidates=${candidates.size} active=${activeRecordIds.joinToString(",")}"
        )

        val local = try {
            // The selector itself uses AndroLLM's cancellable ephemeral generation.
            // Do not wrap it in a shorter whole-answer timeout: a healthy slow decode
            // must not be mistaken for KB inapplicability or force Cloud prematurely.
            localModelManager?.selectKbRecords(
                notebookContext, currentUser, candidates, activeRecordIds
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "KB_SELECTOR_LOCAL_FAILED rev=V4_3_EVIDENCE_BOUND " +
                    "${t.javaClass.simpleName}:${t.message}"
            )
            null
        }

        val activeSet = activeRecordIds.filter { it > 0 }.toSet()
        val localConfidence = local?.confidence?.trim()?.uppercase().orEmpty()
        // Phase 9: initial SOP admission is strict. MEDIUM may preserve an already
        // active source, but it must not create a new active SOP from scratch.
        val localAccepted = local != null &&
            local.selectedRecordIds.isNotEmpty() &&
            if (activeSet.isEmpty()) localConfidence == "HIGH"
            else (localConfidence == "HIGH" || localConfidence == "MEDIUM")
        val localChangesActiveRecord = localAccepted &&
            activeSet.isNotEmpty() &&
            local!!.selectedRecordIds.any { it !in activeSet }

        val cloudUsable = canAttemptCloud()

        // True Local-first selected-KB admission: initial admission requires HIGH
        // evidence; MEDIUM is allowed only for continuity of an already-active SOP.
        // Cloud remains the
        // technical fallback when Local is unresolved, and may still review a later
        // proposed transition away from an already-active SOP. This method is invoked
        // only by the user-selected KB workflow.
        if (localAccepted && !localChangesActiveRecord) {
            Log.i(
                TAG,
                "KB_SELECTOR_LOCAL_ACCEPT rev=V3_8_LOCAL_FIRST_ADMISSION " +
                    "selected=${local!!.selectedRecordIds.joinToString(",")} " +
                    "confidence=$localConfidence transition=false " +
                    "caseEvidence=${local.caseFactEvidence.size}"
            )
            return local
        }

        if (localChangesActiveRecord) {
            Log.i(
                TAG,
                "KB_SELECTOR_LOCAL_TRANSITION_REVIEW rev=V3_7_EVIDENCE_TRANSITION " +
                    "from=${activeSet.joinToString(",")} " +
                    "to=${local!!.selectedRecordIds.joinToString(",")} " +
                    "cloudReview=$cloudUsable caseEvidence=${local.caseFactEvidence.size}"
            )
            if (!cloudUsable) {
                // Offline remains functional but fail-closed for an unconfirmed
                // transition between source records. The current active record is
                // not silently replaced on a single small-model decision.
                return KbRecordSelection(
                    selectedRecordIds = emptyList(),
                    confidence = "LOW",
                    clarificationQuestion =
                        "The new information may change which SOP applies. What additional established case fact confirms the change?",
                    decisions = local.decisions,
                    caseFactEvidence = local.caseFactEvidence
                )
            }
        } else {
            val reason = when {
                local == null -> "technical_failure"
                local.selectedRecordIds.isEmpty() -> "no_confident_record"
                else -> "low_confidence_or_unusable"
            }
            Log.w(
                TAG,
                "KB_SELECTOR_LOCAL_UNRESOLVED rev=V3_7_EVIDENCE_TRANSITION " +
                    "reason=$reason cloudFallback=$cloudUsable " +
                    "localConfidence=${localConfidence.ifBlank { "NONE" }} " +
                    "localSelected=${local?.selectedRecordIds?.joinToString(",").orEmpty()} " +
                    "candidates=${candidates.size}"
            )
            if (!cloudUsable) return local
        }

        val cloud = try {
            withTimeout(KB_SELECTOR_CLOUD_TIMEOUT_MS) {
                groqProvider.selectGroundedKbRecords(
                    notebookContext, currentUser, candidates, activeRecordIds
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(
                TAG,
                "KB_CLOUD_SELECTOR_FAILED rev=V3_7_EVIDENCE_TRANSITION " +
                    "${t.javaClass.simpleName}:${t.message}"
            )
            null
        }

        if (cloud != null) {
            Log.i(
                TAG,
                "KB_SELECTOR_CLOUD_RESULT rev=V3_7_EVIDENCE_TRANSITION " +
                    "selected=${cloud.selectedRecordIds.joinToString(",")} " +
                    "confidence=${cloud.confidence} caseEvidence=${cloud.caseFactEvidence.size}"
            )
            return cloud
        }

        // A failed Cloud review must never convert a proposed record transition
        // into an unreviewed one. Initial evidence-bound Local admissions have
        // already returned above and are never sent to Cloud merely for veto/review.
        if (localChangesActiveRecord) {
            Log.w(
                TAG,
                "KB_SELECTOR_TRANSITION_FAIL_CLOSED rev=V3_7_EVIDENCE_TRANSITION " +
                    "from=${activeSet.joinToString(",")} status=${groqProvider.status()}"
            )
            return KbRecordSelection(
                selectedRecordIds = emptyList(),
                confidence = "LOW",
                clarificationQuestion =
                    "I need one more established case fact before changing the active SOP. What additional fact is known?",
                decisions = local?.decisions.orEmpty(),
                caseFactEvidence = local?.caseFactEvidence.orEmpty()
            )
        }

        Log.w(
            TAG,
            "KB_SELECTOR_CLOUD_UNAVAILABLE rev=V3_7_EVIDENCE_TRANSITION " +
                "offlineLocalAvailable=${local != null} status=${groqProvider.status()}"
        )
        return local
    }

    fun healthSnapshot(): Phase1RuntimeHealthSnapshot {
        runtimeHealth.setLocalReady(localModelManager?.isReady == true && localModelManager?.isTextWarm == true)
        runtimeHealth.observeCloudStatus(groqProvider.status())
        return runtimeHealth.snapshot()
    }

    suspend fun startNewChat(clearHistory: Boolean = true, mode: String = "NORMAL"): String = withContext(Dispatchers.IO) {
        turnMutex.withLock {
            localModelManager?.closeSession()
            val id = if (clearHistory) chatStore.startNewConversation(mode) else chatStore.currentConversationId()
            // KB follow-up memory belongs to one conversation.
            if (clearHistory) pocketRagSessions.clearAll()
            lastAcceptedProviderForRouting = Provider.NONE
            lastAcceptedUserForRouting = ""
            lastAcceptedAssistantForRouting = ""
            lastAcceptedExternalIntentForRouting = false
            currentTurnExternalIntentForRouting = false
            troubleshootingSessionState.clear()
            id
        }
    }

    suspend fun openConversation(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        turnMutex.withLock {
            localModelManager?.closeSession()
            val opened = chatStore.activateConversation(conversationId)
            if (opened) {
                // Re-seeded from the opened conversation's saved turns on its next KB question.
                pocketRagSessions.clearAll()
                lastAcceptedProviderForRouting = Provider.NONE
                lastAcceptedUserForRouting = ""
                lastAcceptedAssistantForRouting = ""
                lastAcceptedExternalIntentForRouting = false
                currentTurnExternalIntentForRouting = false
                troubleshootingSessionState.clear()
            }
            opened
        }
    }

    fun currentConversationId(): String = chatStore.currentConversationId()

    fun conversationSessions(limit: Int = 5): List<AndroConversationSession> =
        chatStore.listConversations(limit)

    fun currentConversationMessages(limit: Int = 300): List<AndroChatMessage> =
        chatStore.allCurrentMessages(limit)

    /** The open conversation as the chat screen shows it (sources, provider, display-only replies). */
    fun currentConversationDisplayMessages(limit: Int = 300): List<AndroDisplayMessage> =
        runCatching { chatStore.displayMessages(limit) }.getOrDefault(emptyList())

    /**
     * Saves what the chat screen shows for a finished turn, so its sources, badge, Check online and
     * "not in this KB" card (and replies that are not model memory) are back after the app is closed,
     * sent to the background or reopened.
     */
    fun saveTurnForDisplay(query: String, result: TurnResult, turnStartedMs: Long) {
        val knowledgeUsed = result.sourceIds.any {
            it.startsWith("kbref:") || it.startsWith(KnowledgeSourcePresentation.NOT_FOUND_PREFIX) ||
                it.startsWith(KnowledgeSourcePresentation.WEAK_PREFIX) || it.startsWith("kbnomatch:")
        }
        runCatching {
            chatStore.saveTurnForDisplay(query, result.text, result.provider.name, result.sourceIds, knowledgeUsed, turnStartedMs)
        }.onFailure { Log.w(TAG, "TURN_DISPLAY_SAVE_FAILED ${it.javaClass.simpleName}:${it.message}") }
    }

    fun recordInterruptedTurn(user: String, partialAssistant: String) {
        chatStore.recordInterruptedTurn(user, partialAssistant)
    }

    fun clearInterruptedTurnContext() {
        chatStore.clearInterruptedTurn()
    }

    suspend fun ensureConversationOpen() = withContext(Dispatchers.IO) {
        val lm = localModelManager ?: return@withContext
        if (!lm.isReady) return@withContext
        val history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        lm.ensureConversationForHistory(history)
    }

    suspend fun resetNativeConversationForRecovery() = withContext(Dispatchers.IO) {
        val lm = localModelManager ?: return@withContext
        lm.closeSession()
        val history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        lm.ensureConversationForHistory(history)
    }

    /**
     * Voice-only transcript resolution before the conversational turn enters resident
     * KV. Uses a detached local generation and recent accepted history; failures are
     * fail-open (KEEP original transcript). No KB/E5 or Cloud participates.
     */
    suspend fun resolveVoiceTranscript(rawTranscript: String): VoiceTranscriptResolution =
        withContext(Dispatchers.IO) {
            val raw = rawTranscript.trim()
            val lm = localModelManager
            if (raw.isBlank() || lm == null || !lm.isReady || lm.permanentlyDisabled || !lm.isTextWarm) {
                return@withContext VoiceTranscriptResolution(
                    action = "keep", text = raw, confidence = 1.0
                )
            }
            try {
                withTimeout(2_000L) {
                    lm.resolveVoiceTranscript(
                        rawTranscript = raw,
                        history = chatStore.recentWithinTokenBudget(historyTokenBudget, 24)
                    )
                }
            } catch (te: TimeoutCancellationException) {
                Log.w(TAG, "STT_CONTEXT_RESOLVER_TIMEOUT")
                VoiceTranscriptResolution("keep", raw, "", 0.0)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "STT_CONTEXT_RESOLVER_FAILED ${t.javaClass.simpleName}: ${t.message}")
                VoiceTranscriptResolution("keep", raw, "", 0.0)
            }
        }

    private fun buildProviderNeutralTurnContext(
        query: String,
        troubleshooting: Boolean,
        kbSelected: Boolean,
        visionActive: Boolean,
        knowledgeEvidence: String = "",
        attachmentEvidence: String = "",
        currentVisualEvidence: String = ""
    ): TurnContextSnapshot {
        val recent = chatStore.recent(8).takeLast(4).joinToString("\n") { message ->
            "${message.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: ${message.content.take(320)}"
        }
        return TurnContextSnapshot(
            userQuery = query,
            troubleshooting = troubleshooting,
            kbSelected = kbSelected,
            visionActive = visionActive,
            notebookContext = notebookObserver?.renderTurnContext(maxChars = 2_800, recentTurns = 0).orEmpty(),
            recentConversation = recent.take(1_800),
            knowledgeEvidence = knowledgeEvidence.take(4_000),
            attachmentEvidence = attachmentEvidence.take(2_500),
            currentVisualEvidence = currentVisualEvidence.take(1_800),
            networkAvailable = canAttemptCloud()
        )
    }

    private fun markCloudEpisode(reason: EscalationReason, user: String, assistant: String) {
        val lastQuestion = assistant.lineSequence()
            .flatMap { line -> line.split(Regex("(?<=[?.!])\\s+")) .asSequence() }
            .map { it.trim() }
            .lastOrNull { it.endsWith("?") }
            .orEmpty()
            .take(500)
        activeEscalationEpisode = EscalationEpisode(
            reason = reason,
            startedAtTurnEpochMs = System.currentTimeMillis(),
            active = true,
            dependentTurnsUsed = 0,
            maxDependentTurns = 8,
            episodeGoal = user.trim().take(700),
            lastCloudQuestion = lastQuestion,
            dependencyUnknownTurns = 0
        )
        Log.i(TAG, "ESCALATION_EPISODE_START reason=$reason maxDependentTurns=8 goalChars=${user.trim().length.coerceAtMost(700)}")
    }

    private fun noteCloudEpisodeContinuation() {
        val episode = activeEscalationEpisode ?: return
        val updated = episode.copy(dependentTurnsUsed = episode.dependentTurnsUsed + 1)
        activeEscalationEpisode = updated
        Log.i(
            TAG,
            "ESCALATION_EPISODE_CONTINUE reason=${updated.reason} used=${updated.dependentTurnsUsed}/${updated.maxDependentTurns}"
        )
    }

    private fun clearCloudEpisode(reason: String) {
        if (activeEscalationEpisode != null) {
            Log.i(TAG, "ESCALATION_EPISODE_END reason=$reason previous=${activeEscalationEpisode?.reason}")
            activeEscalationEpisode = null
        }
    }

    private fun resolvePocketRagSelection(
        genericKnowledgeProvider: GenericKnowledgeContextProvider?
    ): PocketRagSelection? {
        if (genericKnowledgeProvider?.isSelected() == true) {
            val identity = genericKnowledgeProvider.selectedPackIdentity()
            return PocketRagSelection(
                kind = "generic",
                id = identity.first.ifBlank { "generic" },
                displayName = identity.second.ifBlank { "Selected Knowledge Base" }
            )
        }
        if (selectedKnowledgeProvider?.isSelected() == true) {
            return PocketRagSelection(
                kind = "anm_sop",
                id = "anm_sop",
                displayName = "Selected Knowledge Base"
            )
        }
        return null
    }

    /**
     * KB questions and answers are saved in the conversation like every other turn, so the
     * conversation list and a reopened or restored conversation show them.
     */
    private fun saveKbTurn(query: String, answer: String) {
        runCatching {
            chatStore.append("user", query)
            chatStore.append("assistant", answer)
        }.onFailure { Log.w(TAG, "KB_HISTORY_SAVE_FAILED ${it.message}") }
    }

    private fun seedKbSessionFromSavedTurns(sessionKey: String) {
        if (pocketRagSessions.context(sessionKey).isNotEmpty()) return
        val saved = runCatching { chatStore.recent(24) }.getOrDefault(emptyList())
        val turns = ArrayList<Pair<String, String>>()
        var i = 0
        while (i + 1 < saved.size) {
            if (saved[i].role == "user" && saved[i + 1].role == "assistant") {
                turns += saved[i].content to saved[i + 1].content
                i += 2
            } else i++
        }
        if (pocketRagSessions.seedIfEmpty(sessionKey, turns.takeLast(KB_SEED_TURNS))) {
            Log.i(TAG, "KB_HISTORY_RESTORED kb=$sessionKey turns=${turns.takeLast(KB_SEED_TURNS).size}")
        }
    }

    private suspend fun handlePocketRagTurn(
        query: String,
        started: Long,
        selectedAtTurnStart: PocketRagSelection,
        attachments: List<BaselineContextAttachment>,
        turnSourceIds: List<String>,
        genericKnowledgeProvider: GenericKnowledgeContextProvider?,
        /** "Check online" pressed on a local KB answer: Cloud verifies that answer with the same evidence. */
        checkOnline: Boolean = false,
        /** The local answer as it is written (shown at once; replaced by the checked final answer). */
        onAnswerDelta: ((String) -> Unit)? = null,
        /** Clears the shown preview when that draft is not accepted as it is. */
        onAnswerAbort: (() -> Unit)? = null
    ): TurnResult {
        currentTurnKnowledgeSelectedForRouting = true
        val genericLookupState = genericKnowledgeProvider?.lastLookupState()
        // No hard block here: an embedding-space mismatch only disables dense retrieval inside the
        // KB provider (full-text retrieval continues), so the selected KB keeps producing cited answers.
        val selection = selectedAtTurnStart.let { initial ->
            if (initial.kind == "generic" && !genericLookupState?.packId.isNullOrBlank()) {
                initial.copy(
                    id = genericLookupState!!.packId,
                    displayName = genericLookupState.displayName.ifBlank { initial.displayName }
                )
            } else initial
        }

        pocketRagSessions.clearAllExcept(selection.sessionKey)
        // "Check online" with internet: search again with an online-sized budget, so Muse Spark
        // checks the phone's answer against more of the knowledge base's related passages.
        val onlineAttachment = if (checkOnline && selection.kind == "generic" && canAttemptCloud()) runCatching {
            val history = pocketRagSessions.context(selection.sessionKey).flatMapIndexed { i, t ->
                listOf(AndroChatMessage(i * 2L, "user", t.user, 0L), AndroChatMessage(i * 2L + 1, "assistant", t.assistant, 0L))
            }
            genericKnowledgeProvider?.contextForOnline(query, history, CHECK_ONLINE_CONTEXT_TOKENS)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() else null
        val useOnline = onlineAttachment != null && onlineAttachment.text.isNotBlank()
        val turnAttachments = if (useOnline) attachments.filterNot {
            it.attributes["knowledge_pack"] == "generic" && it.attributes["grounding_required"] == "true"
        } + onlineAttachment!! else attachments
        val assembler = when {
            useOnline -> PocketRagEvidenceAssembler { CHECK_ONLINE_CONTEXT_TOKENS }
            selection.kind == "generic" -> genericKbEvidenceAssembler
            else -> pocketRagEvidenceAssembler
        }
        if (useOnline) Log.i(TAG, "CHECK_ONLINE_WIDE_EVIDENCE chars=${onlineAttachment!!.text.length}")
        val assembled = assembler.assemble(
            query = query,
            selected = selection,
            attachments = turnAttachments.map { attachment ->
                PocketRagAttachmentRecord(attachment.sourceId, attachment.text, attachment.attributes)
            }
        )
        val packet = assembled
        // Current/live questions the KB has nothing solid on go to a live web search (labelled).
        val liveQuestion = FreshnessTemporalGuard.requiresFreshVerification(query)
        val kbEvidenceWeak = packet == null || packet.bestRelevance == null ||
            packet.bestRelevance < PocketRagPromptBuilder.WEAK_EVIDENCE_RELEVANCE
        val earlierAnswerWasNotFound = checkOnline && pocketRagSessions.context(selection.sessionKey)
            .none { it.user.trim().equals(query.trim(), ignoreCase = true) }
        if ((liveQuestion && kbEvidenceWeak) || (checkOnline && earlierAnswerWasNotFound && kbEvidenceWeak)) {
            kbLiveWebAnswer(query, started, if (liveQuestion) "live_question_weak_kb" else "check_online_not_in_kb")
                ?.let { return it }
        }
        // Offline first: a question the knowledge base clearly does not cover ("what is a bullet
        // train" with the ASHA guide open) gets the phone's general answer straight away, labelled,
        // instead of a long knowledge-base attempt that ends in "not found". Weaker matches still
        // go through the knowledge base first (broad questions often match at -1 to 1).
        val clearlyOutside = packet == null || (packet.bestRelevance != null && packet.bestRelevance < PocketRagPromptBuilder.VERY_WEAK_EVIDENCE_RELEVANCE)
        if (clearlyOutside && !checkOnline) {
            Log.i(TAG, "KB_OUT_OF_SCOPE_GENERAL best=${packet?.bestRelevance} kb=${selection.sessionKey}")
            kbGeneralAnswer(query, started, packet?.sources?.map { KnowledgeSourcePresentation.topicLabel(it.display) }.orEmpty(), null, selection.displayName, selection.sessionKey)
                ?.let { return it }
        }
        if (packet == null) {
            val answer = "I couldn't find enough relevant information in the ${selection.displayName.ifBlank { "selected knowledge base" }} to answer that reliably."
            Log.w(TAG, "POCKET_RAG_NO_EVIDENCE kb=${selection.sessionKey}")
            return TurnResult(
                text = answer,
                provider = Provider.LOCAL,
                elapsedMs = System.currentTimeMillis() - started,
                streamedLocally = false,
                sourceIds = listOf(KnowledgeSourcePresentation.topicsMarker(KnowledgeSourcePresentation.NOT_FOUND_PREFIX, emptyList()))
            )
        }

        val kbEngine = PocketRagEngine(
            local = PocketRagLocalGenerator(localModelManager, onAnswerDelta),
            cloud = PocketRagCloudResolver(groqProvider),
            qualityGate = PocketRagQualityGate(),
            sessions = pocketRagSessions,
            canUseCloud = { canAttemptCloud() },
            contextWindowTokens = { if (selection.kind == "generic") kbContextWindowTokens else effectiveContextWindowTokens },
            localTimeoutMs = engineScaledTimeout(if (selection.kind == "generic") kbScaledTimeout(GENERIC_KB_LOCAL_TIMEOUT_MS) else LOCAL_TURN_TIMEOUT_MS),
            localRecoveryTimeoutMs = engineScaledTimeout(if (selection.kind == "generic") kbScaledTimeout(GENERIC_KB_RETRY_TIMEOUT_MS) else KB_GROUNDED_RETRY_TIMEOUT_MS),
            // Generic KB: the engine watchdog (stall detection) bounds generation; a fixed caller
            // deadline discarded healthy long answers (blank "could not answer" after 52 s).
            callerTimeoutEnabled = selection.kind != "generic",
            onDraftRejected = onAnswerAbort,
            onlineBackupEnabled = { answerPolicyStore.onlineBackup() }
        )
        // Cloud takes part only by the user's choice: the Cloud First setting, or "Check online".
        val cloudMode = when {
            checkOnline -> PocketRagEngine.CloudMode.VERIFY
            answerPolicyStore.get() == AnswerPolicy.CLOUD_FIRST -> PocketRagEngine.CloudMode.CLOUD_FIRST
            else -> PocketRagEngine.CloudMode.NONE
        }
        val draftToVerify = if (checkOnline) pocketRagSessions.context(selection.sessionKey)
            .lastOrNull { it.user.trim().equals(query.trim(), ignoreCase = true) }?.assistant else null
        Log.i(TAG, "POCKET_RAG_CLOUD_MODE mode=$cloudMode policy=${answerPolicyStore.get()} cloudReachable=${canAttemptCloud()} draft=${draftToVerify?.length ?: 0}")
        val kbResult = proveKbAnswer(
            kbEngine.answer(packet, cloudMode, draftToVerify), packet, selection, genericKnowledgeProvider,
            rewrittenQuestion = attachments.firstNotNullOfOrNull { it.attributes["kb_search_rewrite"]?.takeIf { r -> r.isNotBlank() } }.orEmpty()
        )
        if (selection.kind == "generic") genericKnowledgeProvider?.commitPendingEvidenceWindow(kbResult.sourceIds.isNotEmpty())
        if (selection.kind == "anm_sop") {
            runCatching { selectedKnowledgeProvider?.onAcceptedTurn(query, kbResult.text, kbResult.provider) }
                .onFailure { Log.w(TAG, "POCKET_RAG_PROVIDER_COMMIT_FAILED ${it.message}") }
        }
        runtimeHealth.turnAccepted(kbResult.provider, System.currentTimeMillis() - started, groqProvider.status())
        // Only an accepted answer is saved (history, restored follow-up memory); the app's
        // failure message after a broken or unsupported generation is shown but never saved.
        if (kbResult.accepted) saveKbTurn(query, kbResult.text)
        Log.i(
            TAG,
            "POCKET_RAG_TURN_ACCEPT kb=${selection.sessionKey} provider=${kbResult.provider} " +
                "local=${kbResult.localAttempted} cloud=${kbResult.cloudAttempted} " +
                "reason=${kbResult.escalationReason.ifBlank { "none" }} sources=${kbResult.sourceIds.size} " +
                "elapsed=${System.currentTimeMillis() - started}ms"
        )
        // "Not in this KB" / weak match: a visible card with the closest topics the KB does have.
        val topics = packet.sources.map { KnowledgeSourcePresentation.topicLabel(it.display) }
        val notFound = !kbResult.accepted && kbResult.escalationReason.startsWith("local_declared_insufficient")
        // A low re-ranker score alone is not "weak" when every term of the question is in the evidence
        // (a comparison "difference between AoN and EAS": no passage covers both, each is covered).
        val weak = kbResult.accepted && packet.bestRelevance != null && packet.bestRelevance < PocketRagPromptBuilder.WEAK_EVIDENCE_RELEVANCE &&
            !AnswerProof.questionTermsCovered(query, packet.evidenceText)
        val coverageMarker = when {
            notFound -> listOf(KnowledgeSourcePresentation.topicsMarker(KnowledgeSourcePresentation.NOT_FOUND_PREFIX, topics))
            weak -> listOf(KnowledgeSourcePresentation.topicsMarker(KnowledgeSourcePresentation.WEAK_PREFIX, topics)) +
                (if (packet.bestRelevance != null && packet.bestRelevance < PocketRagPromptBuilder.VERY_WEAK_EVIDENCE_RELEVANCE)
                    listOf(KnowledgeSourcePresentation.VERY_WEAK) else emptyList())
            else -> emptyList()
        }
        // A local KB answer (or a not-found reply) offers "Check online" (Local First, cloud configured).
        // Shown even when Cloud is not set up or offline: pressing it then says why it cannot check.
        val verifyMarker = if (kbResult.provider == Provider.LOCAL && (kbResult.accepted || notFound))
            listOf("verification:optional") else emptyList()
        // The KB has no answer to a current/live question: a live web search instead of a dead end.
        // "Check online" and the KB evidence does not answer it: a live web search (never the online
        // AI's own memory, which guessed "Accord of Need" for AoN).
        if (kbResult.escalationReason == PocketRagEngine.CHECK_ONLINE_NOT_IN_KB) {
            kbLiveWebAnswer(query, started, "check_online_not_in_evidence")?.let { return it }
            return TurnResult(
                text = "The selected knowledge base does not answer this, and the web search could not be reached just now. Please try again in a moment.",
                provider = Provider.NONE,
                elapsedMs = System.currentTimeMillis() - started,
                streamedLocally = false,
                sourceIds = listOf("freshness_required:web_search_unavailable")
            )
        }
        // Not in this KB: does another installed KB cover it? (each switch is tens of ms)
        val otherPackNote = if (notFound && selection.kind == "generic" && !checkOnline) {
            runCatching { genericKnowledgeProvider?.bestOtherPackFor(query, packet.bestRelevance) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.let { otherPackNote(query, selection.displayName, it) }
        } else null
        if (notFound && (liveQuestion || cloudMode == PocketRagEngine.CloudMode.CLOUD_FIRST || checkOnline)) {
            kbLiveWebAnswer(query, started, "kb_not_found_${cloudMode.name.lowercase()}")?.let { return it }
        }
        // Outside the knowledge base ("how does a rocket engine work" with a procurement KB): a general
        // answer from the phone's model, clearly labelled as not from the knowledge base, instead of
        // a list of unrelated topics. The "not found" card and Check online stay.
        if (notFound && !checkOnline) {
            kbGeneralAnswer(query, started, topics, otherPackNote, selection.displayName, selection.sessionKey)?.let { return it }
        }
        // Tap a source to read the passages behind it: each cited passage with its best-matching sentence.
        val passageMarkers = kbResult.sourceIds.mapNotNull { ref ->
            val id = Regex("^kbref:\\s*(S\\d+)", RegexOption.IGNORE_CASE).find(ref)?.groupValues?.get(1)?.uppercase() ?: return@mapNotNull null
            val block = packet.evidenceBlocks.firstOrNull { it.sourceId.equals(id, ignoreCase = true) } ?: return@mapNotNull null
            val text = block.text.lines().map { it.trim() }
                .filterNot { it.startsWith("Source ID:") || it.startsWith("Source:") || it.startsWith("Note:") }
                .joinToString(" ") { it.removePrefix("\u2022").trim() }
            text.takeIf { it.isNotBlank() }?.let { KnowledgeSourcePresentation.passageMarker(id, it, kbResult.text) }
        }.distinct()
        // "The document says": the cited passages' own sentence shown before the model's explanation
        // (not for the app's own extract, which already is the documents' sentences).
        val quoteMarker = if (kbResult.accepted && selection.kind == "generic" &&
            !kbResult.text.startsWith("Here is what the selected knowledge base says") && !kbResult.text.startsWith("चुने गए ज्ञान आधार में यह लिखा है")
        ) runCatching {
            val citedIds = kbResult.sourceIds.mapNotNull { Regex("^kbref:\\s*(S\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase() }.distinct()
            val blocks = citedIds.mapNotNull { id -> packet.evidenceBlocks.firstOrNull { it.sourceId.equals(id, ignoreCase = true) } }
            val passages = blocks.map { b ->
                val label = packet.sources.firstOrNull { it.id.equals(b.sourceId, ignoreCase = true) }?.display?.let { KnowledgeSourcePresentation.topicLabel(it) }.orEmpty()
                label to b.text.lines().map { it.trim() }.filterNot { it.startsWith("Source ID:") || it.startsWith("Source:") || it.startsWith("Note:") }
                    .joinToString(" ") { it.removePrefix("\u2022").trim() }
            }
            val rewrite = attachments.firstNotNullOfOrNull { it.attributes["kb_search_rewrite"]?.takeIf { r -> r.isNotBlank() } }.orEmpty()
            KnowledgeSourcePresentation.chooseQuote(query, rewrite, kbResult.text, passages)?.let { (quote, source) ->
                KnowledgeSourcePresentation.quoteMarker(quote, source, KnowledgeSourcePresentation.quoteDisagrees(quote, kbResult.text))
            }
        }.getOrNull() else null
        return TurnResult(
            text = if (otherPackNote != null) otherPackNote + "\n\n" + kbResult.text else kbResult.text,
            provider = kbResult.provider,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = kbResult.sourceIds + passageMarkers + listOfNotNull(quoteMarker) + coverageMarker + verifyMarker
        )
    }

    /**
     * Pramaan's proof check on an accepted knowledge-base answer: every statement is matched to a
     * cited passage (re-ranker + numbers); statements found nowhere are removed when the rest stands,
     * and the result carries "proof:<found>/<total>" for the Sources box.
     */
    private suspend fun proveKbAnswer(
        result: PocketRagResult,
        packet: PocketRagPacket,
        selection: PocketRagSelection,
        genericKnowledgeProvider: GenericKnowledgeContextProvider?,
        /** The search question as the answer model rewrote it (English), when it did. */
        rewrittenQuestion: String = ""
    ): PocketRagResult {
        if (!result.accepted || selection.kind != "generic" || packet.evidenceBlocks.isEmpty()) return result
        val started = System.currentTimeMillis()
        val cited = result.sourceIds.mapNotNull { Regex("^kbref:\\s*(S\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase() }.toSet()
        // An answer that cites nothing is checked against every passage it was given; it then shows the
        // passages that support it (and is flagged when none does) instead of being shown without sources.
        val blocks = if (cited.isEmpty()) packet.evidenceBlocks else packet.evidenceBlocks.filter { it.sourceId.uppercase() in cited }.ifEmpty { packet.evidenceBlocks }
        val passages = blocks.map { it.sourceId to it.text }
        val proof = runCatching {
            withTimeoutOrNull(PROOF_TIMEOUT_MS) {
                AnswerProof.check(result.text, passages, genericKnowledgeProvider?.proofScorer(), packet.sources.map { it.display })
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return result
        if (proof.total == 0) return result
        val pruned = AnswerProof.prune(result.text, proof)
        val final = pruned?.second ?: proof
        var sourceIds = result.sourceIds
        if (cited.isEmpty()) {
            val supporting = final.statements.filter { it.supported }.mapNotNull { it.passageId?.uppercase() }.toSet()
            sourceIds = sourceIds + packet.sources.filter { it.id.uppercase() in supporting }.map { "kbref:${it.id} :: ${it.display}" }
        }
        // Did the passages talk about what was asked? (stroke signs for "danger signs in pregnancy")
        val citedTexts = blocks.filter { b -> cited.isEmpty() || b.sourceId.uppercase() in cited }.map { it.text }
        // The user's own words and the model's English rewrite must both show the gap: a word only the
        // rewrite added ("recommended") or a Hinglish word ("bacche") alone never raises the warning.
        var topicTexts = citedTexts
        if (AnswerProof.missingTopicWords(packet.query, citedTexts).isNotEmpty()) {
            // Section headings and the document title count too (a foot-care passage under "Diabetes").
            val citedIds = if (cited.isEmpty()) blocks.map { it.sourceId.uppercase() }.toSet() else cited
            val around = runCatching { genericKnowledgeProvider?.citedContextText(citedIds) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull().orEmpty()
            if (around.isNotBlank()) topicTexts = citedTexts + around
        }
        // Hinglish words ("badhani", "chahiye") are never in English documents: for a Hinglish
        // question only the model's English rewrite is checked.
        val hinglish = CoreHealthFacts.isHinglish(packet.query)
        val literalMissing = if (hinglish) (if (rewrittenQuestion.isBlank()) emptyList() else AnswerProof.missingTopicWords(rewrittenQuestion, topicTexts))
            else AnswerProof.missingTopicWords(packet.query, topicTexts)
        val rewriteMissing = if (rewrittenQuestion.isBlank()) literalMissing else AnswerProof.missingTopicWords(rewrittenQuestion, topicTexts)
        val missing = if (literalMissing.isNotEmpty() && rewriteMissing.isNotEmpty())
            // Only words missing for both: a word the rewrite alone added ("duration", "available") never warns.
            rewriteMissing.filter { w -> literalMissing.contains(w) } else emptyList()
        val unsupported = final.supported == 0
        var text = pruned?.first ?: result.text
        if (missing.isNotEmpty()) {
            val words = missing.take(3).joinToString(", ") { "“$it”" }
            text = (if (KbReplyText.dominantScript(packet.query) == KbReplyText.Script.DEVANAGARI)
                "⚠ मिले हुए अंशों में $words का ज़िक्र नहीं है — यह ज्ञान आधार शायद आपके प्रश्न को कवर नहीं करता।\n\n"
            else "⚠ The passages found do not mention $words — this knowledge base may not cover your question.\n\n") + text
        }
        // The specific topic note above already warns; the general "not clearly found" banner is for no support at all.
        if (unsupported && missing.isEmpty() && KnowledgeSourcePresentation.VERY_WEAK !in sourceIds) sourceIds = sourceIds + KnowledgeSourcePresentation.VERY_WEAK
        Log.i(
            TAG,
            "KB_PROOF found=${proof.supported}/${proof.total} removed=${proof.total - final.total} after=${final.supported}/${final.total} " +
                "conflicts=${proof.statements.count { it.directionConflict }} uncited=${cited.isEmpty()} missingTopic=${missing.joinToString(",")} " +
                "scores=${proof.statements.joinToString(",") { "%.1f".format(Locale.US, it.score ?: -99f) }} ms=${System.currentTimeMillis() - started}"
        )
        return result.copy(text = text, sourceIds = sourceIds + AnswerProof.marker(final))
    }

    /** "Not in ASHA KB — your Procurement KB covers this (…, page 96)", in the user's script. */
    private fun otherPackNote(query: String, activeName: String, match: GenericKnowledgeContextProvider.OtherPackMatch): String {
        val active = activeName.ifBlank { "the selected knowledge base" }
        val where = match.source.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return if (KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI)
            "यह \"$active\" में नहीं है, लेकिन आपके दूसरे ज्ञान आधार \"${match.displayName}\" में इसका उत्तर है$where। " +
                "स्रोत सहित उत्तर के लिए Settings → Knowledge Base में \"${match.displayName}\" चुनें (नई बातचीत शुरू होगी)।"
        else "This is not in \"$active\", but your other knowledge base \"${match.displayName}\" covers it$where. " +
            "For a cited answer, choose \"${match.displayName}\" in Settings → Knowledge Base (this starts a new chat)."
    }

    private val kbCameraDeictic = Regex(
        "(?i)\\b(this|these|that one|it|here|label|packet|strip|bottle|tablet|syrup|picture|photo|camera|ye|yeh|ise|isko|iska|iske|dekho|dekhiye)\\b|यह|ये|इसे|इसका|इसको|देखो|देखिए"
    )

    /**
     * Meta Stack + selected KB + camera: "what is this?" gets the camera facts appended
     * ("[camera: label text: "ORS…"; it looks like an ORS packet]") so the KB search and Llama both
     * use them. Null when the turn does not refer to what the camera shows. Outside [chat] for the
     * JVM method-size limit.
     */
    private suspend fun kbCameraQuery(
        query: String,
        visionModeActive: Boolean,
        bitmap: Bitmap?,
        visualFrameProvider: (suspend () -> Bitmap?)?
    ): String? {
        if (!visionModeActive || !isMetaStackActive || localModelManager?.isReadyForVision == true) return null
        if (!kbCameraDeictic.containsMatchIn(query)) return null
        val frame = bitmap ?: runCatching { visualFrameProvider?.invoke() }.getOrNull() ?: return null
        val obs = runCatching { withTimeoutOrNull(20_000L) { metaVision.observe(query, frame) } }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "KB_CAMERA_META_OBSERVE_FAILED ${it.message}") }
            .getOrNull() ?: return null
        val parts = kbVisionFacts(obs)
        if (parts.isEmpty()) return null
        Log.i(TAG, "KB_CAMERA_META_QUERY parts=${parts.size}")
        return kbQueryWithFacts(query, "camera", parts)
    }

    private fun kbVisionFacts(obs: MetaVisionMath.Observation?): MutableList<String> = buildList {
        if (obs == null) return@buildList
        obs.memory?.let { add("the item is \"${it.label}\"") }
        obs.text.takeIf { it.isNotBlank() }?.let { add("label text: \"${kbFactText(it)}\"") }
        obs.scene.firstOrNull()?.takeIf { it.score >= 0.35f && it.label != "room or background" }?.let { add("it looks like: ${it.label}") }
    }.toMutableList()

    /** Sentence marks would split the question into extra "parts" (GenericKbQueryPlanner). */
    private fun kbFactText(text: String): String =
        text.replace(Regex("[\\n?!;।.]+"), " ").replace(Regex("\\s+"), " ").trim().take(160)

    /** Facts go inside the question ("what is this [camera: …]?") so it stays one question part. */
    private fun kbQueryWithFacts(query: String, tag: String, parts: List<String>): String {
        val q = query.trim()
        val end = q.takeLastWhile { it in "?.!।" }
        return "${q.dropLast(end.length).trimEnd()} [$tag: ${parts.joinToString(" / ")}]$end"
    }

    private val visitStore by lazy { VisitStore(java.io.File(context.filesDir, "visit_notes.jsonl")) }

    /**
     * Visit Note and the due list (outside [chat]: JVM method size). A visit note is read by rules
     * (numbers), the phone's model (names, symptoms, medicines, advice; numbers it adds that are not
     * in the words are dropped), checked (impossible values, danger signs, missing items), each
     * serious sign cited from the selected knowledge base when it covers it, and saved on this phone.
     */
    private suspend fun maybeHandleVisitWork(query: String, started: Long): TurnResult? {
        if (VisitStore.isDueRequest(query)) {
            val today = VisitNote.todayEpochDay()
            val text = VisitStore.render(visitStore.due(today), today, VisitNote.isHindi(query), saved = visitStore.count())
            chatStore.append("user", query)
            chatStore.append("assistant", text)
            Log.i(TAG, "VISIT_DUE_LIST saved=${visitStore.count()}")
            return TurnResult(text, Provider.LOCAL, System.currentTimeMillis() - started, false, sourceIds = listOf("due_list"))
        }
        if (!VisitNote.isRequest(query)) return null
        val narrative = VisitNote.narrativeOf(query)
        if (narrative.length < 8) {
            return TurnResult(VisitNote.howTo(VisitNote.isHindi(query)), Provider.LOCAL, System.currentTimeMillis() - started, false, sourceIds = listOf("visit_note"))
        }
        val modelOutput = runCatching {
            withTimeoutOrNull(engineScaledTimeout(VISIT_NOTE_MODEL_TIMEOUT_MS)) {
                localModelManager?.generateDetached(VisitNote.modelPrompt(narrative), VISIT_NOTE_MAX_TOKENS, "visit_note")
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        var result = VisitNote.build(narrative, modelOutput)
        // Serious signs get the selected knowledge base's own page when it covers them.
        val generic = contextProviders.filterIsInstance<GenericKnowledgeContextProvider>().firstOrNull()
        if (generic?.isSelected() == true && result.alerts.isNotEmpty()) {
            val cited = result.alerts.mapIndexed { i, alert ->
                if (i >= 3 || alert.level == VisitNote.Level.WATCH) alert else {
                    val c = runCatching { withTimeoutOrNull(8_000L) { generic.quickCitation(alert.kbQuery) } }
                        .onFailure { if (it is CancellationException) throw it }.getOrNull()
                    if (c != null) alert.copy(citation = c.first) else alert
                }
            }
            result = result.copy(alerts = cited)
        }
        val text = VisitNote.render(result)
        if (!query.contains("[self-test]")) visitStore.save(result)
        chatStore.append("user", query)
        chatStore.append("assistant", text)
        Log.i(
            TAG,
            "VISIT_NOTE_SAVED model=${result.usedModel} category=${result.record.category} alerts=${result.alerts.joinToString { it.code }} " +
                "issues=${result.issues.size} missing=${result.missing.size} elapsed=${System.currentTimeMillis() - started}ms"
        )
        return TurnResult(text, Provider.LOCAL, System.currentTimeMillis() - started, false, sourceIds = listOf("visit_note"))
    }

    /** Wired by the runtime graph: pauses the selected KB for one turn (Phase1FeatureBundle). */
    @Volatile var knowledgeTurnPause: ((Boolean) -> Unit)? = null

    /**
     * KB selected + a document attached: the question is about that document, so the KB steps
     * aside for this one turn (before, the document was silently dropped and the KB answered
     * alone). A photo keeps the KB: its vision facts join the KB question instead.
     */
    private suspend fun pauseKbForAttachedDocument(turnAttachments: List<Phase1UserAttachment>, query: String) {
        knowledgeTurnPause?.invoke(false)
        val documentAttached = turnAttachments.any { it.kind != Phase1AttachmentKind.IMAGE }
        // A follow-up on the document just discussed ("summarise it in Hindi", "what does page 3
        // say") stays with that document; a new question goes back to the selected KB.
        val documentFollowUp = !documentAttached && turnAttachments.isEmpty() && lastTurnKbPausedForDocument &&
            runCatching { semanticCapabilityProvider?.isContextDependentForRecall(query, chatStore.recent(6)) == true }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
        lastTurnKbPausedForDocument = false
        if (!documentAttached && !documentFollowUp) return
        val generic = contextProviders.filterIsInstance<GenericKnowledgeContextProvider>().firstOrNull()
        if (resolvePocketRagSelection(generic) == null) return
        knowledgeTurnPause?.invoke(true)
        lastTurnKbPausedForDocument = true
        Log.i(TAG, "KB_PAUSED_FOR_ATTACHED_DOCUMENT attachments=${turnAttachments.size} followUp=$documentFollowUp")
    }

    /** The previous turn was answered from an attached document while a KB stayed selected. */
    @Volatile private var lastTurnKbPausedForDocument = false

    /** The KB search question: camera facts, else attached-photo facts, joined to the user's words. */
    private suspend fun kbTurnQuery(
        query: String,
        visionModeActive: Boolean,
        bitmap: Bitmap?,
        visualFrameProvider: (suspend () -> Bitmap?)?,
        turnAttachments: List<Phase1UserAttachment>
    ): String = kbCameraQuery(query, visionModeActive, bitmap, visualFrameProvider)
        ?: kbAttachedPhotoQuery(query, turnAttachments) ?: kbAttachedDocumentQuery(query, turnAttachments) ?: query

    /**
     * KB selected + a document attached (PDF, Word, slides, text): the answer still comes from the
     * selected knowledge base, and the document's lines that match the question join the question
     * so the search looks for what the document is about ("is this bid valid?" + the bid's terms).
     */
    private fun kbAttachedDocumentQuery(query: String, turnAttachments: List<Phase1UserAttachment>): String? {
        val doc = turnAttachments.firstOrNull { it.kind != Phase1AttachmentKind.IMAGE && it.extractedText.isNotBlank() } ?: return null
        val excerpt = runCatching { doc.relevantText(query, 600) }.getOrDefault("").ifBlank { doc.extractedText.take(600) }
        val text = kbFactText(excerpt)
        if (text.length < 12) return null
        Log.i(TAG, "KB_DOCUMENT_QUERY kind=${doc.kind} chars=${text.length}")
        return kbQueryWithFacts(query, "document ${kbFactText(doc.displayName).take(60)}", listOf(text))
    }

    /** KB selected + a photo attached: what the phone sees/reads in the photo joins the KB question. */
    private suspend fun kbAttachedPhotoQuery(query: String, turnAttachments: List<Phase1UserAttachment>): String? {
        val photo = turnAttachments.firstOrNull { it.kind == Phase1AttachmentKind.IMAGE } ?: return null
        val bitmap = photo.visionBitmaps.firstOrNull { !it.isRecycled && it.width > 0 }
            ?: photo.decodeCloudRecoveryBitmaps(1).firstOrNull()
        val obs = if (isMetaStackActive && bitmap != null) {
            runCatching { withTimeoutOrNull(20_000L) { metaVision.observe(query, bitmap) } }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "KB_PHOTO_META_OBSERVE_FAILED ${it.message}") }
                .getOrNull()
        } else null
        val parts = kbVisionFacts(obs)
        if (parts.none { it.startsWith("label text") } && photo.extractedText.isNotBlank()) {
            parts += "label text: \"${kbFactText(photo.extractedText)}\""
        }
        if (parts.isEmpty()) return null
        Log.i(TAG, "KB_PHOTO_META_QUERY parts=${parts.size}")
        return kbQueryWithFacts(query, "photo", parts)
    }

    /**
     * Teach/forget an item for the camera (Meta Stack, DINOv2). Kept outside [chat] for the JVM
     * method-size limit. Returns null when the turn is not such a command.
     */
    private suspend fun maybeHandleVisualMemoryCommand(
        query: String,
        visionModeActive: Boolean,
        bitmap: Bitmap?,
        visualFrameProvider: (suspend () -> Bitmap?)?,
        started: Long
    ): TurnResult? {
        if (!isMetaStackActive) return null
        val hindi = KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI ||
            Regex("(?i)\\b(yaad|bhool|ise|isko)\\b").containsMatchIn(query)
        MetaVisionMath.parseForgetCommand(query)?.let { label ->
            if (label.lowercase() !in metaVision.rememberedLabels().map { it.lowercase() }) return null
            metaVision.forget(label)
            val answer = if (hindi) "ठीक है, मैंने \"$label\" को भुला दिया।" else "Done — I have forgotten \"$label\"."
            chatStore.append("user", query); chatStore.append("assistant", answer)
            return TurnResult(answer, Provider.LOCAL, System.currentTimeMillis() - started, false, sourceIds = listOf("visual_memory"))
        }
        val label = MetaVisionMath.parseTeachCommand(query) ?: return null
        if (!visionModeActive && bitmap == null) return null
        val frame = bitmap ?: runCatching { visualFrameProvider?.invoke() }.getOrNull()
        val answer = when {
            frame == null -> if (hindi) "कैमरा से फोटो नहीं मिली। वस्तु को कैमरे के सामने रखकर फिर से कहें।"
                else "I couldn't get a camera frame. Hold the item in front of the camera and say it again."
            !metaVision.has(MetaModelStore.Component.DINOV2) -> if (hindi) "याद रखने के लिए DINOv2 मॉडल (ONNX) इंस्टॉल करना होगा।"
                else "To remember items I need the DINOv2 model (ONNX) installed in Settings."
            metaVision.remember(label, frame) -> if (hindi) "याद रख लिया: \"$label\"। अगली बार कैमरे में दिखने पर मैं इसे पहचान लूँगा।"
                else "Saved. I'll recognise \"$label\" the next time it is in front of the camera. (Teach it 2–3 times from different angles for better recognition.)"
            else -> if (hindi) "इसे याद नहीं रख पाया। फिर से कोशिश करें।" else "I couldn't save that item. Please try again."
        }
        chatStore.append("user", query); chatStore.append("assistant", answer)
        Log.i(TAG, "VISUAL_MEMORY_COMMAND label='$label' frame=${frame != null}")
        return TurnResult(answer, Provider.LOCAL, System.currentTimeMillis() - started, false, sourceIds = listOf("live_camera", "visual_memory"))
    }

    /**
     * Troubleshooting-only case-closure gate.
     *
     * Kept outside [chat] so the main suspend state machine stays below the JVM method-size
     * ceiling. Returning null means normal routing must continue unchanged.
     */
    private suspend fun maybeHandleTroubleshootingResolution(
        query: String,
        visionModeActive: Boolean,
        anyKnowledgePackSelectedAtTurnStart: Boolean,
        acceptedHistoryForDecision: List<AndroChatMessage>,
        started: Long
    ): TurnResult? {
        val troubleshootingLifecycleActive = visionModeActive &&
            AndroVoiceController.state.value.troubleshootingMode &&
            !anyKnowledgePackSelectedAtTurnStart &&
            troubleshootingSessionState.hasAcceptedTurns()
        if (!troubleshootingLifecycleActive) return null

        val recentAssistant = acceptedHistoryForDecision
            .lastOrNull { it.role.equals("assistant", ignoreCase = true) }
            ?.content.orEmpty()
        val resolutionAssessment = runCatching {
            semanticCapabilityProvider?.assessTroubleshootingResolution(query, recentAssistant)
        }.getOrNull()
        if (resolutionAssessment?.resolved != true) return null

        val resolution = troubleshootingSessionState.acknowledgeResolved(query)
        val answer = "Great — I’ve marked the issue as resolved based on your confirmation. Would you like to continue troubleshooting or end this session?"
        chatStore.append("user", query)
        chatStore.append("assistant", answer)
        notifyAcceptedTurn(
            user = query,
            assistant = answer,
            provider = Provider.LOCAL,
            investigation = true
        )
        Log.i(
            TAG,
            "TROUBLESHOOTING_RESOLUTION_PROMPT local=true action=${resolution.resolutionAction.take(120)} " +
                "semantic=${resolutionAssessment.resolvedScore}/${resolutionAssessment.continueScore}"
        )
        return TurnResult(
            text = answer,
            provider = Provider.LOCAL,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = listOf("troubleshooting_resolution_confirmation"),
            troubleshootingResolutionPrompt = true,
            resolutionSummary = resolution.resolutionAction
        )
    }

    suspend fun chat(
        userText: String,
        bitmap: Bitmap? = null,
        forceExternalInformation: Boolean = false,
        visionModeActive: Boolean = false,
        visualContext: String = "",
        visualFrameProvider: (suspend () -> Bitmap?)? = null,
        turnAttachments: List<Phase1UserAttachment> = emptyList(),
        onLocalDelta: ((String) -> Unit)? = null,
        onLocalSpeechDelta: ((String) -> Unit)? = null,
        onLocalAbort: (() -> Unit)? = null
    ): TurnResult = withContext(Dispatchers.IO) {
        val query = userText.trim()
        if (query.isBlank()) return@withContext TurnResult("", Provider.NONE, 0L, false)

        turnMutex.withLock {
            currentTurnExternalIntentForRouting = false
            pauseKbForAttachedDocument(turnAttachments, query)
            val started = System.currentTimeMillis()
            runtimeHealth.setLocalReady(localModelManager?.isReady == true && localModelManager?.isTextWarm == true)
            runtimeHealth.observeCloudStatus(groqProvider.status())

            val genericKnowledgeProviderForTurn = contextProviders
                .filterIsInstance<GenericKnowledgeContextProvider>()
                .firstOrNull()
            val genericKnowledgeSelectedAtTurnStart = genericKnowledgeProviderForTurn?.isSelected() == true
            val selectedPocketKbAtTurnStart = resolvePocketRagSelection(genericKnowledgeProviderForTurn)
            val anyKnowledgePackSelectedAtTurnStart = selectedPocketKbAtTurnStart != null
            // After an app restart (Android may end the app in the background) or when an earlier
            // conversation is reopened, KB follow-up memory is rebuilt from the saved turns.
            selectedPocketKbAtTurnStart?.let { seedKbSessionFromSavedTurns(it.sessionKey) }
            val kbHistoryForRetrieval = selectedPocketKbAtTurnStart
                ?.let { pocketRagSessions.asAndroHistory(it.sessionKey) }
                .orEmpty()

            // A KB turn uses detached generation and must not mutate/roll the resident
            // normal-chat KV session. KB-OFF keeps the previous behavior unchanged.
            if (!visionModeActive && !anyKnowledgePackSelectedAtTurnStart) {
                maybeRollNativeConversationForTokenBudget()
            }
            val acceptedHistoryForDecision = if (anyKnowledgePackSelectedAtTurnStart) {
                kbHistoryForRetrieval
            } else {
                chatStore.recent(120)
            }

            // Troubleshooting resolution lifecycle is intentionally extracted from chat().
            // Apart from keeping this concern isolated, this prevents the suspend lambda backing
            // chat() from exceeding the JVM 64 KiB per-method bytecode limit.
            maybeHandleTroubleshootingResolution(
                query = query,
                visionModeActive = visionModeActive,
                anyKnowledgePackSelectedAtTurnStart = anyKnowledgePackSelectedAtTurnStart,
                acceptedHistoryForDecision = acceptedHistoryForDecision,
                started = started
            )?.let { return@withLock it }

            // "Remember this as ORS packet" / "ise ORS packet naam se yaad rakho": DINOv2 visual memory.
            maybeHandleVisualMemoryCommand(query, visionModeActive, bitmap, visualFrameProvider, started)
                ?.let { return@withLock it }

            // "Visit note: …" → a checked visit record; "which visits are due" → the due list.
            maybeHandleVisitWork(query, started)?.let { return@withLock it }

            // Narrow local calculator shortcut. This is additive only: existing routing is
            // untouched. Semantic intent must strongly identify a calculation AND the
            // deterministic parser must successfully consume a complete expression.
            // Camera, attachments and the legacy selected ANM/clinical KB retain ownership.
            if (!visionModeActive && turnAttachments.isEmpty() && !anyKnowledgePackSelectedAtTurnStart) {
                val mathIntent = runCatching { semanticCapabilityProvider?.classifyQueryIntent(query) }.getOrNull()
                val strongMathIntent = mathIntent != null &&
                    mathIntent.intent == SemanticQueryIntent.MATH_CALCULATION &&
                    mathIntent.score >= 0.76f &&
                    (mathIntent.score - mathIntent.runnerUpScore) >= 0.025f
                if (strongMathIntent) {
                    val solved = OnDeviceMathEngine.solve(query)
                    if (solved != null) {
                        localModelManager?.closeSession()
                        val answer = solved.answer
                        chatStore.append("user", query)
                        chatStore.append("assistant", answer)
                        notifyAcceptedTurn(query, answer, Provider.LOCAL)
                        Log.i(TAG, "LOCAL_CALCULATOR_ACCEPTED expression='${solved.canonicalExpression.take(160)}' result='${answer.take(120)}'")
                        return@withLock TurnResult(
                            text = answer,
                            provider = Provider.LOCAL,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = listOf("local_calculator")
                        )
                    } else {
                        Log.i(TAG, "LOCAL_CALCULATOR_PARSE_MISS semantic=true fallthrough=true")
                    }
                }
            }
            val multiAttachmentComparison = !anyKnowledgePackSelectedAtTurnStart &&
                turnAttachments.size > 1 &&
                attachmentContextProvider?.isMultiAttachmentComparisonRequest(query) == true
            val turnScopedAttachments = mutableListOf<BaselineContextAttachment>()
            if (!anyKnowledgePackSelectedAtTurnStart) {
                for (attachment in turnAttachments) {
                    val prepared = attachmentContextProvider?.contextAttachmentForTurn(
                        attachment = attachment,
                        query = query,
                        broadSourceCoverage = multiAttachmentComparison
                    ) ?: attachment.toContextAttachment(query)
                    if (prepared != null) turnScopedAttachments += prepared
                }
            }
            val hierarchicalAttachmentPlan =
                if (!visionModeActive &&
                    !anyKnowledgePackSelectedAtTurnStart &&
                    turnAttachments.size == 1
                ) {
                    // Local-first attachment ownership applies online and offline.
                    // Keep the map/reduce plan mobile-bounded online and offline. Three source
                    // sections means at most three compact leaf generations plus one
                    // synthesis, which is realistic within the 45 s whole-turn budget on
                    // the current Gemma device profile. Cloud remains fallback online.
                    val localSummarySections = 6
                    attachmentContextProvider?.summaryPlanForTurn(
                        attachment = turnAttachments.first(),
                        query = query,
                        maxSections = localSummarySections
                    )
                } else null

            val providerAttachmentEscalation = if (!visionModeActive &&
                !anyKnowledgePackSelectedAtTurnStart &&
                turnAttachments.isNotEmpty()
            ) {
                var chosen: Pair<Phase1UserAttachment, AttachmentEscalationDecision>? = null
                for (attachment in turnAttachments) {
                    val decision = attachmentContextProvider?.cloudEscalationDecision(attachment, query)
                    if (decision?.shouldEscalate == true) {
                        chosen = attachment to decision
                        break
                    }
                }
                chosen
            } else null
            val firstAttachmentWithinLocalPdfContract = turnAttachments.firstOrNull()?.let { attachment ->
                attachment.kind == Phase1AttachmentKind.PDF_DOCUMENT && attachment.pageCount in 1..100
            } == true
            val attachmentEscalation = providerAttachmentEscalation ?: when {
                !visionModeActive &&
                    !anyKnowledgePackSelectedAtTurnStart &&
                    hierarchicalAttachmentPlan != null &&
                    !hierarchicalAttachmentPlan.coverageComplete &&
                    !firstAttachmentWithinLocalPdfContract -> {
                    turnAttachments.firstOrNull()?.let { attachment ->
                        attachment to AttachmentEscalationDecision(
                            shouldEscalate = true,
                            category = AttachmentEscalationClass.CAPACITY,
                            reason = "whole_document_local_summary_representative",
                            coverageComplete = false
                        )
                    }
                }
                !visionModeActive &&
                    !anyKnowledgePackSelectedAtTurnStart &&
                    multiAttachmentComparison -> {
                    turnAttachments.firstOrNull()?.let { attachment ->
                        attachment to AttachmentEscalationDecision(
                            shouldEscalate = true,
                            category = AttachmentEscalationClass.CAPACITY,
                            reason = "multi_document_comparison_requires_balanced_coverage",
                            coverageComplete = false
                        )
                    }
                }
                else -> null
            }
            if (attachmentEscalation != null) {
                val (attachment, decision) = attachmentEscalation
                Log.i(
                    TAG,
                    "ATTACHMENT_ESCALATION_PLANNED rev=V11_0_CAPABILITY_CLOUD " +
                        "category=${decision.category} reason=${decision.reason} " +
                        "coverageComplete=${decision.coverageComplete} name=${attachment.displayName}"
                )
            }

            val immediateRecallCandidate =
                !visionModeActive &&
                    !anyKnowledgePackSelectedAtTurnStart &&
                    (contextProviders.filterIsInstance<AttachmentSessionContextProvider>()
                        .firstOrNull()
                        ?.hasImmediateRecallCandidate(
                            acceptedHistory = acceptedHistoryForDecision,
                            excludedAttachmentIds = turnAttachments.mapTo(linkedSetOf()) { it.id }
                        ) == true)
            val attachmentContextDependent =
                if (immediateRecallCandidate) {
                    contextProviders.filterIsInstance<SemanticCapabilityContextProvider>()
                        .firstOrNull()
                        ?.let { provider ->
                            runCatching { provider.isContextDependentForRecall(query, acceptedHistoryForDecision) }
                                .getOrDefault(false)
                        } == true
                } else false

            // KB + camera ("what is this?", "ye kya hai"): the Meta vision facts join the question,
            // so the guideline search uses the label text the camera read.
            val kbQuery = if (anyKnowledgePackSelectedAtTurnStart) kbTurnQuery(query, visionModeActive, bitmap, visualFrameProvider, turnAttachments) else query
            var attachments = turnScopedAttachments + collectExtensionAttachments(
                query = kbQuery,
                allowSessionRecall = !visionModeActive && !anyKnowledgePackSelectedAtTurnStart,
                currentTurnAttachmentIds = turnAttachments.mapTo(linkedSetOf()) { it.id },
                attachmentContextDependent = attachmentContextDependent,
                historyOverride = if (anyKnowledgePackSelectedAtTurnStart) kbHistoryForRetrieval else null
            )
            val genericKnowledgeProvider = genericKnowledgeProviderForTurn
            val genericLookupState = genericKnowledgeProvider?.lastLookupState()
            val turnSourceIds = buildList {
                addAll(turnAttachments.map { it.sourceId() })
                attachments.filter { it.attributes["knowledge_pack"] == "generic" }.forEach { attachment ->
                    attachment.attributes["kb_source_refs"]
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.forEach { add("kbref:$it") }
                }
                if (genericKnowledgeProvider?.isSelected() == true && genericLookupState?.attempted == true) {
                    val pack = genericLookupState.displayName.ifBlank { "Selected Knowledge Base" }
                        .replace('|', ' ').replace('\n', ' ').take(100)
                    if (!genericLookupState.ready) {
                        add("kbunavailable:$pack")
                    } else if (!genericLookupState.confident) {
                        add("kbnomatch:$pack")
                    }
                }
            }.distinct().take(20)
            val genericKnowledgeGrounding = attachments.any {
                it.attributes["grounding_required"] == "true" &&
                    it.attributes["knowledge_pack"] == "generic"
            }
            val genericKnowledgeSelected = genericKnowledgeProvider?.isSelected() == true
            // All user-selected knowledge packs own their turn.  The older ANM/SOP
            // provider and the generic procurement pack use different retrieval
            // engines, but provider-selection/freshness policy must see one source-
            // ownership boundary.  Otherwise the global router can steal a turn after
            // Generic KB has already supplied authoritative evidence.
            val anyKnowledgePackSelected =
                selectedKnowledgeProvider?.isSelected() == true || genericKnowledgeSelected
            val troubleshootingModeActive = visionModeActive &&
                visualContext.contains("<troubleshooting_mode>", ignoreCase = false)

            // V31 PocketSage-inspired mobile RAG ownership boundary.  When a KB is
            // selected, only retrieval adapters above and the isolated PocketRag engine
            // participate. The deliberate exception is an active troubleshooting camera
            // turn: inspect the current frame first, then use the selected KB to ground
            // the diagnostic answer. Ordinary KB turns remain isolated as before.
            if (anyKnowledgePackSelected && !troubleshootingModeActive) {
                return@withLock handlePocketRagTurn(
                    query = kbQuery,
                    started = started,
                    selectedAtTurnStart = selectedPocketKbAtTurnStart!!,
                    attachments = attachments,
                    turnSourceIds = turnSourceIds,
                    genericKnowledgeProvider = genericKnowledgeProvider,
                    checkOnline = forceExternalInformation,
                    onAnswerDelta = onLocalDelta,
                    onAnswerAbort = onLocalAbort
                )
            }

            // Provider-neutral TurnContext is built for every mode combination.
            // This is deliberately an adapter over the existing runtime rather than
            // a replacement router, so Audio/KB/Cloud behavior remains rollback-safe.
            if (useProviderNeutralTurnContext) {
                val troubleshootingTurn = visualContext.contains("<troubleshooting_mode>", ignoreCase = false)
                val shadow = buildProviderNeutralTurnContext(
                    query = query,
                    troubleshooting = troubleshootingTurn,
                    kbSelected = anyKnowledgePackSelected,
                    visionActive = visionModeActive
                )
                Log.i(
                    TAG,
                    "TURN_CONTEXT_READY mode=${if (troubleshootingTurn) "TROUBLESHOOTING" else "CONVERSATION"} " +
                        "kb=$anyKnowledgePackSelected vision=$visionModeActive notebookChars=${shadow.notebookContext.length} " +
                        "recentChars=${shadow.recentConversation.length} cloudEpisode=${activeEscalationEpisode?.reason ?: "NONE"}"
                )
            }

            val attachmentVisionPlan = buildAttachmentVisionPlan(
                query = query,
                turnAttachments = turnAttachments,
                contextAttachments = attachments
            )
            val visionTurnAttachments = attachmentVisionPlan.visionTurnAttachments
            val attachedVisionBitmaps = attachmentVisionPlan.bitmaps
            val localVlmAttachmentBitmap = attachmentVisionPlan.localVlmBitmap
            val localVlmCanOwnAttachment = localVlmAttachmentBitmap != null &&
                localModelManager?.isReadyForVision == true &&
                selectedKnowledgeProvider?.isSelected() != true

            // Do not silently substitute PDF page 1 when the user asks about an
            // unlocated chart/diagram/image. That creates a confident answer about the
            // wrong pixels. If text/OCR retrieval cannot resolve a page, ask for the
            // missing locator instead; this is a genuine user-action boundary, not a
            // provider failure.
            if (attachmentVisionPlan.unresolvedDocumentVisualRequest) {
                Log.w(TAG, "ATTACHMENT_VISUAL_PAGE_REQUIRED reason=page_not_resolved_from_evidence")
                return@withLock TurnResult(
                    text = "I can inspect that visual, but I couldn't determine which PDF page contains it from the extracted text. Please tell me the page number (for example, ‘page 47’) and I’ll analyze that page.",
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            var evidence = buildEvidence(
                attachments = attachments,
                visualContext = visualContext,
                bitmapPresent = false,
                cloudVisualObservation = "",
                hasUsefulVisualObservation = false,
                kbCaseContext = selectedKnowledgeProvider?.caseContextForTurn(query).orEmpty()
            )

            // Patch 1: resolve capabilities before choosing the provider branch so
            // visual, safety and live/external requirements can compose in one turn.
            val globalSemanticRoutingEnabled = visionModeActive || !anyKnowledgePackSelected
            // Troubleshooting + live camera is already an explicit user-owned mode.
            // Running the full semantic capability ensemble here adds several seconds
            // of repeated E5 work before every VLM turn even though visual ownership is
            // already known. Preserve only the boundaries that can legitimately divert
            // this mode: safety, deterministic external requirements, and an explicit
            // AR-overlay request. All other modes retain the full capability pipeline.
            val troubleshootingFastCapabilityMode = visionModeActive &&
                visualContext.contains("<troubleshooting_mode>", ignoreCase = false) &&
                !anyKnowledgePackSelected && turnAttachments.isEmpty()
            val capabilities = resolveTurnCapabilitiesForMode(
                query = query,
                acceptedHistory = acceptedHistoryForDecision,
                includeGlobalSemanticRouting = globalSemanticRoutingEnabled,
                troubleshootingFastMode = troubleshootingFastCapabilityMode
            )
            // Dynamic freshness routing is primary. Narrow AndroLLM-style deterministic
            // guards fast-path explicit web/current/live capability requests and post-cutoff
            // factual dates; E5 handles paraphrases and the semantic boundary. When that
            // boundary leans fresh but is not confident, a detached Gemma planner uses
            // the 2024 knowledge horizon plus recent accepted conversation to decide.
            val semanticCapabilityFailure = globalSemanticRoutingEnabled && !capabilities.semanticOperational
            // Confirm only explicit temporal scope here. This is deliberately
            // topic-independent and does not replace the semantic router.
            // Selected-KB ownership remains unchanged below.
            val explicitTemporalFreshness = FreshnessTemporalGuard.requiresFreshVerification(query)
            // Narrow capability escape: a selected KB owns in-domain/source-grounded
            // questions, but a clearly unrelated fresh/current request may temporarily
            // use the current-information capability without deselecting the KB.  This
            // check runs only when the selected KB produced no grounding for the turn.
            val selectedKbGroundingPresent = attachments.any { it.attributes["grounding_required"] == "true" }
            val kbEscapeCapabilities = if (anyKnowledgePackSelected && !selectedKbGroundingPresent && !visionModeActive && turnAttachments.isEmpty()) {
                resolveTurnCapabilities(
                    query = query,
                    acceptedHistory = acceptedHistoryForDecision,
                    includeGlobalSemanticRouting = true
                )
            } else TurnCapabilities()
            val kbCapabilityEscapeExternal = anyKnowledgePackSelected && !selectedKbGroundingPresent &&
                kbEscapeCapabilities.semanticOperational && kbEscapeCapabilities.externalRequired
            if (kbCapabilityEscapeExternal) {
                Log.i(TAG, "KB_CAPABILITY_ESCAPE capability=CURRENT_INFORMATION preserveKbSelection=true")
            }
            // A selected KB is the factual authority for the session.  Camera mode may
            // still enable the semantic layer so it can detect visual/safety needs, but
            // freshness/external classification must not silently take the turn away
            // from the selected source pack.
            // An explicit user-owned online-verification action is authoritative.
            // It is transport/control metadata, not a semantic keyword heuristic, and
            // therefore must never be downgraded to Local by the freshness classifier.
            val baseExternalRequired =
                forceExternalInformation ||
                    (explicitTemporalFreshness && !anyKnowledgePackSelected) ||
                    (capabilities.externalRequired && !anyKnowledgePackSelected) ||
                    kbCapabilityEscapeExternal
            // Semantic infrastructure failure is uncertainty, not proof that Cloud is
            // required. Let the tiny detached planner adjudicate instead of escalating
            // every ordinary turn. This mirrors modern semantic routers: semantic
            // signals constrain the candidate set; backend choice is not inferred from
            // classifier failure itself.
            // A three-axis semantic ensemble can occasionally vote "stable" even
            // when its fresh/local descriptors are nearly tied.  Do not let a close
            // numerical boundary silently authorize stale Local knowledge.  Hand only
            // these close calls to the existing detached Gemma freshness planner.
            // This is domain-neutral and introduces no news/topic keyword table.
            val semanticFreshnessBoundaryDistance =
                kotlin.math.abs(capabilities.externalScore - capabilities.localScore) +
                    kotlin.math.abs(capabilities.presentStateScore - capabilities.stableStateScore)
            val semanticFreshnessBoundaryNear = capabilities.semanticOperational &&
                semanticFreshnessBoundaryDistance <= 0.035f
            val plannerScopeEligible =
                !baseExternalRequired &&
                ((capabilities.externalAmbiguous && capabilities.externalFreshLean) || semanticCapabilityFailure) &&
                !anyKnowledgePackSelected &&
                turnAttachments.isEmpty() &&
                !capabilities.safetyRequired
            // Cloud First: an unclear "does this need current information?" turn leaning fresh goes
            // straight to the online web search instead of first asking the phone model (seconds).
            val cloudFirstFreshShortcut = plannerScopeEligible && cloudFirstLeansFresh(capabilities)
            val plannerLocal = localModelManager.takeUnless { cloudFirstFreshShortcut }
            var plannerAttempted = false
            var plannerFailed = false
            val runtimeCapabilityPlan = if (plannerScopeEligible &&
                plannerLocal != null &&
                plannerLocal.isReady &&
                !plannerLocal.permanentlyDisabled
            ) {
                plannerAttempted = true
                try {
                    withTimeout(engineScaledTimeout(LOCAL_PLANNER_TIMEOUT_MS)) {
                        val plannerHistory = try {
                            if (semanticCapabilityProvider?.isContextDependentForRecall(query, acceptedHistoryForDecision) == true) {
                                acceptedHistoryForDecision.takeLast(6)
                            } else emptyList()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            emptyList()
                        }
                        plannerLocal.planRuntimeCapability(
                            userQueryOnly = query,
                            history = plannerHistory
                        )
                    }
                } catch (te: TimeoutCancellationException) {
                    plannerFailed = true
                    Log.w(TAG, "ANDROLLM_CAPABILITY_PLAN_TIMEOUT ms=$LOCAL_PLANNER_TIMEOUT_MS")
                    RuntimeCapabilityPlan()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    plannerFailed = true
                    Log.w(TAG, "ANDROLLM_CAPABILITY_PLAN_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    RuntimeCapabilityPlan()
                }
            } else {
                RuntimeCapabilityPlan()
            }
            val plannerExternalRequired = runtimeCapabilityPlan.requiresFreshInformation || cloudFirstFreshShortcut
            if (cloudFirstFreshShortcut) Log.i(TAG, "CLOUD_FIRST_FRESH_SHORTCUT planner=skipped route=web_search")
            // Exact AndroLLM execution philosophy adapted to a semantic router:
            // the coarse router constrains the planner, and planner failure does not
            // erase the router's evidence. On an unresolved ambiguous turn, follow the
            // semantic direction rather than blindly falling back Local. This remains
            // fully topic-independent.
            // If a genuinely ambiguous turn cannot be resolved by the constrained
            // planner, preserve the requirement for fresh information instead of
            // silently authorizing an ordinary stale Local answer. Online this reaches
            // external retrieval. Unresolved/required freshness now fails closed offline
            // instead of authorizing a stale Local answer.
            // V26: unresolved semantic/planner ambiguity is not proof that external
            // information is required. Explicit/deterministic external requirements
            // remain authoritative; otherwise preserve the local-first policy.
            val semanticFailureFallbackExternal = false
            val rawExternalRequired = baseExternalRequired || plannerExternalRequired || semanticFailureFallbackExternal
            // V24: Troubleshooting + Live Camera is an explicit local visual-evidence
            // capability. A semantic freshness false-positive must not discard the current
            // frame or bypass local multimodal diagnosis. Explicit online requests and
            // deterministic external capabilities still retain cloud ownership.
            val troubleshootingCameraCapabilityOwner = visionModeActive &&
                visualContext.contains("<troubleshooting_mode>", ignoreCase = false) &&
                !forceExternalInformation &&
                !capabilities.deterministicExternalRequired
            // V26: the current camera frame is itself a present-time information source.
            // A semantic freshness lean must not reinterpret a visual identification,
            // inspection, diagnosis, or localization request as a need for web/cloud.
            // Explicit user-owned online actions and deterministic external capabilities
            // still retain external ownership.
            val currentFrameVisualCapabilityOwner = visionModeActive &&
                (capabilities.visualRequired || capabilities.spatialLocalizationRequired || capabilities.arOverlayRequested) &&
                !forceExternalInformation &&
                !capabilities.deterministicExternalRequired
            val spatialCameraCapabilityOwner = currentFrameVisualCapabilityOwner &&
                capabilities.spatialLocalizationRequired
            val externalRequired = rawExternalRequired &&
                !currentFrameVisualCapabilityOwner &&
                !troubleshootingCameraCapabilityOwner
            if (rawExternalRequired && !externalRequired) {
                Log.i(
                    TAG,
                    "CAMERA_CAPABILITY_OWNS_TURN rev=V24_FINAL_HARDENING rawExternal=true " +
                        "troubleshootingOwner=$troubleshootingCameraCapabilityOwner frameOwner=$currentFrameVisualCapabilityOwner spatialOwner=$spatialCameraCapabilityOwner " +
                        "explicitExternal=$forceExternalInformation deterministicExternal=${capabilities.deterministicExternalRequired} " +
                        "spatial=${capabilities.spatialLocalizationRequired}"
                )
            }
            val freshnessNeed = when {
                troubleshootingCameraCapabilityOwner || currentFrameVisualCapabilityOwner -> "local"
                forceExternalInformation -> CAPABILITY_FRESH_REQUIRED
                explicitTemporalFreshness && !currentFrameVisualCapabilityOwner &&
                    !troubleshootingCameraCapabilityOwner -> CAPABILITY_FRESH_REQUIRED
                baseExternalRequired -> CAPABILITY_FRESH_REQUIRED
                cloudFirstFreshShortcut -> CAPABILITY_FRESH_PREFERRED
                runtimeCapabilityPlan.capability == CAPABILITY_FRESH_REQUIRED -> CAPABILITY_FRESH_REQUIRED
                runtimeCapabilityPlan.capability == CAPABILITY_FRESH_PREFERRED -> CAPABILITY_FRESH_PREFERRED
                semanticFailureFallbackExternal -> CAPABILITY_FRESH_REQUIRED
                else -> "local"
            }

            currentTurnExternalIntentForRouting = externalRequired
            currentTurnKnowledgeSelectedForRouting = anyKnowledgePackSelected
            currentTurnTroubleshootingForRouting = visionModeActive &&
                visualContext.contains("<troubleshooting_mode>", ignoreCase = false)
            currentTurnContinuesCloudEpisodeForRouting = false

            // Provider ownership is deliberately one-turn only. Conversation/case
            // continuity lives in chatStore + Notebook, not in a sticky Local/Cloud
            // episode. Every accepted turn is re-planned from its current capability
            // requirements. The legacy episode machinery remains compiled for rollback
            // diagnostics, but it is not allowed to override the current turn plan.
            var continueCloudEpisodeThisTurn = false
            if (activeEscalationEpisode != null) {
                clearCloudEpisode("provider_ownership_disabled_per_turn_planning")
            }
            currentTurnContinuesCloudEpisodeForRouting = false

            if (plannerScopeEligible) {
                Log.i(
                    TAG,
                    "ANDROLLM_CAPABILITY_AMBIGUITY attempted=$plannerAttempted failed=$plannerFailed " +
                        "resolved=${runtimeCapabilityPlan.resolved} plan=${runtimeCapabilityPlan.capability} " +
                        "fallback=${if (!runtimeCapabilityPlan.resolved) when {
                            capabilities.externalFreshLean -> "semantic_fresh_lean"
                            semanticFreshnessBoundaryNear -> "semantic_boundary_uncertain"
                            else -> "local_safe_default"
                        } else "resolved"}"
                )
            }
            val verifiedGroundingRequired = capabilities.verifiedGroundingRequired ||
                (anyKnowledgePackSelected && !kbCapabilityEscapeExternal)
            val safetyEscalationRequired = capabilities.safetyRequired
            // Troubleshooting is an explicit session mode, not a guessed topic.  When
            // the user has deliberately enabled Live Camera inside that mode, the
            // current frame is part of the evidence for every committed turn.  This
            // prevents the camera from degrading into a preview-only surface simply
            // because a short utterance ("it still won't start", "what now?") is not
            // independently classified as visual by E5.
            val troubleshootingVisionRequired = visionModeActive &&
                visualContext.contains("<troubleshooting_mode>", ignoreCase = false)
            // Explicit Live Video is a user-owned multimodal mode. Once enabled, the
            // current camera frame participates in every turn; semantic routing may
            // influence how the frame is used, but it must never silently discard it.
            // This keeps short contextual turns ("what now?", "is this okay?")
            // grounded in the same live scene the user deliberately enabled.
            val cameraEvidenceRelevant = visionModeActive &&
                (troubleshootingVisionRequired || capabilities.visualRequired || capabilities.cameraEvidenceRelevant)
            // V28: camera availability is not visual intent. The current frame is admitted
            // only when this turn actually needs camera evidence. This prevents Camera ON
            // from forcing every ordinary conversational/procedural turn through the VLM.
            val useCameraForTurn = visionModeActive && cameraEvidenceRelevant
            val visualRequired = capabilities.visualRequired || troubleshootingVisionRequired || cameraEvidenceRelevant
            // A camera-relevant turn must externalize grounded observation even when the
            // older visualRequired classifier underfires. This is evidence ownership only;
            // it never authorizes AR or external/cloud routing.
            val groundedCameraTurn = bitmap != null && cameraEvidenceRelevant
            val spatialLocalizationRequired = capabilities.spatialLocalizationRequired
            // V25: spatial reasoning and AR rendering are intentionally separate.
            // Only this per-turn action permission may authorize an AR side effect.
            val arOverlayRequested = capabilities.arOverlayRequested
            // Stable/general knowledge remains Local-first. Only objective factual turns
            // identified by the existing semantic/deterministic capability layer are
            // validate-before-speak. Procedural/subjective questions remain ordinary
            // Local-first, while live/current information is still routed externally above.
            // Generic factual verification belongs only to ordinary KB-OFF / non-grounded turns.
            // Selected KB and source-grounded attachment answers are already owned by their
            // source validators and must never be rewritten by the generic cloud verifier.
            val sourceGroundedTurn =
                (anyKnowledgePackSelected && !kbCapabilityEscapeExternal) ||
                    attachments.any { it.attributes["grounding_required"] == "true" }
            // Routine stable facts stay Local-first. Correctness boundaries that
            // genuinely justify escalation are handled elsewhere:
            // - freshness/current facts -> external retrieval;
            // - safety/high-stakes -> safety capability;
            // - attachment/KB claims -> source-grounding validators.
            // The generic cloud fact checker is therefore not part of ordinary turns.
            val factualVerificationRequired = false
            val answerPolicyDecision = resolveAnswerPolicyForMode(
                query = query,
                capabilities = capabilities,
                sourceGroundedTurn = sourceGroundedTurn,
                hasTurnAttachments = turnAttachments.isNotEmpty(),
                troubleshootingVisual = troubleshootingVisionRequired,
                kbSelected = anyKnowledgePackSelected
            )
            Log.i(
                TAG,
                "CAPABILITY_ROUTE external=$externalRequired safety=$safetyEscalationRequired " +
                    "visual=$visualRequired useCameraForTurn=$useCameraForTurn troubleshootingVisual=$troubleshootingVisionRequired cameraEvidenceRelevant=$cameraEvidenceRelevant groundedCamera=$groundedCameraTurn spatial=$spatialLocalizationRequired arOverlay=$arOverlayRequested factualHint=${capabilities.factualVerificationRequired} " +
                    "factualVerify=$factualVerificationRequired semanticAmbiguous=${capabilities.externalAmbiguous} " +
                    "freshnessNeed=$freshnessNeed planner=${runtimeCapabilityPlan.capability} " +
                    "boundaryNear=$semanticFreshnessBoundaryNear boundaryDistance=${"%.4f".format(semanticFreshnessBoundaryDistance)} " +
                    "explicitExternal=$forceExternalInformation deterministicExternal=${capabilities.deterministicExternalRequired} " +
                    "plannerAttempted=$plannerAttempted freshLean=${capabilities.externalFreshLean} " +
                    "scores=${"%.4f".format(capabilities.externalScore)}/${"%.4f".format(capabilities.localScore)} " +
                    "temporal=${"%.4f".format(capabilities.presentStateScore)}/${"%.4f".format(capabilities.stableStateScore)} " +
                    "semantic=${capabilities.semanticOperational} " +
                    "fallback=${capabilities.fallbackUsed} deterministic=androllm_web_freshness_guards " +
                    "answerPolicy=${answerPolicyDecision.policy} cloudPreferred=${answerPolicyDecision.cloudPreferred} " +
                    "policyReason=${answerPolicyDecision.reason}"
            )

            // V1 mode contract: the user chose KB before session start. Runtime never
            // reclassifies individual turns as generic or changes KB mode mid-session.
            val selectedKbAtTurn = selectedKnowledgeProvider

            // ── Audio+Video path ─────────────────────────────────────────
            // Camera ON is model-based for visual reasoning. A loaded vision-capable
            // local model gets the first attempt; Cloud remains the direct fallback.
            // Capability composition still applies:
            // - visual + external -> visual observation, then Compound Mini/tools
            // - visual + safety   -> safety-constrained multimodal cloud
            // - external only     -> Compound Mini/tools without needless image upload
            // - safety only       -> safety-constrained text cloud
            // - ordinary camera turn -> local VLM first, then multimodal cloud
            // Text-only local models do not receive raw camera pixels.
            suspend fun handleVisionModeTurn(): TurnResult {
                if (bitmap == null && (visualRequired || (!externalRequired && !safetyEscalationRequired))) {
                    Log.w(TAG, "VIDEO_MULTIMODAL_FRAME_MISSING failClosed=true visualRequired=$visualRequired")
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = "I couldn't capture the current camera frame. Please keep the camera pointed at the subject and try again.",
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = listOf("live_camera")
                    )
                }

                // Fast authoritative KB-off troubleshooting path. Do this before
                // high-detail/scope semantic classification: the reviewed local VLM
                // owns the current frame and does not need those generic camera-mode
                // classifiers. Explicit AR/safety/external turns were already excluded.
                if (troubleshootingVisionRequired && !anyKnowledgePackSelected &&
                    bitmap != null && !arOverlayRequested &&
                    !externalRequired && !safetyEscalationRequired && !sourceGroundedTurn &&
                    turnAttachments.isEmpty()
                ) {
                    return runReviewedTroubleshootingCameraTurn(
                        query = query, bitmap = bitmap, detail = GroqChatProvider.VisionDetail.STANDARD, turnStartedMs = started
                    )
                }

                var highDetailVisual = false
                if (bitmap != null && visualRequired && capabilityDeciders.isNotEmpty()) {
                    for (decider in capabilityDeciders) {
                        val detailed = try {
                            decider.isOperational() &&
                                decider.requiresHighDetailVisualCapability(query, acceptedHistoryForDecision)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "VISUAL_DETAIL_DECIDER_FAILED ${t.message}")
                            false
                        }
                        if (detailed) highDetailVisual = true
                    }
                }
                var broadVisualInventory = false
                if (bitmap != null && visualRequired && capabilityDeciders.isNotEmpty()) {
                    for (decider in capabilityDeciders) {
                        val broad = try {
                            decider.isOperational() &&
                                decider.requiresBroadVisualInventoryCapability(query, acceptedHistoryForDecision)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "VISUAL_SCOPE_DECIDER_FAILED ${t.message}")
                            false
                        }
                        if (broad) broadVisualInventory = true
                    }
                }
                val detail = if (highDetailVisual || broadVisualInventory) GroqChatProvider.VisionDetail.HIGH
                    else GroqChatProvider.VisionDetail.STANDARD
                val networkHint = networkDiagnosticHint()
                var directVisionAttempted = false
                // Retain the observer's frame-only finding while later local/cloud
                // reasoning runs. The final answer provider must not replace this
                // evidence with its own prose.
                var turnVisualObservation = ""
                // A troubleshooting VLM timeout/rejection is recoverable. Keep this
                // separate from ordinary camera recovery so the same frame can reach
                // the existing multimodal Cloud path instead of being mistaken for a
                // user cancellation or silently downgraded to text-only advice.
                var troubleshootingLocalVisionUnavailable = false

                // A semantically dependent follow-up to an active Cloud episode stays
                // with Cloud for this bounded sub-problem. The exact same Notebook, KB
                // evidence and current frame are used, so provider switching never
                // creates a second conversation. If Cloud fails, normal Local/legacy
                // routing below remains the recovery path.
                if (continueCloudEpisodeThisTurn && troubleshootingVisionRequired &&
                    bitmap != null && !externalRequired && !safetyEscalationRequired &&
                    !spatialLocalizationRequired && canAttemptCloud()
                ) {
                    val kbGroundingPresent = attachments.any {
                        it.attributes["grounding_required"] == "true"
                    }
                    if (!anyKnowledgePackSelected || kbGroundingPresent) {
                        val renderedSources = renderExtensionContext(attachments)
                        val turnContext = buildProviderNeutralTurnContext(
                            query = query,
                            troubleshooting = troubleshootingVisionRequired,
                            kbSelected = anyKnowledgePackSelected,
                            visionActive = true,
                            knowledgeEvidence = renderedSources,
                            currentVisualEvidence = "Current camera frame is attached directly to this multimodal turn."
                        )
                        val cloudQuery = buildString {
                            appendLine(turnContext.renderForReasoning(7_200))
                            appendLine("<investigation_contract>")
                            appendLine("Continue the same investigation sub-problem from the prior accepted Cloud turn.")
                            appendLine("Reason directly over the current image and shared case state. Do not repeat completed checks.")
                            if (anyKnowledgePackSelected) {
                                appendLine("The selected Knowledge Base remains the sole authority for domain facts; use only supplied KB evidence for those claims.")
                            }
                            appendLine("</investigation_contract>")
                            appendLine("<user_request>")
                            appendLine(query.take(3_000))
                            appendLine("</user_request>")
                        }.take(9_000)
                        Log.i(TAG, "ESCALATION_EPISODE_MM_ATTEMPT kb=$anyKnowledgePackSelected contextChars=${cloudQuery.length}")
                        val episodeCloud = try {
                            withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                                runCloudVision(
                                    query = cloudQuery,
                                    evidence = renderedSources,
                                    bitmap = bitmap,
                                    detail = detail,
                                    safetyCritical = false
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            groqProvider.cancelActiveCall(); null
                        } catch (ce: CancellationException) {
                            groqProvider.cancelActiveCall(); throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "ESCALATION_EPISODE_MM_FAILED ${t.javaClass.simpleName}:${t.message}")
                            null
                        }
                        if (episodeCloud != null && episodeCloud.text.isNotBlank()) {
                            val historyForValidation = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                            val valid = if (kbGroundingPresent) {
                                validateAcceptedAnswer(
                                    query = query,
                                    answer = episodeCloud.text,
                                    provider = Provider.CLOUD,
                                    attachments = attachments,
                                    history = historyForValidation
                                )
                            } else true
                            if (valid) {
                                chatStore.append("user", query)
                                chatStore.append("assistant", episodeCloud.text)
                                notifyAcceptedTurn(
                                    query, episodeCloud.text, Provider.CLOUD,
                                    investigation = true,
                                    // Cloud prose is not visual evidence. Only promote a
                                    // separately validated observation into Notebook;
                                    // otherwise the next turn may treat an answer as an
                                    // old camera fact.
                                    visualObservation = episodeCloud.visualObservation,
                                    sourceIds = (if (anyKnowledgePackSelected) turnSourceIds else emptyList()) + listOf("live_camera")
                                )
                                val elapsed = System.currentTimeMillis() - started
                                runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                                Log.i(TAG, "ESCALATION_EPISODE_MM_ACCEPTED kb=$anyKnowledgePackSelected elapsed=${elapsed}ms")
                                return episodeCloud.copy(
                                    elapsedMs = elapsed,
                                    sourceIds = (if (anyKnowledgePackSelected) turnSourceIds else emptyList()) +
                                        listOf("live_camera", "provider_neutral_context", "cloud_episode")
                                )
                            }
                            Log.w(TAG, "ESCALATION_EPISODE_MM_REJECTED reason=grounding_validator fallback=local")
                        }
                    }
                }

                // Final provider-neutral multimodal path. Whenever Live Video is
                // explicitly active and the local model supports vision, reason directly
                // over pixels + Notebook + recent turns + any selected KB evidence.
                // Troubleshooting adds a diagnostic policy, but is no longer required
                // merely to make the camera part of the conversation. The legacy
                // observer->text-reasoner path remains below as a fallback for devices
                // or models that cannot complete direct multimodal reasoning.
                if (useProviderNeutralTurnContext && visionModeActive &&
                    bitmap != null && !externalRequired && !safetyEscalationRequired &&
                    !arOverlayRequested &&
                    localModelManager?.isReadyForVision == true
                ) {
                    val kbGroundingPresent = attachments.any {
                        it.attributes["grounding_required"] == "true"
                    }
                    if (!anyKnowledgePackSelected || kbGroundingPresent) {
                        val renderedSources = renderExtensionContext(attachments)
                        val turnContext = buildProviderNeutralTurnContext(
                            query = query,
                            troubleshooting = troubleshootingVisionRequired,
                            kbSelected = anyKnowledgePackSelected,
                            visionActive = true,
                            knowledgeEvidence = renderedSources,
                            attachmentEvidence = "",
                            currentVisualEvidence = "Current camera frame is attached directly to this multimodal turn."
                        )
                        val multimodalQuery = buildString {
                            appendLine(turnContext.renderForReasoning(7_200))
                            appendLine("<live_video_contract>")
                            appendLine("Reason directly over the current image and the accepted conversation state. Do not invent details not visible or supported by evidence.")
                            appendLine("Treat the current frame as present-turn evidence even when the user's wording is short or context-dependent.")
                            appendLine("For visible state, current pixels outrank user interpretation. Treat user statements as reported symptoms to investigate, not confirmed visual facts.")
                            appendLine("If the user's reported visual state conflicts with the current frame, preserve both, state the conflict, and ask one focused clarification rather than silently choosing the user's claim.")
                            if (groundedCameraTurn) {
                                appendLine("Inspect the current image BEFORE answering the user's words. The image is present-turn evidence, not decoration.")
                                appendLine("Return exactly these three sections: VISUAL_EVIDENCE:, UNCERTAINTY:, ANSWER:.")
                                appendLine("Under VISUAL_EVIDENCE list only concrete facts visibly supported by the current frame. If none are useful, write exactly NO_USEFUL_VISUAL_EVIDENCE.")
                                if (broadVisualInventory) {
                                    appendLine("The user is asking for broad visual coverage. Inspect the whole frame systematically and include all distinct relevant visible objects/items you can support, including secondary/background items; do not stop after the first obvious object.")
                                } else if (highDetailVisual) {
                                    appendLine("Inspect fine visible details carefully, including small labels, connectors, indicators, markings, text, or subtle states that are relevant to the request.")
                                }
                                appendLine("Under UNCERTAINTY list important visible-state details that cannot be confirmed; if none, write none.")
                                appendLine("Under ANSWER give the user-facing reply grounded in the visual evidence. If the request is troubleshooting, give the next useful diagnostic action/question; otherwise answer the visual request directly. Keep it concise unless the user requests broad scene coverage or more detail. Do not repeat completed checks.")
                                appendLine("Never claim that you see an object, state, light, screen condition, cable, damage, or error unless the current image supports it. Do not output JSON or markdown fences.")
                            }
                            if (anyKnowledgePackSelected) {
                                appendLine("The selected Knowledge Base is the authority for domain facts. Use the image for observations and the KB for domain guidance; do not substitute outside factual knowledge for the KB.")
                            }
                            appendLine("</live_video_contract>")
                            appendLine("<user_request>")
                            appendLine(query.take(3_000))
                            appendLine("</user_request>")
                        }.take(9_000)

                        Log.i(
                            TAG,
                            "UNIFIED_TURN_CONTEXT mode=${if (troubleshootingVisionRequired) "TROUBLESHOOTING" else if (groundedCameraTurn) "GROUNDED_LIVE_VIDEO" else "LIVE_VIDEO"} provider=LOCAL_MM " +
                                "kb=$anyKnowledgePackSelected contextChars=${multimodalQuery.length}"
                        )
                        if (troubleshootingVisionRequired) {
                            Log.i(
                                TAG,
                                "TROUBLESHOOTING_LOCAL_VISION_OWNER rev=V23_TROUBLESHOOTING_CAMERA_LOCAL " +
                                    "frame=${bitmap.width}x${bitmap.height} spatialClassifier=$spatialLocalizationRequired"
                            )
                        }
                        directVisionAttempted = true
                        val directLocal = try {
                            val localVisionTimeoutMs = if (troubleshootingVisionRequired)
                                TROUBLESHOOTING_VLM_TIMEOUT_MS else LOCAL_VLM_TURN_TIMEOUT_MS
                            val localVisionMaxTokens = when {
                                !groundedCameraTurn -> 640
                                broadVisualInventory -> TROUBLESHOOTING_VLM_INVENTORY_MAX_OUTPUT_TOKENS
                                highDetailVisual -> TROUBLESHOOTING_VLM_HIGH_DETAIL_MAX_OUTPUT_TOKENS
                                troubleshootingVisionRequired -> TROUBLESHOOTING_VLM_MAX_OUTPUT_TOKENS
                                // Grounded camera turns need only compact evidence and
                                // a short answer. Keeping the cap small avoids spending
                                // another several seconds decoding an essay before the
                                // normal conversation layer takes over.
                                else -> TROUBLESHOOTING_VLM_MAX_OUTPUT_TOKENS
                            }
                            val evidenceLimit = when {
                                broadVisualInventory -> TROUBLESHOOTING_EVIDENCE_INVENTORY_LIMIT
                                highDetailVisual -> TROUBLESHOOTING_EVIDENCE_HIGH_DETAIL_LIMIT
                                else -> TROUBLESHOOTING_EVIDENCE_DEFAULT_LIMIT
                            }
                            withTimeout(localVisionTimeoutMs) {
                                if (groundedCameraTurn) {
                                    runTroubleshootingVision(
                                        query = multimodalQuery,
                                        bitmap = bitmap,
                                        grounded = kbGroundingPresent,
                                        maxOutputTokens = localVisionMaxTokens,
                                        evidenceLimit = evidenceLimit
                                    )
                                } else {
                                    runLocalVision(
                                        query = multimodalQuery,
                                        bitmap = bitmap,
                                        grounded = kbGroundingPresent,
                                        maxOutputTokens = localVisionMaxTokens
                                    )
                                }
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "UNIFIED_LOCAL_MM_TIMEOUT rev=V24_FINAL_HARDENING troubleshooting=$troubleshootingVisionRequired fallback=cloud")
                            if (troubleshootingVisionRequired) troubleshootingLocalVisionUnavailable = true
                            null
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "UNIFIED_LOCAL_MM_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=cloud")
                            if (troubleshootingVisionRequired) troubleshootingLocalVisionUnavailable = true
                            null
                        }

                        if (directLocal != null && directLocal.text.isNotBlank()) {
                            val historyForValidation = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                            val valid = if (kbGroundingPresent) {
                                validateAcceptedAnswer(
                                    query = query,
                                    answer = directLocal.text,
                                    provider = Provider.LOCAL,
                                    attachments = attachments,
                                    history = historyForValidation
                                )
                            } else true
                            if (valid) {
                                if (groundedCameraTurn) {
                                    Log.i(
                                        TAG,
                                        "GROUNDED_CAMERA_ACCEPTED rev=V27_CAMERA_EVIDENCE evidenceChars=${directLocal.visualObservation.length} answerChars=${directLocal.text.length}"
                                    )
                                }
                                chatStore.append("user", query)
                                chatStore.append("assistant", directLocal.text)
                                notifyAcceptedTurn(
                                    query, directLocal.text, Provider.LOCAL,
                                    investigation = true,
                                    visualObservation = if (groundedCameraTurn) directLocal.visualObservation else "",
                                    sourceIds = (if (anyKnowledgePackSelected) turnSourceIds else emptyList()) +
                                        (if (groundedCameraTurn) listOf("live_camera") else emptyList())
                                )
                                val elapsed = System.currentTimeMillis() - started
                                runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                                clearCloudEpisode("local_multimodal_accepted")
                                Log.i(
                                    TAG,
                                    "UNIFIED_LOCAL_MM_ACCEPTED kb=$anyKnowledgePackSelected answerChars=${directLocal.text.length} elapsed=${elapsed}ms"
                                )
                                return directLocal.copy(
                                    elapsedMs = elapsed,
                                    visualObservation = if (groundedCameraTurn) directLocal.visualObservation else "",
                                    sourceIds = (if (anyKnowledgePackSelected) turnSourceIds else emptyList()) +
                                        (if (groundedCameraTurn) listOf("live_camera") else emptyList()) +
                                        listOf("provider_neutral_context")
                                )
                            }
                            Log.w(TAG, "UNIFIED_LOCAL_MM_REJECTED reason=grounding_validator fallback=cloud")
                            if (troubleshootingVisionRequired) troubleshootingLocalVisionUnavailable = true
                        }
                        if (troubleshootingVisionRequired && directLocal == null) {
                            troubleshootingLocalVisionUnavailable = true
                            Log.w(TAG, "TROUBLESHOOTING_LOCAL_VISION_UNAVAILABLE fallback=same_frame_cloud")
                        }
                    } else {
                        Log.i(TAG, "UNIFIED_LOCAL_MM_SKIPPED reason=selected_kb_without_verified_evidence")
                    }
                }

                // Legacy rollback/fallback path retained intact below.
                // Live-assist camera routing deliberately separates SEEING from
                // REASONING. Gemma VLM emits a short factual observation only; the
                // normal local text model then reasons over that observation + accepted
                // conversation. This prevents the VLM from spending tens of seconds
                // generating a generic troubleshooting essay that may ignore the pixels.
                // Phase 19: explicit visual-guidance turns are a dedicated fast
                // path. One captured ARCore image -> one local locator call.
                // Cloud may receive the exact same bitmap only if local spatial
                // grounding is unusable. Do not run the normal observer + text
                // reasoner + second AR relocalizer chain.
                if (!externalRequired && !safetyEscalationRequired && bitmap != null &&
                    arOverlayRequested
                ) {
                    Log.i(
                        TAG,
                        "VIDEO_ROUTE primary=FAST_AR_SINGLE_FRAME fallback=CLOUD_SAME_FRAME " +
                            "localVision=${localModelManager?.isReadyForVision == true} rev=V19_FAST_LOCAL_AR"
                    )
                    lastArLocalizationProvider = Provider.NONE
                    // Spatial localization is a visual capability, not a competing knowledge
                    // provider. A selected KB must therefore not disable AR. When KB evidence
                    // exists, give the locator a compact read-only context so it can resolve
                    // references such as "the component specified in the procedure" while the
                    // current image remains the sole authority for coordinates/visibility.
                    val arKnowledgeEvidence = if (anyKnowledgePackSelected) {
                        renderExtensionContext(attachments).take(2_400)
                    } else ""
                    val localized = runDedicatedArLocalization(
                        query = query,
                        bitmap = bitmap,
                        knowledgeEvidence = arKnowledgeEvidence
                    )
                    if (localized != null) {
                        val (intent, detected) = localized
                        val placement = onArPlacement?.invoke(intent, detected) ?: run {
                            ARPlacementResult.NOT_READY
                        }
                        val visualObservation = when (placement) {
                            ARPlacementResult.AR_ANCHORED ->
                                "VISIBLE_FACTS: Requested target '${detected.label}' was localized and world-anchored in the current Live Video view. SUPPORTED_INFERENCES: The marker is tracking with ARCore. UNKNOWN: none for placement."
                            ARPlacementResult.IMAGE_GROUNDED_2D ->
                                "VISIBLE_FACTS: Requested target '${detected.label}' was localized and highlighted on screen. SUPPORTED_INFERENCES: ARCore geometry was not reliable enough for a world anchor. UNKNOWN: persistent world placement."
                            ARPlacementResult.NOT_READY ->
                                "VISIBLE_FACTS: Requested target '${detected.label}' was localized in the captured turn image, but no AR marker was placed in the current Live Video view. SUPPORTED_INFERENCES: ArogyaDoot rejected placement rather than draw a potentially wrong marker. UNKNOWN: current live-view position and persistent world placement."
                        }
                        onVisualObservation?.invoke(visualObservation)
                        val provider = if (lastArLocalizationProvider == Provider.CLOUD) Provider.CLOUD else Provider.LOCAL
                        val answer = when (placement) {
                            ARPlacementResult.AR_ANCHORED -> "I found ${detected.label} and anchored the marker on it."
                            ARPlacementResult.IMAGE_GROUNDED_2D -> "I found ${detected.label} and highlighted it on screen. I couldn't safely lock a world anchor yet."
                            ARPlacementResult.NOT_READY -> "I found ${detected.label} in the captured frame, but I couldn't safely place an AR tag in the live view. Keep it in view, hold the phone steady, and try again."
                        }
                        chatStore.append("user", query)
                        chatStore.append("assistant", answer)
                        if (troubleshootingVisionRequired) {
                            troubleshootingSessionState.recordArGuidance(detected.label, placement.name)
                        }
                        notifyAcceptedTurn(
                            query, answer, provider,
                            investigation = troubleshootingVisionRequired,
                            visualObservation = if (troubleshootingVisionRequired) visualObservation else "",
                            sourceIds = if (troubleshootingVisionRequired) listOf("live_camera", "ar_guidance") else emptyList()
                        )
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(provider, elapsed, groqProvider.status())
                        Log.i(
                            TAG,
                            "FAST_AR_TURN_ACCEPTED rev=V19_FAST_LOCAL_AR provider=$provider target='${detected.label}' " +
                                "placement=$placement elapsed=${elapsed}ms oneImage=true"
                        )
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = answer,
                            provider = provider,
                            elapsedMs = elapsed,
                            streamedLocally = false,
                            visualObservation = visualObservation,
                            sourceIds = listOf("live_camera", "ar_guidance")
                        )
                    }

                    val answer = "I couldn't localize that target reliably in this frame. Keep it in view and try again."
                    chatStore.append("user", query)
                    chatStore.append("assistant", answer)
                    notifyAcceptedTurn(query, answer, Provider.NONE)
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = answer,
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = listOf("live_camera")
                    )
                }


                if (!externalRequired && !safetyEscalationRequired && bitmap != null &&
                    !arOverlayRequested && !directVisionAttempted &&
                    localModelManager?.isReady == true && localModelManager?.isTextWarm == true &&
                    selectedKnowledgeProvider?.isSelected() != true
                ) {
                    Log.i(
                        TAG,
                        "VIDEO_ROUTE primary=VISUAL_OBSERVER+LOCAL_LLM fallback=FULL_CLOUD " +
                            "troubleshooting=$troubleshootingVisionRequired localVision=${localModelManager?.isReadyForVision == true}"
                    )
                    var observation = if (localCameraReady) {
                        try {
                            withTimeout(engineScaledTimeout(LIVE_VISUAL_OBSERVER_TIMEOUT_MS)) {
                                runLocalVisualObservation(query, bitmap)
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "VIDEO_LOCAL_OBSERVER_TIMEOUT ms=$LIVE_VISUAL_OBSERVER_TIMEOUT_MS fallback=cloud_observer")
                            ""
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "VIDEO_LOCAL_OBSERVER_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=cloud_observer")
                            ""
                        }
                    } else ""

                    if (observation.isBlank() && canAttemptCloud()) {
                        // Legacy observer path fallback. Preserve the returned Cloud
                        // observation so downstream reasoning can actually use it.
                        observation = runCloudVisualObservationOnly(query, bitmap)
                    } else if (arOverlayRequested && observation.isNotBlank() && arLocalizationNeedsCloudFallback(observation) && canAttemptCloud()) {
                        // Local VLM understood that the user requested localization but could
                        // not ground coordinates. Cloud may refine perception; if it returns a
                        // grounded observation, use that evidence for the local reasoner too.
                        val cloudObservation = runCloudVisualObservationOnly(query, bitmap)
                        if (cloudObservation.isNotBlank() && !cloudObservation.contains("NO_USEFUL_VISUAL_EVIDENCE")) {
                            observation = cloudObservation
                            Log.i(TAG, "AR_LOCALIZATION_CLOUD_REFINED chars=${cloudObservation.length}")
                        }
                    }

                    if (observation.isNotBlank()) {
                        turnVisualObservation = observation
                        val visualConflict = troubleshootingVisionRequired &&
                            !anyKnowledgePackSelected &&
                            VisualEvidenceReconciler.isPotentialConflict(query, observation)
                        if (visualConflict) {
                            val conflictEvidence = VisualEvidenceReconciler.formatConflict(query, observation)
                            Log.w(
                                TAG,
                                "VISUAL_CONFLICT_DETECTED rev=V1_FRAME_PRECEDENCE " +
                                    "userChars=${query.length} observationChars=${observation.length} cloudAvailable=${canAttemptCloud()}"
                            )
                            // A material disagreement deserves same-frame Cloud review
                            // before local text recovery. If Cloud is unavailable, the
                            // bounded local path below receives the explicit conflict and
                            // must ask for clarification rather than choosing a side.
                            if (canAttemptCloud()) {
                                val conflictQuery = buildString {
                                    appendLine("This troubleshooting turn contains a visual conflict.")
                                    appendLine("Current frame pixels are authoritative for visible state.")
                                    appendLine("The user statement is a reported symptom, not proof.")
                                    appendLine("Compare both and respond briefly with one focused clarification if unresolved.")
                                    appendLine(conflictEvidence)
                                    appendLine("USER REQUEST: ${query.trim().take(1_200)}")
                                }
                                val cloudConflict = try {
                                    withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                                        runCloudVision(
                                            query = conflictQuery,
                                            evidence = conflictEvidence,
                                            bitmap = bitmap,
                                            detail = detail,
                                            safetyCritical = false
                                        )
                                    }
                                } catch (te: TimeoutCancellationException) {
                                    Log.w(TAG, "VISUAL_CONFLICT_CLOUD_REVIEW_TIMEOUT")
                                    groqProvider.cancelActiveCall()
                                    null
                                } catch (ce: CancellationException) {
                                    groqProvider.cancelActiveCall()
                                    throw ce
                                } catch (t: Throwable) {
                                    Log.w(TAG, "VISUAL_CONFLICT_CLOUD_REVIEW_FAILED ${t.javaClass.simpleName}:${t.message}")
                                    null
                                }
                                if (cloudConflict != null && cloudConflict.text.isNotBlank()) {
                                    chatStore.append("user", query)
                                    chatStore.append("assistant", cloudConflict.text)
                                    notifyAcceptedTurn(
                                        query,
                                        cloudConflict.text,
                                        Provider.CLOUD,
                                        investigation = true,
                                        visualObservation = observation,
                                        sourceIds = listOf("live_camera", "visual_conflict_cloud")
                                    )
                                    localModelManager?.closeSession()
                                    val elapsed = System.currentTimeMillis() - started
                                    runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                                    Log.i(TAG, "VISUAL_CONFLICT_CLOUD_REVIEW_ACCEPTED chars=${cloudConflict.text.length}")
                                    return cloudConflict.copy(
                                        elapsedMs = elapsed,
                                        visualObservation = observation,
                                        sourceIds = listOf("live_camera", "visual_conflict_cloud")
                                    )
                                }
                            }
                        }
                        onVisualObservation?.invoke(observation)
                        val observerTurnContext = buildProviderNeutralTurnContext(
                            query = query,
                            troubleshooting = troubleshootingVisionRequired,
                            kbSelected = false,
                            visionActive = true,
                            currentVisualEvidence = observation.take(1_400)
                        )
                        val visualAttachment = BaselineContextAttachment(
                            sourceId = "live_camera_observation",
                            text = buildString {
                                append(observerTurnContext.renderForReasoning(4_800)).append("\n\n")
                                append("CURRENT CAMERA-FRAME EVIDENCE (captured for this user turn):\n")
                                append(observation.take(1_400)).append("\n\n")
                                append("Reasoning contract: treat directly visible current-frame facts as authoritative evidence about the present physical state. ")
                                append("Supported inferences may be used as probable state, not certainty. If visual evidence contradicts an assumed failure mode, do not continue that failure path. ")
                                append("Do not invent facts that are listed as UNKNOWN. If the exact malfunction remains unknown, acknowledge what the image establishes and ask the most useful non-repetitive clarification instead of giving generic troubleshooting advice.")
                                if (visualConflict) {
                                    append(" The user has reported a visual claim that conflicts with this frame. Treat it as an unverified symptom, preserve the conflict, and ask one focused clarification; do not mark it as a confirmed fact.")
                                }
                            },
                            priority = 120,
                            attributes = mapOf(
                                "visual_observation" to "true",
                                "source_type" to "live_camera",
                                "grounding_required" to "true",
                                "current_frame_authoritative" to "true"
                            )
                        )
                        val localReasoned = try {
                            withTimeout(engineScaledTimeout(LIVE_VISUAL_REASONING_TIMEOUT_MS)) {
                                runLocal(
                                    query = query,
                                    evidence = visualAttachment.text,
                                    attachments = listOf(visualAttachment),
                                    onLocalDelta = onLocalDelta,
                                    livePresentationAllowed = false,
                                    maxOutputTokenOverride = LIVE_VISUAL_REASONING_MAX_TOKENS
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "VIDEO_LOCAL_REASONING_TIMEOUT ms=$LIVE_VISUAL_REASONING_TIMEOUT_MS fallback=cloud")
                            null
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "VIDEO_LOCAL_REASONING_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=cloud")
                            null
                        }

                        if (localReasoned != null && localReasoned.text.isNotBlank()) {
                            chatStore.append("user", query)
                            chatStore.append("assistant", localReasoned.text)
                            notifyAcceptedTurn(
                                query,
                                localReasoned.text,
                                Provider.LOCAL,
                                investigation = troubleshootingVisionRequired && !anyKnowledgePackSelected,
                                visualObservation = observation,
                                sourceIds = listOf("live_camera")
                            )
                            val elapsed = System.currentTimeMillis() - started
                            runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                            Log.i(
                                TAG,
                                "VIDEO_LOCAL_LIVE_ACCEPTED observationChars=${observation.length} " +
                                    "answerChars=${localReasoned.text.length} elapsed=${elapsed}ms"
                            )
                            return localReasoned.copy(
                                elapsedMs = elapsed,
                                visualObservation = observation,
                                sourceIds = listOf("live_camera")
                            )
                        }
                        Log.w(TAG, "VIDEO_LOCAL_REASONING_REJECTED fallback=cloud")
                    } else {
                        Log.w(TAG, "VIDEO_LOCAL_OBSERVATION_REJECTED fallback=cloud")
                    }
                }

                // V28: a successfully attempted local VLM that later becomes unusable
                // because of sanitization/formatting/grounding rejection does NOT by itself
                // authorize Cloud. Give the normal local text brain one bounded recovery
                // opportunity using the user's words/history only and explicitly forbid
                // invented camera facts. Cloud remains available only after this local
                // recovery is exhausted, or when external/safety capability genuinely owns
                // the turn.
                if (directVisionAttempted && !troubleshootingLocalVisionUnavailable &&
                    !externalRequired && !safetyEscalationRequired &&
                    localModelManager?.isReady == true && localModelManager?.isTextWarm == true &&
                    selectedKnowledgeProvider?.isSelected() != true
                ) {
                    val recoveryAttachment = BaselineContextAttachment(
                        sourceId = "live_camera_local_recovery",
                        text = buildString {
                            append("CURRENT CAMERA FRAME STATUS: local visual interpretation was attempted but no reliable visual evidence was accepted. ")
                            append("Do not claim or invent anything about what the camera currently shows. ")
                            append("Answer from the user's statement, accepted conversation history and Notebook. ")
                            append("If a visual fact is essential, ask one concise clarification or ask for a clearer view.")
                        },
                        priority = 115,
                        attributes = mapOf(
                            "visual_observation" to "unavailable",
                            "source_type" to "live_camera",
                            "local_recovery" to "true"
                        )
                    )
                    val recovered = try {
                        withTimeout(engineScaledTimeout(LIVE_VISUAL_REASONING_TIMEOUT_MS)) {
                            runLocal(
                                query = query,
                                evidence = recoveryAttachment.text,
                                attachments = listOf(recoveryAttachment),
                                onLocalDelta = onLocalDelta,
                                livePresentationAllowed = false,
                                maxOutputTokenOverride = 320
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        Log.w(TAG, "VIDEO_LOCAL_TEXT_RECOVERY_TIMEOUT ms=$LIVE_VISUAL_REASONING_TIMEOUT_MS fallback=cloud")
                        null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "VIDEO_LOCAL_TEXT_RECOVERY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    if (recovered != null && recovered.text.isNotBlank()) {
                        chatStore.append("user", query)
                        chatStore.append("assistant", recovered.text)
                        notifyAcceptedTurn(
                            query,
                            recovered.text,
                            Provider.LOCAL,
                            investigation = troubleshootingVisionRequired && !anyKnowledgePackSelected,
                            visualObservation = turnVisualObservation,
                            sourceIds = if (troubleshootingVisionRequired) listOf("live_camera", "local_text_recovery") else emptyList()
                        )
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                        Log.i(TAG, "VIDEO_LOCAL_TEXT_RECOVERY_ACCEPTED rev=V28_CAMERA_ADMISSION chars=${recovered.text.length} elapsed=${elapsed}ms")
                        return recovered.copy(
                            elapsedMs = elapsed,
                            visualObservation = turnVisualObservation,
                            sourceIds = listOf("local_text_recovery")
                        )
                    }
                    Log.w(TAG, "VIDEO_LOCAL_TEXT_RECOVERY_EXHAUSTED rev=V28_CAMERA_ADMISSION fallback=cloud_if_available")
                }

                val localKbVisionPathAvailable = selectedKnowledgeProvider?.isSelected() == true &&
                    localCameraReady &&
                    !externalRequired

                if (!canAttemptCloud() && !localKbVisionPathAvailable) {
                    Log.w(
                        TAG,
                        "VIDEO_CLOUD_UNAVAILABLE network=$networkHint hasKey=${groqProvider.hasApiKey()} status=${groqProvider.status()}"
                    )

                    // Offline must remain conversational even when the current frame could not
                    // be grounded. Do not fabricate image facts and do not terminate the turn.
                    // Fall back to the local text brain using accepted history/notebook context
                    // and explicitly tell it that the current frame is unavailable as evidence.
                    if (localModelManager?.isReady == true) {
                        val recoveryAttachment = BaselineContextAttachment(
                            sourceId = "live_camera_offline_recovery",
                            text = buildString {
                                append("CURRENT CAMERA FRAME STATUS: local visual interpretation was unavailable for this turn. ")
                                append("Do not claim or invent anything about what the camera currently shows. ")
                                append("Continue the troubleshooting conversation from the user's statement, accepted conversation history and Notebook. ")
                                append("If a visual fact is necessary, ask one concise, useful clarification or ask the user to point the camera more clearly; do not output a generic cloud-unavailable failure message.")
                            },
                            priority = 115,
                            attributes = mapOf(
                                "visual_observation" to "unavailable",
                                "source_type" to "live_camera",
                                "offline_recovery" to "true"
                            )
                        )
                        val recovered = try {
                            withTimeout(engineScaledTimeout(LIVE_VISUAL_REASONING_TIMEOUT_MS)) {
                                runLocal(
                                    query = query,
                                    evidence = recoveryAttachment.text,
                                    attachments = listOf(recoveryAttachment),
                                    onLocalDelta = onLocalDelta,
                                    livePresentationAllowed = false,
                                    maxOutputTokenOverride = LIVE_VISUAL_REASONING_MAX_TOKENS
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "VIDEO_OFFLINE_TEXT_RECOVERY_TIMEOUT ms=$LIVE_VISUAL_REASONING_TIMEOUT_MS fallback=cloud")
                            null
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "VIDEO_OFFLINE_TEXT_RECOVERY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                        if (recovered != null && recovered.text.isNotBlank()) {
                            chatStore.append("user", query)
                            chatStore.append("assistant", recovered.text)
                            notifyAcceptedTurn(
                                query,
                                recovered.text,
                                Provider.LOCAL,
                                investigation = troubleshootingVisionRequired && !anyKnowledgePackSelected,
                                visualObservation = turnVisualObservation,
                                sourceIds = if (troubleshootingVisionRequired) listOf("live_camera", "offline_text_recovery") else emptyList()
                            )
                            val elapsed = System.currentTimeMillis() - started
                            runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                            Log.i(TAG, "VIDEO_OFFLINE_TEXT_RECOVERY_ACCEPTED chars=${recovered.text.length} elapsed=${elapsed}ms")
                            return recovered.copy(
                                elapsedMs = elapsed,
                                visualObservation = turnVisualObservation,
                                sourceIds = listOf("live_camera_offline_recovery")
                            )
                        }
                    }

                    runtimeHealth.cloudFailure("video_cloud_unavailable", groqProvider.status())
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = "I couldn't reliably interpret the current frame offline. Please keep the camera on the subject and describe what isn't working.",
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = listOf("live_camera")
                    )
                }

                // Selected KB + Camera is a deliberately separate workflow.  Normal
                // Camera behavior below is untouched when KB is off or the active turn
                // is unrelated to the selected ANM knowledge thread.
                //
                //   Local VLM observation when available, otherwise Cloud Vision ->
                //   semantic KB re-retrieval -> grounded Local LLM -> cloud fallback.
                //
                // The observation source is capability-driven; the KB remains the
                // grounded reasoning source for the final answer.
                val kbProvider = selectedKnowledgeProvider
                val kbCameraTurn = kbProvider?.isSelected() == true
                if (kbProvider != null && kbCameraTurn && !externalRequired) {
                    val frame = bitmap ?: run {
                        Log.w(TAG, "KB_CAMERA_FRAME_MISSING failClosed=true")
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I couldn't capture the current camera frame. Please keep the camera pointed at the subject and try again.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = listOf("live_camera")
                        )
                    }
                    val observation = observeVisualFrameInternal(query, frame)
                    if (observation.isBlank()) {
                        Log.w(TAG, "KB_CAMERA_OBSERVATION_MISSING failClosed=true")
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I couldn't inspect the current camera frame reliably. Please check Cloud AI and try again.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = listOf("live_camera")
                        )
                    }

                    val enrichedKb = kbProvider.contextForVisualObservation(
                        query = query,
                        visualObservation = observation,
                        acceptedHistory = acceptedHistoryForDecision
                    )
                    val nonKbAttachments = attachments.filterNot {
                        it.attributes["knowledge_pack"] == "anm_sop"
                    }
                    val kbAttachment = enrichedKb ?: attachments.firstOrNull {
                        it.attributes["knowledge_pack"] == "anm_sop"
                    }
                    val visualObservationAttachment = BaselineContextAttachment(
                        sourceId = "live_camera_observation",
                        text = "Current camera-frame observation:\n${observation.take(2_000)}",
                        priority = 110,
                        attributes = mapOf(
                            "visual_observation" to "true",
                            "source_type" to "live_camera"
                        )
                    )
                    attachments = buildList {
                        addAll(nonKbAttachments)
                        if (kbAttachment != null) add(kbAttachment)
                        add(visualObservationAttachment)
                    }.sortedByDescending { it.priority }
                    evidence = buildEvidence(
                        attachments = attachments,
                        visualContext = visualContext,
                        bitmapPresent = true,
                        cloudVisualObservation = observation,
                        hasUsefulVisualObservation = true,
                        kbCaseContext = kbProvider.caseContextForTurn(query)
                    )

                    val hasKbEvidence = kbAttachment?.attributes?.get("grounding_required") == "true"
                    if (!hasKbEvidence) {
                        val clarification = kbProvider.clarificationForTurn(query).ifBlank {
                            "I can't safely identify an applicable SOP from the current details and camera observation. Please add the key clinical finding or stage."
                        }
                        Log.w(
                            TAG,
                            "KB_CAMERA_NO_VERIFIED_EVIDENCE failClosed=true rev=V3_1_1_SOP_GROUNDED_CLOUD"
                        )
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = clarification,
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            visualObservation = observation,
                            sourceIds = listOf("live_camera")
                        )
                    }

                    Log.i(
                        TAG,
                        "MODE_ROUTE mode=CAMERA_KB primary=LOCAL_GROUNDED observationChars=${observation.length} evidenceChars=${evidence.length}"
                    )
                    var groundedLocal = try {
                        runLocal(
                            query = query,
                            evidence = evidence,
                            attachments = attachments,
                            onLocalDelta = onLocalDelta,
                            retryVariant = false,
                            livePresentationAllowed = false
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "KB_CAMERA_LOCAL_FAILED ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    if (groundedLocal == null) {
                        Log.w(TAG, "KB_CAMERA_LOCAL_RETRY freshSession=true")
                        groundedLocal = try {
                            runLocal(
                                query = query,
                                evidence = evidence,
                                attachments = attachments,
                                onLocalDelta = onLocalDelta,
                                retryVariant = true,
                                livePresentationAllowed = false
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "KB_CAMERA_LOCAL_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    }

                    // A grounded local KB answer is authoritative once it passes the
                    // local SOP grounding gate. Do not replace it with a cloud check.

                    if (groundedLocal != null && groundedLocal.text.isNotBlank()) {
                        localModelManager?.closeSession()
                        chatStore.append("user", query)
                        chatStore.append("assistant", groundedLocal.text)
                        notifyAcceptedTurn(query, groundedLocal.text, Provider.LOCAL)
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                        return groundedLocal.copy(
                            elapsedMs = elapsed,
                            visualObservation = observation,
                            sourceIds = listOf("live_camera")
                        )
                    }

                    Log.w(TAG, "KB_CAMERA_LOCAL_UNUSABLE primary=CLOUD_FALLBACK")
                    val cloudFallback = try {
                        withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                            runGroundedKbCloud(
                                query,
                                evidence,
                                safetyCritical = safetyEscalationRequired,
                                genericKnowledge = genericKnowledgeSelected,
                                validationAttachments = attachments
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall(); null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "KB_CAMERA_CLOUD_FALLBACK_FAILED ${t.message}")
                        null
                    }
                    if (cloudFallback != null && cloudFallback.text.isNotBlank()) {
                        localModelManager?.closeSession()
                        chatStore.append("user", query)
                        chatStore.append("assistant", cloudFallback.text)
                        notifyAcceptedTurn(query, cloudFallback.text, Provider.CLOUD)
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return cloudFallback.copy(
                            elapsedMs = elapsed,
                            visualObservation = observation,
                            sourceIds = listOf("live_camera")
                        )
                    }
                    runtimeHealth.cloudFailure("kb_camera_no_accepted_answer", groqProvider.status())
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = "I couldn't produce a sufficiently grounded answer from the selected knowledge base for this visual finding.",
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        visualObservation = observation,
                        sourceIds = listOf("live_camera")
                    )
                }

                // Current/web + visual needs both capabilities. Convert the frame to
                // factual visual evidence first, then let Compound Mini use web/tools.
                if (externalRequired) {
                    val visualObservation = if (visualRequired && bitmap != null) {
                        observeVisualFrameInternal(query, bitmap)
                    } else ""
                    if (visualRequired && visualObservation.isBlank()) {
                        Log.w(TAG, "VIDEO_EXTERNAL_VISUAL_OBSERVATION_MISSING failClosed=true")
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I need both the live camera view and current information for that, but I couldn't inspect the frame reliably. Please try again.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = listOf("live_camera")
                        )
                    }
                    if (visualObservation.isNotBlank() && kbProvider?.isSelected() == true) {
                        val enrichedKb = kbProvider.contextForVisualObservation(
                            query = query,
                            visualObservation = visualObservation,
                            acceptedHistory = acceptedHistoryForDecision
                        )
                        if (enrichedKb != null) {
                            attachments = (attachments.filterNot { it.attributes["knowledge_pack"] == "anm_sop" } + enrichedKb)
                                .sortedByDescending { it.priority }
                        }
                    }
                    val composedEvidence = buildEvidence(
                        attachments = attachments,
                        visualContext = visualContext,
                        bitmapPresent = visualRequired && bitmap != null,
                        cloudVisualObservation = visualObservation,
                        hasUsefulVisualObservation = visualObservation.isNotBlank(),
                        kbCaseContext = kbProvider?.caseContextForTurn(query).orEmpty()
                    )
                    Log.i(
                        TAG,
                        "MODE_ROUTE mode=CAMERA primary=CLOUD_EXTERNAL visual=$visualRequired safety=$safetyEscalationRequired"
                    )
                    val external = try {
                        withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) {
                            runExternalCapabilities(
                                query,
                                composedEvidence,
                                safetyCritical = safetyEscalationRequired,
                                groundedKnowledge = kbProvider?.isSelected() == true &&
                                    attachments.any { it.attributes["grounding_required"] == "true" }
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall(); null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "VIDEO_EXTERNAL_CAPABILITY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    if (external != null && external.text.isNotBlank()) {
                        chatStore.append("user", query)
                        chatStore.append("assistant", external.text)
                        notifyAcceptedTurn(query, external.text, Provider.CLOUD)
                        localModelManager?.closeSession()
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return external.copy(
                            elapsedMs = elapsed,
                            visualObservation = visualObservation,
                            sourceIds = if (visualRequired) listOf("live_camera") else turnSourceIds
                        )
                    }
                    runtimeHealth.cloudFailure("video_external_unavailable", groqProvider.status())
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = currentInformationUnavailableMessage(),
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = (if (visualRequired) listOf("live_camera") else turnSourceIds) +
                            listOf("freshness_required:offline_or_cloud_unavailable")
                    )
                }

                // Safety without visual dependence should not upload a frame merely
                // because the camera happens to be enabled. Safety + visual stays
                // multimodal and receives a safety-constrained vision prompt.
                if (safetyEscalationRequired && !visualRequired) {
                    Log.w(TAG, "MODE_ROUTE mode=CAMERA primary=CLOUD_SAFETY visual=false")
                    val safeCloud = try {
                        withTimeout(CLOUD_TURN_TIMEOUT_MS) { runSafetyCloud(query, evidence) }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall(); null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "VIDEO_SAFETY_ESCALATION_FAILED ${t.message}")
                        null
                    }
                    if (safeCloud != null && safeCloud.text.isNotBlank()) {
                        chatStore.append("user", query)
                        chatStore.append("assistant", safeCloud.text)
                        notifyAcceptedTurn(query, safeCloud.text, Provider.CLOUD)
                        localModelManager?.closeSession()
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return safeCloud.copy(elapsedMs = elapsed, sourceIds = turnSourceIds)
                    }
                    runtimeHealth.cloudFailure("video_safety_unavailable", groqProvider.status())
                    return safetyFallbackTurn(query, evidence, onLocalDelta, started, turnSourceIds)
                }

                val frame = bitmap ?: run {
                    Log.w(TAG, "VIDEO_MULTIMODAL_FRAME_MISSING failClosed=true")
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = "I couldn't capture the current camera frame. Please keep the camera pointed at the subject and try again.",
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = listOf("live_camera")
                    )
                }
                Log.i(
                    TAG,
                    "VIDEO_MULTIMODAL_CLOUD_BEGIN historyTurns=${chatStore.recent(120).size} " +
                        "evidenceChars=${evidence.length} frame=${frame.width}x${frame.height} " +
                        "detail=$detail safety=$safetyEscalationRequired imageUpload=true network=$networkHint"
                )
                val cloudTurnContext = if (useProviderNeutralTurnContext) {
                    buildProviderNeutralTurnContext(
                        query = query,
                        troubleshooting = troubleshootingVisionRequired,
                        kbSelected = anyKnowledgePackSelected,
                        visionActive = true,
                        knowledgeEvidence = renderExtensionContext(attachments),
                        currentVisualEvidence = "Current camera frame is attached directly to this multimodal turn."
                    )
                } else null
                val cloudVisualQuery = if (cloudTurnContext != null) {
                    buildString {
                        appendLine(cloudTurnContext.renderForReasoning(7_200))
                        appendLine("<live_video_contract>")
                        appendLine("Reason over the current image, Notebook, and recent conversation together.")
                        appendLine("For visible state, current pixels outrank user interpretation. User statements are reported symptoms, not confirmed visual facts.")
                        appendLine("If the reported visual state conflicts with the current frame, preserve both and ask one focused clarification; never silently convert the report into frame evidence.")
                        if (troubleshootingVisionRequired) {
                            appendLine("This is an active troubleshooting case. Keep the reply brief and operational: normally 1 to 3 short sentences, under about 60 words, with ONE next action or ONE focused question. Do not repeat completed checks. Expand only if the user explicitly requests detailed steps or a full list.")
                        }
                        appendLine("</live_video_contract>")
                        appendLine("<user_request>")
                        appendLine(query.take(3_000))
                        appendLine("</user_request>")
                    }.take(9_000)
                } else query
                Log.i(
                    TAG,
                    "VIDEO_CLOUD_CONTEXT troubleshooting=$troubleshootingVisionRequired " +
                        "notebookChars=${cloudTurnContext?.notebookContext?.length ?: 0} " +
                        "recentChars=${cloudTurnContext?.recentConversation?.length ?: 0} frame=true"
                )
                val visualCloud = try {
                    withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                        runCloudVision(cloudVisualQuery, evidence, frame, detail, safetyCritical = safetyEscalationRequired)
                    }
                } catch (te: TimeoutCancellationException) {
                    Log.w(TAG, "VIDEO_MULTIMODAL_CLOUD_TIMEOUT ms=$CLOUD_TURN_TIMEOUT_MS")
                    groqProvider.cancelActiveCall(); null
                } catch (ce: CancellationException) {
                    groqProvider.cancelActiveCall(); throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "VIDEO_MULTIMODAL_CLOUD_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    null
                }

                if (visualCloud != null && visualCloud.text.isNotBlank()) {
                    Log.i(TAG, "VIDEO_MULTIMODAL_CLOUD_SUCCESS chars=${visualCloud.text.length} detail=$detail safety=$safetyEscalationRequired")
                    chatStore.append("user", query)
                    chatStore.append("assistant", visualCloud.text)
                    notifyAcceptedTurn(
                        query,
                        visualCloud.text,
                        Provider.CLOUD,
                        investigation = troubleshootingVisionRequired && !anyKnowledgePackSelected,
                        visualObservation = turnVisualObservation,
                        sourceIds = listOf("live_camera")
                    )
                    localModelManager?.closeSession()
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                    return visualCloud.copy(
                        elapsedMs = elapsed,
                        visualObservation = turnVisualObservation,
                        sourceIds = listOf("live_camera")
                    )
                }
                Log.w(TAG, "VIDEO_MULTIMODAL_CLOUD_NO_ACCEPTED_ANSWER status=${groqProvider.status()} detail=$detail")
                runtimeHealth.cloudFailure("video_multimodal_failed", groqProvider.status())
                localModelManager?.closeSession()
                return TurnResult(
                    text = "I can't reliably inspect the current camera frame right now. Please check the connection and try again.",
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = listOf("live_camera")
                )
            }
            if (visionModeActive && useCameraForTurn) return@withLock handleVisionModeTurn()
            if (visionModeActive && !useCameraForTurn) {
                Log.i(TAG, "CAMERA_ADMISSION_BYPASS rev=V28_CAMERA_ADMISSION reason=frame_not_relevant route=normal_brain")
            }
            // ── End Audio+Video path ───────────────────────────────────────

            // ── Audio-only attachment vision path ───────────────────────────
            // Model-based attachment contract:
            // - vision-capable local model: photos/visual-only documents use local VLM first;
            // - text-only local model: deterministic extraction feeds the local text LLM;
            // - any unusable local result escalates directly to Cloud when available;
            // - offline ML Kit/extracted evidence is a best-effort fallback, not a VLM gate.
            val attachmentHasLocalEvidence = visionTurnAttachments.any { it.hasLocalEvidence }
            val attachmentVisionShouldOwnTurn = attachedVisionBitmaps.isNotEmpty() &&
                !localVlmCanOwnAttachment &&
                (canAttemptCloud() || !attachmentHasLocalEvidence)
            if (attachedVisionBitmaps.isNotEmpty() && !attachmentVisionShouldOwnTurn) {
                Log.i(
                    TAG,
                    "ATTACHMENT_VISION_DEFERRED_OFFLINE localEvidence=true " +
                        "sources=${turnSourceIds.joinToString(",")}"
                )
            }
            suspend fun handleAttachmentVisionTurn(): TurnResult? {
                if (!attachmentVisionShouldOwnTurn) return null
                var attachmentHighDetail = false
                for (decider in capabilityDeciders) {
                    val detailed = try {
                        decider.isOperational() &&
                            decider.requiresHighDetailVisualCapability(query, acceptedHistoryForDecision)
                    } catch (_: Throwable) { false }
                    if (detailed) attachmentHighDetail = true
                }
                val detail = if (attachmentHighDetail) GroqChatProvider.VisionDetail.HIGH
                    else GroqChatProvider.VisionDetail.STANDARD

                Log.i(
                    TAG,
                    "ATTACHMENT_VISION_CLOUD_BEGIN sources=${turnSourceIds.joinToString(",")} " +
                        "images=${attachedVisionBitmaps.size} detail=$detail safety=$safetyEscalationRequired external=$externalRequired"
                )

                // V1 selected-KB image attachment path mirrors Live Camera:
                // Cloud observation only -> Notebook-staged observation -> E5/KB ->
                // grounded Local reasoning. Normal attachment vision remains unchanged
                // when KB is off.
                val attachmentKbProvider = selectedKnowledgeProvider
                if (attachmentKbProvider?.isSelected() == true && !externalRequired) {
                    val observation = observeVisualFrameInternal(query, attachedVisionBitmaps.firstOrNull())
                    if (observation.isBlank()) {
                        runtimeHealth.cloudFailure("kb_attachment_visual_observation_missing", groqProvider.status())
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I couldn't inspect the attached image reliably. Please check Cloud AI and try again.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = turnSourceIds
                        )
                    }

                    val enrichedKb = attachmentKbProvider.contextForVisualObservation(
                        query = query,
                        visualObservation = observation,
                        acceptedHistory = acceptedHistoryForDecision
                    )
                    val nonKbAttachments = attachments.filterNot { it.attributes["knowledge_pack"] == "anm_sop" }
                    val kbAttachment = enrichedKb ?: attachments.firstOrNull { it.attributes["knowledge_pack"] == "anm_sop" }
                    val visualObservationAttachment = BaselineContextAttachment(
                        sourceId = "attachment_image_observation",
                        text = "Attached-image observation:\n${observation.take(2_000)}",
                        priority = 110,
                        attributes = mapOf(
                            "visual_observation" to "true",
                            "source_type" to "attachment_image"
                        )
                    )
                    attachments = buildList {
                        addAll(nonKbAttachments)
                        if (kbAttachment != null) add(kbAttachment)
                        add(visualObservationAttachment)
                    }.sortedByDescending { it.priority }
                    evidence = buildEvidence(
                        attachments = attachments,
                        visualContext = visualContext,
                        bitmapPresent = true,
                        cloudVisualObservation = observation,
                        hasUsefulVisualObservation = true,
                        kbCaseContext = attachmentKbProvider.caseContextForTurn(query)
                    )

                    val hasKbEvidence = kbAttachment?.attributes?.get("grounding_required") == "true"
                    if (!hasKbEvidence) {
                        val clarification = attachmentKbProvider.clarificationForTurn(query).ifBlank {
                            "I can't safely identify an applicable SOP from the current details and attached image. Please add the key clinical finding or stage."
                        }
                        Log.w(
                            TAG,
                            "KB_ATTACHMENT_NO_VERIFIED_EVIDENCE failClosed=true rev=V3_1_1_SOP_GROUNDED_CLOUD"
                        )
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = clarification,
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            visualObservation = observation,
                            sourceIds = turnSourceIds
                        )
                    }

                    Log.i(TAG, "MODE_ROUTE mode=ATTACHMENT_KB primary=LOCAL_GROUNDED observationChars=${observation.length}")
                    var groundedLocal = try {
                        runLocal(
                            query = query,
                            evidence = evidence,
                            attachments = attachments,
                            onLocalDelta = onLocalDelta,
                            retryVariant = false,
                            livePresentationAllowed = false
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "KB_ATTACHMENT_LOCAL_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
                    }
                    if (groundedLocal == null) {
                        Log.w(TAG, "KB_ATTACHMENT_LOCAL_RETRY freshSession=true")
                        groundedLocal = try {
                            runLocal(
                                query = query,
                                evidence = evidence,
                                attachments = attachments,
                                onLocalDelta = onLocalDelta,
                                retryVariant = true,
                                livePresentationAllowed = false
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "KB_ATTACHMENT_LOCAL_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
                        }
                    }

                    // A grounded local KB answer is authoritative once it passes the
                    // local SOP grounding gate. Do not replace it with a cloud check.

                    if (groundedLocal != null && groundedLocal.text.isNotBlank()) {
                        localModelManager?.closeSession()
                        chatStore.append("user", query)
                        chatStore.append("assistant", groundedLocal.text)
                        notifyAcceptedTurn(query, groundedLocal.text, Provider.LOCAL)
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                        return groundedLocal.copy(
                            elapsedMs = elapsed,
                            visualObservation = observation,
                            sourceIds = turnSourceIds
                        )
                    }

                    val cloudFallback = try {
                        withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                            runGroundedKbCloud(
                                query,
                                evidence,
                                safetyCritical = safetyEscalationRequired,
                                genericKnowledge = genericKnowledgeSelected,
                                validationAttachments = attachments
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall(); null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "KB_ATTACHMENT_CLOUD_FALLBACK_FAILED ${t.message}"); null
                    }
                    if (cloudFallback != null && cloudFallback.text.isNotBlank()) {
                        localModelManager?.closeSession()
                        chatStore.append("user", query)
                        chatStore.append("assistant", cloudFallback.text)
                        notifyAcceptedTurn(query, cloudFallback.text, Provider.CLOUD)
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return cloudFallback.copy(
                            elapsedMs = elapsed,
                            visualObservation = observation,
                            sourceIds = turnSourceIds
                        )
                    }

                    runtimeHealth.cloudFailure("kb_attachment_no_accepted_answer", groqProvider.status())
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = "I couldn't produce a sufficiently grounded answer from the selected knowledge base for this image.",
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        visualObservation = observation,
                        sourceIds = turnSourceIds
                    )
                }

                // A visual attachment can also require current/web capability. Extract
                // factual visual evidence from the first turn-scoped image, then pass
                // that evidence to Compound Mini. (Multi-page visual document handling
                // is finalized separately with the PDF attachment patch.)
                if (externalRequired) {
                    val visualObservation = observeVisualFrameInternal(query, attachedVisionBitmaps.firstOrNull())
                    if (visualObservation.isBlank()) {
                        runtimeHealth.cloudFailure("attachment_external_visual_observation_missing", groqProvider.status())
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I need both the attached image and current information for that, but I couldn't inspect the image reliably. Please try again.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            sourceIds = turnSourceIds
                        )
                    }
                    val composedEvidence = buildEvidence(
                        attachments = attachments,
                        visualContext = visualContext,
                        bitmapPresent = true,
                        cloudVisualObservation = visualObservation,
                        hasUsefulVisualObservation = true,
                        kbCaseContext = selectedKnowledgeProvider?.caseContextForTurn(query).orEmpty()
                    )
                    val attachmentExternal = if (canAttemptCloud()) {
                        try {
                            withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) {
                                runExternalCapabilities(
                                    query,
                                    composedEvidence,
                                    safetyCritical = safetyEscalationRequired,
                                    groundedKnowledge = selectedKnowledgeProvider?.isSelected() == true &&
                                        attachments.any { it.attributes["grounding_required"] == "true" }
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            groqProvider.cancelActiveCall(); null
                        } catch (ce: CancellationException) {
                            groqProvider.cancelActiveCall(); throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "ATTACHMENT_EXTERNAL_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    } else null
                    if (attachmentExternal != null && attachmentExternal.text.isNotBlank()) {
                        chatStore.append("user", query)
                        chatStore.append("assistant", attachmentExternal.text)
                        notifyAcceptedTurn(query, attachmentExternal.text, Provider.CLOUD)
                        localModelManager?.closeSession()
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return attachmentExternal.copy(
                            elapsedMs = elapsed,
                            visualObservation = visualObservation,
                            sourceIds = turnSourceIds
                        )
                    }
                    runtimeHealth.cloudFailure("attachment_external_unavailable", groqProvider.status())
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = currentInformationUnavailableMessage(),
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = turnSourceIds
                    )
                }

                val attachmentCloud = if (canAttemptCloud()) {
                    try {
                        withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                            runCloudVision(
                                query, evidence, attachedVisionBitmaps, detail,
                                safetyCritical = safetyEscalationRequired
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall()
                        runtimeHealth.cloudFailure("attachment_vision_timeout", groqProvider.status())
                        null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        runtimeHealth.cloudFailure("attachment_vision_${t.javaClass.simpleName}", groqProvider.status())
                        null
                    }
                } else null

                if (attachmentCloud != null && attachmentCloud.text.isNotBlank()) {
                    // Direct visual attachment Cloud answers previously bypassed the
                    // same source-claim validator used by capacity escalation. Keep the
                    // stabilized KB ownership untouched: only the general KB-OFF path
                    // uses this validator here.
                    val attachmentCloudAccepted = if (!anyKnowledgePackSelectedAtTurnStart) {
                        validateAcceptedAnswer(
                            query = query,
                            answer = attachmentCloud.text,
                            provider = Provider.CLOUD,
                            attachments = attachments,
                            history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                        )
                    } else true
                    if (attachmentCloudAccepted) {
                        chatStore.append("user", query)
                        chatStore.append("assistant", attachmentCloud.text)
                        notifyAcceptedTurn(query, attachmentCloud.text, Provider.CLOUD)
                        localModelManager?.closeSession()
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        return attachmentCloud.copy(elapsedMs = elapsed, sourceIds = turnSourceIds)
                    }
                    Log.w(TAG, "ATTACHMENT_VISION_CLOUD_REJECTED reason=source_validation")
                }

                runtimeHealth.cloudFailure("attachment_vision_no_answer", groqProvider.status())
                // Offline (or cloud failed): the phone's own eyes. Meta DETR / CLIP / DINOv2 + OCR
                // describe the attached photo and Llama answers from that description.
                localMetaAttachmentAnswer(query, evidence, attachments, attachedVisionBitmaps.firstOrNull(), onLocalDelta, started, turnSourceIds)
                    ?.let { return it }
                if (!attachmentHasLocalEvidence) {
                    localModelManager?.closeSession()
                    return TurnResult(
                        text = if (canAttemptCloud()) {
                            "I couldn't inspect the attached image reliably. Please try the attachment again."
                        } else {
                            "I couldn't extract enough usable evidence from this attachment on-device. Offline I can read visible text, barcodes, and locally recognized objects, but this attachment needs richer visual analysis."
                        },
                        provider = Provider.NONE,
                        elapsedMs = System.currentTimeMillis() - started,
                        streamedLocally = false,
                        sourceIds = turnSourceIds
                    )
                }
                Log.w(
                    TAG,
                    "ATTACHMENT_VISION_CLOUD_FAILED_LOCAL_FALLBACK evidenceChars=${evidence.length} " +
                        "sources=${turnSourceIds.joinToString(",")}"
                )
                return null
            }
            handleAttachmentVisionTurn()?.let { return@withLock it }
            // ── End attachment vision path ───────────────────────────────────

            // Capability preflight was resolved before provider selection so it can
            // also govern Live Camera turns. E5 remains the primary classifier; a
            // deterministic fail-safe is used only when semantic orchestration fails.
            val hasVerifiedGrounding = attachments.any { it.attributes["grounding_required"] == "true" }
            if (selectedKnowledgeProvider?.isSelected() == true) {
                Log.i(
                    TAG,
                    "KB_CLOUD_POLICY_REV V3_2_SOP_GROUNDED_CLOUD selected=true grounded=$hasVerifiedGrounding"
                )
            }

            // V3 selected-KB applicability may determine that the current facts do
            // not yet identify a safe source record. In that case ask the one
            // clarification produced by the detached record selector instead of
            // searching the web or inventing a local protocol.
            val kbClarification = selectedKnowledgeProvider?.clarificationForTurn(query).orEmpty()
            val kbSelectorTechnicalFailure =
                selectedKnowledgeProvider?.selectorTechnicalFailureForTurn(query) == true

            // A technical selector failure is not a clinical NEEDS_INFO decision. Do
            // not write a fake clarification into the Notebook/history. Local gets two
            // compact attempts and cloud gets one source-bounded selector fallback
            // before this branch can be reached.
            if (selectedKnowledgeProvider?.isSelected() == true &&
                !hasVerifiedGrounding && !kbCapabilityEscapeExternal && kbSelectorTechnicalFailure) {
                val retryMessage = kbClarification.ifBlank {
                    "I couldn't complete the SOP applicability check for that turn. Please try the same clinical detail again."
                }
                Log.w(
                    TAG,
                    "MODE_ROUTE mode=AUDIO_ONLY primary=KB_SELECTOR_TECHNICAL_FAILURE " +
                        "rev=V3_2_CONTEXT_APPLICABILITY"
                )

                /*
                 * Conversation contract restore: the user actually made this turn,
                 * so it must not disappear from AndroConversationStore merely because
                 * the detached KB selector failed. Persist the visible user/error pair
                 * so native conversation recovery retains continuity, but DO NOT notify
                 * Notebook/memory observers because this was a technical failure rather
                 * than accepted clinical reasoning.
                 */
                chatStore.append("user", query)
                chatStore.append("assistant", retryMessage)
                localModelManager?.closeSession()
                Log.i(
                    TAG,
                    "KB_TECHNICAL_TURN_COMMITTED rev=V3_3_CONTRACT_RESTORE " +
                        "history=true observers=false"
                )

                return@withLock TurnResult(
                    text = retryMessage,
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            if (selectedKnowledgeProvider?.isSelected() == true &&
                !hasVerifiedGrounding && !kbCapabilityEscapeExternal && kbClarification.isNotBlank()) {
                Log.i(TAG, "MODE_ROUTE mode=AUDIO_ONLY primary=KB_CLARIFICATION rev=V3_2_CONTEXT_APPLICABILITY")
                chatStore.append("user", query)
                chatStore.append("assistant", kbClarification)
                notifyAcceptedTurn(query, kbClarification, Provider.LOCAL)
                val elapsed = System.currentTimeMillis() - started
                runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                return@withLock TurnResult(
                    text = kbClarification,
                    provider = Provider.LOCAL,
                    elapsedMs = elapsed,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            // V3.1 selected-KB fail-closed contract: if no source record passed
            // applicability, do not leave the approved pack and do not ask unrestricted
            // Local/Cloud models to invent a protocol.  The selector normally supplies
            // one clarification; this fallback covers DB/selector runtime failure.
            if (selectedKnowledgeProvider?.isSelected() == true && !hasVerifiedGrounding && !kbCapabilityEscapeExternal) {
                val safeClarification = kbClarification.ifBlank {
                    "I can't safely identify the applicable SOP from the current details. Please add the key clinical finding or stage."
                }
                Log.w(
                    TAG,
                    "MODE_ROUTE mode=AUDIO_ONLY primary=KB_FAIL_CLOSED " +
                        "rev=V3_2_CONTEXT_APPLICABILITY reason=no_applicable_record"
                )
                chatStore.append("user", query)
                chatStore.append("assistant", safeClarification)
                notifyAcceptedTurn(query, safeClarification, Provider.LOCAL)
                val elapsed = System.currentTimeMillis() - started
                runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                return@withLock TurnResult(
                    text = safeClarification,
                    provider = Provider.LOCAL,
                    elapsedMs = elapsed,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            // Generic selected knowledge packs are source authorities too.  A
            // retrieval miss/unavailable pack must never fall through to generic Local
            // knowledge or a web answer while the UI still says KB is selected.
            if (genericKnowledgeSelected && !hasVerifiedGrounding && !kbCapabilityEscapeExternal) {
                val packName = genericLookupState?.displayName?.ifBlank { "Selected Knowledge Base" }
                    ?: "Selected Knowledge Base"
                val message = if (genericLookupState?.ready == false) {
                    "The selected Knowledge Base is not available for this turn. Please reopen the Knowledge Base or retry after it finishes loading."
                } else {
                    "I couldn't find sufficiently relevant evidence in the selected Knowledge Base for that question. Please rephrase the question, or turn the knowledge base off (tap the KB line at the top) to ask outside its sources."
                }
                Log.w(
                    TAG,
                    "MODE_ROUTE mode=AUDIO_ONLY primary=GENERIC_KB_FAIL_CLOSED pack=${packName.replace('|', ' ').take(80)} " +
                        "attempted=${genericLookupState?.attempted == true} ready=${genericLookupState?.ready == true} " +
                        "confident=${genericLookupState?.confident == true} top=${genericLookupState?.topSimilarity ?: 0f}"
                )
                chatStore.append("user", query)
                chatStore.append("assistant", message)
                // This is a durable user-visible KB retrieval failure, not accepted
                // knowledge. Keep it in conversation history for continuity but do not
                // teach Notebook/memory observers that the failure text is factual.
                localModelManager?.closeSession()
                val elapsed = System.currentTimeMillis() - started
                runtimeHealth.turnAccepted(Provider.NONE, elapsed, groqProvider.status())
                return@withLock TurnResult(
                    text = message,
                    provider = Provider.NONE,
                    elapsedMs = elapsed,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            // Other verified-grounding providers may still use their existing
            // external-capability fallback. Selected ANM KB turns are handled above.
            if (verifiedGroundingRequired && !hasVerifiedGrounding) {
                Log.w(TAG, "MODE_ROUTE mode=AUDIO_ONLY primary=CLOUD_KB_SEARCH reason=verified_grounding_missing")
                val groundedCloud = if (canAttemptCloud()) {
                    try {
                        withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) {
                            runExternalCapabilities(
                                query = query,
                                evidence = evidence,
                                safetyCritical = safetyEscalationRequired
                            )
                        }
                    }
                    catch (te: TimeoutCancellationException) { groqProvider.cancelActiveCall(); null }
                    catch (ce: CancellationException) { groqProvider.cancelActiveCall(); throw ce }
                    catch (t: Throwable) { Log.w(TAG, "GROUNDING_ESCALATION_FAILED ${t.message}"); null }
                } else null
                if (groundedCloud != null && groundedCloud.text.isNotBlank()) {
                    chatStore.append("user", query)
                    chatStore.append("assistant", groundedCloud.text)
                    notifyAcceptedTurn(query, groundedCloud.text, Provider.CLOUD)
                    localModelManager?.closeSession()
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                    return@withLock groundedCloud.copy(elapsedMs = elapsed, sourceIds = turnSourceIds)
                }
                runtimeHealth.cloudFailure("verified_grounding_unavailable", groqProvider.status())
                localModelManager?.closeSession()
                return@withLock TurnResult(
                    text = "I can't access verified guidance for that right now. Please use the approved SOP or a qualified professional.",
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            // Camera is OFF and this turn semantically appears visual.  Do not require
            // a camera when the turn is already grounded in a user document/attachment:
            // semantic visual classification can false-positive on document language.
            // Attachment-specific visual requests were already handled by
            // handleAttachmentVisionTurn() above; remaining document turns must continue
            // through Local-first text grounding and the normal Cloud fallback path.
            val attachmentEvidenceAvailable = attachments.any { attachment ->
                attachment.text.isNotBlank() &&
                    (attachment.attributes["user_attachment"] == "true" ||
                        attachment.attributes["attachment_recall"] == "true")
            }
            if (visualRequired && attachmentEvidenceAvailable) {
                Log.i(
                    TAG,
                    "VISUAL_REQUIREMENT_SUPPRESSED reason=attachment_evidence_available " +
                        "sources=${turnSourceIds.joinToString(",")}"
                )
            }
            if (visualRequired &&
                !attachmentEvidenceAvailable &&
                selectedKnowledgeProvider?.isSelected() != true &&
                turnAttachments.none { it.kind == Phase1AttachmentKind.IMAGE && it.visionBitmaps.isNotEmpty() }
            ) {
                Log.i(TAG, "MODE_ROUTE mode=AUDIO_ONLY primary=USER_ACTION reason=visual_capability_missing")
                localModelManager?.closeSession()
                return@withLock TurnResult(
                    text = "I need to see that to answer reliably. Turn on Live Camera or attach a photo, then ask me again.",
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = turnSourceIds
                )
            }

            if (visualRequired && selectedKnowledgeProvider?.isSelected() == true) {
                Log.i(TAG, "KB_VISUAL_ROUTE_SUPPRESSED mode=AUDIO_ONLY reason=selected_kb_text_turn")
            }

            // Fresh/live information owns the turn. If the same request is also
            // safety-critical, Compound Mini keeps its tools but receives the safety
            // constraint as part of the provider contract.
            if (externalRequired) {
                Log.i(
                    TAG,
                    "MODE_ROUTE mode=AUDIO_ONLY primary=CLOUD_EXTERNAL safety=$safetyEscalationRequired build=live-web-2026-10-02"
                )
                val external = if (groqProvider.canAttemptExternalRequest() && networkCanReachInternet()) {
                    try {
                        withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) {
                            runExternalCapabilities(
                                query,
                                evidence,
                                safetyCritical = safetyEscalationRequired,
                                groundedKnowledge = (selectedKnowledgeProvider?.isSelected() == true && hasVerifiedGrounding) || genericKnowledgeGrounding
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall(); null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "EXTERNAL_CAPABILITY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                } else null
                if (external != null && external.text.isNotBlank()) {
                    chatStore.append("user", query)
                    chatStore.append("assistant", external.text)
                    notifyAcceptedTurn(query, external.text, Provider.CLOUD)
                    localModelManager?.closeSession()
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                    val freshnessSources = listOf(
                        "freshness:$freshnessNeed",
                        "freshness_runtime_year:${Calendar.getInstance().get(Calendar.YEAR)}"
                    )
                    return@withLock external.copy(elapsedMs = elapsed, sourceIds = turnSourceIds + freshnessSources)
                }
                runtimeHealth.cloudFailure("external_capability_unavailable", groqProvider.status())

                // Web search failed: no answer from the online AI's memory (it was confidently out of
                // date, e.g. "SpaceX has no AI product"); the honest reply below offers Check online.

                // Not reachable online: honest "current information unavailable" reply or a
                // clearly qualified offline answer (moved out of chat(): method-size limit).
                return@withLock freshnessUnavailableTurn(
                    query, started, turnSourceIds, freshnessNeed, semanticCapabilityFailure,
                    safetyEscalationRequired, sourceGroundedTurn, acceptedHistoryForDecision
                )
            }

            // High-risk procedural requests without verified local SOP evidence use
            // the dedicated safety-constrained cloud prompt. When the user explicitly
            // selected ANM KB and strong evidence was retrieved, let the grounded local
            // workflow run first; it is validated before speech and may be cloud-checked
            // after generation without destroying offline capability.
            if (safetyEscalationRequired && !hasVerifiedGrounding) {
                Log.w(TAG, "MODE_ROUTE mode=AUDIO_ONLY primary=CLOUD_SAFETY")
                val safeCloud = if (canAttemptCloud()) {
                    try { withTimeout(CLOUD_TURN_TIMEOUT_MS) { runSafetyCloud(query, evidence) } }
                    catch (te: TimeoutCancellationException) { groqProvider.cancelActiveCall(); null }
                    catch (ce: CancellationException) { groqProvider.cancelActiveCall(); throw ce }
                    catch (t: Throwable) { Log.w(TAG, "SAFETY_ESCALATION_FAILED ${t.message}"); null }
                } else null
                if (safeCloud != null && safeCloud.text.isNotBlank()) {
                    chatStore.append("user", query)
                    chatStore.append("assistant", safeCloud.text)
                    notifyAcceptedTurn(query, safeCloud.text, Provider.CLOUD)
                    localModelManager?.closeSession()
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                    return@withLock safeCloud.copy(elapsedMs = elapsed, sourceIds = turnSourceIds)
                }
                runtimeHealth.cloudFailure("safety_escalation_unavailable", groqProvider.status())
                return@withLock safetyFallbackTurn(query, evidence, onLocalDelta, started, turnSourceIds)
            }

            // Balanced Cloud First affects only ordinary ungrounded general answering.
            // Hard freshness uses the stricter external/tool branch above; selected KB,
            // attachments, visual/AR and safety retain their existing ownership.
            // A Cloud failure never blocks a locally answerable turn.
            if (answerPolicyDecision.cloudPreferred && !externalRequired && !safetyEscalationRequired &&
                !sourceGroundedTurn && turnAttachments.isEmpty() && !visualRequired
            ) {
                // Moved to a helper: chat() is at the JVM method-size limit.
                runCloudPreferredTurn(query, evidence, answerPolicyDecision, started, turnSourceIds)?.let { return@withLock it }
            }

            Log.i(
                TAG,
                "MODE_ROUTE mode=AUDIO_ONLY primary=LOCAL evidence=${attachments.isNotEmpty()} " +
                    "sources=${attachments.joinToString(",") { it.sourceId }}"
            )

            // Match AndroLLM's ordinary voice contract: resident-KV local chat may
            // stream clean model deltas immediately. Transient Notebook/context text is
            // not source grounding and must not suppress streaming. Only turns that
            // genuinely require whole-answer validation remain buffered.
            val liveLocalSpeechAllowed = !sourceGroundedTurn &&
                turnAttachments.isEmpty() &&
                !factualVerificationRequired &&
                !visualRequired &&
                // Ambiguous freshness turns are buffered until the detached planner's
                // Local decision and post-Local sufficiency gate have both completed.
                !plannerAttempted

            // Adaptive ordinary-answer budget. This is semantic answer-shape sizing, not
            // topic routing: narrow follow-ups should not consume a 768-token budget,
            // while explicit detailed requests retain the full budget. Grounded/visual
            // flows keep their existing independent budgets.
            val adaptiveLocalOutputTokens: Int? = if (!sourceGroundedTurn &&
                turnAttachments.isEmpty() && !visualRequired && !safetyEscalationRequired
            ) {
                if (explicitDetailedResponseRequested(query)) {
                    // Explicit response-shape instructions are stronger than a fuzzy
                    // embedding classifier. This is domain-neutral and does not inspect
                    // the subject of the user's question.
                    1_024
                } else {
                    try {
                        when (semanticCapabilityProvider?.classifyResponseScope(query, acceptedHistoryForDecision)?.scope) {
                            SemanticResponseScope.SHORT -> 320
                            SemanticResponseScope.NORMAL -> 640
                            SemanticResponseScope.DETAILED -> 1_024
                            null -> 640
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "RESPONSE_SCOPE_FAILED ${t.javaClass.simpleName}:${t.message}")
                        640
                    }
                }
            } else null
            if (adaptiveLocalOutputTokens != null) {
                Log.i(TAG, "LOCAL_OUTPUT_BUDGET tokens=$adaptiveLocalOutputTokens mode=semantic_scope")
            }
            Log.i(
                TAG,
                "LOCAL_SPEECH_POLICY streamingAllowed=$liveLocalSpeechAllowed " +
                    "grounded=${!liveLocalSpeechAllowed} evidenceChars=${evidence.length}"
            )

            val localDeltaOwner: ((String) -> Unit)? = if (onLocalDelta == null && onLocalSpeechDelta == null) {
                null
            } else { delta ->
                onLocalDelta?.invoke(delta)
                if (liveLocalSpeechAllowed) onLocalSpeechDelta?.invoke(delta)
            }

            val genericKbIntent = attachments.firstOrNull {
                it.attributes["grounding_required"] == "true" && it.attributes["knowledge_pack"] == "generic"
            }?.attributes?.get("kb_query_intent")
            val groundedLocalOutputTokens: Int? = if (sourceGroundedTurn && genericKbIntent != null) {
                when (genericKbIntent) {
                    // Keep enough answer headroom for real SOP/manual use cases while
                    // still preventing the runaway multi-thousand-character generations
                    // seen in the comparison failure trace.
                    SemanticQueryIntent.PROCEDURE.name -> 1_280
                    SemanticQueryIntent.COMPARISON.name -> 1_024
                    SemanticQueryIntent.ENUMERATION.name -> 960
                    else -> 896
                }
            } else null
            if (groundedLocalOutputTokens != null) {
                Log.i(TAG, "LOCAL_OUTPUT_BUDGET tokens=$groundedLocalOutputTokens mode=compact_rag")
            }


            suspend fun localAttempt(retryVariant: Boolean = false): TurnResult? = try {
                // Do NOT impose a fixed whole-answer wall-clock timeout here.
                // The transplanted AndroLLM core owns first-token and inter-token
                // stall watchdogs, so a healthy long answer (recipes, detailed
                // troubleshooting, KB procedures) may continue while callbacks make
                // progress. A true native stall is still actively cancelled by core.
                runLocal(
                    query = query,
                    evidence = evidence,
                    attachments = attachments,
                    onLocalDelta = localDeltaOwner,
                    retryVariant = retryVariant,
                    livePresentationAllowed = liveLocalSpeechAllowed,
                    maxOutputTokenOverride = if (retryVariant && groundedLocalOutputTokens != null) {
                        minOf(groundedLocalOutputTokens, 768)
                    } else groundedLocalOutputTokens ?: adaptiveLocalOutputTokens,
                )
            } catch (ce: CancellationException) {
                // Never synchronously call LiteRT from the router/UI cancellation path.
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "ANDROLLM_LOCAL_FAILURE ${t.javaClass.simpleName}: ${t.message}", t)
                try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                null
            }


            // Phase-1 local VLM ownership: when the user selected a model whose
            // LiteRT-LM vision executor initialized successfully, a standalone photo
            // is sent directly to that local model. No filename/topic allow-list and
            // no Internet dependency. If VLM inference fails, the existing offline
            // ML Kit OCR/object/barcode evidence + text-LLM path remains the fallback.
            val localVisionBitmap = localVlmAttachmentBitmap
            val localVlmAttemptedThisTurn = localVisionBitmap != null &&
                localModelManager?.isReadyForVision == true &&
                selectedKnowledgeProvider?.isSelected() != true

            // The answer phase is a separate function: with Android debug builds (-Xdebug spills every
            // variable) one chat() state machine exceeded the JVM 64 KB method limit.
            suspend fun answerTurn(): TurnResult {
                var local = when {
                    continueCloudEpisodeThisTurn -> {
                        Log.i(TAG, "ESCALATION_EPISODE_AUDIO_SKIP_LOCAL reason=${activeEscalationEpisode?.reason ?: "UNKNOWN"}")
                        null
                    }
                    localVisionBitmap != null &&
                        localModelManager?.isReadyForVision == true &&
                        selectedKnowledgeProvider?.isSelected() != true -> {
                        Log.i(
                            TAG,
                            "LOCAL_VLM_ROUTE primary=LOCAL_VLM modelVision=true " +
                                "image=${localVisionBitmap.width}x${localVisionBitmap.height} online=${canAttemptCloud()}"
                        )
                        try {
                            withTimeout(LOCAL_VLM_TURN_TIMEOUT_MS) {
                                runLocalVision(
                                    query = query,
                                    bitmap = localVisionBitmap,
                                    grounded = evidence.isNotBlank() || attachments.any { it.attributes["grounding_required"] == "true" }
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "LOCAL_VLM_TIMEOUT ms=$LOCAL_VLM_TURN_TIMEOUT_MS fallback=local_extraction")
                            try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                            null
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "LOCAL_VLM_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=local_extraction")
                            null
                        }
                    }
                    hierarchicalAttachmentPlan != null -> {
                        // Phase-2 whole-document analysis is Local-first both online and
                        // offline. The bounded AndroLLM map/reduce path gets the first answer
                        // opportunity; Cloud is used only if Local times out, fails validation,
                        // or cannot produce a useful source-grounded synthesis.
                        Log.i(
                            TAG,
                            "ATTACHMENT_WHOLE_ANALYSIS_ROUTE primary=LOCAL_HIERARCHICAL " +
                                "online=${canAttemptCloud()} " +
                                "coverageComplete=${hierarchicalAttachmentPlan.coverageComplete} " +
                                "sections=${hierarchicalAttachmentPlan.sections.size}"
                        )
                        try {
                            withTimeout(ATTACHMENT_HIERARCHICAL_TIMEOUT_MS) {
                                runHierarchicalAttachmentLocal(
                                    query = query,
                                    plan = hierarchicalAttachmentPlan,
                                    attachments = attachments,
                                    onLocalDelta = localDeltaOwner
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            Log.w(TAG, "ATTACHMENT_HIERARCHICAL_TIMEOUT ms=$ATTACHMENT_HIERARCHICAL_TIMEOUT_MS")
                            try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                            null
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "ATTACHMENT_HIERARCHICAL_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                            null
                        }
                    }
                    else -> localAttempt(retryVariant = false)
                }
                if (local == null && !continueCloudEpisodeThisTurn) {
                    val genericKbGrounded = attachments.any {
                        it.attributes["grounding_required"] == "true" &&
                            it.attributes["knowledge_pack"] == "generic"
                    }
                    val selectedKbGrounded = selectedKnowledgeProvider?.isSelected() == true && hasVerifiedGrounding
                    if (genericKbGrounded) {
                        // Generic knowledge packs are explicitly Local-first. A response that
                        // fails structural/grounding quality gets exactly one fresh detached
                        // grounded retry before any Cloud fallback, online or offline.
                        try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                        Log.w(TAG, "GENERIC_KB_LOCAL_RETRY reason=first_grounded_answer_rejected evidenceChars=${evidence.length}")
                        local = try {
                            runLocal(
                                query = query,
                                evidence = evidence,
                                attachments = attachments,
                                onLocalDelta = localDeltaOwner,
                                retryVariant = true,
                                livePresentationAllowed = false,
                                maxOutputTokenOverride = 640
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "GENERIC_KB_LOCAL_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    } else if (selectedKbGrounded) {
                        // Selected KB is source-owned and Local-first in BOTH answer modes.
                        // Give one independent grounded Local retry before any Cloud fallback,
                        // regardless of apparent network availability.
                        try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                        Log.w(TAG, "KB_LOCAL_GROUNDED_RETRY rev=V6_LOCAL_FIRST_PARITY evidenceChars=${evidence.length}")
                        local = try {
                            runLocal(
                                query = query,
                                evidence = evidence,
                                attachments = attachments,
                                onLocalDelta = localDeltaOwner,
                                retryVariant = true,
                                livePresentationAllowed = false
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "KB_LOCAL_GROUNDED_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    } else if (turnScopedAttachments.isNotEmpty() && (!localVlmAttemptedThisTurn || !canAttemptCloud())) {
                        // Text/Office attachments retain one evidence retry. For an online
                        // image/VLM turn, a failed Local VLM attempt escalates directly to
                        // Cloud instead of burning a second Local text-generation attempt.
                        // Offline keeps the extraction/text fallback so the app remains useful.
                        try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                        Log.w(
                            TAG,
                            "ATTACHMENT_LOCAL_EVIDENCE_RETRY evidenceChars=${evidence.length} " +
                                "sources=${turnSourceIds.joinToString(",")}"
                        )
                        local = try {
                            runLocal(
                                query = query,
                                evidence = evidence,
                                attachments = attachments,
                                onLocalDelta = localDeltaOwner,
                                retryVariant = true,
                                livePresentationAllowed = false
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "ATTACHMENT_LOCAL_EVIDENCE_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }
                    } else {
                        // Ordinary KB-OFF chat follows the actual AndroLLM recovery philosophy:
                        // a Local non-answer/blank/control failure gets ONE bounded detached
                        // plain-text regeneration before provider fallback, regardless of whether
                        // Cloud happens to be available. Cloud is recovery after Local exhaustion,
                        // not a shortcut around a recoverable Local turn. This is the important
                        // protection for normal procedural questions such as recipes/how-to.
                        try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                        val acceptedHistory = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                        val retry = try {
                            localModelManager?.inferPlainTextRetry(
                                userQueryOnly = query,
                                history = acceptedHistory,
                                reason = "first_attempt_unusable_or_non_answer"
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "ANDROLLM_PLAIN_TEXT_RETRY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }

                        if (retry != null && retry.completed && retry.text.isNotBlank()) {
                            var retryText = FixSightOutputSanitizer.compactForLive(
                                FixSightOutputSanitizer.sanitize(retry.text)
                            ).trim()
                            var retryStructuralFailure = structuralOutputFailure(retryText)
                            if (retryStructuralFailure == "incomplete_tail") {
                                val recovered = recoverCompleteGroundedPrefix(retryText)
                                if (recovered != null) {
                                    Log.w(TAG, "LOCAL_RETRY_TAIL_RECOVERED from=${retryText.length} to=${recovered.length}")
                                    retryText = recovered
                                    retryStructuralFailure = structuralOutputFailure(retryText)
                                }
                            }
                            val retryRepetitionFailure = if (retryText.isNotBlank() && !repeatOrRestatementRequested(query)) {
                                crossTurnRepetitionReason(retryText, acceptedHistory)
                                    ?: duplicateAssistantQuestionReason(retryText, acceptedHistory)
                            } else null
                            val retryNonAnswerFailure = ordinaryNonAnswerReason(query, retryText)
                            // Deterministic answer-shape checks own acceptance. Do not use
                            // embedding similarity as a binary quality judge for a completed
                            // ordinary Local answer; near-tied cosine scores previously caused
                            // valid answers to be discarded.
                            val retryUsable = retryText.isNotBlank() &&
                                retryStructuralFailure == null &&
                                retryRepetitionFailure == null &&
                                retryNonAnswerFailure == null &&
                                !isObviousUserEcho(query, retryText)

                            if (retryUsable) {
                                Log.i(TAG, "ANDROLLM_PLAIN_TEXT_RETRY_ACCEPTED chars=${retryText.length} cloudAvailable=${canAttemptCloud()}")
                                local = TurnResult(
                                    text = retryText,
                                    provider = Provider.LOCAL,
                                    elapsedMs = retry.inferenceMs,
                                    streamedLocally = false
                                )
                            } else {
                                Log.w(
                                    TAG,
                                    "ANDROLLM_PLAIN_TEXT_RETRY_REJECTED structural=${retryStructuralFailure ?: "none"} " +
                                        "repetition=${retryRepetitionFailure ?: "none"} " +
                                        "nonAnswer=${retryNonAnswerFailure ?: "none"} chars=${retryText.length}"
                                )
                            }
                        }
                    }
                }
                if (local == null) {
                    Log.w(TAG, "ANDROLLM_LOCAL_EXHAUSTED")
                    val lm = localModelManager
                    if (lm != null && lm.isReady && lm.isTextWarm && !lm.permanentlyDisabled) {
                        runtimeHealth.localAnswerRejected("answer_not_accepted")
                    } else {
                        runtimeHealth.localFailure("engine_or_generation_unavailable")
                    }
                    try { onLocalAbort?.invoke() } catch (_: Throwable) {}
                }

                // Capability planning already happened in a separate quiet pass before
                // normal Local generation. The visible Local answer stream is plain text only.

                // Selected-KB answers that already passed local SOP grounding stay local.
                // Normal source-grounded attachment answers are also owned by their source
                // validator. Only ordinary KB-OFF objective factual questions use the
                // generic factual confidence/verification path below.
                if (local != null && local.text.isNotBlank() && factualVerificationRequired) {
                    Log.i(
                        TAG,
                        "FACTUAL_LOCAL_DRAFT_READY chars=${local.text.length} cloud=${canAttemptCloud()}"
                    )

                    var cloudVerifiedLocal = false
                    if (canAttemptCloud()) {
                        val verification = try {
                            withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                                groqProvider.verifyFactualAnswer(
                                    messages = chatStore.historyForCloud(currentQuery = query),
                                    currentQuery = query,
                                    localDraft = local.text
                                )
                            }
                        } catch (te: TimeoutCancellationException) {
                            groqProvider.cancelActiveCall()
                            Log.w(TAG, "FACTUAL_CLOUD_VERIFY_TIMEOUT ms=$CLOUD_TURN_TIMEOUT_MS")
                            null
                        } catch (ce: CancellationException) {
                            groqProvider.cancelActiveCall()
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "FACTUAL_CLOUD_VERIFY_FAILED ${t.javaClass.simpleName}: ${t.message}")
                            null
                        }

                        when {
                            verification?.localAccepted == true -> {
                                cloudVerifiedLocal = true
                                Log.i(TAG, "FACTUAL_CLOUD_VERIFY_ACCEPT local=true")
                            }
                            verification != null && verification.correctedAnswer.isNotBlank() -> {
                                val corrected = verification.correctedAnswer.trim()
                                Log.i(TAG, "FACTUAL_CLOUD_VERIFY_CORRECTED direct=true chars=${corrected.length}")
                                // The resident Local KV contains the rejected draft. Retire it
                                // before committing the authoritative Cloud correction.
                                localModelManager?.closeSession()
                                chatStore.append("user", query)
                                chatStore.append("assistant", corrected)
                                notifyAcceptedTurn(query, corrected, Provider.CLOUD)
                                val elapsed = System.currentTimeMillis() - started
                                runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                                return TurnResult(
                                    text = corrected,
                                    provider = Provider.CLOUD,
                                    elapsedMs = elapsed,
                                    streamedLocally = false,
                                    visualObservation = "",
                                    sourceIds = turnSourceIds
                                )
                            }
                            else -> {
                                // The verifier itself failed. Do not blindly commit an objective
                                // factual draft just because Android says Internet exists. Fall
                                // through to the strict offline confidence gate; if that also
                                // cannot establish confidence, abstain.
                                Log.w(TAG, "FACTUAL_CLOUD_VERIFY_UNAVAILABLE fallback=offline_confidence")
                            }
                        }
                    }

                    // If the strong Cloud verifier explicitly accepted the Local draft, no
                    // weaker Local self-check is needed. Otherwise Cloud was unavailable or
                    // its verifier failed, so use a detached AndroLLM confidence check with a
                    // deliberately strict abstention policy.
                    val offlineConfident = if (cloudVerifiedLocal) true else try {
                        withTimeout(LOCAL_PLAIN_RETRY_TIMEOUT_MS) {
                            localModelManager?.isOfflineFactualDraftConfident(
                                userQueryOnly = query,
                                localDraft = local.text,
                                history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                            ) ?: false
                        }
                    } catch (te: TimeoutCancellationException) {
                        Log.w(TAG, "FACTUAL_OFFLINE_CONFIDENCE_TIMEOUT ms=$LOCAL_PLAIN_RETRY_TIMEOUT_MS")
                        false
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "FACTUAL_OFFLINE_CONFIDENCE_FAILED ${t.javaClass.simpleName}: ${t.message}")
                        false
                    }

                    if (!offlineConfident) {
                        Log.w(TAG, "FACTUAL_OFFLINE_ABSTAIN reason=confidence_not_established")
                        localModelManager?.closeSession()
                        return TurnResult(
                            text = "I’m not confident enough to answer that factual question reliably while offline.",
                            provider = Provider.NONE,
                            elapsedMs = System.currentTimeMillis() - started,
                            streamedLocally = false,
                            visualObservation = "",
                            sourceIds = turnSourceIds
                        )
                    }
                    Log.i(TAG, "FACTUAL_OFFLINE_CONFIDENCE_ACCEPT")
                }

                // Capacity/capability escalation for bounded documents. A useful Local
                // representative answer is retained as fallback, but online Cloud gets first
                // chance when the user's broad request exceeds the locally inspected coverage.
                if (local != null && local.text.isNotBlank() && attachmentEscalation != null && canAttemptCloud()) {
                    val (attachment, decision) = attachmentEscalation
                    Log.i(
                        TAG,
                        "ATTACHMENT_ESCALATION_BEGIN rev=V11_0_CAPABILITY_CLOUD " +
                            "category=${decision.category} reason=${decision.reason} localFallbackChars=${local.text.length}"
                    )
                    val escalated = try {
                        withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                            runAttachmentCapacityCloud(
                                query = query,
                                turnAttachments = turnAttachments,
                                fallbackEvidence = evidence,
                                decision = decision,
                                safetyCritical = safetyEscalationRequired
                            )
                        }
                    } catch (te: TimeoutCancellationException) {
                        groqProvider.cancelActiveCall()
                        Log.w(TAG, "ATTACHMENT_ESCALATION_TIMEOUT ms=$CLOUD_TURN_TIMEOUT_MS fallback=local")
                        null
                    } catch (ce: CancellationException) {
                        groqProvider.cancelActiveCall(); throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "ATTACHMENT_ESCALATION_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=local")
                        null
                    }
                    if (escalated != null && escalated.text.isNotBlank()) {
                        val escalationAccepted = validateAcceptedAnswer(
                            query = query,
                            answer = escalated.text,
                            provider = Provider.CLOUD,
                            attachments = attachments,
                            history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                        )
                        if (escalationAccepted) {
                            Log.i(TAG, "ATTACHMENT_ESCALATION_SUCCESS direct=true validated=true category=${decision.category}")
                            localModelManager?.closeSession()
                            chatStore.append("user", query)
                            chatStore.append("assistant", escalated.text)
                            notifyAcceptedTurn(query, escalated.text, Provider.CLOUD)
                            val elapsed = System.currentTimeMillis() - started
                            runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                            val escalationSources = listOf(
                                "attachment_escalation:${decision.category}",
                                "attachment_coverage:${if (decision.coverageComplete) "complete" else "representative"}",
                                "attachment_reason:${decision.reason.take(120)}"
                            )
                            return escalated.copy(
                                elapsedMs = elapsed,
                                visualObservation = "",
                                sourceIds = (turnSourceIds + escalationSources).distinct()
                            )
                        } else {
                            Log.w(TAG, "ATTACHMENT_ESCALATION_REJECTED reason=source_validation category=${decision.category} fallback=local")
                        }
                    }
                    Log.w(
                        TAG,
                        "ATTACHMENT_ESCALATION_LOCAL_FALLBACK category=${decision.category} " +
                            "reason=${decision.reason} name=${attachment.displayName}"
                    )
                }

                // Local answer passed all applicable gates — commit and return. If the
                // attachment engine knows a broad request was answered from representative
                // rather than complete source coverage (offline, or Cloud escalation failed),
                // say so explicitly instead of presenting the Local synthesis as exhaustive.
                if (local != null && local.text.isNotBlank()) {
                    val representativeCoverage =
                        (hierarchicalAttachmentPlan != null && !hierarchicalAttachmentPlan.coverageComplete) ||
                            multiAttachmentComparison
                    if (representativeCoverage) {
                        val note = "\n\nCoverage note: this answer is based on representative extracted sections, not an exhaustive review of every part of all attached source material."
                        if (!local.text.contains("Coverage note:", ignoreCase = true)) {
                            local = local.copy(text = local.text.trimEnd() + note)
                        }
                        Log.w(TAG, "ATTACHMENT_LOCAL_REPRESENTATIVE_COVERAGE disclosed=true")
                    }
                    Log.i(TAG, "LOCAL_OUTPUT_ACCEPTED chars=${local.text.length}")
                    // Session ownership is decided inside runLocal(), where we know
                    // whether evidence was ACTUALLY used by the Local model. Do not infer
                    // grounded/ephemeral ownership from the outer `evidence` string: it can
                    // contain Notebook/troubleshooting/visual-context text that never entered
                    // the resident Local conversation, and doing so destroys KV reuse on
                    // otherwise ordinary KB-OFF turns.
                    chatStore.append("user", query)
                    chatStore.append("assistant", local.text)
                    notifyAcceptedTurn(query, local.text, Provider.LOCAL)
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                    val verificationMarker = if (answerPolicyDecision.offerOnlineVerification && !sourceGroundedTurn) {
                        listOf("verification:optional")
                    } else emptyList()
                    val committedSourceIds = if (genericKnowledgeGrounding && local.sourceIds.isNotEmpty()) {
                        turnSourceIds.filterNot { it.startsWith("kbref:") } + local.sourceIds
                    } else {
                        turnSourceIds
                    }
                    return local.copy(
                        elapsedMs = elapsed,
                        streamedLocally = local.streamedLocally,
                        visualObservation = "",
                        sourceIds = (committedSourceIds + verificationMarker).distinct()
                    )
                }

                // A selected Generic Knowledge Pack remains the source authority after
                // Local exhaustion.  At this point the first grounded Local attempt and its
                // independent retry have both failed, so Cloud may synthesize ONLY from the
                // same selected evidence packet.  The generic KB validator is applied to
                // the Cloud answer before it can be committed.
                if (local == null && genericKnowledgeSelected && genericKnowledgeGrounding) {
                    Log.w(TAG, "GENERIC_KB_LOCAL_SYNTHESIS_UNAVAILABLE cloudFallback=grounded_only")
                }

                // Cloud fallback. User-document turns get a wider query-focused source
                // view than the Local model's bounded context. Retained PDF/Office images
                // are used only after Local fails; native image routing remains unchanged.
                val attachmentCloudEvidence = if (turnAttachments.isNotEmpty()) {
                    val parts = mutableListOf<String>()
                    val balancedPerSource = if (turnAttachments.size > 1) {
                        (14_000 / turnAttachments.size).coerceIn(3_000, 7_000)
                    } else 14_000
                    for (attachment in turnAttachments) {
                        val part = attachmentContextProvider?.cloudEvidenceForTurn(
                            attachment = attachment,
                            query = query,
                            maxChars = balancedPerSource,
                            broadSourceCoverage = multiAttachmentComparison
                        ).orEmpty()
                        if (part.isNotBlank()) parts += part
                    }
                    parts.joinToString("\n\n").take(14_000)
                } else ""
                val cloudEvidence = when {
                    attachmentCloudEvidence.isBlank() -> evidence
                    selectedKnowledgeProvider?.isSelected() == true -> evidence
                    else -> attachmentCloudEvidence
                }
                val originalFallbackBitmaps = turnAttachments
                    .flatMap { it.visionBitmaps }
                    .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
                    .distinctBy { System.identityHashCode(it) }
                    .take(4)
                val remainingRecoverySlots = (4 - originalFallbackBitmaps.size).coerceAtLeast(0)
                val temporaryRecoveryBitmaps = if (
                    selectedKnowledgeProvider?.isSelected() == true || remainingRecoverySlots == 0
                ) {
                    emptyList()
                } else {
                    decodeRecoveryBitmapsWithinSlots(turnAttachments, remainingRecoverySlots)
                }
                val documentFallbackBitmaps = (originalFallbackBitmaps + temporaryRecoveryBitmaps)
                    .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
                    .distinctBy { System.identityHashCode(it) }
                    .take(4)
                val hasEvidence = cloudEvidence.isNotBlank()
                val cloud = try { if (!canAttemptCloud()) {
                    Log.w(TAG, "CLOUD_FALLBACK_SKIPPED canAttempt=${canAttemptCloud()} network=${networkDiagnosticHint()} hasKey=${groqProvider.hasApiKey()} status=${groqProvider.status()}")
                    null
                } else try {
                    Log.i(TAG, "CLOUD_FALLBACK_BEGIN evidenceChars=${cloudEvidence.length} hasKB=$hasEvidence fallbackImages=${documentFallbackBitmaps.size}")
                    withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                        if ((selectedKnowledgeProvider?.isSelected() == true || genericKnowledgeSelected) && hasVerifiedGrounding) {
                            Log.i(
                                TAG,
                                "KB_CLOUD_FALLBACK mode=${if (genericKnowledgeSelected) "GENERIC_SOURCE_AUTHORITY" else "SOP_AUTHORITY"} " +
                                    "rev=V6_SOURCE_GROUNDED_CLOUD"
                            )
                            runGroundedKbCloud(
                                query,
                                cloudEvidence,
                                safetyCritical = safetyEscalationRequired,
                                genericKnowledge = genericKnowledgeSelected,
                                validationAttachments = attachments
                            )
                        } else if (documentFallbackBitmaps.isNotEmpty()) {
                            runCloudVision(
                                query = query,
                                evidence = cloudEvidence,
                                bitmaps = documentFallbackBitmaps,
                                detail = GroqChatProvider.VisionDetail.STANDARD,
                                safetyCritical = safetyEscalationRequired
                            )
                        } else {
                            runCloud(query, cloudEvidence, null)
                        }
                    }
                } catch (te: TimeoutCancellationException) {
                    Log.w(TAG, "CLOUD_FALLBACK_TIMEOUT ms=$CLOUD_TURN_TIMEOUT_MS")
                    groqProvider.cancelActiveCall(); null
                } catch (ce: CancellationException) {
                    groqProvider.cancelActiveCall(); throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "CLOUD_FALLBACK_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    null
                } } finally {
                    temporaryRecoveryBitmaps.forEach { bmp ->
                        runCatching { if (!bmp.isRecycled) bmp.recycle() }
                    }
                }

                if (cloud != null && cloud.text.isNotBlank()) {
                    val cloudForAcceptance = cloud
                    val groundedKbCloud =
                        (selectedKnowledgeProvider?.isSelected() == true || genericKnowledgeSelected) && hasVerifiedGrounding
                    val attachmentNeedsValidation = !groundedKbCloud && turnScopedAttachments.isNotEmpty() && hasEvidence
                    val cloudAccepted = if (attachmentNeedsValidation) {
                        validateAcceptedAnswer(
                            query = query,
                            answer = cloudForAcceptance.text,
                            provider = Provider.CLOUD,
                            attachments = attachments,
                            history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
                        )
                    } else true

                    if (cloudAccepted) {
                        Log.i(TAG, "CLOUD_FALLBACK_SUCCESS direct=true kbPassthrough=$groundedKbCloud attachmentValidated=$attachmentNeedsValidation chars=${cloudForAcceptance.text.length}")
                        chatStore.append("user", query)
                        chatStore.append("assistant", cloudForAcceptance.text)
                        notifyAcceptedTurn(query, cloudForAcceptance.text, Provider.CLOUD)
                        localModelManager?.closeSession()
                        val elapsed = System.currentTimeMillis() - started
                        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
                        val committedCloudSourceIds = if (genericKnowledgeSelected && cloudForAcceptance.sourceIds.isNotEmpty()) {
                            turnSourceIds.filterNot { it.startsWith("kbref:") } + cloudForAcceptance.sourceIds
                        } else {
                            turnSourceIds
                        }
                        return cloudForAcceptance.copy(
                            elapsedMs = elapsed,
                            visualObservation = "",
                            sourceIds = committedCloudSourceIds.distinct()
                        )
                    } else {
                        Log.w(TAG, "CLOUD_FALLBACK_REJECTED reason=source_validation")
                    }
                }

                runtimeHealth.cloudFailure("fallback_no_accepted_answer", groqProvider.status())
                return TurnResult(
                    text = when {
                        localModelManager?.isReady != true ->
                            "The on-phone AI (Meta Llama) is not ready yet, so I couldn't answer. Open Settings to see its status" +
                                (if (!groqProvider.hasApiKey()) ", or add a Meta API key there for online answers." else ".")
                        !groqProvider.hasApiKey() ->
                            "I couldn't write a reliable answer to that on this phone. Try asking it a little differently, or add a Meta API key in Settings for online answers."
                        groqProvider.status() == GroqProviderStatus.AUTH_FAILED ->
                            "The online Meta AI key was rejected, and the on-phone answer did not pass its checks. Please check the key in Settings."
                        groqProvider.status() == GroqProviderStatus.NETWORK_ERROR ->
                            "I couldn't reach the internet, and the on-phone answer did not pass its checks. Please try again, or ask it a little differently."
                        else -> "I couldn't complete that turn. Please try again."
                    },
                    provider = Provider.NONE,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    visualObservation = "",
                    sourceIds = turnSourceIds
                )
            }
            return@withLock answerTurn()
        }
    }


    private data class AttachmentVisionPlan(
        val visionTurnAttachments: List<Phase1UserAttachment>,
        val bitmaps: List<Bitmap>,
        val localVlmBitmap: Bitmap?,
        val unresolvedDocumentVisualRequest: Boolean
    )

    /**
     * Resolve the bounded visual evidence for an attachment turn outside chat() so the
     * coroutine state machine stays below the JVM 64 KiB method limit.
     *
     * All document pages remain addressable through AttachmentSessionContextProvider;
     * only the relevant page/image for the current turn is materialised into memory.
     */
    private suspend fun buildAttachmentVisionPlan(
        query: String,
        turnAttachments: List<Phase1UserAttachment>,
        contextAttachments: List<BaselineContextAttachment>
    ): AttachmentVisionPlan {
        val visualAttachments = turnAttachments.filter { attachment ->
            attachment.needsVision ||
                attachmentContextProvider?.queryNeedsVisualInspection(attachment, query) == true
        }

        val bitmaps = mutableListOf<Bitmap>()
        var unresolvedDocumentVisualRequest = false
        for (attachment in visualAttachments) {
            val resolved = attachmentContextProvider?.visionBitmapForTurn(attachment, query)
                ?: attachment.visionBitmaps.firstOrNull {
                    !it.isRecycled && it.width > 0 && it.height > 0
                }
            if (resolved != null && !resolved.isRecycled) {
                bitmaps += resolved
            } else if (
                attachment.kind == Phase1AttachmentKind.PDF_DOCUMENT &&
                attachmentContextProvider?.queryNeedsVisualInspection(attachment, query) == true
            ) {
                unresolvedDocumentVisualRequest = true
            }
        }

        val hasRecallContext = contextAttachments.any {
            it.attributes["attachment_recall"] == "true"
        }
        if (turnAttachments.isEmpty() && hasRecallContext) {
            val recalled = attachmentContextProvider?.recalledVisionBitmapForTurn(query)
            if (recalled != null && !recalled.isRecycled && recalled.width > 0 && recalled.height > 0) {
                bitmaps += recalled
            }
        }

        val firstUsable = bitmaps.firstOrNull {
            !it.isRecycled && it.width > 0 && it.height > 0
        }
        return AttachmentVisionPlan(
            visionTurnAttachments = visualAttachments,
            bitmaps = bitmaps,
            localVlmBitmap = firstUsable,
            unresolvedDocumentVisualRequest = unresolvedDocumentVisualRequest
        )
    }


    /**
     * Context rollover — P0-D fix.
     *
     * Called at the start of every turn. When the resident conversation's token
     * count approaches the effective limit, retire it so the next turn reseeds
     * from the most recent complete exchanges in chatStore.
     *
     * Uses the container-resolved model capacity (not the hardcoded 32,768).
     * The rollover threshold leaves headroom for output and system prompt.
     */
    private fun maybeRollNativeConversationForTokenBudget() {
        val lm = localModelManager ?: return
        val currentTokens = lm.getConversationTokenCount()
        if (currentTokens == 0) return // token count unavailable

        val effective = effectiveContextWindowTokens
        val threshold = (effective - RESERVED_OUTPUT_TOKENS - SYSTEM_PROMPT_TOKEN_ESTIMATE - CONTEXT_SAFETY_TOKENS)
            .coerceAtLeast(512)

        if (currentTokens >= threshold) {
            Log.w(TAG, "CONTEXT_ROLLOVER tokens=$currentTokens threshold=$threshold effective=$effective — retiring resident session")
            lm.closeSession()
            // The next ensureConversationForHistory call will reseed from chatStore
            // using historyTokenBudget, which is already pair-aware via sanitizeSeed.
        }
    }


    /**
     * Phase-2 offline whole-attachment analysis using AndroLLM ephemeral generation.
     * This path is attachment-only, KB-OFF, and never touches the resident public KV.
     */
    private suspend fun runHierarchicalAttachmentLocal(
        query: String,
        plan: AttachmentSummaryPlan,
        attachments: List<BaselineContextAttachment>,
        onLocalDelta: ((String) -> Unit)?
    ): TurnResult? {
        val lm = localModelManager ?: return null
        if (!lm.isReady || lm.permanentlyDisabled || !lm.isTextWarm) return null

        val acceptedHistory = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        val started = System.currentTimeMillis()
        val generated = lm.inferAttachmentHierarchicalSummary(
            plan = plan,
            userQueryOnly = query,
            history = acceptedHistory
        )
        if (!generated.completed || generated.text.isBlank()) {
            Log.w(TAG, "ATTACHMENT_HIERARCHICAL_REJECT reason=generation_failed error=${generated.error}")
            return null
        }

        if (containsPrivateContextLeak(generated.text) ||
            generated.text.contains("[Section digest", ignoreCase = true) ||
            generated.text.contains("[Private source digests]", ignoreCase = true)
        ) {
            Log.w(TAG, "ATTACHMENT_HIERARCHICAL_REJECT reason=private_context_leak")
            return null
        }

        val sanitized = FixSightOutputSanitizer.compactForLive(
            FixSightOutputSanitizer.sanitize(generated.text)
        ).trim()
        val acceptedText = stripLeadingUserEcho(query, sanitized)
        if (acceptedText.isBlank()) return null

        val structuralFailure = structuralOutputFailure(acceptedText, enforceNumericGlue = false)
        if (structuralFailure != null || isObviousUserEcho(query, acceptedText)) {
            Log.w(
                TAG,
                "ATTACHMENT_HIERARCHICAL_REJECT reason=${structuralFailure ?: "user_echo"} chars=${acceptedText.length}"
            )
            return null
        }

        val originalAttachment = attachments.firstOrNull {
            it.attributes["user_attachment"] == "true" &&
                it.attributes["turn_scoped"] == "true"
        } ?: attachments.firstOrNull { it.attributes["user_attachment"] == "true" }
            ?: return null

        val exactSections = buildString {
            plan.sections.forEachIndexed { index, section ->
                if (isNotEmpty()) append("\n\n")
                append("[Source section ").append(index + 1).append("]\n")
                append(section.trim())
            }
        }.take(28_000)

        val exactAttachment = BaselineContextAttachment(
            sourceId = "exact_hierarchical_attachment_grounding",
            text = exactSections,
            priority = 100,
            attributes = buildMap {
                putAll(originalAttachment.attributes)
                put("grounding_required", "true")
                put("exact_generation_evidence", "true")
                put("hierarchical_attachment_summary", "true")
                put("hierarchical_coverage_complete", plan.coverageComplete.toString())
            }
        )

        val acceptedByValidator = validateAcceptedAnswer(
            query = query,
            answer = acceptedText,
            provider = Provider.LOCAL,
            attachments = listOf(exactAttachment),
            history = acceptedHistory
        )
        if (!acceptedByValidator) {
            Log.w(TAG, "ATTACHMENT_HIERARCHICAL_REJECT reason=source_validator")
            return null
        }

        if (onLocalDelta != null && isSafeSpokenFragment(acceptedText)) {
            try {
                onLocalDelta.invoke(acceptedText)
            } catch (t: Throwable) {
                Log.w(TAG, "LOCAL_STREAM_DELTA_CONSUMER_FAILED ${t.message}")
            }
        }

        Log.i(
            TAG,
            "ATTACHMENT_HIERARCHICAL_ACCEPT chars=${acceptedText.length} " +
                "coverageComplete=${plan.coverageComplete} sourceChars=${plan.sourceChars}"
        )
        return TurnResult(
            text = acceptedText,
            provider = Provider.LOCAL,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = onLocalDelta != null
        )
    }

    /**
     * Local inference — single acceptance gate.
     *
     * Gate order:
     *   1. Basic validity (blank, private context leak, sanitizer)
     *   2. Structural/echo checks
     *   3. Semantic grounding validation only when evidence requires it
     *
     * If evidence is present, routes through inferTextGrounded (ephemeral conversation).
     * Otherwise routes through inferText (resident KV session).
     *
     * Ordinary local turns may stream clean deltas to the service-owned AndroLLM
     * SentenceAssembler/TTS queue while generation continues. Evidence-grounded and
     * factual-verification turns remain non-spoken until their stronger gate completes.
     * A null return means the local candidate was not usable for this turn.
     */
    /**
     * Provider-neutral AR localization parser. The vision model, not static Kotlin
     * intent rules, decides whether the user's current request is asking to locate
     * or highlight a visible target. When present, the same normalized result can
     * drive ARCore whether it came from the local VLM or cloud perception fallback.
     */
    /**
     * "Find / highlight X" that the phone's DETR, DINOv2 and CLIP could not locate: Meta SAM 3.1
     * (online, Meta key + internet only) segments X from the phrase. Null otherwise.
     */
    private suspend fun samOnlineLocalization(query: String, bitmap: Bitmap): Pair<ARIntent, DetectedObject>? {
        if (!MetaOnline.ready(context)) return null
        val target = MetaVisionMath.targetFromQuery(query).ifBlank { return null }.take(60)
        return try {
            val scaled = if (maxOf(bitmap.width, bitmap.height) > 1024) {
                val f = 1024f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
            } else bitmap
            val jpeg = java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            val boxes = withTimeoutOrNull(SAM_ONLINE_TIMEOUT_MS) { MetaOnline.api(context).sam("camera_find", jpeg, target) }.orEmpty()
            val best = boxes.maxByOrNull { it.confidence ?: 0f } ?: run {
                Log.i(TAG, "AR_SAM_ONLINE_NOT_FOUND target='$target'"); return null
            }
            val confidence = (best.confidence ?: 0.7f).coerceIn(0.5f, 0.95f)
            val line = MetaVisionMath.arLine(target, confidence, best.normalized())
            val parsed = parseArLocalizationObservation(line, bitmap.width, bitmap.height)
            if (parsed != null && isSpatialLocalizationTrustworthy(parsed.second)) {
                lastArLocalizationProvider = Provider.CLOUD
                Log.i(TAG, "AR_SAM_ONLINE_FOUND target='$target' boxes=${boxes.size} line=${line.take(160)}")
                parsed
            } else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AR_SAM_ONLINE_FAILED ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            null
        }
    }

    private fun parseArLocalizationObservation(
        observation: String,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0
    ): Pair<ARIntent, DetectedObject>? {
        val exactPayload = observation.lineSequence()
            .firstOrNull { it.trim().startsWith("AR_LOCALIZATION:", ignoreCase = true) }
            ?.substringAfter(":", "")
            ?.trim()
            .orEmpty()

        if (exactPayload.equals("NONE", ignoreCase = true) || exactPayload.contains("NOT_FOUND", ignoreCase = true)) return null

        fun fieldFrom(text: String, name: String): String? {
            val pipePattern = Regex("(?i)(?:^|\\|)\\s*" + Regex.escape(name) + "\\s*=\\s*([^|]+)")
            pipePattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            // Dedicated cloud vision providers sometimes honor the schema semantically
            // but serialize it as compact JSON. Accept that representation without
            // changing routing or inventing coordinates.
            val jsonPattern = Regex("(?i)[\"']?" + Regex.escape(name) + "[\"']?\\s*[:=]\\s*[\"']?([^\"',|}\\n]+)")
            jsonPattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val linePattern = Regex("(?im)^\\s*" + Regex.escape(name) + "\\s*[:=]\\s*([^\\n|]+)")
            return linePattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
        fun number(text: String, name: String): Float? = fieldFrom(text, name)?.toFloatOrNull()?.takeIf { it.isFinite() }

        val source = if (exactPayload.isNotBlank() && exactPayload.contains("FOUND", ignoreCase = true)) exactPayload else observation
        val target = fieldFrom(source, "target") ?: return null
        val rawConfidence = number(source, "confidence") ?: return null
        val confidence = when {
            rawConfidence in 0f..1f -> rawConfidence
            rawConfidence in 1f..100f -> rawConfidence / 100f
            else -> return null
        }.coerceIn(0f, 1f)

        var x = number(source, "x")
        var y = number(source, "y")
        var w = number(source, "w")
        var h = number(source, "h")

        if (x == null || y == null || w == null || h == null) {
            val bboxMatch = Regex("(?i)(?:bounding[_ ]?box|bbox)\\s*[:=]\\s*\\[?\\s*([0-9]*\\.?[0-9]+)\\s*[, ]+\\s*([0-9]*\\.?[0-9]+)\\s*[, ]+\\s*([0-9]*\\.?[0-9]+)\\s*[, ]+\\s*([0-9]*\\.?[0-9]+)\\s*\\]?").find(observation)
            if (bboxMatch != null) {
                val values = bboxMatch.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
                if (values.size == 4) {
                    x = values[0]; y = values[1]; w = values[2]; h = values[3]
                }
            }
        }

        if (x == null || y == null || w == null || h == null) {
            val x1 = number(observation, "x1")
            val y1 = number(observation, "y1")
            val x2 = number(observation, "x2")
            val y2 = number(observation, "y2")
            if (x1 != null && y1 != null && x2 != null && y2 != null && x2 > x1 && y2 > y1) {
                x = x1; y = y1; w = x2 - x1; h = y2 - y1
            }
        }

        val rawCoords = listOfNotNull(x, y, w, h)
        if (rawCoords.size != 4) return null
        val maxCoord = rawCoords.maxOrNull() ?: return null
        val coordScale = when {
            maxCoord <= 1.001f -> 1f
            maxCoord <= 100.001f -> 100f
            maxCoord <= 1000.001f -> 1000f
            else -> return null
        }
        val normalizedX = x!! / coordScale
        val normalizedY = y!! / coordScale
        val normalizedW = w!! / coordScale
        val normalizedH = h!! / coordScale

        val bx = normalizedX.coerceIn(0f, 1f)
        val by = normalizedY.coerceIn(0f, 1f)
        val bw = normalizedW.coerceIn(0.01f, 1f)
        val bh = normalizedH.coerceIn(0.01f, 1f)
        if (confidence < 0.45f || bx >= 1f || by >= 1f) return null

        val marker = when (fieldFrom(source, "marker")?.uppercase()) {
            "BOX" -> ARMarkerType.BOX_HIGHLIGHT
            else -> ARMarkerType.ARROW_POINTER
        }
        val detected = DetectedObject(
            label = target.take(120),
            bbox = NormalizedBBox(bx, by, bw.coerceAtMost(1f - bx), bh.coerceAtMost(1f - by)),
            confidence = confidence,
            sourceImageWidth = sourceWidth,
            sourceImageHeight = sourceHeight
        )
        val intent = ARIntent(
            action = if (marker == ARMarkerType.ARROW_POINTER) ARAction.POINT else ARAction.HIGHLIGHT,
            targetObject = detected.label,
            type = marker,
            color = ARColor.YELLOW
        )
        return intent to detected
    }

    private fun publishArLocalizationFromObservation(observation: String, sourceWidth: Int = 0, sourceHeight: Int = 0) {
        val parsed = parseArLocalizationObservation(observation, sourceWidth, sourceHeight) ?: return
        val intent = parsed.first
        val detected = parsed.second
        Log.i(TAG, "AR_LOCALIZATION_ACCEPT target='${detected.label}' confidence=${detected.confidence} bbox=${detected.bbox} marker=${intent.type}")
    }

    /**
     * Phase 9 AR refinement. Initial Camera2 localization is used only to identify the
     * requested target and activate AR. Once ARCore owns the camera, re-localize that
     * same target on a raw ARCore camera image so bbox pixels and ARCore IMAGE_PIXELS
     * share one geometry. No static object vocabulary is used.
     */
    suspend fun refineArLocalizationOnArFrame(
        requestedIntent: ARIntent,
        targetLabel: String,
        bitmap: Bitmap
    ): DetectedObject? {
        val prompt = buildString {
            append("Locate exactly this visible target in the image: ").append(targetLabel.take(160)).append(". ")
            append("Do not troubleshoot or explain. Return exactly one line: ")
            append("AR_LOCALIZATION: FOUND | target=<label> | confidence=<0..1> | x=<0..1> | y=<0..1> | w=<0..1> | h=<0..1> | marker=")
            append(if (requestedIntent.type == ARMarkerType.BOX_HIGHLIGHT) "BOX" else "ARROW")
            append(". If you cannot confidently localize it, return AR_LOCALIZATION: NOT_FOUND | target=").append(targetLabel.take(160))
            append(". Coordinates must describe the supplied image itself. Inspect the full frame carefully and keep the box tight around the same target; never reuse coordinates from conversation history or an earlier frame.")
        }

        val localObservation = try {
            val lm = localModelManager
            if (lm?.isReadyForVision == true) {
                val result = withTimeout(LIVE_VISUAL_OBSERVER_TIMEOUT_MS) {
                    lm.inferVisionImage(
                        bitmap = bitmap,
                        userQueryOnly = prompt,
                        history = emptyList(),
                        maxOutputToken = 56,
                        // V17: 512 is a native Gemma 3n vision resolution and keeps this
                        // fast current-frame pass lighter than the 768px primary locator.
                        imageMaxSide = 512,
                        taskRole = VisionTaskRole.AR_LOCALIZATION
                    )
                }
                if (result.completed) FixSightOutputSanitizer.sanitize(result.text).trim() else ""
            } else ""
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AR_LOCAL_RELOCALIZE_FAILED ${t.javaClass.simpleName}:${t.message}")
            ""
        }

        val localParsed = parseArLocalizationObservation(localObservation, bitmap.width, bitmap.height)
        val localTrusted = localParsed?.second?.takeIf { isSpatialLocalizationTrustworthy(it) }
        if (localTrusted != null) {
            lastArLocalizationProvider = Provider.LOCAL
            Log.i(TAG, "AR_RELOCALIZATION_ACCEPT rev=V18_CURRENT_FRAME provider=LOCAL target='${localTrusted.label}' conf=${localTrusted.confidence}")
            return localTrusted
        } else if (localParsed != null) {
            Log.w(TAG, "AR_RELOCALIZATION_LOCAL_WEAK rev=V18_CURRENT_FRAME target='${localParsed.second.label}' conf=${localParsed.second.confidence}")
        }

        if (!canAttemptCloud()) {
            Log.w(TAG, "AR_RELOCALIZATION_UNAVAILABLE_OFFLINE target='${targetLabel.take(80)}'")
            return null
        }

        return try {
            val cloudObservation = VisionWorkCoordinator.withVisionWork("ar_cloud_vlm") {
                withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                    groqProvider.analyzeImageObservation(emptyList(), prompt, bitmap).trim()
                }
            }
            val parsed = parseArLocalizationObservation(cloudObservation, bitmap.width, bitmap.height)
            val trusted = parsed?.second?.takeIf { isSpatialLocalizationTrustworthy(it) }
            if (trusted != null) {
                lastArLocalizationProvider = Provider.CLOUD
                Log.i(TAG, "AR_RELOCALIZATION_ACCEPT rev=V18_CURRENT_FRAME provider=CLOUD target='${trusted.label}' conf=${trusted.confidence}")
            } else if (parsed != null) {
                Log.w(TAG, "AR_RELOCALIZATION_CLOUD_WEAK rev=V18_CURRENT_FRAME target='${parsed.second.label}' conf=${parsed.second.confidence}")
            }
            trusted
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AR_CLOUD_RELOCALIZE_FAILED ${t.javaClass.simpleName}:${t.message}")
            null
        }
    }

    private fun arLocalizationNeedsCloudFallback(observation: String): Boolean =
        observation.lineSequence().any { line ->
            line.trim().startsWith("AR_LOCALIZATION:", ignoreCase = true) &&
                line.contains("NOT_FOUND", ignoreCase = true)
        }

    private suspend fun runCloudVisualObservationOnly(query: String, bitmap: Bitmap): String {
        if (!canAttemptCloud()) return ""
        return try {
            withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                val history = chatStore.historyForCloud(currentQuery = query)
                // V25: ordinary cloud visual observation is evidence only. It may not
                // publish AR localization as a side effect; explicit AR turns use the
                // dedicated AR path and its separate per-turn permission gate.
                groqProvider.analyzeImageObservation(history, query, bitmap).trim()
            }
        } catch (te: TimeoutCancellationException) {
            groqProvider.cancelActiveCall()
            Log.w(TAG, "AR_CLOUD_LOCALIZATION_TIMEOUT")
            ""
        } catch (ce: CancellationException) {
            groqProvider.cancelActiveCall()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AR_CLOUD_LOCALIZATION_FAILED ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    /**
     * Turn-triggered technical visual observer. It is intentionally NOT the final
     * assistant answer. One fresh frame is analyzed after the user's utterance is
     * committed. The VLM must separate directly visible facts from reasonable state
     * inferences, contradictions and unknowns so the text reasoner can use the image
     * as evidence instead of following the user's wording as a prior.
     */
    private suspend fun runLocalVisualObservation(
        query: String,
        bitmap: Bitmap
    ): String {
        if (isMetaStackActive && localModelManager?.isReadyForVision != true) {
            val observation = metaVision.observe(query, bitmap)
            recordAnswerSource(ModelSource.ENSEMBLE_LIGHT, query)
            val rendered = observation.render()
            Log.i(TAG, "LIVE_VISUAL_OBSERVATION_META chars=${rendered.length} models=${observation.modelsUsed}")
            return rendered
        }

        val lm = localModelManager ?: return ""
        if (!lm.isReadyForVision) return ""
        // A bounded history window lets the observer resolve follow-ups such as
        // "that screen" without allowing prior prose to override the pixels.
        val history = chatStore.recent(4)

        val observerPrompt = buildString {
            append("Act only as a technical visual observer. Inspect the image pixels independently of the user's assumptions. " )
            append("The user's words are a user-reported symptom or claim, not proof of what is visible. " )
            append("The current image is authoritative for present visible state; if the words and pixels disagree, report the disagreement explicitly instead of accepting the user's visual interpretation. " )
            append("Do NOT troubleshoot, diagnose, recommend actions, or agree with a failure mode merely because the user mentioned it. " )
            append("Report concrete visible state first, then only reasonable inferences supported by the image. Mark anything not visually established as unknown. " )
            append("Pay attention to operating state, active/illuminated displays, visible interfaces/programs, indicators/lights, connections, readable status/error text, and visual evidence that confirms or contradicts an obvious interpretation of the user's request. " )
            append("This observer is evidence-only. Do not request, prepare, mention, or emit AR markers, anchors, overlays, arrows, boxes, or localization control lines. " )
            append("If spatial position is visually relevant, describe it in ordinary factual language only. " )
            append("Prefer this compact format when possible: VISIBLE_FACTS:, SUPPORTED_INFERENCES:, VISIBLE_STATUS_TEXT:, CONTRADICTIONS:, UNKNOWN:. " )
            append("Stay under 110 words. Prior conversation may resolve references only; it never overrides the current pixels.\n\nUSER CONTEXT: " ).append(query.trim().take(500))
        }

        val result = VisionWorkCoordinator.withVisionWork("live_visual_observer") {
            lm.inferVisionImage(
                bitmap = bitmap,
                userQueryOnly = observerPrompt,
                history = history,
                maxOutputToken = LIVE_VISUAL_OBSERVER_MAX_TOKENS,
                // V17: Gemma 3n vision natively supports 768. Preserve more small-object
                // detail in the single local pass instead of adding slower retry/crop passes.
                imageMaxSide = 768,
                taskRole = VisionTaskRole.OBSERVER
            )
        }
        if (!result.completed || result.text.isBlank()) {
            Log.w(TAG, "LIVE_VISUAL_OBSERVATION_REJECTED reason=empty_or_incomplete error=${result.error}")
            return ""
        }

        val clean = FixSightOutputSanitizer.sanitize(result.text).trim()
        if (clean.length < 24) {
            Log.w(TAG, "LIVE_VISUAL_OBSERVATION_REJECTED reason=too_short chars=${clean.length}")
            return ""
        }

        // A small edge VLM can occasionally ignore exact formatting while still seeing the
        // scene correctly. Formatting is not a reason to throw away pixels and run the VLM
        // again. Remove advice-like sentences, keep the remaining visual evidence, and let
        // the text reasoner interpret it cautiously. This is domain-neutral.
        val advice = Regex("""(?i)\b(try|check|ensure|restart|reboot|replace|you should|steps? to|first,? you|plug it|unplug|power cycle|contact support)\b""")
        val pieces = clean
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val evidenceOnly = pieces.filterNot { advice.containsMatchIn(it) }.joinToString(" " ).trim()
        if (evidenceOnly.length < 24) {
            Log.w(TAG, "LIVE_VISUAL_OBSERVATION_REJECTED reason=advice_only_or_weak chars=${clean.length}")
            return ""
        }

        val upper = evidenceOnly.uppercase()
        val structured = upper.contains("VISIBLE_FACTS:") && upper.contains("SUPPORTED_INFERENCES:") && upper.contains("UNKNOWN:")
        val accepted = evidenceOnly.take(1_200)
        if (structured) {
            Log.i(TAG, "LIVE_VISUAL_OBSERVATION_ACCEPTED mode=structured chars=${accepted.length} attempts=1")
        } else {
            Log.i(TAG, "LIVE_VISUAL_OBSERVATION_ACCEPTED mode=unstructured_grounded chars=${accepted.length} attempts=1")
        }

        // V25 hard boundary: this observer is used only by non-AR camera turns.
        // No semantic localization result is permitted to invoke a locator or renderer here.
        return accepted
    }


    private fun isSpatialLocalizationTrustworthy(target: DetectedObject): Boolean {
        val b = target.bbox
        val area = b.w * b.h
        val touchesLeft = b.x <= 0.01f
        val touchesTop = b.y <= 0.01f
        val touchesRight = (b.x + b.w) >= 0.99f
        val touchesBottom = (b.y + b.h) >= 0.99f
        val edgeTouches = listOf(touchesLeft, touchesTop, touchesRight, touchesBottom).count { it }

        val validGeometry =
            b.w in 0.01f..1f &&
            b.h in 0.01f..1f &&
            b.x >= 0f && b.x < 1f &&
            b.y >= 0f && b.y < 1f &&
            b.x + b.w <= 1.001f &&
            b.y + b.h <= 1.001f

        // Domain-neutral spatial gate. Large foreground objects legitimately touch
        // multiple viewport edges (for example a laptop filling most of the camera).
        // Edge contact alone is therefore not evidence of a bad localization.
        //
        // Keep strict rejection for invalid geometry/low confidence. Treat a full-frame
        // box as suspicious only when confidence is weak; high-confidence large boxes
        // remain usable for object highlighting and can still fall back to 2D placement
        // if ARCore tracking itself is unavailable.
        val nearlyFullFrame = area >= 0.92f && edgeTouches >= 3
        val suspiciousBoundaryClamp = nearlyFullFrame && target.confidence < 0.80f
        val trusted = validGeometry && target.confidence >= 0.62f && !suspiciousBoundaryClamp
        if (!trusted) {
            Log.w(
                TAG,
                "AR_SPATIAL_QUALITY_REJECT rev=V8_0_SINGLE_FRAME_GUIDANCE " +
                    "target='${target.label}' conf=${target.confidence} area=${"%.3f".format(area)} edgeTouches=$edgeTouches bbox=$b"
            )
        }
        return trusted
    }

    private suspend fun runDedicatedArLocalization(
        query: String,
        bitmap: Bitmap,
        knowledgeEvidence: String = ""
    ): Pair<ARIntent, DetectedObject>? {
        // Always provide a small recent context window to the dedicated locator. This
        // lets the vision model resolve natural follow-ups such as "highlight it"
        // without Kotlin pronoun/keyword routing. The current image remains the only
        // source of coordinates and visibility.
        val recentContext = chatStore.recent(8).takeLast(4).joinToString("\n") { message ->
            "${message.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}: ${message.content.take(240)}"
        }.take(1_100)
        val priorVisualContext = notebookObserver?.snapshot()?.visualFindings
            ?.takeLast(4)
            ?.joinToString("\n") { it.take(260) }
            .orEmpty()
            .take(900)

        val prompt = buildString {
            append("The user is explicitly asking for visual localization/AR placement. Infer the intended visible target from the user's request, recent conversation context when useful, and the supplied image. The supplied image is aligned to the user's currently visible camera viewport; all x/y/w/h values must be relative to this exact image, with (0,0) at its top-left. ")
            append("Do not answer the user's question and do not troubleshoot. Return exactly one line only. ")
            append("If the intended target is visible, return: AR_LOCALIZATION: FOUND | target=<short label> | confidence=<0..1> | x=<0..1> | y=<0..1> | w=<0..1> | h=<0..1> | marker=<ARROW or BOX>. ")
            append("Here confidence means confidence in the BOUNDING BOX location, not merely confidence that the object exists. Keep the box tight around the requested target. ")
            append("Inspect the whole image carefully, including small or low-contrast objects. Distinguish the requested target from visually similar nearby objects using shape, controls, context, and relative placement visible in the image. Do not require the target to be large or centered. ")
            append("Choose BOX for highlight/box/circle/label style requests and ARROW for point/show/identify/locate/find style requests. ")
            append("If the target cannot be confidently localized, return: AR_LOCALIZATION: NOT_FOUND | target=<short label>. Never invent coordinates. ")
            if (recentContext.isNotBlank()) {
                append("RECENT CONVERSATION (target resolution only; never a source of coordinates): ")
                append(recentContext).append(" ")
            }
            if (priorVisualContext.isNotBlank()) {
                append("PRIOR ACCEPTED VISUAL FINDINGS (target hint only; re-check visibility in the supplied image): ")
                append(priorVisualContext).append(" ")
            }
            if (knowledgeEvidence.isNotBlank()) {
                append("The selected Knowledge Base may be used only to resolve what target the user means. ")
                append("Do not derive coordinates, visibility, or object presence from the Knowledge Base; those must come from the supplied image. ")
                append("KNOWLEDGE CONTEXT: ").append(knowledgeEvidence.take(2_400)).append(" ")
            }
            append("USER REQUEST: ").append(query.trim().take(500))
        }

        if (isMetaStackActive && localModelManager?.isReadyForVision != true) {
            try {
                val metaLine = metaVision.localize(query, bitmap)
                val parsed = parseArLocalizationObservation(metaLine, bitmap.width, bitmap.height)
                if (parsed != null && isSpatialLocalizationTrustworthy(parsed.second)) {
                    lastArLocalizationProvider = Provider.LOCAL
                    Log.i(TAG, "AR_DEDICATED_LOCALIZATION_META_STACK provider=LOCAL line=${metaLine.take(160)}")
                    return Pair(parsed.first, parsed.second)
                }
                Log.i(TAG, "AR_DEDICATED_LOCALIZATION_META_STACK_NOT_FOUND line=${metaLine.take(160)}")
                // Not found on the phone: Meta SAM 3.1 online finds it from the words themselves.
                samOnlineLocalization(query, bitmap)?.let { return it }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "AR_DEDICATED_LOCALIZATION_META_STACK_FAILED ${t.javaClass.simpleName}:${t.message}")
            }
        }

        val lm = localModelManager
        if (lm?.isReadyForVision == true) {
            try {
                val result = VisionWorkCoordinator.withVisionWork("ar_local_vlm") {
                    withTimeout(LIVE_VISUAL_OBSERVER_TIMEOUT_MS) {
                        lm.inferVisionImage(
                            bitmap = bitmap,
                            userQueryOnly = prompt,
                            history = emptyList(),
                            maxOutputToken = 72,
                            // V21: AR localization is the one vision path where pixel precision
                            // directly controls overlay placement. Preserve more detail for small
                            // ports/buttons/objects without changing general or troubleshooting VLM.
                            imageMaxSide = 1280,
                            taskRole = VisionTaskRole.AR_LOCALIZATION
                        )
                    }
                }
                if (result.completed && result.text.isNotBlank()) {
                    val clean = FixSightOutputSanitizer.sanitize(result.text).trim()
                    val parsed = parseArLocalizationObservation(clean, bitmap.width, bitmap.height)
                    if (parsed != null && isSpatialLocalizationTrustworthy(parsed.second)) {
                        lastArLocalizationProvider = Provider.LOCAL
                        Log.i(TAG, "AR_DEDICATED_LOCALIZATION_PROVIDER rev=V17_LOCAL_VISION_QUALITY provider=LOCAL chars=${clean.length}")
                        return parsed
                    }
                    if (parsed != null) {
                        Log.w(TAG, "AR_DEDICATED_LOCALIZATION_LOCAL_SPATIAL_WEAK rev=V17_LOCAL_VISION_QUALITY fallback=cloud_same_frame")
                    } else {
                        Log.w(TAG, "AR_DEDICATED_LOCALIZATION_LOCAL_UNPARSED rev=V17_LOCAL_VISION_QUALITY chars=${clean.length}")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "AR_DEDICATED_LOCALIZATION_LOCAL_FAILED rev=V17_LOCAL_VISION_QUALITY ${t.javaClass.simpleName}:${t.message}")
            }
        }

        if (!canAttemptCloud()) return null
        return try {
            suspend fun cloudAttempt(cloudPrompt: String, attempt: Int): Pair<ARIntent, DetectedObject>? {
                val observation = withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                    groqProvider.analyzeImageObservation(
                        emptyList(), cloudPrompt, bitmap, GroqChatProvider.VisionDetail.HIGH
                    ).trim()
                }
                val parsed = parseArLocalizationObservation(observation, bitmap.width, bitmap.height)
                val trusted = parsed?.takeIf { isSpatialLocalizationTrustworthy(it.second) }
                if (trusted != null) {
                    lastArLocalizationProvider = Provider.CLOUD
                    Log.i(TAG, "AR_DEDICATED_LOCALIZATION_PROVIDER rev=V14_AR_STABILITY provider=CLOUD attempt=$attempt chars=${observation.length} sameImage=true")
                    return trusted
                }
                Log.w(
                    TAG,
                    "AR_DEDICATED_LOCALIZATION_CLOUD_UNUSABLE rev=V14_AR_STABILITY attempt=$attempt " +
                        "parsed=${parsed != null} chars=${observation.length}"
                )
                return null
            }

            val firstObservation = withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                groqProvider.analyzeImageObservation(
                    emptyList(), prompt, bitmap, GroqChatProvider.VisionDetail.HIGH
                ).trim()
            }
            val firstParsed = parseArLocalizationObservation(firstObservation, bitmap.width, bitmap.height)
            val firstTrusted = firstParsed?.takeIf { isSpatialLocalizationTrustworthy(it.second) }
            if (firstTrusted != null) {
                lastArLocalizationProvider = Provider.CLOUD
                Log.i(TAG, "AR_DEDICATED_LOCALIZATION_PROVIDER rev=V19_ROUTING_STABILITY provider=CLOUD attempt=1 chars=${firstObservation.length} sameImage=true")
                firstTrusted
            } else if (firstParsed != null) {
                // A second "strict format" request cannot improve an already parsed
                // but spatially weak localization and only burns cloud quota.
                Log.w(
                    TAG,
                    "AR_DEDICATED_LOCALIZATION_CLOUD_UNUSABLE rev=V19_ROUTING_STABILITY " +
                        "attempt=1 parsed=true chars=${firstObservation.length} retrySkipped=parsed_spatially_weak"
                )
                null
            } else {
                cloudAttempt(
                    prompt + " STRICT FORMAT RETRY: output only the exact AR_LOCALIZATION line specified above; no prose, markdown, JSON wrapper, explanation, or extra lines.",
                    2
                )
            }
        } catch (ce: CancellationException) {
            groqProvider.cancelActiveCall()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "AR_DEDICATED_LOCALIZATION_CLOUD_FAILED rev=V17_LOCAL_VISION_QUALITY ${t.javaClass.simpleName}:${t.message}")
            null
        }
    }

    /**
     * V25 troubleshooting-camera contract: one VLM inference produces two independent
     * products — structured visual evidence for durable memory and a concise user answer.
     * A generic conversational reply is not accepted as visual evidence.
     */
    /** All provider drafts in this route pass the same gate before history/TTS. */
    private suspend fun runReviewedTroubleshootingCameraTurn(
        query: String,
        bitmap: Bitmap,
        detail: GroqChatProvider.VisionDetail,
        turnStartedMs: Long
    ): TurnResult {
        // TroubleshootingSessionState is the single machine-facing context for
        // KB-off troubleshooting. Do not replay raw Notebook/chat prose into the
        // visual reviewer: both Local and Cloud must reason from the same compact
        // accepted case state + the current user turn + the same fresh frame.
        // After the app was closed or the conversation reopened, the in-memory case state is empty:
        // continue from the saved Notebook of this conversation instead of starting the case again.
        // Only troubleshooting's own saved notes are used (nothing from normal chat or a knowledge base).
        val caseState = troubleshootingSessionState.renderForPrompt().ifBlank {
            notebookObserver?.troubleshootingNotes()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    "<earlier_case_notes>\nSaved notes from earlier in this troubleshooting case (re-check visual details against the current frame):\n" +
                        it.take(2_600) + "\n</earlier_case_notes>"
                }.orEmpty()
        }
        val reviewed = TroubleshootingCameraReview.run(
            user = query,
            recent = "",
            notebook = "",
            local = { prompt ->
                if (isMetaStackActive && localModelManager?.isReadyForVision != true) {
                    try {
                        val lm = localModelManager
                        if (lm?.isReady != true) null else {
                            val facts = metaVision.observe(query, bitmap).render()
                            recordAnswerSource(ModelSource.ENSEMBLE_LIGHT, query)
                            var done: BaselineGenerationResult? = null
                            withTimeout(engineScaledTimeout(TROUBLESHOOTING_REVIEW_TIMEOUT_MS)) {
                                lm.inferTextGrounded(
                                    evidence = "CURRENT CAMERA FRAME — measured by on-device Meta vision models " +
                                        "(you cannot see the image; these facts are the only visual evidence):\n$facts",
                                    userQueryOnly = prompt + "\n\nUse only the CURRENT CAMERA FRAME facts as visual evidence. " +
                                        "Anything they do not establish belongs under UNCERTAINTY.",
                                    history = emptyList(),
                                    sourceGroundingRequired = true,
                                    maxOutputTokenOverride = if (prompt.contains("STRICT LOCAL RECOVERY:")) 200 else 300,
                                    onComplete = { done = it }
                                ).collect { }
                            }
                            done?.takeIf { it.completed }?.text
                        }
                    } catch (te: TimeoutCancellationException) {
                        Log.w(TAG, "TROUBLESHOOTING_REVIEW_META_TIMEOUT")
                        null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "TROUBLESHOOTING_REVIEW_META_FAILED ${t.message}")
                        null
                    }
                } else {
                    val lm = localModelManager
                    if (lm?.isReadyForVision != true) {
                        Log.i(TAG, "TROUBLESHOOTING_REVIEW_LOCAL_UNAVAILABLE")
                        null
                    } else {
                    try {
                        // The complete request/contract is already bounded by the
                        // dedicated builder. Never pass it through the legacy 3k cut.
                        // Recent turns are in the prompt; do not duplicate them in KV.
                        withTimeout(TROUBLESHOOTING_REVIEW_TIMEOUT_MS) {
                            val generation = VisionWorkCoordinator.withVisionWork("troubleshooting_review_local") {
                                lm.inferVisionImage(
                                    bitmap = bitmap,
                                    userQueryOnly = prompt,
                                    history = emptyList(),
                                    // Seven fields incl. a real description of the frame; 192 tokens cut
                                    // the contract short (→ recovery pass or a generic fallback).
                                    maxOutputToken = if (prompt.contains("STRICT LOCAL RECOVERY:")) 200 else 300,
                                    // Keep the full viewport image quality for troubleshooting;
                                    // latency is bounded through generation length, not by degrading pixels.
                                    imageMaxSide = 1280,
                                    taskRole = VisionTaskRole.TROUBLESHOOTING,
                                    // Near-greedy reading of the frame for this review only (chat default 0.8 drifts).
                                    temperature = TROUBLESHOOTING_REVIEW_TEMPERATURE
                                )
                            }
                            if (generation.completed) generation.text else null
                        }
                    } catch (te: TimeoutCancellationException) {
                        Log.w(TAG, "TROUBLESHOOTING_REVIEW_LOCAL_TIMEOUT")
                        null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "TROUBLESHOOTING_REVIEW_LOCAL_FAILED ${t.javaClass.simpleName}")
                        null
                    } finally {
                        lm.closeSession()
                    }
                }
            } },
            cloud = { prompt ->
                try {
                    withTimeout(TROUBLESHOOTING_VLM_TIMEOUT_MS) {
                        // Same captured bitmap, independent interpretation. The
                        // cloud helper's prose-as-observation field is ignored;
                        // only the review parser produces notebook observations.
                        runCloudVision(
                            query = prompt, evidence = "", bitmap = bitmap,
                            detail = GroqChatProvider.VisionDetail.HIGH, safetyCritical = false
                        )?.text
                    }
                } catch (te: TimeoutCancellationException) {
                    groqProvider.cancelActiveCall()
                    Log.w(TAG, "TROUBLESHOOTING_REVIEW_CLOUD_TIMEOUT")
                    null
                } catch (ce: CancellationException) {
                    groqProvider.cancelActiveCall()
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "TROUBLESHOOTING_REVIEW_CLOUD_FAILED ${t.javaClass.simpleName}")
                    null
                }
            },
            cloudAvailable = { canAttemptCloud() },
            sanitize = { FixSightOutputSanitizer.sanitize(it) },
            shapeFailure = { structuralOutputFailure(it, enforceNumericGlue = false) },
            event = { Log.i(TAG, "TROUBLESHOOTING_REVIEW rev=2_long_session $it") },
            sessionState = caseState,
            cloudReviewNeeded = { draft -> troubleshootingSessionState.shouldEscalateAcceptedLocal(draft) },
            previousSteps = troubleshootingSessionState.recentSteps(3),
            cloudFirst = answerPolicyStore.get() == AnswerPolicy.CLOUD_FIRST
        )
        val provider = when (reviewed.provider) {
            TroubleshootingCameraReview.Provider.LOCAL -> Provider.LOCAL
            TroubleshootingCameraReview.Provider.CLOUD -> Provider.CLOUD
            TroubleshootingCameraReview.Provider.NONE -> Provider.NONE
        }
        // Persist only the accepted compact troubleshooting contract. This keeps
        // turn 30-40 coherent without replaying the entire raw conversation or frames.
        reviewed.draft?.let { draft ->
            troubleshootingSessionState.recordAccepted(
                user = query,
                draft = draft,
                provider = provider.name
            )
            // Notebook: working diagnosis ("Theory"), eliminated causes ("Ruled Out") and each
            // step with the user's reported result ("Tried"), so the case and its report conclude.
            if (provider != Provider.NONE) notebookObserver?.let { notebook ->
                try {
                    troubleshootingSessionState.workingTheory().takeIf { it.isNotBlank() }?.let(notebook::updateTheory)
                    troubleshootingSessionState.latestRuledOut()?.let(notebook::recordTroubleshootingRuledOut)
                    troubleshootingSessionState.latestStepAndReply()?.let { (step, reply) -> notebook.recordCheckResult(step, reply) }
                } catch (t: Throwable) {
                    Log.w(TAG, "TROUBLESHOOTING_NOTEBOOK_UPDATE_FAILED ${t.javaClass.simpleName}")
                }
            }
        }

        // Preserve the user's turn and the final clarification even when both
        // providers fail. Unaccepted drafts never enter conversation or notebook.
        chatStore.append("user", query)
        chatStore.append("assistant", reviewed.answer)
        notifyAcceptedTurn(
            query, reviewed.answer, provider, investigation = true,
            visualObservation = reviewed.observation, sourceIds = listOf("live_camera")
        )
        val elapsed = (System.currentTimeMillis() - turnStartedMs).coerceAtLeast(1L)
        if (provider != Provider.NONE) runtimeHealth.turnAccepted(provider, elapsed, groqProvider.status())
        return TurnResult(
            text = reviewed.answer, provider = provider, elapsedMs = elapsed,
            streamedLocally = false, visualObservation = reviewed.observation,
            sourceIds = listOf("live_camera")
        )
    }

    private suspend fun runTroubleshootingVision(
        query: String,
        bitmap: Bitmap,
        grounded: Boolean,
        maxOutputTokens: Int,
        evidenceLimit: Int = TROUBLESHOOTING_EVIDENCE_DEFAULT_LIMIT
    ): TurnResult? {
        val lm = localModelManager ?: return null
        if (!lm.isReadyForVision) return null
        val history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        val started = System.currentTimeMillis()
        val visionQuery = buildString {
            appendLine("EVIDENCE PRECEDENCE CONTRACT:")
            appendLine("- Inspect the current image pixels before interpreting the user's words.")
            appendLine("- The current frame is authoritative for visible present state.")
            appendLine("- The user's wording is a reported symptom or claim, not visual proof.")
            appendLine("- If the claim conflicts with the frame, preserve both, mark the conflict in VISUAL_EVIDENCE or UNCERTAINTY, and do not confirm the claim.")
            appendLine("- Ask one focused clarification when the conflict cannot be resolved from the frame.")
            appendLine("USER TURN:")
            append(query.trim().take(3_000))
        }
        val result = VisionWorkCoordinator.withVisionWork("troubleshooting_grounded_vlm") {
            lm.inferVisionImage(
                bitmap = bitmap,
                userQueryOnly = visionQuery,
                history = history,
                maxOutputToken = maxOutputTokens.coerceIn(96, 480),
                taskRole = VisionTaskRole.TROUBLESHOOTING
            )
        }
        if (!result.completed || result.text.isBlank()) {
            Log.w(TAG, "TROUBLESHOOTING_VLM_REJECTED reason=empty_or_incomplete error=${result.error}")
            return null
        }

        val raw = result.text.replace("\r\n", "\n").trim()
        fun section(name: String, next: String?): String {
            val startToken = "$name:"
            val start = raw.indexOf(startToken, ignoreCase = true)
            if (start < 0) return ""
            val contentStart = start + startToken.length
            val end = if (next != null) {
                val idx = raw.indexOf("$next:", startIndex = contentStart, ignoreCase = true)
                if (idx >= 0) idx else raw.length
            } else raw.length
            return raw.substring(contentStart, end).trim()
        }

        val evidenceRaw = section("VISUAL_EVIDENCE", "UNCERTAINTY")
        val uncertaintyRaw = section("UNCERTAINTY", "ANSWER")
        val answerRaw = section("ANSWER", null)

        // Troubleshooting is evidence-first. A short on-device VLM reply must contain
        // a concrete visual-evidence section before it can be promoted. Some mobile
        // VLM generations stop after VISUAL_EVIDENCE/UNCERTAINTY and omit ANSWER;
        // that is recoverable because the evidence itself is still useful and must
        // remain available to the next conversational turn and Notebook.
        if (evidenceRaw.isBlank()) {
            Log.w(
                TAG,
                "TROUBLESHOOTING_VLM_REJECTED reason=structured_evidence_required " +
                    "evidencePresent=false answerPresent=${answerRaw.isNotBlank()} rawChars=${raw.length}"
            )
            lm.closeSession()
            return null
        }

        fun cleanLines(value: String): List<String> = value.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
            .map { FixSightOutputSanitizer.sanitize(it).trim() }
            .filter { it.isNotBlank() }
            .take(evidenceLimit.coerceIn(4, 20))
            .toList()

        val noUsefulEvidence = evidenceRaw.lineSequence().any {
            it.trim().equals("NO_USEFUL_VISUAL_EVIDENCE", ignoreCase = true)
        }
        val evidenceLines = if (noUsefulEvidence) emptyList() else cleanLines(evidenceRaw)
        val uncertaintyLines = cleanLines(uncertaintyRaw)
        if (noUsefulEvidence || evidenceLines.isEmpty()) {
            Log.w(
                TAG,
                "TROUBLESHOOTING_VLM_REJECTED reason=no_concrete_visual_evidence " +
                    "explicitNone=$noUsefulEvidence rawChars=${raw.length}"
            )
            lm.closeSession()
            return null
        }

        val answerWithEvidenceFallback = answerRaw.ifBlank {
            val fallback = evidenceLines.joinToString("; ").take(700)
            Log.w(
                TAG,
                "TROUBLESHOOTING_VLM_EVIDENCE_FALLBACK evidence=${evidenceLines.size} chars=${fallback.length}"
            )
            "Visible evidence: $fallback"
        }
        val acceptedAnswer = stripLeadingUserEcho(
            query,
            FixSightOutputSanitizer.compactForLive(
                FixSightOutputSanitizer.sanitize(answerWithEvidenceFallback).trim()
            ).trim()
        )
        if (acceptedAnswer.isBlank()) {
            Log.w(TAG, "TROUBLESHOOTING_VLM_REJECTED reason=blank_answer_after_parse")
            lm.closeSession()
            return null
        }
        structuralOutputFailure(acceptedAnswer, enforceNumericGlue = !grounded)?.let { reason ->
            Log.w(TAG, "TROUBLESHOOTING_VLM_REJECTED reason=$reason chars=${acceptedAnswer.length}")
            lm.closeSession()
            return null
        }
        ordinaryNonAnswerReason(query, acceptedAnswer)?.let { reason ->
            Log.w(TAG, "TROUBLESHOOTING_VLM_REJECTED reason=ordinary_non_answer:$reason cloudFallback=true")
            lm.closeSession()
            return null
        }

        val visualObservation = if (noUsefulEvidence) {
            "NO_USEFUL_VISUAL_EVIDENCE"
        } else buildString {
            append("VISIBLE_FACTS: ")
            append(evidenceLines.joinToString("; "))
            if (uncertaintyLines.isNotEmpty()) {
                append(" UNKNOWN: ")
                append(uncertaintyLines.joinToString("; "))
            }
        }.take(700)

        lm.closeSession()
        Log.i(
            TAG,
            "TROUBLESHOOTING_VLM_STRUCTURED_ACCEPTED evidence=${evidenceLines.size} " +
                "noUseful=$noUsefulEvidence uncertainty=${uncertaintyLines.size} answerChars=${acceptedAnswer.length}"
        )
        return TurnResult(
            text = acceptedAnswer,
            provider = Provider.LOCAL,
            elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
            streamedLocally = false,
            visualObservation = visualObservation
        )
    }

    /**
     * Direct local vision-language turn retained for standalone image/attachment
     * workflows that explicitly need a full VLM answer. Live camera does not use
     * this path; it uses the short observer + text-reasoning split above.
     */
    private suspend fun runLocalVision(
        query: String,
        bitmap: Bitmap,
        grounded: Boolean,
        maxOutputTokens: Int = 640
    ): TurnResult? {
        val lm = localModelManager ?: return null
        if (!lm.isReadyForVision) return null
        val history = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        val started = System.currentTimeMillis()
        val result = VisionWorkCoordinator.withVisionWork("attachment_or_image_vlm") {
            lm.inferVisionImage(
                bitmap = bitmap,
                userQueryOnly = query,
                history = history,
                maxOutputToken = maxOutputTokens.coerceIn(96, 640)
            )
        }
        if (!result.completed || result.text.isBlank()) {
            Log.w(TAG, "LOCAL_VLM_OUTPUT_REJECTED reason=empty_or_incomplete error=${result.error}")
            return null
        }

        val sanitized = FixSightOutputSanitizer.sanitize(result.text).trim()
        val accepted = stripLeadingUserEcho(
            query,
            FixSightOutputSanitizer.compactForLive(sanitized).trim()
        )
        if (accepted.isBlank()) {
            Log.w(
                TAG,
                "LOCAL_VLM_OUTPUT_REJECTED reason=blank_after_sanitize " +
                    "rawChars=${result.text.length} sanitizedChars=${sanitized.length}"
            )
            return null
        }
        structuralOutputFailure(accepted, enforceNumericGlue = !grounded)?.let { reason ->
            Log.w(TAG, "LOCAL_VLM_OUTPUT_REJECTED reason=$reason chars=${accepted.length} grounded=$grounded")
            return null
        }
        if (isObviousUserEcho(query, accepted)) {
            Log.w(TAG, "LOCAL_VLM_OUTPUT_REJECTED reason=user_echo chars=${accepted.length}")
            return null
        }
        ordinaryNonAnswerReason(query, accepted)?.let { reason ->
            Log.w(TAG, "LOCAL_VLM_OUTPUT_REJECTED reason=ordinary_non_answer:$reason")
            return null
        }

        // The VLM conversation is intentionally ephemeral. Retire any resident
        // text session so the following text turn reseeds from authoritative chatStore.
        lm.closeSession()
        Log.i(TAG, "LOCAL_VLM_OUTPUT_ACCEPTED chars=${accepted.length}")
        return TurnResult(
            text = accepted,
            provider = Provider.LOCAL,
            elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1L),
            streamedLocally = false,
            visualObservation = accepted.take(700)
        )
    }

    /**
     * Offline answer for an attached photo: the phone's Meta vision models (DETR, CLIP, DINOv2, OCR)
     * describe it and Llama answers from that description. Kept out of chat() (JVM method size).
     */
    private suspend fun localMetaAttachmentAnswer(
        query: String,
        evidence: String,
        attachments: List<BaselineContextAttachment>,
        bitmap: Bitmap?,
        onLocalDelta: ((String) -> Unit)?,
        started: Long,
        turnSourceIds: List<String>
    ): TurnResult? {
        if (!localCameraReady || bitmap == null) return null
        val metaObservation = runCatching { observeVisualFrameInternal(query, bitmap) }.getOrDefault("").trim()
        if (metaObservation.isBlank()) return null
        Log.i(TAG, "ATTACHMENT_VISION_LOCAL_META observationChars=${metaObservation.length}")
        // runLocal reads evidence only from attachments, so the observation goes in as one.
        val observationAttachment = BaselineContextAttachment(
            sourceId = "attachment_image_observation",
            text = "Attached photo, as seen by the phone's Meta vision models (object detection, scene labels and " +
                "printed-text reading). Answer from these facts only; if they are not enough to answer, say what " +
                "can be seen and what is unclear.\n" + metaObservation.take(2_000),
            priority = 110,
            attributes = mapOf("visual_observation" to "true", "source_type" to "attachment_image")
        )
        val localVisual = try {
            runLocal(query = query, evidence = evidence, attachments = listOf(observationAttachment) + attachments,
                onLocalDelta = onLocalDelta, retryVariant = false, livePresentationAllowed = false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "ATTACHMENT_VISION_LOCAL_META_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
        } ?: return null
        if (localVisual.text.isBlank()) return null
        localModelManager?.closeSession()
        chatStore.append("user", query)
        chatStore.append("assistant", localVisual.text)
        notifyAcceptedTurn(query, localVisual.text, Provider.LOCAL)
        val elapsed = System.currentTimeMillis() - started
        runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
        return localVisual.copy(elapsedMs = elapsed, visualObservation = metaObservation, sourceIds = turnSourceIds)
    }

    /**
     * Safety-sensitive turn that could not be checked online: the phone's model still answers,
     * with emergency-first instructions, instead of a fixed refusal. Null when Llama is not ready.
     */
    private suspend fun localSafetyAnswer(
        query: String,
        evidence: String,
        onLocalDelta: ((String) -> Unit)?,
        started: Long,
        turnSourceIds: List<String>
    ): TurnResult? {
        val guard = BaselineContextAttachment(
            sourceId = "safety_offline_guidance",
            text = "Safety-sensitive question; online verification is not available. If this may be an emergency or a danger sign, " +
                "first tell the user to call 108 (ambulance) — or 102 for a pregnant woman or newborn — and to take the person to the " +
                "nearest PHC/CHC or hospital, then give only the immediate, widely accepted first steps while help comes. " +
                "Do not give a prescription medicine dose unless it is standard national guidance, and say to confirm it with a doctor. " +
                "If you are not sure, say so briefly." + if (evidence.isNotBlank()) "\n\n" + evidence.take(2_400) else "",
            priority = 118,
            attributes = mapOf("source_type" to "safety_guidance")
        )
        val answer = try {
            withTimeout(engineScaledTimeout(LOCAL_TURN_TIMEOUT_MS)) {
                runLocal(query = query, evidence = guard.text, attachments = listOf(guard), onLocalDelta = onLocalDelta,
                    livePresentationAllowed = false)
            }
        } catch (te: TimeoutCancellationException) {
            Log.w(TAG, "SAFETY_LOCAL_TIMEOUT"); null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "SAFETY_LOCAL_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
        } ?: return null
        if (answer.text.isBlank()) return null
        chatStore.append("user", query)
        chatStore.append("assistant", answer.text)
        notifyAcceptedTurn(query, answer.text, Provider.LOCAL)
        val elapsed = System.currentTimeMillis() - started
        runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
        Log.i(TAG, "SAFETY_LOCAL_ACCEPTED chars=${answer.text.length} elapsed=${elapsed}ms")
        return answer.copy(elapsedMs = elapsed, sourceIds = turnSourceIds)
    }

    /** Safety-sensitive turn without online verification: the phone's model, else the emergency message. */
    private suspend fun safetyFallbackTurn(
        query: String,
        evidence: String,
        onLocalDelta: ((String) -> Unit)?,
        started: Long,
        turnSourceIds: List<String>
    ): TurnResult {
        localSafetyAnswer(query, evidence, onLocalDelta, started, turnSourceIds)?.let { return it }
        localModelManager?.closeSession()
        return TurnResult(
            text = safetyUnavailableMessage(query),
            provider = Provider.NONE,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = turnSourceIds
        )
    }

    /** Last resort when neither online nor the phone's model can answer a safety-sensitive turn. */
    private fun safetyUnavailableMessage(query: String): String =
        if (KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI)
            "यह गंभीर हो सकता है। अगर किसी की जान को खतरा है तो तुरंत 108 (एम्बुलेंस) पर कॉल करें — गर्भवती महिला या नवजात के लिए 102 — और नज़दीकी PHC या अस्पताल ले जाएँ। अभी मैं इस पर जाँची हुई जानकारी नहीं दे पा रहा हूँ।"
        else "This may be serious. If someone is in danger, call 108 (ambulance) now — or 102 for a pregnant woman or newborn — " +
            "and take them to the nearest PHC or hospital. I can't give checked guidance on this right now."

    private suspend fun runLocal(
        query: String,
        evidence: String,
        attachments: List<BaselineContextAttachment>,
        onLocalDelta: ((String) -> Unit)?,
        retryVariant: Boolean = false,
        livePresentationAllowed: Boolean = false,
        maxOutputTokenOverride: Int? = null
    ): TurnResult? {
        val lm = localModelManager ?: return null
        if (!lm.isReady || lm.permanentlyDisabled || !lm.isTextWarm) return null

        val acceptedHistory = chatStore.recentWithinTokenBudget(historyTokenBudget, 120)
        val rawKbCaseContext = attachments.firstOrNull { it.attributes["knowledge_pack"] == "anm_sop" }
            ?.attributes?.get("kb_case_context").orEmpty()
        val genericKnowledgePackAttachment = attachments.firstOrNull {
            it.attributes["grounding_required"] == "true" &&
                it.attributes["knowledge_pack"] == "generic"
        }
        val genericContextDependent = genericKnowledgePackAttachment
            ?.attributes?.get("kb_context_dependent")?.equals("true", ignoreCase = true) == true
        // Independent selected-KB questions must not inherit stale assistant answers.
        // Genuine follow-ups keep bounded history; ANM keeps its existing Notebook rule.
        val modelHistory = when {
            rawKbCaseContext.isNotBlank() -> acceptedHistory.takeLast(6)
            genericKnowledgePackAttachment != null && !genericContextDependent -> emptyList()
            // The whole history took tokens from the file's text (budgetLocalEvidence) on every later turn.
            // Attachments keep the last two turns (the user's earlier context stays), never the whole chat.
            attachments.isNotEmpty() -> acceptedHistory.takeLast(PLAIN_CHAT_HISTORY_MESSAGES)
            else -> acceptedHistory
        }
        val kbCaseContext = budgetKbCaseContext(rawKbCaseContext, modelHistory, query)
        val evidenceBudget = budgetLocalEvidence(
            attachments = attachments,
            history = modelHistory,
            query = query,
            fallbackEvidence = evidence,
            additionalReservedText = kbCaseContext
        )
        val localEvidence = evidenceBudget.text
        val hasEvidence = localEvidence.isNotBlank()
        val groundingRequired = attachments.any { it.attributes["grounding_required"] == "true" }
        val selectedKbStrictGrounding = attachments.any {
            it.attributes["grounding_required"] == "true" &&
                it.attributes["knowledge_pack"] == "anm_sop"
        }
        val genericKnowledgePackGrounding = attachments.any {
            it.attributes["grounding_required"] == "true" &&
                it.attributes["knowledge_pack"] == "generic"
        }
        if (groundingRequired && !hasEvidence) {
            Log.w(TAG, "LOCAL_ROUTE_SKIPPED reason=grounding_required_but_no_evidence_budget")
            return null
        }

        var completed: BaselineGenerationResult? = null
        val started = System.currentTimeMillis()
        var firstDeltaAtMs: Long? = null
        // AndroLLM ownership rule: the inference/router layer emits clean model
        // deltas only. Sentence assembly and TTS belong to the owning voice turn
        // (VoiceAssistantService in AndroLLM; AndroTurnCoordinator/MainActivity
        // adapter in FixSight). This keeps presentation out of provider routing.
        var streamedAny = false
        val liveEchoPrefix = StringBuilder()
        var liveEchoPrefixResolved = !livePresentationAllowed

        fun emitLiveDelta(delta: String) {
            if (delta.isBlank()) return
            if (firstDeltaAtMs == null) firstDeltaAtMs = System.currentTimeMillis()
            try {
                onLocalDelta?.invoke(delta)
                streamedAny = true
            } catch (t: Throwable) {
                Log.w(TAG, "LOCAL_STREAM_DELTA_CONSUMER_FAILED ${t.message}")
            }
        }

        fun firstSentenceBoundary(s: String): Int {
            val idx = s.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '।' || it == '！' || it == '？' || it == '\n' }
            return if (idx >= 0) idx + 1 else -1
        }

        fun presentDelta(delta: String) {
            if (hasEvidence || onLocalDelta == null || delta.isBlank()) return
            if (delta.trimStart().startsWith("{")) return

            if (liveEchoPrefixResolved) {
                emitLiveDelta(delta)
                return
            }

            liveEchoPrefix.append(delta)
            val buffered = liveEchoPrefix.toString()
            val boundary = firstSentenceBoundary(buffered)

            if (boundary > 0) {
                val firstSentence = buffered.substring(0, boundary).trim()
                val remainder = buffered.substring(boundary)
                if (isObviousUserEcho(query, firstSentence)) {
                    Log.w(TAG, "LOCAL_LIVE_ECHO_PREFIX_SUPPRESSED chars=${firstSentence.length}")
                    liveEchoPrefixResolved = true
                    liveEchoPrefix.setLength(0)
                    emitLiveDelta(remainder)
                } else {
                    // The first complete sentence is already enough to prove that
                    // this is not a verbatim/near-verbatim user echo. Release it
                    // immediately instead of waiting for a 180-character buffer.
                    // This reduces perceived Local latency without changing model
                    // generation, routing, validation or fallback ownership.
                    liveEchoPrefixResolved = true
                    liveEchoPrefix.setLength(0)
                    emitLiveDelta(buffered)
                }
                return
            }

            // If the model has produced a substantial novel prefix without a sentence
            // boundary, release it to preserve low-latency AndroLLM-style streaming.
            // A short echo-only generation remains quarantined until completion.
            if (buffered.length >= LIVE_ECHO_PREFIX_MAX_CHARS && !isObviousUserEcho(query, buffered)) {
                liveEchoPrefixResolved = true
                liveEchoPrefix.setLength(0)
                emitLiveDelta(buffered)
            }
        }

        if (hasEvidence) {
            // KB/memory turn: use ephemeral grounded conversation.
            // System prompt stays stable. Resident KV is untouched.
            // Evidence + query go into Message.user() of the throwaway conversation.
            // A retry is genuinely independent: the verified evidence/case context is
            // retained, but conversational history is omitted so a bad prior generation
            // or stale dialogue surface cannot deterministically recreate the same miss.
            val groundedHistory = if (retryVariant) emptyList() else modelHistory
            Log.i(
                TAG,
                "LOCAL_ROUTE grounded=true evidenceChars=${localEvidence.length} retry=$retryVariant " +
                    "historyTurns=${groundedHistory.size}"
            )
            lm.inferTextGrounded(
                evidence = localEvidence,
                userQueryOnly = query,
                history = groundedHistory,
                strictGrounding = selectedKbStrictGrounding,
                sourceGroundingRequired = groundingRequired,
                knowledgePackGrounding = genericKnowledgePackGrounding,
                caseContext = kbCaseContext,
                maxOutputTokenOverride = maxOutputTokenOverride,
                onComplete = { completed = it }
            ).collect { /* silent — validate full answer before speaking */ }
        } else {
            // Ordinary turn: use resident KV session with full reuse.
            Log.i(TAG, "LOCAL_ROUTE grounded=false KB_EVIDENCE=none")
            // Keep AndroLLM's default sampler seed across a recovery attempt. The
            // retry changes only the native Conversation state, not decoding policy.
            if (!lm.ensureConversationForHistory(plainChatHistory(query, acceptedHistory), samplerSeed = 0)) return null
            lm.inferText(
                contextPacket = "",
                userQueryOnly = query,
                isFirstTurn = acceptedHistory.isEmpty(),
                maxOutputToken = maxOutputTokenOverride ?: 768,
                onToken = ::presentDelta,
                onComplete = { completed = it }
            ).collect { /* full result arrives here; deltas are handled by onToken */ }
        }

        val done = completed ?: run {
            if (!hasEvidence) lm.closeSession()
            return null
        }
        if (!done.completed || done.text.isBlank()) {
            if (!hasEvidence) lm.closeSession()
            return null
        }

        if (containsPrivateContextLeak(done.text)) {
            Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=private_context_leak chars=${done.text.length}")
            if (hasEvidence) { /* ephemeral conv already discarded */ } else lm.closeSession()
            return null
        }

        val sanitizedText = FixSightOutputSanitizer.sanitize(done.text).trim()
        val compactedText = FixSightOutputSanitizer.compactForLive(sanitizedText).trim()
        var acceptedText = stripLeadingUserEcho(query, compactedText)
        if (genericKnowledgePackGrounding) {
            // The model is instructed not to generate provenance, but small local
            // models can still append a partial Sources footer. Strip that model-owned
            // footer BEFORE structural validation. Deterministic provenance is appended
            // only after every grounding/semantic gate has accepted the answer body.
            acceptedText = stripGeneratedGenericKbFooter(acceptedText)
        }
        if (acceptedText.isBlank()) {
            Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=blank_or_incomplete_after_live_compaction")
            if (!hasEvidence) lm.closeSession()
            return null
        }
        if (compactedText != sanitizedText) {
            Log.i(TAG, "LOCAL_OUTPUT_COMPACTED from=${sanitizedText.length} to=${compactedText.length}")
        }
        if (acceptedText != compactedText) {
            Log.i(TAG, "LOCAL_OUTPUT_LEADING_ECHO_STRIPPED from=${compactedText.length} to=${acceptedText.length}")
        }

        // Grounded/evidence turns must never accept an internal message envelope or
        // a recycled recent user turn as an answer. Small local models occasionally
        // emit LiteRT/message-serialization residue such as
        // [{"type":"text","text":"..."}] or replay the previous user question.
        // Semantic similarity alone cannot distinguish that protocol leak from a
        // genuinely source-grounded answer, so reject it deterministically before
        // any grounding validator is allowed to approve it. Ordinary KB-OFF chat is
        // intentionally untouched.
        if (hasEvidence) {
            val groundedProtocolFailure = groundedOutputProtocolFailure(acceptedText, acceptedHistory)
            if (groundedProtocolFailure != null) {
                Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=$groundedProtocolFailure chars=${acceptedText.length}")
                return null
            }
        }

        // Debug marker — logs exact user-visible Gemma output before gate decision.
        Log.i(TAG, "LOCAL_FINAL_TEXT text='${acceptedText.take(400)}'")

        // Gate 1: cheap structural quality. This is topic-agnostic: it does not
        // rewrite the user request or judge semantics. It only blocks malformed
        // decoder tails and consecutive sentence repetition from entering KV/history/TTS.
        var structuralFailure = structuralOutputFailure(acceptedText, enforceNumericGlue = !hasEvidence)
        if (structuralFailure == "consecutive_sentence_repetition" && genericKnowledgePackGrounding) {
            // Repetition is a presentation defect, not evidence that the grounded answer
            // is factually unusable. Collapse only immediately repeated near-duplicate
            // sentences, then let the normal source/semantic grounding checks decide.
            // No facts are rewritten and non-adjacent/meaningfully distinct statements
            // are preserved.
            val repaired = collapseConsecutiveGroundedDuplicates(acceptedText)
            if (repaired != acceptedText && repaired.isNotBlank()) {
                Log.w(TAG, "LOCAL_GROUNDED_REPETITION_REPAIRED from=${acceptedText.length} to=${repaired.length}")
                acceptedText = repaired
                structuralFailure = structuralOutputFailure(acceptedText, enforceNumericGlue = false)
            }
        }
        if (structuralFailure == "incomplete_tail" && (genericKnowledgePackGrounding || !hasEvidence)) {
            // A bounded generation can stop after a useful set of complete sentences
            // and then begin one unfinished sentence. Preserve the conservative complete
            // prefix instead of throwing away the whole answer. Markdown tables are still
            // excluded by recoverCompleteGroundedPrefix().
            val recovered = recoverCompleteGroundedPrefix(acceptedText)
            if (recovered != null) {
                Log.w(
                    TAG,
                    if (genericKnowledgePackGrounding) {
                        "LOCAL_GROUNDED_TAIL_RECOVERED from=${acceptedText.length} to=${recovered.length}"
                    } else {
                        "LOCAL_ORDINARY_TAIL_RECOVERED from=${acceptedText.length} to=${recovered.length}"
                    }
                )
                acceptedText = recovered
                structuralFailure = structuralOutputFailure(acceptedText, enforceNumericGlue = !hasEvidence)
            }
        }
        if (structuralFailure != null) {
            // Hard quality invariant: malformed decoder output is never an accepted
            // assistant turn, even if provisional deltas were already rendered. The
            // outer Local-first router already owns one bounded detached retry and then
            // Cloud fallback, so committing a known-corrupt answer only contaminates
            // history/Notebook/KV and makes later turns worse.
            Log.w(
                TAG,
                "LOCAL_OUTPUT_REJECTED reason=$structuralFailure chars=${acceptedText.length} " +
                    "streamed=$streamedAny hardGate=true"
            )
            if (!hasEvidence) lm.closeSession()
            return null
        }

        // Echo is also a hard failure. Provisional presentation is not permission to
        // make a known-invalid answer authoritative; onLocalAbort clears provisional UI
        // and the existing bounded Local retry gets a clean session.
        if (isObviousUserEcho(query, acceptedText)) {
            Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=user_echo chars=${acceptedText.length} streamed=$streamedAny hardGate=true")
            if (!hasEvidence) lm.closeSession()
            return null
        }

        // Ordinary Audio-only KB-OFF still needs a lightweight cross-turn guard.
        // This is lexical/structural only (no E5, no domain/topic vocabulary): it
        // prevents a completed answer or question from being accepted again after
        // the user has already moved the conversation forward.
        if (!hasEvidence && !repeatOrRestatementRequested(query)) {
            val qualityHistory = chatStore.recent(120)
            val repetitionFailure = crossTurnRepetitionReason(acceptedText, qualityHistory)
                ?: duplicateAssistantQuestionReason(acceptedText, qualityHistory)
            if (repetitionFailure != null) {
                Log.w(
                    TAG,
                    "LOCAL_OUTPUT_REJECTED reason=$repetitionFailure chars=${acceptedText.length} " +
                        "streamed=$streamedAny hardGate=true"
                )
                lm.closeSession()
                return null
            }
        }

        // Ordinary KB-OFF Local chat must distinguish "model returned text" from
        // "model actually answered". Explicit uncertainty/self-limitation is a failed
        // Local attempt, not a successful turn. Returning null lets the outer router
        // escalate directly when online or use one bounded Local recovery when offline.
        if (!hasEvidence) {
            val nonAnswerReason = ordinaryNonAnswerReason(query, acceptedText)
            if (nonAnswerReason != null) {
                Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=ordinary_non_answer:$nonAnswerReason chars=${acceptedText.length}")
                lm.closeSession()
                return null
            }
            // Ordinary Local acceptance is objective/deterministic only. Embedding
            // similarity is not a reliable binary judge of whether a generated answer
            // is useful, so no post-generation semantic sufficiency veto is applied.
            Log.i(TAG, "LOCAL_SUFFICIENCY_GATE mode=DETERMINISTIC_ONLY")
        }

        // Normal prose is not judged for factual truth by a second Local LLM. Objective
        // facts use the dedicated Cloud/offline factual-confidence path below; this
        // boundary only rejects clear non-answers and malformed/repetitive output.

        // V14 simple-local-RAG: do not use embedding similarity as a binary judge
        // of an already generated grounded answer. Retrieval decides relevance; the
        // GenericKnowledgeContextProvider performs objective integrity checks.
        if (genericKnowledgePackGrounding) {
            Log.i(TAG, "GENERIC_KB_POST_GENERATION_GATE mode=OBJECTIVE_ONLY")
        }

        // Semantic/source validation is retained ONLY for turns that explicitly
        // require verified grounding (KB/evidence mode). Ordinary Audio-only KB-OFF
        // local chat follows AndroLLM's protocol/structural acceptance boundary.
        if (groundingRequired) {
            val validationAttachments = if (groundingRequired && evidenceBudget.groundingAttachments.isNotEmpty()) {
                // Validate against the exact per-source evidence that Gemma actually
                // received.  Do not collapse current attachments, recalled attachments
                // and KB sources into one synthetic source because that destroys
                // provenance and can let a fact from the wrong document validate.
                attachments.filterNot { it.attributes["grounding_required"] == "true" } +
                    evidenceBudget.groundingAttachments
            } else attachments
            val acceptedByValidator = validateAcceptedAnswer(
                query = query,
                answer = acceptedText,
                provider = Provider.LOCAL,
                attachments = validationAttachments,
                history = acceptedHistory
            )
            if (!acceptedByValidator) {
                Log.w(TAG, "LOCAL_OUTPUT_REJECTED reason=semantic_validator chars=${acceptedText.length}")
                if (!hasEvidence) lm.closeSession()
                return null
            }
        } else {
            Log.i(TAG, "ANSWER_VALIDATION_BYPASS provider=LOCAL reason=ordinary_audio_kb_off")
        }

        // Generic KB provenance is app-owned. Finalize presentation only AFTER
        // structural/grounding validation: internal [S#] IDs remain available to the
        // validator, while the accepted user-visible text is citation-clean and the
        // exact sources remain in TurnResult.sourceIds for the collapsed Sources box.
        var acceptedGenericKbSourceIds: List<String> = emptyList()
        if (genericKnowledgePackGrounding) {
            val provenanceAttachments = if (evidenceBudget.groundingAttachments.isNotEmpty()) {
                evidenceBudget.groundingAttachments
            } else {
                attachments
            }
            acceptedGenericKbSourceIds = genericKbVisibleSourceIds(acceptedText, provenanceAttachments)
            acceptedText = finalizeGenericKbProvenance(acceptedText, provenanceAttachments)
        }

        // Passed all gates. Resident KV may be reused only when the assistant text
        // stored natively is exactly the same text that will be shown/spoken/stored.
        // If sanitization changed the model output, retire the native session; the
        // next turn will reseed from authoritative accepted chatStore history.
        if (!hasEvidence) {
            val nativeText = done.text.trim()
            val nativeVisibleText = FixSightOutputSanitizer.compactForLive(nativeText).trim()
            if (nativeVisibleText == acceptedText) {
                lm.commitAcceptedLocalTurn(query, acceptedText)
            } else {
                Log.i(TAG, "LOCAL_SESSION_RETIRED reason=accepted_text_differs_from_native nativeChars=${nativeText.length} acceptedChars=${acceptedText.length}")
                lm.closeSession()
            }
        }

        // Grounded/evidence inference uses a throwaway conversation and leaves any
        // resident text session stale relative to the newly accepted authoritative
        // history. Retire it HERE, based on the evidence actually used by runLocal().
        // Ordinary resident chat keeps its KV session intact.
        if (hasEvidence) {
            lm.closeSession()
            Log.i(TAG, "LOCAL_SESSION_RETIRED reason=accepted_ephemeral_grounded_turn")
        }

        Log.i(TAG, "LOCAL_OUTPUT_ACCEPTED grounded=$hasEvidence chars=${acceptedText.length}")
        Log.i(
            TAG,
            "LOCAL_STAGE_TIMING firstDeltaMs=${firstDeltaAtMs?.let { (it - started).coerceAtLeast(0L) } ?: -1L} " +
                "generationMs=${(System.currentTimeMillis() - started).coerceAtLeast(0L)} outputChars=${acceptedText.length}"
        )

        // Evidence/grounded turns are deliberately not streamed before their
        // acceptance gate. Once accepted, hand the complete text to the turn
        // owner as one delta so it can sentence-assemble/speak it safely.
        if (hasEvidence && onLocalDelta != null && isSafeSpokenFragment(acceptedText)) {
            try {
                onLocalDelta.invoke(acceptedText)
                streamedAny = true
            } catch (t: Throwable) {
                Log.w(TAG, "LOCAL_STREAM_DELTA_CONSUMER_FAILED ${t.message}")
            }
        }

        return TurnResult(
            text = acceptedText,
            provider = Provider.LOCAL,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = streamedAny,
            sourceIds = acceptedGenericKbSourceIds
        )
    }

    /**
     * Grounded-answer acceptance boundary. Ordinary Audio-only KB-OFF local answers
     * bypass this method after protocol/structural checks, matching AndroLLM chat.
     * Source-grounded Cloud fallbacks are also passed through this boundary so KB/
     * attachment ownership cannot be lost during provider recovery.
     */
    private suspend fun validateAcceptedAnswer(
        query: String,
        answer: String,
        provider: Provider,
        attachments: List<BaselineContextAttachment>,
        history: List<AndroChatMessage>
    ): Boolean {
        val groundingRequired = attachments.any { it.attributes["grounding_required"] == "true" }

        val repetitionReason = crossTurnRepetitionReason(answer, history)
        if (repetitionReason != null) {
            Log.w(TAG, "ANSWER_VALIDATION_REJECT provider=$provider reason=$repetitionReason")
            return false
        }

        // A selected-KB answer already has its own conversation-aware relevance
        // and exact-SOP grounding validator. Do not run the generic semantic
        // capability judge on top of it: that duplicate judge can misclassify a
        // legitimate follow-up as "stale_previous_topic" even while every claim is
        // grounded in the active source record. KB-OFF keeps the generic validator
        // unchanged. Repetition protection remains above this boundary.
        val validators = if (groundingRequired) {
            responseValidators.filterNot { it is SemanticCapabilityContextProvider }
        } else {
            responseValidators
        }
        if (groundingRequired) {
            Log.i(
                TAG,
                "ANSWER_VALIDATION_POLICY rev=V3_7_KB_SOURCE_AUTHORITY " +
                    "grounded=true validators=${validators.joinToString(",") { it.javaClass.simpleName }}"
            )
        }
        if (validators.isEmpty()) {
            if (groundingRequired) {
                Log.w(TAG, "ANSWER_VALIDATION_REJECT provider=$provider reason=no_semantic_validator_grounding_required")
                return false
            }
            Log.w(TAG, "ANSWER_VALIDATION_BYPASS provider=$provider reason=no_validator")
            return true
        }
        for (validator in validators) {
            val result = try {
                validator.validate(query, answer, provider, attachments, history)
            } catch (t: Throwable) {
                Log.w(TAG, "ANSWER_VALIDATOR_FAILED ${t.message}")
                val groundingRequired = attachments.any { it.attributes["grounding_required"] == "true" }
                if (groundingRequired) return false else continue
            }
            if (!result.accepted) {
                Log.w(TAG, "ANSWER_VALIDATION_REJECT provider=$provider reason=${result.reason}")
                return false
            }
        }
        Log.i(TAG, "ANSWER_VALIDATION_ACCEPT provider=$provider")
        return true
    }

    /**
     * Evidence budget for the ephemeral grounded local turn. Evidence is selected
     * by provider priority and truncated before it reaches Gemma. The authoritative
     * history always stores only the raw user turn, never this transient evidence.
     */
    /**
     * Topic-agnostic cross-turn quality gate. It rejects a near-copy of the
     * immediately previous assistant reply, which is the failure observed when
     * Gemma repeated the same completed troubleshooting action after the user
     * supplied a new state. It does not inspect domains or device keywords.
     */
    private fun crossTurnRepetitionReason(
        answer: String,
        history: List<AndroChatMessage>
    ): String? {
        val previousAssistant = history.lastOrNull { it.role == "assistant" }?.content.orEmpty().trim()
        if (previousAssistant.isBlank()) return null

        val a = normalizedTokenSet(answer)
        val b = normalizedTokenSet(previousAssistant)
        if (a.size < 10 || b.size < 10) return null
        val union = (a union b).size.coerceAtLeast(1)
        val jaccard = (a intersect b).size.toDouble() / union.toDouble()
        if (jaccard >= CROSS_TURN_DUPLICATE_JACCARD) {
            return "cross_turn_near_duplicate_${"%.2f".format(jaccard)}"
        }
        return null
    }

    /**
     * Rejects a near-duplicate assistant question from recent accepted history.
     * The comparison is topic-independent: it uses only normalized surface overlap
     * and never a domain/intent keyword list.
     */
    private fun duplicateAssistantQuestionReason(
        answer: String,
        history: List<AndroChatMessage>
    ): String? {
        fun questions(text: String): List<String> = text
            .split(Regex("(?<=[?？])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.endsWith("?") || it.endsWith("？") }

        val current = questions(answer)
        if (current.isEmpty()) return null
        val previous = history
            .filter { it.role == "assistant" }
            .takeLast(20)
            .flatMap { questions(it.content) }
        if (previous.isEmpty()) return null

        for (candidate in current) {
            val a = normalizedTokenSet(candidate)
            if (a.size < 3) continue
            for (prior in previous) {
                val b = normalizedTokenSet(prior)
                if (b.size < 3) continue
                val overlap = (a intersect b).size.toDouble()
                val union = (a union b).size.coerceAtLeast(1).toDouble()
                val jaccard = overlap / union
                val containment = overlap / minOf(a.size, b.size).coerceAtLeast(1).toDouble()
                if (jaccard >= 0.72 || containment >= 0.86) {
                    return "repeated_question_${"%.2f".format(maxOf(jaccard, containment))}"
                }
            }
        }
        return null
    }

    /**
     * Domain-neutral response-shape override. Explicit requests for a long/stepwise
     * answer must not be under-budgeted by a fuzzy semantic scope classifier.
     */
    private fun explicitDetailedResponseRequested(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        if (q.isBlank()) return false
        val patterns = listOf(
            Regex("""\b(?:step\s*[- ]?by\s*[- ]?step|in\s+detail|detailed(?:ly)?|thorough(?:ly)?|comprehensive(?:ly)?|complete\s+(?:steps?|procedure|process|guide)|all\s+(?:steps?|details?))\b"""),
            Regex("""(?:विस्तार\s+से|पूरी\s+जानकारी|सभी\s+चरण|चरण\s+दर\s+चरण)""")
        )
        return patterns.any { it.containsMatchIn(q) }
    }

    /**
     * Repetition guards protect against stale model loops, but must not reject a
     * response when the user explicitly asks to hear the same content again or in
     * another wording. These are response-control phrases, never topic keywords.
     */
    private fun repeatOrRestatementRequested(query: String): Boolean {
        val q = query.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        if (q.isBlank()) return false
        val patterns = listOf(
            Regex("""\b(?:repeat(?:\s+that|\s+it|\s+again)?|say\s+(?:that|it)\s+again|explain\s+(?:that|it)\s+again|rephrase|say\s+it\s+differently|simplify\s+(?:that|it))\b"""),
            Regex("""(?:फिर\s+से|दोबारा|दुबारा|एक\s+बार\s+फिर)""")
        )
        return patterns.any { it.containsMatchIn(q) }
    }

    private fun normalizedTokenSet(text: String): Set<String> = text.lowercase()
        .replace(Regex("""[^\p{L}\p{N}']+"""), " ")
        .trim()
        .split(Regex("""\s+"""))
        .filter { it.length >= 2 }
        .toSet()


    /**
     * Generic knowledge-pack citations are app-owned, not model-generated. Strip only
     * trailing Source/Sources footers and append exact retrieval provenance carried by
     * the attachment. This guarantees the visible citation corresponds to material
     * actually supplied to the answer generator.
     */
    private fun stripGeneratedGenericKbFooter(answer: String): String = answer.trim()
        .replace(
            Regex("""(?is)\n\s*Sources?\s*:\s*[^\n]*(?:\n\s*[-•]\s*[^\n]*)*\s*$"""),
            ""
        )
        .trimEnd()

    private fun recoverCompleteGroundedPrefix(answer: String): String? {
        val clean = answer.trim()
        if (clean.length < 160 || clean.contains('|')) return null
        val lastBoundary = clean.indexOfLast { it in setOf('.', '!', '?', '।', '！', '？') }
        if (lastBoundary < 120) return null
        val prefix = clean.substring(0, lastBoundary + 1).trim()
        // Recovery is intentionally conservative: do not throw away a large fraction
        // of the model output, which could hide a materially incomplete answer.
        if (prefix.length < (clean.length * 0.70).toInt()) return null
        return prefix
    }

    private fun genericKbVisibleSourceIds(
        answer: String,
        attachments: List<BaselineContextAttachment>
    ): List<String> {
        val refs = attachments.asSequence()
            .filter { it.attributes["grounding_required"] == "true" && it.attributes["knowledge_pack"] == "generic" }
            .flatMap { it.attributes["kb_source_refs"].orEmpty().lineSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (refs.isEmpty()) return emptyList()

        val citedIds = Regex("""\[(S\d+)]""", RegexOption.IGNORE_CASE)
            .findAll(answer)
            .map { it.groupValues[1].uppercase() }
            .toSet()
        val selected = if (citedIds.isEmpty()) refs else refs.filter { ref ->
            val id = Regex("""^\s*(S\d+)\s*::""", RegexOption.IGNORE_CASE)
                .find(ref)?.groupValues?.getOrNull(1)?.uppercase()
            id != null && id in citedIds
        }
        // If the model emitted no usable IDs, fall back to all verified evidence refs;
        // never make the Sources box empty merely because the model omitted citations.
        val visible = selected.ifEmpty { refs }
        return visible.map { "kbref:$it" }.distinct().take(20)
    }

    private fun finalizeGenericKbProvenance(
        answer: String,
        attachments: List<BaselineContextAttachment>
    ): String {
        // Source provenance is carried separately in TurnResult.sourceIds and rendered
        // by MainActivity's single collapsed Sources box. Keep model citation IDs until
        // AFTER grounding validation (the validator uses them to reject invented S-IDs),
        // then remove both inline [S#] markers and any model-generated Source footer
        // from user-visible / spoken text. Retrieval metadata remains untouched.
        val generic = attachments.firstOrNull {
            it.attributes["grounding_required"] == "true" && it.attributes["knowledge_pack"] == "generic"
        } ?: return answer.trim()
        if (generic.attributes["kb_source_refs"].isNullOrBlank()) return answer.trim()

        return stripGeneratedGenericKbFooter(answer)
            .replace(Regex("""\s*\[(?:S\d+)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[ \t]+(?=[,.;:!?।！？])"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    private fun budgetKbCaseContext(
        raw: String,
        history: List<AndroChatMessage>,
        query: String
    ): String {
        if (raw.isBlank()) return ""
        fun est(text: String): Int = kotlin.math.ceil(text.length / 3.0).toInt().coerceAtLeast(1)
        val historyTokens = history.sumOf { est(it.content) + 8 }
        val fixedWithoutCase = SYSTEM_PROMPT_TOKEN_ESTIMATE + RESERVED_OUTPUT_TOKENS +
            CONTEXT_SAFETY_TOKENS + est(query) + historyTokens
        val remaining = (effectiveContextWindowTokens - fixedWithoutCase).coerceAtLeast(0)
        // Preserve at least ~700 tokens for actual SOP evidence. Notebook is important
        // case memory, but verified guidance must retain enough budget to ground answer.
        val maxCaseTokens = (remaining - 700).coerceIn(0, 1_200)
        if (maxCaseTokens <= 0) return ""
        return raw.take(maxCaseTokens * 3).trim()
    }

    private data class LocalEvidenceBudget(
        val text: String,
        val groundingText: String,
        val groundingAttachments: List<BaselineContextAttachment>
    )

    private fun budgetLocalEvidence(
        attachments: List<BaselineContextAttachment>,
        history: List<AndroChatMessage>,
        query: String,
        fallbackEvidence: String,
        additionalReservedText: String = ""
    ): LocalEvidenceBudget {
        if (attachments.isEmpty()) return LocalEvidenceBudget("", "", emptyList())
        fun est(text: String): Int = kotlin.math.ceil(text.length / 3.0).toInt().coerceAtLeast(1)
        val historyTokens = history.sumOf { est(it.content) + 8 }
        val additionalReservedTokens = if (additionalReservedText.isBlank()) 0 else est(additionalReservedText)
        val fixed = SYSTEM_PROMPT_TOKEN_ESTIMATE + RESERVED_OUTPUT_TOKENS + CONTEXT_SAFETY_TOKENS +
            est(query) + historyTokens + additionalReservedTokens
        val availableTokens = (effectiveContextWindowTokens - fixed).coerceAtLeast(0)
        if (availableTokens < 96) {
            Log.w(TAG, "LOCAL_EVIDENCE_SKIPPED reason=context_budget availableTokens=$availableTokens")
            return LocalEvidenceBudget("", "", emptyList())
        }
        val genericKbAttachment = attachments.firstOrNull {
            it.attributes["grounding_required"] == "true" && it.attributes["knowledge_pack"] == "generic"
        }
        val genericIntent = genericKbAttachment?.attributes?.get("kb_query_intent")
        // Compact-RAG contract for generic KB generation: retrieval may search broadly,
        // but the on-device generator should receive only a small, high-signal packet.
        // Small quantized LLMs synthesize more reliably from a few strong passages than
        // from every retrieved hit. Retrieval/ranking itself is intentionally unchanged.
        val desiredEvidenceTokens = when (genericIntent) {
            // Procedures can legitimately span several ordered source blocks. Keep more
            // evidence headroom so compact-RAG never deletes later supported steps.
            SemanticQueryIntent.PROCEDURE.name -> 1_450
            // Comparisons/enumerations often need evidence for more than one entity/item.
            // Compact the packet, but retain enough room for multiple complete blocks.
            SemanticQueryIntent.COMPARISON.name,
            SemanticQueryIntent.ENUMERATION.name -> 1_250
            null -> 900
            else -> 900
        }
        // A user's own file (PDF, Word, slides, photo text) is the whole subject of the turn: the
        // generic 900-token packet cut a page of extracted text to ~2,700 characters, so answers
        // about attached documents missed most of the relevant section. Same size as a KB packet.
        val userAttachmentTurn = genericKbAttachment == null && attachments.any { it.attributes["user_attachment"] == "true" }
        val maxEvidenceTokens = minOf(
            availableTokens,
            if (userAttachmentTurn) maxOf(desiredEvidenceTokens, USER_ATTACHMENT_EVIDENCE_TOKENS) else desiredEvidenceTokens
        )
        val maxChars = maxEvidenceTokens * 3
        val out = StringBuilder()
        val grounding = StringBuilder()
        val groundingAttachments = mutableListOf<BaselineContextAttachment>()
        for (attachment in attachments.sortedByDescending { it.priority }) {
            val clean = FixSightOutputSanitizer.sanitize(attachment.text).trim()
            if (clean.isBlank()) continue
            val framed = if (attachment.attributes["knowledge_pack"] == "generic") {
                clean
            } else {
                "[Evidence source ${attachment.sourceId}]\n$clean"
            }
            val remaining = maxChars - out.length
            if (remaining <= 0) break
            if (out.isNotEmpty()) out.append("\n\n")

            val isGenericGrounding = attachment.attributes["grounding_required"] == "true" &&
                attachment.attributes["knowledge_pack"] == "generic"
            val piece: String
            val budgetedAttributes: Map<String, String>
            if (isGenericGrounding && framed.length > remaining) {
                // Generic KB evidence is structured as a header followed by atomic
                // "Source ID: Sx" blocks. Never cut a source block mid-passage: retain
                // only complete blocks that fit, then rebuild source/record metadata from
                // those exact IDs. This keeps generation evidence, validation evidence
                // and user-visible provenance synchronized after context-window budgeting.
                val marker = Regex("""(?m)^Source ID: (S\d+)\s*$""")
                val matches = marker.findAll(framed).toList()
                if (matches.isEmpty()) {
                    piece = framed.take(remaining)
                    budgetedAttributes = attachment.attributes
                } else {
                    val header = framed.substring(0, matches.first().range.first).trimEnd()
                    val keptBlocks = mutableListOf<Pair<String, String>>()
                    // Keep generation context compact by token/character budget, but do
                    // not impose a hard source-count cap. A fixed top-N cap can silently
                    // remove one side of a comparison or later steps of a procedure. The
                    // KB provider has already ranked and compacted these blocks; retain as
                    // many COMPLETE ranked blocks as fit in the intent-aware budget.
                    var usedChars = header.length
                    matches.forEachIndexed { index, match ->
                        val start = match.range.first
                        val end = if (index + 1 < matches.size) matches[index + 1].range.first else framed.length
                        val block = framed.substring(start, end).trim()
                        val cost = (if (usedChars > 0) 2 else 0) + block.length
                        if (usedChars + cost <= remaining) {
                            keptBlocks += match.groupValues[1].uppercase() to block
                            usedChars += cost
                        }
                    }
                    piece = buildString {
                        if (header.isNotBlank()) append(header)
                        keptBlocks.forEach { (_, block) ->
                            if (isNotEmpty()) append("\n\n")
                            append(block)
                        }
                    }.trim()
                    val keptIds = keptBlocks.map { it.first }.toSet()
                    val filteredRefs = attachment.attributes["kb_source_refs"].orEmpty()
                        .lineSequence().filter { line ->
                            val id = Regex("""^\s*(S\d+)\s*::""", RegexOption.IGNORE_CASE)
                                .find(line)?.groupValues?.getOrNull(1)?.uppercase()
                            id != null && id in keptIds
                        }.joinToString("\n")
                    val originalIds = attachment.attributes["kb_record_ids"].orEmpty().split(',')
                        .map { it.trim() }.filter { it.isNotBlank() }
                    val keptRecordIds = keptIds.mapNotNull { id ->
                        id.removePrefix("S").toIntOrNull()?.minus(1)?.let { index -> originalIds.getOrNull(index) }
                    }.joinToString(",")
                    val packetComplete = keptIds.size == matches.size
                    val priorCoverageComplete = !attachment.attributes["kb_coverage_complete"].equals("false", ignoreCase = true)
                    budgetedAttributes = attachment.attributes + mapOf(
                        "kb_source_refs" to filteredRefs,
                        "kb_primary_source_ref" to filteredRefs.lineSequence().firstOrNull().orEmpty(),
                        "kb_record_ids" to keptRecordIds,
                        "kb_evidence_complete_packet" to packetComplete.toString(),
                        "kb_coverage_complete" to (priorCoverageComplete && packetComplete).toString(),
                        "kb_budgeted_source_count" to keptIds.size.toString()
                    )
                }
            } else {
                piece = framed.take(remaining)
                budgetedAttributes = attachment.attributes
            }
            out.append(piece)
            if (attachment.attributes["grounding_required"] == "true") {
                if (grounding.isNotEmpty()) grounding.append("\n\n")
                grounding.append(piece)
                groundingAttachments += attachment.copy(
                    text = piece,
                    attributes = budgetedAttributes + mapOf(
                        "grounding_required" to "true",
                        "exact_generation_evidence" to "true"
                    )
                )
            }
        }
        val result = out.toString().trim()
        val groundingResult = grounding.toString().trim()
        Log.i(
            TAG,
            "LOCAL_EVIDENCE_BUDGET attached=${attachments.size} chars=${result.length} " +
                "groundingChars=${groundingResult.length} maxTokens=$maxEvidenceTokens fallbackChars=${fallbackEvidence.length}"
        )
        return LocalEvidenceBudget(result, groundingResult, groundingAttachments)
    }

    /**
     * Deterministic protocol/echo guard for source-grounded Local generations.
     * It intentionally runs only when evidence is present, so ordinary AndroLLM
     * chat remains unchanged. This closes the failure where Gemma returned a
     * serialized message object containing an earlier user question and the E5
     * grounding validator accepted it because the echoed text was case-relevant.
     */
    private fun groundedOutputProtocolFailure(
        text: String,
        history: List<AndroChatMessage>
    ): String? {
        val clean = text.trim()
        if (clean.isBlank()) return "blank_grounded_output"

        // Reject only a top-level chat/message envelope shape, not arbitrary JSON
        // requested by the user. The observed corruption is exactly a single
        // type=text/text=... message object (optionally wrapped in a one-item array).
        val compact = clean.replace(Regex("\\s+"), " ")
        val messageEnvelope = Regex(
            """(?is)^\s*\[?\s*\{\s*[\"']type[\"']\s*:\s*[\"']text[\"']\s*,\s*[\"']text[\"']\s*:\s*[\"'].*[\"']\s*\}\s*\]?\s*$"""
        )
        if (messageEnvelope.matches(compact)) return "serialized_text_message_envelope"

        // A grounded answer may reference case facts, but it must not simply replay
        // a recent user turn. Check the last few accepted user turns only; this is
        // language/domain neutral and does not alter KB selection or evidence.
        val recentUsers = history.asReversed()
            .filter { it.role.equals("user", ignoreCase = true) }
            .take(4)
        for (message in recentUsers) {
            if (isObviousUserEcho(message.content, clean)) {
                return "recent_user_turn_echo"
            }

            // Small-model paraphrase echoes can escape exact surface checks. Only
            // apply this stronger overlap rule when the generated output itself is
            // question-shaped; legitimate explanatory answers are not affected.
            if (clean.endsWith("?") || clean.endsWith("？")) {
                val userTokens = normalizedTokenSet(message.content)
                val answerTokens = normalizedTokenSet(clean)
                if (userTokens.size >= 5 && answerTokens.size >= 5) {
                    val overlap = userTokens.intersect(answerTokens).size
                    val containment = overlap.toDouble() /
                        minOf(userTokens.size, answerTokens.size).coerceAtLeast(1).toDouble()
                    val novel = answerTokens.subtract(userTokens).size
                    if (containment >= 0.60 && novel <= 5) {
                        return "recent_user_question_paraphrase_echo"
                    }
                }
            }
        }
        return null
    }

    private fun containsPrivateContextLeak(text: String): Boolean {
        val normalized = text.lowercase()
        val structuralMarkers = listOf(
            "[reference evidence]", "[end reference evidence]",
            "<knowledge>", "</knowledge>",
            "<internal_context>", "<internal_visual_context>",
            "private supporting passage", "[evidence source ",
            "passage 1 (", "passage 2 (", "passage 3 ("
        )
        if (structuralMarkers.any { normalized.contains(it) }) return true
        if (Regex("(?im)^\\s*keywords\\s*:").containsMatchIn(text)) return true
        if (Regex("(?im)^\\s*(?:source|file_name|page_number)\\s*:").containsMatchIn(text)) return true
        return false
    }

    /**
     * Topic-agnostic structural gate for ordinary local output.
     * Returns a stable log reason or null when the answer shape is acceptable.
     */
    private fun structuralOutputFailure(text: String, enforceNumericGlue: Boolean = true): String? {
        val clean = text.trim()
        if (clean.isBlank()) return "blank"
        if ('\uFFFD' in clean || '\u0000' in clean) return "invalid_character"
        // Decoder corruption occasionally glues a long numeric tail directly to a word
        // (for example "to1707077401"). This is a protocol/shape failure, not semantic
        // fact checking, and can be rejected deterministically without another LLM.
        if (enforceNumericGlue && Regex("""(?i)(?:\p{L}\d{7,}|\d{7,}\p{L})""").containsMatchIn(clean)) {
            return "corrupt_numeric_glue"
        }
        // Cheap boundary check for the specific small-model failure where a turn
        // ends after announcing the topic instead of answering it. This is not an
        // LLM validator and does not inspect the user's domain.
        if (clean.length <= 100) {
            val lower = clean.lowercase()
            val stub = listOf(
                "let's talk about",
                "let us talk about",
                "let's discuss",
                "let me get some information",
                "let me find some information"
            ).any { it in lower }
            if (stub) return "conversational_stub"
        }

        // Long output without terminal punctuation is the failure shape previously
        // observed when the decoder stopped mid-word. Short factual answers such as
        // "4" remain valid even without punctuation.
        if (
            clean.length >= 80 &&
            clean.lastOrNull() !in setOf('.', '!', '?', '।', '！', '？', ')', ']', '}')
        ) {
            return "incomplete_tail"
        }

        val sentences = Regex("""[^.!?।！？]+(?:[.!?।！？]+|$)""")
            .findAll(clean)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        for (i in 1 until sentences.size) {
            if (sentencesNearDuplicate(sentences[i - 1], sentences[i])) {
                return "consecutive_sentence_repetition"
            }
        }
        return null
    }

    private fun collapseConsecutiveGroundedDuplicates(text: String): String {
        val parts = Regex("""[^.!?।！？]+(?:[.!?।！？]+|$)""")
            .findAll(text.trim())
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (parts.size < 2) return text.trim()
        fun identity(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\p{S}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val kept = ArrayList<String>(parts.size)
        for (sentence in parts) {
            val previous = kept.lastOrNull()
            // Repair only exact/effectively-identical adjacent repetition. Similar
            // sentences can encode distinct conditions and must never be deleted by
            // deterministic cleanup; the normal retry/Cloud path handles those.
            if (previous != null && identity(previous) == identity(sentence)) continue
            kept += sentence
        }
        return kept.joinToString(" ").trim()
    }

    private fun sentencesNearDuplicate(a: String, b: String): Boolean {
        // Language-neutral normalization: no English stop-word or filler lists.
        // The same token-overlap rule therefore works for English, Hindi,
        // Hinglish, and other scripts without encoding domain vocabulary.
        val ta = normalizedTokenSet(a)
        val tb = normalizedTokenSet(b)
        if (ta.size < 3 || tb.size < 3) return false
        val overlap = ta.intersect(tb).size
        val union = ta.union(tb).size.coerceAtLeast(1)
        val jaccard = overlap.toFloat() / union.toFloat()
        val containment = overlap.toFloat() / minOf(ta.size, tb.size).toFloat()
        return jaccard >= CROSS_TURN_DUPLICATE_JACCARD ||
            (overlap >= 3 && containment >= 0.90f)
    }

    private fun isSafeSpokenFragment(text: String): Boolean =
        FixSightOutputSanitizer.sanitize(text).isNotBlank() && !containsPrivateContextLeak(text)

    /**
     * Remove only a leading sentence that is itself an obvious echo of the user's
     * current turn. If the whole generation is just an echo this returns blank, so
     * the normal local-reject/cloud-fallback path runs. This is language/domain-neutral.
     */
    private fun stripLeadingUserEcho(user: String, answer: String): String {
        val clean = answer.trim()
        if (clean.isBlank()) return clean
        if (isObviousUserEcho(user, clean)) return ""

        val boundary = clean.indexOfFirst {
            it == '.' || it == '!' || it == '?' || it == '।' || it == '！' || it == '？' || it == '\n'
        }
        if (boundary < 0) return clean

        val firstSentence = clean.substring(0, boundary + 1).trim()
        if (!isObviousUserEcho(user, firstSentence)) return clean
        return clean.substring(boundary + 1).trimStart()
    }

    /**
     * Ordinary KB-OFF Local answers need one cheap adequacy boundary in addition to
     * syntax/repetition checks. This does not judge factual truth (objective facts use
     * the separate factual verifier). It only detects clear non-answers/self-limitations
     * that should never count as a successful Local turn.
     */
    private fun ordinaryNonAnswerReason(query: String, answer: String): String? {
        val raw = answer.trim()
        if (raw.isBlank()) return "blank"
        val a = raw.lowercase(java.util.Locale.ROOT)
        // "My information may be out of date - tap Check online" is the instructed, useful reply
        // for current events, not a failed answer (rejecting it left the user with an error offline).
        if (a.contains("check online")) return null
        val answerWords = raw.split(Regex("\\s+")).count { token -> token.any(Char::isLetterOrDigit) }
        val queryWords = query.trim().split(Regex("\\s+")).count { token -> token.any(Char::isLetterOrDigit) }
        val strongInabilityMarkers = listOf(
            "i don't know",
            "i do not know",
            "i cannot answer",
            "i can't answer",
            "i am unable to answer",
            "i'm unable to answer",
            "i don't have access",
            "i do not have access",
            "i cannot access",
            "i can't access",
            "i don't have the information",
            "i do not have the information",
            "as an ai",
            "as a language model"
        )
        if (strongInabilityMarkers.any { a.startsWith(it) }) return "explicit_uncertainty_or_inability"

        // A short uncertainty-only response is a failed answer. A longer answer that
        // merely contains a reasonable caveat ("I'm not sure this is the only cause,
        // but...") remains eligible so useful best-effort Local guidance is preserved.
        val softUncertaintyMarkers = listOf(
            "i'm not sure",
            "i am not sure",
            "i don't have enough information",
            "i do not have enough information",
            "not enough information",
            "insufficient information"
        )
        if (answerWords <= 18 && softUncertaintyMarkers.any { a.startsWith(it) }) {
            return "explicit_uncertainty_or_inability"
        }
        if (a == "[[unsure]]" || a == "unsure" || a == "unknown") return "explicit_unsure_sentinel"

        if (queryWords >= 8 && answerWords <= 3 && raw.length < 28) return "too_short_for_substantive_request"

        return null
    }

    private fun isObviousUserEcho(user: String, answer: String): Boolean {
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val u = norm(user)
        val a = norm(answer)
        if (u.isBlank() || a.isBlank()) return false
        if (u == a) return true
        if (a.length <= u.length + 12 && (a.contains(u) || u.contains(a))) return true

        // Catch near-rephrasings that add almost no new content. This is purely
        // token/novelty based and contains no language or domain phrases. A tiny
        // surface-form normalization makes plural/singular echoes comparable without
        // introducing a topic dictionary (e.g. "kite" vs "kites").
        fun echoTokens(text: String): Set<String> = normalizedTokenSet(text).map { token ->
            if (token.length >= 5 && token.endsWith("s")) token.dropLast(1) else token
        }.toSet()
        val userTokens = echoTokens(user)
        val answerTokens = echoTokens(answer)
        if (userTokens.size >= 4 && answerTokens.size >= 4) {
            val overlap = userTokens.intersect(answerTokens).size
            val containment = overlap.toDouble() / minOf(userTokens.size, answerTokens.size).coerceAtLeast(1).toDouble()
            val answerNovel = answerTokens.subtract(userTokens).size
            if (containment >= 0.72 && answerNovel <= 2 && answerTokens.size <= userTokens.size + 2) return true
        }
        return false
    }

    suspend fun observeVisualFrame(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        visionCapabilityMutex.withLock {
            observeVisualFrameInternal(
                "Observe the current frame and report only visible facts relevant to ongoing assistance.",
                bitmap
            )
        }
    }

    private suspend fun observeVisualFrameInternal(query: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""

        // Observation is also model-based: use the selected local VLM (or the Meta vision stack) as
        // the eyes first. This makes KB+camera and KB+image usable offline.
        if (localCameraReady) {
            val localObservation = try {
                withTimeout(engineScaledTimeout(LIVE_VISUAL_OBSERVER_TIMEOUT_MS)) {
                    runLocalVisualObservation(
                        query = query,
                        bitmap = bitmap
                    ).trim()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "VISION_LOCAL_OBSERVATION_FAILED ${t.javaClass.simpleName}: ${t.message}")
                ""
            }
            if (localObservation.isNotBlank()) {
                Log.i(TAG, "VISION_LOCAL_OBSERVATION_ACCEPTED chars=${localObservation.length}")
                return localObservation
            }
            Log.w(TAG, "VISION_LOCAL_OBSERVATION_REJECTED fallback=cloud")
        }

        if (!canAttemptCloud()) {
            Log.w(TAG, "VISION_OBSERVATION_UNAVAILABLE localVision=${localModelManager?.isReadyForVision == true} canCloud=${canAttemptCloud()} network=${networkDiagnosticHint()} hasKey=${groqProvider.hasApiKey()} status=${groqProvider.status()}")
            return ""
        }
        return try {
            VisionWorkCoordinator.withVisionWork("cloud_visual_observation") {
                withTimeout(CLOUD_TURN_TIMEOUT_MS) {
                    val history = chatStore.historyForCloud(currentQuery = query)
                    groqProvider.analyzeImageObservation(
                        messages = history,
                        currentQuery = query,
                        bitmap = bitmap
                    ).trim()
                }
            }
        } catch (te: TimeoutCancellationException) {
            groqProvider.cancelActiveCall()
            Log.w(TAG, "VISION_CLOUD_OBSERVATION_TIMEOUT")
            ""
        } catch (ce: CancellationException) {
            groqProvider.cancelActiveCall()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "VISION_CLOUD_OBSERVATION_FAILED ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    private fun buildEvidence(
        attachments: List<BaselineContextAttachment>,
        visualContext: String,
        bitmapPresent: Boolean,
        cloudVisualObservation: String,
        hasUsefulVisualObservation: Boolean,
        kbCaseContext: String = ""
    ): String = buildString {
        val base = renderExtensionContext(attachments)
        if (base.isNotBlank()) append(base)

        if (kbCaseContext.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("<active_kb_case_notebook>\n")
            append(kbCaseContext.trim().take(6_500))
            append("\n</active_kb_case_notebook>")
        }

        if (visualContext.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("<internal_visual_context>\n")
            append(visualContext.trim().take(3_500))
            append("\n</internal_visual_context>")
        }

        if (bitmapPresent) {
            if (isNotEmpty()) append("\n")
            append("<internal_visual_context>\n")
            if (hasUsefulVisualObservation) {
                append("Current camera-frame observation from the vision capability:\n")
                append(cloudVisualObservation.take(4_000))
            } else {
                append(
                    "A current camera frame exists, but no reliable visual observation is available " +
                        "for this turn. Do not claim to see specific visual details."
                )
            }
            append("\n</internal_visual_context>")
        }
    }

    private suspend fun runExternalCapabilities(
        query: String,
        evidence: String,
        safetyCritical: Boolean = false,
        groundedKnowledge: Boolean = false
    ): TurnResult? {
        val fullHistory = chatStore.historyForCloud(currentQuery = query)
        val history = try {
            val accepted = chatStore.recent(120)
            if (semanticCapabilityProvider?.isContextDependentForRecall(query, accepted) == true) {
                fullHistory.takeLast(4)
            } else emptyList()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "EXTERNAL_CONTEXT_FILTER_FAILED ${t.javaClass.simpleName}:${t.message}")
            emptyList()
        }
        val started = System.currentTimeMillis()
        Log.i(TAG, "EXTERNAL_CONTEXT_POLICY dependentHistoryMessages=${history.size} fullHistoryMessages=${fullHistory.size}")
        // Ordinary live/current queries should not inherit the large normal-turn
        // evidence payload. Keep evidence only when the caller explicitly needs
        // grounded/safety context; GroqChatProvider applies an additional compact cap.
        // A follow-up ("what is the latest on this") is searched with its subject: the previous
        // question goes into the search text itself (the web search runs on the question alone).
        val previousQuestion = history.lastOrNull { it.first == "user" }?.second?.let { extractUserRequest(it) }.orEmpty()
        val searchQuery = if (previousQuestion.isNotBlank() && !previousQuestion.equals(query.trim(), ignoreCase = true))
            "${query.trim()} (follow-up to the earlier question: \"${previousQuestion.take(300)}\")" else query.trim()
        val current = if ((groundedKnowledge || safetyCritical) && evidence.isNotBlank()) {
            "<internal_context>\n${evidence.trim().take(4_000)}\n</internal_context>\n\n" +
                "<user_request>\n${searchQuery.take(4_000)}\n</user_request>"
        } else {
            searchQuery.take(4_000)
        }
        val text = groqProvider.chatWithExternalCapabilities(
            history,
            current,
            safetyCritical = safetyCritical,
            groundedKnowledge = groundedKnowledge
        )
        return if (text.isBlank()) null else TurnResult(
            text = text, provider = Provider.CLOUD,
            elapsedMs = System.currentTimeMillis() - started, streamedLocally = false
        )
    }

    private suspend fun runGroundedKbCloud(
        query: String,
        evidence: String,
        safetyCritical: Boolean = false,
        genericKnowledge: Boolean = false,
        validationAttachments: List<BaselineContextAttachment> = emptyList()
    ): TurnResult? {
        if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED || evidence.isBlank()) return null
        val history = chatStore.historyForCloud(currentQuery = query).takeLast(4)
        val started = System.currentTimeMillis()
        return try {
            Log.i(
                TAG,
                "KB_CLOUD_GROUNDED_BEGIN rev=V7_SHARED_EVIDENCE_CONTEXT " +
                    "generic=$genericKnowledge safety=$safetyCritical evidenceChars=${evidence.length} acceptedHistoryMessages=${history.size}"
            )
            val genericAttachment = validationAttachments.firstOrNull {
                it.attributes["grounding_required"] == "true" && it.attributes["knowledge_pack"] == "generic"
            }
            val coverageIncomplete = genericAttachment?.let {
                it.attributes["kb_coverage_complete"].equals("false", ignoreCase = true) ||
                    it.attributes["kb_evidence_complete_packet"].equals("false", ignoreCase = true)
            } == true
            val coverageConstraint = if (coverageIncomplete) {
                "\n<coverage_constraint>The retrieved KB evidence does not cover every requested dimension. Answer only from the supplied evidence and explicitly state that the answer is partial/incomplete rather than filling gaps from pretrained knowledge.</coverage_constraint>"
            } else ""
            val current =
                "<internal_context>\n${evidence.trim().take(7_000)}\n</internal_context>" + coverageConstraint + "\n\n" +
                    "<user_request>\n${query.trim().take(3_000)}\n</user_request>"
            val genericIntent = genericAttachment?.attributes?.get("kb_query_intent")
            val maxCloudTokens = if (genericKnowledge) {
                when (genericIntent) {
                    SemanticQueryIntent.PROCEDURE.name -> 1_280
                    SemanticQueryIntent.COMPARISON.name -> 1_024
                    SemanticQueryIntent.ENUMERATION.name -> 960
                    else -> 896
                }
            } else {
                when (runCatching { semanticCapabilityProvider?.classifyResponseScope(query, chatStore.recentWithinTokenBudget(historyTokenBudget, 120)) }.getOrNull()?.scope) {
                    SemanticResponseScope.DETAILED -> 1_024
                    SemanticResponseScope.NORMAL -> 768
                    SemanticResponseScope.SHORT -> 512
                    null -> 768
                }
            }
            val text = if (genericKnowledge) {
                groqProvider.chatGenericKnowledgeText(
                    messages = history,
                    currentQuery = current,
                    safetyCritical = safetyCritical,
                    maxOutputTokens = maxCloudTokens
                )
            } else {
                groqProvider.chatGroundedKnowledgeText(
                    messages = history,
                    currentQuery = current,
                    safetyCritical = safetyCritical,
                    maxOutputTokens = maxCloudTokens
                )
            }
            val groundedGenericSourceIds = if (genericKnowledge && validationAttachments.isNotEmpty()) {
                genericKbVisibleSourceIds(text, validationAttachments)
            } else emptyList()
            val finalizedText = text.trim()
            if (finalizedText.isBlank()) {
                null
            } else {
                // Cloud is the final recovery provider by product contract. Once the
                // grounded Cloud call succeeds, do not route its answer back through
                // Local semantic/style validators that can recreate the same failure
                // which triggered escalation. Preserve the Cloud answer as returned
                // without post-generation Local rewriting or semantic rejection.
                Log.i(TAG, "KB_CLOUD_GROUNDED_ACCEPT passthrough=true generic=$genericKnowledge")
                TurnResult(
                    text = finalizedText,
                    provider = Provider.CLOUD,
                    elapsedMs = System.currentTimeMillis() - started,
                    streamedLocally = false,
                    sourceIds = groundedGenericSourceIds
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) onApiKeyFailed?.invoke()
            runtimeHealth.cloudFailure("kb_grounded_${t.javaClass.simpleName}", groqProvider.status())
            null
        }
    }

    private suspend fun runSafetyCloud(query: String, evidence: String): TurnResult? {
        if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) return null
        val history = chatStore.historyForCloud(currentQuery = query)
        val started = System.currentTimeMillis()
        return try {
            val current = if (evidence.isBlank()) query else
                "<internal_context>\n$evidence\n</internal_context>\n\n<user_request>\n$query\n</user_request>"
            val text = groqProvider.chatSafetyText(history, current)
            if (text.isBlank()) null else TurnResult(
                text = text,
                provider = Provider.CLOUD,
                elapsedMs = System.currentTimeMillis() - started,
                streamedLocally = false
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) onApiKeyFailed?.invoke()
            runtimeHealth.cloudFailure("safety_${t.javaClass.simpleName}", groqProvider.status())
            null
        }
    }

    /**
     * Decode at most [maxImages] temporary recovery bitmaps across all attachments.
     * The previous flatMap implementation passed the full remaining slot count to
     * every attachment and only truncated after decoding, causing avoidable bitmap
     * pressure on multi-attachment turns.
     */
    private fun decodeRecoveryBitmapsWithinSlots(
        attachments: List<Phase1UserAttachment>,
        maxImages: Int
    ): List<Bitmap> {
        val limit = maxImages.coerceIn(0, 4)
        if (limit == 0) return emptyList()
        val out = mutableListOf<Bitmap>()
        for (attachment in attachments) {
            val remaining = limit - out.size
            if (remaining <= 0) break
            val decoded = attachment.decodeCloudRecoveryBitmaps(maxImages = remaining)
            for (bitmap in decoded) {
                if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0 && out.size < limit) {
                    out += bitmap
                } else {
                    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                }
            }
        }
        return out
    }

    private suspend fun runAttachmentCapacityCloud(
        query: String,
        turnAttachments: List<Phase1UserAttachment>,
        fallbackEvidence: String,
        decision: AttachmentEscalationDecision,
        safetyCritical: Boolean
    ): TurnResult? {
        if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) return null
        val parts = mutableListOf<String>()
        val multiComparison = turnAttachments.size > 1 &&
            attachmentContextProvider?.isMultiAttachmentComparisonRequest(query) == true
        val perSourceBudget = if (turnAttachments.size > 1) {
            (18_000 / turnAttachments.size).coerceIn(3_000, 9_000)
        } else 16_000
        for (attachment in turnAttachments) {
            val part = attachmentContextProvider?.cloudEvidenceForTurn(
                attachment = attachment,
                query = query,
                maxChars = perSourceBudget,
                broadSourceCoverage = multiComparison
            ).orEmpty()
            if (part.isNotBlank()) parts += part
        }
        val baseEvidence = parts.joinToString("\n\n").ifBlank { fallbackEvidence }.take(18_000)
        val coverageNote = buildString {
            append("[Attachment escalation: ").append(decision.category).append("]\n")
            append("Reason: ").append(decision.reason).append(".\n")
            if (!decision.coverageComplete) {
                append(
                    "Coverage is incomplete because on-device extraction/visual analysis was bounded. " +
                        "Use only the supplied extracted text and images. If the user's request requires exhaustive coverage, explicitly state that the available cloud evidence is representative rather than the full original file.\n"
                )
            }
        }
        val evidence = (coverageNote + baseEvidence).take(19_000)

        val originals = turnAttachments.flatMap { it.visionBitmaps }
            .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
            .distinctBy { System.identityHashCode(it) }
            .take(4)
        val remainingRecoverySlots = (4 - originals.size).coerceAtLeast(0)
        val temporary = if (remainingRecoverySlots == 0) {
            emptyList()
        } else {
            decodeRecoveryBitmapsWithinSlots(turnAttachments, remainingRecoverySlots)
        }
        val images = (originals + temporary)
            .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
            .distinctBy { System.identityHashCode(it) }
            .take(4)
        Log.i(
            TAG,
            "ATTACHMENT_ESCALATION_CLOUD_PAYLOAD rev=V11_0_CAPABILITY_CLOUD " +
                "category=${decision.category} evidenceChars=${evidence.length} images=${images.size} " +
                "recovery=${temporary.size} coverageComplete=${decision.coverageComplete}"
        )
        return try {
            if (images.isNotEmpty()) {
                runCloudVision(
                    query = query,
                    evidence = evidence,
                    bitmaps = images,
                    detail = GroqChatProvider.VisionDetail.STANDARD,
                    safetyCritical = safetyCritical
                )
            } else {
                runCloud(query, evidence, null)
            }
        } finally {
            temporary.forEach { bmp -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
        }
    }

    private suspend fun runCloudVision(
        query: String,
        evidence: String,
        bitmap: Bitmap,
        detail: GroqChatProvider.VisionDetail = GroqChatProvider.VisionDetail.STANDARD,
        safetyCritical: Boolean = false
    ): TurnResult? = runCloudVision(query, evidence, listOf(bitmap), detail, safetyCritical)

    private suspend fun runCloudVision(
        query: String,
        evidence: String,
        bitmaps: List<Bitmap>,
        detail: GroqChatProvider.VisionDetail = GroqChatProvider.VisionDetail.STANDARD,
        safetyCritical: Boolean = false
    ): TurnResult? =
        visionCapabilityMutex.withLock {
            if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) return@withLock null
            val history = if (currentTurnTroubleshootingForRouting) {
                chatStore.historyForCloud(currentQuery = query).takeLast(4)
            } else {
                chatStore.historyForCloud(currentQuery = query)
            }
            val started = System.currentTimeMillis()
            try {
                val current = if (evidence.isBlank()) query else
                    "<internal_context>\n$evidence\n</internal_context>\n\n<user_request>\n$query\n</user_request>"
                val text = VisionWorkCoordinator.withVisionWork("cloud_vision_turn") {
                    groqProvider.chatVision(
                        history, current, bitmaps, detail, safetyCritical
                    )
                }
                if (text.isBlank()) null else TurnResult(
                    text, Provider.CLOUD, System.currentTimeMillis() - started, false,
                    visualObservation = text.take(700)
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) onApiKeyFailed?.invoke()
                runtimeHealth.cloudFailure(t.javaClass.simpleName, groqProvider.status())
                null
            }
        }

    /** Cloud First and the turn leans toward needing current information: go to the web search. */
    /**
     * Current information was needed but neither the web search nor the online AI could answer.
     * Moved out of chat() (JVM method-size limit); behaviour unchanged.
     */
    private suspend fun freshnessUnavailableTurn(
        query: String,
        started: Long,
        turnSourceIds: List<String>,
        freshnessNeed: String,
        semanticCapabilityFailure: Boolean,
        safetyEscalationRequired: Boolean,
        sourceGroundedTurn: Boolean,
        acceptedHistoryForDecision: List<AndroChatMessage>
    ): TurnResult {
        // FRESH_REQUIRED is a hard epistemic boundary. If current external
        // retrieval cannot run, never substitute a confident-sounding pre-2025
        // Local answer. Tell the user exactly why Internet/cloud is required.
        // Offline, a current-information question still gets the phone's best answer, clearly
        // marked as possibly out of date (a fixed "I can't verify" message helped nobody). Safety
        // and source-grounded turns stay fail-closed below.
        val offlineBestEffort = freshnessNeed == CAPABILITY_FRESH_REQUIRED && !semanticCapabilityFailure &&
            !safetyEscalationRequired && !sourceGroundedTurn && localModelManager?.isReady == true
        if (offlineBestEffort) {
            val lm = localModelManager!!
            val answer = try {
                lm.inferFreshnessLimitedAnswer(userQueryOnly = query, history = acceptedHistoryForDecision, freshnessNeed = freshnessNeed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "FRESHNESS_REQUIRED_LOCAL_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
            }
            val clean = answer?.text?.let { FixSightOutputSanitizer.sanitize(it).trim() }.orEmpty()
            if (clean.isNotBlank()) {
                val note = if (KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI)
                    "\n\n(यह जानकारी पुरानी हो सकती है — ताज़ा जानकारी के लिए Check online दबाएँ।)"
                else "\n\n(This may be out of date — tap Check online for the latest information.)"
                val text = if (clean.contains("Check online", ignoreCase = true)) clean else clean + note
                chatStore.append("user", query)
                chatStore.append("assistant", text)
                localModelManager?.closeSession()
                val elapsed = System.currentTimeMillis() - started
                runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                Log.i(TAG, "FRESHNESS_REQUIRED_LOCAL_BEST_EFFORT chars=${text.length}")
                return TurnResult(
                    text = text,
                    provider = Provider.LOCAL,
                    elapsedMs = elapsed,
                    streamedLocally = false,
                    sourceIds = turnSourceIds + listOf("verification:optional", "freshness_local:$freshnessNeed")
                )
            }
        }
        if (freshnessNeed == CAPABILITY_FRESH_REQUIRED || semanticCapabilityFailure) {
            val message = currentInformationUnavailableMessage()
            // Preserve the visible/durable failure state, but never feed a
            // provider=NONE live-data failure into accepted-turn observers or
            // Notebook facts. It is an availability status, not factual content.
            chatStore.append("user", query)
            chatStore.append("assistant", message)
            localModelManager?.closeSession()
            Log.w(
                TAG,
                "FRESHNESS_REQUIRED_UNAVAILABLE network=${networkDiagnosticHint()} " +
                    "semanticFailure=$semanticCapabilityFailure accepted=false"
            )
            return TurnResult(
                text = message,
                provider = Provider.NONE,
                elapsedMs = System.currentTimeMillis() - started,
                streamedLocally = false,
                sourceIds = turnSourceIds + listOf("freshness_required:offline_or_cloud_unavailable")
            )
        }

        // FRESH_PREFERRED remains useful offline. A detached Local answer may
        // provide pre-2025 background, but its prompt must explicitly qualify
        // that newer facts cannot be verified.
        // Dynamic offline/provider-unavailable behavior: freshness does not make
        // the Local model useless. For ordinary ungrounded turns, answer from
        // the model's known 2024 horizon with an explicit natural qualification.
        // Safety and source-grounded turns remain fail-closed because stale or
        // ungrounded substitution would be misleading.
        val freshnessLocalEligible = !semanticCapabilityFailure &&
            !safetyEscalationRequired && !sourceGroundedTurn
        if (freshnessLocalEligible) {
            val lm = localModelManager
            val staleLocal = if (lm != null && lm.isReady && !lm.permanentlyDisabled) {
                try {
                    lm.inferFreshnessLimitedAnswer(
                        userQueryOnly = query,
                        history = acceptedHistoryForDecision,
                        freshnessNeed = freshnessNeed
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "FRESHNESS_LOCAL_FALLBACK_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
            } else null

            if (staleLocal != null && staleLocal.text.isNotBlank()) {
                val clean = FixSightOutputSanitizer.sanitize(staleLocal.text).trim()
                if (clean.isNotBlank()) {
                    chatStore.append("user", query)
                    chatStore.append("assistant", clean)
                    notifyAcceptedTurn(query, clean, Provider.LOCAL)
                    // The fallback was detached, so retire resident KV and let the
                    // next normal Local turn reseed from authoritative history.
                    localModelManager?.closeSession()
                    val elapsed = System.currentTimeMillis() - started
                    runtimeHealth.turnAccepted(Provider.LOCAL, elapsed, groqProvider.status())
                    Log.i(TAG, "FRESHNESS_LOCAL_ACCEPTED need=$freshnessNeed chars=${clean.length}")
                    return TurnResult(
                        text = clean,
                        provider = Provider.LOCAL,
                        elapsedMs = elapsed,
                        streamedLocally = false,
                        sourceIds = turnSourceIds + listOf(
                            "freshness_local:$freshnessNeed",
                            "knowledge_horizon:2024"
                        )
                    )
                }
            }
        }

        localModelManager?.closeSession()
        return TurnResult(
            text = when {
                semanticCapabilityFailure -> "I couldn't safely determine whether this request needs a device capability or current external information. Please try again; I won't guess with the wrong route."
                safetyEscalationRequired -> "I need current verified information for that safety-sensitive question, but I can't access it right now. Please avoid hazardous action until it can be verified."
                else -> "I can't verify the current information right now, and the local fallback was unavailable."
            },
            provider = Provider.NONE,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = turnSourceIds
        )
    }

    private fun cloudFirstLeansFresh(capabilities: TurnCapabilities): Boolean =
        capabilities.externalFreshLean && answerPolicyStore.get() == AnswerPolicy.CLOUD_FIRST && canAttemptCloud()

    /**
     * Cloud First for an ordinary turn: the online AI answers (fact lookups via web search first).
     * Null when Cloud is offline or fails; the caller then answers locally.
     */
    private suspend fun runCloudPreferredTurn(
        query: String,
        evidence: String,
        answerPolicyDecision: AnswerPolicyDecision,
        started: Long,
        turnSourceIds: List<String>
    ): TurnResult? {
        if (!canAttemptCloud()) {
            Log.i(TAG, "CLOUD_PREFERRED_SKIPPED offline_or_unavailable=true fallback=local reason=${answerPolicyDecision.reason}")
            return null
        }
        Log.i(TAG, "MODE_ROUTE mode=AUDIO_ONLY primary=CLOUD_PREFERRED reason=${answerPolicyDecision.reason}")
        val webLookup = answerPolicyDecision.reason == "objective_fact_lookup"
        val preferredCloud = try {
            withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) {
                if (webLookup) {
                    // Exact/objective facts are checked with a web search first; the online AI's
                    // own answer is the fallback.
                    runExternalCapabilities(query = query, evidence = "", safetyCritical = false, groundedKnowledge = false)
                        ?: withTimeoutOrNull(CLOUD_TURN_TIMEOUT_MS) { runCloud(query, evidence, null) }
                } else {
                    // The online AI answers what it knows; when it is not sure (new or changed things,
                    // e.g. "what is space x ai") it asks for a web search instead of guessing.
                    withTimeoutOrNull(CLOUD_TURN_TIMEOUT_MS) { runCloud(query, evidence, null) }
                        ?: runExternalCapabilities(query = query, evidence = "", safetyCritical = false, groundedKnowledge = false)
                }
            }
        } catch (te: TimeoutCancellationException) {
            groqProvider.cancelActiveCall()
            Log.w(TAG, "CLOUD_PREFERRED_TIMEOUT fallback=local")
            null
        } catch (ce: CancellationException) {
            groqProvider.cancelActiveCall(); throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "CLOUD_PREFERRED_FAILED ${t.javaClass.simpleName}: ${t.message} fallback=local")
            null
        }
        if (preferredCloud == null || preferredCloud.text.isBlank()) {
            runtimeHealth.cloudFailure("cloud_preferred_unavailable", groqProvider.status())
            return null
        }
        chatStore.append("user", query)
        chatStore.append("assistant", preferredCloud.text)
        notifyAcceptedTurn(query, preferredCloud.text, Provider.CLOUD)
        localModelManager?.closeSession()
        val elapsed = System.currentTimeMillis() - started
        runtimeHealth.turnAccepted(Provider.CLOUD, elapsed, groqProvider.status())
        return preferredCloud.copy(
            elapsedMs = elapsed,
            sourceIds = (turnSourceIds + listOf(
                "answer_policy:cloud_first",
                "cloud_preferred:${answerPolicyDecision.reason}"
            )).distinct()
        )
    }

    /**
     * Selected KB + a question about current/live information the KB does not cover (news, weather,
     * "latest …", a year after 2024): answer with a live web search, clearly labelled as not from the
     * KB. The KB stays selected for the next question. Null when the web search is not possible.
     */
    /**
     * The earlier turns the phone's model reads with a plain-chat question. All of them (up to ~3,000
     * tokens) were sent with every question: after five or six turns each answer took longer to start
     * and the 3B model mixed the earlier topics into new questions. A question that stands on its own
     * gets none; one that depends on the conversation ("and for a child?", "explain more") gets the
     * last [PLAIN_CHAT_HISTORY_MESSAGES] messages.
     */
    private suspend fun plainChatHistory(query: String, history: List<AndroChatMessage>): List<AndroChatMessage> {
        if (history.isEmpty()) return history
        val dependent = runCatching { semanticCapabilityProvider?.isContextDependentForRecall(query, history) == true }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(true)
        Log.i(TAG, "PLAIN_CHAT_HISTORY dependent=$dependent turns=${history.size}")
        return if (dependent) history.takeLast(PLAIN_CHAT_HISTORY_MESSAGES) else emptyList()
    }

    /** General answer for a question the knowledge base does not cover (labelled; null if unsure). */
    private suspend fun kbGeneralAnswer(query: String, started: Long, topics: List<String>, otherPackNote: String? = null, knowledgeName: String = "", sessionKey: String = ""): TurnResult? {
        val lm = localModelManager ?: return null
        if (!lm.isReady || lm.permanentlyDisabled) return null
        val history = plainChatHistory(query, pocketRagSessions.context(sessionKey).flatMapIndexed { i, t ->
            listOf(AndroChatMessage(i * 2L, "user", t.user, 0L), AndroChatMessage(i * 2L + 1, "assistant", t.assistant, 0L))
        })
        val result = try {
            withTimeoutOrNull(GENERAL_ANSWER_TIMEOUT_MS) { lm.inferGeneralAnswer(query, history, knowledgeName) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "KB_GENERAL_ANSWER_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
        } ?: return null
        val answer = result.text.trim()
        if (answer.isBlank()) return null
        Log.i(TAG, "KB_GENERAL_ANSWER chars=${answer.length}")
        val lead = otherPackNote ?: if (KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI)
            "यह चुने हुए ज्ञान आधार में नहीं है।" else "This is not covered by the selected knowledge base."
        val general = if (KbReplyText.dominantScript(query) == KbReplyText.Script.DEVANAGARI)
            "सामान्य उत्तर (इसके दस्तावेज़ों से नहीं):" else "General answer (not from its documents):"
        val text = "$lead $general\n\n$answer"
        saveKbTurn(query, text)
        return TurnResult(
            text = text,
            provider = Provider.LOCAL,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = listOf(
                KnowledgeSourcePresentation.topicsMarker(KnowledgeSourcePresentation.NOT_FOUND_PREFIX, topics),
                "verification:optional"
            )
        )
    }

    private suspend fun kbLiveWebAnswer(query: String, started: Long, reason: String): TurnResult? {
        if (!groqProvider.canAttemptRequest() || !networkCanReachInternet()) return null
        Log.i(TAG, "KB_LIVE_WEB_ESCAPE reason=$reason")
        val searched = try {
            withTimeout(EXTERNAL_CLOUD_TIMEOUT_MS) { runExternalCapabilities(query, "") }
        } catch (te: TimeoutCancellationException) {
            groqProvider.cancelActiveCall(); null
        } catch (ce: CancellationException) {
            groqProvider.cancelActiveCall(); throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "KB_LIVE_WEB_ESCAPE_FAILED ${t.javaClass.simpleName}: ${t.message}"); null
        }
        val web = searched?.takeIf { it.text.isNotBlank() }
        if (web == null || web.text.isBlank()) return null
        val text = "Not from the selected knowledge base — answered online:\n\n" + web.text.trim()
        saveKbTurn(query, text)
        runtimeHealth.turnAccepted(Provider.CLOUD, System.currentTimeMillis() - started, groqProvider.status())
        return TurnResult(
            text = text,
            provider = Provider.CLOUD,
            elapsedMs = System.currentTimeMillis() - started,
            streamedLocally = false,
            sourceIds = listOf("freshness:kb_live_web")
        )
    }

    private fun extractUserRequest(content: String): String {
        val start = content.lastIndexOf("<user_request>")
        val end = content.lastIndexOf("</user_request>")
        return (if (start >= 0 && end > start) content.substring(start + 14, end) else content).trim()
    }

    private suspend fun runCloud(query: String, evidence: String, bitmap: Bitmap?): TurnResult? {
        if (bitmap != null) return runCloudVision(
            query, evidence, bitmap, GroqChatProvider.VisionDetail.STANDARD
        )
        if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) return null
        val history = chatStore.historyForCloud(currentQuery = query)
        val started = System.currentTimeMillis()
        return try {
            val current = if (evidence.isBlank()) query else
                "<internal_context>\n$evidence\n</internal_context>\n\n<user_request>\n$query\n</user_request>"
            val text = groqProvider.chatText(history, current)
            // "Needs a web search" is not an answer; runCloudPreferredTurn then searches the web.
            if (text.contains(GroqChatProvider.NEEDS_WEB)) Log.i(TAG, "CLOUD_NEEDS_WEB query=${query.take(80)}")
            if (text.isBlank() || text.contains(GroqChatProvider.NEEDS_WEB)) null
            else TurnResult(text, Provider.CLOUD, System.currentTimeMillis() - started, false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (groqProvider.status() == GroqProviderStatus.AUTH_FAILED) onApiKeyFailed?.invoke()
            null
        }
    }

    /** User policy changes provider preference, never capability ownership. */
    private suspend fun resolveAnswerPolicyDecision(
        query: String,
        capabilities: TurnCapabilities,
        sourceGroundedTurn: Boolean,
        hasTurnAttachments: Boolean
    ): AnswerPolicyDecision {
        val policy = answerPolicyStore.get()
        if (sourceGroundedTurn || hasTurnAttachments ||
            capabilities.externalRequired || capabilities.safetyRequired || capabilities.visualRequired
        ) {
            return AnswerPolicyDecision(policy, false, false, "specialized_capability_owner")
        }

        val intentResult = try { semanticCapabilityProvider?.classifyQueryIntent(query) }
        catch (ce: CancellationException) { throw ce }
        catch (t: Throwable) {
            Log.w(TAG, "ANSWER_POLICY_INTENT_FAILED ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        val intent = intentResult?.intent
        val words = query.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val deterministicExactFact = AnswerPolicyHeuristics.isExactStableFactRequest(query)
        val objectiveLookup = intent == SemanticQueryIntent.VALUE_LOOKUP || deterministicExactFact
        val materiallyComplex = when (intent) {
            SemanticQueryIntent.COMPARISON, SemanticQueryIntent.ENUMERATION -> words >= 18
            SemanticQueryIntent.GENERAL -> words >= 28
            else -> false
        }

        // Cloud First means what the setting says: every ordinary turn goes to Cloud first (Local
        // answers only when Cloud is offline or fails). Before, only fact lookups and long requests did.
        if (policy == AnswerPolicy.CLOUD_FIRST) {
            return AnswerPolicyDecision(
                policy = policy,
                cloudPreferred = true,
                offerOnlineVerification = false,
                reason = when {
                    objectiveLookup -> "objective_fact_lookup"
                    materiallyComplex -> "complex_general_request"
                    else -> "cloud_first_policy"
                }
            )
        }

        // Offline first: ordinary questions are answered by the phone's model. Online is used when
        // the question needs fresh information (capabilities.externalRequired: news, weather, today's
        // prices… handled before this point), when the user taps Check online, or under Cloud First.
        // Stable objective facts remain available offline in Local First and when
        // Cloud First falls back; the UI may offer explicit online verification.
        return AnswerPolicyDecision(
            policy = policy,
            cloudPreferred = false,
            // "Check online" is offered under every local answer.
            offerOnlineVerification = true,
            reason = when {
                objectiveLookup -> "local_fact_with_optional_verification"
                intent != null -> "local_fast_path_${intent.name.lowercase()}"
                else -> "local_default"
            }
        )
    }

    private suspend fun resolveTurnCapabilitiesForMode(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        includeGlobalSemanticRouting: Boolean,
        troubleshootingFastMode: Boolean
    ): TurnCapabilities = if (troubleshootingFastMode) {
        resolveTroubleshootingFastCapabilities(query, acceptedHistory)
    } else {
        resolveTurnCapabilities(query, acceptedHistory, includeGlobalSemanticRouting)
    }

    private suspend fun resolveAnswerPolicyForMode(
        query: String,
        capabilities: TurnCapabilities,
        sourceGroundedTurn: Boolean,
        hasTurnAttachments: Boolean,
        troubleshootingVisual: Boolean,
        kbSelected: Boolean
    ): AnswerPolicyDecision {
        if (troubleshootingVisual && !kbSelected) {
            return AnswerPolicyDecision(
                policy = AnswerPolicy.LOCAL_FIRST,
                cloudPreferred = false,
                offerOnlineVerification = false,
                reason = "troubleshooting_visual_owner_fast_path"
            )
        }
        return resolveAnswerPolicyDecision(
            query = query,
            capabilities = capabilities,
            sourceGroundedTurn = sourceGroundedTurn,
            hasTurnAttachments = hasTurnAttachments
        )
    }

    private suspend fun resolveTroubleshootingFastCapabilities(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): TurnCapabilities {
        var decisionFailed = false
        var safetyRequired = false
        var arOverlayRequested = false
        var deterministicExternalRequired = false

        for (decider in capabilityDeciders) {
            try {
                if (decider !is SemanticCapabilityContextProvider &&
                    decider.requiresExternalCapability(query, acceptedHistory)
                ) {
                    deterministicExternalRequired = true
                }
                if (decider.requiresSafetyEscalation(query, acceptedHistory)) {
                    safetyRequired = true
                }
                if (decider.requiresArOverlayCapability(query, acceptedHistory)) {
                    arOverlayRequested = true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                decisionFailed = true
                Log.w(TAG, "TROUBLESHOOTING_FAST_CAPABILITY_FAILED ${t.message}")
            }
        }

        Log.i(
            TAG,
            "TROUBLESHOOTING_FAST_CAPABILITY safety=$safetyRequired arOverlay=$arOverlayRequested " +
                "deterministicExternal=$deterministicExternalRequired semanticOperational=${!decisionFailed}"
        )
        return TurnCapabilities(
            externalRequired = deterministicExternalRequired,
            deterministicExternalRequired = deterministicExternalRequired,
            safetyRequired = safetyRequired,
            visualRequired = true,
            cameraEvidenceRelevant = true,
            arOverlayRequested = arOverlayRequested,
            semanticOperational = !decisionFailed,
            fallbackUsed = decisionFailed
        )
    }

    private suspend fun resolveTurnCapabilities(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        includeGlobalSemanticRouting: Boolean
    ): TurnCapabilities {
        // Audio-only KB-OFF has no global semantic/topic pre-router. The global E5
        // capability provider is retained only for Audio+Video and is skipped here unless
        // includeGlobalSemanticRouting=true. Selected KB grounding remains available only
        // when the user explicitly enables KB.
        var decisionFailed = false
        suspend fun anyDecision(
            label: String,
            block: suspend (BaselineCapabilityDecider) -> Boolean
        ): Boolean {
            for (decider in capabilityDeciders) {
                if (!includeGlobalSemanticRouting && decider is SemanticCapabilityContextProvider) continue
                val decided = try {
                    block(decider)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "CAPABILITY_DECIDER_FAILED type=$label ${t.message}")
                    decisionFailed = true
                    false
                }
                if (decided) return true
            }
            return false
        }

        val grounding = anyDecision("grounding") {
            it.requiresVerifiedGrounding(query, acceptedHistory)
        }

        var externalAmbiguous = false
        var externalFreshLean = false
        var externalScore = 0f
        var localScore = 0f
        var presentStateScore = 0f
        var stableStateScore = 0f
        var deterministicExternalRequired = false
        val external = if (includeGlobalSemanticRouting) {
            var required = false
            for (decider in capabilityDeciders) {
                val decided = try {
                    if (decider is SemanticCapabilityContextProvider) {
                        val assessment = decider.assessExternalCapability(query, acceptedHistory)
                        externalAmbiguous = externalAmbiguous || assessment.ambiguousExternalLean
                        externalFreshLean = externalFreshLean || assessment.semanticFreshLean
                        externalScore = maxOf(externalScore, assessment.currentExternalScore, assessment.contextualExternalScore)
                        localScore = maxOf(localScore, assessment.currentLocalScore, assessment.contextualLocalScore)
                        presentStateScore = maxOf(presentStateScore, assessment.presentStateScore, assessment.contextualPresentStateScore)
                        stableStateScore = maxOf(stableStateScore, assessment.stableStateScore, assessment.contextualStableStateScore)
                        assessment.required
                    } else {
                        val direct = decider.requiresExternalCapability(query, acceptedHistory)
                        if (direct) deterministicExternalRequired = true
                        direct
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "CAPABILITY_DECIDER_FAILED type=external ${t.message}")
                    decisionFailed = true
                    false
                }
                if (decided) required = true
            }
            required
        } else false

        val safety = if (includeGlobalSemanticRouting) anyDecision("safety") {
            it.requiresSafetyEscalation(query, acceptedHistory)
        } else false
        val visual = if (includeGlobalSemanticRouting) anyDecision("visual") {
            it.requiresVisualCapability(query, acceptedHistory)
        } else false
        val cameraEvidenceRelevant = if (includeGlobalSemanticRouting) anyDecision("camera_evidence") {
            it.isCurrentCameraEvidenceRelevant(query, acceptedHistory)
        } else false
        val spatialLocalization = if (includeGlobalSemanticRouting) anyDecision("spatial_localization") {
            it.requiresSpatialLocalizationCapability(query, acceptedHistory)
        } else false
        val arOverlayRequested = if (includeGlobalSemanticRouting) anyDecision("ar_overlay") {
            it.requiresArOverlayCapability(query, acceptedHistory)
        } else false
        // Stable factual questions are ordinary Local turns. Freshness, safety and
        // source-grounding already provide the escalation boundaries that need them.
        val factual = false

        return TurnCapabilities(
            externalRequired = external,
            deterministicExternalRequired = deterministicExternalRequired,
            externalAmbiguous = externalAmbiguous,
            externalFreshLean = externalFreshLean,
            externalScore = externalScore,
            localScore = localScore,
            presentStateScore = presentStateScore,
            stableStateScore = stableStateScore,
            verifiedGroundingRequired = grounding,
            safetyRequired = safety,
            visualRequired = visual,
            cameraEvidenceRelevant = cameraEvidenceRelevant,
            spatialLocalizationRequired = spatialLocalization,
            arOverlayRequested = arOverlayRequested,
            factualVerificationRequired = factual,
            semanticOperational = includeGlobalSemanticRouting && !decisionFailed,
            fallbackUsed = false
        )
    }


    /**
     * Collect optional context attachments without changing the frozen Audio-only/KB-off
     * provider policy. This is the exact helper contract from the previous compiling
     * baseline; selected-KB and session-recall providers remain explicitly gated.
     */
    private suspend fun collectExtensionAttachments(
        query: String,
        allowSessionRecall: Boolean = true,
        selectedKbSupplementalEvidence: String = "",
        currentTurnAttachmentIds: Set<String> = emptySet(),
        attachmentContextDependent: Boolean = false,
        historyOverride: List<AndroChatMessage>? = null
    ): List<BaselineContextAttachment> {
        if (contextProviders.isEmpty()) return emptyList()
        val history = historyOverride ?: chatStore.recent(120)
        val collected = mutableListOf<BaselineContextAttachment>()
        for (provider in contextProviders) {
            try {
                if (!allowSessionRecall && provider is SessionConversationContextProvider) continue
                if (provider is SelectedKnowledgeContextProvider && !provider.isSelected()) continue
                if (provider is GenericKnowledgeContextProvider) {
                    provider.setActiveContextWindowTokens(kbContextWindowTokens)
                }
                if (provider is SessionConversationContextProvider) {
                    provider.updateHotHistoryTokenBudget(historyTokenBudget)
                }

                if (provider is AttachmentSessionContextProvider) {
                    if (!provider.shouldAttachForTurn(
                            query = query,
                            acceptedHistory = history,
                            excludedAttachmentIds = currentTurnAttachmentIds,
                            contextDependentContinuation = attachmentContextDependent
                        )) continue
                    collected += provider.contextAttachmentsForTurnRecall(
                        query = query,
                        acceptedHistory = history,
                        excludedAttachmentIds = currentTurnAttachmentIds,
                        contextDependentContinuation = attachmentContextDependent
                    ).filter { it.text.isNotBlank() }
                    continue
                }

                if (!provider.shouldAttach(query, history)) continue
                val attachment = if (provider is SelectedKnowledgeContextProvider && selectedKbSupplementalEvidence.isNotBlank()) {
                    provider.contextForAttachmentEvidence(
                        query = query,
                        attachmentEvidence = selectedKbSupplementalEvidence,
                        acceptedHistory = history
                    )
                } else {
                    provider.contextFor(query, history)
                }
                if (attachment != null && attachment.text.isNotBlank()) collected += attachment
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "CONTEXT_PROVIDER_FAILED ${t.message}")
            }
        }
        return collected.sortedByDescending { it.priority }
    }

    /** Render private extension context for model input only. */
    private fun renderExtensionContext(attachments: List<BaselineContextAttachment>): String {
        if (attachments.isEmpty()) return ""
        return attachments.mapNotNull { attachment ->
            val clean = FixSightOutputSanitizer.sanitize(attachment.text)
            if (clean.isBlank()) null else buildString {
                append("<source id=\"")
                append(attachment.sourceId.replace("\"", ""))
                append("\">\n")
                append(clean)
                append("\n</source>")
            }
        }.joinToString("\n\n").take(5_000)
    }

    /** Notify passive Notebook/RCA/memory observers only after a turn is accepted. */
    private suspend fun notifyAcceptedTurn(
        user: String,
        assistant: String,
        provider: Provider,
        investigation: Boolean = false,
        visualObservation: String = "",
        sourceIds: List<String> = emptyList()
    ) {
        if (provider != Provider.NONE) {
            lastAcceptedProviderForRouting = provider
            lastAcceptedUserForRouting = user.trim().take(1_500)
            lastAcceptedAssistantForRouting = assistant.trim().take(2_500)
            lastAcceptedExternalIntentForRouting = currentTurnExternalIntentForRouting
            // Provider switching never owns conversation memory. Keep only a tiny
            // escalation marker for diagnostics/anti-ping-pong evolution; the shared
            // chatStore + Notebook remain authoritative across Local<->Cloud changes.
            if (provider == Provider.CLOUD) {
                // Cloud is an executor for this accepted turn only. Do not create
                // SOURCE_GROUNDED_SYNTHESIS / visual / generic provider ownership.
                // The next turn is planned again from current request + shared state.
                clearCloudEpisode("cloud_turn_one_shot")
            } else if (provider == Provider.LOCAL) {
                clearCloudEpisode("local_turn_accepted")
            }
            // Interrupted context is a one-follow-up bridge only. Once the next turn is
            // accepted, the incomplete exchange must stop contaminating future prompts.
            chatStore.clearInterruptedTurn()
        }
        if (provider != Provider.NONE) {
            acceptedTurnObservers.forEach { observer ->
                try {
                    observer.onAcceptedTurn(user, assistant, provider)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "ACCEPTED_TURN_OBSERVER_FAILED ${t.message}")
                }
            }
        } else {
            Log.i(TAG, "ACCEPTED_TURN_OBSERVER_SKIPPED provider=NONE")
        }
        if (investigation && provider != Provider.NONE) {
            try {
                notebookObserver?.recordStructuredAcceptedTurn(
                    userText = user,
                    assistantText = assistant,
                    provider = provider,
                    visualObservation = visualObservation,
                    sourceIds = sourceIds
                )
            } catch (t: Throwable) {
                Log.w(TAG, "NOTEBOOK_STRUCTURED_COMMIT_FAILED ${t.javaClass.simpleName}:${t.message}")
            }
        }
    }


    /**
     * Fast offline boundary for cloud calls.
     *
     * We deliberately do NOT require NET_CAPABILITY_VALIDATED because VPN/private-DNS
     * transitions can report it false even while HTTPS works.  We only fast-fail when
     * Android can positively tell us that there is no active network or that the active
     * network lacks INTERNET capability.  If Android cannot inspect network state (for
     * example permission/device variance), return true and let the bounded HTTP request
     * remain the source of truth.
     */
    private fun currentInformationUnavailableMessage(): String {
        return if (!networkCanReachInternet()) {
            "This question depends on current information. My offline knowledge is reliable only through the end of 2024, so I need an internet connection to verify a fresh answer."
        } else {
            "This needs current information from the web, but the web search could not be completed just now. Tap Check online to try again; I won't present an old answer as current."
        }
    }

    private fun networkCanReachInternet(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (t: Throwable) {
            Log.w(TAG, "CLOUD_NETWORK_PROBE_UNKNOWN ${t.javaClass.simpleName}:${t.message}")
            true
        }
    }

    private fun networkDiagnosticHint(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            if (network == null) {
                "none"
            } else {
                val caps = cm.getNetworkCapabilities(network)
                if (caps == null) {
                    "active/no-caps"
                } else {
                    val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    "active internet=$internet validated=$validated vpn=$vpn wifi=$wifi cellular=$cellular"
                }
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun canAttemptCloud(): Boolean =
        groqProvider.canAttemptRequest() && networkCanReachInternet()

    /**
     * Non-blocking cancellation request. UI/voice code should normally cancel
     * AndroTurnCoordinator's Job instead; this exists for watchdog/recovery paths.
     */
    fun requestCancelActiveGeneration(reason: String = "runtime_cancel") {
        try { localModelManager?.requestCancelActiveGeneration(reason) } catch (_: Throwable) {}
        try { groqProvider.cancelActiveCall() } catch (_: Throwable) {}
    }

    @Deprecated("Cancel the AndroTurnCoordinator job instead of native LiteRT directly")
    fun cancelActiveGeneration() = requestCancelActiveGeneration("legacy_runtime_cancel")

    fun continueTroubleshootingAfterResolutionPrompt() {
        troubleshootingSessionState.continueAfterResolutionPrompt()
    }

    fun finalizeTroubleshootingResolution(): TroubleshootingSessionState.ResolutionSnapshot? {
        val finalized = troubleshootingSessionState.finalizeResolution() ?: return null
        notebookObserver?.recordResolution(
            userAcknowledgement = finalized.userAcknowledgement,
            resolutionAction = finalized.resolutionAction
        )
        return finalized
    }

    suspend fun endChat() = withContext(Dispatchers.IO) {
        turnMutex.withLock {
            localModelManager?.closeSession()
        }
    }

    fun closeNow() {
        // Activity/service teardown must return immediately. The owning turn job
        // is cancelled first by AndroTurnCoordinator; the engine then drains and
        // closes Conversation/Engine on its own worker scope.
        try { localModelManager?.shutdownAsync("runtime_close") } catch (_: Throwable) {}
        try { groqProvider.cancelActiveCall() } catch (_: Throwable) {}
    }
}
