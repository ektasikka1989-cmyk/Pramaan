package com.example.arfas

import kotlin.math.abs

/** Domain-neutral mobile port of the verified standalone exact-E5 hybrid benchmark. */
object RagFlowMobileRanker {
    private const val DENSE_WEIGHT = 0.35f
    private const val LEXICAL_WEIGHT = 0.25f
    private const val COVERAGE_WEIGHT = 0.20f
    private const val PHRASE_WEIGHT = 0.15f
    private const val MULTI_CHANNEL_WEIGHT = 0.05f
    private const val SEMANTIC_SUFFICIENCY_FLOOR = 0.845f
    private const val HYBRID_SUFFICIENCY_FLOOR = 0.45f
    private const val LEXICAL_PRESERVATION_FLOOR = 0.35f

    data class Candidate(
        val id: String,
        val vectorSimilarity: Float,
        val content: String,
        val title: String = "",
        val heading: String = "",
        val denseRank: Int? = 1,
        val lexicalRank: Int? = null,
        val lexicalScore: Float? = null
    )

    data class Ranked(
        val id: String,
        val score: Float,
        val termSimilarity: Float,
        val vectorSimilarity: Float,
        val lexicalSupport: Float = 0f,
        val denseSupported: Boolean = false,
        val lexicalSupported: Boolean = false
    )

    data class Evidence(
        val id: String,
        val score: Float,
        val docId: String,
        val pageNumber: Int?,
        val text: String,
        val heading: String = ""
    )

    fun dedupe(candidates: List<Evidence>): List<Evidence> {
        val selected = mutableListOf<Evidence>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (selected.none { isNearDuplicate(it.text, candidate.text) }) selected += candidate
        }
        return selected
    }

    fun rank(query: String, candidates: List<Candidate>): List<Ranked> {
        if (query.isBlank() || candidates.isEmpty()) return emptyList()
        val queryTokens = tokens(query, dropStopWords = true)
        if (queryTokens.isEmpty()) return emptyList()
        val queryTerms = queryTokens.toSet()
        val queryBigrams = bigrams(queryTokens)
        val lexicalValues = candidates.mapNotNull { it.lexicalScore }

        val scored = candidates.map { candidate ->
            val bodyTokens = tokens(candidate.content, dropStopWords = true)
            val structuralTokens = tokens(candidate.title + " " + candidate.heading, dropStopWords = true)
            val bodyCoverage = coverage(queryTerms, bodyTokens.toSet())
            val structuralCoverage = coverage(queryTerms, structuralTokens.toSet())
            val term = maxOf(bodyCoverage, structuralCoverage)
            val phrase = if (queryBigrams.isEmpty()) 0f else {
                val candidateBigrams = bigrams(bodyTokens) + bigrams(structuralTokens)
                queryBigrams.count { it in candidateBigrams }.toFloat() / queryBigrams.size.toFloat()
            }
            val lexical = minMax(candidate.lexicalScore, lexicalValues)
            val dense = if (candidate.denseRank != null) candidate.vectorSimilarity.coerceIn(0f, 1f) else 0f
            val multi = if (candidate.denseRank != null && candidate.lexicalRank != null) 1f else 0f
            Ranked(
                id = candidate.id,
                score = DENSE_WEIGHT * dense + LEXICAL_WEIGHT * lexical +
                    COVERAGE_WEIGHT * term + PHRASE_WEIGHT * phrase + MULTI_CHANNEL_WEIGHT * multi,
                termSimilarity = term,
                vectorSimilarity = candidate.vectorSimilarity,
                lexicalSupport = lexical,
                denseSupported = candidate.denseRank != null,
                lexicalSupported = candidate.lexicalRank != null
            )
        }.sortedWith(
            compareByDescending<Ranked> { it.score }
                .thenByDescending { it.vectorSimilarity }
                .thenByDescending { it.lexicalSupport }
        )

        // A relative ranker always has a winner. Require absolute semantic strength or
        // independent dense+lexical corroboration before exposing selected-KB evidence.
        val bestDense = candidates.filter { it.denseRank != null }
            .maxOfOrNull { it.vectorSimilarity } ?: 0f
        val bestHybrid = scored.filter { it.denseSupported && it.lexicalSupported }
            .maxOfOrNull { it.score } ?: 0f
        if (bestDense < SEMANTIC_SUFFICIENCY_FLOOR && bestHybrid < HYBRID_SUFFICIENCY_FLOOR) {
            return emptyList()
        }

        // Keep semantically ranked evidence broad, but do not let zero-overlap dense
        // neighbours displace passages that contain at least one meaningful query term.
        // If no lexical overlap exists at all, retain the strongest semantic candidates
        // so paraphrase-only questions remain answerable.
        val termSupported = scored.filter { it.termSimilarity > 0f || it.lexicalSupported }
        val admitted = if (termSupported.isNotEmpty()) termSupported else scored

        val lower = query.trim().lowercase()
        val procedural = lower.startsWith("how ") ||
            Regex("(?i)\\b(procedure|process|step(?:s)?|workflow|instructions?)\\b").containsMatchIn(lower)
        val broad = lower.startsWith("what are ") ||
            Regex("(?i)\\b(types?|kinds?|categories|classes|compare|comparison)\\b").containsMatchIn(lower)
        val factor = if (procedural) 0.35f else if (broad) 0.40f else 0.55f
        val floor = if (procedural || broad) 0.20f else 0.25f
        val threshold = maxOf(floor, (admitted.firstOrNull()?.score ?: return emptyList()) * factor)
        return admitted.filter { it.score >= threshold || it.lexicalSupport >= LEXICAL_PRESERVATION_FLOOR }
    }

    fun termSimilarity(query: String, candidate: Candidate): Float {
        val queryTerms = tokens(query, dropStopWords = true).toSet()
        if (queryTerms.isEmpty()) return 0f
        val body = tokens(candidate.content, dropStopWords = true).toSet()
        val structural = tokens(candidate.title + " " + candidate.heading, dropStopWords = true).toSet()
        return maxOf(coverage(queryTerms, body), coverage(queryTerms, structural))
    }

    /** Query-aware score for same-source neighboring pages; metadata alone cannot win. */
    fun contextScore(query: String, candidate: Candidate, baseScore: Float): Float {
        val queryTokens = tokens(query, dropStopWords = true)
        if (queryTokens.isEmpty()) return baseScore
        val candidateTokens = tokens(candidate.heading + " " + candidate.content, dropStopWords = true)
        val term = coverage(queryTokens.toSet(), candidateTokens.toSet())
        val queryPhrases = bigrams(queryTokens)
        val candidatePhrases = bigrams(candidateTokens)
        val phrase = if (queryPhrases.isEmpty()) 0f else
            queryPhrases.count { it in candidatePhrases }.toFloat() / queryPhrases.size.toFloat()
        return baseScore + 0.20f * term + 0.10f * phrase
    }

    /** Packs whole blocks by character budget while reserving independent sources. */
    fun packEvidence(candidates: List<Evidence>, charBudget: Int = 6_000): List<Evidence> {
        if (charBudget <= 0 || candidates.isEmpty()) return emptyList()
        val ordered = candidates.sortedByDescending { it.score }
        val packed = mutableListOf<Evidence>()
        val seenLocations = linkedSetOf<String>()
        val sourceChars = linkedMapOf<String, Int>()
        val sourceBudget = maxOf(1, (charBudget * 0.45f).toInt())
        var used = 0

        fun add(candidate: Evidence): Boolean {
            if (packed.any { isNearDuplicate(it.text, candidate.text) }) return false
            val remaining = charBudget - used
            if (remaining <= 0) return false
            if (candidate.text.length > remaining) {
                if (packed.isNotEmpty()) return false
                val bounded = candidate.copy(text = candidate.text.take(remaining))
                packed += bounded
                used += bounded.text.length
                return true
            }
            packed += candidate
            used += candidate.text.length
            sourceChars[candidate.docId] = (sourceChars[candidate.docId] ?: 0) + candidate.text.length
            seenLocations += locationKey(candidate)
            return true
        }

        ordered.forEach { candidate ->
            if ((sourceChars[candidate.docId] ?: 0) >= sourceBudget) return@forEach
            if (locationKey(candidate) in seenLocations) return@forEach
            add(candidate)
        }
        ordered.forEach { candidate ->
            if (used >= charBudget || packed.any { it.id == candidate.id }) return@forEach
            add(candidate)
        }
        return packed
    }

    fun estimatedPacketChars(candidates: List<Evidence>): Int =
        candidates.sumOf { it.text.length }

    /** True when the text independently covers the user's meaningful query terms. */
    fun supportsQuery(query: String, text: String): Boolean {
        val queryTerms = tokens(query, dropStopWords = true).toSet()
        if (queryTerms.isEmpty()) return true
        val textTerms = tokens(text, dropStopWords = true).toSet()
        return queryTerms.any { it in textTerms }
    }

    private fun locationKey(candidate: Evidence): String =
        "${candidate.docId}::${candidate.pageNumber ?: -1}::${candidate.heading.trim()}"

    private fun coverage(query: Set<String>, candidate: Set<String>): Float =
        if (query.isEmpty()) 0f else query.count { it in candidate }.toFloat() / query.size.toFloat()

    private fun minMax(value: Float?, values: List<Float>): Float {
        if (value == null || values.isEmpty()) return 0f
        val low = values.minOrNull() ?: return 0f
        val high = values.maxOrNull() ?: return 0f
        if (abs(high - low) < 0.000001f) return 1f
        return ((value - low) / (high - low)).coerceIn(0f, 1f)
    }

    private fun bigrams(words: List<String>): Set<Pair<String, String>> = words.zipWithNext().toSet()

    private fun isNearDuplicate(first: String, second: String): Boolean {
        val a = tokens(first, dropStopWords = false).take(300).toSet()
        val b = tokens(second, dropStopWords = false).take(300).toSet()
        val union = a + b
        if (union.isEmpty()) return false
        return a.intersect(b).size.toFloat() / union.size.toFloat() >= 0.92f
    }

    private fun tokens(text: String, dropStopWords: Boolean): List<String> =
        KnowledgeLexicalText.tokens(text, dropStopWords, maxTokens = 1_200)
}
