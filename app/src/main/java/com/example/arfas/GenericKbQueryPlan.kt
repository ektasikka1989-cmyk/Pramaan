package com.example.arfas

import java.text.Normalizer
import java.util.Locale

data class GenericKbClause(val id: String, val literal: String, val normalized: String)

data class GenericKbQueryPlan(
    val literalQuery: String,
    val resolvedQuery: String,
    val clauses: List<GenericKbClause>,
    val retrievalQueries: List<String>
)

enum class GenericKbCoverageState { SUPPORTED, PARTIAL, UNSUPPORTED, NEEDS_CLARIFICATION }

data class GenericKbClauseCoverage(
    val clauseId: String,
    val state: GenericKbCoverageState,
    val sourceIds: Set<String> = emptySet()
)

/** Corpus-neutral structural planning. The literal turn is never rewritten. */
object GenericKbQueryPlanner {
    fun plan(literalQuery: String, resolvedQuery: String): GenericKbQueryPlan {
        val literal = literalQuery.trim()
        val resolved = literal
        val split = splitClauses(resolved)
        // A splitter must never turn an identifier or number into separate retrieval
        // requests. If it does, retrieve the complete literal query instead.
        val clauseTexts = if (preservesRequiredLiterals(resolved, split)) split else listOf(resolved)
        val clauses = clauseTexts.mapIndexed { index, value ->
            GenericKbClause("C${index + 1}", value, normalize(value))
        }.ifEmpty { listOf(GenericKbClause("C1", resolved, normalize(resolved))) }
        val queries = linkedSetOf(resolved)
        clauses.forEach { clause ->
            queries += clause.literal
            clause.normalized.takeIf { it.isNotBlank() && it != clause.literal }?.let(queries::add)
        }
        return GenericKbQueryPlan(literal, resolved, clauses, queries.filter(String::isNotBlank))
    }

    private fun splitClauses(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        text.forEachIndexed { index, ch ->
            val boundary = when (ch) {
                '?', '!', ';', '\n', '।' -> true
                '.' -> isPeriodBoundary(text, index)
                else -> false
            }
            if (boundary) {
                text.substring(start, index).trim().trim(',', ':', '-')
                    .takeIf { it.length >= 2 }?.let(out::add)
                start = index + 1
            }
        }
        text.substring(start).trim().trim(',', ':', '-')
            .takeIf { it.length >= 2 }?.let(out::add)
        return out.flatMap(::splitList).distinct()
    }

    private val LIST_SEPARATOR = Regex("""\s*,\s*(?:(?:and|aur|or|ya|और|या)\s+)?|\s+(?:and|aur|or|ya|और|या)\s+""", RegexOption.IGNORE_CASE)
    private val ASKS_FOR_ITEMS = Regex("""(?i)^\s*(what|which|who|explain|define|describe|tell|list|give|meaning|full\s+form|kya|batao|bataiye|samjhao|क्या|बताइए|बताओ)\b""")
    private val HINDI_TAIL = Regex("""(?i)^(kya|kaun|ka|ki|ke|kise|matlab|means?|meaning|क्या|का|की|के|मतलब)\b""")
    private val COMPARISON = Regex("""(?i)\b(differen\w*|compar\w*|between|versus|vs\.?|antar|फ़र्क|अंतर)\b""")

    /**
     * A list of three or more short items is asked as one question per item:
     * "what is asha, anc and pnc" -> "what is asha" / "what is anc" / "what is pnc", and Hinglish
     * "asha, anc aur pnc kya hai" -> "asha kya hai" / "anc kya hai" / "pnc kya hai".
     * Comparisons ("difference between A, B and C") and two-item phrases stay whole.
     */
    internal fun splitList(clause: String): List<String> {
        if (!clause.contains(',') || COMPARISON.containsMatchIn(clause)) return listOf(clause)
        val parts = clause.split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 3) return listOf(clause)
        fun words(t: String) = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val middle = parts.subList(1, parts.size - 1)
        val itemWords = middle.maxOf { words(it).size }
        if (itemWords > 4) return listOf(clause)
        val first = words(parts.first())
        val last = words(parts.last())
        if (first.size < itemWords || last.size < itemWords) return listOf(clause)
        val prefix = first.dropLast(itemWords).joinToString(" ")
        val suffix = last.drop(itemWords).joinToString(" ")
        // Only a question that asks about several things: "what is A, B and C" / "A, B aur C kya hai".
        val asks = ASKS_FOR_ITEMS.containsMatchIn(clause) || (prefix.isBlank() && HINDI_TAIL.containsMatchIn(suffix))
        if (!asks) return listOf(clause)
        val items = listOf(first.takeLast(itemWords).joinToString(" ")) + middle + listOf(last.take(itemWords).joinToString(" "))
        return items.map { item -> listOf(prefix, item, suffix).filter { it.isNotBlank() }.joinToString(" ") }
    }

    /** A full stop after a decimal or short abbreviation is not a clause boundary. */
    private fun isPeriodBoundary(text: String, index: Int): Boolean {
        val previous = text.getOrNull(index - 1) ?: return false
        val next = text.getOrNull(index + 1)
        if (previous.isDigit() || next?.isDigit() == true) return false
        if (next != null && !next.isWhitespace()) return false
        var wordStart = index - 1
        while (wordStart >= 0 && text[wordStart].isLetter()) wordStart--
        val wordLength = index - wordStart - 1
        return wordLength > 3
    }

    private fun preservesRequiredLiterals(literal: String, clauses: List<String>): Boolean {
        if (clauses.isEmpty()) return false
        val required = Regex("""(?<![\p{L}\p{N}])(?:\p{N}+(?:[.,]\p{N}+)*|[\p{L}]{2,}[\p{L}\p{N}._/-]*\p{N}[\p{L}\p{N}._/-]*)(?![\p{L}\p{N}])""")
            .findAll(literal).map { it.value }.filter { it.isNotBlank() }.toSet()
        return required.all { token -> clauses.any { clause -> clause.contains(token, ignoreCase = true) } }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}._/-]+"), " ")
        .trim().split(Regex("\\s+")).joinToString(" ")
}

object GenericKbTokenBudget {
    /** Conservative cross-script estimate; Indic text and identifiers are denser than chars/4. */
    fun estimateTokens(chars: Int): Int = ((chars.coerceAtLeast(0) + 1) / 2).coerceAtLeast(1)

    fun desiredOutputTokens(query: String, clauses: Int, evidence: Int): Int {
        val queryTokens = estimateTokens(query.length).toLong()
        val evidenceTokens = estimateTokens(evidence).toLong()
        val clauseTokens = clauses.coerceAtLeast(1).toLong() * 128L
        return (192L + queryTokens + clauseTokens + evidenceTokens / 8L)
            .coerceAtLeast(256L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /** Derived from the active model window, not a document or topic-specific hit cap. */
    fun evidenceCharBudget(contextTokens: Int, query: String, clauses: Int): Int {
        val context = contextTokens.coerceAtLeast(1_024)
        val queryTokens = estimateTokens(query.length)
        val outputReserve = (context / 4 + clauses.coerceAtLeast(1) * 96).coerceIn(256, context / 2)
        val available = (context - 620 - queryTokens - outputReserve).coerceAtLeast(512)
        return (available * 1.55f).toInt().coerceAtLeast(900)
    }

    fun retrievalCandidatePool(evidenceCharBudget: Int, clauses: Int): Int {
        val perClause = (evidenceCharBudget / 700).coerceIn(8, 32)
        return (perClause * clauses.coerceAtLeast(1)).coerceAtLeast(8)
    }
}
