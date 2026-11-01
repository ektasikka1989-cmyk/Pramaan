package com.example.arfas

/**
 * Deterministic, language-list-free helpers for selected-KB answers:
 * - the reply script is taken from the characters of the question (Unicode script), not guessed
 *   by the model: typos in an English question must not produce a Hindi (Devanagari) answer;
 * - a degenerate repetition at the end of a generation ("है है है …") is cut off.
 */
object KbReplyText {
    enum class Script { LATIN, DEVANAGARI, OTHER, NONE }

    /**
     * Sentence boundaries (and line breaks) for checking answers: never after "Rs.", "No.", "Tab.",
     * "e.g." and similar, and never before a number, so "Rs. 1,75,000" is not cut into "… Rs." and
     * "1,75,000 …" (a salvage once left "A PHC level AB-HWC gets Rs.").
     */
    val SENTENCE_SPLIT = Regex(
        "(?<!\\b(?:Rs|No|Nos|Dr|Tab|Cap|Inj|Syp|Mr|Mrs|Ms|Smt|Sh|Shri|St|Govt|Dept|viz|vs|approx|Para|para|Art|Sec|Fig|Vol|Ch|Sl|S\\.No|e\\.g|i\\.e)\\.)" +
            "(?<=[.!?\u0964])\\s+(?!\\d)|\\n+"
    )

    /** Share of letters in each script; the majority script of the letters decides. */
    fun dominantScript(text: String): Script {
        var latin = 0; var deva = 0; var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.DEVANAGARI -> deva++
                else -> other++
            }
            i += Character.charCount(cp)
        }
        val total = latin + deva + other
        // Words decide a mixed question: "SHC स्तर के AB-HWC को कितना untied fund मिलता है?" is Hindi
        // with English terms (6 Hindi words, 4 English) though English letters are the majority.
        val devaWords = Regex("[\\u0900-\\u097F]+").findAll(text).count { m -> m.value.any { Character.isLetter(it) } }
        val latinWords = Regex("[A-Za-z]+").findAll(text).count()
        return when {
            total == 0 -> Script.NONE
            devaWords >= 3 && devaWords >= latinWords -> Script.DEVANAGARI
            deva * 2 >= total -> Script.DEVANAGARI
            latin * 2 >= total -> Script.LATIN
            else -> Script.OTHER
        }
    }

    /** Share (0..1) of an answer's letters that are Devanagari. */
    fun devanagariShare(text: String): Double {
        var letters = 0; var deva = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                letters++
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.DEVANAGARI) deva++
            }
            i += Character.charCount(cp)
        }
        return if (letters == 0) 0.0 else deva.toDouble() / letters
    }

    /** Reply-script instruction for the answer model, from the question's own characters. */
    fun scriptInstruction(query: String): String = when (dominantScript(query)) {
        // Never say "Latin" here: Llama 3.2 3B read "write in Latin letters" as "write in Latin" and
        // answered in the Latin language.
        // Hinglish questions are understood but answered in simple English: Llama 3.2 3B wrote broken
        // Hinglish ("20 mg tak tablet ka") and once reversed a limit ("se adhik" for "up to").
        Script.LATIN -> "Reply language: the question is typed in the English alphabet (A-Z). It may be Hinglish " +
            "(Hindi typed in A-Z): understand it, and always answer in clear, simple English. Do not use Devanagari script. " +
            "Keep every limit exactly as the evidence states it (up to, not exceeding, more than, at least)."
        Script.DEVANAGARI -> "Reply script: the question is written in Devanagari, so write the answer in Hindi in " +
            "Devanagari script (technical terms from the evidence may stay in English)."
        else -> "Reply script: write the answer in the same language and script as the question."
    }

    /**
     * True when the answer is in the wrong script for the question: a Latin-letter question answered
     * mostly in Devanagari, or a Devanagari question answered with almost no Devanagari.
     */
    private val ENGLISH_FUNCTION_WORDS = setOf(
        "the", "is", "are", "was", "and", "of", "to", "for", "a", "an", "with", "should", "be", "it", "this",
        "what", "how", "when", "who", "which", "do", "does", "can", "on", "by", "or", "if", "her", "she", "they"
    )

    /**
     * An English question answered in another Latin-alphabet language (Llama 3.2 3B once answered
     * in Latin): the answer has 20+ words but almost none of English's most common words.
     */
    fun wrongLanguageForEnglish(query: String, answer: String): Boolean {
        fun words(t: String) = Regex("[A-Za-z]+").findAll(t.lowercase()).map { it.value }.toList()
        val q = words(query)
        if (dominantScript(query) != Script.LATIN || q.count { it in ENGLISH_FUNCTION_WORDS } < 1) return false
        val a = words(answer.replace(Regex("\\[[^\\]]*]"), " "))
        if (a.size < 20) return false
        return a.count { it in ENGLISH_FUNCTION_WORDS }.toDouble() / a.size < 0.04
    }

    /**
     * Citation forms a 3B model writes instead of [S1]: "(S1)", "(S1, S2)", "(Source: S1)",
     * "[Source S1]", "Source S1", "S1:" at a line end. All become [S1] so the app can show the
     * passages. Bare "S1" inside a sentence is left alone (it may be real text).
     */
    fun normalizeCitations(text: String): String {
        if (!Regex("S\\d").containsMatchIn(text)) return text
        var out = text
        val list = "S\\d{1,2}(?:\\s*(?:,|;|and|&)\\s*S\\d{1,2})*"
        out = Regex("[(\\[【]\\s*(?:sources?|src)?\\s*:?\\s*($list)\\s*[)\\]】]", RegexOption.IGNORE_CASE)
            .replace(out) { m -> "[" + Regex("S\\d{1,2}").findAll(m.groupValues[1]).joinToString(", ") { it.value } + "]" }
        out = Regex("\\b(?:sources?|src)\\s*:?\\s*($list)\\b", RegexOption.IGNORE_CASE)
            .replace(out) { m -> "[" + Regex("S\\d{1,2}").findAll(m.groupValues[1]).joinToString(", ") { it.value } + "]" }
        return out
    }

    fun scriptMismatch(query: String, answer: String): Boolean = when (dominantScript(query)) {
        Script.LATIN -> devanagariShare(answer) > 0.3
        Script.DEVANAGARI -> devanagariShare(answer) < 0.2
        else -> false
    }

    /**
     * Cuts a degenerate repetition at the end of [text]: the same unit of 1..[MAX_UNIT_WORDS] words
     * repeated at least [MIN_REPEATS] times in a row is reduced to its first occurrence, and the
     * text ends there. Text without such a tail is returned unchanged.
     */
    fun trimRepeatedTail(text: String): String {
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size < MIN_REPEATS) return text
        var cutAt: Int? = null
        for (n in 1..MAX_UNIT_WORDS) {
            if (words.size < n * MIN_REPEATS) break
            fun unit(end: Int) = (end - n until end).joinToString(" ") { words[it].value }
            val last = unit(words.size)
            var copies = 1
            var end = words.size - n
            while (end - n >= 0 && unit(end) == last) { copies++; end -= n }
            if (copies >= MIN_REPEATS) {
                // Keep the first copy of the unit; drop the repeats after it.
                val keepEndWord = end + n - 1
                val candidate = words[keepEndWord].range.last + 1
                cutAt = minOf(cutAt ?: candidate, candidate)
            }
        }
        return cutAt?.let { text.substring(0, it).trimEnd() } ?: text
    }

    /**
     * Removes degenerate repetition that is not an exact tail loop: a sentence whose words are
     * almost all the same as an earlier sentence's (word-set overlap >= [NEAR_DUPLICATE]) is
     * dropped, and a word repeated back to back ("breakup breakup") is written once. Only when one sentence occurs
     * [MIN_LOOP_OCCURRENCES]+ times (a loop); sentences of fewer than [MIN_DEDUPE_WORDS] words are
     * always kept (list labels, short steps). Line structure
     * (numbered steps, bullets) is preserved.
     */
    /** Word-set overlap (of the longer sentence) at which a second sentence is the same sentence. */
    private const val SAME_SENTENCE = 0.9

    fun removeRepeatedSentences(text: String): String {
        val stutter = Regex("(?i)(?<![\\p{L}\\p{M}])(\\p{L}[\\p{L}\\p{M}]*)(?:\\s+\\1)+(?![\\p{L}\\p{M}])")
        val splitter = Regex("(?<=[.!?।])\\s+")
        fun words(piece: String) = Regex("[\\p{L}\\p{N}]+").findAll(piece.lowercase()).map { it.value }.toSet()
        fun near(a: Set<String>, b: Set<String>) = a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= NEAR_DUPLICATE
        val lines = text.split('\n').map { line -> splitter.split(stutter.replace(line) { it.groupValues[1] }) }
        // Only a degenerate loop repeats one sentence MIN_LOOP_OCCURRENCES or more times; similar
        // but legitimate sentences (two parallel steps) are left alone.
        val sets = lines.flatten().map(::words).filter { it.size >= MIN_DEDUPE_WORDS }
        val looping = sets.any { a -> sets.count { b -> near(a, b) } >= MIN_LOOP_OCCURRENCES }
        val kept = ArrayList<Set<String>>()
        val out = StringBuilder()
        for ((index, pieces) in lines.withIndex()) {
            // Without a loop, only a sentence that is (almost) word for word an earlier one is dropped:
            // the same rule copied from two editions of a document ("AoN would lapse …" twice).
            val keptPieces = pieces.filter { piece ->
                val w = words(piece)
                if (w.size < MIN_DEDUPE_WORDS) return@filter true
                val duplicate = kept.any { k ->
                    if (looping) near(w, k)
                    else w.intersect(k).size.toDouble() / maxOf(w.size, k.size) >= SAME_SENTENCE
                }
                if (!duplicate) kept += w
                !duplicate
            }
            val joined = keptPieces.joinToString(" ").trimEnd()
            if (joined.isNotBlank() || text.split('\n')[index].isBlank()) out.append(joined).append('\n')
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * An answer that stopped at the output limit mid-sentence is cut back to its last complete
     * sentence or line, and a short note (in the answer's script) says there is more and how to get
     * it. [likelyAtLimit] comes from the caller (answer length close to the output budget); an answer
     * that ended normally, or that already ends with complete punctuation, is returned unchanged.
     */
    fun completeAtLimit(text: String, likelyAtLimit: Boolean): String {
        val t = text.trimEnd()
        if (!likelyAtLimit || t.isEmpty() || t.last() in TERMINAL) return text
        val lineStart = t.lastIndexOf('\n') + 1
        val lastLine = t.substring(lineStart)
        // Last real sentence end inside the unfinished line (not a step number such as "3.").
        val sentenceEnd = Regex("[.!?।](?=\\s)").findAll(lastLine)
            .map { it.range.first }
            .filter { pos -> !Regex("^\\s*[\\p{N}\\p{L}]{1,3}$").matches(lastLine.substring(0, pos)) }
            .lastOrNull()
        val cutAt = when {
            sentenceEnd != null -> lineStart + sentenceEnd + 1
            lineStart > 0 -> lineStart - 1
            else -> return text
        }
        // Keep at least half the answer; otherwise show it as it is rather than lose most of it.
        if (cutAt < t.length / 2) return text
        val kept = t.substring(0, cutAt).trimEnd()
        return kept + "\n\n" + if (devanagariShare(kept) > 0.3) MORE_HI else MORE_EN
    }

    private const val TERMINAL = ".!?।:)]\"'”"
    /**
     * The user's reply to the app's own "Type "continue" to see the rest" note: [previousAnswer]
     * carries that note and the reply is the word it asked for (punctuation and case ignored).
     */
    fun isContinuationReply(reply: String, previousAnswer: String): Boolean {
        if (!previousAnswer.contains(MORE_EN) && !previousAnswer.contains(MORE_HI)) return false
        val word = reply.trim().trim('.', '!', '?', '"', '\'', ' ').lowercase()
        return word == CONTINUE_WORD
    }

    const val CONTINUE_WORD = "continue"

    const val MORE_EN = "(The source has more on this. Type \"continue\" to see the rest.)"
    const val MORE_HI = "(स्रोत में इस पर और जानकारी है। बाकी देखने के लिए \"continue\" लिखें।)"

    private const val MIN_LOOP_OCCURRENCES = 3
    /**
     * Degenerate repetition anywhere in a draft, in any script, with or without spaces:
     * "heinheinhein…", "है है है है …", or one phrase written again and again. Returns a short
     * description, or null. A unit of [MIN_LOOP_UNIT_CHARS]..[MAX_LOOP_UNIT_CHARS] characters that
     * contains a letter, repeated [MIN_LOOP_REPEATS]+ times back to back over [MIN_LOOP_SPAN_CHARS]+
     * characters, is a decoder loop. Table rows are exempt ('|' or tab in the unit): identical cells
     * are legitimate there. Also flags a long draft whose words are almost all the same few words.
     */
    fun degenerateRepetition(text: String): String? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val n = t.length
        if (n >= MIN_LOOP_SPAN_CHARS) {
            for (u in MIN_LOOP_UNIT_CHARS..minOf(MAX_LOOP_UNIT_CHARS, n / MIN_LOOP_REPEATS)) {
                var i = 0
                while (i + u * MIN_LOOP_REPEATS <= n) {
                    var repeats = 1
                    while (i + (repeats + 1) * u <= n && t.regionMatches(i, t, i + repeats * u, u)) repeats++
                    if (repeats >= MIN_LOOP_REPEATS && repeats * u >= MIN_LOOP_SPAN_CHARS) {
                        val unit = t.substring(i, i + u)
                        if (unit.any { it.isLetter() } && '|' !in unit && '\t' !in unit) {
                            return "repeat:${unit.trim().take(24)}x$repeats"
                        }
                    }
                    i += if (repeats > 1) (repeats - 1) * u else 1
                }
            }
        }
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*").findAll(t.lowercase()).map { it.value }.toList()
        if (words.size >= LOW_DIVERSITY_MIN_WORDS && words.toSet().size.toDouble() / words.size < LOW_DIVERSITY_RATIO) {
            return "low_diversity:${words.toSet().size}/${words.size}"
        }
        return null
    }

    private const val MIN_LOOP_UNIT_CHARS = 2
    private const val MAX_LOOP_UNIT_CHARS = 80
    private const val MIN_LOOP_REPEATS = 4
    private const val MIN_LOOP_SPAN_CHARS = 24
    private const val LOW_DIVERSITY_MIN_WORDS = 40
    private const val LOW_DIVERSITY_RATIO = 0.25

    private const val NEAR_DUPLICATE = 0.8
    private const val MIN_DEDUPE_WORDS = 6
    private const val MAX_UNIT_WORDS = 12
    private const val MIN_REPEATS = 4
}
