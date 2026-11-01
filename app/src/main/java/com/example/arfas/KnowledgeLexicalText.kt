package com.example.arfas

import java.util.Locale

/** Domain-neutral lexical normalization shared by mobile retrieval and grounding. */
object KnowledgeLexicalText {
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "being", "by", "can",
        "could", "describe", "different", "do", "does", "explain", "for", "from", "have",
        "how", "i", "in", "into", "is", "it", "ka", "kaise", "ke", "ki", "kya", "me",
        "mein", "my", "of", "on", "or", "our", "par", "please", "should", "something",
        "tell", "that", "the", "their", "this", "through", "to", "used", "was", "we",
        "were", "what", "when", "where", "which", "who", "why", "with", "work", "would",
        "you", "your", "about", "after", "before", "aur", "hai"
    )

    private fun stem(token: String): String {
        return if (token.length > 3 && token.endsWith("s") && !token.endsWith("ss")) {
            token.dropLast(1)
        } else token
    }

    private fun expanded(raw: String): List<String> {
        val whole = stem(raw.lowercase(Locale.ROOT).trim('.', '_', '/', '-'))
        if (whole.length < 2) return emptyList()
        val components = whole.split(Regex("[._/-]+"))
            .map(::stem)
            .filter { it.length >= 2 }
        return if (components.size > 1) (components + whole).distinct() else listOf(whole)
    }

    fun tokens(text: String, dropStopWords: Boolean, maxTokens: Int = 1_200): List<String> {
        if (text.isBlank() || maxTokens <= 0) return emptyList()
        return Regex("""[\p{L}\p{N}][\p{L}\p{M}\p{N}._/-]*""")
            .findAll(text)
            .flatMap { expanded(it.value).asSequence() }
            .filter { !dropStopWords || it !in stopWords }
            .take(maxTokens)
            .toList()
    }
}
