package com.example.arfas

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * FixSight capability bundle sitting ABOVE the AndroLLM runtime.
 *
 * Knowledge Base contract:
 * - KB is opt-in per active session.
 * - Audio-only KB-OFF has no global semantic/topic router.
 * - The ANM provider contributes evidence + verified grounding only while selected.
 * - Semantic retrieval and KB are initialised lazily and never block voice-session startup.
 */
class Phase1FeatureBundle(private val context: Context) {
    companion object { private const val TAG = "FixSightFeatures" }

    private val initMutex = Mutex()
    private val knowledgeMutex = Mutex()
    val semanticEngine = SemanticSearchEngine(context.applicationContext)
    private val knowledgeDb = ANMDatabaseHelper(context.applicationContext).also {
        it.semanticEngine = semanticEngine
    }
    private val genericKnowledgeDb = GenericKnowledgePackRepository(
        context.applicationContext,
        semanticEngine
    )

    private val conversationMemoryStore =
        ConversationMemoryStore(context.applicationContext, semanticEngine)

    val notebookObserver = PassiveNotebookObserver(context.applicationContext)
    val memoryObserver: AcceptedTurnObserver =
        ConversationMemoryObserver(conversationMemoryStore)

    // Retained only for Audio+Video capability composition. Audio-only KB-OFF
    // explicitly skips this decider, so ordinary voice chat never runs its E5 router.
    val capabilityProvider = SemanticCapabilityContextProvider(semanticEngine)

    private enum class ActiveKnowledgePack { ANM, GENERIC }

    private val knowledgePrefs = context.getSharedPreferences("fixsight_kb_selection", Context.MODE_PRIVATE)
    @Volatile private var activeKnowledgePack: ActiveKnowledgePack = runCatching {
        ActiveKnowledgePack.valueOf(knowledgePrefs.getString("active_pack", ActiveKnowledgePack.ANM.name) ?: ActiveKnowledgePack.ANM.name)
    }.getOrDefault(ActiveKnowledgePack.ANM)

    private val adaptiveKnowledgeCore = AdaptiveKnowledgeContextProvider(
        ensureReady = { ensureKnowledgeReady() },
        db = knowledgeDb,
        semanticEngine = semanticEngine
    )

    init {
        adaptiveKnowledgeCore.configureResponseScopeClassifier { query, history ->
            capabilityProvider.classifyResponseScope(query, history)?.scope ?: SemanticResponseScope.NORMAL
        }
    }

    // Session-selected evidence/grounding seam only. It intentionally does not
    // own external, safety, visual or factual routing.
    val selectedKnowledgeProvider = SelectedKnowledgeContextProvider(
        enabled = { knowledgeEnabled && !knowledgePausedForTurn && activeKnowledgePack == ActiveKnowledgePack.ANM },
        delegate = adaptiveKnowledgeCore,
        notebookSnapshot = { notebookObserver.snapshotWithoutTroubleshooting() },
        recordVerifiedVisualObservation = { observation, source ->
            notebookObserver.recordVerifiedVisualObservation(observation, source)
        },
        updateCurrentGoal = { goal -> notebookObserver.updateCurrentGoal(goal) },
        recordRejectedTheory = { theory -> notebookObserver.recordRejectedTheory(theory) }
    )

    val genericKnowledgeProvider = GenericKnowledgeContextProvider(
        enabled = { knowledgeEnabled && !knowledgePausedForTurn && activeKnowledgePack == ActiveKnowledgePack.GENERIC },
        ensureReady = { ensureKnowledgeReady() },
        repository = genericKnowledgeDb,
        isContextDependent = { query, history -> capabilityProvider.isContextDependentForKnowledgeBase(query, history) }
    )

    val memoryProvider: BaselineContextProvider =
        ConversationMemoryContextProvider(conversationMemoryStore)
    val sessionRecallProvider = SessionConversationContextProvider(semanticEngine)

    @Volatile private var semanticReady = false
    @Volatile private var knowledgeReady = false
    @Volatile private var knowledgeEnabled = false
    /**
     * Set by the runtime for one turn when the user attached a document while a KB is selected:
     * that turn is about the document, so the KB steps aside (the KB stays selected).
     */
    @Volatile var knowledgePausedForTurn = false


    fun setConversationId(id: String) {
        val clean = id.trim().ifBlank { "default" }
        notebookObserver.setConversationId(clean)
        conversationMemoryStore.setConversationId(clean)
        Log.i(TAG, "FIXSIGHT_CONVERSATION_SCOPE id=${clean.take(12)}")
    }

    fun pruneConversationScopes(validIds: Set<String>) {
        notebookObserver.pruneConversationIds(validIds)
        conversationMemoryStore.pruneConversationIds(validIds)
    }

    fun configureMemoryExtractor(extractor: suspend (String, String) -> List<String>) {
        conversationMemoryStore.extractor = extractor
    }

    fun configureKnowledgeQueryPlanner(
        planner: suspend (String, String, String) -> KbQueryPlan?
    ) {
        selectedKnowledgeProvider.configureQueryPlanner(planner)
    }


    fun configureKnowledgeRecordSelector(
        selector: suspend (String, String, List<ANMDatabaseHelper.KbRecordCandidate>, List<Int>) -> KbRecordSelection?
    ) {
        selectedKnowledgeProvider.configureRecordSelector(selector)
    }

    suspend fun initialise(): Phase1Status = withContext(Dispatchers.IO) {
        initMutex.withLock {
            // Audio-only KB-OFF must not load E5 merely to classify ordinary chat.
            // The shared SemanticSearchEngine is initialized lazily only when selected
            // KB or attachment semantic retrieval actually needs it.
            semanticReady = semanticEngine.isReady
            knowledgeReady = when (activeKnowledgePack) {
                ActiveKnowledgePack.ANM -> knowledgeDb.isReady()
                ActiveKnowledgePack.GENERIC -> genericKnowledgeDb.isReady()
            }
            Log.i(
                TAG,
                "FIXSIGHT_FEATURES_READY semanticLazy=true semantic=$semanticReady " +
                    "knowledge=$knowledgeReady kbLazy=true selected=$knowledgeEnabled"
            )
            Phase1Status(semanticReady, knowledgeReady)
        }
    }

    data class KnowledgeInstallationStatus(
        val fileExists: Boolean = false,
        val valid: Boolean = false,
        val rowCount: Int = 0,
        val embeddingCount: Int = 0,
        val packName: String = "",
        val packType: String = ""
    )

    /** Installed generic knowledge packs (active first), for quick switching in Settings. */
    suspend fun installedKnowledgePacks(): List<GenericKnowledgePackRepository.InstalledPack> =
        runCatching { genericKnowledgeDb.installedPacks() }.getOrDefault(emptyList())

    /**
     * Makes an installed pack the active Knowledge Base. Not allowed while a KB session is running
     * (the KB is locked for a session). Its prepared search files are reused, so this takes seconds.
     */
    suspend fun switchKnowledgePack(packId: String): Boolean = withContext(Dispatchers.IO) {
        knowledgeMutex.withLock {
            if (knowledgeEnabled) {
                Log.w(TAG, "FIXSIGHT_KB_SWITCH_REJECT selectedInSession=true")
                return@withLock false
            }
            if (!ensureSemanticReadyLocked()) return@withLock false
            val ok = genericKnowledgeDb.activatePack(packId)
            if (ok) {
                activeKnowledgePack = ActiveKnowledgePack.GENERIC
                knowledgePrefs.edit().putString("active_pack", activeKnowledgePack.name).apply()
                selectedKnowledgeProvider.resetSessionState()
            }
            knowledgeReady = ok && genericKnowledgeDb.isReady()
            Log.i(TAG, "FIXSIGHT_KB_SWITCH pack=$packId ok=$ok ready=$knowledgeReady rows=${genericKnowledgeDb.currentStatus().rowCount}")
            knowledgeReady
        }
    }

    suspend fun removeKnowledgePack(packId: String): Boolean = genericKnowledgeDb.removeLibraryPack(packId)

    suspend fun installKnowledgeDatabase(
        uri: Uri,
        displayName: String = "",
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        knowledgeMutex.withLock {
            if (knowledgeEnabled) {
                Log.w(TAG, "FIXSIGHT_KB_INSTALL_REJECT selectedInSession=true")
                return@withLock false
            }
            if (!ensureSemanticReadyLocked()) {
                Log.w(TAG, "FIXSIGHT_KB_INSTALL_REJECT semanticReady=false")
                return@withLock false
            }

            // Generic packs are attempted first by schema, never by filename/domain.
            // If the schema is not a generic FixSight pack, fall back to the legacy
            // ANM installer unchanged.
            when (genericKnowledgeDb.installFromUri(uri, displayName)) {
                GenericKnowledgePackRepository.InstallResult.INSTALLED -> {
                    activeKnowledgePack = ActiveKnowledgePack.GENERIC
                    knowledgePrefs.edit().putString("active_pack", activeKnowledgePack.name).apply()
                    knowledgeReady = genericKnowledgeDb.isReady()
                    selectedKnowledgeProvider.resetSessionState()
                    Log.i(TAG, "FIXSIGHT_KB_INSTALL generic=true ready=$knowledgeReady rows=${genericKnowledgeDb.currentStatus().rowCount}")
                    return@withLock knowledgeReady
                }
                GenericKnowledgePackRepository.InstallResult.FAILED -> {
                    Log.w(TAG, "FIXSIGHT_KB_INSTALL genericSchema=true valid=false")
                    return@withLock false
                }
                GenericKnowledgePackRepository.InstallResult.UNSUPPORTED_SCHEMA -> Unit
            }

            knowledgeDb.semanticEngine = semanticEngine
            val ok = knowledgeDb.installFromUri(uri, onProgress)
            knowledgeReady = ok && knowledgeDb.isReady()
            if (knowledgeReady) {
                activeKnowledgePack = ActiveKnowledgePack.ANM
                knowledgePrefs.edit().putString("active_pack", activeKnowledgePack.name).apply()
            }
            Log.i(TAG, "FIXSIGHT_KB_INSTALL legacyAnm=true ok=$ok ready=$knowledgeReady records=${knowledgeDb.getRecordCount()}")
            knowledgeReady
        }
    }

    private suspend fun ensureSemanticReadyLocked(): Boolean {
        if (semanticReady && semanticEngine.isReady) return true
        semanticReady = semanticEngine.initialise()
        return semanticReady
    }

    /** Outcome of [installKnowledgeDatabaseDetailed]: success plus the real reason either way. */
    data class KbInstallOutcome(val ok: Boolean, val reason: String)

    /**
     * ArogyaDoot install path (Guide v1 → v2). The plain installer rejects while a KB is
     * selected for the session, and ArogyaDoot selects it at startup — so selection is
     * dropped around the install; on success the new pack (already active) is re-enabled
     * and search is re-warmed, on failure the previous selection is restored. Returns the
     * real failure reason so the Knowledge Base screen can show it.
     */
    suspend fun installKnowledgeDatabaseDetailed(
        uri: Uri,
        displayName: String = "",
        onProgress: (String) -> Unit = {}
    ): KbInstallOutcome {
        val wasEnabled = isKnowledgeEnabled()
        if (wasEnabled) setKnowledgeEnabled(false)
        val ok = installKnowledgeDatabase(uri, displayName, onProgress)
        if (!ok) {
            if (wasEnabled && isKnowledgeReady()) setKnowledgeEnabled(true)
            val reason = installFailureReason()
            Log.w(TAG, "FIXSIGHT_KB_INSTALL_DETAILED ok=false reason=$reason")
            return KbInstallOutcome(false, reason)
        }
        if (wasEnabled) {
            val ready = ensureKnowledgeReady()
            setKnowledgeEnabled(ready)
            if (ready) runCatching { warmKnowledgeSearch() }
            Log.i(TAG, "FIXSIGHT_KB_INSTALL_DETAILED ok=true reselected=$ready")
        } else {
            Log.i(TAG, "FIXSIGHT_KB_INSTALL_DETAILED ok=true reselected=false")
        }
        return KbInstallOutcome(true, "installed")
    }

    /** Official guides library rows (read-only query; retrieval untouched). Call off the main thread. */
    fun guideLibrary(): List<GuideDoc> = genericKnowledgeDb.listGuideSources()

    /** Topic headings inside one guide, for "Ask about this guide". Call off the main thread. */
    fun guideTopics(sourceName: String): List<String> = genericKnowledgeDb.guideTopics(sourceName)

    /** Best-effort real reason for a failed install (the Boolean installer hides it). */
    private fun installFailureReason(): String = when {
        !semanticEngine.isReady -> "search models not ready — open Setup → AI on this phone first, then retry"
        else -> "the chosen file was not recognised as a guide database (needs the knowledge_chunks / procurement_chunks table with embeddings)"
    }

    /**
     * Warms the KB search sessions (E5 first embedding + re-ranker verification) so the
     * first question does not pay for them. Best-effort; never fails warm-up.
     */
    suspend fun warmKnowledgeSearch(): Boolean = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        val e5 = runCatching { semanticEngine.embed("query: warmup") != null }.getOrDefault(false)
        runCatching { genericKnowledgeDb.warmSearchSessions() }
        Log.i(TAG, "KB_SEARCH_WARM ms=${System.currentTimeMillis() - t} e5=$e5")
        e5
    }

    /** R9 marker of the installed guide pack (2 = v2, 0 = v1, -1 = none). */
    fun guideVersion(): Int = runCatching { genericKnowledgeDb.guideVersion() }.getOrDefault(-1)

    suspend fun ensureKnowledgeReady(): Boolean = withContext(Dispatchers.IO) {
        knowledgeMutex.withLock {
            if (!ensureSemanticReadyLocked()) {
                knowledgeReady = false
                Log.w(TAG, "FIXSIGHT_KB_READY semantic=false")
                return@withLock false
            }

            knowledgeReady = when (activeKnowledgePack) {
                ActiveKnowledgePack.GENERIC -> {
                    if (genericKnowledgeDb.isReady()) true else genericKnowledgeDb.initialise()
                }
                ActiveKnowledgePack.ANM -> {
                    if (knowledgeDb.isReady()) true
                    else {
                        knowledgeDb.semanticEngine = semanticEngine
                        val before = knowledgeDb.inspectInstalledDatabase()
                        if (before.fileExists && !before.valid) {
                            knowledgeDb.close()
                            false
                        } else {
                            val ready = knowledgeDb.initialise()
                            if (ready && !knowledgeDb.inspectInstalledDatabase().valid) {
                                knowledgeDb.close(); false
                            } else ready
                        }
                    }
                }
            }
            Log.i(TAG, "FIXSIGHT_KB_READY ready=$knowledgeReady pack=${activeKnowledgePack.name}")
            knowledgeReady
        }
    }

    /** Called only after ensureKnowledgeReady() succeeds when enabling. */
    fun setKnowledgeEnabled(enabled: Boolean): Boolean {
        val accepted = if (enabled) knowledgeReady && when (activeKnowledgePack) {
            ActiveKnowledgePack.ANM -> knowledgeDb.isReady()
            ActiveKnowledgePack.GENERIC -> genericKnowledgeDb.isReady()
        } else true
        val wasEnabled = knowledgeEnabled
        knowledgeEnabled = enabled && accepted

        // If KB is activated after passive/generic conversation, start a clean V1 KB
        // investigation Notebook so old generic turns cannot contaminate the case.
        if (!wasEnabled && knowledgeEnabled) {
            notebookObserver.clear()
            selectedKnowledgeProvider.resetSessionState()
            Log.i(TAG, "FIXSIGHT_KB_NOTEBOOK_CASE_START clearedPassiveHistory=true")
        }
        if (wasEnabled && !knowledgeEnabled) {
            selectedKnowledgeProvider.resetSessionState()
        }

        Log.i(TAG, "FIXSIGHT_KB_SELECTION requested=$enabled accepted=$accepted selected=$knowledgeEnabled")
        return knowledgeEnabled == enabled
    }

    fun isKnowledgeEnabled(): Boolean = knowledgeEnabled
    fun isGenericKnowledgeSelected(): Boolean = knowledgeEnabled && activeKnowledgePack == ActiveKnowledgePack.GENERIC
    fun isKnowledgeReady(): Boolean = knowledgeReady && when (activeKnowledgePack) {
        ActiveKnowledgePack.ANM -> knowledgeDb.isReady()
        ActiveKnowledgePack.GENERIC -> genericKnowledgeDb.isReady()
    }

    suspend fun inspectKnowledgeInstallation(): KnowledgeInstallationStatus = when (activeKnowledgePack) {
        ActiveKnowledgePack.GENERIC -> {
            val s = genericKnowledgeDb.inspectInstalled()
            KnowledgeInstallationStatus(
                fileExists = s.fileExists,
                valid = s.valid,
                rowCount = s.rowCount,
                embeddingCount = s.embeddingCount,
                packName = s.displayName,
                packType = "GENERIC"
            )
        }
        ActiveKnowledgePack.ANM -> {
            val s = knowledgeDb.inspectInstalledDatabase()
            KnowledgeInstallationStatus(
                fileExists = s.fileExists,
                valid = s.valid,
                rowCount = s.rowCount,
                embeddingCount = s.embeddingCount,
                packName = "ANM SOP",
                packType = "ANM"
            )
        }
    }

    fun knowledgeStatusText(): String = when {
        isKnowledgeReady() && activeKnowledgePack == ActiveKnowledgePack.GENERIC -> {
            val s = genericKnowledgeDb.currentStatus()
            "Ready — ${s.displayName.ifBlank { "Custom Knowledge Pack" }} • ${s.rowCount} chunks"
        }
        isKnowledgeReady() -> {
            val stats = knowledgeDb.getDbStats()
            "Ready — ${stats.totalSops} ANM SOP records"
        }
        else -> "Not loaded — install an ArogyaDoot knowledge database"
    }

    /**
     * Authoritative process-scoped Notebook stream. UI must observe this directly;
     * transient turn events are presentation-only and must not own Notebook state.
     */
    val notebookState: StateFlow<InvestigationNotebook>
        get() = notebookObserver.state

    fun notebookSnapshot(): InvestigationNotebook = notebookObserver.snapshot()

    /** Verbatim accepted rows used as the authoritative RCA transcript. */
    fun notebookRows(): List<PassiveNotebookObserver.Row> = notebookObserver.readRows()

    fun close() {
        knowledgeEnabled = false
        try { knowledgeDb.close() } catch (_: Throwable) {}
        try { genericKnowledgeDb.close() } catch (_: Throwable) {}
        try { conversationMemoryStore.closeStore() } catch (_: Throwable) {}
        try { semanticEngine.close() } catch (_: Throwable) {}
    }
}

data class Phase1Status(val semanticReady: Boolean, val knowledgeReady: Boolean)
