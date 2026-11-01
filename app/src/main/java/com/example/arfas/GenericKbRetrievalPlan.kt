package com.example.arfas

/**
 * Phase 1.4 query-side planning helpers: literal anchors, spelling variant, same-turn
 * residual variant, lane weights and coverage-aware packing order. Pure logic, testable
 * without Android. Nothing here uses conversation history or any vocabulary list.
 */
object GenericKbRetrievalPlan {

    enum class Lane(val weight: Float) {
        LITERAL(1.00f),
        CLAUSE(1.00f),
        SPELLING(0.92f),
        RESIDUAL(0.90f),
        /** The question rewritten by the answer model into a self-contained search question. */
        REWRITE(0.95f),
        /** Long forms of the question's abbreviations, from the pack's own text (GenericKbAliasIndex). */
        ALIAS(0.90f)
    }

    data class Variant(val lane: Lane, val clauseId: String?, val text: String)

    /** Meaningful literal words of the question, excluding the digits of identifiers/values. */
    fun anchors(literal: String, profile: GenericKbCorpusProfile): Set<String> =
        GenericKbText.tokens(literal).filter { profile.isMeaningful(it) }.toCollection(LinkedHashSet())

    /** The literal with unknown words replaced by their closest pack word; null when nothing changes. */
    fun spellingVariant(literal: String, profile: GenericKbCorpusProfile): String? {
        var changed = false
        val out = Regex("[\\p{L}][\\p{L}\\p{M}]*").replace(literal) { m ->
            val fixed = profile.correct(m.value.lowercase())
            if (fixed != null) { changed = true; fixed } else m.value
        }
        return out.takeIf { changed }
    }

    /**
     * Same-turn residual: meaningful literal words that the current top evidence does not
     * cover, plus the strongest covered word as the shared subject. Null when every word is
     * covered or nothing is covered (the literal lane already represents that case).
     */
    fun residualVariant(
        literal: String,
        evidenceTexts: List<String>,
        profile: GenericKbCorpusProfile
    ): String? {
        val words = anchors(literal, profile).toList()
        if (words.size < 2) return null
        val evidence = evidenceTexts.flatMap { GenericKbText.tokens(it) }.toHashSet()
        fun covered(w: String): Boolean {
            if (w in evidence) return true
            val stem = w.take(maxOf(4, w.length - 2))
            if (w.length >= 5 && evidence.any { it.startsWith(stem) }) return true
            val fixed = profile.correct(w)
            return fixed != null && fixed in evidence
        }
        val (have, missing) = words.partition(::covered)
        if (missing.isEmpty() || have.isEmpty()) return null
        val subject = have.maxByOrNull { profile.idf(it) } ?: return null
        return (missing + subject).joinToString(" ")
    }

    /**
     * Coverage-aware order: the best admitted chunk for every covered question part first
     * (in the order the parts were asked), then everything else by final rank.
     */
    fun coverageOrder(ranked: List<String>, bestPerPart: List<String?>): List<String> {
        val out = LinkedHashSet<String>()
        bestPerPart.filterNotNull().forEach { if (it in ranked) out += it }
        ranked.forEach { out += it }
        return out.toList()
    }

    /** One candidate after the scan, all scores in z-units of the query's own distribution. */
    data class Scored(
        val chunkId: String,
        val laneZ: Map<Lane, Float>,
        val clauseZ: Map<String, Float>,
        val lexicalRank: Int?,
        val identifier: GenericKbIdentifierLane.Admission?,
        val penalty: Float,
        val metadataZ: Float,
        /** 1-based position among the semantic candidates of this turn; null when not a semantic candidate. */
        val denseRank: Int? = null,
        /** Cross-encoder relevance logit when the re-ranker scored this passage; null otherwise. */
        val rerankScore: Float? = null
    ) {
        val bestLaneZ: Float get() = laneZ.values.maxOrNull() ?: Float.NEGATIVE_INFINITY
    }

    const val LEXICAL_MAX_BONUS = 0.6f

    fun identifierBonus(kind: GenericKbIdentifierLane.Kind?): Float = when (kind) {
        GenericKbIdentifierLane.Kind.EXACT -> 2.0f
        GenericKbIdentifierLane.Kind.HIERARCHICAL -> 1.8f
        GenericKbIdentifierLane.Kind.SECTION -> 1.2f
        GenericKbIdentifierLane.Kind.PARENT_ONLY -> 0.8f
        null -> 0f
    }

    /**
     * A section whose own heading field names the identifier is the section itself; a body
     * line that starts with the identifier (a manual or FAQ quoting it) gets half the support.
     */
    fun identifierBonus(admission: GenericKbIdentifierLane.Admission?): Float {
        val base = identifierBonus(admission?.kind)
        return when {
            admission?.kind != GenericKbIdentifierLane.Kind.SECTION -> base
            admission.evidence == "heading" -> base
            // A heading that only refers to the section is weaker than a body line that opens it.
            admission.evidence == GenericKbIdentifierLane.HEADING_MENTION -> base / 4f
            else -> base / 2f
        }
    }

    /** Reciprocal-rank constant (standard RRF). */
    const val RRF_K = 60f

    /**
     * Rank-fusion score of the semantic and full-text lanes, scaled so a passage ranked first
     * by both scores 2.0. A lane that did not rank the passage contributes nothing.
     */
    fun rankFusion(denseRank: Int?, lexicalRank: Int?): Float =
        (RRF_K + 1f) * ((denseRank?.let { 1f / (RRF_K + it) } ?: 0f) + (lexicalRank?.let { 1f / (RRF_K + it) } ?: 0f))

    /**
     * Re-ranker candidates are chosen dynamically: the semantic lane's best `pool` passages (pool
     * comes from the evidence budget) plus every full-text hit plus identifier matches. There is
     * no relevance floor: the re-ranker only re-orders, it never removes evidence.
     */

    /**
     * Order for the profile-free full-text fallback: by re-ranker score when available (ties by
     * full-text rank), otherwise by full-text rank. Returns indexes into the input lists.
     */
    fun lexicalFallbackOrder(lexicalRanks: List<Int>, rerankScores: FloatArray?): List<Int> =
        lexicalRanks.indices.sortedWith(
            if (rerankScores != null && rerankScores.size == lexicalRanks.size)
                compareByDescending<Int> { rerankScores[it] }.thenBy { lexicalRanks[it] }
            else compareBy { lexicalRanks[it] }
        )

    /**
     * Final rank. With the re-ranker: its relevance logit plus identifier support and metadata,
     * minus half the quality penalty. Without it: semantic + full-text rank fusion, identifier
     * support and metadata, minus the quality penalty.
     */
    fun fused(s: Scored): Float =
        if (s.rerankScore != null) s.rerankScore + identifierBonus(s.identifier) + s.metadataZ - 0.5f * s.penalty
        else rankFusion(s.denseRank, s.lexicalRank) + identifierBonus(s.identifier) + s.metadataZ - s.penalty

    /**
     * Evidence admission. A passage is admitted when the semantic lane and the full-text lane
     * independently rank it for this question (rank agreement), or when the identifier lane
     * admits it; when the verified cross-encoder re-ranker ran, its candidate set is admitted in
     * its order (re-order only, never a filter). A single semantic score above a cutoff is no longer sufficient on its own:
     * on real packs, unrelated questions reach the same top scores as real ones. The answer's
     * grounded quality/refusal check remains the hard rejection path after admission.
     */
    fun admitted(s: Scored, reranked: Boolean = false): Boolean = when {
        // When the re-ranker ran, its candidate set (semantic best + full-text hits + the best
        // identifier matches) is the evidence set, in its order. Nothing is dropped for a low score;
        // the grounded-answer check decides. An unscored passage cannot be ordered against scored ones.
        reranked -> s.rerankScore != null
        s.identifier != null -> true
        else -> s.denseRank != null && s.lexicalRank != null
    }
}
