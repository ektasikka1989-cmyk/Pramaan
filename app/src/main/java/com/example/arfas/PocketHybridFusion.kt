package com.example.arfas

import kotlin.math.min

/**
 * Small, domain-neutral rank fusion used by the PocketSage-style KB retriever.
 * It combines independent dense and lexical ranks using standard reciprocal-rank
 * fusion (RRF), then removes only near-duplicate overlapping evidence.
 */
object PocketHybridFusion {
    private const val DEFAULT_RRF_K = 60
    private const val NEAR_DUPLICATE_JACCARD = 0.78f

    data class FusedId(
        val id: String,
        val score: Float,
        val denseRank: Int?,
        val lexicalRank: Int?
    )

    data class Evidence(
        val id: String,
        val score: Float,
        val docId: String,
        val pageNumber: Int?,
        val text: String
    )

    fun fuse(
        denseIds: List<String>,
        lexicalIds: List<String>,
        limit: Int,
        rrfK: Int = DEFAULT_RRF_K
    ): List<FusedId> {
        val denseRank = denseIds.distinct().withIndex().associate { it.value to it.index + 1 }
        val lexicalRank = lexicalIds.distinct().withIndex().associate { it.value to it.index + 1 }
        val ids = LinkedHashSet<String>(denseRank.size + lexicalRank.size).apply {
            addAll(denseRank.keys)
            addAll(lexicalRank.keys)
        }
        return ids.map { id ->
            val dr = denseRank[id]
            val lr = lexicalRank[id]
            var score = 0f
            if (dr != null) score += 1f / (rrfK + dr).toFloat()
            if (lr != null) score += 1f / (rrfK + lr).toFloat()
            FusedId(id, score, dr, lr)
        }.sortedWith(
            compareByDescending<FusedId> { it.score }
                .thenBy { it.denseRank ?: Int.MAX_VALUE }
                .thenBy { it.lexicalRank ?: Int.MAX_VALUE }
        ).take(limit.coerceAtLeast(1))
    }

    fun dedupe(candidates: List<Evidence>, limit: Int): List<Evidence> {
        if (candidates.isEmpty()) return emptyList()
        val selected = ArrayList<Evidence>(min(limit.coerceAtLeast(1), candidates.size))
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (selected.size >= limit.coerceAtLeast(1)) break
            val duplicate = selected.any { existing -> nearDuplicate(existing, candidate) }
            if (!duplicate) selected += candidate
        }
        return selected
    }

    private fun nearDuplicate(a: Evidence, b: Evidence): Boolean {
        val sameDoc = a.docId.isNotBlank() && a.docId == b.docId
        if (!sameDoc) return false
        val aTokens = tokenSet(a.text)
        val bTokens = tokenSet(b.text)
        if (aTokens.isEmpty() || bTokens.isEmpty()) return false
        val intersection = aTokens.count { it in bTokens }
        val union = aTokens.size + bTokens.size - intersection
        if (union <= 0) return false
        val jaccard = intersection.toFloat() / union.toFloat()
        val adjacentPages = when {
            a.pageNumber == null || b.pageNumber == null -> true
            else -> kotlin.math.abs(a.pageNumber - b.pageNumber) <= 1
        }
        return adjacentPages && jaccard >= NEAR_DUPLICATE_JACCARD
    }

    private fun tokenSet(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]{2,}")
            .findAll(text.lowercase())
            .map { it.value }
            .take(220)
            .toSet()
}
