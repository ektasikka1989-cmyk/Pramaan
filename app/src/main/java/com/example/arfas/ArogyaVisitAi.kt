package com.example.arfas

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * Pramaan's visit layer is a WRAPPER over the base platform. Guide questions go through the
 * platform's own turn (AndroBaselineRuntime.chat with the installed knowledge base selected): the
 * same AndroLLM, PocketSage RAG, semantic search, re-ranking and grounding the platform chat uses,
 * unchanged. The visit layer adds only:
 *  - deterministic newborn / postnatal danger-sign alert BEFORE any model (ArogyaDangerSigns);
 *  - visit context in the question (age group) so retrieval finds the right population;
 *  - visit-note organisation using Pramaan's generateDetached + ArogyaCapture (labels only).
 */
enum class ArogyaAiTask { EXPLAIN, ORGANISE_NOTES, HANDOVER }
data class ArogyaAiRequest(val task:ArogyaAiTask,val visit:ArogyaVisit,val question:String="",val hindi:Boolean=false,val topic:String="",val previousExchange:String="",val onlineCheck:Boolean=false,
    /** Worker already saw the urgent alert and asked for the guide's guidance: alert is prepended, not repeated alone. */
    val afterSafetyPrompt:Boolean=false,
    /** Live progress: stage and the answer text as it is being written (a draft until checked). */
    val onProgress:((ArogyaProgress)->Unit)? = null)
data class ArogyaExtraction(val fields:List<ExtractedField>,val modelLabel:String,val metaModel:Boolean,val ms:Long,val note:String)
data class ArogyaAiResult(val text:String,val provider:String,val elapsedMs:Long,val sources:List<ArogyaSource> = emptyList(),val canCopyToNotes:Boolean=false,val online:Boolean=false,val checkedAt:String="",val sourceOnly:Boolean=false,val captureItems:List<ArogyaCaptureItem> = emptyList(),val timingsMs:Map<String,Long> = emptyMap(),val safetyPrompt:Boolean=false,
    /** "The guide says": Pramaan's own chosen source sentence for this answer, with its source label. */
    val quote:String="",val quoteSource:String="",
    /** R8: the web/general answer was replaced by the ANM message; closest guide topics shown with it. */
    val r8Blocked:Boolean=false,val r8Topics:List<String> = emptyList(),
    /** Item 5: 1 = guide answer, 2 = official website, 3 = not found. Per-sentence citations. */
    val tier:Int = 1,val segments:List<ArogyaOfficialAnswer.CitedSegment> = emptyList(),
    /** Item 5a: the ☁ Check online answer kept beside the offline one (Offline | Online tabs). */
    val onlineText:String="",val onlineSources:List<ArogyaSource> = emptyList(),
    val onlineSegments:List<ArogyaOfficialAnswer.CitedSegment> = emptyList(),val onlineMs:Long=0L,val onlineTier:Int=0,
    val onlineProvider:String="",
    /** Part C3: labeled stored copy shown only when the model failed/timed out (strong match). */
    val storedCopy:Boolean=false)

class ArogyaVisitAi(private val runtime:AndroBaselineRuntime,private val features:Phase1FeatureBundle,private val onlineKey:()->String = {""},
    /** Pramaan's own attachment manager: photo/PDF text is read on the phone (ML Kit OCR, PDF text). */
    private val attachments: Phase1AttachmentManager? = null,
    /** Same model-load boundary Pramaan's chat service waits on before every turn. */
    private val ensureLocalModel: suspend () -> Boolean = { true },
    /** App context for the Cloud-first → phone retry (policy flip, restored after). Null = no retry. */
    private val appContext: Context? = null) {
    private val mutex=Mutex()
    @Volatile private var lastVisitId: String? = null
    /** Item 6: true while an AI answer is being written (guide installs wait for it). */
    val isBusy: Boolean get() = mutex.isLocked

    /** Set by the screen: which cloud model/key to use and the Settings order (Cloud first / Local first). */
    @Volatile var cloudChoice: (() -> ArogyaCloudChoice)? = null

    /**
     * Every Pramaan AI action goes through here. Cloud first: cloud model, then the phone if there is no signal,
     * no key, a slow network or an unusable reply. Local first: the phone, then the cloud if the phone model is not
     * ready or its reply is unusable. [accept] checks each reply (grounding etc.); returns the result and who made it.
     */
    private suspend fun <T> route(feature: String, prompt: String, maxTokens: Int, cloudPrompt: String = prompt,
                                  system: String = "Follow the instructions exactly.", cloud: Boolean = true,
                                  cloudTokens: Int = maxTokens, lang: String = "en",
                                  onDelta: ((String) -> Unit)? = null,
                                  accept: (String) -> T?): Pair<T?, String> {
        val c = runCatching { withContext(Dispatchers.IO) { cloudChoice?.invoke() } }.getOrNull()
        val ep = c?.endpoint; val key = c?.key.orEmpty()
        var emitted = false
        // Part B8: word-by-word deltas per engine; "" resets the shown draft when the turn
        // falls back to the other engine, so two half-answers never concatenate on screen.
        val emit: (String) -> Unit = { d -> if (d.isNotEmpty()) emitted = true; onDelta?.invoke(d) }
        suspend fun cloud(): Pair<T?, String>? {
            if (!cloud || ep == null || key.isBlank()) return null
            val t = System.currentTimeMillis()
            val budget = ArogyaBudget.tokens(cloudTokens, lang, ArogyaBudget.estimatePromptTokens(cloudPrompt.length), ArogyaBudget.CLOUD_CONTEXT, feature, "cloud")
            val client = OpenAiCompatibleClient(ep, { key }, CLOUD_DEADLINE_MS.toInt())
            val raw = if (onDelta == null) {
                ArogyaCloud.withDeadline(CLOUD_DEADLINE_MS, feature) { client.complete(system, cloudPrompt, budget) }
            } else {
                val streamed = ArogyaCloud.withDeadline(CLOUD_DEADLINE_MS, feature) { runBlocking { client.completeStream(system, cloudPrompt, budget, emit) } }
                if (!streamed.isNullOrBlank()) streamed
                else if (coroutineContext.isActive) ArogyaCloud.withDeadline(CLOUD_DEADLINE_MS, feature) { client.complete(system, cloudPrompt, budget) }
                else null
            }
            Log.i(TAG, "AROGYA_ROUTE feature=$feature engine=cloud model=${ep.model} ok=${raw != null} streamed=${onDelta != null} ms=${System.currentTimeMillis() - t}")
            return raw?.let { r -> accept(r)?.let { it to ep.label } }
        }
        suspend fun phone(): Pair<T?, String>? = try {
            val model = readyModel(); val t = System.currentTimeMillis()
            val budget = ArogyaBudget.tokens(maxTokens, lang, ArogyaBudget.estimatePromptTokens(prompt.length), ArogyaBudget.PHONE_CONTEXT, feature, "phone")
            val raw = generate(prompt, model, budget, onPartial = if (onDelta == null) null else emit).orEmpty()
            Log.i(TAG, "AROGYA_ROUTE feature=$feature engine=phone ms=${System.currentTimeMillis() - t} chars=${raw.length}")
            accept(raw)?.let { it to "On-phone model (offline): " + (model.loadedModelFileName?.substringBeforeLast('.') ?: "local") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "AROGYA_ROUTE feature=$feature engine=phone failed ${e.message?.take(120)}"); null }
        for (engine in ArogyaRouting.order(c?.cloudFirst == true, cloud && ep != null && key.isNotBlank())) {
            (if (engine == "cloud") cloud() else phone())?.let { return it }
            if (emitted) { emitted = false; onDelta?.invoke("") }
        }
        return null to ""
    }
    fun cancel() { runtime.requestCancelActiveGeneration("arogya_user_cancel"); runtime.localModelManager?.requestCancelActiveGeneration("arogya_user_cancel") }
    /** Gap item 2: drop the in-flight cloud call when the network dies; local work continues. */
    fun cancelCloud() { runtime.cancelCloudCall() }

    /**
     * Ask SOP "New conversation": drops this visit's runtime session context (pocketRagSessions
     * + saved turns) so the next question starts fresh. lastVisitId is kept: the cleared
     * session belongs to this visit, and the next answer re-seeds it.
     */
    suspend fun newConversation() {
        runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }
        Log.i(TAG, "AROGYA_SOP_NEW_CONVERSATION visit=$lastVisitId")
    }

    /** One line for the AI page: which model file is loaded and whether the guide is selected. */
    fun modelStatus(): String {
        val m = runtime.localModelManager
        val name = m?.loadedModelFileName?.substringBeforeLast('.') ?: "not loaded yet"
        val state = when { m == null -> "no local model"; m.permanentlyDisabled -> "disabled"; m.isReady -> "ready"; else -> "loading" }
        val kb = runCatching { features.isKnowledgeEnabled() }.getOrDefault(false)
        return "Language model: $name ($state) · Meta Llama, runs offline\nASHA guide: " + (if (kb) "selected, searchable offline" else "not selected yet")
    }

    /** On-phone Meta vision stack (MobileSAM + DINOv2) for offline drug-kit counting. */
    fun vision(): MetaVisionEngine = runtime.metaVision

    /** True when the on-phone counter can run (DINOv2 + both SAM files installed). */
    fun visionOk(): Boolean = runtime.metaVision.has(MetaModelStore.Component.DINOV2) &&
        runtime.metaVision.has(MetaModelStore.Component.SAM_ENCODER) &&
        runtime.metaVision.has(MetaModelStore.Component.SAM_DECODER)

    /** Structured twin of modelStatus() for the AI table: short loaded file name ("" when none) + ready flag. */
    fun modelInfo(): Pair<String, Boolean> {
        val m = runtime.localModelManager
        return (m?.loadedModelFileName?.substringBeforeLast('.').orEmpty()) to
            (m != null && m.isReady && !m.permanentlyDisabled)
    }

    /** Loads the model and selects the ASHA guide as soon as Pramaan opens, so the first question does not wait ~30 s. */
    /** R9 marker of the installed guide pack (2 = v2, 0 = v1, -1 = none). */
    fun guideVersion(): Int = runCatching { features.guideVersion() }.getOrDefault(-1)

    /** Official guides library rows + topic headings (IO thread; empty when no pack installed). */
    suspend fun guideLibrary(): List<GuideDoc> = withContext(Dispatchers.IO) { runCatching { features.guideLibrary() }.getOrDefault(emptyList()) }
    suspend fun guideTopics(sourceName: String): List<String> = withContext(Dispatchers.IO) { runCatching { features.guideTopics(sourceName) }.getOrDefault(emptyList()) }

    /** Evidence size from the profile switch (1 = R1 6 x 1100, 2 = 5 x 900 candidate, 0 = legacy 4 x 900). */
    private fun applyEvidenceSize() {
        when (ArogyaFlags.r1Profile) {
            2 -> { GenericKbKeyFacts.WINDOW_PASSAGES = 5; GenericKbKeyFacts.WINDOW_CHARS = 900 }
            0 -> { GenericKbKeyFacts.WINDOW_PASSAGES = 4; GenericKbKeyFacts.WINDOW_CHARS = 900 }
            else -> { GenericKbKeyFacts.WINDOW_PASSAGES = 6; GenericKbKeyFacts.WINDOW_CHARS = 1_100 }
        }
    }

    suspend fun warmUp(appContext: Context? = null) {
        applyEvidenceSize()
        val t = System.currentTimeMillis()
        val model = runCatching { ensureLocalModel() }.getOrDefault(false)
        val kb = runCatching { features.ensureKnowledgeReady() && (features.isKnowledgeEnabled() || features.setKnowledgeEnabled(true)) }.getOrDefault(false)
        val search = kb && ArogyaFlags.speedWarmSearch && runCatching { features.warmKnowledgeSearch() }.getOrDefault(false)
        Log.i(TAG, "AROGYA_WARMUP model=$model kb=$kb ms=${System.currentTimeMillis() - t}")
        Log.i(TAG, "AROGYA_WARMUP_SEARCH warmed=$search")
        // Part C1: the 100 verified questions embed in the background (same call the KB uses).
        if (appContext != null) runCatching {
            val ctx = appContext.applicationContext
            val asset = ctx.assets.open("verified_answers.json").use { it.readBytes() }
            val engine = features.semanticEngine
            ArogyaVerifiedAnswers.warmAsync(asset, java.io.File(ctx.filesDir, ArogyaVerifiedAnswers.CACHE_NAME)) { q ->
                runBlocking { engine.embed(q) }
            }
        }.onFailure { Log.w(TAG, "AROGYA_GOLD warm kick failed ${it.message}") }
    }

    // ── Attachments for a guide question (Pramaan's pending-attachment path, unchanged) ──
    /** Reads a photo or PDF on the phone and holds it for the next question. Returns its name, or null if unreadable. */
    suspend fun attach(uri: android.net.Uri): String? = attachments?.stageUri(uri)?.displayName
    fun attachedNames(): List<String> = attachments?.peekPendingAll()?.map { it.displayName }.orEmpty()
    fun clearAttachments() { attachments?.clearPending() }

    suspend fun run(request:ArogyaAiRequest):ArogyaAiResult {
        applyEvidenceSize()
        val started = System.nanoTime()
        // 1. Danger-sign rules: instant, no model. URGENT stops here unless the worker asked for guidance after it.
        val danger = ArogyaDangerSigns.evaluate(request.question, request.visit)
        val alert = ArogyaDangerSigns.message(danger)
        if (danger.urgent && !request.afterSafetyPrompt && alert != null) {
            val ms = (System.nanoTime()-started)/1_000_000
            Log.i(TAG, "AROGYA_TIMING event=urgent_warning ms=$ms")
            return ArogyaAiResult(alert, "Immediate safety rules • no model used", ms, safetyPrompt=true)
        }
        val result = mutex.withLock {
            when (request.task) {
                ArogyaAiTask.EXPLAIN -> askPramaan(request)
                else -> organiseNotes(request)
            }
        }
        Log.i(TAG, "AROGYA_TIMING event=ai_result task=${request.task} total_ms=${result.elapsedMs} accepted=${!result.sourceOnly}")
        // The warning was already shown on screen; the answer carries only a one-line reminder, never the full warning again.
        // The short warning stays on screen above the answer; it is never repeated inside the answer.
        return result
    }

    /** The visit's age group in plain words, so Pramaan retrieves guidance for the right population. */
    private fun visitContext(v: ArogyaVisit): String {
        val days = ArogyaWorkflow.parseDate(v.birthDate)?.let { b -> ArogyaWorkflow.parseDate(v.date)?.let { java.time.temporal.ChronoUnit.DAYS.between(b, it) } }
        return when {
            days != null && days in 0..28 -> "newborn, $days days old"
            days != null && days in 29..59 -> "young infant, $days days old"
            days != null && days < 365 * 5 -> "child, ${days / 30} months old"
            v.kind == ArogyaKind.NEWBORN -> "newborn"
            v.kind == ArogyaKind.CHILD -> "young child"
            else -> ""
        }
    }

    private suspend fun askPramaan(request: ArogyaAiRequest): ArogyaAiResult {
        val t0 = System.currentTimeMillis()
        val question = request.question.trim()
        check(question.length <= 700) { "Shorten the question to 700 characters." }
        check(question.isNotEmpty() || attachments?.peekPendingAll().orEmpty().isNotEmpty()) { "Type or speak a question first." }
        // Load / warm the local model first, exactly like Pramaan's chat service does before a turn.
        val modelReady = runCatching { ensureLocalModel() }.getOrDefault(false)
        Log.i(TAG, "AROGYA_LOCAL_MODEL_READY $modelReady")
        // Select the installed knowledge base exactly as Pramaan's own KB mode does.
        check(features.ensureKnowledgeReady()) { "The ASHA guide is not ready. Open Setup → Install ASHA guide. Your visit is saved." }
        if (!features.isKnowledgeEnabled()) check(features.setKnowledgeEnabled(true)) { "Could not select the ASHA guide. Open Setup and select it. Your visit is saved." }
        // One Pramaan conversation per visit: follow-ups stay within this visit, never another household's.
        if (lastVisitId != request.visit.id) { runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }; lastVisitId = request.visit.id }
        runCatching { runtime.ensureConversationOpen() }
        // The question goes to Pramaan as the worker wrote it (an appended age phrase split retrieval
        // into two clauses on the phone: 30 s instead of 15 s).
        val asked = question.ifBlank { "What does this attachment show, and what should the ASHA do?" }
        val online = request.onlineCheck && !ArogyaOnlinePolicy.localOnly(question)
        // Pramaan's own turn: AndroLLM + PocketSage + semantic search + grounding, unchanged.
        val turnFiles = attachments?.claimPendingAttachmentsForTurn().orEmpty()
        if (turnFiles.isNotEmpty()) Log.i(TAG, "AROGYA_ATTACHMENTS count=${turnFiles.size} kinds=${turnFiles.joinToString { it.kind.name }}")
        request.onProgress?.invoke(ArogyaProgress("search"))
        val draft = StringBuilder()
        var answered = false
        var firstWordsMs = -1L
        suspend fun doChat(): AndroBaselineRuntime.TurnResult = withTimeout(ASK_TIMEOUT_MS) {
            runtime.chat(if (online) ArogyaOnlinePolicy.scrub(asked, request.visit) else asked, forceExternalInformation = online, turnAttachments = turnFiles,
                onLocalDelta = { d ->
                    if (firstWordsMs < 0 && d.isNotBlank()) firstWordsMs = System.currentTimeMillis() - t0
                    synchronized(draft) { draft.append(d) }; request.onProgress?.invoke(ArogyaProgress("write", synchronized(draft) { draft.toString() })) },
                // Review fix: when the checker rejects a streamed draft (cloud or local), clear it
                // so the next answer never appends to rejected text; the card shows "checking…".
                onLocalAbort = { synchronized(draft) { draft.setLength(0) }; request.onProgress?.invoke(ArogyaProgress("checking")) })
        }
        // Part C3: when the model fails or times out and a verified answer matches
        // strongly, show its stored copy with the "AI unavailable" label instead of
        // an error. Anything else (cancel, setup checks) still throws as before.
        fun storedFallback(reason: String): ArogyaAiResult? {
            val elapsed = System.currentTimeMillis() - t0
            // Same query C2 matched seconds ago: match() reuses its cached vector.
            val scored = runCatching { ArogyaVerifiedAnswers.match(question) }.getOrNull() ?: return null
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val built = ArogyaGoldPin.storedCopyIfStrong(scored, hindiQ) ?: return null
            Log.i(TAG, "AROGYA_GOLD fallback id=${scored.item.id} score=${"%.3f".format(java.util.Locale.US, scored.score)} reason=$reason")
            val first = scored.item.sources.firstOrNull()
            return ArogyaAiResult(built.first, "Pramaan • stored copy", elapsed, sources = built.second,
                quote = first?.quote.orEmpty(),
                quoteSource = if (first == null) "" else "${first.title}, page ${first.page}",
                timingsMs = mapOf("pramaan_turn" to elapsed), tier = 1, storedCopy = true)
        }
        // UI2 speed pass: an offline (phone-model) turn reads at most 4,000 chars of evidence.
        // Cloud turns (online, or the Cloud-first setting) keep the full budget.
        GenericKnowledgeContextProvider.phoneEvidenceCap = !online && runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST
        val turn = try {
            try {
                doChat()
            } catch (e: Exception) {
                // A real cancel (back button, Stop) must never trigger a retry — but a cancel
                // caused by the network dying mid-call falls back to the phone instead.
                if (e is CancellationException && e !is TimeoutCancellationException && !ArogyaNetWatch.justLost()) throw e
                if (runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST && !ArogyaNetWatch.justLost()) {
                    storedFallback("model_failed")?.let { return it }
                    throw e
                }
                Log.w(TAG, "AROGYA_ASK_FALLBACK cloud failed (${e.message?.take(100)}), retrying on the phone")
                // In-memory override only: the saved setting is never touched, so a
                // crash mid-retry cannot leave it changed.
                runtime.policyOverride = AnswerPolicy.LOCAL_FIRST
                GenericKnowledgeContextProvider.phoneEvidenceCap = true
                try { doChat() } finally { runtime.policyOverride = null }
            }.also { answered = true }
        } catch (e: TimeoutCancellationException) {
            storedFallback("timeout")?.let { return it }
            Log.w(TAG, "AROGYA_ASK_TIMEOUT after ${ASK_TIMEOUT_MS / 1000}s")
            error("The answer is taking too long. Try again, or ask a shorter question — your visit is saved.")
        } catch (e: Exception) {
            // Retry-path failure (not a cancel, not a setup check — those are outside this try).
            if (e is CancellationException) throw e
            storedFallback("model_failed")?.let { return it }
            throw e
        } finally {
            GenericKnowledgeContextProvider.phoneEvidenceCap = false
            // Used attachments are released; on failure they stay attached for a retry.
            attachments?.releaseTurnAttachments(turnFiles.map { it.id }, consumed = answered)
        }
        return buildResult(turn, question, System.currentTimeMillis() - t0, firstWordsMs, detail = false, onlineCheck = request.onlineCheck)
    }

    /**
     * Item 4 "More detail": the same question regenerates from the SAME passages (the
     * runtime's detail cache; no new search) with the longer detail length line, up to the
     * model ceiling. The caller replaces the answer in place; sources come from the same
     * evidence, so the source line stays the same.
     */
    suspend fun moreDetail(question: String, visit: ArogyaVisit, onProgress: ((ArogyaProgress) -> Unit)? = null): ArogyaAiResult = mutex.withLock {
        val t0 = System.currentTimeMillis()
        val asked = question.trim()
        check(asked.isNotEmpty()) { "Ask a question first." }
        runCatching { ensureLocalModel() }
        check(features.ensureKnowledgeReady()) { "The ASHA guide is not ready. Open Setup → Install ASHA guide. Your visit is saved." }
        if (!features.isKnowledgeEnabled()) check(features.setKnowledgeEnabled(true)) { "Could not select the ASHA guide. Open Setup and select it. Your visit is saved." }
        if (lastVisitId != visit.id) { runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }; lastVisitId = visit.id }
        runCatching { runtime.ensureConversationOpen() }
        onProgress?.invoke(ArogyaProgress("search"))
        val draft = StringBuilder()
        var firstWordsMs = -1L
        val turn = try {
            withTimeout(ASK_TIMEOUT_MS) {
                runtime.chat(asked, moreDetail = true,
                    onLocalDelta = { d ->
                        if (firstWordsMs < 0 && d.isNotBlank()) firstWordsMs = System.currentTimeMillis() - t0
                        synchronized(draft) { draft.append(d) }; onProgress?.invoke(ArogyaProgress("write", synchronized(draft) { draft.toString() })) },
                    onLocalAbort = { synchronized(draft) { draft.setLength(0) }; onProgress?.invoke(ArogyaProgress("checking")) })
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "AROGYA_ASK_TIMEOUT detail after ${ASK_TIMEOUT_MS / 1000}s")
            error("The fuller answer is taking too long. Your first answer is unchanged.")
        }
        return buildResult(turn, asked, System.currentTimeMillis() - t0, firstWordsMs, detail = true, onlineCheck = false)
    }

    /** Shared turn → card result: breakdown log, tiers, sources, quote, dose check. */
    private suspend fun buildResult(
        turn: AndroBaselineRuntime.TurnResult,
        question: String,
        elapsed: Long,
        firstWordsMs: Long,
        detail: Boolean,
        onlineCheck: Boolean
    ): ArogyaAiResult {
        // Joined offline with the stage lines by timestamp: POCKET_RAG_SEARCH_MS (search/rerank),
        // POCKET_RAG_EVIDENCE_MS, POCKET_RAG_LOCAL_GEN_MS / POCKET_RAG_CLOUD_MS (first words),
        // LLAMA_GENERATE (prompt/prefill/generation), POCKET_RAG_GATE_MS (checker), recovery lines.
        Log.i(TAG, "AROGYA_ASK_BREAKDOWN detail=$detail online=${turn.provider == AndroBaselineRuntime.Provider.CLOUD} totalMs=$elapsed firstWordsMs=$firstWordsMs sources=${turn.sourceIds.size} profile=${ArogyaFlags.r1Profile} chars=${turn.text.length}")
        val online = turn.provider == AndroBaselineRuntime.Provider.CLOUD
        val mode = if (onlineCheck) "check_online" else if (online) "cloud" else "offline"
        val notFoundMarker = turn.sourceIds.firstOrNull { it.startsWith(KnowledgeSourcePresentation.NOT_FOUND_PREFIX) }
        val liveWeb = turn.sourceIds.any { it.startsWith("freshness:") }
        val hasGuideAnswer = turn.text.trim().isNotBlank() && notFoundMarker == null && !liveWeb
        // UI6: the model's [S1]/[1] tags are dropped from the shown text; the card draws its own
        // tappable superscripts from the checked citations (segments are built from this same text).
        val text = ArogyaAnswerFormat.stripCitationMarkers(turn.text.trim())
        val hasAnswer = text.isNotBlank()
        val provider = when (turn.provider) {
            AndroBaselineRuntime.Provider.LOCAL -> "Pramaan • offline (${runtime.localModelManager?.loadedModelFileName?.substringBeforeLast('.') ?: "local model"})"
            AndroBaselineRuntime.Provider.CLOUD -> "Pramaan • online"
            else -> "Pramaan"
        }
        // Sources exactly as Pramaan presents them: document rows (title, pages, official link) and the
        // passages behind each row with the sentences the answer came from.
        val rows = runCatching { KnowledgeSourcePresentation.visibleSources(turn.sourceIds) }.getOrDefault(emptyList())
        val passages = runCatching { KnowledgeSourcePresentation.passagesForRows(turn.sourceIds) }.getOrDefault(emptyList())
        val sources = rows.mapIndexed { i, row ->
            val url = KnowledgeSourcePresentation.openTarget(row).orEmpty()
            val label = row.substringBefore(" | ").trim()
            val title = KnowledgeSourcePresentation.cleanTitle(label.substringBefore(" — ").trim())
            var pages = label.substringAfter(" — ", "").trim()
            if (pages.isBlank()) {
                // Every source shows a page: recover it from this row's own kbref when the
                // grouped row lost it (diagnostic tells Batch 7 where it went missing).
                val sid = "S${i + 1}"
                pages = turn.sourceIds.asSequence()
                    .filter { it.startsWith("kbref:", ignoreCase = true) && it.contains(sid, ignoreCase = true) }
                    .mapNotNull { Regex("(?i)—\\s*pages?\\s+\\d+(?:\\s*[–-]\\s*\\d+)?").find(it)?.value?.trim() }
                    .firstOrNull().orEmpty()
                Log.i(TAG, "AROGYA_SOURCE_PAGE_FALLBACK row=$sid pages='$pages' title='${title.take(60)}'")
            }
            val used = passages.getOrNull(i).orEmpty()
            ArogyaSource("${i + 1}", title, pages, used.joinToString("\n\n") { it.text }, url, used.flatMap { it.highlights }.distinct())
        }
        // Item 5b Tier 2: online and the guides are silent — an allowlisted official page
        // may still ground the answer. Anything else falls through to Tier 3 (R8 stays on).
        if (!hasGuideAnswer && liveWeb && online) {
            val tier2 = runCatching { withContext(Dispatchers.IO) { buildTier2(question, turn.text, sources, elapsed) } }.getOrNull()
            if (tier2 != null) {
                Log.i(TAG, "AROGYA_ANSWER_TIER tier=2 mode=$mode sources=guide:${sources.size},web:${tier2.sources.count { it.kind == "web" }}")
                return tier2
            }
        }
        // R8 guideline-only mode (Ask SOP) = Tier 3: a live-web or general answer —
        // recognised by the kbnotfound:/freshness: markers, never by verification:optional —
        // becomes the ANM message with the closest guide topics. Grounded and "Check online"
        // answers pass through.
        if (ArogyaFlags.r8GuidelineOnly && (notFoundMarker != null || liveWeb)) {
            val topics = if (notFoundMarker != null) runCatching { KnowledgeSourcePresentation.topicsOf(notFoundMarker) }.getOrDefault(emptyList()) else emptyList()
            Log.i(TAG, "AROGYA_R8_BLOCKED kind=${if (liveWeb) "live_web" else "general"} topics=${topics.size}")
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val msg = if (hindiQ) "यह आपकी आधिकारिक गाइड या आधिकारिक सरकारी वेबसाइटों में नहीं है — अपनी ANM से पूछें।"
            else "This is not in your official guides or official government websites — ask your ANM."
            val topicLine = if (topics.isEmpty()) "" else (if (hindiQ) "\n\nगाइड में सबसे करीबी विषय: " else "\n\nClosest guide topics: ") + topics.joinToString("; ")
            Log.i(TAG, "AROGYA_ANSWER_TIER tier=3 mode=$mode sources=guide:${sources.size},web:0")
            return ArogyaAiResult(msg + topicLine, "Pramaan", elapsed,
                online = online, sourceOnly = true,
                timingsMs = mapOf("pramaan_turn" to elapsed), r8Blocked = true, r8Topics = topics, tier = 3)
        }
        val quote = runCatching { KnowledgeSourcePresentation.quoteOf(turn.sourceIds) }.getOrNull()
        // Dose safety: every dose answer is logged for the M1 human review list; a dose
        // whose evidence context names only other age groups carries an ANM caution line.
        var finalText = if (hasAnswer) text else "No answer came back from the guide. Rephrase the question or ask your ANM. Your visit is unchanged."
        if (ArogyaFlags.doseAgeMatch && hasAnswer && ArogyaDoseCheck.isDoseQuestion(question)) {
            val group = ArogyaDoseCheck.askedGroup(question)
            val verdict = runCatching { ArogyaDoseCheck.check(question, text, sources.joinToString("\n") { it.excerpt }) }.getOrDefault(ArogyaDoseCheck.Verdict.UNCERTAIN)
            val nums = runCatching { ArogyaAiExtras.numbers(text).distinct().take(6).joinToString(",") }.getOrDefault("")
            Log.i(TAG, "AROGYA_DOSE_CHECK group=${group.name} verdict=${verdict.name} nums=[$nums] q='${question.take(80)}'")
            if (verdict == ArogyaDoseCheck.Verdict.MISMATCH) {
                val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
                finalText += "\n\n" + ArogyaDoseCheck.caution(group, hindiQ)
            }
        }
        // Item 5d: per-sentence guide citations (from the model text; the dose caution is app chrome).
        val segments = if (hasAnswer && sources.isNotEmpty()) ArogyaOfficialAnswer.guideSegments(text, sources) else emptyList()
        Log.i(TAG, "AROGYA_ANSWER_TIER tier=1 mode=$mode sources=guide:${sources.size},web:0")
        return ArogyaAiResult(
            finalText,
            provider, elapsed, sources = sources, online = turn.provider == AndroBaselineRuntime.Provider.CLOUD,
            checkedAt = if (turn.provider == AndroBaselineRuntime.Provider.CLOUD) java.time.Instant.now().toString() else "",
            sourceOnly = !hasAnswer, timingsMs = mapOf("pramaan_turn" to elapsed),
            quote = quote?.takeIf { !it.mismatch }?.text.orEmpty(), quoteSource = quote?.source.orEmpty(),
            tier = 1, segments = segments
        )
    }

    /**
     * Item 5b Tier 2: the live-web answer is kept only when official pages ground it.
     * Non-allowlisted references are discarded; each kept sentence must occur
     * (normalised) in a fetched official page or a guide passage; medication/dose,
     * urgency-downgrade and patient-assessment sentences fail the whole answer.
     * Null → the caller falls back to Tier 3.
     */
    private suspend fun buildTier2(question: String, webText: String, guideSources: List<ArogyaSource>, elapsed: Long): ArogyaAiResult? {
        val (answer, refs) = ArogyaOfficialSources.parseRefs(webText)
        val clean = ArogyaAnswerFormat.stripCitationMarkers(answer.removePrefix("Not from the selected knowledge base — answered online:").trim())
        if (clean.isBlank() || refs.isEmpty()) return null
        if (ArogyaOnlinePolicy.DOWNGRADE.containsMatchIn(clean) || ArogyaOnlinePolicy.PATIENT_ASSESSMENT.containsMatchIn(clean)) {
            Log.i(TAG, "AROGYA_TIER2_REJECT safety")
            return null
        }
        val pairs = refs.take(3).mapNotNull { ref ->
            if (!ArogyaNetWatch.online) { Log.i(TAG, "AROGYA_NET_LOST cancelled=tier2_fetch"); return null }
            val f = runCatching { withTimeoutOrNull(9_000) { ArogyaOfficialSources.fetch(ref.url) } }.getOrNull()
            if (f == null || f.text.isBlank()) null else ref to f
        }
        if (pairs.isEmpty()) {
            Log.i(TAG, "AROGYA_TIER2_REJECT no_fetched_pages refs=${refs.size}")
            return null
        }
        val segments = ArogyaOfficialAnswer.webSegments(clean, pairs.map { it.second }, guideSources)
        if (segments.isEmpty()) {
            Log.i(TAG, "AROGYA_TIER2_REJECT ungrounded")
            return null
        }
        val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
        val usedWeb = segments.flatMap { it.sourceIds }.filter { it.startsWith("W") }.distinct()
        val usedGuide = segments.flatMap { it.sourceIds }.filter { !it.startsWith("W") }.distinct()
        val webSources = usedWeb.mapNotNull { id ->
            val idx = id.removePrefix("W").toIntOrNull()?.minus(1) ?: return@mapNotNull null
            val (ref, p) = pairs.getOrNull(idx) ?: return@mapNotNull null
            ArogyaSource(id, ref.title.ifBlank { p.title }.ifBlank { p.url },
                if (p.url.lowercase().contains(".pdf")) "PDF" else "Web page",
                p.text.take(2_000), p.url,
                segments.filter { id in it.sourceIds }.map { it.text }.take(6),
                kind = "web", date = p.date, domain = ArogyaOfficialSources.domainOf(p.url))
        }
        // Guide sources cited too (a sentence may match both): guide rows first, then web.
        val citedGuides = usedGuide.mapNotNull { id -> guideSources.firstOrNull { it.id == id } }
        // Newer-guidance flag: a web-only sentence from a page newer than the guide document.
        var conflictLine = ""
        val webOnly = segments.any { seg -> seg.sourceIds.any { it.startsWith("W") } && seg.sourceIds.none { !it.startsWith("W") } }
        if (webOnly) {
            val lib = runCatching { features.guideLibrary() }.getOrDefault(emptyList())
            fun guideDateOf(title: String): String {
                val row = lib.firstOrNull { it.title.equals(title, ignoreCase = true) || it.sourceName.equals(title, ignoreCase = true) }
                    ?: lib.firstOrNull { it.title.contains(title, ignoreCase = true) || title.contains(it.title, ignoreCase = true) }
                return row?.publicationDate?.ifBlank { row?.retrievedUtc }.orEmpty()
            }
            val gDate = citedGuides.map { guideDateOf(it.title) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val newer = webSources.firstOrNull { ArogyaOfficialSources.isNewer(it.date, gDate) }
            if (newer != null) conflictLine = if (hindiQ) "\n\n${newer.domain} (${newer.date}) पर नई जानकारी मिली है — अपनी ANM से पक्का करें।"
            else "\n\nNewer guidance found on ${newer.domain} (${newer.date}) — confirm with your ANM."
        }
        val body = segments.joinToString(" ") { it.text } + conflictLine
        Log.i(TAG, "AROGYA_TIER2_ACCEPT sentences=${segments.size} web=${webSources.size} guides=${citedGuides.size} conflict=${conflictLine.isNotBlank()}")
        return ArogyaAiResult(body, "Pramaan • online (official website)", elapsed,
            sources = citedGuides + webSources, online = true,
            checkedAt = java.time.Instant.now().toString(),
            timingsMs = mapOf("pramaan_turn" to elapsed), tier = 2, segments = segments)
    }

    private suspend fun organiseNotes(request: ArogyaAiRequest): ArogyaAiResult {
        val start = System.currentTimeMillis()
        val notes = (if (request.task == ArogyaAiTask.HANDOVER) request.visit.notes + "\n" + request.visit.referralNote else request.visit.notes).trim()
        check(notes.isNotBlank()) { "Speak or type your visit observations first." }
        check(notes.length <= 6500) { "This recording is too long for one AI review. Keep the original notes and review them manually." }
        val (items, by) = route("organise_notes", ArogyaCapture.prompt(notes), 700, ArogyaCapture.prompt(ArogyaOnlinePolicy.scrub(notes, request.visit)), cloudTokens = 1500, lang = ArogyaBudget.langOf(notes)) { ArogyaCapture.parse(it, notes) }
        items ?: error("The AI could not organise this recording. Your original words are saved; edit them or retry.")
        return ArogyaAiResult(ArogyaCapture.render(items), "Pramaan • $by", System.currentTimeMillis() - start, canCopyToNotes = true, captureItems = items)
    }

    private suspend fun generate(prompt: String, model: LocalModelManager, maxTokens: Int = 420, onPartial: ((String) -> Unit)? = null): String? = coroutineScope {
        val expired = AtomicBoolean(false)
        val watchdog = launch { delay(90000); expired.set(true); model.requestCancelActiveGeneration("arogya_90s_limit") }
        try {
            val result = model.generateDetachedStream(prompt, maxTokens, "arogya_visit", onPartial)
            check(!expired.get()) { "Local AI exceeded the time limit. No draft was accepted; your visit is unchanged." }
            result
        } catch (e: CancellationException) { model.requestCancelActiveGeneration("arogya_cancel"); throw e }
        finally { withContext(NonCancellable) { watchdog.cancelAndJoin() } }
    }

    private suspend fun readyModel(): LocalModelManager {
        runCatching { ensureLocalModel() }
        val model = runtime.localModelManager
        check(model != null && model.isReady && !model.permanentlyDisabled) { "The local AI model is not ready. Open Setup and load your installed model." }
        return model
    }

    /** Simple words for the family, rewritten from the guide answer only. New numbers are removed; falls back to the guide sentence. */
    suspend fun familyExplain(answer: String, quote: String, hindi: Boolean): String = familyExplainDetailed(answer, quote, hindi).first

    /** Same, plus the engine label + time for the A5 result chip. Part B8: streams the draft via [onProgress]. */
    suspend fun familyExplainDetailed(answer: String, quote: String, hindi: Boolean, onProgress: ((ArogyaProgress) -> Unit)? = null): Triple<String, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val buf = StringBuilder()
        val (opt, by) = route("family_explain", ArogyaAiExtras.familyPrompt(answer, quote, hindi), 450,
            ArogyaAiExtras.familyPrompt(ArogyaOnlinePolicy.scrubGeneric(answer), ArogyaOnlinePolicy.scrubGeneric(quote), hindi),
            cloudTokens = 900, lang = if (hindi) "hi" else "en",
            onDelta = if (onProgress == null) null else { d -> if (d.isEmpty()) buf.clear() else buf.append(d); onProgress(ArogyaProgress("draft", buf.toString())) }) { out ->
            ArogyaAiExtras.groundedSentences(out, answer + "\n" + quote, max = 8).takeIf { it.isNotBlank() } }
        val clean = opt.orEmpty()
        Log.i(TAG, "AROGYA_TIMING event=family_explain ms=${System.currentTimeMillis() - t} chars=${clean.length}")
        check(clean.isNotBlank()) { "Could not make a simple explanation. Read the guide sentence to the family instead." }
        Triple(clean, by, System.currentTimeMillis() - t)
    }

    /** Part B5: AI only polishes the fixed template's wording; the worker edits it before sending. */
    suspend fun anmMessage(facts: String, visit: ArogyaVisit? = null): String = anmMessageDetailed(facts, visit).first

    /** Same, plus the engine label that ran it (for the demo's honest "AI at work" panel). */
    suspend fun anmMessageDetailed(facts: String, visit: ArogyaVisit? = null, hindi: Boolean = false): Pair<String, String> =
        polishAnmMessage(facts, visit, hindi).let { it.first to it.second }

    /**
     * Part B5 "Improve wording": polish [template] without changing any fact. The phone
     * sees the template; the cloud sees it with identifiers as [Pn] tokens (restored
     * afterwards, so names and phones never leave the device). Returns the message, the
     * engine label, and a note: "" when polished, else why the template is shown instead.
     */
    suspend fun polishAnmMessage(template: String, visit: ArogyaVisit?, hindi: Boolean, extraTokens: List<String> = emptyList(), onProgress: ((ArogyaProgress) -> Unit)? = null): Triple<String, String, String> = mutex.withLock {
        val t = System.currentTimeMillis()
        val v = visit ?: ArogyaVisit()
        val values = listOf(v.name, v.village, v.birthDate) + extraTokens
        val (tokenized, used) = ArogyaAnmTemplate.tokenize(template, values)
        val toks = used.mapIndexed { i, _ -> "[P${i + 1}]" }
        val cloudPrompt = if (visit != null) ArogyaOnlinePolicy.scrub(tokenized, v) else ArogyaOnlinePolicy.scrubGeneric(tokenized)
        val lang = if (hindi) "hi" else ArogyaBudget.langOf(template)
        val buf = StringBuilder()
        val (opt, by) = route("anm_message", ArogyaAiExtras.anmPrompt(template, hindi), 450, ArogyaAiExtras.anmPrompt(cloudPrompt, hindi), cloudTokens = 900, lang = lang,
            onDelta = if (onProgress == null) null else { d ->
                if (d.isEmpty()) buf.clear() else buf.append(d)
                onProgress(ArogyaProgress("draft", ArogyaAnmTemplate.detokenize(buf.toString(), used)))
            }) { out ->
            ArogyaAiExtras.groundedSentences(out, template + "\n" + tokenized, max = 60, lineMode = true).takeIf { it.length >= 30 } }
        val cloudRan = !by.startsWith("On-phone")
        val seen = if (cloudRan) tokenized else template
        val polishedRaw = opt.orEmpty()
        val signs = ArogyaAnmTemplate.signNames(seen, v, hindi)
        val reason = if (polishedRaw.isBlank()) "the AI returned nothing usable"
        else ArogyaAnmTemplate.verifyPolished(seen, polishedRaw, signs, if (cloudRan) toks else emptyList())
        val clean = if (reason == null) ArogyaAnmTemplate.detokenize(polishedRaw, used) else template
        Log.i(TAG, "AROGYA_TIMING event=anm_message ms=${System.currentTimeMillis() - t} chars=${clean.length} polished=${reason == null} reason=${reason ?: "ok"}")
        Triple(clean, by, reason.orEmpty())
    }

    /** Daily refresher: one question per guide passage, kept only if the right answer is in the passage. */
    suspend fun refresher(count: Int = 3): List<ArogyaQuizItem> = refresherDetailed(count).first

    /** Same, plus the engine label + time for the A5 result chip. Passages in [excludeIds] are skipped so repeats stop. */
    suspend fun refresherDetailed(count: Int = 3, excludeIds: Set<String> = emptySet()): Triple<List<ArogyaQuizItem>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val pool = ArogyaSources.all.values.filter { it.excerpt.length > 120 }
        val pickIds = ArogyaPractice.pickFresh(pool.map { it.id }, excludeIds, count + 2).toSet()
        val picks = pool.filter { it.id in pickIds }
        val out = ArrayList<ArogyaQuizItem>()
        var by = ""
        for (src in picks) {
            if (out.size >= count) break
            val (item, label) = route("daily_practice", ArogyaAiExtras.quizPrompt(src.excerpt), 200) { ArogyaAiExtras.parseQuiz(it, src) }
            item?.let { out += it; by = label }
        }
        Log.i(TAG, "AROGYA_TIMING event=refresher ms=${System.currentTimeMillis() - t} items=${out.size}")
        check(out.isNotEmpty()) { "Could not prepare today's questions. Try again." }
        Triple(out, by, System.currentTimeMillis() - t)
    }

    /** Reads an MCP card photo/PDF: text by ML Kit on the phone, fields by Llama, and only fields found in the text are kept. */
    suspend fun readCard(uri: android.net.Uri): ArogyaCardReading = readCardDetailed(uri).first

    /** Same, plus the engine label that read the fields (for the demo's honest "AI at work" panel). */
    suspend fun readCardDetailed(uri: android.net.Uri): Pair<ArogyaCardReading, String> = mutex.withLock {
        val t = System.currentTimeMillis()
        val mgr = attachments ?: error("Card reading is not available.")
        mgr.clearPending()
        val staged = mgr.stageUri(uri) ?: error("Could not read this file. Use a clear photo or a PDF.")
        val claimed = mgr.claimPendingAttachmentsForTurn()
        try {
            val text = listOf(staged.extractedText, staged.localVisualContext).filter { it.isNotBlank() }.joinToString("\n").trim()
            check(text.length >= 15) { "No readable text was found. Take a closer photo in good light, card flat." }
            // Only values found in the card's own text are kept. Local-only: a card photo
            // is identifiers by design, and names must never reach a cloud prompt.
            var lastRaw = ""
            val lang = ArogyaBudget.langOf(text)
            suspend fun readHalf(half: String): List<ArogyaCardField> {
                val (o, _) = route("card_read", ArogyaAiExtras.cardPrompt(half), 900, cloud = false, cloudTokens = 1500, lang = ArogyaBudget.langOf(half)) { out ->
                    ArogyaAiExtras.parseCard(out, half).takeIf { it.isNotEmpty() } }
                return o.orEmpty()
            }
            val (opt, by0) = route("card_read", ArogyaAiExtras.cardPrompt(text), 900, cloud = false, cloudTokens = 1500, lang = lang) { out ->
                lastRaw = out
                ArogyaAiExtras.parseCard(out, text).takeIf { it.isNotEmpty() } }
            var fields = opt.orEmpty()
            var by = by0
            // Part B6: cut JSON (unclosed brackets) → read each half and merge, so no row is lost.
            if (fields.isEmpty() && lastRaw.isNotBlank() && !ArogyaExtractSplit.jsonComplete(lastRaw)) {
                val (h1, h2) = ArogyaExtractSplit.halves(text)
                if (h2.isNotBlank()) {
                    fields = ArogyaExtractSplit.mergeCard(readHalf(h1), readHalf(h2))
                    by = (by + " + split-read").trim(' ', '+')
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=card_read parts=2 fields=${fields.size}")
                }
            }
            Log.i(TAG, "AROGYA_TIMING event=card_read ms=${System.currentTimeMillis() - t} ocrChars=${text.length} fields=${fields.size}")
            ArogyaCardReading(text, fields) to by
        } finally { mgr.releaseTurnAttachments(claimed.map { it.id }, consumed = true) }
    }

    /**
     * A4 medicine scan: name, batch, expiry and quantity off a strip/box photo. The OCR text
     * comes from ML Kit on the phone; the deterministic parse runs first and the model (via
     * route()) only fills what is missing. Every kept value is grounded in the OCR text.
     * Returns the scan, the engine label and the parse time (OCR time is measured by the caller).
     */
    /**
     * Add-person photo/PDF: copy the person's details off OCR text. Aadhaar numbers
     * are masked before the model sees the text; every kept value is grounded in it.
     * Returns the field map, the engine label and the time.
     */
    suspend fun extractPersonFields(ocr: String): Triple<Map<String, String>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val clean = ArogyaPersonExtract.maskAadhaar(ocr)
        check(clean.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        val det = mutableMapOf<String, String>()
        Regex("""(?<!\d)(\d{10})(?!\d)""").find(clean)?.let { det["phone"] = it.groupValues[1] }
        var by = "On-phone rules (offline): patterns"
        var lastRaw = ""
        suspend fun readHalf(half: String): Map<String, String> {
            val (o, _) = route("person_extract", ArogyaPersonExtract.prompt(half), 900, cloud = false, cloudTokens = 1500, lang = ArogyaBudget.langOf(half)) { out ->
                ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(out), half)
            }
            return o.orEmpty()
        }
        // Local-only: the OCR is identifiers by design, and names must never reach a cloud prompt.
        val (opt, label) = route("person_extract", ArogyaPersonExtract.prompt(clean), 900, cloud = false, cloudTokens = 1500, lang = ArogyaBudget.langOf(clean)) { out ->
            lastRaw = out
            ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(out), clean)
        }
        opt?.let { by = label }
        var model = opt.orEmpty()
        // Part B6: a cut last line (or almost nothing from a long page) → read each half and merge.
        if ((ArogyaExtractSplit.lastLineCut(lastRaw) || (model.size < 2 && clean.length > 800)) && lastRaw.isNotBlank()) {
            val (h1, h2) = ArogyaExtractSplit.halves(clean)
            if (h2.isNotBlank()) {
                model = ArogyaExtractSplit.mergePerson(ArogyaExtractSplit.mergePerson(model, readHalf(h1)), readHalf(h2))
                by = (by + " + split-read").trim(' ', '+')
                Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=person_extract parts=2 fields=${model.size}")
            }
        }
        val fields = ArogyaPersonExtract.ground(det + model, clean)
        Log.i(TAG, "AROGYA_TIMING event=person_extract ms=${System.currentTimeMillis() - t} fields=${fields.size}")
        Triple(fields, by, System.currentTimeMillis() - t)
    }

    /**
     * Part B6: a register page with several people → one field map per block, each
     * grounded in its own block. Phone-only (identifiers); at most 10 blocks.
     */
    suspend fun extractPersonList(ocr: String): Triple<List<Map<String, String>>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val clean = ArogyaPersonExtract.maskAadhaar(ocr)
        check(clean.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        val blocks = ArogyaExtractSplit.splitBlocks(clean).ifEmpty { listOf(clean) }
        var by = "On-phone rules (offline): patterns"
        val out = ArrayList<Map<String, String>>()
        blocks.forEach { block ->
            val det = mutableMapOf<String, String>()
            Regex("""(?<!\d)(\d{10})(?!\d)""").find(block)?.let { det["phone"] = it.groupValues[1] }
            val (opt, label) = route("person_extract", ArogyaPersonExtract.prompt(block), 900, cloud = false, cloudTokens = 1500, lang = ArogyaBudget.langOf(block)) { raw ->
                ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(raw), block).takeIf { it.isNotEmpty() }
            }
            val fields = ArogyaPersonExtract.ground(det + (opt ?: emptyMap()), block)
            if (fields.isNotEmpty()) {
                out += fields
                if (opt != null) by = label
            }
        }
        Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=person_extract parts=${blocks.size} people=${out.size}")
        Log.i(TAG, "AROGYA_TIMING event=person_extract_list ms=${System.currentTimeMillis() - t} people=${out.size}")
        Triple(out, by, System.currentTimeMillis() - t)
    }

    suspend fun scanMedicine(ocr: String): Triple<MedScan, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        check(ocr.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        var scan = ArogyaMedScan.parse(ocr)
        var by = "On-phone rules (offline): regex"
        if (scan.name.isBlank() || scan.batch.isBlank() || scan.expiry.isBlank()) {
            var lastRaw = ""
            val (opt, label) = route("medicine_scan", ArogyaMedScan.prompt(ocr), 400, ArogyaMedScan.prompt(ArogyaOnlinePolicy.scrubGeneric(ocr)), cloudTokens = 900, lang = ArogyaBudget.langOf(ocr)) { out ->
                lastRaw = out
                ArogyaMedScan.ground(ArogyaMedScan.parse(out), ocr)
            }
            var model = opt ?: MedScan()
            // Part B6: a cut last line → read each half and merge.
            if (lastRaw.isNotBlank() && ArogyaExtractSplit.lastLineCut(lastRaw)) {
                val (h1, h2) = ArogyaExtractSplit.halves(ocr)
                if (h2.isNotBlank()) {
                    suspend fun readHalf(half: String): MedScan {
                        val (o, _) = route("medicine_scan", ArogyaMedScan.prompt(half), 400, ArogyaMedScan.prompt(ArogyaOnlinePolicy.scrubGeneric(half)), cloudTokens = 900, lang = ArogyaBudget.langOf(half)) { out ->
                            ArogyaMedScan.ground(ArogyaMedScan.parse(out), half)
                        }
                        return o ?: MedScan()
                    }
                    model = ArogyaExtractSplit.mergeMed(ArogyaExtractSplit.mergeMed(model, readHalf(h1)), readHalf(h2))
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=medicine_scan parts=2")
                }
            }
            if (opt != null || model != MedScan()) {
                scan = ArogyaExtractSplit.mergeMed(scan, model)
                if (opt != null) by = label
            }
        }
        scan = ArogyaMedScan.ground(scan, ocr)
        Log.i(TAG, "AROGYA_TIMING event=medicine_scan ms=${System.currentTimeMillis() - t} name=${scan.name.isNotBlank()} batch=${scan.batch.isNotBlank()} expiry=${scan.expiry.isNotBlank()} qty=${scan.qty}")
        Triple(scan, by, System.currentTimeMillis() - t)
    }

    /**
     * Voice stock entry: a spoken stock change → grounded lines the ASHA confirms.
     * Deterministic parse first; route() (phone or cloud per the Home switch) only fills
     * in when the rules find nothing. Every kept line's item and number are in the transcript.
     */
    suspend fun voiceStock(transcript: String, catalog: List<String>): Triple<List<VoiceStockLine>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        check(transcript.trim().length >= 4) { "Speak the stock change first." }
        var lines = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules(transcript, catalog), transcript, catalog)
        var by = "On-phone rules (offline): patterns"
        if (lines.isEmpty()) {
            var lastRaw = ""
            val (opt, label) = route("voice_stock", ArogyaVoiceStock.prompt(transcript, catalog), 400,
                ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric(transcript), catalog), cloudTokens = 900, lang = ArogyaBudget.langOf(transcript)) { out ->
                lastRaw = out
                ArogyaVoiceStock.ground(ArogyaVoiceStock.parseModel(out), transcript, catalog).takeIf { it.isNotEmpty() }
            }
            opt?.let { lines = it; by = label }
            // Part B6: nothing parsed from a long note (the model answer was cut) → read each half and merge.
            if (lines.isEmpty() && lastRaw.isNotBlank() && transcript.length > 400) {
                val (h1, h2) = ArogyaExtractSplit.halves(transcript)
                if (h2.isNotBlank()) {
                    suspend fun readHalf(half: String): List<VoiceStockLine> {
                        val (o, _) = route("voice_stock", ArogyaVoiceStock.prompt(half, catalog), 400,
                            ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric(half), catalog), cloudTokens = 900, lang = ArogyaBudget.langOf(half)) { out ->
                            ArogyaVoiceStock.ground(ArogyaVoiceStock.parseModel(out), half, catalog).takeIf { it.isNotEmpty() }
                        }
                        return o.orEmpty()
                    }
                    lines = ArogyaExtractSplit.mergeVoice(readHalf(h1), readHalf(h2))
                    if (lines.isNotEmpty()) by = (by + " + split-read").trim(' ', '+')
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=voice_stock parts=2 lines=${lines.size}")
                }
            }
        }
        Log.i(TAG, "AROGYA_TIMING event=voice_stock ms=${System.currentTimeMillis() - t} lines=${lines.size}")
        Triple(lines, by, System.currentTimeMillis() - t)
    }

    /**
     * Finish Visit step 1: notes → structured fields. Uses the chosen hosted Meta model when a key is
     * saved (notes are scrubbed of the beneficiary's name first); otherwise, or if that call fails,
     * the phone's own model. Every field is checked against the notes; temperature and weight are
     * also read by fixed rules. The result is only a proposal for the ASHA to tick.
     */
    suspend fun extractFields(visit: ArogyaVisit): ArogyaExtraction = mutex.withLock {
        val notes = visit.notes.trim()
        check(notes.isNotBlank()) { "Speak or type your visit observations first." }
        check(notes.length <= 6000) { "These notes are too long for one review. Shorten them first." }
        val t = System.currentTimeMillis()
        val (ai, by) = route("finish_visit", ArogyaFinishVisit.extractionPrompt(notes), 900, ArogyaFinishVisit.extractionPrompt(ArogyaOnlinePolicy.scrub(notes, visit)),
            system = "You extract structured fields from health-worker notes. Reply with JSON only.", cloudTokens = 1500, lang = ArogyaBudget.langOf(notes)) { raw -> ArogyaFinishVisit.parseExtraction(raw, notes).takeIf { it.isNotEmpty() } }
        val fields = ai.orEmpty()
        val rules = ArogyaFinishVisit.ruleFallback(notes).filter { r -> fields.none { it.key == r.key } }
        val ms = System.currentTimeMillis() - t
        val label = by.ifBlank { "Fixed patterns only (no model)" }
        Log.i(TAG, "AROGYA_FINISH_EXTRACT model=\"$label\" ms=$ms aiFields=${fields.size} ruleFields=${rules.size}")
        ArogyaExtraction(fields + rules, label, !label.startsWith("On-phone") && !label.startsWith("Fixed"), ms, "")
    }

    /**
     * Voice note → one column of an official form. Follows the app's Local first / Cloud first setting:
     * Cloud first tries the cloud model (identifiers removed) and falls back to the phone; Local first uses the
     * phone model and asks the cloud only if the phone model is unavailable or fills nothing.
     */
    suspend fun fillForm(b: Beneficiary, spec: FormSpec, column: Int, note: String): Pair<Map<String, FilledValue>, ArogyaExtraction> = mutex.withLock {
        val choice = runCatching { withContext(Dispatchers.IO) { cloudChoice?.invoke() } }.getOrNull()
        val endpoint = choice?.endpoint; val key = choice?.key.orEmpty(); val cloudFirst = choice?.cloudFirst == true
        val text = note.trim()
        check(text.isNotBlank()) { "Speak or type what you saw first." }
        check(text.length <= 6000) { "This note is too long. Shorten it first." }
        val t = System.currentTimeMillis()
        var label = ""; var meta = false; var msg = ""
        val cloudReady = endpoint != null && key.isNotBlank()
        suspend fun cloud(): Map<String, FilledValue>? = try {
            val raw = ArogyaCloud.withDeadline(CLOUD_DEADLINE_MS, "fill_form") {
                OpenAiCompatibleClient(endpoint!!, { key }, CLOUD_DEADLINE_MS.toInt()).complete("You fill official health forms from notes. Reply with JSON only.",
                    ArogyaOfficialForms.prompt(spec, column, ArogyaOfficialForms.scrub(b, text)), 900)
            } ?: error("no reply within ${CLOUD_DEADLINE_MS / 1000} s")
            label = endpoint!!.label; meta = endpoint.metaModel
            ArogyaOfficialForms.parse(raw, spec, column, text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { msg = (msg + " Online model not used: ${e.message?.take(120)}").trim(); Log.w(TAG, "AROGYA_FORM_ONLINE_FAILED ${endpoint?.id} ${e.message?.take(200)}"); null }
        suspend fun phone(): Map<String, FilledValue>? = try {
            val model = readyModel()
            val raw = generate(ArogyaOfficialForms.prompt(spec, column, text), model, 700)
            label = "On-phone model (offline): " + (model.loadedModelFileName?.substringBeforeLast('.') ?: "local"); meta = false
            ArogyaOfficialForms.parse(raw.orEmpty(), spec, column, text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { msg = (msg + " On-phone model not ready: ${e.message?.take(100)}").trim(); null }
        var values: Map<String, FilledValue>? = null
        if (cloudFirst && cloudReady) values = cloud()
        if (values == null) values = phone()
        if (!cloudFirst && cloudReady && values.orEmpty().none { it.value.source == "ai" }) cloud()?.let { values = it }
        if (values == null) { label = "Fixed patterns only (no model)"; values = ArogyaOfficialForms.parse("", spec, column, text) }
        val ms = System.currentTimeMillis() - t
        Log.i(TAG, "AROGYA_FORM_FILL form=${spec.id} col=$column policy=${if (cloudFirst) "cloud_first" else "local_first"} model=\"$label\" meta=$meta ms=$ms filled=${values!!.size}/${ArogyaOfficialForms.fieldsFor(spec, column).size}")
        values!! to ArogyaExtraction(emptyList(), label, meta, ms, msg)
    }

    companion object { private const val TAG = "ArogyaDemo"; const val CLOUD_DEADLINE_MS = 25_000L; const val ASK_TIMEOUT_MS = 300_000L }
}
