package com.example.arfas

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Semantic recall for accepted investigation exchanges that are no longer inside
 * the local hot-history token budget. This is a passive context provider: it never
 * writes history, chooses actions, or alters Audio+Video routing.
 *
 * Exchange embeddings are cached so a 30–40 turn conversation does not repeatedly
 * re-embed the same old dialogue on every turn.
 */
class SessionConversationContextProvider(
    private val semanticEngine: SemanticSearchEngine
) : BaselineContextProvider {
    companion object {
        private const val TAG = "SessionRecall"
        private const val MIN_RECALL_SCORE = 0.70f
        private const val MAX_RECALLED_EXCHANGES = 2
        private const val MAX_RECALL_CHARS = 1_800
        private const val DEFAULT_HOT_HISTORY_TOKEN_BUDGET = 2_800
        private const val MAX_CACHE_ENTRIES = 192
    }

    private val exchangeEmbeddingCache = ConcurrentHashMap<String, FloatArray>()

    @Volatile
    private var hotHistoryTokenBudget: Int = DEFAULT_HOT_HISTORY_TOKEN_BUDGET

    fun updateHotHistoryTokenBudget(tokens: Int) {
        hotHistoryTokenBudget = tokens.coerceIn(768, 16_000)
        Log.i(TAG, "SESSION_RECALL_BUDGET tokens=$hotHistoryTokenBudget")
    }

    private fun estimateTokens(text: String): Int =
        ceil(text.length / 3.0).toInt().coerceAtLeast(1) + 8

    /**
     * Returns the first message index that still fits inside the same approximate
     * history token budget used by the local runtime. Older messages are eligible
     * for semantic recall; hot messages are never duplicated as evidence.
     */
    private fun hotStartIndex(history: List<AndroChatMessage>): Int {
        if (history.isEmpty()) return 0
        var index = history.size
        var used = 0

        while (index >= 2) {
            val assistant = history[index - 1]
            val user = history[index - 2]
            if (user.role != "user" || assistant.role != "assistant") break
            val pairTokens = estimateTokens(user.content) + estimateTokens(assistant.content)
            if (used > 0 && used + pairTokens > hotHistoryTokenBudget) break
            used += pairTokens
            index -= 2
        }
        return index
    }

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean {
        if (!semanticEngine.isReady || query.isBlank()) return false
        return hotStartIndex(acceptedHistory) >= 2
    }

    private suspend fun embeddingForExchange(text: String): FloatArray? {
        exchangeEmbeddingCache[text]?.let { return it }
        val embedding = semanticEngine.embed("passage: ${text.take(1_300)}") ?: return null
        if (exchangeEmbeddingCache.size >= MAX_CACHE_ENTRIES) {
            exchangeEmbeddingCache.keys.firstOrNull()?.let { exchangeEmbeddingCache.remove(it) }
        }
        exchangeEmbeddingCache[text] = embedding
        return embedding
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!semanticEngine.isReady || query.isBlank()) return null

        val cutoff = hotStartIndex(acceptedHistory)
        if (cutoff < 2) return null
        val older = acceptedHistory.take(cutoff)
        val queryEmbedding = semanticEngine.embed("query: ${query.trim()}") ?: return null

        data class Exchange(val text: String, val score: Float)
        val candidates = ArrayList<Exchange>()
        var i = 0
        while (i < older.size) {
            val user = older[i]
            if (user.role != "user") {
                i++
                continue
            }
            val assistant = older.getOrNull(i + 1)?.takeIf { it.role == "assistant" }
            val text = buildString {
                append("User: ").append(user.content.trim())
                if (assistant != null) append("\nAssistant: ").append(assistant.content.trim())
            }
            val embedding = embeddingForExchange(text)
            if (embedding != null) {
                val score = semanticEngine.cosineSimilarity(queryEmbedding, embedding)
                if (score >= MIN_RECALL_SCORE) candidates.add(Exchange(text, score))
            }
            i += if (assistant != null) 2 else 1
        }

        val selected = candidates.sortedByDescending { it.score }.take(MAX_RECALLED_EXCHANGES)
        if (selected.isEmpty()) return null

        val text = selected.joinToString("\n\n") { it.text }.take(MAX_RECALL_CHARS)
        Log.i(
            TAG,
            "SESSION_RECALL attached=${selected.size} eligibleMessages=$cutoff " +
                "best=${"%.4f".format(selected.first().score)} chars=${text.length}"
        )
        return BaselineContextAttachment(
            sourceId = "accepted_session_recall",
            text = text,
            priority = 40,
            attributes = mapOf("retrieval" to "semantic_accepted_history_recall")
        )
    }
}
