package com.example.arfas

import java.util.Locale

/**
 * Pramaan's proof check for a knowledge-base answer: every statement is matched to the passage that
 * supports it with the phone's multilingual cross-encoder (the same re-ranker that ranks search
 * results, so Hindi statements are checked against English passages too), and every number in a
 * statement must appear in that passage. The answer then carries "proof:<found>/<total>", shown as
 * "✓ 4 of 4 statements found in the sources"; statements found nowhere are removed when the rest
 * of the answer still stands.
 *
 * Without the re-ranker (not ready yet) word overlap decides, more strictly. Pure Kotlin: tested on
 * the JVM with the real re-ranker on real knowledge bases (see the gap-analysis doc).
 */
object AnswerProof {

    data class Statement(
        val text: String,
        /** Best re-ranker score over the passages (null when the word-overlap fallback decided). */
        val score: Float?,
        val passageId: String?,
        val numbersFound: Boolean,
        val supported: Boolean,
        /** The statement reverses the passage's limit ("more than 50,000" where the passage says "up to"). */
        val directionConflict: Boolean = false
    )

    data class Result(val statements: List<Statement>) {
        val total: Int get() = statements.size
        val supported: Int get() = statements.count { it.supported }
        val allSupported: Boolean get() = total > 0 && supported == total
    }

    /**
     * Re-ranker score at or above which a statement counts as found in a passage. Calibrated on the
     * ASHA and procurement packs (2026-10-23): 112/112 verbatim and 15/15 reworded statements (5 in
     * Hindi against English passages, lowest 1.6) pass; 10/12 wrong-but-on-topic statements fail.
     * It shows a statement matches the sources' content and numbers, not that its logic is right.
     */
    const val SUPPORT_SCORE = 1.5f
    /** Below this the statement is treated as found nowhere and may be removed. */
    const val REMOVE_SCORE = -3.0f
    private const val MIN_WORDS = 4

    private val CITATION = Regex("\\s*\\[(?:S?\\d+(?:\\s*[,–-]\\s*S?\\d+)*)]")
    private val SPLIT = KbReplyText.SENTENCE_SPLIT

    /** The answer's statements: sentences and list items, without citation marks, headings or lead-ins. */
    fun statements(answer: String): List<String> = answer
        .split(SPLIT)
        .map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }
        .map { it.replace(Regex("^\\d{1,2}[.)]\\s+"), "") }
        .map { it.replace(CITATION, "").replace("**", "").trim() }
        .filter { s ->
            val words = s.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
            words >= MIN_WORDS && !s.endsWith(":") && !s.startsWith("|") &&
                !s.startsWith("Source", ignoreCase = true) && !s.startsWith("स्रोत")
        }
        .distinct()

    private val NUM = Regex("\\d+(?:[.,]\\d+)*")
    private fun numbers(t: String): Set<String> = NUM.findAll(t).map { it.value.replace(",", "") }
        .filter { it.length >= 2 || it.toIntOrNull()?.let { n -> n >= 2 } == true }.toSet()

    private fun words(t: String): Set<String> =
        t.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 4 }.toSet()

    /** Passages read with each statement (see check). */
    const val PROOF_CANDIDATES = 2
    private const val UNSCORED = -99f

    suspend fun check(
        answer: String,
        passages: List<Pair<String, String>>,
        scorer: (suspend (String, List<String>) -> FloatArray?)?,
        /** Source labels ("Manual … 2025 — page 69"): numbers the answer takes from them are not claims. */
        sourceLabels: List<String> = emptyList()
    ): Result {
        val labelNumbers = sourceLabels.flatMap { numbers(it) }.toSet()
        val list = statements(answer)
        if (list.isEmpty() || passages.isEmpty()) return Result(emptyList())
        val texts = passages.map { it.second }
        val passageNumbers = texts.map { numbers(it) }
        val passageWords = texts.map { words(it) }
        val out = ArrayList<Statement>(list.size)
        for (s in list) {
            // The re-ranker reads each statement with its PROOF_CANDIDATES best passages by shared
            // words (the others keep a score that never wins): half the pairs, several seconds less on
            // the phone after every answer, and the passage a statement came from shares its words.
            val sw = words(s)
            val sNums = numbers(s)
            // Passages holding every number the statement states are always read too.
            val keep = if (texts.size <= PROOF_CANDIDATES) texts.indices.toSet()
                else (texts.indices.sortedByDescending { i -> (sw intersect passageWords[i]).size }.take(PROOF_CANDIDATES) +
                    (if (sNums.isEmpty()) emptyList() else texts.indices.filter { passageNumbers[it].containsAll(sNums) })).toSet()
            val scores = scorer?.let { sc ->
                val idx = texts.indices.filter { it in keep }
                runCatching { sc(s, idx.map { texts[it] }) }.getOrNull()?.takeIf { it.size == idx.size }?.let { part ->
                    FloatArray(texts.size) { i -> idx.indexOf(i).let { j -> if (j >= 0) part[j] else UNSCORED } }
                }
            }
            // "page 69", "pages 4–5" and numbers from the source's own title are where, not what.
            val nums = numbers(s.replace(Regex("(?i)\\bpages?\\s*\\d+(?:\\s*[–-]\\s*\\d+)?"), " ")) - labelNumbers
            if (scores != null && scores.size == texts.size) {
                // The best passage whose numbers cover the statement's numbers; else the best overall.
                val order = scores.indices.sortedByDescending { scores[it] }
                val withNumbers = order.firstOrNull { i -> passageNumbers[i].containsAll(nums) }
                val pick = withNumbers ?: order.first()
                val numbersFound = withNumbers != null
                val score = scores[pick]
                val conflict = numbersFound && directionConflict(s, texts[pick])
                out += Statement(s, score, passages[pick].first, numbersFound, numbersFound && !conflict && score >= SUPPORT_SCORE, conflict)
            } else {
                val w = words(s)
                val best = passageWords.indices.maxByOrNull { i -> if (w.isEmpty()) 0 else (w intersect passageWords[i]).size }
                val overlap = if (best == null || w.isEmpty()) 0.0 else (w intersect passageWords[best]).size.toDouble() / w.size
                val numbersFound = best != null && passageNumbers[best].containsAll(nums)
                val conflict = numbersFound && best != null && directionConflict(s, texts[best])
                out += Statement(s, null, best?.let { passages[it].first }, numbersFound, numbersFound && !conflict && overlap >= 0.6, conflict)
            }
        }
        return Result(out)
    }

    /**
     * The answer without statements found nowhere (score below [REMOVE_SCORE], or a number no passage
     * has), when at least half of the statements remain, with the proof of what remains. Null when
     * nothing should change.
     */
    fun prune(answer: String, result: Result): Pair<String, Result>? {
        val drop = result.statements.filter { st ->
            !st.supported && ((st.score != null && st.score < REMOVE_SCORE) || !st.numbersFound || st.directionConflict)
        }
        if (drop.isEmpty() || result.total - drop.size < maxOf(1, (result.total + 1) / 2)) return null
        var text = answer
        for (st in drop) {
            val lines = text.lines().toMutableList()
            val idx = lines.indexOfFirst { line -> line.replace(CITATION, "").replace("**", "").contains(st.text) }
            if (idx >= 0) {
                val line = lines[idx]
                val cleaned = line.replace(CITATION, "").replace("**", "")
                if (cleaned.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim()
                        .replace(Regex("^\\d{1,2}[.)]\\s+"), "") == st.text) lines.removeAt(idx)
                else {
                    // The statement is one sentence of a longer line: remove that sentence only.
                    val sentenceRx = Regex(Regex.escape(st.text) + "(?:\\s*\\[(?:S?\\d+(?:\\s*[,–-]\\s*S?\\d+)*)])*\\s*")
                    lines[idx] = line.replace(sentenceRx, "").trimEnd()
                }
                text = lines.joinToString("\n")
            }
        }
        val pruned = text.replace(Regex("\n{3,}"), "\n\n").trim().takeIf { it.isNotBlank() && it != answer.trim() } ?: return null
        return pruned to Result(result.statements - drop.toSet())
    }

    fun marker(result: Result): String = "proof:${result.supported}/${result.total}"

    // ── Limits: "up to 50,000" vs "more than 50,000" ─────────────────────────────────────────

    private val UPPER = Regex(
        "(?i)up\\s*to|upto|not\\s+exceed\\w*|not\\s+more\\s+than|maximum|\\bmax\\.?|\\bbelow\\b|less\\s+than|\\bunder\\b|within|" +
            "\\btak\\b|se\\s+kam|तक|से\\s+कम|अधिकतम"
    )
    private val LOWER = Regex(
        "(?i)more\\s+than|\\babove\\b|(?<!not\\s)exceed\\w*|\\bover\\b|minimum|at\\s+least|\\badhik\\b|\\bzyada\\b|\\bjyada\\b|se\\s+upar|" +
            "kam\\s+se\\s+kam|से\\s+अधिक|से\\s+ज़्यादा|से\\s+ज्यादा|से\\s+ऊपर|न्यूनतम|कम\\s+से\\s+कम"
    )
    private val NUM_RAW = Regex("\\d[\\d,]*(?:\\.\\d+)?")

    private fun directions(text: String, number: String): Set<Char> {
        val out = HashSet<Char>()
        for (m in NUM_RAW.findAll(text)) {
            if (m.value.replace(",", "") != number) continue
            val window = text.substring(maxOf(0, m.range.first - 40), minOf(text.length, m.range.last + 41))
            val upper = UPPER.containsMatchIn(window)
            val lower = LOWER.findAll(window).any { lm -> !Regex("(?i)not\\s+$").containsMatchIn(window.substring(0, lm.range.first)) }
            if (upper && !lower) out += 'U'
            if (lower && !upper) out += 'L'
        }
        return out
    }

    /** The statement gives a limit the other way round from the passage, for the same number. */
    fun directionConflict(statement: String, passage: String): Boolean =
        numbers(statement).any { n ->
            val st = directions(statement, n)
            val ps = directions(passage, n)
            st.size == 1 && ps.size == 1 && st != ps
        }

    // ── Topic: does the evidence talk about what was asked? ─────────────────────────────────

    private val FRAME = setOf(
        "what", "which", "where", "when", "should", "would", "could", "about", "during", "under", "there", "their", "these",
        "those", "other", "given", "giving", "maximum", "minimum", "amount", "number", "limit", "limits", "required", "allowed",
        "provide", "provided", "process", "procedure", "explain", "describe", "please", "detail", "details", "without", "within",
        "between", "before", "after", "things", "important", "different", "various", "possible", "necessary",
        "recommended", "recommend", "suggested", "advised", "correct", "proper", "normal", "usual", "exactly", "regarding",
        "related", "following", "general", "specific", "information", "guidelines", "according",
        "bought", "taken", "given", "paid", "sold", "spent", "made", "done", "kept", "brought", "chosen",
        "person", "persons", "people", "someone", "individual", "individuals", "anyone", "everyone",
        "performed", "perform", "conducted", "conduct", "carried", "undertaken", "undertake", "provided", "ensure", "ensured",
        "available", "availability", "duration", "period", "receive", "received", "receives", "obtain", "amount", "quantity"
    )

    /**
     * Specific words of the (English) question that none of the cited passages contains. When at
     * least half of them are missing, the answer probably comes from passages about something else
     * (stroke warning signs for "danger signs in pregnancy"). Empty when the question is not English.
     */
    /**
     * True when every content word of an English question (short terms such as "AoN" and "EAS"
     * included; question and comparison words such as "difference" or "between" excluded) occurs in
     * [evidence]. A comparison question never has one passage about both things, so its re-ranker
     * score is low even when the knowledge base covers each of them: this decides whether the
     * "weak match" warning is shown. Devanagari questions: false (no check, warning as before).
     */
    fun questionTermsCovered(question: String, evidence: String): Boolean {
        if (question.count { it.code in 0x0900..0x097F } >= 2) return false
        val text = " " + evidence.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        val terms = question.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in FRAME && it !in QUESTION_WORDS }
            // A misspelt question word ("differemce") is still a question word.
            .filterNot { w -> w.length >= 6 && QUESTION_WORDS.any { q -> q.length >= 6 && editDistance(w, q) <= 2 } }
            .distinct()
        if (terms.isEmpty()) return false
        return terms.all { t -> text.contains(" $t ") || (t.length >= 5 && text.contains(" ${t.trimEnd('s')}")) }
    }

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private val QUESTION_WORDS = setOf(
        "the", "and", "for", "how", "what", "who", "whom", "whose", "why", "is", "are", "was", "were", "be", "of", "to", "in", "on",
        "an", "at", "by", "or", "do", "does", "did", "can", "with", "from", "its", "it", "this", "that", "me", "tell", "give", "my", "we",
        "difference", "differences", "differ", "compare", "comparison", "versus", "vs", "similar", "similarity", "meaning", "mean",
        "means", "define", "definition", "explain", "kya", "hai", "ka", "ki", "ke", "aur", "mein", "main", "batao"
    )

    fun missingTopicWords(question: String, citedPassages: List<String>): List<String> {
        val latin = question.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (latin < question.count { it.code in 0x0900..0x097F } * 2 || citedPassages.isEmpty()) return emptyList()
        val terms = question.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 6 && it !in FRAME }.distinct()
        if (terms.isEmpty()) return emptyList()
        // British and American spellings are the same word: anaemia ~ anemia, diarrhoea ~ diarrhea, oedema ~ edema.
        fun spell(t: String) = t.replace("ae", "e").replace("oe", "e").replace("our", "or")
        val evidence = spell(citedPassages.joinToString(" ").lowercase(Locale.ROOT))
        // Stem match: "weighing" ~ "weight", "pregnancy" ~ "pregnant", "screening" ~ "screened".
        val stemmed = terms.filter { t -> spell(t).let { st -> !evidence.contains(st.take(minOf(st.length, maxOf(5, st.length - 3)))) } }
        // Spelling-tolerant: a typo ("umblical") or a known alias/Hinglish form ("dast")
        // that the evidence does state never raises the warning.
        val missing = if (!ArogyaFlags.coverageFuzzy || stemmed.isEmpty()) stemmed else {
            val evWords = evidence.split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }.distinct().take(3000)
            stemmed.filter { t -> !fuzzyHit(t, evWords) && !aliasHit(t, evWords) }
        }
        // One missing word alone is a warning only when it is long and specific ("pregnancy"), not a
        // verb the passage words differently ("bought" for "purchase").
        val enough = missing.size >= 2 || missing.singleOrNull()?.let { it.length >= 8 } == true
        return if (enough && missing.size * 2 >= terms.size) missing else emptyList()
    }

    /** Edit distance ≤ 1 (short words) or ≤ 2, same first letter (typos, not different words). */
    internal fun fuzzyHit(term: String, evidenceWords: List<String>): Boolean {
        if (term.length < 6) return false
        val cap = if (term.length <= 7) 1 else 2
        return evidenceWords.any { w ->
            w.isNotEmpty() && w[0] == term[0] && kotlin.math.abs(w.length - term.length) <= cap &&
                editDistance(term, w, cap) <= cap
        }
    }

    /** True when [term]'s alias group (or the term itself as an alias) appears in the evidence. */
    internal fun aliasHit(term: String, evidenceWords: List<String>): Boolean {
        val group = if (TOPIC_ALIASES.containsKey(term)) term else TOPIC_ALIAS_OF[term] ?: return false
        return TOPIC_ALIASES[group]?.any { it in evidenceWords } == true
    }

    private val TOPIC_ALIASES = mapOf(
        "umbilical" to setOf("umbilical", "umblical", "umbical", "cord", "stump", "naal"),
        "diarrhoea" to setOf("diarrhoea", "diarrhea", "dast", "loose", "motion"),
        "anaemia" to setOf("anaemia", "anemia", "haemoglobin", "hemoglobin", "khoon"),
        "pregnancy" to setOf("pregnancy", "pregnant", "garbh", "garbhvati", "antenatal"),
        "breastfeeding" to setOf("breastfeeding", "breastfeed", "feeding", "doodh", "stanpan"),
        "immunization" to setOf("immunization", "immunisation", "vaccine", "tika", "teeka", "teekakaran"),
        "newborn" to setOf("newborn", "navjat", "navjaat", "neonatal"),
        "fever" to setOf("fever", "bukhar", "temperature"),
        "pneumonia" to setOf("pneumonia", "saans"),
        "malaria" to setOf("malaria", "meleriya"),
        "tuberculosis" to setOf("tuberculosis", "tubercular"),
    )
    private val TOPIC_ALIAS_OF: Map<String, String> = TOPIC_ALIASES.flatMap { (g, ws) -> ws.map { it to g } }.toMap()

    /** Damerau-Levenshtein distance (an adjacent transposition costs 1), capped at [cap] + 1. */
    internal fun editDistance(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) {
            var rowMin = cap + 1
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 1)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return cap + 1
        }
        return d[a.length][b.length]
    }

    /** (found, total) from a "proof:4/5" marker, or null. */
    fun parseMarker(sourceIds: List<String>): Pair<Int, Int>? = sourceIds.firstOrNull { it.startsWith("proof:") }
        ?.removePrefix("proof:")?.split('/')?.takeIf { it.size == 2 }
        ?.let { (a, b) -> a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { y -> x to y } } }
}
