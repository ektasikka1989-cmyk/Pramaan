package com.example.arfas

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped dependency graph for the FixSight runtime.
 *
 * This mirrors AndroLLM's application-scoped repositories/engine ownership:
 * Activity recreation never destroys the local engine, conversation store,
 * semantic router, attachment manager, or Notebook observer. The foreground
 * voice service owns active voice/turn execution while this graph owns the
 * long-lived runtime dependencies it consumes.
 *
 * Phase-1 stability rule:
 * - Ordinary Audio-only / KB-OFF conversation has no semantic/topic pre-router.
 * - Notebook is a passive accepted-turn observer.
 * - Cross-session durable-memory retrieval/extraction is intentionally NOT
 *   inserted into the live Audio-only path. Normal voice turns therefore stay
 *   on the resident AndroLLM chat/KV path.
 * - ANM/KB is opt-in per session through a selected evidence provider.
 *   It does not become a second global capability router.
 */
class FixSightRuntimeGraph private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    private val modelLoadMutex = Mutex()
    private val modelLoadInProgress = AtomicBoolean(false)

    val isLocalModelLoadInProgress: Boolean
        get() = modelLoadInProgress.get()

    val groqKeyStore = GroqKeyStore(appContext)
    val phase1Features = Phase1FeatureBundle(appContext)
    val attachmentManager = Phase1AttachmentManager(appContext)
    val attachmentRecallProvider = AttachmentSessionContextProvider(appContext, phase1Features.semanticEngine)
    val sessionAuditTrail = SessionAuditTrail(appContext)

    val runtime = AndroBaselineRuntime(
        context = appContext,
        apiKey = groqKeyStore.getKey().trim(),
        contextProviders = listOf(
            // Retained for Audio+Video only. AndroBaselineRuntime explicitly skips it
            // for Audio-only KB-OFF, so it cannot pre-route ordinary voice questions.
            phase1Features.capabilityProvider,
            // Selected KB provider is inert while KB is OFF and becomes the
            // grounding/validation owner only when the user explicitly enables KB.
            phase1Features.selectedKnowledgeProvider,
            // Generic installable knowledge-pack provider. It is inert unless the
            // active session selected a generic pack and E5 finds strong evidence.
            phase1Features.genericKnowledgeProvider,
            // Conversation-scoped attachment workspace. Accepted source files/text
            // are restored from app-private per-conversation storage; E5 embeddings
            // remain lazy/in-memory and it never becomes a global topic router.
            attachmentRecallProvider
        ),
        acceptedTurnObservers = listOf(
            // Notebook persists the accepted exchange first.
            phase1Features.notebookObserver,
            // Selected-KB provider then commits staged visual evidence / planner goal
            // changes for the same accepted turn. It is a no-op while KB is off.
            phase1Features.selectedKnowledgeProvider
        )
    )

    init {
        // Generic selected-KB planner is detached and local-only. It is invoked only
        // Generic selected-KB retrieval is intentionally self-contained in
        // GenericKnowledgeContextProvider (V14 simple local RAG). No detached
        // Local-LLM retrieval planner is wired into the hot path.

        // V4 KB-ON surgical path: do not spend a separate Local-LLM generation on
        // query planning before retrieval. SelectedKnowledgeContextProvider already
        // builds a deterministic case projection from accepted history + Notebook +
        // the current turn.  The optional planner seam remains available in the code,
        // but is deliberately not configured in the default runtime. KB-OFF is
        // unaffected because this seam is KB-selected-only.

        // Generic KB: the answer model rewrites a weakly matched or numeric question into one
        // search question in the documents' terms plus an arithmetic expression (the app does the
        // arithmetic). Local, greedy, ~70 output tokens; the literal question is always searched too.
        phase1Features.genericKnowledgeProvider.configureQuestionRewriter { question, aliasHints ->
            runtime.localModelManager?.rewriteKbSearchQuestion(question, aliasHints)
        }
        runtime.knowledgeTurnPause = { paused -> phase1Features.knowledgePausedForTurn = paused }

        // Record-level clinical admission. Local remains the complete offline path.
        // When cloud is available it may adjudicate a technical failure or a
        // non-HIGH local decision, but it can select only from the already-retrieved
        // source records and never becomes an unrestricted medical source.
        phase1Features.configureKnowledgeRecordSelector { notebookContext, currentUser, candidates, activeRecordIds ->
            runtime.selectKnowledgeRecords(
                notebookContext = notebookContext,
                currentUser = currentUser,
                candidates = candidates,
                activeRecordIds = activeRecordIds
            )
        }
    }

    @Volatile private var featuresInitialised = false

    /** Start app-scoped initialization without blocking the caller/UI. */
    fun initialiseAsync(onModelProgress: ((String) -> Unit)? = null) {
        initScope.launch {
            initialise(onModelProgress)
        }
    }

    /**
     * Initializes shared semantic infrastructure for optional KB use and attempts to load the selected local model.
     * Safe to call repeatedly from Activity and Service; both paths serialize here.
     */
    suspend fun initialise(onModelProgress: ((String) -> Unit)? = null): Boolean {
        initMutex.withLock {
            if (!featuresInitialised) {
                val status = phase1Features.initialise()
                featuresInitialised = true
                Log.i(TAG, "RUNTIME_GRAPH_FEATURES semanticLazy=true semantic=${status.semanticReady} kbReady=${status.knowledgeReady} kbSelected=${phase1Features.isKnowledgeEnabled()}")
            }
        }
        return ensureLocalModelReady(onModelProgress)
    }

    /**
     * AndroLLM-style application-scoped model readiness boundary.
     * There is exactly one load attempt at a time regardless of Activity/Service races.
     */
    suspend fun ensureLocalModelReady(onProgress: ((String) -> Unit)? = null): Boolean =
        modelLoadMutex.withLock {
            val lm = runtime.localModelManager ?: return@withLock false
            // Meta Stack: Llama 3.2 GGUF (llama.cpp) is preferred whenever it is installed; a
            // .litertlm bundle is used only when forced or when no Llama model is present.
            val preferred: File? = runtime.modelFileManager.preferredTextModelFile()
            if (lm.isReady && lm.isTextWarm && !lm.permanentlyDisabled &&
                (preferred == null || lm.loadedModelFileName == preferred.name)
            ) return@withLock true

            modelLoadInProgress.set(true)
            try {
                if (lm.permanentlyDisabled) {
                    if (!lm.engineNeedsReload || !lm.prepareControlledReloadAfterQuarantine()) {
                        return@withLock false
                    }
                }

                if (preferred == null) {
                    val installed = runtime.modelFileManager.listInstalledModels()
                        .count { it.endsWith(".litertlm", ignoreCase = true) }
                    onProgress?.invoke(
                        if (installed > 1) "Multiple local models installed — choose one in Settings"
                        else "Not loaded — install Llama 3.2 (Meta models zip) in Settings"
                    )
                    return@withLock false
                }

                val file: File = preferred
                if (!file.exists() || file.length() <= 0L) {
                    onProgress?.invoke("Not loaded — model file missing")
                    Log.w(TAG, "RUNTIME_GRAPH_MODEL_MISSING path=${file.absolutePath}")
                    return@withLock false
                }
                if (lm.isReady && lm.loadedModelFileName != file.name) {
                    Log.i(TAG, "RUNTIME_GRAPH_MODEL_SWITCH from=${lm.loadedModelFileName} to=${file.name}")
                    lm.unload()
                }

                Log.i(TAG, "RUNTIME_GRAPH_MODEL_LOAD_BEGIN file=${file.name} mb=${file.length() / 1024 / 1024}")
                val ok = lm.loadModel(file.absolutePath) { progress ->
                    onProgress?.invoke(progress)
                }
                Log.i(TAG, "RUNTIME_GRAPH_MODEL_LOAD_END ok=$ok warm=${lm.isTextWarm}")
                if (ok && lm.isTextWarm) return@withLock true
                // Stand-by: Meta Llama could not be loaded (file damaged, out of memory): the phone's
                // Gemma model, when installed, answers instead of leaving the user with no offline AI.
                val standby = runtime.modelFileManager.gemmaModelFile()?.takeIf { it.name != file.name }
                    ?: return@withLock false
                Log.w(TAG, "RUNTIME_GRAPH_STANDBY_GEMMA from=${file.name} to=${standby.name}")
                onProgress?.invoke("Meta Llama did not load — starting Gemma instead")
                runCatching { lm.unload() }
                if (lm.permanentlyDisabled && (!lm.engineNeedsReload || !lm.prepareControlledReloadAfterQuarantine())) return@withLock false
                val standbyOk = lm.loadModel(standby.absolutePath) { progress -> onProgress?.invoke(progress) }
                Log.i(TAG, "RUNTIME_GRAPH_STANDBY_LOAD_END ok=$standbyOk warm=${lm.isTextWarm}")
                standbyOk && lm.isTextWarm
            } finally {
                modelLoadInProgress.set(false)
            }
        }

    fun updateGroqKey(key: String) {
        val clean = key.trim()
        if (clean.isBlank()) groqKeyStore.clearKey() else groqKeyStore.saveKey(clean)
        runtime.updateGroqApiKey(groqKeyStore.getKey())
    }

    /** Online AI engine switch (Settings): Groq · Qwen or Meta · Muse Spark, each with its own key. */
    fun setOnlineEngine(engine: GroqKeyStore.Engine) {
        groqKeyStore.setEngine(engine)
        runtime.updateGroqApiKey(groqKeyStore.getKey())
    }

    companion object {
        private const val TAG = "FixSightRuntimeGraph"

        @Volatile private var instance: FixSightRuntimeGraph? = null

        fun get(context: Context): FixSightRuntimeGraph =
            instance ?: synchronized(this) {
                instance ?: FixSightRuntimeGraph(context).also { instance = it }
            }
    }
}
