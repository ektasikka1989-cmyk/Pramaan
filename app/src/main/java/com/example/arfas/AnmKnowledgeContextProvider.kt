package com.example.arfas

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


data class KbQueryPlan(
    val relation: String = "UNCERTAIN",
    val caseQuery: String = "",
    val focusQuery: String = "",
    val factsUsed: List<String> = emptyList(),
    val goalChange: Boolean = false,
    val newGoal: String = "",
    val rejectedTheoryIds: List<String> = emptyList()
) {
    fun normalizedRelation(): String = relation.trim().uppercase().let { value ->
        if (value in setOf("FOLLOW_UP", "CASE_RELATED", "UNCERTAIN")) value else "UNCERTAIN"
    }
}


data class KbRecordDecision(
    val recordId: Int,
    val verdict: String,
    val rationale: String = "",
    val missingFact: String = ""
) {
    fun normalizedVerdict(): String = verdict.trim().uppercase().let { value ->
        if (value in setOf("APPLICABLE", "CONFLICT", "NEEDS_INFO")) value else "CONFLICT"
    }
}

data class KbRecordSelection(
    val selectedRecordIds: List<Int> = emptyList(),
    val confidence: String = "LOW",
    val clarificationQuestion: String = "",
    val decisions: List<KbRecordDecision> = emptyList(),
    val caseFactEvidence: List<String> = emptyList()
)

/**
 * Selected knowledge-pack retrieval core.
 *
 * Retrieval is based on the selected database's E5 embeddings plus bounded
 * conversation context. User selection authorizes the search; no topic/domain
 * keyword router exists in this class.
 */
class AdaptiveKnowledgeContextProvider(
    private val ensureReady: suspend () -> Boolean,
    private val db: ANMDatabaseHelper,
    private val semanticEngine: SemanticSearchEngine
) : BaselineContextProvider, BaselineResponseValidator {

    private val validationPrewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val validationEmbeddingCache = object : LinkedHashMap<String, FloatArray>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 96
    }
    private val validationEmbeddingCacheLock = Any()
    @Volatile private var responseScopeClassifier: (suspend (String, List<AndroChatMessage>) -> SemanticResponseScope)? = null

    fun configureResponseScopeClassifier(
        classifier: suspend (String, List<AndroChatMessage>) -> SemanticResponseScope
    ) {
        responseScopeClassifier = classifier
    }

    private suspend fun cachedValidationEmbedding(input: String): FloatArray? {
        synchronized(validationEmbeddingCacheLock) {
            validationEmbeddingCache[input]?.let { return it }
        }
        val embedded = semanticEngine.embed(input) ?: return null
        synchronized(validationEmbeddingCacheLock) {
            validationEmbeddingCache[input] = embedded
        }
        return embedded
    }

    private fun scheduleValidationPrewarm(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        evidenceText: String
    ) {
        if (evidenceText.isBlank()) return
        val queryInput = "query: ${query.trim()}"
        val contextInput = "query: ${recentConversationContext(acceptedHistory, query, 6)}"
        val evidenceInputs = splitGroundingEvidence(evidenceText)
            .take(18)
            .map { "passage: ${it.take(1_050)}" }

        validationPrewarmScope.launch {
            val started = System.currentTimeMillis()
            var warmed = 0
            listOf(queryInput, contextInput).plus(evidenceInputs).forEach { input ->
                val alreadyCached = synchronized(validationEmbeddingCacheLock) {
                    validationEmbeddingCache.containsKey(input)
                }
                if (!alreadyCached && cachedValidationEmbedding(input) != null) warmed++
            }
            Log.i(
                TAG,
                "KB_VALIDATION_PREWARM_DONE warmed=$warmed inputs=${evidenceInputs.size + 2} ms=${System.currentTimeMillis() - started}"
            )
        }
    }

    companion object {
        private const val TAG = "AdaptiveKnowledge"
        private const val MIN_EVIDENCE_SCORE = 0.835f
        private const val MAX_CANDIDATES = 10
        private const val MAX_EVIDENCE_BLOCKS = 4
        private const val MAX_EVIDENCE_CHARS = 4_800

        // Numeric quality gates only. No domain/topic keyword lists are used.
        private const val MIN_ANSWER_RELEVANCE = 0.61f
        private const val MIN_SHORT_RESPONSE_RELEVANCE = 0.50f
        private const val MIN_GROUNDING_SIMILARITY = 0.68f
        private const val MIN_GROUNDING_LEXICAL_COVERAGE = 0.42f
        private const val STRONG_GROUNDING_SIMILARITY = 0.84f
    }

    private fun recentConversationContext(
        acceptedHistory: List<AndroChatMessage>,
        query: String,
        maxMessages: Int = 6
    ): String = buildString {
        acceptedHistory.takeLast(maxMessages).forEach { message ->
            append(message.role).append(": ").append(message.content.trim()).append('\n')
        }
        append("user: ").append(query.trim())
    }.trim()

    suspend fun isOperational(): Boolean = semanticEngine.isReady

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (query.isBlank()) return false
        return ensureReady()
    }

    /**
     * Selected-KB validation is deliberately data-driven:
     *  1. answer must remain semantically related to the live user turn/context;
     *  2. every substantive declarative claim must be semantically supported by
     *     one of the exact evidence blocks supplied to the generator.
     *
     * There are no hand-authored topic keyword lists or response prototypes
     * in this path.
     */
    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        val cleanQuery = query.trim()
        val cleanAnswer = FixSightOutputSanitizer.sanitize(answer).trim()
        if (cleanQuery.isBlank() || cleanAnswer.isBlank()) {
            return BaselineValidationResult.reject("blank_query_or_answer")
        }

        val groundingRequired = attachments.any {
            it.attributes["grounding_required"] == "true"
        }
        if (!groundingRequired) {
            return BaselineValidationResult.ACCEPT
        }

        // Selected-KB answers must never expose private runtime instructions. This is
        // deliberately scoped to grounded turns and matches only control/framing text
        // that originates inside FixSight, not user medical vocabulary.
        val normalizedAnswer = cleanAnswer.lowercase()
        val privateControlMarkers = listOf(
            "active investigation notebook",
            "verified reference evidence",
            "private case memory",
            "private working context",
            "use confirmed user facts and verified visual observations as case facts",
            "treat hypotheses, prior suggestions, checks and prior assistant wording as context only",
            "for this detached selected-kb turn",
            "sole factual authority for medical/sop content"
        )
        if (privateControlMarkers.any { normalizedAnswer.contains(it) }) {
            Log.w(TAG, "ANSWER_PRIVATE_CONTEXT_LEAK provider=$provider accepted=false")
            return BaselineValidationResult.reject("private_context_leak")
        }

        if (!semanticEngine.isReady) {
            return BaselineValidationResult.reject("semantic_unavailable_grounding_required")
        }

        val selectionConfidence = attachments
            .filter { it.attributes["grounding_required"] == "true" }
            .mapNotNull { it.attributes["kb_selection_confidence"] }
            .firstOrNull()
            .orEmpty()
            .trim()
            .uppercase()
        if (selectionConfidence.isNotBlank() &&
            selectionConfidence != "HIGH" &&
            selectionConfidence != "MEDIUM") {
            Log.w(
                TAG,
                "ANSWER_SELECTION_GATE provider=$provider confidence=$selectionConfidence accepted=false"
            )
            return BaselineValidationResult.reject("kb_selection_not_confident")
        }

        // Preserve spoken-field discipline without imposing one arbitrary ceiling on
        // every grounded turn. Reuse the semantic response-scope classifier already
        // used by the main runtime: concise requests remain compact, while an explicit
        // detailed request is not forced to Cloud simply because a correct source-bound
        // Local answer is longer. Grounding/relevance gates below remain authoritative.
        val responseScope = try {
            responseScopeClassifier?.invoke(cleanQuery, acceptedHistory) ?: SemanticResponseScope.NORMAL
        } catch (t: Throwable) {
            Log.w(TAG, "ANSWER_RESPONSE_SCOPE_FAILED ${t.javaClass.simpleName}:${t.message}")
            SemanticResponseScope.NORMAL
        }
        val maxOperationalChars = when (responseScope) {
            SemanticResponseScope.SHORT -> 1_200
            SemanticResponseScope.NORMAL -> 2_500
            SemanticResponseScope.DETAILED -> 5_000
        }
        if (cleanAnswer.length > maxOperationalChars) {
            Log.w(
                TAG,
                "ANSWER_OPERATIONAL_LENGTH provider=$provider chars=${cleanAnswer.length} " +
                    "scope=$responseScope max=$maxOperationalChars accepted=false"
            )
            return BaselineValidationResult.reject("grounded_answer_not_operational_for_scope")
        }

        val currentQueryEmbedding = cachedValidationEmbedding("query: $cleanQuery")
            ?: return BaselineValidationResult.reject("query_embedding_failed")
        val conversationContext = recentConversationContext(acceptedHistory, cleanQuery, 6)
        val contextEmbedding = cachedValidationEmbedding("query: $conversationContext")
            ?: currentQueryEmbedding
        val answerEmbedding = semanticEngine.embed("passage: $cleanAnswer")
            ?: return BaselineValidationResult.reject("answer_embedding_failed")

        val currentRelevance =
            semanticEngine.cosineSimilarity(currentQueryEmbedding, answerEmbedding)
        val contextualRelevance =
            semanticEngine.cosineSimilarity(contextEmbedding, answerEmbedding)
        val relevance = maxOf(currentRelevance, contextualRelevance)

        // Structural first-turn guard only; it contains no topic vocabulary.
        val queryWords = semanticTokens(cleanQuery)
        val underSpecifiedFirstTurn =
            acceptedHistory.isEmpty() && queryWords.size <= 2
        if (underSpecifiedFirstTurn) {
            val clarificationShape =
                cleanAnswer.contains('?') && cleanAnswer.length <= 220
            if (!clarificationShape) {
                Log.w(
                    TAG,
                    "ANSWER_UNDERSPECIFIED_REJECT provider=$provider " +
                        "queryWords=${queryWords.size} answerChars=${cleanAnswer.length}"
                )
                return BaselineValidationResult.reject(
                    "underspecified_first_turn_overreach"
                )
            }
        }

        val minimumRelevance =
            if (cleanAnswer.length <= 40) {
                MIN_SHORT_RESPONSE_RELEVANCE
            } else {
                MIN_ANSWER_RELEVANCE
            }
        if (relevance < minimumRelevance) {
            Log.w(
                TAG,
                "ANSWER_SEMANTIC provider=$provider " +
                    "current=${"%.4f".format(currentRelevance)} " +
                    "context=${"%.4f".format(contextualRelevance)} accepted=false"
            )
            return BaselineValidationResult.reject("low_contextual_adequacy")
        }

        val chunks = attachments
            .filter { it.attributes["grounding_required"] == "true" }
            .sortedByDescending { it.priority }
            .flatMap { attachment -> splitGroundingEvidence(attachment.text) }
            .take(18)
        if (chunks.isEmpty()) {
            Log.w(TAG, "ANSWER_GROUNDING provider=$provider chunks=0 accepted=false")
            return BaselineValidationResult.reject("insufficient_grounding")
        }

        val chunkEmbeddings = chunks.mapNotNull { chunk ->
            cachedValidationEmbedding("passage: ${chunk.take(1_050)}")
                ?.let { embedding -> chunk to embedding }
        }
        if (chunkEmbeddings.isEmpty()) {
            return BaselineValidationResult.reject("grounding_embedding_failed")
        }

        val claims = extractGroundingClaims(cleanAnswer)
        var minimumClaimScore = 1f
        var checkedClaims = 0

        for (claim in claims) {
            val claimEmbedding =
                semanticEngine.embed("passage: ${claim.take(700)}")
                    ?: return BaselineValidationResult.reject("claim_embedding_failed")

            var bestSemantic = 0f
            var bestLexical = 0f
            var supported = false
            for ((evidenceChunk, evidenceEmbedding) in chunkEmbeddings) {
                val semantic = semanticEngine.cosineSimilarity(
                    evidenceEmbedding,
                    claimEmbedding
                )
                val lexical = groundingLexicalCoverage(claim, evidenceChunk)
                if (semantic > bestSemantic ||
                    (semantic == bestSemantic && lexical > bestLexical)) {
                    bestSemantic = semantic
                    bestLexical = lexical
                }
                if (semantic >= MIN_GROUNDING_SIMILARITY &&
                    (lexical >= MIN_GROUNDING_LEXICAL_COVERAGE ||
                        semantic >= STRONG_GROUNDING_SIMILARITY)) {
                    supported = true
                }
            }

            minimumClaimScore = minOf(minimumClaimScore, bestSemantic)
            checkedClaims++
            if (!supported) {
                Log.w(
                    TAG,
                    "ANSWER_GROUNDING_CLAIM provider=$provider " +
                        "semantic=${"%.4f".format(bestSemantic)} " +
                        "lexical=${"%.4f".format(bestLexical)} accepted=false " +
                        "claimChars=${claim.length}"
                )
                return BaselineValidationResult.reject(
                    "unsupported_grounded_claim"
                )
            }
        }

        if (checkedClaims == 0) {
            val clarificationShape = cleanAnswer.length <= 420 &&
                (cleanAnswer.contains('?') || cleanAnswer.contains('？'))
            if (!clarificationShape) {
                Log.w(TAG, "ANSWER_GROUNDING provider=$provider claims=0 accepted=false reason=no_checkable_claim_or_clarification")
                return BaselineValidationResult.reject("no_checkable_grounded_claim")
            }
            Log.i(
                TAG,
                "ANSWER_GROUNDING provider=$provider " +
                    "claims=0 chunks=${chunks.size} clarification=true accepted=true"
            )
        } else {
            Log.i(
                TAG,
                "ANSWER_GROUNDING provider=$provider claims=$checkedClaims " +
                    "chunks=${chunks.size} minScore=${"%.4f".format(minimumClaimScore)} " +
                    "accepted=true"
            )
        }

        Log.i(
            TAG,
            "ANSWER_SEMANTIC provider=$provider " +
                "current=${"%.4f".format(currentRelevance)} " +
                "context=${"%.4f".format(contextualRelevance)} accepted=true"
        )
        return BaselineValidationResult.ACCEPT
    }

    private fun splitGroundingEvidence(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // Remove private runtime framing before applying the user-visible sanitizer.
        val unframed = text
            .replace(Regex("(?m)^\\s*\\[Evidence source [^]]+]\\s*$"), "")
            .replace(Regex("(?m)^\\s*\\[REFERENCE EVIDENCE]\\s*$"), "")
            .replace(Regex("(?m)^\\s*\\[END REFERENCE EVIDENCE]\\s*$"), "")
            .trim()
        if (unframed.isBlank()) return emptyList()

        // SOP records are rendered as labelled source lines (IDENTIFY/ASSESS/
        // REFER/etc.). Keeping each line local prevents a whole-record embedding
        // from blessing unrelated but medically-similar claims.
        return unframed.lineSequence()
            .map { FixSightOutputSanitizer.sanitize(it).trim() }
            .filter { it.isNotBlank() }
            .flatMap { line ->
                if (line.length <= 520) {
                    sequenceOf(line)
                } else {
                    Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
                        .findAll(line)
                        .map { it.value.trim() }
                        .filter { it.isNotBlank() }
                }
            }
            .map { it.take(520) }
            .distinct()
            .take(18)
            .toList()
    }

    private fun groundingLexicalCoverage(claim: String, evidence: String): Float {
        val stop = setOf(
            "a", "an", "the", "and", "or", "but", "if", "then", "than", "to", "of", "in", "on", "at",
            "for", "from", "with", "without", "by", "as", "is", "are", "was", "were", "be", "been", "being",
            "this", "that", "these", "those", "it", "its", "they", "them", "their", "you", "your", "we", "our",
            "can", "could", "should", "would", "may", "might", "must", "do", "does", "did", "have", "has", "had"
        )
        val claimTokens = semanticTokens(claim).filter { it.length >= 2 && it !in stop }.toSet()
        if (claimTokens.isEmpty()) return 1f
        val evidenceTokens = semanticTokens(evidence).filter { it.length >= 2 && it !in stop }.toSet()
        if (evidenceTokens.isEmpty()) return 0f
        return claimTokens.count { it in evidenceTokens }.toFloat() / claimTokens.size.toFloat()
    }

    /**
     * Claim extraction uses punctuation/shape only. No phrase allowlist,
     * diagnostic vocabulary, or static "safe sentence" list exists here.
     */
    private fun extractGroundingClaims(answer: String): List<String> {
        val units = buildList {
            answer.lineSequence().forEach { line ->
                val cleanLine =
                    line.trim()
                        .replace(Regex("^[•*\\-\\d.)\\s]+"), "")
                        .trim()
                if (cleanLine.isBlank()) return@forEach

                val split = Regex("[^.!?।！？]+(?:[.!?।！？]+|$)")
                    .findAll(cleanLine)
                    .map { it.value.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                if (split.isEmpty()) add(cleanLine) else addAll(split)
            }
        }

        return units
            .map { it.trim() }
            .filterNot { it.endsWith("?") || it.endsWith("？") }
            .filter { semanticTokens(it).size >= 3 }
            .distinctBy {
                it.lowercase()
                    .replace(Regex("\\s+"), " ")
                    .take(120)
            }
            .take(12)
    }

    private fun semanticTokens(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

    /**
     * The current user turn is always query view #1. If accepted user history
     * exists, a second view is built from that history. This removes pronoun,
     * symptom, disease, and follow-up phrase tables entirely.
     */
    private fun semanticRetrievalQueries(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String = ""
    ): List<String> {
        val current = query.trim().take(500)
        if (current.isBlank()) return emptyList()
        val visual = visualObservation.trim().take(650)

        val primary = buildString {
            append(current)
            if (visual.isNotBlank()) {
                append(". Visible observation: ").append(visual)
            }
        }.take(1_200)

        val recentUsers = acceptedHistory.asReversed()
            .filter { it.role == "user" && it.content.isNotBlank() }
            .take(4)
            .map { it.content.trim().take(320) }
            .reversed()

        if (recentUsers.isEmpty()) {
            return listOf(primary)
        }

        val contextual = buildString {
            recentUsers.forEach { prior ->
                if (isNotEmpty()) append(". ")
                append(prior)
            }
            if (isNotEmpty()) append(". ")
            append(current)
            if (visual.isNotBlank()) {
                append(". ").append(visual)
            }
        }.trim().take(1_600)

        return listOf(primary, contextual)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizedEvidenceKey(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]+"), "")
            .trim()
            .take(1_200)

    private fun sameEvidenceNeighborhood(
        a: SopRecord,
        b: SopRecord
    ): Boolean {
        if (
            a.fileName.isBlank() ||
            b.fileName.isBlank() ||
            a.fileName != b.fileName
        ) return false

        val ai = a.id.toLongOrNull() ?: return false
        val bi = b.id.toLongOrNull() ?: return false
        return kotlin.math.abs(ai - bi) <= 2
    }

    private suspend fun retrieveAttachment(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String = "",
        preparedQueries: List<String>? = null
    ): BaselineContextAttachment? {
        if (!ensureReady()) return null

        val queries =
            (preparedQueries
                ?: semanticRetrievalQueries(
                    query,
                    acceptedHistory,
                    visualObservation
                ))
                .map { it.trim().take(1_600) }
                .filter { it.isNotBlank() }
                .distinct()

        if (queries.isEmpty()) return null

        // Dynamic lexical reranking uses only the current user text.
        val rawMatches = db.searchSopsAdaptiveMulti(
            queries = queries,
            maxResults = MAX_CANDIDATES,
            minSemanticScore = MIN_EVIDENCE_SCORE
        )
        if (rawMatches.isEmpty()) {
            Log.d(
                TAG,
                "ADAPTIVE_KB none topSemantic=${db.lastTopSimilarity} " +
                    "views=${queries.size}"
            )
            return null
        }

        val exactSeen = HashSet<String>()
        val deduplicated =
            ArrayList<ANMDatabaseHelper.AdaptiveSopMatch>()

        // Preserve the rank returned by the data-driven retriever.
        for (match in rawMatches) {
            val exactKey = normalizedEvidenceKey(match.record.sopText)
            if (
                exactKey.isNotBlank() &&
                !exactSeen.add(exactKey)
            ) continue

            if (
                deduplicated.any {
                    sameEvidenceNeighborhood(it.record, match.record)
                }
            ) continue

            deduplicated += match
        }

        val selected =
            ArrayList<ANMDatabaseHelper.AdaptiveSopMatch>()
        val seenSources = HashSet<String>()

        for (match in deduplicated) {
            val sourceKey =
                match.record.fileName.ifBlank {
                    match.record.sourceInfo
                }

            if (
                sourceKey.isNotBlank() &&
                seenSources.add(sourceKey)
            ) {
                selected += match
                if (
                    selected.size >=
                    minOf(3, MAX_EVIDENCE_BLOCKS)
                ) break
            }
        }

        if (selected.size < MAX_EVIDENCE_BLOCKS) {
            for (match in deduplicated) {
                if (
                    selected.any {
                        it.record.id == match.record.id
                    }
                ) continue

                selected += match
                if (selected.size >= MAX_EVIDENCE_BLOCKS) break
            }
        }

        if (selected.isEmpty()) return null

        val blocks =
            ArrayList<
                Pair<ANMDatabaseHelper.AdaptiveSopMatch, SopRecord>
            >()
        val expandedSeen = HashSet<String>()

        for (match in selected) {
            val expanded =
                db.expandAdjacentChunks(
                    match.record,
                    radius = 1,
                    maxChars = 1_800
                )
            val cleaned = sanitizeEvidence(expanded.sopText)
            val key = normalizedEvidenceKey(cleaned)

            if (
                cleaned.isBlank() ||
                (key.isNotBlank() && !expandedSeen.add(key))
            ) continue

            blocks +=
                match to expanded.copy(sopText = cleaned)
        }

        if (blocks.isEmpty()) return null

        val evidence =
            blocks
                .joinToString("\n\n") { it.second.sopText }
                .take(MAX_EVIDENCE_CHARS)
                .trim()
        if (evidence.isBlank()) return null

        val sourceMetadata =
            blocks.joinToString(" || ") { (_, record) ->
                record.sourceInfo.take(260)
            }.take(1_200)

        val recordIds =
            blocks.joinToString(",") {
                it.first.record.id
            }.take(500)

        val scores =
            blocks.joinToString(",") {
                "%.4f".format(it.first.semanticScore)
            }.take(300)

        blocks.forEachIndexed { index, (match, record) ->
            Log.i(
                TAG,
                "ADAPTIVE_KB_BLOCK[${index + 1}] " +
                    "semantic=${"%.4f".format(match.semanticScore)} " +
                    "condition='${record.conditionName.take(120)}' " +
                    "source='${record.fileName.take(140)}'"
            )
        }

        Log.i(
            TAG,
            "ADAPTIVE_KB attached candidates=${rawMatches.size} " +
                "blocks=${blocks.size} views=${queries.size} " +
                "top=${"%.4f".format(db.lastTopSimilarity)} " +
                "visual=${visualObservation.isNotBlank()}"
        )

        val attachment = BaselineContextAttachment(
            sourceId = "adaptive_local_knowledge",
            text = evidence,
            priority = 100,
            attributes = mapOf(
                "grounding_required" to "true",
                "retrieval" to
                    "e5_current_plus_context_dynamic_idf_dedup",
                "knowledge_pack" to "anm_sop",
                "kb_sources" to sourceMetadata,
                "kb_record_ids" to recordIds,
                "kb_scores" to scores,
                "kb_top_similarity" to
                    "%.4f".format(db.lastTopSimilarity),
                "kb_visual_enriched" to
                    visualObservation.isNotBlank().toString()
            )
        )
        scheduleValidationPrewarm(query, acceptedHistory, attachment.text)
        return attachment
    }

    suspend fun recordCandidates(
        preparedQueries: List<String>,
        activeRecordIds: List<Int>
    ): List<ANMDatabaseHelper.KbRecordCandidate> {
        if (!ensureReady()) return emptyList()
        return db.searchRecordCandidates(
            queries = preparedQueries,
            activeRecordIds = activeRecordIds,
            // V4.1 KB-ON recall hardening: keep a modest source-bounded shortlist
            // large enough for semantic + lexical recall diversity. The applicability
            // selector still admits at most two records. KB-OFF never calls this path.
            maxRecords = 12
        )
    }

    /**
     * Stage B evidence assembly.  The applicability selector has already chosen
     * clinical RECORD ids, so only those records can enter the answer context.
     */
    suspend fun contextForRecordIds(
        query: String,
        recordIds: List<Int>,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String = ""
    ): BaselineContextAttachment? {
        if (!ensureReady()) return null
        val uniqueRecordCount = recordIds.distinct().count { it > 0 }
        val perRecordBudget = if (uniqueRecordCount > 1) 2_300 else 3_450
        val records = db.loadRecordEvidence(
            recordIds,
            maxCharsPerRecord = perRecordBudget
        )
        if (records.isEmpty()) return null

        val blocks = records.mapNotNull { record ->
            val cleaned = sanitizeEvidence(record.sopText)
            cleaned.takeIf { it.isNotBlank() }?.let { record.copy(sopText = it) }
        }
        if (blocks.isEmpty()) return null

        val evidence = blocks.joinToString("\n\n") { it.sopText }
            .take(MAX_EVIDENCE_CHARS)
            .trim()
        if (evidence.isBlank()) return null

        blocks.forEachIndexed { index, record ->
            Log.i(
                TAG,
                "ADAPTIVE_KB_RECORD_BLOCK[${index + 1}] rev=V3_2_CONTEXT_APPLICABILITY " +
                    "recordId=${record.id} condition='${record.conditionName.take(140)}'"
            )
        }

        val attachment = BaselineContextAttachment(
            sourceId = "adaptive_local_knowledge",
            text = evidence,
            priority = 100,
            attributes = mapOf(
                "grounding_required" to "true",
                "retrieval" to "v3_1_fact_applicability_selector",
                "knowledge_pack" to "anm_sop",
                "kb_record_ids" to blocks.joinToString(",") { it.id },
                "kb_conditions" to blocks.joinToString(" || ") { it.conditionName }.take(800),
                "kb_visual_enriched" to visualObservation.isNotBlank().toString(),
                "kb_engine_revision" to "V3_2_CONTEXT_APPLICABILITY"
            )
        )
        // Latency optimization: build immutable E5 validation embeddings while the
        // grounded Gemma answer is being generated. Validation still uses the exact
        // same thresholds and evidence; only the scheduling changes.
        scheduleValidationPrewarm(query, acceptedHistory, attachment.text)
        return attachment
    }

    /*
     * V3.2 hardening: the old flat passage retrieval entry points are intentionally
     * disabled.  The selected-KB wrapper must use recordCandidates() followed by
     * contextForRecordIds() after the applicability gate.
     */
    suspend fun contextForPreparedQueries(
        query: String,
        preparedQueries: List<String>,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String = ""
    ): BaselineContextAttachment? {
        Log.w(TAG, "KB_FLAT_RETRIEVAL_BLOCKED rev=V3_2_CONTEXT_APPLICABILITY entry=prepared")
        return null
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        Log.w(TAG, "KB_FLAT_RETRIEVAL_BLOCKED rev=V3_2_CONTEXT_APPLICABILITY entry=direct")
        return null
    }

    suspend fun contextForVisualObservation(
        query: String,
        visualObservation: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        Log.w(TAG, "KB_FLAT_RETRIEVAL_BLOCKED rev=V3_2_CONTEXT_APPLICABILITY entry=visual")
        return null
    }

    private fun sanitizeEvidence(raw: String): String {
        if (raw.isBlank()) return ""

        val androSanitized =
            AndroPromptInjectionDetector
                .sanitizeRetrievedDocument(raw)

        return androSanitized
            .replace(
                Regex(
                    "(?is)\\bKEYWORDS\\s*:\\s*.*?" +
                        "(?=\\n[A-Z][A-Z _/\\-]{2,}\\s*:|\\z)"
                ),
                ""
            )
            .replace(
                Regex(
                    "(?im)^\\s*(?:SOURCE|FILE|FILE_NAME|" +
                        "PAGE|PAGE_NUMBER)\\s*:\\s*.*$"
                ),
                ""
            )
            .replace(
                Regex("(?im)^\\s*Passage\\s+\\d+.*$"),
                ""
            )
            .replace(
                Regex(
                    "(?im)^\\s*[-*]\\s*" +
                        "(?:source|page|keywords?)\\s*[:=-].*$"
                ),
                ""
            )
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim()
            .take(3_500)
    }
}

typealias AnmKnowledgeContextProvider = AdaptiveKnowledgeContextProvider


/**
 * Session-selected ANM knowledge wrapper.
 *
 * This wrapper deliberately does NOT become a second global routing brain.
 * Ordinary Audio-only KB-OFF has no global semantic/topic router. This class contributes only:
 *   - ANM/SOP context attachment while the user has explicitly enabled KB,
 *   - verified-grounding admission for the active ANM thread, and
 *   - grounding validation when ANM evidence was actually attached.
 *
 * Keeping this seam separate prevents the older AdaptiveKnowledgeContextProvider
 * capability thresholds from interfering with ordinary local chat.
 */
class SelectedKnowledgeContextProvider(
    private val enabled: () -> Boolean,
    private val delegate: AdaptiveKnowledgeContextProvider,
    private val notebookSnapshot: () -> InvestigationNotebook = { InvestigationNotebook() },
    private val recordVerifiedVisualObservation: (String, String) -> Unit = { _, _ -> },
    private val updateCurrentGoal: (String) -> Unit = { _ -> },
    private val recordRejectedTheory: (String) -> Unit = { _ -> }
) : BaselineContextProvider, BaselineResponseValidator, BaselineCapabilityDecider, AcceptedTurnObserver {

    @Volatile
    private var queryPlanner: (suspend (String, String, String) -> KbQueryPlan?)? = null

    @Volatile
    private var recordSelector: (suspend (String, String, List<ANMDatabaseHelper.KbRecordCandidate>, List<Int>) -> KbRecordSelection?)? = null

    // Phase 10 KB authority model: exactly one SOP may be clinically authoritative.
    // Provisional candidates remain recall-visible but are never answer-authoritative.
    @Volatile private var activeRecordIds: List<Int> = emptyList()
    @Volatile private var provisionalRecordId: Int? = null
    @Volatile private var lastSelectedRecordIdsForTurn: List<Int> = emptyList()
    @Volatile private var pendingClarificationTurnKey: String = ""
    @Volatile private var pendingClarification: String = ""
    @Volatile private var pendingClarificationId: String = ""
    @Volatile private var pendingClarificationQuestionForAnswer: String = ""
    @Volatile private var awaitingClarificationAnswer: Boolean = false
    @Volatile private var clarificationAnsweredIdForTurn: String = ""
    @Volatile private var clarificationSequence: Long = 0L
    @Volatile private var consecutiveTechnicalActiveFallbacks: Int = 0
    @Volatile private var pendingSelectorTechnicalFailureTurnKey: String = ""
    // Candidate continuity exists only inside an unresolved selected-KB clarification.
    // It prevents the user's answer to our own question from pushing a previously
    // plausible SOP out of recall on the very next turn. These are NOT active records.
    @Volatile private var pendingClarificationCandidates: List<ANMDatabaseHelper.KbRecordCandidate> = emptyList()
    // Provisional applicability survives one clarification only as a candidate.
    // It must never become clinically authoritative until a later APPLICABLE decision.

    @Volatile private var lastTurnKey: String = ""
    @Volatile private var lastCaseContext: String = ""
    @Volatile private var lastPlan: KbQueryPlan = KbQueryPlan()
    @Volatile private var lastPlannerAvailable: Boolean = false
    @Volatile private var lastHadCaseState: Boolean = false
    @Volatile private var stagedVisualObservation: String = ""
    @Volatile private var lastHypothesesById: Map<String, String> = emptyMap()

    fun configureQueryPlanner(planner: suspend (String, String, String) -> KbQueryPlan?) {
        queryPlanner = planner
    }

    fun configureRecordSelector(
        selector: suspend (String, String, List<ANMDatabaseHelper.KbRecordCandidate>, List<Int>) -> KbRecordSelection?
    ) {
        recordSelector = selector
    }

    fun resetSessionState() {
        activeRecordIds = emptyList()
        provisionalRecordId = null
        lastSelectedRecordIdsForTurn = emptyList()
        pendingClarificationTurnKey = ""
        pendingClarification = ""
        pendingClarificationId = ""
        pendingClarificationQuestionForAnswer = ""
        awaitingClarificationAnswer = false
        clarificationAnsweredIdForTurn = ""
        clarificationSequence = 0L
        consecutiveTechnicalActiveFallbacks = 0
        pendingSelectorTechnicalFailureTurnKey = ""
        pendingClarificationCandidates = emptyList()
        lastTurnKey = ""
        lastCaseContext = ""
        lastPlan = KbQueryPlan()
        stagedVisualObservation = ""
        lastHypothesesById = emptyMap()
        Log.i("SelectedKnowledge", "KB_ENGINE_REV V3_2_CONTEXT_APPLICABILITY sessionReset=true")
        Log.i("SelectedKnowledge", "KB_SELECTOR_POLICY_REV V3_8_LOCAL_FIRST_APPLICABILITY")
    }

    fun clarificationForTurn(query: String): String =
        if (enabled() && pendingClarificationTurnKey == query.trim()) pendingClarification else ""

    fun selectorTechnicalFailureForTurn(query: String): Boolean =
        enabled() && pendingSelectorTechnicalFailureTurnKey == query.trim()

    private fun sameClarificationQuestion(a: String, b: String): Boolean {
        fun canonical(value: String): String = value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val ca = canonical(a)
        val cb = canonical(b)
        return ca.isNotBlank() && ca == cb
    }

    private fun newClarificationId(): String {
        clarificationSequence += 1L
        return "KBCLAR-$clarificationSequence"
    }

    private fun userSafeClarification(raw: String): String {
        val clean = FixSightOutputSanitizer.sanitize(raw)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(220)
        if (clean.isBlank()) return ""
        val protocolLeak = Regex(
            "(?i)(?:\\bCASE\\s*[:=]|\\bSOURCE\\s*[:=]|\\bREASON\\s*[:=]|" +
                "\\bSELECT\\s*[:=]|\\bKEEP\\s*[:=]|\\bSWITCH\\s*[:=]|" +
                "\\bNEEDS(?:_|\\s*)INFO\\s*[:=]|^\\s*<?\\d+\\s*>?\\s*\\|)"
        ).containsMatchIn(clean) || clean.contains('|') || clean.contains('<') || clean.contains('>')
        if (protocolLeak) {
            Log.w("SelectedKnowledge", "KB_CLARIFICATION_PROTOCOL_BLOCKED rev=V5_1_NO_PROTOCOL_LEAK")
            return ""
        }
        return if (clean.endsWith("?") || clean.endsWith(".")) clean else "$clean?"
    }

    override suspend fun isOperational(): Boolean = true

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (!enabled() || query.isBlank()) return false
        // V1 contract: user selection is the ONLY admission decision. Retrieval
        // quality decides whether evidence is attached afterwards.
        Log.i("SelectedKnowledge", "KB_SELECTED_SEARCH_AUTHORIZED queryChars=${query.trim().length}")
        return true
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!enabled()) return null
        return recordAwareContextFor(query, acceptedHistory, visualObservation = "", supplementalEvidence = "")
    }

    suspend fun contextForVisualObservation(
        query: String,
        visualObservation: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!enabled()) return null
        val cleanObservation = FixSightOutputSanitizer.sanitize(visualObservation).trim().take(700)
        stagedVisualObservation = cleanObservation
        return recordAwareContextFor(query, acceptedHistory, cleanObservation, supplementalEvidence = "")
    }

    /**
     * KB-ON attachment enrichment.  OCR/document/object evidence is allowed to
     * improve retrieval for THIS turn, but is not silently promoted into durable
     * confirmed patient facts.  The accepted answer/turn still goes through the
     * normal Notebook observer after grounding succeeds.
     */
    suspend fun contextForAttachmentEvidence(
        query: String,
        attachmentEvidence: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!enabled()) return null
        val clean = FixSightOutputSanitizer.sanitize(attachmentEvidence)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(1_800)
        if (clean.isBlank()) return contextFor(query, acceptedHistory)
        return recordAwareContextFor(
            query = query,
            acceptedHistory = acceptedHistory,
            visualObservation = "",
            supplementalEvidence = clean
        )
    }

    private suspend fun recordAwareContextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String,
        supplementalEvidence: String = ""
    ): BaselineContextAttachment? {
        return try {
            val current = query.trim().take(700)
            if (current.isBlank()) return null

            // Deterministic clarification linkage. The current utterance is considered
            // the answer only when the immediately preceding accepted assistant turn
            // exactly matches the clarification that this provider emitted. No fuzzy
            // Notebook substring matching is used.
            val previousAssistant = acceptedHistory.lastOrNull { it.role == "assistant" }
                ?.content
                ?.let { FixSightOutputSanitizer.sanitize(it).trim() }
                .orEmpty()
            clarificationAnsweredIdForTurn = if (
                awaitingClarificationAnswer &&
                pendingClarificationId.isNotBlank() &&
                pendingClarificationQuestionForAnswer.isNotBlank() &&
                previousAssistant == pendingClarificationQuestionForAnswer
            ) {
                pendingClarificationId
            } else {
                ""
            }
            if (clarificationAnsweredIdForTurn.isNotBlank()) {
                awaitingClarificationAnswer = false
                Log.i(
                    "SelectedKnowledge",
                    "KB_CLARIFICATION_ANSWER_LINKED rev=V5_0_SINGLE_AUTHORITY id=$clarificationAnsweredIdForTurn"
                )
            }

            /*
             * V3.3 contract restore:
             * The FINALIZED conversation-aware planner runs first. Record-level
             * retrieval/applicability is a knowledge layer underneath it; it is not
             * allowed to treat the current utterance as a brand-new case.
             */
            val prepared = prepareTurn(query, acceptedHistory, visualObservation, supplementalEvidence)
            val notebook = notebookSnapshot()
            val selectorFactContext = renderFactOnlyApplicabilityContext(
                notebook = notebook,
                current = current,
                visualObservation = visualObservation,
                supplementalEvidence = supplementalEvidence,
                acceptedHistory = acceptedHistory
            )

            pendingClarificationTurnKey = ""
            pendingClarification = ""
            pendingSelectorTechnicalFailureTurnKey = ""
            lastSelectedRecordIdsForTurn = emptyList()

            // View 1 is ALWAYS the literal current request. View 2 keeps a stable
            // presenting-case anchor and adds the current request.  Full accepted
            // history still goes to the applicability selector as facts, but an
            // answer to an earlier clarification cannot redefine semantic recall.
            val acceptedUserContext = renderAcceptedUserHistory(acceptedHistory)
            val presentingProblem = notebook.problem.trim().ifBlank {
                acceptedHistory.firstOrNull { it.role == "user" && it.content.isNotBlank() }
                    ?.content?.trim().orEmpty()
            }
            val stableFacts = notebook.confirmedFacts
                .take(8)
                .joinToString(". ") { it.trim().take(260) }
            val plannedCaseContext = prepared.queries
                .drop(1)
                .joinToString(". ")
                .trim()

            val caseView = buildString {
                if (presentingProblem.isNotBlank()) {
                    append("Presenting problem: ").append(presentingProblem.take(700))
                }
                if (stableFacts.isNotBlank()) {
                    if (isNotEmpty()) append(". ")
                    append("Established user facts: ").append(stableFacts)
                }
                if (plannedCaseContext.isNotBlank()) {
                    if (isNotEmpty()) append(". ")
                    append(plannedCaseContext.take(900))
                }
                if (isEmpty() && acceptedUserContext.isNotBlank()) {
                    append(acceptedUserContext.take(1_200))
                }
                if (supplementalEvidence.isNotBlank()) {
                    if (isNotEmpty()) append(". ")
                    append("Current offline attachment evidence: ")
                    append(supplementalEvidence.take(1_200))
                }
                if (isNotEmpty()) append(". ")
                append("Current user request: ").append(current)
            }.trim().take(2_400)

            val queries = listOf(current, caseView)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)

            Log.i(
                "SelectedKnowledge",
                "KB_CONVERSATION_REV V3_3_CONTRACT_RESTORE historyUsers=" +
                    acceptedHistory.count { it.role == "user" } +
                    " planner=${prepared.plannerAvailable} relation=${prepared.plan.normalizedRelation()} " +
                    "views=${queries.size} active=${activeRecordIds.joinToString(",")}" 
            )

            val freshCandidates = delegate.recordCandidates(queries, activeRecordIds)

            /*
             * V5.2 semantic KB continuation latch.
             *
             * No phrase/keyword list is used here. The local conversation planner
             * already decides whether the current utterance is a FOLLOW_UP. When an
             * SOP is already authoritative and that exact active record is still
             * present in fresh semantic retrieval, a FOLLOW_UP must continue against
             * that record locally instead of re-running applicability selection and
             * potentially escalating a vague continuation to Cloud.
             *
             * A CASE_RELATED or UNCERTAIN turn still goes through the normal selector,
             * so genuinely new facts can trigger applicability review/transition.
             */
            val semanticContinuationActiveId = if (
                prepared.plannerAvailable &&
                prepared.plan.normalizedRelation() == "FOLLOW_UP" &&
                activeRecordIds.size == 1 &&
                freshCandidates.any { it.recordId == activeRecordIds.first() }
            ) {
                activeRecordIds.first()
            } else {
                null
            }

            if (semanticContinuationActiveId != null) {
                val attachment = delegate.contextForRecordIds(
                    query = current,
                    recordIds = listOf(semanticContinuationActiveId),
                    acceptedHistory = acceptedHistory,
                    visualObservation = visualObservation
                )
                if (attachment != null && attachment.text.isNotBlank()) {
                    pendingClarificationCandidates = emptyList()
                    provisionalRecordId = null
                    awaitingClarificationAnswer = false
                    pendingClarificationId = ""
                    pendingClarificationQuestionForAnswer = ""
                    lastSelectedRecordIdsForTurn = listOf(semanticContinuationActiveId)
                    Log.i(
                        "SelectedKnowledge",
                        "KB_SEMANTIC_CONTINUATION_LOCAL rev=V5_2_ACTIVE_SOP " +
                            "record=$semanticContinuationActiveId relation=FOLLOW_UP " +
                            "planner=true evidenceChars=${attachment.text.length}"
                    )
                    return attachment.copy(
                        attributes = attachment.attributes + mapOf(
                            "kb_case_context" to prepared.notebookContext.take(6_500),
                            "kb_engine_revision" to "V3_2_CONTEXT_APPLICABILITY",
                            "kb_conversation_revision" to "V5_2_SEMANTIC_CONTINUATION",
                            "kb_query_relation" to "FOLLOW_UP",
                            "kb_query_planner" to "local_llm",
                            "kb_selection_confidence" to "CONTINUITY",
                            "kb_selector_recovery" to "semantic_active_record"
                        )
                    )
                } else {
                    Log.w(
                        "SelectedKnowledge",
                        "KB_SEMANTIC_CONTINUATION_LOCAL_MISS rev=V5_2_ACTIVE_SOP " +
                            "record=$semanticContinuationActiveId fallback=selector"
                    )
                }
            }

            // Preserve a small neutral shortlist across one unresolved applicability
            // clarification. They are merged as ordinary candidates (never ACTIVE),
            // so the selector can still reject them, but the answer to our own
            // clarification cannot erase a previously promising source from recall.
            val candidates = (pendingClarificationCandidates + freshCandidates)
                .distinctBy { it.recordId }
                .take(16)
            if (pendingClarificationCandidates.isNotEmpty()) {
                Log.i(
                    "SelectedKnowledge",
                    "KB_PENDING_CANDIDATE_CONTINUITY rev=V3_8_CLARIFICATION_CONTINUITY " +
                        "carried=${pendingClarificationCandidates.map { it.recordId }.joinToString(",")} " +
                        "fresh=${freshCandidates.size} merged=${candidates.size}"
                )
            }
            if (candidates.isEmpty()) {
                pendingSelectorTechnicalFailureTurnKey = query.trim()
                pendingClarificationCandidates = emptyList()
                pendingClarificationTurnKey = query.trim()
                pendingClarification =
                    "I couldn't complete the SOP search for that turn. Please try the same clinical detail again."
                Log.w(
                    "SelectedKnowledge",
                    "KB_RECORD_SELECTION rev=V3_2_CONTEXT_APPLICABILITY candidates=0 " +
                        "active=${activeRecordIds.joinToString(",")} technicalFailure=true"
                )
                return null
            }

            val selector = recordSelector
            val selection = if (selector != null) {
                try { selector(selectorFactContext, current, candidates, activeRecordIds) }
                catch (t: Throwable) {
                    Log.w("SelectedKnowledge", "KB_RECORD_SELECTOR_FAILED ${t.message}")
                    null
                }
            } else null

            val candidateIds = candidates.map { it.recordId }.toSet()
            val confidence = selection?.confidence?.trim()?.uppercase().orEmpty()
            val applicableIds = selection?.decisions.orEmpty()
                .filter { it.normalizedVerdict() == "APPLICABLE" }
                .map { it.recordId }
                .filter { it in candidateIds }
                .toSet()
            val needsInfoIds = selection?.decisions.orEmpty()
                .filter { it.normalizedVerdict() == "NEEDS_INFO" }
                .map { it.recordId }
                .filter { it in candidateIds }
                .toSet()

            // V3.11: an evidence-bound selector may identify the SOP that already fits
            // the presenting case while asking for one branch-specific fact. Keep that
            // SOP provisionally active instead of collapsing back into an unanchored
            // candidate pool. This never infers an SOP from retrieval score alone: the
            // selector must provide MEDIUM/HIGH evidence and explicitly name the record.
            val anchoredNeedsInfoIds = if (
                selection != null &&
                (confidence == "HIGH" || (activeRecordIds.isNotEmpty() && confidence == "MEDIUM")) &&
                selection.clarificationQuestion.isNotBlank()
            ) {
                selection.selectedRecordIds
                    .filter { it in candidateIds && it in needsInfoIds }
                    .distinct()
                    .take(1)
            } else emptyList()

            if (anchoredNeedsInfoIds.isNotEmpty()) {
                val question = userSafeClarification(selection!!.clarificationQuestion).ifBlank {
                    "What additional established case fact would determine whether this SOP applies?"
                }
                val provisional = anchoredNeedsInfoIds.first()
                provisionalRecordId = provisional
                pendingClarificationCandidates = candidates
                    .filter { it.recordId == provisional }
                    .take(1)
                pendingClarificationTurnKey = query.trim()
                pendingClarification = question
                pendingClarificationId = newClarificationId()
                pendingClarificationQuestionForAnswer = question
                awaitingClarificationAnswer = true
                lastSelectedRecordIdsForTurn = emptyList()
                Log.i(
                    "SelectedKnowledge",
                    "KB_RECORD_PROVISIONAL rev=V5_0_SINGLE_AUTHORITY " +
                        "record=$provisional active=${activeRecordIds.joinToString(",")} " +
                        "confidence=$confidence clarificationId=$pendingClarificationId " +
                        "question='${question.take(120)}'"
                )
                return null
            }

            // Normal admission remains evidence-bound. A purely TECHNICAL selector
            // failure is different: if an already-accepted active SOP is still present
            // in fresh retrieval, retain only that active record rather than destroying
            // the investigation. This never admits a new record and never performs a
            // transition without positive selector evidence.
            val evidenceSelectedIds =
                if (confidence == "HIGH" || (activeRecordIds.isNotEmpty() && confidence == "MEDIUM")) {
                    selection?.selectedRecordIds.orEmpty()
                        .filter { it in candidateIds && it in applicableIds }
                        .distinct()
                        .take(1)
                } else {
                    emptyList()
                }
            val technicalActiveFallback = if (
                selection == null &&
                evidenceSelectedIds.isEmpty() &&
                consecutiveTechnicalActiveFallbacks < 1
            ) {
                activeRecordIds.filter { it in candidateIds }.distinct().take(1)
            } else emptyList()

            val suppliedClarification = FixSightOutputSanitizer.sanitize(
                selection?.clarificationQuestion.orEmpty()
            ).trim().take(220)
            val decisionById = selection?.decisions.orEmpty().associateBy { it.recordId }
            val answeredClarificationActiveFallback =
                if (
                    selection != null &&
                    evidenceSelectedIds.isEmpty() &&
                    technicalActiveFallback.isEmpty() &&
                    clarificationAnsweredIdForTurn.isNotBlank() &&
                    suppliedClarification.isNotBlank() &&
                    sameClarificationQuestion(suppliedClarification, pendingClarificationQuestionForAnswer)
                ) {
                    activeRecordIds
                        .filter { id ->
                            id in candidateIds &&
                                decisionById[id]?.normalizedVerdict() != "CONFLICT"
                        }
                        .distinct()
                        .take(1)
                } else {
                    emptyList()
                }

            val selectedIds = when {
                evidenceSelectedIds.isNotEmpty() -> evidenceSelectedIds
                technicalActiveFallback.isNotEmpty() -> technicalActiveFallback
                else -> answeredClarificationActiveFallback
            }
            val effectiveConfidence =
                if (technicalActiveFallback.isNotEmpty() || answeredClarificationActiveFallback.isNotEmpty()) {
                    "MEDIUM"
                } else {
                    confidence
                }
            if (technicalActiveFallback.isNotEmpty()) {
                consecutiveTechnicalActiveFallbacks += 1
                Log.w(
                    "SelectedKnowledge",
                    "KB_SELECTOR_TECHNICAL_KEEP_ACTIVE rev=V3_9_ACTIVE_CONTINUITY " +
                        "active=${technicalActiveFallback.joinToString(",")} candidates=${candidates.size}"
                )
            }
            if (technicalActiveFallback.isEmpty() && selection != null) {
                consecutiveTechnicalActiveFallbacks = 0
            }
            if (answeredClarificationActiveFallback.isNotEmpty()) {
                Log.w(
                    "SelectedKnowledge",
                    "KB_SELECTOR_ANSWERED_CLARIFICATION_KEEP_ACTIVE rev=V3_10_NOTEBOOK_QA_CONTINUITY " +
                        "active=${answeredClarificationActiveFallback.joinToString(",")} " +
                        "question='${suppliedClarification.take(120)}'"
                )
            }

            selection?.decisions.orEmpty().forEach { decision ->
                if (decision.recordId in candidateIds) {
                    Log.i(
                        "SelectedKnowledge",
                        "KB_RECORD_DECISION rev=V3_2_CONTEXT_APPLICABILITY " +
                            "id=${decision.recordId} verdict=${decision.normalizedVerdict()} " +
                            "missing=${decision.missingFact.isNotBlank()} " +
                            "rationale='${decision.rationale.take(180)}'"
                    )
                }
            }

            Log.i(
                "SelectedKnowledge",
                "KB_RECORD_SELECTION rev=V3_2_CONTEXT_APPLICABILITY " +
                    "selected=${selectedIds.joinToString(",")} confidence=${if (effectiveConfidence.isBlank()) "NONE" else effectiveConfidence} " +
                    "applicable=${applicableIds.joinToString(",")} " +
                    "active=${activeRecordIds.joinToString(",")} candidates=${candidates.size}"
            )

            if (selectedIds.isEmpty()) {
                val supplied = suppliedClarification
                val question = if (selection == null) {
                    pendingSelectorTechnicalFailureTurnKey = query.trim()
                    // Technical failure is not a clinical clarification decision.
                    // Do not create a new continuity shortlist from a failed selector.
                    "I couldn't complete the SOP applicability check for that turn. Please try the same clinical detail again."
                } else {
                    // Preserve the bounded retrieval pool across the clarification turn;
                    // the selector itself composes semantic + lexical coverage.
                    pendingClarificationCandidates = candidates
                    userSafeClarification(supplied).ifBlank {
                        "I need one more established patient fact before I can safely match an SOP. What additional fact is known?"
                    }
                }
                pendingClarificationTurnKey = query.trim()
                pendingClarification = if (question.endsWith("?") || question.endsWith(".")) question else "$question?"
                if (selection != null) {
                    pendingClarificationId = newClarificationId()
                    pendingClarificationQuestionForAnswer = pendingClarification
                    awaitingClarificationAnswer = true
                }
                Log.i(
                    "SelectedKnowledge",
                    "KB_RECORD_CLARIFICATION rev=V3_2_CONTEXT_APPLICABILITY " +
                        "chars=${pendingClarification.length} selectorPresent=${selection != null}"
                )
                return null
            }

            pendingClarificationCandidates = emptyList()
            provisionalRecordId = null
            awaitingClarificationAnswer = false
            pendingClarificationId = ""
            pendingClarificationQuestionForAnswer = ""
            lastSelectedRecordIdsForTurn = selectedIds.take(1)
            val attachment = delegate.contextForRecordIds(
                query = current,
                recordIds = selectedIds,
                acceptedHistory = acceptedHistory,
                visualObservation = visualObservation
            ) ?: return null

            attachment.copy(
                attributes = attachment.attributes + mapOf(
                    "kb_case_context" to prepared.notebookContext.take(6_500),
                    "kb_engine_revision" to "V3_2_CONTEXT_APPLICABILITY",
                    "kb_conversation_revision" to "V3_3_CONTRACT_RESTORE",
                    "kb_query_relation" to prepared.plan.normalizedRelation(),
                    "kb_query_planner" to if (prepared.plannerAvailable) "local_llm" else "deterministic_fallback",
                    "kb_selection_confidence" to effectiveConfidence,
                    "kb_case_fact_evidence" to selection?.caseFactEvidence.orEmpty()
                        .joinToString(" || ")
                        .take(1_200),
                    "kb_selector_recovery" to if (technicalActiveFallback.isNotEmpty()) {
                        "active_continuity"
                    } else {
                        "none"
                    }
                )
            )
        } catch (t: Throwable) {
            Log.w("SelectedKnowledge", "KB_RECORD_CONTEXT_FAILED ${t.message}")
            null
        }
    }

    fun isSelected(): Boolean = enabled()

    /** Read-only case context for the current turn. Empty when KB is off. */
    fun caseContextForTurn(query: String): String {
        if (!enabled()) return ""
        return if (lastTurnKey == query.trim()) lastCaseContext else ""
    }

    /**
     * V1 mode contract: the user chose KB before session start, so runtime never
     * reclassifies a turn as generic or asks to leave KB mode mid-investigation.
     */
    suspend fun shouldPromptDisableKb(query: String): Boolean = false

    override suspend fun onAcceptedTurn(
        userText: String,
        assistantText: String,
        provider: AndroBaselineRuntime.Provider
    ) {
        if (!enabled() || userText.trim() != lastTurnKey) return

        val acceptedClarification =
            lastSelectedRecordIdsForTurn.isEmpty() &&
                pendingClarificationCandidates.isNotEmpty() &&
                pendingClarificationTurnKey == userText.trim() &&
                pendingClarification.isNotBlank() &&
                FixSightOutputSanitizer.sanitize(assistantText).trim() ==
                    FixSightOutputSanitizer.sanitize(pendingClarification).trim()

        val visual = stagedVisualObservation.trim()
        if (visual.isNotBlank()) {
            recordVerifiedVisualObservation(visual, "CLOUD_VISION")
        }

        if (lastSelectedRecordIdsForTurn.isNotEmpty()) {
            activeRecordIds = lastSelectedRecordIdsForTurn.take(1)
            Log.i(
                "SelectedKnowledge",
                "KB_RECORD_ANCHOR rev=V3_2_CONTEXT_APPLICABILITY active=${activeRecordIds.joinToString(",")}"
            )
        }
        if (acceptedClarification) {
            awaitingClarificationAnswer = true
            if (pendingClarificationQuestionForAnswer.isBlank()) {
                pendingClarificationQuestionForAnswer = FixSightOutputSanitizer.sanitize(assistantText).trim()
            }
            if (pendingClarificationId.isBlank()) pendingClarificationId = newClarificationId()
        }
        pendingClarificationTurnKey = ""
        pendingClarification = ""
        pendingSelectorTechnicalFailureTurnKey = ""
        if (!acceptedClarification) {
            pendingClarificationCandidates = emptyList()
            if (lastSelectedRecordIdsForTurn.isNotEmpty()) provisionalRecordId = null
        } else {
            // Keep only recall continuity. A provisional SOP is deliberately NOT
            // promoted into activeRecordIds merely because its question was asked.
            Log.i(
                "SelectedKnowledge",
                "KB_PROVISIONAL_RETAINED rev=V5_0_SINGLE_AUTHORITY " +
                    "provisional=${provisionalRecordId ?: 0} active=${activeRecordIds.joinToString(",")} " +
                    "ids=${pendingClarificationCandidates.map { it.recordId }.joinToString(",")}"
            )
        }
        lastSelectedRecordIdsForTurn = emptyList()

        val plan = lastPlan
        if (plan.goalChange && plan.newGoal.isNotBlank()) {
            updateCurrentGoal(plan.newGoal)
        }
        plan.rejectedTheoryIds.forEach { id ->
            lastHypothesesById[id]?.takeIf { it.isNotBlank() }?.let(recordRejectedTheory)
        }

        stagedVisualObservation = ""
        lastTurnKey = ""
        lastPlan = KbQueryPlan()
        lastHypothesesById = emptyMap()
    }

    override suspend fun requiresVerifiedGrounding(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        // KB ON is strict selected-KB investigation mode. Missing evidence escalates
        // or fails closed; it never silently becomes unrestricted local medical advice.
        return enabled() && query.isNotBlank()
    }

    override suspend fun requiresExternalCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun requiresSafetyEscalation(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun requiresFactualVerification(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun requiresVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun requiresHighDetailVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        if (!enabled()) return BaselineValidationResult.ACCEPT
        val hasAnmEvidence = attachments.any {
            it.attributes["knowledge_pack"] == "anm_sop" &&
                it.attributes["grounding_required"] == "true"
        }
        if (!hasAnmEvidence) return BaselineValidationResult.ACCEPT
        return delegate.validate(query, answer, provider, attachments, acceptedHistory)
    }

    private data class PreparedTurn(
        val queries: List<String>,
        val notebookContext: String,
        val plan: KbQueryPlan,
        val plannerAvailable: Boolean
    )

    private fun renderAcceptedUserHistory(
        acceptedHistory: List<AndroChatMessage>,
        maxUserTurns: Int = 12
    ): String = acceptedHistory.asReversed()
        .filter { it.role == "user" && it.content.isNotBlank() }
        .take(maxUserTurns.coerceIn(1, 24))
        .reversed()
        .joinToString(". ") { it.content.trim().take(420) }
        .trim()
        .take(3_600)

    private suspend fun prepareTurn(
        query: String,
        acceptedHistory: List<AndroChatMessage>,
        visualObservation: String,
        supplementalEvidence: String = ""
    ): PreparedTurn {
        val current = query.trim().take(500)
        val notebook = notebookSnapshot()
        val notebookOnlyContext = renderNotebookForKb(notebook, current, visualObservation)
        val acceptedUserContext = renderAcceptedUserHistory(acceptedHistory)

        // AndroConversationStore/acceptedHistory remains the authoritative live
        // conversation. Notebook is additive case structure, never a replacement.
        val notebookContext = buildString {
            if (acceptedUserContext.isNotBlank()) {
                append("RECENT ACCEPTED USER TURNS:\n")
                append(acceptedUserContext)
                append('\n')
            }
            if (notebookOnlyContext.isNotBlank()) append(notebookOnlyContext)
            if (supplementalEvidence.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("CURRENT OFFLINE ATTACHMENT EVIDENCE (turn-scoped, not automatically confirmed fact):\n")
                append(supplementalEvidence.take(1_800))
            }
        }.trim().take(6_500)

        val planner = queryPlanner
        val hasCaseState = acceptedUserContext.isNotBlank() ||
            notebook.problem.isNotBlank() || notebook.confirmedFacts.isNotEmpty() ||
            notebook.userResponses.isNotEmpty() || notebook.visualFindings.isNotEmpty() ||
            notebook.questionsAlreadyAsked.isNotEmpty() || visualObservation.isNotBlank()
        val rawPlan = if (planner != null) {
            try { planner(notebookContext, current, visualObservation) }
            catch (t: Throwable) {
                Log.w("SelectedKnowledge", "KB_QUERY_PLANNER_FAILED ${t.message}")
                null
            }
        } else null

        val plannerAvailable = rawPlan != null
        val normalizedPlan = sanitizePlannerPlan(
            raw = rawPlan ?: KbQueryPlan(),
            notebook = notebook,
            current = current,
            visualObservation = visualObservation
        )
        val relation = normalizedPlan.normalizedRelation()
        val queries = buildList {
            // Q1 is inviolable: raw user request is ALWAYS searched independently.
            add(current)

            normalizedPlan.caseQuery.trim().takeIf { it.isNotBlank() }?.let { add(it.take(1_600)) }
            normalizedPlan.focusQuery.trim().takeIf { it.isNotBlank() }?.let { add(it.take(1_600)) }

            // In a KB session every turn belongs to the same investigation by user
            // choice. If Local planning is unavailable for an abbreviated turn, use a
            // deterministic Notebook projection rather than classifying it as generic.
            if (size == 1 && hasCaseState) {
                deterministicCaseQuery(notebook, current, visualObservation, supplementalEvidence)
                    .takeIf { it.isNotBlank() }
                    ?.let { add(it.take(1_600)) }
            }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)

        lastTurnKey = query.trim()
        lastCaseContext = notebookContext
        lastPlan = normalizedPlan
        lastPlannerAvailable = plannerAvailable
        lastHadCaseState = hasCaseState
        stagedVisualObservation = FixSightOutputSanitizer.sanitize(visualObservation).trim().take(700)
        lastHypothesesById = buildMap {
            listOf(notebook.primaryTheory, notebook.secondaryTheory)
                .filter { it.isNotBlank() }
                .forEachIndexed { index, theory -> put("H${index + 1}", theory) }
        }

        Log.i(
            "SelectedKnowledge",
            "KB_QUERY_PLAN relation=$relation views=${queries.size} planner=$plannerAvailable " +
                "facts=${normalizedPlan.factsUsed.take(8).joinToString(",")} goalChange=${normalizedPlan.goalChange}"
        )
        return PreparedTurn(queries, notebookContext, normalizedPlan, plannerAvailable)
    }

    private fun enrichWithCaseContext(
        attachment: BaselineContextAttachment,
        prepared: PreparedTurn
    ): BaselineContextAttachment = attachment.copy(
        attributes = attachment.attributes + mapOf(
            "kb_case_context" to prepared.notebookContext.take(6_500),
            "kb_query_relation" to prepared.plan.normalizedRelation(),
            "kb_query_planner" to if (prepared.plannerAvailable) "local_llm" else "deterministic_fallback"
        )
    )

    private fun sanitizePlannerPlan(
        raw: KbQueryPlan,
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String
    ): KbQueryPlan {
        val idMap = notebookEvidenceIdMap(notebook)

        val validFactIds = raw.factsUsed
            .map { it.trim().uppercase() }
            .filter { id ->
                id in idMap &&
                    id.firstOrNull() in setOf('P', 'G', 'F', 'R', 'V')
            }
            .distinct()
            .take(16)

        val validRejected = raw.rejectedTheoryIds
            .map { it.trim().uppercase() }
            .filter {
                it in idMap &&
                    it.startsWith("H")
            }
            .distinct()
            .take(4)

        val selectedTexts =
            validFactIds.mapNotNull(idMap::get)

        /*
         * The planner may SELECT existing Notebook evidence IDs, but its free-form
         * case/focus wording is never trusted as retrieval text. Retrieval queries
         * are reconstructed only from user facts, verified observations, Notebook
         * state, and the current user turn. This removes every hand-authored
         * planner allowlist/stop-word vocabulary and also blocks invented diagnoses.
         */
        val safeCase = buildPlannerFallbackQuery(
            selectedTexts = selectedTexts,
            notebook = notebook,
            current = current,
            visualObservation = visualObservation,
            includeGoal = true
        )

        val safeFocus = buildPlannerFocusFallback(
            selectedTexts = selectedTexts,
            notebook = notebook,
            current = current,
            visualObservation = visualObservation
        )

        val sourceText = buildString {
            selectedTexts.forEach { text ->
                if (isNotEmpty()) append(". ")
                append(text.trim())
            }
            if (notebook.problem.isNotBlank()) {
                if (isNotEmpty()) append(". ")
                append(notebook.problem.trim())
            }
            if (notebook.currentGoal.isNotBlank()) {
                if (isNotEmpty()) append(". ")
                append(notebook.currentGoal.trim())
            }
            if (visualObservation.isNotBlank()) {
                if (isNotEmpty()) append(". ")
                append(visualObservation.trim())
            }
            if (isNotEmpty()) append(". ")
            append(current.trim())
        }

        val rawGoal =
            FixSightOutputSanitizer
                .sanitize(raw.newGoal)
                .trim()
                .take(240)

        // Goal updates are accepted only when their own tokens are overwhelmingly
        // present in trusted source text. No static intent vocabulary participates.
        val safeGoalChange =
            raw.goalChange &&
                rawGoal.isNotBlank() &&
                dataGroundedText(rawGoal, sourceText)

        if (raw.goalChange && !safeGoalChange) {
            Log.w(
                "SelectedKnowledge",
                "KB_GOAL_PLAN_REJECT unsupportedPlannerGoal=true"
            )
        }

        if (
            raw.caseQuery.isNotBlank() ||
            raw.focusQuery.isNotBlank()
        ) {
            Log.d(
                "SelectedKnowledge",
                "KB_QUERY_PLAN_FREEFORM_IGNORED " +
                    "caseChars=${raw.caseQuery.length} " +
                    "focusChars=${raw.focusQuery.length}"
            )
        }

        return KbQueryPlan(
            relation = raw.normalizedRelation(),
            caseQuery = safeCase.take(1_600),
            focusQuery = safeFocus.take(1_600),
            factsUsed = validFactIds,
            goalChange = safeGoalChange,
            newGoal = if (safeGoalChange) rawGoal else "",
            rejectedTheoryIds = validRejected
        )
    }

    private fun stableNotebookWindow(
        items: List<String>,
        firstCount: Int,
        lastCount: Int
    ): List<String> {
        if (items.size <= firstCount + lastCount) return items
        return (items.take(firstCount) + items.takeLast(lastCount))
            .distinctBy { it.lowercase().replace(Regex("\\s+"), " ").take(120) }
    }

    private fun notebookEvidenceIdMap(notebook: InvestigationNotebook): Map<String, String> = buildMap {
        if (notebook.problem.isNotBlank()) put("P1", notebook.problem.trim())
        if (notebook.currentGoal.isNotBlank()) put("G1", notebook.currentGoal.trim())
        stableNotebookWindow(notebook.confirmedFacts, firstCount = 8, lastCount = 12)
            .forEachIndexed { index, text -> put("F${index + 1}", text.trim()) }
        notebook.userResponses.takeLast(12).forEachIndexed { index, text -> put("R${index + 1}", text.trim()) }
        notebook.visualFindings.takeLast(12).forEachIndexed { index, text -> put("V${index + 1}", text.trim()) }
        notebook.questionsAlreadyAsked.takeLast(12).forEachIndexed { index, text -> put("Q${index + 1}", text.trim()) }
        notebook.checksPerformed.takeLast(8).forEachIndexed { index, text -> put("C${index + 1}", text.trim()) }
        notebook.actionsSuggested.takeLast(8).forEachIndexed { index, text -> put("A${index + 1}", text.trim()) }
        listOf(notebook.primaryTheory, notebook.secondaryTheory)
            .filter { it.isNotBlank() }
            .forEachIndexed { index, text -> put("H${index + 1}", text.trim()) }
        notebook.rejectedTheories.takeLast(6).forEachIndexed { index, text -> put("X${index + 1}", text.trim()) }
    }

    /**
     * Generic provenance check for planner-written Notebook metadata.
     * All tokens are derived from the candidate and trusted source at runtime.
     * There is no static vocabulary or domain lexicon.
     */
    private fun dataGroundedText(
        candidate: String,
        sourceText: String
    ): Boolean {
        val candidateTokens = dynamicTokens(candidate).distinct()
        if (candidateTokens.isEmpty()) return false

        val sourceTokens = dynamicTokens(sourceText).toHashSet()
        if (sourceTokens.isEmpty()) return false

        val matched = candidateTokens.count { token ->
            token in sourceTokens ||
                sourceTokens.any { source ->
                    val min = minOf(token.length, source.length)
                    min >= 5 &&
                        token.regionMatches(
                            0,
                            source,
                            0,
                            minOf(6, min),
                            ignoreCase = true
                        )
                }
        }

        val coverage =
            matched.toFloat() /
                candidateTokens.size.toFloat()

        return coverage >= 0.80f
    }

    private fun dynamicTokens(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }

    private fun buildPlannerFallbackQuery(
        selectedTexts: List<String>,
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String,
        includeGoal: Boolean
    ): String = buildString {
        val sources = if (selectedTexts.isNotEmpty()) selectedTexts else buildList {
            if (notebook.problem.isNotBlank()) add(notebook.problem)
            addAll(notebook.confirmedFacts.takeLast(8))
            addAll(notebook.userResponses.takeLast(4))
            addAll(notebook.visualFindings.takeLast(4))
        }
        sources.forEach { text ->
            if (text.isBlank()) return@forEach
            if (isNotEmpty()) append(". ")
            append(text.trim())
        }
        if (includeGoal && notebook.currentGoal.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append("Current goal: ").append(notebook.currentGoal.trim())
        }
        if (visualObservation.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append("Verified visual observation: ").append(visualObservation.trim())
        }
        if (isNotEmpty()) append(". ")
        append("Current user request: ").append(current.trim())
    }.trim()

    private fun buildPlannerFocusFallback(
        selectedTexts: List<String>,
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String
    ): String = buildString {
        if (notebook.problem.isNotBlank()) append(notebook.problem.trim())
        selectedTexts.takeLast(4).forEach { text ->
            if (text.isBlank()) return@forEach
            if (isNotEmpty()) append(". ")
            append(text.trim())
        }
        if (visualObservation.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append(visualObservation.trim())
        }
        if (notebook.currentGoal.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append("Goal: ").append(notebook.currentGoal.trim())
        }
        if (isNotEmpty()) append(". ")
        append(current.trim())
    }.trim()

    /** Safe retrieval projection: facts/observations/responses only, never hypotheses/actions. */
    private fun deterministicCaseQuery(
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String,
        supplementalEvidence: String = ""
    ): String = buildString {
        notebook.confirmedFacts.takeLast(8).forEach { fact ->
            if (isNotEmpty()) append(". ")
            append(fact.trim())
        }
        notebook.userResponses.takeLast(4).forEach { response ->
            if (isNotEmpty()) append(". ")
            append(response.trim())
        }
        notebook.visualFindings.takeLast(4).forEach { visual ->
            if (isNotEmpty()) append(". ")
            append(visual.trim())
        }
        if (visualObservation.isNotBlank() && notebook.visualFindings.none { it.contains(visualObservation, ignoreCase = true) }) {
            if (isNotEmpty()) append(". ")
            append("Verified visual observation: ").append(visualObservation.trim())
        }
        if (supplementalEvidence.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append("Current offline attachment evidence: ").append(supplementalEvidence.take(1_200))
        }
        if (isNotEmpty()) append(". ")
        append("Current user request: ").append(current)
    }.trim()

    /**
     * Applicability-gate input contains facts only.  It deliberately excludes
     * hypotheses, prior assistant actions, checks, goals and generated summaries.
     * This prevents a previous model suggestion from becoming a prerequisite that
     * silently changes which clinical record is admitted.
     */
    private fun renderFactOnlyApplicabilityContext(
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String,
        supplementalEvidence: String = "",
        acceptedHistory: List<AndroChatMessage> = emptyList()
    ): String = buildString {
        append("FACT-ONLY CASE CONTEXT\n")
        val acceptedUsers = acceptedHistory.asReversed()
            .filter { it.role == "user" && it.content.isNotBlank() }
            .take(16)
            .reversed()
        if (acceptedUsers.isNotEmpty()) {
            append("VERBATIM ACCEPTED USER TURNS (explicit user statements; do not infer beyond them):\n")
            acceptedUsers.forEachIndexed { index, message ->
                append("U${index + 1}: ${message.content.trim().take(500)}\n")
            }
        }
        notebook.confirmedFacts.takeLast(16).forEachIndexed { index, fact ->
            if (fact.isNotBlank()) append("F${index + 1}: ${fact.trim()}\n")
        }
        notebook.userResponses.takeLast(12).forEachIndexed { index, response ->
            if (response.isNotBlank()) append("R${index + 1}: ${response.trim()}\n")
        }
        notebook.visualFindings.takeLast(12).forEachIndexed { index, finding ->
            if (finding.isNotBlank()) append("V${index + 1}: ${finding.trim()}\n")
        }
        if (
            visualObservation.isNotBlank() &&
            notebook.visualFindings.none {
                it.contains(visualObservation, ignoreCase = true)
            }
        ) {
            append("V_CURRENT: ${visualObservation.trim()}\n")
        }
        if (supplementalEvidence.isNotBlank()) {
            append("A_CURRENT (turn-scoped offline attachment evidence; not automatically confirmed): ")
            append(supplementalEvidence.take(1_800))
            append('\n')
        }
        append("CURRENT USER TURN: ${current.trim()}")
    }.trim().take(6_500)

    /**
     * Full Notebook projection for Local reasoning/query planning. Epistemic labels
     * are explicit: hypotheses and prior suggestions are context, NOT facts.
     */
    private fun renderNotebookForKb(
        notebook: InvestigationNotebook,
        current: String,
        visualObservation: String
    ): String = buildString {
        append("ACTIVE SELECTED-KB INVESTIGATION NOTEBOOK\n")
        if (notebook.problem.isNotBlank()) append("P1 MAIN PROBLEM: ${notebook.problem.trim()}\n")
        if (notebook.currentGoal.isNotBlank()) append("G1 PREVIOUS/CURRENT GOAL: ${notebook.currentGoal.trim()}\n")

        if (notebook.confirmedFacts.isNotEmpty()) {
            append("CONFIRMED USER FACTS:\n")
            stableNotebookWindow(notebook.confirmedFacts, firstCount = 8, lastCount = 12)
                .forEachIndexed { i, fact -> append("F${i + 1}: ${fact.trim()}\n") }
        }
        if (notebook.userResponses.isNotEmpty()) {
            append("USER RESPONSES LINKED TO PRIOR QUESTIONS:\n")
            notebook.userResponses.takeLast(12).forEachIndexed { i, response -> append("R${i + 1}: ${response.trim()}\n") }
        }
        if (notebook.visualFindings.isNotEmpty()) {
            append("VERIFIED VISUAL/ATTACHMENT FINDINGS:\n")
            notebook.visualFindings.takeLast(12).forEachIndexed { i, finding -> append("V${i + 1}: ${finding.trim()}\n") }
        }
        if (notebook.checksPerformed.isNotEmpty()) {
            append("CHECKS ALREADY PERFORMED (CONTEXT, NOT NEW FACTS):\n")
            notebook.checksPerformed.takeLast(8).forEachIndexed { i, check -> append("C${i + 1}: ${check.trim()}\n") }
        }
        if (notebook.questionsAlreadyAsked.isNotEmpty()) {
            append("QUESTIONS ALREADY ASKED:\n")
            notebook.questionsAlreadyAsked.takeLast(12).forEachIndexed { i, q -> append("Q${i + 1}: ${q.trim()}\n") }
        }
        if (notebook.actionsSuggested.isNotEmpty()) {
            append("PREVIOUS ACTIONS/SUGGESTIONS (CONTEXT ONLY; NOT FACTS):\n")
            notebook.actionsSuggested.takeLast(8).forEachIndexed { i, action -> append("A${i + 1}: ${action.trim()}\n") }
        }
        val hypotheses = listOf(notebook.primaryTheory, notebook.secondaryTheory).filter { it.isNotBlank() }
        if (hypotheses.isNotEmpty()) {
            append("HYPOTHESES (NOT CONFIRMED FACTS):\n")
            hypotheses.forEachIndexed { i, hypothesis -> append("H${i + 1}: ${hypothesis.trim()}\n") }
        }
        if (notebook.rejectedTheories.isNotEmpty()) {
            append("REJECTED/RULED-OUT THEORIES:\n")
            notebook.rejectedTheories.takeLast(6).forEachIndexed { i, item -> append("X${i + 1}: ${item.trim()}\n") }
        }
        if (visualObservation.isNotBlank()) {
            append("V_CURRENT VERIFIED CAMERA OBSERVATION: ${visualObservation.trim()}\n")
        }
        append("CURRENT USER TURN: ${current.trim()}\n")
        append(
            "AUTHORITY RULE: F/R/V are case evidence from the user or verified observation. " +
                "H/A/C/Q are context only and must never be promoted to confirmed facts without new evidence."
        )
    }.trim().take(6_500)

}
