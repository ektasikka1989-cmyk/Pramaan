package com.example.arfas

import java.util.Locale

/**
 * The answer model turns the user's question into (1) one self-contained search question in
 * the documents' own terms and (2) an arithmetic expression when the question gives amounts.
 *
 * "i am going to procure 20 acs at price of 42000 each what should be the epbg" →
 *   SEARCH: How much performance security (ePBG) should be taken for a contract?
 *   CALC: 20*42000
 *
 * The literal question is still searched; the rewrite is one more view, and the re-ranker reads
 * "rewrite + literal". The model never does the arithmetic (KbCalculator does). The prompt's
 * examples are deliberately from no particular KB, and the KB's own abbreviation long forms
 * (GenericKbAliasIndex) are given as hints so a small model can name the right term.
 */
data class GenericKbQuestionRewrite(
    val search: String,
    val calc: String?,
    /** One search question per thing asked, when the question asks two different things. */
    val parts: List<String> = listOf(search)
) {
    companion object {
        const val MAX_OUTPUT_TOKENS = 96
        private const val MAX_PARTS = 3
        /** Long questions often ask two things at once; the model splits them into one search each. */
        private const val LONG_QUESTION_WORDS = 12

        /** Structural signals that a rewrite can help: amounts to work out, or a long (often two-part) question. */
        fun worthRewriting(question: String): Boolean =
            mayNeedCalculation(question) || question.trim().split(Regex("\\s+")).size >= LONG_QUESTION_WORDS ||
                question.count { it == '?' } >= 2

        /**
         * Rewrite before searching: long or two-part questions, amounts, and questions with words the pack
         * never uses (Hindi words such as "kitna", "kab", "bukhar"; misspellings).
         */
        fun worthRewritingForSearch(question: String, unknownWords: Int): Boolean =
            // Short questions in the pack's own words ("what is gem", "bid security") are searched as
            // typed: rewriting them first cost one extra model call (2-3 s on the phone) for no measured
            // gain; when their own words find nothing convincing, the checked fallback rewrite still runs.
            worthRewriting(question) || unknownWords > 0

        /** Two or more numbers in the question: an amount may have to be worked out. */
        fun mayNeedCalculation(question: String): Boolean =
            Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(question).count() >= 2

        fun prompt(question: String, aliasHints: List<String>): String = buildString {
            appendLine("Rewrite the question for a document search. Answer with SEARCH and CALC lines only:")
            appendLine("The question may be very short, misspelled or mixed Hindi and English: understand what the user wants and write it in clear English.")
            appendLine("SEARCH: one short, complete question in formal document language (no numbers from the user's own case)")
            appendLine("If the question asks two different things, write two SEARCH lines, one for each thing.")
            appendLine("Keep the question's key words (for example limit, dose, value, age) and never add details the user did not ask about.")
            appendLine("CALC: the arithmetic for the amount the user describes, using only + - * / and numbers, or NONE")
            if (aliasHints.isNotEmpty()) appendLine("Terms used in these documents: " + aliasHints.joinToString("; "))
            CoreHealthFacts.hinglishHints(question).takeIf { it.isNotEmpty() }?.let { appendLine("Hindi words in this question: " + it.joinToString("; ")) }
            appendLine()
            appendLine("Question: we need 4 rooms for 3 nights at 2500 per night, what advance must be paid")
            appendLine("SEARCH: What advance payment is required for a booking?")
            appendLine("CALC: 4*3*2500")
            appendLine()
            appendLine("Question: room ka rent kitna dena hai")
            appendLine("SEARCH: How much rent must be paid for a room?")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: bachche ko bukhar me kya dena chahiye")
            appendLine("SEARCH: What should be given to a child with fever?")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: leave kab milti hai")
            appendLine("SEARCH: When can leave be granted, and under what conditions?")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: who can sanction leave for staff and how many days can be given at once")
            appendLine("SEARCH: Who is the competent authority to sanction leave?")
            appendLine("SEARCH: What is the maximum period of leave that can be sanctioned at one time?")
            appendLine("CALC: NONE")
            appendLine()
            append("Question: ").appendLine(question.trim().replace(Regex("\\s+"), " ").take(400))
            // Without this the 3B model often rewrote the examples again instead of the user's
            // question (every Hinglish question in the ASHA test came back with no rewrite).
            append("Write only the SEARCH and CALC lines for this last question.")
        }

        /** Parses the model output; null when no usable SEARCH line. */
        fun parse(output: String, question: String): GenericKbQuestionRewrite? {
            val all = output.lines().map { it.trim().trim('*', '`', ' ') }
            // When the model repeats the examples, its rewrite of the user's question follows the
            // line that repeats that question: read only what comes after it.
            val q = question.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            val echo = all.indexOfLast { l -> l.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").let { it.contains(q.take(60)) && q.length >= 8 } }
            val lines = if (echo >= 0 && echo < all.size - 1) all.drop(echo + 1) else all
            val searches = lines.mapNotNull { l ->
                Regex("^search\\s*\\d*\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            }.map { it.trim('"', '\'', ' ').take(240) }.filter { it.length >= 8 && it.any(Char::isLetter) }
                .distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_PARTS)
            // A small model that cannot work out the question copies an example ("What advance payment
            // is required for a booking?" for a question about zinc): such lines are not rewrites.
            .filterNot { copiesExample(it, question) }
            val search = searches.firstOrNull() ?: return null
            val calcRaw = lines.firstNotNullOfOrNull { l ->
                Regex("^calc\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            }
            // The expression may use only numbers the user actually wrote (no invented quantities).
            val calc = calcRaw?.takeUnless { it.uppercase(Locale.ROOT).startsWith("NONE") }
                ?.takeIf { KbCalculator.evaluate(it) != null && numbersFromQuestion(it, question) }
            return GenericKbQuestionRewrite(search, calc, searches)
        }

        private val EXAMPLE_SEARCHES = listOf(
            "What advance payment is required for a booking?",
            "How much rent must be paid for a room?",
            "What should be given to a child with fever?",
            "When can leave be granted, and under what conditions?",
            "Who is the competent authority to sanction leave?",
            "What is the maximum period of leave that can be sanctioned at one time?"
        )

        private fun contentWords(t: String): Set<String> =
            t.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()

        private val STOP = setOf(
            "what", "which", "who", "whom", "when", "where", "why", "how", "the", "and", "for", "are", "can", "does", "should",
            "must", "this", "that", "with", "from", "into", "about", "under", "kya", "hai", "hain", "kaise", "kitna", "kitne",
            "kab", "kaun", "kis", "kya", "karna", "chahiye", "dena", "milti", "milta", "hota", "hoti", "than", "there", "their"
        )

        /**
         * True when [search] is (nearly) one of the prompt's examples, or shares no content word with
         * a Latin-script question (a Devanagari question is checked against the examples only).
         */
        internal fun copiesExample(search: String, question: String): Boolean {
            val s = contentWords(search)
            val q = contentWords(question)
            fun fromQuestion(word: String) = q.any { qw -> qw == word || word.startsWith(qw.take(5)) || qw.startsWith(word.take(5)) }
            // Close to an example and adding nothing the user said: a copy.
            val example = EXAMPLE_SEARCHES.any { e ->
                val w = contentWords(e)
                w.isNotEmpty() && (s intersect w).size * 3 >= w.size * 2 && (s - w).none(::fromQuestion) && w.none(::fromQuestion)
            }
            if (example) return true
            // A Hinglish question ("navjaat shishu ko pehla doodh kab pilana chahiye") shares no words
            // with its correct English rewrite, so the shared-word test is for English questions only.
            val latin = question.count { it in 'a'..'z' || it in 'A'..'Z' } > question.count { it.code in 0x0900..0x097F }
            return latin && !CoreHealthFacts.isHinglish(question) && q.isNotEmpty() && s.none(::fromQuestion)
        }

        private fun numbers(text: String): List<String> =
            Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(text).map { java.math.BigDecimal(it.value.replace(",", "")).stripTrailingZeros().toPlainString() }.toList()

        internal fun numbersFromQuestion(expression: String, question: String): Boolean {
            val allowed = numbers(question).toSet()
            val used = numbers(expression)
            return used.isNotEmpty() && used.all { it in allowed || it == "100" }
        }
    }
}
