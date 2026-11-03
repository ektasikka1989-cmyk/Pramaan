package com.example.arfas

import android.util.Log
import java.util.Locale

/**
 * Domain-neutral selected knowledge-pack provider.
 *
 * Contract:
 * - the user explicitly selects the pack;
 * - retrieval is hybrid lexical + semantic inside that pack;
 * - the literal current user turn is always the primary retrieval query;
 * - at most two generic task-shape views may supplement the literal query;
 * - evidence is fused from multiple relevant documents into one bounded packet;
 * - only sources that actually fit inside that packet are exposed to the UI;
 * - answer adequacy is judged semantically, not by domain/query keyword lists.
 */


class GenericKnowledgeContextProvider(
    private val enabled: () -> Boolean,
    private val ensureReady: suspend () -> Boolean,
    private val repository: GenericKnowledgePackRepository,
    private val isContextDependent: suspend (String, List<AndroChatMessage>) -> Boolean
) : BaselineContextProvider, BaselineResponseValidator {

    companion object {
        /** Another pack is suggested only when its best passage clears this re-ranker score… */
        const val OTHER_PACK_MIN_RERANK = -1.0f
        /** …and beats the active pack's best by this margin. */
        const val OTHER_PACK_MARGIN = 2.5f
        /** A visit note cites the knowledge base only for a passage the re-ranker clearly matched. */
        const val QUICK_CITATION_MIN_RERANK = 1.0f
        private const val MAX_OTHER_PACKS = 4
        private const val OTHER_PACK_POOL = 8

        /**
         * Follow-up resolution thresholds, in re-ranker logits (a property of the re-ranker model).
         * Measured on the 75-question set: stand-alone questions scored >= 0.9 on their own; the
         * elliptical "Explain the second one." scored -2.89 alone and 4.67 with its previous question.
         * On the phone the stand-alone "what are different type of tenders" scored 0.88 and was
         * wrongly joined to the previous question at the old limit of 1.0, so the limit is 0.
         */
        const val FOLLOW_UP_WEAK_RERANK = 0.0f
        const val FOLLOW_UP_MARGIN = 2.0f
        /** A question of at most this many words may be elliptical ("and the dose?"). */
        const val FOLLOW_UP_SHORT_WORDS = 4
        const val FOLLOW_UP_ANSWER_CHARS = PocketRagPromptBuilder.FOLLOW_UP_ANSWER_CHARS
        private const val TAG = "GenericKnowledgeContext"
        /** Best re-ranker relevance at or above which the literal search is trusted without a rewrite. */
        const val REWRITE_BELOW_RERANK = 2.0f
        /** A rewrite search is not used when its best relevance falls this far below the literal one. */
        const val REWRITE_MAX_LOSS = 1.0f
        /** Best re-ranker relevance at or above which no second search pass runs (speedSkipSecondPass). */
        const val SKIP_SECOND_PASS_RERANK = 8.0f
        /** Passages whose sentences compete for key facts. */
        const val KEY_FACT_PASSAGES = 5
        /** "Check online": passages (and characters per passage) sent to the online model. */
        const val ONLINE_PASSAGES = 12
        const val ONLINE_WINDOW_CHARS = 1_600
        /** Part B3: Check-online turns read up to 16 passages; the Cloud-first wide set stays 12 x 1600. */
        const val CHECK_PASSAGES = 16
        /** Evidence budget (characters) from which the model reads whole passages instead of key sentences. */
        const val PASSAGE_MODE_MIN_CHARS = 3_000
        /**
         * Speed pass (UI2): offline phone answers read at most this much evidence. Measured on the
         * phone: ~6,000 chars made a 2,135-token prompt and 29 s before the first word.
         */
        const val PHONE_EVIDENCE_CAP_CHARS = 4_000
        /** Set per turn by the Ask path: true only while an offline (phone-model) turn runs. */
        @Volatile @JvmStatic var phoneEvidenceCap: Boolean = false
    }

    data class LookupState(
        val attempted: Boolean = false,
        val ready: Boolean = true,
        val confident: Boolean = false,
        val topSimilarity: Float = 0f,
        val packId: String = "",
        val displayName: String = ""
    )

    @Volatile private var lookupState = LookupState()
    /** (user question, text it was resolved to) of the previous KB turn, for chained follow-ups. */
    @Volatile private var lastResolution: Pair<String, String>? = null
    // AnythingLLM-style source-window memory: retrieval always uses the CURRENT user
    // message. Recent accepted evidence is only a context backfill when the current
    // search yields a small window; it never becomes part of the search query.
    @Volatile private var acceptedEvidenceWindow: List<GenericKnowledgePackRepository.Hit> = emptyList()
    @Volatile private var acceptedEvidencePackId: String = ""
    @Volatile private var pendingEvidenceWindow: List<GenericKnowledgePackRepository.Hit> = emptyList()
    @Volatile private var pendingEvidencePackId: String = ""
    @Volatile private var pendingEvidenceQuery: String = ""
    @Volatile private var activeContextWindowTokens: Int = 4_096
    /** The passages of the last packet, in Source ID order (S1 = first). */
    @Volatile private var lastPacked: List<GenericKnowledgePackRepository.Hit> = emptyList()

    /**
     * The cited passages' surroundings: their documents' titles and the same and two previous pages
     * (section headings). "How should a person with diabetes cut their toenails?" is answered by a
     * foot-care passage under a diabetes heading that never repeats the word; the topic check reads
     * this too, so the warning does not fire for it.
     */
    suspend fun citedContextText(sourceIds: Set<String>): String {
        val hits = sourceIds.mapNotNull { id -> id.uppercase().removePrefix("S").toIntOrNull()?.let { lastPacked.getOrNull(it - 1) } }
            .distinctBy { it.docId to it.pageNumber }.take(3)
        val sb = StringBuilder()
        for (h in hits) sb.append((h.sourceTitle ?: h.sourceName).orEmpty()).append('\n')
            .append(runCatching { repository.nearbyText(h.docId, h.pageNumber) }.getOrDefault("")).append('\n')
        return sb.toString()
    }

    /** Set during [contextForOnline]: more and longer passages for the online model. */
    @Volatile private var widePassages = false
    /** Answer-model question rewrite (search question + arithmetic); null until the runtime wires it. */
    @Volatile private var questionRewriter: (suspend (String, List<String>) -> GenericKbQuestionRewrite?)? = null
    /** Rewrite of the current turn (for the app-side calculation in the evidence packet). */
    @Volatile private var currentRewrite: GenericKbQuestionRewrite? = null
    @Volatile private var keyFactsScoredByModel = false

    fun configureQuestionRewriter(rewriter: suspend (String, List<String>) -> GenericKbQuestionRewrite?) {
        questionRewriter = rewriter
    }


    fun isSelected(): Boolean = enabled()

    /**
     * One quick look-up in the active pack (a visit note's danger sign, for its page citation):
     * the best passage's source label and its most relevant sentence, or null when nothing in the
     * pack clearly matches.
     */
    suspend fun quickCitation(query: String, minRelevance: Float = QUICK_CITATION_MIN_RERANK): Pair<String, String>? {
        if (!enabled() || query.isBlank() || !(repository.isReady() || runCatching { ensureReady() }.getOrDefault(false))) return null
        val plan = GenericKbQueryPlanner.plan(query, query)
        val result = runCatching { repository.searchPhase14(query.trim(), plan.clauses, OTHER_PACK_POOL) }.getOrNull() ?: return null
        val best = result.bestRerank ?: return null
        if (best < minRelevance) return null
        val hit = result.hits.firstOrNull() ?: return null
        val sentence = KnowledgeSourcePresentation.bestSentence(hit.content.replace(Regex("\\s+"), " ").trim().take(1_200), query)
        return sourceLabel(hit) to sentence.take(220)
    }

    /**
     * Knowledge-base health check of the active pack ([KbHealthCheck]): [samples] passages spread
     * across documents are searched for with a question made from each (by [questionWriter], the
     * phone's model, when given; else the passage's key sentence) and found or not in the top 5;
     * plus the pack's data statistics. [onProgress] gets "3/20".
     */
    suspend fun healthCheck(
        samples: Int = 20,
        questionWriter: (suspend (String) -> String?)? = null,
        onProgress: ((String) -> Unit)? = null
    ): KbHealthCheck.Report? {
        if (!(repository.isReady() || runCatching { ensureReady() }.getOrDefault(false))) return null
        val picked = repository.healthSamples(samples)
        val probes = ArrayList<KbHealthCheck.Probe>()
        var byModel = 0
        for ((i, s) in picked.withIndex()) {
            onProgress?.invoke("${i + 1}/${picked.size}")
            val written = questionWriter?.let { w -> runCatching { w(s.content.take(1_500)) }.getOrNull()?.trim()?.lineSequence()?.firstOrNull()?.trim() }
                ?.takeIf { it.length in 12..240 }
            val question = written ?: KbHealthCheck.keySentence(s.content) ?: continue
            if (written != null) byModel++
            val started = System.currentTimeMillis()
            val plan = GenericKbQueryPlanner.plan(question, question)
            val result = runCatching { repository.searchPhase14(question, plan.clauses, 24) }.getOrNull()
            // The same text from another copy of the document (an amended version) counts as found.
            fun key(t: String) = t.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }.take(300)
            val sampleKey = key(s.content)
            val rank = result?.hits?.take(5)?.indexOfFirst { h ->
                h.chunkId == s.chunkId || h.originalChunkId == s.chunkId || (sampleKey.length >= 80 && key(h.content).contains(sampleKey.take(120))) ||
                    (h.docId == s.docId && s.page != null && (h.pageNumber == s.page || (h.pageNumber != null && h.pageEnd != null && s.page in h.pageNumber..h.pageEnd)))
            }?.takeIf { it >= 0 }?.plus(1)
            probes += KbHealthCheck.Probe(s.title.ifBlank { s.docId }, s.page, question, rank, result?.bestRerank, System.currentTimeMillis() - started)
        }
        val stats = repository.healthStats()
        val report = KbHealthCheck.report(repository.currentDisplayName(), probes, stats, questionsByModel = byModel * 2 > probes.size)
        Log.i(TAG, "KB_HEALTH pack=${repository.currentDisplayName()} score=${report.score} at5=${report.recallAt5} at1=${report.recallAt1} n=${probes.size}")
        return report
    }

    /** The re-ranker as a statement-vs-passages scorer for [AnswerProof]; null until it is ready. */
    fun proofScorer(): (suspend (String, List<String>) -> FloatArray?)? = repository.keyFactScorer()

    /** UI2: the same re-ranker reading every statement's pairs in one batched run. */
    fun proofPairScorer(): (suspend (List<Pair<String, String>>) -> FloatArray?)? = repository.pairScorer()

    data class OtherPackMatch(val packId: String, val displayName: String, val relevance: Float, val source: String)

    /**
     * The active pack does not cover [query]: search the user's other installed packs (switching is
     * a file move, tens of ms) and return the one that clearly covers it. The active pack is always
     * restored. Null when no other pack has a clearly better passage.
     * Called by the runtime inside its turn lock, after the turn's own answer is settled.
     */
    suspend fun bestOtherPackFor(query: String, activeBestRelevance: Float?): OtherPackMatch? {
        if (!enabled() || query.isBlank()) return null
        val packs = runCatching { repository.installedPacks() }.getOrDefault(emptyList())
        val active = packs.firstOrNull { it.active } ?: return null
        val others = packs.filter { !it.active }.take(MAX_OTHER_PACKS)
        if (others.isEmpty()) return null
        val floor = maxOf(OTHER_PACK_MIN_RERANK, (activeBestRelevance ?: OTHER_PACK_MIN_RERANK) + OTHER_PACK_MARGIN)
        var best: OtherPackMatch? = null
        val started = System.currentTimeMillis()
        try {
            for (pack in others) {
                if (!runCatching { repository.activatePack(pack.packId) }.getOrDefault(false)) continue
                val plan = GenericKbQueryPlanner.plan(query, query)
                val result = runCatching { repository.searchPhase14(query.trim(), plan.clauses, OTHER_PACK_POOL) }.getOrNull() ?: continue
                val score = result.bestRerank ?: continue
                if (score >= floor && score > (best?.relevance ?: Float.NEGATIVE_INFINITY)) {
                    val hit = result.hits.firstOrNull()
                    val where = listOfNotNull(
                        (hit?.sourceTitle ?: hit?.sourceName)?.let { KnowledgeSourcePresentation.cleanTitle(it) }?.takeIf { it.isNotBlank() }?.take(90),
                        hit?.pageNumber?.let { "page $it" }
                    ).joinToString(", ")
                    best = OtherPackMatch(pack.packId, pack.displayName, score, where)
                }
            }
        } finally {
            runCatching { repository.activatePack(active.packId) }
                .onFailure { Log.w(TAG, "KB_OTHER_PACK_RESTORE_FAILED ${it.message}") }
        }
        Log.i(TAG, "KB_OTHER_PACK_PROBE packs=${others.size} activeBest=$activeBestRelevance best=${best?.displayName}:${best?.relevance} ms=${System.currentTimeMillis() - started}")
        return best
    }
    fun lastLookupState(): LookupState = lookupState
    fun selectedPackIdentity(): Pair<String, String> =
        repository.currentPackId() to repository.currentDisplayName()

    /** Set by the selected-KB runtime before retrieval; defaults safely until model probing completes. */
    fun setActiveContextWindowTokens(tokens: Int) {
        activeContextWindowTokens = tokens.coerceAtLeast(1_024)
    }

    /**
     * New isolated KB engine commits the evidence window after accepting a turn
     * without invoking this provider's legacy answer validator. Retrieval state
     * remains provider-owned; answer acceptance is owned by PocketRagEngine (the selected-KB answer path).
     */
    fun commitPendingEvidenceWindow(answerUsedEvidence: Boolean = true) {
        // Only an answer that actually used the evidence (it has sources) may become the accepted
        // evidence window; a refusal or a failed answer leaves the previous window in place.
        if (!answerUsedEvidence) {
            Log.i(TAG, "GENERIC_KB_EVIDENCE_WINDOW_NOT_COMMITTED reason=answer_without_sources")
            return
        }
        if (pendingEvidenceWindow.isEmpty()) return
        acceptedEvidenceWindow = pendingEvidenceWindow
        acceptedEvidencePackId = pendingEvidencePackId
        Log.i(
            TAG,
            "GENERIC_KB_ISOLATED_ACCEPT_SOURCE_WINDOW pack=${acceptedEvidencePackId.take(24)} " +
                "query=${pendingEvidenceQuery.take(80)} chunks=${acceptedEvidenceWindow.size}"
        )
    }

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = enabled() && query.isNotBlank()

    /**
     * Mechanical cross-section compaction only. No query vocabulary participates.
     * Sampling the beginning/middle/end is safer for tables/forms than blindly taking
     * only the first characters, and it remains completely domain-independent.
     */
    private fun compactAcrossContent(content: String, maxChars: Int): String {
        val clean = content.trim()
        if (clean.length <= maxChars) return clean
        if (maxChars < 180) return clean.take(maxChars)

        val third = (maxChars - 12) / 3
        val middleStart = ((clean.length - third) / 2).coerceAtLeast(third)
        val tailStart = (clean.length - third).coerceAtLeast(middleStart + third)
        return buildString {
            append(clean.take(third).trimEnd())
            append("\n…\n")
            append(clean.substring(middleStart, (middleStart + third).coerceAtMost(clean.length)).trim())
            append("\n…\n")
            append(clean.substring(tailStart).take(third).trimStart())
        }.take(maxChars)
    }


    private fun sourceLabel(hit: GenericKnowledgePackRepository.Hit): String = buildString {
        val title = hit.sourceTitle?.trim().orEmpty()
        val file = hit.sourceName?.trim().orEmpty()
        append(KnowledgeSourcePresentation.cleanTitle(if (title.isNotBlank()) title else file).ifBlank { "Knowledge source" })
        val page = hit.pageNumber
        val end = hit.pageEnd
        when {
            page != null && end != null && end > page -> append(" — pages ").append(page).append('–').append(end)
            page != null -> append(" — page ").append(page)
        }
        hit.locator?.takeIf { it.isNotBlank() && !it.contains("page", ignoreCase = true) }?.let {
            append(" — ").append(it.take(120))
        }
        // A section is shown only when the pack stores a single heading. Several lines joined with
        // '|' are the page's first lines (extraction artefact), not a section title, and misled users.
        // A running header (the same heading over many pages) is not this passage's section.
        hit.headingContext?.trim()?.takeIf { !hit.runningHeading && it.isNotBlank() && '|' !in it && it.length <= 160 }?.let {
            append(" — section ").append(it)
        }
    }

    /**
     * "Check online": the same search with an online-sized budget (the online model reads far more
     * than the phone's), so Muse Spark checks the answer against more of the related passages.
     */
    suspend fun contextForOnline(query: String, acceptedHistory: List<AndroChatMessage>, contextTokens: Int): BaselineContextAttachment? {
        val before = activeContextWindowTokens
        activeContextWindowTokens = contextTokens.coerceAtLeast(before)
        widePassages = true
        return try { contextFor(query, acceptedHistory) } finally { activeContextWindowTokens = before; widePassages = false }
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!enabled() || query.isBlank()) return null
        lookupState = LookupState(attempted = false, ready = true)
        if (!ensureReady()) {
            // Not ready (still loading, missing files): the turn continues without KB evidence.
            lookupState = LookupState(attempted = false, ready = false)
            Log.w(TAG, "GENERIC_KB_CONTEXT_UNAVAILABLE ready=false")
            return null
        }
        // Diagnostic only: if the pack's vectors do not match the phone's embedder, dense retrieval
        // is switched off for this pack and full-text retrieval keeps answering with citations.
        // It never blocks the knowledge base.
        val space = runCatching { repository.vectorSpaceCheck() }.getOrNull()
        val denseEnabled = !(space != null && space.checked > 0 && !space.passed)
        if (!denseEnabled) {
            Log.w(TAG, "GENERIC_KB_DENSE_DISABLED reason=${space?.reason} min=${space?.minimumCosine} fallback=lexical_only")
        }

        // Multi-view E5 + FTS retrieval: one database scan evaluates the literal turn
        // together with a small set of domain-neutral task views. This preserves exact
        // wording while recovering definitions, lists, procedures and follow-ups whose
        // wording differs from the document. No topic-specific keyword table is used.
        val started = System.currentTimeMillis()
        Log.i(TAG, "KB_ENGINE_BUILD abbrev-2026-10-02")
        // The literal question is always searched first and never rewritten. A history-joined
        // second search happens only for weak literal evidence (see follow-up resolution below).
        val resolvedRetrievalQuery = query.trim()
        Log.i(TAG, "LITERAL_FIRST_RETRIEVAL historyVariant=evidence_gated")
        val queryPlan = GenericKbQueryPlanner.plan(query, query)
        val literalSlots = repository.meaningfulLiteralSlots(query)
        Log.i(
            TAG,
            "GENERIC_KB_RETRIEVAL_QUERY literal=${query.take(100)} resolved=${resolvedRetrievalQuery.take(180)} " +
                "clauses=${queryPlan.clauses.size} literalSlots=${literalSlots.size} history=${acceptedHistory.size}"
        )
        val modelEvidenceBudget = GenericKbTokenBudget.evidenceCharBudget(
            contextTokens = activeContextWindowTokens,
            query = resolvedRetrievalQuery,
            clauses = queryPlan.clauses.size
        )
        // Offline phone turns only; "Check online" (widePassages) and cloud turns keep the full budget.
        val evidenceCharBudget = if (phoneEvidenceCap && !widePassages)
            minOf(modelEvidenceBudget, PHONE_EVIDENCE_CAP_CHARS) else modelEvidenceBudget
        if (evidenceCharBudget != modelEvidenceBudget)
            Log.i(TAG, "AROGYA_PHONE_EVIDENCE_CAP budget=$evidenceCharBudget model=$modelEvidenceBudget")
        val candidateWork = GenericKbTokenBudget.retrievalCandidatePool(
            evidenceCharBudget = evidenceCharBudget,
            clauses = queryPlan.clauses.size
        )
        // Phase 1.4 path; falls back to the previous clause search when the pack profile or
        // calibration is not available yet (or on any error), so behaviour never degrades to nothing.
        // Question rewrite by the answer model, decided by evidence and structure, not by topic:
        // a question with two or more numbers may need a calculation (rewrite first, one search);
        // otherwise the literal search runs first and the rewrite is tried only when the re-ranker
        // finds no convincing passage for the user's own words.
        currentRewrite = null
        val rewriter = questionRewriter
        suspend fun rewrite(): GenericKbQuestionRewrite? = rewriter?.let { r ->
            runCatching { r(resolvedRetrievalQuery, repository.aliasExpansions(resolvedRetrievalQuery)) }.getOrNull()
        }
        var phase14: GenericKnowledgePackRepository.Phase14Result? = null
        // Up front only when amounts must be worked out (each rewrite is one extra model call, a few
        // seconds on the phone); other questions are rewritten only when their own words find no
        // convincing passage (below).
        // Long or two-part questions too ("explain ePBG and how it should be calculated for ..."): their
        // own words matched only the many guide pages that mention ePBG, never the rule that gives the
        // amount; one search per part finds both.
        val unknownWords = runCatching { repository.unknownWords(resolvedRetrievalQuery) }.getOrDefault(emptyList())
        if (unknownWords.isNotEmpty()) Log.i(TAG, "KB_UNKNOWN_WORDS ${unknownWords.take(6).joinToString(",")}")
        // Hindi/Hinglish questions also search English dictionary terms (the question itself
        // is untouched, so the answer stays in Hindi).
        val hindiViews = if (ArogyaFlags.hindiRewrite && ArogyaHindiTerms.needsViews(resolvedRetrievalQuery))
            runCatching { ArogyaHindiTerms.views(resolvedRetrievalQuery) }.getOrDefault(emptyList()) else emptyList()
        if (hindiViews.isNotEmpty()) Log.i(TAG, "KB_HINDI_VIEWS ${hindiViews.joinToString(" | ").take(160)}")
        if (rewriter != null && GenericKbQuestionRewrite.worthRewritingForSearch(resolvedRetrievalQuery, unknownWords.size)) {
            val rw = rewrite()
            currentRewrite = rw
            if (rw != null) {
                phase14 = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, (rw.parts + hindiViews).distinct()) }
                    .onFailure { Log.w(TAG, "KB_PHASE14_REWRITE_FAILED ${it.javaClass.simpleName}:${it.message}") }
                    .getOrNull()
            }
        }
        if (phase14 == null) {
            phase14 = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, hindiViews) }
                .onFailure { Log.w(TAG, "KB_PHASE14_FAILED ${it.javaClass.simpleName}:${it.message}") }
                .getOrNull()
            val literalBestScore = phase14?.bestRerank
            if (rewriter != null && currentRewrite == null && phase14 != null && literalBestScore != null && literalBestScore < REWRITE_BELOW_RERANK) {
                val rw = rewrite()
                if (rw != null && !rw.search.equals(resolvedRetrievalQuery, ignoreCase = true)) {
                    val viaRewrite = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, (rw.parts + hindiViews).distinct()) }.getOrNull()
                    val rewriteBest = viaRewrite?.bestRerank
                    val use = viaRewrite != null && viaRewrite.hits.isNotEmpty() && rewriteBest != null && rewriteBest >= literalBestScore - REWRITE_MAX_LOSS
                    Log.i(TAG, "KB_REWRITE_SEARCH literalBest=${"%.2f".format(Locale.US, literalBestScore)} rewriteBest=${rewriteBest?.let { "%.2f".format(Locale.US, it) } ?: "-"} used=$use search=${rw.search.take(120)}")
                    if (use) { phase14 = viaRewrite; currentRewrite = rw }
                }
            }
        }
        // Follow-up resolution, decided by evidence, not by words: the literal question is always
        // searched first. Only when the re-ranker finds nothing convincing for it AND there is a
        // previous question in this KB conversation, the question is searched again together with
        // the previous question (and the start of the previous answer). The contextual result is
        // used only if the re-ranker judges it clearly more relevant. Independent questions keep
        // their own strong literal evidence, so topics cannot leak between turns.
        var followUpOf: String? = null
        val previousUser = acceptedHistory.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        val previousAnswer = acceptedHistory.lastOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
        val literalBest = phase14?.bestRerank
        val basis = lastResolution?.takeIf { it.first == previousUser }?.second ?: previousUser
        val hasPrevious = previousUser.isNotBlank() && !previousUser.equals(query.trim(), ignoreCase = true)
        // The app's own continuation protocol: the previous answer was cut at the output limit and
        // told the user to type "continue" (KbReplyText.MORE_EN / MORE_HI). That reply continues the
        // previous answer from the same evidence; it is never searched on its own words (it matched
        // a slide saying "Click on 'Continue' to proceed").
        val continuation = hasPrevious && KbReplyText.isContinuationReply(query, previousAnswer)
        // Confident first pass: skip the embedding classifier and the contextual second search.
        val skipSecondPass = ArogyaFlags.speedSkipSecondPass && !continuation &&
            phase14 != null && (literalBest ?: 0f) >= SKIP_SECOND_PASS_RERANK
        if (skipSecondPass) Log.i(TAG, "KB_FOLLOW_UP skipped=confident literalBest=${"%.2f".format(Locale.US, literalBest ?: 0f)}")
        if (continuation) {
            val again = runCatching { repository.searchPhase14(basis, queryPlan.clauses, candidateWork, denseEnabled) }.getOrNull()
            if (again != null && again.hits.isNotEmpty()) {
                phase14 = again
                followUpOf = previousUser
            }
            Log.i(TAG, "KB_FOLLOW_UP continuation=true used=${followUpOf != null} previous=${previousUser.take(80)}")
        } else if (phase14 != null && literalBest != null && hasPrevious && !skipSecondPass) {
            // Follow-up resolution, decided by evidence: (a) the literal question finds nothing
            // convincing, or (b) the question reads as dependent on the conversation (embedding
            // classifier; a complete question that names its own subject never is). The question is
            // then searched again together with the previous one; the contextual result is used when
            // the re-ranker judges it more relevant (by a margin for (a); at least as relevant for (b)).
            // A weak literal match alone is not a follow-up. Searching it together with the previous
            // question and answer scores the previous topic's passages against a question that
            // contains the previous answer's own words, so they always "won": after a few turns a
            // new question the pack covers only weakly was answered about the earlier topic. The
            // contextual search runs only for a question that reads as dependent on the conversation
            // (embedding classifier) or is too short to stand alone ("and for children?").
            val weak = literalBest < FOLLOW_UP_WEAK_RERANK
            val shortElliptical = query.trim().split(Regex("\\s+")).size <= FOLLOW_UP_SHORT_WORDS
            val dependent = runCatching { isContextDependent(query, acceptedHistory) }.getOrDefault(false)
            if (dependent || (weak && shortElliptical)) {
                // Ask SOP conversation: chained follow-ups ("and for a newborn?", "what dose?") are
                // rewritten from the last 2 exchanges, not just the last question (behind the flag).
                val prevPrev = if (ArogyaFlags.sopFollowUpContext)
                    acceptedHistory.filter { it.role == "user" }.map { it.content.trim() }
                        .filter { it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true) }
                        .takeLast(2).firstOrNull().orEmpty() else ""
                val contextual = listOf(query.trim(), basis, prevPrev, previousAnswer.take(FOLLOW_UP_ANSWER_CHARS))
                    .filter { it.isNotBlank() }.joinToString(" ")
                val second = runCatching { repository.searchPhase14(contextual, queryPlan.clauses, candidateWork, denseEnabled) }.getOrNull()
                val secondBest = second?.bestRerank
                val margin = if (weak) FOLLOW_UP_MARGIN else 0f
                val use = second != null && second.hits.isNotEmpty() && secondBest != null &&
                    secondBest >= literalBest + margin
                Log.i(
                    TAG,
                    "KB_FOLLOW_UP weak=$weak short=$shortElliptical dependent=$dependent literalBest=${"%.2f".format(Locale.US, literalBest)} contextualBest=" +
                        "${secondBest?.let { "%.2f".format(Locale.US, it) } ?: "-"} used=$use previous=${previousUser.take(80)}"
                )
                if (use) {
                    phase14 = second
                    followUpOf = previousUser
                }
            }
        }
        lastResolution = query.trim() to (followUpOf?.let { "${lastResolution?.takeIf { r -> r.first == it }?.second ?: it} ${query.trim()}".take(400) } ?: query.trim())
        if (phase14 != null) {
            val tEv = System.currentTimeMillis()
            val found = phase14
            return phase14Attachment(query, queryPlan, found, evidenceCharBudget, started, followUpOf).also {
                Log.i(TAG, "POCKET_RAG_EVIDENCE_MS total=${System.currentTimeMillis() - tEv} hits=${found.hits.size}")
            }
        }

        // The Phase 1.4 search failed with an error. Retry once in its full-text-only mode (same
        // generic engine, no semantic lane). There is no older pattern-based fallback path: if this
        // also fails, the turn has no KB evidence and the answer says so.
        val lexicalOnly = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, false) }
            .onFailure { Log.w(TAG, "KB_PHASE14_LEXICAL_RETRY_FAILED ${it.javaClass.simpleName}:${it.message}") }
            .getOrNull()
        if (lexicalOnly != null) {
            Log.w(TAG, "KB_PHASE14_LEXICAL_RETRY used=true")
            return phase14Attachment(query, queryPlan, lexicalOnly, evidenceCharBudget, started, null)
        }
        lookupState = LookupState(attempted = true, ready = true, confident = false)
        Log.w(TAG, "KB_PHASE14_NO_RESULT query=${query.take(100)}")
        return null
    }

    /**
     * Phase 1.4 evidence packet: coverage-reserved order from the repository (one block per
     * covered question part first), then remaining evidence by final rank within the budget.
     * Reserved blocks are shortened rather than dropped when the budget is tight.
     */
    private suspend fun phase14Attachment(
        query: String,
        queryPlan: GenericKbQueryPlan,
        result: GenericKnowledgePackRepository.Phase14Result,
        evidenceCharBudget: Int,
        started: Long,
        followUpOf: String? = null
    ): BaselineContextAttachment? {
        lookupState = LookupState(
            attempted = true,
            ready = true,
            confident = result.hits.isNotEmpty(),
            topSimilarity = result.topSimilarity,
            packId = result.packId,
            displayName = result.displayName
        )
        if (result.hits.isEmpty()) {
            Log.i(TAG, "KB_PHASE14_NO_EVIDENCE query=${query.take(100)}")
            return null
        }
        var valueConflict = ""
        // R10-lite companion: extra 1600-char windows (passages past the narrow set) for the
        // cloud packet, cut from the same search (no second search). Empty unless passage mode.
        var wideExtraText = ""
        var wideExtraRefs = ""
        val packed = mutableListOf<GenericKnowledgePackRepository.Hit>()
        val sourceRefs = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        var used = 0
        // Part C2: gold pinning. A strong/medium verified-answer match pins its guide
        // passages at the top of the packet; a strong match also adds reference notes
        // (kb_gold_reference). Trailing normal blocks are dropped so the prompt stays
        // within today's size. The answer is still generated live from guide passages.
        val goldScored = if (ArogyaVerifiedAnswers.count() == 0) null else runCatching {
            val vec = repository.embedForGold(ArogyaVerifiedAnswers.QUERY_PREFIX + query)
            ArogyaVerifiedAnswers.match(query, queryVec = vec)
        }.getOrNull()
        val goldPin = if (goldScored != null && (goldScored.strong || goldScored.medium))
            runCatching { repository.goldPassages(goldScored.item.sources) }.getOrDefault(emptyList())
        else emptyList()
        val goldRef = if (goldScored != null && goldScored.strong && goldPin.isNotEmpty())
            ArogyaGoldPin.referenceBlock(goldScored.item.aEn) else ""
        val pinnedChunkIds = goldPin.map { it.chunkId }.toSet()
        goldPin.forEach { g ->
            val sourceId = "S${packed.size + 1}"
            val ref = ArogyaGoldPin.sourceRef(sourceId, g.title, g.locator, g.url)
            val body = "\u2022 ${g.text}"
            packed += GenericKnowledgePackRepository.Hit(
                chunkId = g.chunkId, originalChunkId = null, docId = g.docId,
                sourceName = null, pageNumber = null, locator = g.locator,
                headingContext = null, unitKind = null, content = body,
                similarity = 1f, rankScore = 1f, smallFragment = false, sourceTitle = g.title,
                sourceUrl = g.url.ifBlank { null }
            )
            sourceRefs += ref
            val block = "Source ID: $sourceId\nSource: $ref\n$body"
            blocks += block
            used += block.length + 2
        }
        // Normal-block chars before gold trimming (key-facts branch sets the exact figure).
        var goldNormalBefore = 0
        // Key facts: the sentences of the top passages that answer the question (cross-encoder
        // scored). A small answer model reads ~1,300 characters of exactly relevant text instead of
        // several full pages. Falls back to whole passages when no fact could be selected.
        val keyFacts = selectKeyFacts(query, queryPlan, result, evidenceCharBudget)
        // Relevance of the turn: the best sentence's re-ranker score when sentences were scored by the
        // re-ranker (more precise than the best whole passage: a passage about "passport numbers of
        // the buyer's representatives" scored 1.8 for "how do I apply for a passport", its best
        // sentence 0.7); otherwise the best passage.
        val relevance = if (keyFactsScoredByModel && keyFacts.isNotEmpty())
            minOf(keyFacts.maxOf { it.score }, result.bestRerank ?: Float.MAX_VALUE) else result.bestRerank
        val calculation = currentRewrite?.calc?.let { calc ->
            KbCalculator.calculate(calc, keyFacts.map { it.text })
        }
        if (keyFacts.isNotEmpty()) {
            val grouped = GenericKbKeyFacts.byPassage(keyFacts)
            // Evidence form follows the model's reading room, not a setting: with room for ~3K characters
            // (Gemma 3n / Gemma 4 E2B on a 4K context) the model reads the best passages whole, PocketSage
            // style (each cut to ~900 characters around its best sentence, plus any best sentence outside
            // them); with less room it reads only the key sentences.
            val windows = if (evidenceCharBudget >= PASSAGE_MODE_MIN_CHARS)
                (if (widePassages) GenericKbKeyFacts.passageWindows(result.hits.take(keyFactPassageCount(queryPlan)).map { it.content }, keyFacts,
                    count = CHECK_PASSAGES, maxChars = ONLINE_WINDOW_CHARS)
                else GenericKbKeyFacts.passageWindows(result.hits.take(keyFactPassageCount(queryPlan)).map { it.content }, keyFacts))
            else null
            // Passages in the order of their best fact; the calculation goes with the first fact that states a percentage.
            val passageOrder = windows?.map { it.first } ?: keyFacts.map { it.passage }.distinct()
            val windowText = windows?.toMap().orEmpty()
            val calcPassage = keyFacts.firstOrNull { KbCalculator.percentages(it.text).isNotEmpty() }?.passage ?: passageOrder.first()
            // Part C2: normal blocks pack into a temp list first; pinned duplicates are
            // skipped (they already sit at the top) and trailing blocks drop to fit.
            val normalBlocks = ArrayList<String>()
            val normalRefs = ArrayList<String>()
            val normalHits = ArrayList<GenericKnowledgePackRepository.Hit>()
            val passageByNormal = ArrayList<Int>()
            val sidByPassage = HashMap<Int, String>()
            passageOrder.forEach { p ->
                val hit = result.hits[p]
                if (hit.chunkId in pinnedChunkIds) return@forEach
                val sourceId = "S${packed.size + normalBlocks.size + 1}"
                sidByPassage[p] = sourceId
                passageByNormal += p
                val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
                val sourceRef = buildString {
                    append(sourceId).append(" :: ").append(label.replace('\n', ' ').take(500))
                    hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
                }
                val body = buildString {
                    val whole = windowText[p]
                    if (whole != null) append("\u2022 ").appendLine(whole)
                    else grouped[p].orEmpty().forEach { append("\u2022 ").appendLine(it.text) }
                    if (calculation != null && p == calcPassage) appendLine(calculation.describe())
                }.trim()
                val block = "Source ID: $sourceId\nSource: $sourceRef\n$body"
                normalHits += hit.copy(content = body)
                normalRefs += sourceRef
                normalBlocks += block
            }
            goldNormalBefore = normalBlocks.sumOf { it.length + 2 }
            val keptNormals = ArogyaGoldPin.trimNormal(used, normalBlocks, goldNormalBefore)
            repeat(keptNormals.size) { i ->
                packed += normalHits[i]
                sourceRefs += normalRefs[i]
                blocks += normalBlocks[i]
                used += normalBlocks[i].length + 2
            }
            // Documents that state different values of the same kind: the answer must give each with
            // its source instead of copying the first one. S-ids follow the pinned layout
            // (a skipped duplicate already sits at the top under its pinned id); statements
            // from trimmed passages are dropped so every cited S-id has its block.
            val keptPassages = passageByNormal.take(keptNormals.size).toSet()
            val sidForConflict: (Int) -> String = { p ->
                sidByPassage[p] ?: run {
                    val gi = goldPin.indexOfFirst { it.chunkId == result.hits[p].chunkId }
                    if (gi >= 0) "S${gi + 1}" else "S?"
                }
            }
            valueConflict = GenericKbKeyFacts.conflict(keyFacts, { p -> result.hits[p].sourceName ?: result.hits[p].docId })
                ?.let { c -> c.statements
                    .filter { (p, _) -> p in keptPassages || result.hits[p].chunkId in pinnedChunkIds }
                    .joinToString("; ") { (p, v) -> "${sidForConflict(p)}: $v" } }.orEmpty()
            if (ArogyaFlags.r10WideCloud && windows != null) {
                runCatching {
                    val narrowSet = windows.map { it.first }.toSet()
                    val wideWindows = GenericKbKeyFacts.passageWindows(
                        result.hits.take((ONLINE_PASSAGES + 2).coerceAtMost(result.hits.size)).map { it.content },
                        keyFacts, count = ONLINE_PASSAGES, maxChars = ONLINE_WINDOW_CHARS
                    )
                    val extra = wideWindows.filter { it.first !in narrowSet && result.hits[it.first].chunkId !in pinnedChunkIds }
                    if (extra.isNotEmpty()) {
                        val refs = mutableListOf<String>()
                        val blks = extra.mapIndexed { i, (p, text) ->
                            val hit = result.hits[p]
                            val sid = "S${packed.size + i + 1}"
                            val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
                            val ref = buildString {
                                append(sid).append(" :: ").append(label.replace('\n', ' ').take(500))
                                hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
                            }
                            refs += ref
                            "Source ID: $sid\nSource: $ref\n\u2022 $text"
                        }
                        wideExtraText = blks.joinToString("\n\n")
                        wideExtraRefs = refs.joinToString("\n")
                        Log.i(TAG, "KB_WIDE_EXTRA blocks=${blks.size} chars=${wideExtraText.length}")
                    }
                }.onFailure { Log.w(TAG, "KB_WIDE_EXTRA_FAILED ${it.message}") }
            }
            Log.i(TAG, "KB_KEY_FACTS mode=${if (windows != null) "passages" else "sentences"} facts=${keyFacts.size} passages=${passageOrder.size} " +
                "chars=${blocks.sumOf { it.length }} " +
                "calc=${calculation?.describe()?.take(160) ?: "-"} best=${keyFacts.firstOrNull()?.score?.let { "%.2f".format(Locale.US, it) } ?: "-"}")
        } else result.hits.forEachIndexed { index, hit ->
            if (hit.chunkId in pinnedChunkIds) return@forEachIndexed
            val sourceId = "S${packed.size + 1}"
            val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
            val sourceRef = buildString {
                append(sourceId).append(" :: ").append(label.replace('\n', ' ').take(500))
                hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
            }
            val note = result.notesByChunk[hit.chunkId]
            val header = buildString {
                appendLine("Source ID: $sourceId")
                appendLine("Source: $sourceRef")
                if (!note.isNullOrBlank()) appendLine("Note: $note")
            }
            val remaining = evidenceCharBudget - used - header.length - 2
            val content = hit.content.trim()
            val body = when {
                content.length <= remaining -> content
                index < result.reservedCount && remaining >= 240 -> compactAcrossContent(content, remaining)
                else -> return@forEachIndexed
            }
            val block = (header + body).trim()
            packed += hit.copy(content = body)
            sourceRefs += sourceRef
            blocks += block
            used += block.length + 2
        }
        if (packed.isEmpty()) {
            Log.i(TAG, "KB_PHASE14_NO_PACKED_EVIDENCE query=${query.take(100)} budget=$evidenceCharBudget")
            return null
        }
        lastPacked = packed.toList()
        val packetText = blocks.joinToString("\n\n")
        val clauseCoverage = result.clauseStates.map { state ->
            val sourceIds = packed.mapIndexedNotNull { i, hit ->
                if (state.clauseId in result.clauseIdsByChunk[hit.chunkId].orEmpty()) "S${i + 1}" else null
            }.toSet()
            val finalState = if (sourceIds.isEmpty()) GenericKbCoverageState.UNSUPPORTED else state.state
            GenericKbClauseCoverage(state.clauseId, finalState, sourceIds)
        }
        val coverageText = clauseCoverage.joinToString("\n") { c ->
            "${c.clauseId}|${c.state.name}|${c.sourceIds.sorted().joinToString(",")}"
        }
        pendingEvidenceWindow = packed
        pendingEvidencePackId = result.packId
        pendingEvidenceQuery = query.trim().lowercase(Locale.ROOT)
        Log.i(
            TAG,
            "KB_PHASE14_CONTEXT pack=${result.packId} packed=${packed.size}/${result.hits.size} reserved=${result.reservedCount} " +
                "chars=${packetText.length} budget=$evidenceCharBudget coverage=${clauseCoverage.joinToString(",") { "${it.clauseId}:${it.state}" }} " +
                "sources=${packed.joinToString(",") { it.chunkId.take(10) }} elapsed=${System.currentTimeMillis() - started}ms"
        )
        if (goldPin.isNotEmpty()) {
            val normalChars = blocks.drop(goldPin.size).sumOf { it.length + 2 }
            val before = if (goldNormalBefore > 0) goldNormalBefore else normalChars
            Log.i(TAG, "AROGYA_GOLD pin id=${goldScored?.item?.id} strong=${goldScored?.strong} " +
                "pinned=${goldPin.size} tokens_before=${ArogyaGoldPin.estimateTokens(before)} " +
                "tokens_after=${ArogyaGoldPin.estimateTokens(packetText.length + goldRef.length)}")
        }
        return BaselineContextAttachment(
            sourceId = "knowledge_pack:${result.packId}",
            text = packetText,
            priority = 95,
            attributes = mapOf(
                "grounding_required" to "true",
                "knowledge_pack" to "generic",
                "knowledge_pack_id" to result.packId,
                "knowledge_pack_name" to result.displayName,
                "kb_top_similarity" to String.format(Locale.US, "%.4f", result.topSimilarity),
                "kb_retrieval_mode" to "PHASE14_CENTRED_CALIBRATED_MULTILANE",
                "kb_retrieval_primary" to "literal_first",
                "kb_retrieval_view_count" to queryPlan.clauses.size.toString(),
                "kb_query_intent" to "CLAUSE_AWARE",
                "kb_retrieval_views" to queryPlan.retrievalQueries.joinToString(" || ").take(1_500),
                "kb_evidence_char_budget" to evidenceCharBudget.toString(),
                "kb_resolved_user_query" to query.trim().take(1_800),
                "kb_source_refs" to sourceRefs.joinToString("\n"),
                "kb_wide_extra" to wideExtraText,
                "kb_wide_extra_refs" to wideExtraRefs,
                "kb_gold_reference" to goldRef,
                "kb_primary_source_ref" to sourceRefs.firstOrNull().orEmpty().take(700),
                "kb_record_ids" to packed.joinToString(",") { it.chunkId }.take(1_500),
                "kb_current_record_ids" to packed.joinToString(",") { it.chunkId }.take(1_500),
                "kb_backfill_count" to "0",
                "kb_evidence_packet_chars" to packetText.length.toString(),
                "kb_evidence_complete_packet" to "true",
                "kb_coverage_requested" to queryPlan.clauses.size.toString(),
                "kb_coverage_matched" to clauseCoverage.count { it.state == GenericKbCoverageState.SUPPORTED }.toString(),
                "kb_coverage_complete" to clauseCoverage.all { it.state == GenericKbCoverageState.SUPPORTED }.toString(),
                "kb_clause_coverage" to coverageText,
                "kb_phase14_trace" to result.trace.take(1_500),
                "kb_follow_up_of" to followUpOf.orEmpty().take(600),
                "kb_best_relevance" to (relevance?.let { String.format(Locale.US, "%.3f", it) } ?: ""),
                "kb_key_facts" to keyFacts.size.toString(),
                "kb_value_conflict" to valueConflict,
                // The pack's own long forms of abbreviations in the question ("AON = acceptance of necessity").
                "kb_terms" to runCatching { repository.abbreviationTerms(query) }.getOrDefault(emptyList())
                    .joinToString("; ") { (abbr, long) -> "$abbr = $long" },
                "kb_search_rewrite" to currentRewrite?.search.orEmpty().take(300)
            )
        )
    }

    /** A question asking several things gets two more passages per extra part to choose from. */
    private fun keyFactPassageCount(queryPlan: GenericKbQueryPlan): Int {
        val partCount = maxOf(currentRewrite?.parts?.size ?: 1, queryPlan.clauses.size).coerceIn(1, 3)
        if (widePassages) return CHECK_PASSAGES + 2 * (partCount - 1)
        // R1-lite: six passages compete for key facts when the switch is on, else five.
        val base = if (ArogyaFlags.r1WideEvidence) KEY_FACT_PASSAGES + 1 else KEY_FACT_PASSAGES
        return base + 2 * (partCount - 1)
    }

    /**
     * Key facts for the question; a question with several parts gets its share of the budget per
     * part so no part is crowded out. Empty when the passages could not be split or scored.
     */
    private suspend fun selectKeyFacts(
        query: String,
        queryPlan: GenericKbQueryPlan,
        result: GenericKnowledgePackRepository.Phase14Result,
        evidenceCharBudget: Int
    ): List<GenericKbKeyFacts.Fact> = runCatching {
        keyFactsScoredByModel = false
        val passageCount = keyFactPassageCount(queryPlan)
        val passages = result.hits.take(passageCount).map { it.content }
        val passageScores = result.hits.take(passageCount).map { it.rankScore }
        if (passages.isEmpty()) return@runCatching emptyList()
        // Whole-passage mode: sentences only anchor the passage windows, so they are scored by word
        // overlap (free) instead of the cross-encoder. Phone log: the cross-encoder takes ~0.4 s per
        // passage pair; scoring 50+ sentences as well added several seconds per answer. Desktop:
        // 180 vs 183 of 198 questions with the answer in the evidence.
        val scorer = if (evidenceCharBudget >= PASSAGE_MODE_MIN_CHARS) null else repository.keyFactScorer()
        keyFactsScoredByModel = scorer != null
        val budget = minOf(GenericKbKeyFacts.DEFAULT_BUDGET, evidenceCharBudget)
        val question = result.rerankQuestion.ifBlank { query }
        // Parts: the model's one-search-per-thing split when it made one, else the planner's clauses.
        val parts = currentRewrite?.parts?.takeIf { it.size > 1 }
            ?: queryPlan.clauses.map { it.literal.trim() }.filter { it.isNotBlank() }
        if (parts.size <= 1) {
            GenericKbKeyFacts.select(question, passages, scorer, budget, passageScores = passageScores)
        } else {
            val share = budget / parts.size
            val out = mutableListOf<GenericKbKeyFacts.Fact>()
            parts.forEach { part ->
                val partQuestion = (listOf(part) + repository.aliasExpansions(part)).joinToString(" ")
                GenericKbKeyFacts.select(partQuestion, passages, scorer, share, GenericKbKeyFacts.MAX_FACTS / 2 + 1, passageScores)
                    .forEach { f -> if (out.none { it.passage == f.passage && it.index == f.index }) out += f }
            }
            out
        }
    }.onFailure { Log.w(TAG, "KB_KEY_FACTS_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrDefault(emptyList())

    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        if (provider != AndroBaselineRuntime.Provider.LOCAL &&
            provider != AndroBaselineRuntime.Provider.CLOUD) return BaselineValidationResult.ACCEPT
        val sources = attachments.filter {
            it.attributes["grounding_required"] == "true" &&
                it.attributes["knowledge_pack"] == "generic"
        }
        if (sources.isEmpty()) return BaselineValidationResult.ACCEPT
        val evidence = sources.joinToString("\n\n") { it.text }.trim()
        if (evidence.isBlank()) return BaselineValidationResult.reject("generic_kb_source_missing")

        val clean = answer.trim()
        if (clean.isBlank()) return BaselineValidationResult.reject("generic_kb_blank_answer")

        // Source IDs are app-owned labels attached to the exact evidence packet. A model
        // may cite only IDs that were actually supplied for this turn. This prevents a
        // fluent Local/Cloud answer from fabricating an S-number while keeping the
        // existing semantic grounding validator as the authority for claim support.
        val allowedSourceIds = sources
            .flatMap { it.attributes["kb_source_refs"].orEmpty().lineSequence().toList() }
            .mapNotNull { Regex("""^\s*(S\d+)\s*::""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.uppercase() }
            .toSet()
        val citedSourceIds = Regex("""\[(S\d+)]""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1].uppercase() }
            .toSet()
        val unknownSourceIds = citedSourceIds - allowedSourceIds
        if (unknownSourceIds.isNotEmpty()) {
            return BaselineValidationResult.reject("generic_kb_unknown_source_id:${unknownSourceIds.sorted().joinToString(",")}")
        }

        // Protocol/privacy format guards only. These do not classify the user's topic.
        val privateLeak = Regex(
            "(?i)<source\\b|\\[SELECTED KNOWLEDGE PACK|knowledge_pack_id|kb_record_ids|grounding_required|\\bEvidence\\s*\\d+\\b|\\bretrieved chunk\\b|\\bcontext chunk\\b|\\[Evidence source"
        ).containsMatchIn(clean)
        if (privateLeak) return BaselineValidationResult.reject("generic_kb_private_context_leak")

        // Reject obvious unfilled form/template output regardless of domain.
        if (Regex("_{4,}|\\.{5,}|\\[\\s*blank\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return BaselineValidationResult.reject("generic_kb_unfilled_template_fragment")
        }

        val intent = sources.firstNotNullOfOrNull { source ->
            source.attributes["kb_query_intent"]?.let { runCatching { SemanticQueryIntent.valueOf(it) }.getOrNull() }
        } ?: SemanticQueryIntent.GENERAL

        val coverageIncomplete = sources.any { source ->
            source.attributes["kb_coverage_complete"].equals("false", ignoreCase = true) ||
                source.attributes["kb_evidence_complete_packet"].equals("false", ignoreCase = true)
        }
        if (coverageIncomplete) {
            Log.i(TAG, "GENERIC_KB_COVERAGE_ADVISORY intent=$intent action=continue_local")
        }

        // Keep post-generation validation objective. Procedure quality/completeness
        // is owned by retrieval + Gemma, not by heuristic surface-shape scoring.

        // Grounded exact-value claims must be visible in the evidence packet. This
        // extractor is intentionally domain-neutral: it covers identifiers, dates,
        // currency, percentages, ranges, and numeric values. Leading list
        // ordinals and app-owned [S#] citations are removed before extraction.
        val answerForClaims = clean.lineSequence().joinToString("\n") { line ->
            line.replaceFirst(Regex("""^\s*\d{1,2}[.)]\s+"""), "")
        }.replace(Regex("""\[(?:S\d+)]""", RegexOption.IGNORE_CASE), "")

        fun normalizedExactClaims(text: String): Set<String> {
            val patterns = listOf(
                Regex("""(?i)\b[A-Z]{2,}[A-Z0-9./_-]*\d[A-Z0-9./_-]*\b"""),
                Regex("""\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b"""),
                Regex("""(?i)(?:₹|\$|€|£)\s?\d[\d,]*(?:\.\d+)?"""),
                Regex("""\b\d+(?:[.,]\d+)?%\b"""),
                Regex("""\b\d+(?:[.,]\d+)?(?:\s*[-–]\s*\d+(?:[.,]\d+)?)?\b"""),
                Regex("""\b\d+(?:\.\d+){1,5}\b"""),
                Regex("""\b(?:19|20)\d{2}\b""")
            )
            return patterns.flatMap { regex -> regex.findAll(text).map { it.value }.toList() }
                .map { it.lowercase(Locale.US).replace(Regex("""\s+"""), "") }
                .filter { it.isNotBlank() }
                .toSet()
        }
        val exactClaims = normalizedExactClaims(answerForClaims)
        val evidenceNormalized = evidence.lowercase(Locale.US).replace(Regex("""\s+"""), "")
        val queryNormalized = query.lowercase(Locale.US).replace(Regex("""\s+"""), "")
        val unsupportedExact = exactClaims.filter { claim ->
            claim.isNotBlank() && !evidenceNormalized.contains(claim) && !queryNormalized.contains(claim)
        }
        // A calculation may introduce a derived result that is absent from the source and
        // the user's input, but only the RIGHT-HAND result of an explicit equation gets
        // that exception. Every exact numeric/identifier operand on the left-hand side
        // must already be grounded in KB evidence or supplied by the user. This prevents
        // an invented procurement rate from being smuggled through merely by adding '='.
        val derivedClaims = answerForClaims.lineSequence().flatMap { line ->
            val eq = line.indexOf('=')
            if (eq <= 0 || eq >= line.lastIndex) return@flatMap emptySequence<String>()
            val lhs = line.substring(0, eq)
            val rhs = line.substring(eq + 1)
            val lhsUnsupported = normalizedExactClaims(lhs).filter { claim ->
                !evidenceNormalized.contains(claim) && !queryNormalized.contains(claim)
            }
            if (lhsUnsupported.isNotEmpty()) emptySequence()
            else normalizedExactClaims(rhs).asSequence()
        }.toSet()
        val unsupportedNonDerived = unsupportedExact.filterNot { it in derivedClaims }
        if (unsupportedNonDerived.isNotEmpty()) {
            return BaselineValidationResult.reject("generic_kb_unsupported_exact_claim:${unsupportedNonDerived.first()}")
        }

        // V14 simple-local-RAG policy: retrieval owns relevance; the local model owns
        // synthesis. Post-generation validation is limited to objective integrity checks
        // above. Embedding similarity is telemetry, never a binary answer-quality gate.
        if (pendingEvidenceWindow.isNotEmpty()) {
            acceptedEvidenceWindow = pendingEvidenceWindow
            acceptedEvidencePackId = pendingEvidencePackId
            Log.i(TAG, "GENERIC_KB_ACCEPTED_SOURCE_WINDOW pack=${acceptedEvidencePackId.take(24)} query=${pendingEvidenceQuery.take(80)} chunks=${acceptedEvidenceWindow.size}")
        }
        Log.i(TAG, "GENERIC_KB_SIMPLE_ACCEPT provider=$provider chars=${clean.length}")
        return BaselineValidationResult.ACCEPT
    }
}
