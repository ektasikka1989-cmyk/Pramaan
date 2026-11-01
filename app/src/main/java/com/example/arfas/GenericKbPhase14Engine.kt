package com.example.arfas

import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.sqrt

/**
 * Phase 1.4 search core, independent of Android and SQLite so the exact same logic can be
 * checked on a desktop against the real pack. The repository supplies rows (one sequence per
 * scan), embeddings, lexical ranks and metadata; this class does the rest:
 *
 *  literal-first variants -> one scan per round with per-variant candidate heaps (passage
 *  vectors and, when the pack has them, build-time likely-question vectors) -> mean-centred
 *  z-scores -> admission by semantic + full-text rank agreement or identifier -> rank-fusion
 *  order with relative quality penalties -> optional same-turn residual round -> clause
 *  coverage -> coverage-reserved order. The calibrated gate is used for clause coverage only.
 */
class GenericKbPhase14Engine<T>(
    private val profile: GenericKbCorpusProfile,
    private val gateZ: Float
) {
    companion object {
        private const val MIN_QUESTION_ROWS = 30L
        /** Semantic candidates per document sent to the re-ranker (document diversity). */
        const val MAX_RERANK_PER_DOCUMENT = 2
        /** Semantic candidates admitted to re-ranking before the per-document cap applies. */
        const val DENSE_UNCAPPED = 5
        /** Full-text hits of each rewritten search question that always reach the re-ranker. */
        const val VIEW_LEXICAL_RERANK = 15
        /** The re-ranker's own best passages kept within the first RAW_LEAD_WITHIN evidence places. */
        const val RAW_LEAD = 2
        const val RAW_LEAD_WITHIN = 4
        /** A higher-precedence passage leads the evidence only within this many logits of the best one. */
        const val LEAD_PRECEDENCE_MARGIN = 1.0f
        /** Evidence passages per document before other documents' evidence is preferred. */
        const val MAX_EVIDENCE_PER_DOCUMENT = 3
        /**
         * Pack-declared source precedence (knowledge_sources.precedence; 1 = primary). Re-ranked
         * passages get this many logits per precedence level above the lowest level among the
         * candidates. Measured on the 75-question set: +1 → primary sources lead 30/75 answers, +2 →
         * 42/75 with one GeM-specific question weakened, +3 → 48/75 but GeM-specific questions
         * start receiving unrelated rule-book pages. No effect when the pack declares no precedence.
         */
        const val PRECEDENCE_STEP_LOGITS = 2.0f
        /**
         * Fast re-rank (speedFastRerank): the pool's first FAST_RERANK_HEAD pairs (dense-best
         * first) are scored first; when the best is at least FAST_RERANK_MIN_BEST and leads the
         * second by FAST_RERANK_MIN_MARGIN, the rest (~0.5 s per pair on the phone) is skipped
         * and admitted with the head's lowest score, keeping head order exact and the evidence
         * set intact. Otherwise the whole pool is scored as before.
         */
        const val FAST_RERANK_HEAD = 8
        const val FAST_RERANK_MIN_BEST = 7.0f
        const val FAST_RERANK_MIN_MARGIN = 2.5f

        /**
         * Pure fast-path decision for host tests: how many tail pairs can be skipped (0 = score all).
         * [headScores] are the head's logits in pool order.
         */
        fun fastRerankSkipped(poolSize: Int, headScores: FloatArray): Int {
            if (poolSize <= FAST_RERANK_HEAD || headScores.size != FAST_RERANK_HEAD) return 0
            var top1 = Float.NEGATIVE_INFINITY
            var top2 = Float.NEGATIVE_INFINITY
            for (s in headScores) {
                if (s > top1) { top2 = top1; top1 = s } else if (s > top2) { top2 = s }
            }
            if (top1 < FAST_RERANK_MIN_BEST || top1 - top2 < FAST_RERANK_MIN_MARGIN) return 0
            return poolSize - FAST_RERANK_HEAD
        }
        /** [PRECEDENCE_STEP_LOGITS] and the most levels it counts, adjustable by the measurement harness. */
        @Volatile var precedenceStep = PRECEDENCE_STEP_LOGITS
        @Volatile var precedenceMaxLevels = Int.MAX_VALUE
        /** Best semantic candidates of the highest-precedence documents always sent to the re-ranker. */
        const val RESERVED_PRIMARY_CANDIDATES = 4
        /** A question word in at most this share of the pack's passages counts as its key term (precedence rule). */
        const val KEY_TERM_MAX_SHARE = 0.01
        /** Below this many passages a 1 % share is one or two passages: no reliable rare-word signal. */
        const val KEY_TERM_MIN_PACK = 500
        /**
         * Identifier matches sent to the re-ranker. A section number that many documents refer to
         * ("Rule 144" in 76 passages of the procurement pack) otherwise sends every mention: 94
         * re-ranker pairs and ~110 s on the phone for one question. Passages that define the section
         * go first, then primary sources, then semantic score; at most MAX_RERANK_PER_DOCUMENT each.
         */
        /** Best re-ranker logit below which abbreviation long forms are added to the question (see search()). */
        const val ALIAS_RERANK_BELOW = 2.0f
        const val MAX_RERANK_ADMISSIONS = 8
        /**
         * Most passages the re-ranker reads per search. Each costs ~0.5–1.5 s on the phone's CPU, and
         * the uncapped union of the lanes was 36–45 passages on the 5,390-passage ASHA pack (73.8 s
         * per search in the phone's self-test). Filled lane by lane (boundedRerankPool), so each
         * lane keeps its best candidates.
         */
        const val MAX_RERANK_PAIRS = 20
        /** [MAX_RERANK_PAIRS], adjustable by the measurement harness. */
        @Volatile var rerankPairLimit = MAX_RERANK_PAIRS
        /**
         * Passages that define the section the question names by number (the identifier opens a
         * heading or a body line, or holds the requested child) lead the evidence, at most this many,
         * best first. A passage that merely refers to that section follows in normal order.
         */
        const val DEFINITION_LEAD = 2
        /** Passages introducing the asked abbreviation that lead a short question's evidence. */
        const val ABBREVIATION_LEAD = 1
    }

    class Row<T>(
        val chunkId: String,
        val heading: String,
        val locator: String,
        val content: String,
        val sourceText: String,
        val vector: FloatArray,
        /**
         * Optional build-time supplemental vector: the centroid of likely questions this passage
         * answers. Searchable only; never shown or sent to the model.
         */
        val questionVector: FloatArray? = null,
        /** Document key (same as the corpus profile's: doc id, else source name); "" when unknown. */
        val documentKey: String = "",
        /**
         * The heading is a running header of its document (the same heading on many pages: a chapter
         * title or an OCR heading carried over pages it does not describe). Not shown to the re-ranker.
         */
        val runningHeading: Boolean = false,
        /** Built only for rows that are retained. */
        val payload: () -> T
    )

    /**
     * The first [limit] items of [ranked] with at most [perDocument] per document (document = the
     * source label before "@p"); when that leaves fewer than [limit], the skipped items are added
     * back in rank order. Ranking is never changed, only which items fill the slots.
     */
    private fun <E> perDocumentCap(
        ranked: List<E>, perDocument: Int, limit: Int, describe: (T) -> String,
        candidateOf: (E) -> Candidate<T>
    ): List<E> {
        val counts = HashMap<String, Int>()
        val kept = ArrayList<E>(); val skipped = ArrayList<E>()
        for (item in ranked) {
            if (kept.size >= limit) break
            val doc = describe(candidateOf(item).payload).substringBefore("@p").ifBlank { candidateOf(item).chunkId }
            val n = counts[doc] ?: 0
            if (n < perDocument) { kept += item; counts[doc] = n + 1 } else skipped += item
        }
        if (kept.size < limit) {
            val keptSet = kept.toHashSet()
            kept += ranked.filter { it !in keptSet && it in skipped }.take(limit - kept.size)
        }
        return kept
    }

    private fun perDocumentCap(ranked: List<Candidate<T>>, perDocument: Int, limit: Int, describe: (T) -> String): List<Candidate<T>> =
        perDocumentCap(ranked, perDocument, limit, describe) { it }

    /**
     * The passages the re-ranker reads: all of [lead] (the best semantic candidates), then the
     * search lanes in turn — each lane's next-best passage, round after round — until
     * [rerankPairLimit] passages. Every lane keeps its own best candidates; only each lane's tail
     * is left out.
     */
    private fun boundedRerankPool(lead: List<Candidate<T>>, lanes: List<List<Candidate<T>>>): List<Candidate<T>> {
        val limit = rerankPairLimit
        val out = LinkedHashMap<String, Candidate<T>>()
        lead.forEach { if (out.size < limit) out.putIfAbsent(it.chunkId, it) }
        val cursors = IntArray(lanes.size)
        var progressed = true
        while (out.size < limit && progressed) {
            progressed = false
            lanes.forEachIndexed { i, lane ->
                while (cursors[i] < lane.size && out.size < limit) {
                    val c = lane[cursors[i]++]
                    if (out.putIfAbsent(c.chunkId, c) == null) { progressed = true; break }
                }
            }
        }
        return out.values.toList()
    }

    /** The identifier matches worth re-ranking: see MAX_RERANK_ADMISSIONS. */
    private fun admissionsForRerank(
        candidates: Collection<Candidate<T>>, precedenceOf: (T) -> Int?, describe: (T) -> String
    ): List<Candidate<T>> {
        val admitted = candidates.filter { it.admission != null }
        if (admitted.size <= MAX_RERANK_ADMISSIONS) return admitted
        val ordered = admitted.sortedWith(
            compareByDescending<Candidate<T>> { it.admission!!.defines }
                .thenByDescending { it.admission!!.kind.rank }
                .thenBy { precedenceOf(it.payload) ?: Int.MAX_VALUE }
                .thenByDescending { it.laneZ.values.maxOrNull() ?: Float.NEGATIVE_INFINITY }
        )
        return perDocumentCap(ordered, MAX_RERANK_PER_DOCUMENT, MAX_RERANK_ADMISSIONS, describe)
    }

    /**
     * Pack-declared precedence: each re-ranked candidate gets PRECEDENCE_STEP_LOGITS per level above
     * the lowest declared level among these candidates (undeclared = lowest). No declared levels, or
     * all candidates on one level: no change.
     */
    private fun applyPrecedence(pool: List<Candidate<T>>, precedenceOf: (T) -> Int?, literal: String = "") {
        val levels = pool.map { precedenceOf(it.payload) }
        val declared = levels.filterNotNull()
        if (declared.isEmpty()) return
        val lowest = declared.max()
        // Precedence never lifts a passage that lacks the question's rarest word over passages that
        // have it: "what is ePBG" must not be answered from a primary source's APBG passage just
        // because that source ranks higher. The rarest word is learned from the pack (document
        // frequency); a passage "has" it in its text or in its source title (words or initials).
        val key = keyTerm(literal)
        val hasKey = pool.map { c -> key == null || candidateHas(c, key) }
        val keyFound = key != null && hasKey.any { it }
        pool.forEachIndexed { i, c ->
            val level = levels[i] ?: lowest
            c.precedenceBonus = if (keyFound && !hasKey[i]) 0f
            else precedenceStep * (lowest - level).coerceIn(0, precedenceMaxLevels)
        }
    }

    /**
     * The question's rarest meaningful word, when it is a rare word of the pack (in at most
     * [KEY_TERM_MAX_SHARE] of passages, e.g. an acronym such as "epbg"); null otherwise. Common
     * words ("bid", "create") never restrict precedence.
     */
    private fun keyTerm(literal: String): String? =
        GenericKbRetrievalPlan.anchors(literal, profile)
            .filter { !it.all(Char::isDigit) && profile.documentFrequency(it) > 0 }
            .minByOrNull { profile.documentFrequency(it) }
            ?.takeIf { profile.chunkCount >= KEY_TERM_MIN_PACK && profile.documentFrequency(it) <= profile.chunkCount * KEY_TERM_MAX_SHARE }

    private fun candidateHas(c: Candidate<T>, term: String): Boolean {
        if (term in GenericKbText.tokens(textOf(c))) return true
        val source = candidateSource[c.chunkId].orEmpty()
        val sourceTokens = GenericKbText.tokens(source)
        if (term in sourceTokens) return true
        if (term.length !in 2..8 || !term.all(Char::isLetter)) return false
        return sourceTokens.filter { it.firstOrNull()?.isLetter() == true }.joinToString("") { it.take(1) }.contains(term)
    }

    class Candidate<T>(
        val chunkId: String,
        val payload: T,
        val admission: GenericKbIdentifierLane.Admission?,
        val rawCos: Float,
        val metadataCos: Float
    ) {
        val laneZ: MutableMap<GenericKbRetrievalPlan.Lane, Float> = HashMap()
        val clauseZ: MutableMap<String, Float> = HashMap()
        /** 1-based semantic rank among this turn's semantic candidates; null when not one. */
        var denseRank: Int? = null
        /** True when the likely-question vector, not the passage vector, gave the best literal score. */
        var viaQuestions: Boolean = false
        /** Cross-encoder relevance logit when the re-ranker scored this passage. */
        var rerankScore: Float? = null
        /** Pack-declared precedence bonus (logits), added to the re-ranked score. */
        var precedenceBonus: Float = 0f
    }

    data class ClauseState(val clauseId: String, val state: GenericKbCoverageState, val note: String = "")

    class Result<T>(
        val ordered: List<Pair<Candidate<T>, Float>>,
        val reservedCount: Int,
        val clauseIdsByChunk: Map<String, Set<String>>,
        val clauseStates: List<ClauseState>,
        val notesByChunk: Map<String, String>,
        val topSimilarity: Float,
        val trace: String,
        /** Best cross-encoder relevance among this turn's candidates; null when the re-ranker did not run. */
        val bestRerank: Float? = null
    )

    /**
     * @param scanRows produces one pass over the pack (called once per round).
     * @param embed embeds a question text ("query: " is added here).
     * @param lexicalRanks FTS/BM25 rank by chunk id for the given text.
     * @param metadataCos lifecycle/date/source adjustments in cosine units for a retained row.
     * @param rerank optional cross-encoder: relevance logits for (question, passages); null result
     *   or null function -> rank-agreement admission is used instead.
     */
    suspend fun search(
        literal: String,
        clauses: List<GenericKbClause>,
        pool: Int,
        scanRows: () -> Sequence<Row<T>>,
        embed: suspend (String) -> FloatArray?,
        lexicalRanks: (String) -> Map<String, Int>,
        metadataCos: (T, GenericKbIdentifierLane.Admission?) -> Float,
        clock: () -> Long = System::currentTimeMillis,
        describe: (T) -> String = { "" },
        rerank: (suspend (String, List<String>) -> FloatArray?)? = null,
        /** False when the pack's vectors do not match the phone's embedder: dense lanes are skipped. */
        denseEnabled: Boolean = true,
        /** Pack-declared precedence of a passage's document (1 = primary); null = not declared. */
        precedenceOf: (T) -> Int? = { null },
        /** Extra search views, e.g. the question rewritten into a self-contained search question. */
        extraViews: List<String> = emptyList()
    ): Result<T>? {
        val started = clock()
        val literalText = literal.trim()
        val size = pool.coerceAtLeast(8)
        if (!denseEnabled) {
            return lexicalOnly(literalText, clauses, size, scanRows, lexicalRanks, metadataCos, clock, describe, rerank, started, "dense_disabled", precedenceOf)
        }
        // Semantic candidate depth grows with the pack (square root of its size), never below the pool.
        val depth = maxOf(size, kotlin.math.ceil(sqrt(profile.chunkCount.toDouble())).toInt())
        val singleClause = clauses.singleOrNull()?.id

        val spelling = GenericKbRetrievalPlan.spellingVariant(literalText, profile)
        val expansions = profile.expansions(literalText)
        val aliasText = expansions.joinToString(" ")
        // A short question about an abbreviation ("full form of aon", "what is aon"): the passage
        // that introduces it, "Acceptance of Necessity (AoN)" or a glossary line "AoN Acceptance of
        // Necessity", leads the evidence, so the answer states the pack's own full form and source.
        val introducesAbbreviation: Regex? = if (expansions.isEmpty() ||
            !profile.isAboutTerm(literalText)) null else {
            val words = Regex("[A-Za-z][A-Za-z0-9]{1,9}").findAll(literalText).map { Regex.escape(it.value) }.toList()
            val forms = expansions.map { lf -> lf.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } }
            val alternatives = words.flatMap { w -> forms.flatMap { lf -> listOf("$lf\\s*\\(\\s*$w\\s*\\)", "\\b$w\\s*[-–:=]?\\s*$lf\\b") } }
            if (alternatives.isEmpty()) null else Regex(alternatives.joinToString("|"), RegexOption.IGNORE_CASE)
        }
        // What the re-ranker reads as "the question": the rewritten question first (when there is one),
        // then the user's own words. The long forms of their abbreviations are added only when the
        // re-ranker finds no convincing passage for the words alone (see the re-rank step): on the
        // 75 questions, always adding them pulled generic manual pages above the right ones.
        rerankQuestion = buildString {
            extraViews.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { append(it.joinToString(" ") { v -> v.trim() }).append(' ') }
            append(literalText)
        }
        expandedRerankQuestion = if (expansions.isEmpty()) null else "$rerankQuestion (${expansions.joinToString("; ")})"
        val variants = buildList {
            add(GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.LITERAL, singleClause, literalText))
            if (clauses.size > 1) clauses.forEach { add(GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.CLAUSE, it.id, it.literal)) }
            if (spelling != null) add(GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.SPELLING, singleClause, spelling))
            extraViews.filter { it.isNotBlank() && !it.equals(literalText, ignoreCase = true) }
                .forEach { add(GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.REWRITE, singleClause, it)) }
            // The abbreviation's long form on its own finds rule text written in long form.
            if (aliasText.isNotBlank()) add(GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.ALIAS, singleClause, aliasText))
        }
        val embedded = variants.mapNotNull { v -> embed("query: ${v.text}")?.let { v to it } }
        if (embedded.firstOrNull()?.first?.lane != GenericKbRetrievalPlan.Lane.LITERAL) {
            // No query embedding (embedder unavailable): full-text retrieval still answers.
            return lexicalOnly(literalText, clauses, size, scanRows, lexicalRanks, metadataCos, clock, describe, rerank, started, "no_query_embedding", precedenceOf)
        }

        val identifiers = GenericKbIdentifierLane.parseQuery(literalText, profile.structure)
        val keys = identifiers.filter { it.isKey }
        val anchors = GenericKbRetrievalPlan.anchors(literalText, profile)
        // Full-text ranks come from the literal question. Spelling-corrected text only adds passages
        // the literal did not find, ranked after them, so a wrong "correction" of a word in another
        // language (e.g. romanised Hindi) cannot push literal matches out.
        val lexical = HashMap(lexicalRanks(literalText))
        if (spelling != null) {
            val offset = lexical.values.maxOrNull() ?: 0
            lexicalRanks(spelling).forEach { (id, rank) -> lexical.putIfAbsent(id, offset + rank) }
        }
        // The model's rewritten search questions (one per thing asked) rank alongside the literal's own
        // hits: the n-th hit of a rewrite counts as rank n, so each part's best full-text hits reach the
        // re-ranker (appended after all literal hits, a second part's best passage never did).
        val viewLexicalTop = HashSet<String>()
        extraViews.filter { it.isNotBlank() }.forEach { view ->
            lexicalRanks(view).forEach { (id, rank) ->
                lexical[id] = minOf(lexical[id] ?: rank, rank)
                if (rank <= VIEW_LEXICAL_RERANK) viewLexicalTop += id
            }
        }
        // Short question about an abbreviation: also search the long form together with the abbreviation,
        // which full-text ranking answers with the passages that introduce it.
        if (introducesAbbreviation != null) {
            val offset = lexical.values.maxOrNull() ?: 0
            val asked = Regex("[A-Za-z][A-Za-z0-9]{1,9}").findAll(literalText).map { it.value }.toList()
            expansions.forEach { lf -> asked.forEach { w -> lexicalRanks("$lf $w").forEach { (id, rank) -> lexical.putIfAbsent(id, offset + rank) } } }
        }
        // Abbreviation long forms add passages after the literal's own hits.
        aliasText.ifBlank { null }?.let { view ->
            val offset = lexical.values.maxOrNull() ?: 0
            lexicalRanks(view).forEach { (id, rank) -> lexical.putIfAbsent(id, offset + rank) }
        }

        val rows = LinkedHashMap<String, Candidate<T>>()
        val denseMembers = HashSet<String>()
        val literalSd = scan(scanRows(), embedded, keys, anchors, lexical.keys, depth, metadataCos, rows, denseMembers)
        fun assignDenseRanks() {
            rows.values.forEach { it.denseRank = null }
            rows.values.filter { it.chunkId in denseMembers }
                .sortedByDescending { it.laneZ.values.maxOrNull() ?: Float.NEGATIVE_INFINITY }
                .forEachIndexed { i, c -> c.denseRank = i + 1 }
        }
        assignDenseRanks()

        fun scored(c: Candidate<T>) = GenericKbRetrievalPlan.Scored(
            chunkId = c.chunkId,
            laneZ = c.laneZ,
            clauseZ = c.clauseZ,
            lexicalRank = lexical[c.chunkId],
            identifier = c.admission,
            penalty = profile.penaltyFor(c.chunkId),
            metadataZ = c.metadataCos / literalSd,
            denseRank = c.denseRank,
            rerankScore = c.rerankScore
        )
        var reranked = false
        fun ranked(): List<Pair<Candidate<T>, Float>> {
            val ordered = rows.values
                .map { it to scored(it) }
                .filter { (_, s) -> GenericKbRetrievalPlan.admitted(s, reranked) }
                .map { (c, s) -> c to GenericKbRetrievalPlan.fused(s) + (if (reranked) c.precedenceBonus else 0f) }
                .sortedByDescending { it.second }
            val lead = (ordered.filter { (c, _) -> c.admission?.defines == true }.take(DEFINITION_LEAD) +
                (introducesAbbreviation?.let { rx ->
                    // From every candidate (not only admitted ones): a glossary line scores low semantically.
                    rows.values.filter { rx.containsMatchIn(textOf(it).substringAfter('\n')) }
                        .sortedWith(compareBy<Candidate<T>> { lexical[it.chunkId] ?: Int.MAX_VALUE })
                        .take(ABBREVIATION_LEAD).map { it to (ordered.firstOrNull { o -> o.first.chunkId == it.chunkId }?.second ?: 0f) }
                }
                    ?: emptyList())).distinctBy { it.first.chunkId }
            if (lead.isEmpty()) return ordered
            val leadIds = lead.mapTo(HashSet()) { it.first.chunkId }
            return lead + ordered.filter { it.first.chunkId !in leadIds }
        }

        // Round 2 only when the first evidence leaves meaningful literal words uncovered.
        var residual: String? = null
        val first = ranked()
        if (first.isNotEmpty()) {
            residual = GenericKbRetrievalPlan.residualVariant(
                literalText,
                first.take(4).map { (c, _) -> textOf(c) },
                profile
            )
            residual?.let { r ->
                embed("query: $r")?.let { v ->
                    val variant = GenericKbRetrievalPlan.Variant(GenericKbRetrievalPlan.Lane.RESIDUAL, singleClause, r)
                    // The residual words get their own full-text ranks, so agreement is judged on them too.
                    lexicalRanks(r).forEach { (id, rank) -> lexical[id] = minOf(lexical[id] ?: rank, rank) }
                    scan(scanRows(), listOf(variant to v), emptyList(), anchors, lexical.keys, depth, metadataCos, rows, denseMembers)
                    assignDenseRanks()
                }
            }
        }

        // Cross-encoder pass: the question read together with the best candidates by semantic +
        // full-text rank fusion (plus identifier matches). Its relevance logit decides admission
        // and order; when it is unavailable, rank agreement stays in force.
        var rerankInfo = "rr=off"
        if (rerank != null) {
            // Union, not a fused cut: each lane contributes its own best candidates, so a passage
            // that only one lane finds (e.g. a definition found by full text) still reaches the
            // re-ranker. Sizes follow the evidence budget (pool) and the full-text lane's own hit count.
            // Semantic candidates are document-diverse: at most MAX_RERANK_PER_DOCUMENT passages of one
            // document, walking further down the semantic ranking instead. A short manual that repeats
            // a topic on every page otherwise fills every slot and a long rule book's single relevant
            // passage never reaches the re-ranker. Same count, so no extra re-ranking cost.
            val denseRanked = rows.values.filter { it.denseRank != null }.sortedBy { it.denseRank }
            // The very best semantic candidates go in whatever their document (the question may be
            // about one guide's topic: its 3rd-best page was excluded by the cap and held the answer).
            val denseTop = denseRanked.take(DENSE_UNCAPPED)
            val denseDiverse = (denseTop + perDocumentCap(denseRanked, MAX_RERANK_PER_DOCUMENT, size, describe)).distinctBy { it.chunkId }
            // Pack-declared primary sources: their best semantic candidates always reach the re-ranker,
            // which then judges them like any other passage.
            val primaryTier = denseRanked.mapNotNull { precedenceOf(it.payload) }.minOrNull()
            val primaryReserved = if (primaryTier == null) emptyList() else perDocumentCap(
                denseRanked.filter { precedenceOf(it.payload) == primaryTier }, MAX_RERANK_PER_DOCUMENT, RESERVED_PRIMARY_CANDIDATES, describe
            )
            // Full-text hits: the best `size` (same budget as the semantic side). Measured on the
            // 75 questions: same results as scoring every hit, 36 % fewer re-ranker pairs.
            val lexicalTop = rows.values.filter { lexical[it.chunkId] != null }.sortedBy { lexical[it.chunkId] }.take(size)
            // Each rewritten search question (one per thing asked) brings its own best full-text hits.
            val viewTop = rows.values.filter { it.chunkId in viewLexicalTop }.sortedBy { lexical[it.chunkId] ?: Int.MAX_VALUE }
            val poolForRerank = boundedRerankPool(
                lead = denseTop,
                lanes = listOf(admissionsForRerank(rows.values, precedenceOf, describe), lexicalTop, denseDiverse, primaryReserved, viewTop)
            )
            var scores: FloatArray? = null
            var fastSkipped = 0
            if (ArogyaFlags.speedFastRerank && poolForRerank.size > FAST_RERANK_HEAD) {
                val head = poolForRerank.take(FAST_RERANK_HEAD)
                val headScores = runCatching { rerank(rerankQuestion, head.map { textOf(it) }) }.getOrNull()
                if (headScores != null && headScores.size == head.size &&
                    fastRerankSkipped(poolForRerank.size, headScores) > 0
                ) {
                    // Decisive top: the head keeps its exact scores; the unscored tail is admitted
                    // with the head's lowest score (order after the head, evidence set intact).
                    val floor = headScores.minOrNull() ?: 0f
                    poolForRerank.forEachIndexed { i, c -> c.rerankScore = if (i < head.size) headScores[i] else floor }
                    fastSkipped = poolForRerank.size - head.size
                }
            }
            if (fastSkipped == 0) {
                scores = runCatching { rerank(rerankQuestion, poolForRerank.map { textOf(it) }) }.getOrNull()
                val expanded = expandedRerankQuestion
                if (scores != null && scores.size == poolForRerank.size && expanded != null && (scores.maxOrNull() ?: 0f) < ALIAS_RERANK_BELOW) {
                    // Weak match for the user's own words: read the question again with the long forms of
                    // its abbreviations; each passage keeps its better score.
                    val second = runCatching { rerank(expanded, poolForRerank.map { textOf(it) }) }.getOrNull()
                    if (second != null && second.size == scores.size) {
                        val first = scores
                        scores = FloatArray(first.size) { maxOf(first[it], second[it]) }
                        if ((second.maxOrNull() ?: Float.NEGATIVE_INFINITY) > (first.maxOrNull() ?: Float.NEGATIVE_INFINITY)) rerankQuestion = expanded
                    }
                }
            }
            if (fastSkipped > 0) {
                applyPrecedence(poolForRerank, precedenceOf, literalText)
                reranked = true
                rerankInfo = "rr=fast:n=${poolForRerank.size}:scored=$FAST_RERANK_HEAD:skipped=$fastSkipped:best=${"%.2f".format(Locale.US, poolForRerank.maxOfOrNull { it.rerankScore ?: Float.NaN } ?: Float.NaN)}"
            } else if (scores != null && scores.size == poolForRerank.size) {
                poolForRerank.forEachIndexed { i, c -> c.rerankScore = scores[i] }
                applyPrecedence(poolForRerank, precedenceOf, literalText)
                reranked = true
                rerankInfo = "rr=on:n=${scores.size}:best=${"%.2f".format(Locale.US, scores.maxOrNull() ?: Float.NaN)}"
            } else {
                rerankInfo = "rr=failed"
            }
        }

        // The same text is often published in several documents (FAQ repeated in manuals, amended
        // copies). Keep its best-ranked copy only, so duplicates do not crowd out other evidence.
        val seenText = HashSet<String>()
        fun bestRerankScore(): Float? = if (reranked) rows.values.mapNotNull { it.rerankScore }.maxOrNull() else null
        val deduped = ranked().filter { (c, _) ->
            val key = textOf(c).substringAfter('\n').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(400)
            key.isEmpty() || seenText.add(key)
        }
        // At most MAX_EVIDENCE_PER_DOCUMENT passages of one document in the evidence, in rank order;
        // topped up from the same documents only when no other document has admitted evidence.
        val capped = perDocumentCap(deduped, MAX_EVIDENCE_PER_DOCUMENT, size, describe) { it.first }
        // Precedence orders the evidence, but never pushes the passages the re-ranker itself judged
        // most relevant out of the first places: the best RAW_LEAD of them are kept within the first
        // RAW_LEAD_WITHIN (a GeM-specific question answered only by a GeM guide must still see it).
        val final = if (!reranked) capped else {
            val rawBest = deduped.filter { it.first.rerankScore != null }
                .sortedByDescending { it.first.rerankScore!! }.take(RAW_LEAD)
            val missing = rawBest.filter { b -> capped.take(RAW_LEAD_WITHIN).none { it.first.chunkId == b.first.chunkId } }
            val placed = if (missing.isEmpty()) capped else {
                val rest = capped.filter { c -> missing.none { it.first.chunkId == c.first.chunkId } }
                (rest.take(RAW_LEAD_WITHIN - missing.size) + missing + rest.drop(RAW_LEAD_WITHIN - missing.size)).take(maxOf(size, capped.size))
            }
            // The first passage is the one a small answer model leans on most: it is the passage the
            // re-ranker judged most relevant, unless a passage from a higher-precedence document is
            // nearly as relevant (within LEAD_PRECEDENCE_MARGIN logits; an amended edition's rule).
            // A primary guideline's weaker passage ("VHSND: ASHA, ANM and AWW attend the session"
            // ahead of "An ASHA is a woman selected by her community…") no longer leads.
            val best = placed.filter { it.first.rerankScore != null }.maxByOrNull { it.first.rerankScore!! }
            val leader = best?.let { b ->
                val bestScore = b.first.rerankScore!!
                placed.filter { c -> c.first.rerankScore != null && c.first.rerankScore!! >= bestScore - LEAD_PRECEDENCE_MARGIN && c.first.precedenceBonus > b.first.precedenceBonus }
                    .maxByOrNull { it.first.precedenceBonus + it.first.rerankScore!! } ?: b
            }
            val first = placed.firstOrNull()?.first
            val keepFirst = first == null || first.admission?.defines == true ||
                (leader != null && (first.rerankScore ?: Float.NEGATIVE_INFINITY) >= leader.first.rerankScore!! - LEAD_PRECEDENCE_MARGIN)
            if (leader == null || keepFirst || first?.chunkId == leader.first.chunkId) placed
            else listOf(leader) + placed.filter { it.first.chunkId != leader.first.chunkId }
        }
        val admissions = rows.values.mapNotNull { it.admission?.kind }.groupingBy { it }.eachCount()
        fun traceText(extra: String) = buildString {
            append("literal=").append(literalText.take(80).replace('\n', ' '))
            append(" lanes=").append(embedded.joinToString(",") { it.first.lane.name }).append(if (residual != null) ",RESIDUAL" else "")
            append(" keys=").append(keys.joinToString(",") { "${it.label}:${it.parent}${it.childOrdinal?.let { c -> "#$c" } ?: ""}" })
            append(" values=").append(identifiers.filterNot { it.isKey }.joinToString(",") { it.surface })
            append(" admissions=").append(admissions.entries.joinToString(",") { "${it.key}:${it.value}" })
            append(" gate=").append("%.2f".format(Locale.US, gateZ))
            append(" lexical=").append(lexical.size)
            append(" residual=").append(residual?.take(60) ?: "-")
            append(" spelling=").append(spelling?.take(60) ?: "-")
            append(" ").append(rerankInfo)
            // Diagnostic only: the full-text lane's own top five, whether or not admitted.
            append(" fts=").append(
                lexical.entries.sortedBy { it.value }.take(5).joinToString(";") { (id, rank) ->
                    rows[id]?.let { nearMiss(it, rank, describe) } ?: "${id.take(10)}:z=-:lx=$rank:src=-:txt=-"
                }.ifEmpty { "-" }
            )
            append(extra)
            append(" elapsed=").append(clock() - started).append("ms")
        }
        val topSimilarity = rows.values.maxOfOrNull { it.rawCos } ?: 0f
        if (final.isEmpty()) {
            return Result(emptyList(), 0, emptyMap(),
                clauses.map { ClauseState(it.id, GenericKbCoverageState.UNSUPPORTED) }, emptyMap(), topSimilarity,
                traceText(" admitted=0 candidates=${rows.size} bestZ=${"%.2f".format(Locale.US, rows.values.maxOfOrNull { c -> c.laneZ.values.maxOrNull() ?: Float.NEGATIVE_INFINITY } ?: Float.NaN)} reason=${if (reranked) "below_rerank_floor" else "no_rank_agreement"} near=" +
                    rows.values.sortedByDescending { c -> if (reranked) c.rerankScore ?: Float.NEGATIVE_INFINITY else c.laneZ.values.maxOrNull() ?: Float.NEGATIVE_INFINITY }.take(5)
                        .joinToString(";") { c -> nearMiss(c, lexical[c.chunkId], describe) }), bestRerankScore())
        }

        fun clauseOf(id: GenericKbIdentifierLane.QueryIdentifier): String? =
            clauses.firstOrNull { it.literal.contains(id.surface) }?.id ?: clauses.firstOrNull()?.id
        val covers = LinkedHashMap<String, Set<String>>()
        for ((c, _) in final) {
            val covered = HashSet<String>()
            if (clauses.size <= 1) clauses.firstOrNull()?.let { covered += it.id }
            else {
                c.clauseZ.forEach { (clauseId, z) -> if (z >= gateZ) covered += clauseId }
                c.admission?.let { a -> clauseOf(a.identifier)?.let { covered += it } }
            }
            covers[c.chunkId] = covered
        }
        val notes = LinkedHashMap<String, String>()
        val clauseStates = clauses.map { clause ->
            val childKeys = keys.filter { clauseOf(it) == clause.id && it.childOrdinal != null }
            val childFound = childKeys.all { key ->
                final.any { (c, _) -> c.admission?.identifier == key && c.admission.kind.rank >= GenericKbIdentifierLane.Kind.HIERARCHICAL.rank }
            }
            val parentOnly = final.filter { (c, _) ->
                c.admission?.kind == GenericKbIdentifierLane.Kind.PARENT_ONLY && clauseOf(c.admission.identifier) == clause.id
            }
            val covered = final.any { (c, _) -> clause.id in covers[c.chunkId].orEmpty() }
            when {
                !covered -> ClauseState(clause.id, GenericKbCoverageState.UNSUPPORTED)
                childKeys.isNotEmpty() && !childFound && parentOnly.isNotEmpty() -> {
                    val key = parentOnly.first().first.admission!!.identifier
                    val labelled = listOfNotNull(key.label, key.parent).joinToString(" ")
                    val note = "The requested sub-part (${key.childOrdinal}) of $labelled was not located in the " +
                        "selected knowledge base; this passage is the parent section."
                    parentOnly.forEach { (c, _) -> notes[c.chunkId] = note }
                    ClauseState(clause.id, GenericKbCoverageState.PARTIAL, note)
                }
                else -> ClauseState(clause.id, GenericKbCoverageState.SUPPORTED)
            }
        }
        val bestPerPart = clauses.map { clause ->
            final.firstOrNull { (c, _) -> clause.id in covers[c.chunkId].orEmpty() }?.first?.chunkId
        } + final.filter { (c, _) -> c.admission != null }
            .sortedByDescending { (c, _) -> c.admission!!.kind.rank }
            .take(keys.size)
            .map { it.first.chunkId }
        val order = GenericKbRetrievalPlan.coverageOrder(final.map { it.first.chunkId }, bestPerPart)
        val byId = final.associateBy { it.first.chunkId }
        val ordered = order.mapNotNull { byId[it] }
        val reserved = bestPerPart.filterNotNull().distinct().size
        val trace = traceText(
            " clauses=" + clauseStates.joinToString(",") { "${it.clauseId}:${it.state}" } +
                " reserved=$reserved top=" + ordered.take(5).joinToString(";") { (c, f) ->
                    "${c.chunkId.take(10)}:f=${"%.2f".format(Locale.US, f)}:z=${"%.2f".format(Locale.US, c.laneZ.values.maxOrNull() ?: 0f)}" +
                        ":p=${"%.2f".format(Locale.US, profile.penaltyFor(c.chunkId))}:id=${c.admission?.let { "${it.kind}/${it.evidence.substringBefore(':')}" } ?: "-"}" +
                        ":lx=${lexical[c.chunkId] ?: "-"}:dr=${c.denseRank ?: "-"}" + (if (c.viaQuestions) ":via=q" else "") +
                        (c.rerankScore?.let { ":rr=${"%.2f".format(Locale.US, it)}" } ?: "") +
                        (if (c.precedenceBonus != 0f) ":prec=+${"%.1f".format(Locale.US, c.precedenceBonus)}" else "") +
                        describe(c.payload).takeIf { it.isNotBlank() }?.let { ":src=${it.replace(';', ',').replace(' ', '_').take(48)}" }.orEmpty()
                }
        )
        return Result(ordered, reserved, covers, clauseStates, notes, topSimilarity, trace, bestRerankScore())
    }

    /**
     * Full-text-only retrieval, used when dense retrieval must not be trusted (pack vectors do
     * not match the phone's embedder) or cannot run (no query embedding). The full-text lane, the
     * identifier lane and, if verified, the re-ranker still produce ordinary cited evidence, so the
     * selected knowledge base keeps answering. Every full-text hit and identifier match is evidence.
     */
    private suspend fun lexicalOnly(
        literalText: String,
        clauses: List<GenericKbClause>,
        size: Int,
        scanRows: () -> Sequence<Row<T>>,
        lexicalRanks: (String) -> Map<String, Int>,
        metadataCos: (T, GenericKbIdentifierLane.Admission?) -> Float,
        clock: () -> Long,
        describe: (T) -> String,
        rerank: (suspend (String, List<String>) -> FloatArray?)?,
        started: Long,
        reason: String,
        precedenceOf: (T) -> Int? = { null }
    ): Result<T> {
        val identifiers = GenericKbIdentifierLane.parseQuery(literalText, profile.structure)
        val keys = identifiers.filter { it.isKey }
        val anchors = GenericKbRetrievalPlan.anchors(literalText, profile)
        val lexical = lexicalRanks(literalText)
        val rows = LinkedHashMap<String, Candidate<T>>()
        for (row in scanRows()) {
            var admission: GenericKbIdentifierLane.Admission? = null
            if (keys.isNotEmpty()) {
                val view = GenericKbIdentifierLane.ChunkView(row.heading, row.locator, row.content, row.sourceText, row.documentKey)
                for (key in keys) {
                    val a = admitIn(key, view, anchors) ?: continue
                    if (admission == null || a.kind.rank > admission.kind.rank) admission = a
                }
            }
            if ((row.chunkId !in lexical && admission == null) || row.content.isBlank()) continue
            val payload = row.payload()
            candidateText[row.chunkId] = rerankText(row)
            candidateSource[row.chunkId] = row.sourceText
            rows[row.chunkId] = Candidate(row.chunkId, payload, admission, rawCos = 0f, metadataCos = metadataCos(payload, admission))
        }
        var rerankInfo = "rr=off"
        var reranked = false
        if (rerank != null && rows.isNotEmpty()) {
            // Same bounded pool as the full path: best full-text hits plus the best identifier matches.
            val pool = boundedRerankPool(
                lead = emptyList(),
                lanes = listOf(admissionsForRerank(rows.values, precedenceOf, describe), rows.values.filter { lexical[it.chunkId] != null }.sortedBy { lexical[it.chunkId] }.take(size * 2))
            )
            val scores = runCatching { rerank(rerankQuestion.ifBlank { literalText }, pool.map { textOf(it) }) }.getOrNull()
            if (scores != null && scores.size == pool.size) {
                pool.forEachIndexed { i, c -> c.rerankScore = scores[i] }
                applyPrecedence(pool, precedenceOf, literalText)
                reranked = true
                rerankInfo = "rr=on:n=${scores.size}:best=${"%.2f".format(Locale.US, scores.maxOrNull() ?: Float.NaN)}"
            } else rerankInfo = "rr=failed"
        }
        fun score(c: Candidate<T>): Float {
            val id = GenericKbRetrievalPlan.identifierBonus(c.admission)
            val penalty = profile.penaltyFor(c.chunkId)
            return if (reranked) (c.rerankScore ?: Float.NEGATIVE_INFINITY) + c.precedenceBonus + id - 0.5f * penalty
            else GenericKbRetrievalPlan.rankFusion(null, lexical[c.chunkId]) + id - penalty
        }
        val seenText = HashSet<String>()
        val scoredRows = rows.values.filter { !reranked || it.rerankScore != null }
            .map { it to score(it) }.sortedByDescending { it.second }
        val leadRows = scoredRows.filter { (c, _) -> c.admission?.defines == true }.take(DEFINITION_LEAD)
        val leadIds = leadRows.mapTo(HashSet()) { it.first.chunkId }
        val final = (leadRows + scoredRows.filter { it.first.chunkId !in leadIds })
            .filter { (c, _) ->
                val key = textOf(c).substringAfter('\n').lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(400)
                key.isEmpty() || seenText.add(key)
            }.take(maxOf(size, 8))
        val covers = LinkedHashMap<String, Set<String>>()
        val clauseHits = if (clauses.size > 1) clauses.associate { it.id to lexicalRanks(it.literal).keys } else emptyMap()
        for ((c, _) in final) {
            covers[c.chunkId] = if (clauses.size <= 1) clauses.map { it.id }.toSet()
            else clauses.filter { c.chunkId in clauseHits[it.id].orEmpty() }.map { it.id }.toSet()
        }
        val clauseStates = clauses.map { clause ->
            val covered = final.any { (c, _) -> clause.id in covers[c.chunkId].orEmpty() }
            ClauseState(clause.id, if (covered) GenericKbCoverageState.SUPPORTED else GenericKbCoverageState.UNSUPPORTED)
        }
        val trace = buildString {
            append("literal=").append(literalText.take(80).replace('\n', ' '))
            append(" mode=lexical_only reason=").append(reason)
            append(" keys=").append(keys.joinToString(",") { "${it.label}:${it.parent}${it.childOrdinal?.let { c -> "#$c" } ?: ""}" })
            append(" lexical=").append(lexical.size).append(' ').append(rerankInfo)
            append(" admitted=").append(final.size)
            append(" top=").append(final.take(5).joinToString(";") { (c, f) ->
                "${c.chunkId.take(10)}:f=${"%.2f".format(Locale.US, f)}:lx=${lexical[c.chunkId] ?: "-"}" +
                    (c.rerankScore?.let { ":rr=${"%.2f".format(Locale.US, it)}" } ?: "") +
                    describe(c.payload).takeIf { it.isNotBlank() }?.let { ":src=${it.replace(';', ',').replace(' ', '_').take(48)}" }.orEmpty()
            })
            append(" elapsed=").append(clock() - started).append("ms")
        }
        val bestRerank = if (reranked) rows.values.mapNotNull { it.rerankScore }.maxOrNull() else null
        return Result(final, 0, covers, clauseStates, emptyMap(), 0f, trace, bestRerank)
    }

    private fun textOf(c: Candidate<T>): String = candidateText[c.chunkId].orEmpty()

    /**
     * What the re-ranker reads for a passage. Packs store the heading again at the start of the
     * content, so "heading + content" read it twice and spent re-ranker tokens on it; and a running
     * header ("Community Level Care for a Malnourished Child" over eleven pages of sick-child danger
     * signs) made every page of the chapter look like the answer to "how to identify a malnourished
     * child". The heading is read once, and not at all when it is a running header.
     */
    private fun rerankText(row: Row<T>): String {
        val heading = row.heading.trim()
        val body = if (heading.isNotEmpty() && row.content.startsWith(heading)) row.content.substring(heading.length).trimStart() else row.content
        return when {
            heading.isEmpty() -> body
            row.runningHeading && body.isNotBlank() -> body
            else -> heading + "\n" + body
        }
    }

    /** Identifier admission, marked with whether the passage's document is numbered by that label. */
    private fun admitIn(
        key: GenericKbIdentifierLane.QueryIdentifier,
        view: GenericKbIdentifierLane.ChunkView,
        anchors: Set<String>
    ): GenericKbIdentifierLane.Admission? {
        val a = GenericKbIdentifierLane.admit(key, view, anchors) ?: return null
        val enumerated = profile.structure.enumerates(view.documentKey, key.label, key.parent)
        return if (enumerated) a else a.copy(enumerated = false)
    }

    /** Diagnostic only: a rejected candidate's score, lexical rank, source and opening words. */
    private fun nearMiss(c: Candidate<T>, lexicalRank: Int?, describe: (T) -> String): String {
        val z = "%.2f".format(Locale.US, c.laneZ.values.maxOrNull() ?: Float.NaN)
        val src = describe(c.payload).replace(';', ',').replace(' ', '_').take(48)
        val words = textOf(c).replace(Regex("\\s+"), " ").replace(';', ',').trim().take(60).replace(' ', '_')
        val rr = c.rerankScore?.let { ":rr=${"%.2f".format(Locale.US, it)}" } ?: ""
        return "${c.chunkId.take(10)}:z=$z:lx=${lexicalRank ?: "-"}$rr:src=$src:txt=$words"
    }
    private val candidateText = HashMap<String, String>()
    /** Question text given to the re-ranker for this search (see search()). */
    var rerankQuestion: String = ""
        private set
    private var expandedRerankQuestion: String? = null
    private val candidateSource = HashMap<String, String>()

    /** One pass; returns the literal lane's score standard deviation (for metadata z-units). */
    private fun scan(
        rows: Sequence<Row<T>>,
        variants: List<Pair<GenericKbRetrievalPlan.Variant, FloatArray>>,
        keys: List<GenericKbIdentifierLane.QueryIdentifier>,
        anchors: Set<String>,
        lexicalIds: Set<String>,
        depth: Int,
        metadataCos: (T, GenericKbIdentifierLane.Admission?) -> Float,
        existing: MutableMap<String, Candidate<T>>,
        denseMembers: MutableSet<String>
    ): Float {
        val scorer = GenericKbCenteredScorer(profile.mean, profile.meanNormSq, variants.map { it.second })
        // Passage and likely-question vectors are scored as two channels, each z-normalised over
        // its own distribution, so neither channel dominates by scale.
        val statsP = Array(variants.size) { GenericKbRunningStats() }
        val statsQ = Array(variants.size) { GenericKbRunningStats() }
        val heapsP = Array(variants.size) { PriorityQueue<Pair<Float, String>>(compareBy { it.first }) }
        val heapsQ = Array(variants.size) { PriorityQueue<Pair<Float, String>>(compareBy { it.first }) }
        val rawP = HashMap<String, FloatArray>()
        val rawQ = HashMap<String, FloatArray>()
        val out = FloatArray(variants.size)
        val outQ = FloatArray(variants.size)
        val literalVector = variants.first().second
        val literalNorm = sqrt(literalVector.fold(0f) { a, x -> a + x * x }).coerceAtLeast(1e-6f)

        fun offer(heap: PriorityQueue<Pair<Float, String>>, score: Float, id: String): Boolean {
            if (heap.size < depth) { heap += score to id; return true }
            if (score > heap.peek()!!.first) { heap.poll(); heap += score to id; return true }
            return false
        }

        for (row in rows) {
            scorer.score(row.vector, out)
            val qv = row.questionVector?.takeIf { it.size == row.vector.size }
            if (qv != null) scorer.score(qv, outQ)
            var retain = row.chunkId in lexicalIds || row.chunkId in existing
            for (i in variants.indices) {
                statsP[i].add(out[i])
                if (offer(heapsP[i], out[i], row.chunkId)) retain = true
                if (qv != null) {
                    statsQ[i].add(outQ[i])
                    if (offer(heapsQ[i], outQ[i], row.chunkId)) retain = true
                }
            }
            var admission: GenericKbIdentifierLane.Admission? = null
            if (keys.isNotEmpty()) {
                val view = GenericKbIdentifierLane.ChunkView(row.heading, row.locator, row.content, row.sourceText, row.documentKey)
                for (key in keys) {
                    val a = admitIn(key, view, anchors) ?: continue
                    if (admission == null || a.kind.rank > admission.kind.rank) admission = a
                }
                if (admission != null) retain = true
            }
            if (!retain || row.content.isBlank()) continue
            rawP[row.chunkId] = out.copyOf()
            if (qv != null) rawQ[row.chunkId] = outQ.copyOf()
            if (row.chunkId in existing) continue
            var dot = 0f
            var dd = 0f
            for (k in row.vector.indices) { dot += literalVector[k] * row.vector[k]; dd += row.vector[k] * row.vector[k] }
            val payload = row.payload()
            candidateText[row.chunkId] = rerankText(row)
            candidateSource[row.chunkId] = row.sourceText
            existing[row.chunkId] = Candidate(
                row.chunkId, payload, admission,
                rawCos = dot / (literalNorm * sqrt(dd).coerceAtLeast(1e-6f)),
                metadataCos = metadataCos(payload, admission)
            )
        }
        val keep = HashSet<String>()
        heapsP.forEach { heap -> heap.forEach { keep += it.second } }
        heapsQ.forEach { heap -> heap.forEach { keep += it.second } }
        denseMembers += keep
        for ((chunkId, scores) in rawP) {
            val c = existing[chunkId] ?: continue
            if (chunkId !in keep && chunkId !in lexicalIds && c.admission == null && c.laneZ.isEmpty()) continue
            val q = rawQ[chunkId]
            for (i in variants.indices) {
                val (variant, _) = variants[i]
                val zp = statsP[i].z(scores[i])
                // Too few enriched rows for their own distribution: measure on the passage one.
                val zq = when {
                    q == null -> Float.NEGATIVE_INFINITY
                    statsQ[i].count >= MIN_QUESTION_ROWS -> statsQ[i].z(q[i])
                    else -> statsP[i].z(q[i])
                }
                val z = maxOf(zp, zq)
                if (i == 0 && variant.lane == GenericKbRetrievalPlan.Lane.LITERAL) c.viaQuestions = zq > zp
                variant.clauseId?.let { id -> c.clauseZ[id] = maxOf(c.clauseZ[id] ?: z, z) }
                c.laneZ[variant.lane] = maxOf(c.laneZ[variant.lane] ?: z, z)
            }
        }
        existing.entries.removeAll { (id, c) -> id !in keep && id !in lexicalIds && c.admission == null && c.laneZ.isEmpty() }
        return statsP.first().sd()
    }
}
