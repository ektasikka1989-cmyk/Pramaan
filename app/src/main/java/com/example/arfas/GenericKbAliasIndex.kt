package com.example.arfas

import java.util.Locale

/**
 * Abbreviations of the installed pack and what they stand for, learned from the pack's own text
 * when it is installed. No word lists; works for any KB:
 *
 *  - Schwartz–Hearst (standard abbreviation detection): "General Financial Rules (GFR)", where the
 *    abbreviation's letters occur in order in the preceding words and its first letter starts a word;
 *  - the pack's own naming: a 2–4 word phrase written right before "(ABBR)" at least
 *    [MIN_NAMED] times, e.g. "Performance Security (ePBG)" in many GeM guides.
 *
 * A question that uses the abbreviation is also searched with its long form, so "what should the
 * ePBG be" finds the rule text that says "performance security should be 3 to 5 per cent".
 */
class GenericKbAliasIndex private constructor(
    private val forms: Map<String, List<String>>,
    /** Passages writing the word as an abbreviation (two or more capitals), per lower-case word. */
    private val abbreviationUses: Map<String, Int>
) {
    val size: Int get() = forms.size

    /** Long forms of [token] when the pack writes it mainly as an abbreviation; empty otherwise. */
    fun longForms(token: String, documentFrequency: Int, passages: Int = 0): List<String> {
        val key = token.lowercase(Locale.ROOT)
        val list = forms[key] ?: return emptyList()
        // The pack's everyday abbreviations (in more than MAX_SHARE of passages, e.g. the portal's own
        // name) are matched literally everywhere; their long form only dilutes the question.
        if (passages > 0 && documentFrequency > passages * MAX_SHARE) return emptyList()
        val asAbbreviation = abbreviationUses[key] ?: 0
        // "is" is written "IS" (Indian Standards) somewhere, but mostly it is an ordinary word.
        return if (asAbbreviation * 2 > documentFrequency) list else emptyList()
    }

    /** Long forms for every abbreviation in [question], at most [maxForms]. */
    fun expansions(question: String, documentFrequency: (String) -> Int, maxForms: Int = 3, passages: Int = 0): List<String> {
        // A short question is about the term itself ("full form of aon", "what is aon", "aon"): its long
        // form is what is asked, however common the abbreviation is in the pack.
        // A question about the term itself ("aon", "what is aon and what is its full form") searches its
        // long form however common the abbreviation is; other questions keep the rule (always adding
        // "earnest money deposit" diluted "what is the range of EMD and who is exempted").
        val aboutTheTerm = isAboutTerm(question)
        return WORD.findAll(question).map { it.value }
            .flatMap { w -> longForms(w, documentFrequency(w.lowercase(Locale.ROOT)), if (aboutTheTerm) 0 else passages).take(2) }
            .distinct().take(maxForms).toList()
    }

    /**
     * Abbreviations in [question] that the pack writes as abbreviations, with the pack's own long
     * form (however common they are): "AON" to "acceptance of necessity". Given to the answer model so
     * it never guesses what one stands for.
     */
    fun terms(question: String, documentFrequency: (String) -> Int): List<Pair<String, String>> =
        WORD.findAll(question).map { it.value }.distinctBy { it.lowercase(Locale.ROOT) }
            .mapNotNull { w -> longForms(w, documentFrequency(w.lowercase(Locale.ROOT)), 0).firstOrNull()?.let { w.uppercase(Locale.ROOT) to it } }
            .take(4).toList()

    /** The question is about a term it names (short question); see [GenericKbPhase14Engine] lead. */
    fun isAboutTerm(question: String): Boolean =
        question.trim().split(Regex("\\s+")).count { it.isNotBlank() } <= SHORT_QUESTION_WORDS ||
            Regex("(?i)\\b(full\\s*form|stands?\\s+for|abbreviation|expand|expansion)\\b").containsMatchIn(question)

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(forms.size)
        forms.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v.size); v.forEach { out.writeUTF(it) } }
        out.writeInt(abbreviationUses.size)
        abbreviationUses.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
    }

    companion object {
        private const val MIN_SH = 2
        const val MAX_SHARE = 0.02
        /** Questions of at most this many words are about the term they name. */
        const val SHORT_QUESTION_WORDS = 5
        private const val MIN_NAMED = 3
        private val WORD = Regex("[A-Za-z][A-Za-z0-9]{1,9}")
        private val SHORT_FORM = Regex("\\(\\s*([A-Za-z][A-Za-z0-9&\\-]{1,9})\\s*\\)")
        private val LETTERS = Regex("[A-Za-z][A-Za-z\\-]*")

        val EMPTY = GenericKbAliasIndex(emptyMap(), emptyMap())

        fun readFrom(input: java.io.DataInputStream): GenericKbAliasIndex {
            val forms = HashMap<String, List<String>>()
            repeat(input.readInt()) {
                val k = input.readUTF()
                forms[k] = List(input.readInt()) { input.readUTF() }
            }
            val uses = HashMap<String, Int>()
            repeat(input.readInt()) { uses[input.readUTF()] = input.readInt() }
            return GenericKbAliasIndex(forms, uses)
        }

        private fun capitals(s: String) = s.count { it.isUpperCase() }

        /** Schwartz–Hearst best long form for [sf] at the end of [candidate]; null when none. */
        fun schwartzHearst(sf: String, candidate: String): String? {
            var s = sf.length - 1
            var l = candidate.length - 1
            while (s >= 0) {
                val ch = sf[s].lowercaseChar()
                if (!ch.isLetterOrDigit()) { s--; continue }
                while (l >= 0 && (candidate[l].lowercaseChar() != ch || (s == 0 && l > 0 && candidate[l - 1].isLetterOrDigit()))) l--
                if (l < 0) return null
                l--; s--
            }
            val start = candidate.lastIndexOf(' ', l.coerceAtLeast(0)) + 1
            return candidate.substring(start).trim().takeIf { it.isNotEmpty() }
        }
    }

    class Builder {
        private val sh = HashMap<String, HashMap<String, Int>>()
        private val named = HashMap<String, HashMap<String, Int>>()
        private val uses = HashMap<String, Int>()

        fun add(text: String) {
            val t = text.replace(Regex("\\s+"), " ")
            WORD.findAll(t).map { it.value }.filter { capitals(it) >= 2 }.map { it.lowercase(Locale.ROOT) }.toSet()
                .forEach { uses[it] = (uses[it] ?: 0) + 1 }
            for (m in SHORT_FORM.findAll(t)) {
                val sf = m.groupValues[1]
                if (capitals(sf) < 2) continue
                val key = sf.lowercase(Locale.ROOT)
                val before = LETTERS.findAll(t.substring((m.range.first - 160).coerceAtLeast(0), m.range.first)).map { it.value }.toList()
                if (before.isEmpty()) continue
                val n = minOf(sf.length + 5, sf.length * 2)
                schwartzHearst(sf, before.takeLast(n).joinToString(" "))?.let { lf ->
                    val words = lf.lowercase(Locale.ROOT).split(' ')
                    if (words.size >= 2 && lf.length > sf.length && key !in words) {
                        val map = sh.getOrPut(key) { HashMap() }
                        map[words.joinToString(" ")] = (map[words.joinToString(" ")] ?: 0) + 1
                    }
                }
                for (w in 2..4) {
                    if (before.size < w) break
                    val words = before.takeLast(w)
                    // A longer name counts only when written as a name ("Annual Maintenance Contract"),
                    // not when an ordinary word precedes it ("shall submit Performance Security").
                    if (w >= 3 && !words.first().first().isUpperCase()) continue
                    val phrase = words.joinToString(" ").lowercase(Locale.ROOT)
                    if (key in phrase.split(' ')) continue
                    val map = named.getOrPut(key) { HashMap() }
                    val k2 = "$w|$phrase"
                    map[k2] = (map[k2] ?: 0) + 1
                }
            }
        }

        fun build(): GenericKbAliasIndex {
            val out = HashMap<String, List<String>>()
            for (key in sh.keys + named.keys) {
                // Same long form with broken spacing from PDF extraction ("transfer oftechnology") counts once.
                val kept = sh[key].orEmpty().entries.filter { it.value >= MIN_SH }
                    .sortedByDescending { it.value }.map { it.key }
                    .distinctBy { lf -> lf.filter { it.isLetterOrDigit() } }.take(2).toMutableList()
                val nm = named[key].orEmpty()
                val best2 = nm.entries.filter { it.key.startsWith("2|") }.maxByOrNull { it.value }
                // The pack's own naming is used only when no letter-matching long form exists
                // ("Performance Security (ePBG)"); otherwise it adds a wrong sense of the letters.
                if (kept.isEmpty() && best2 != null && best2.value >= MIN_NAMED) {
                    var choice = best2.key.substringAfter('|')
                    // A longer phrase ending with it wins when it is (almost) always written so.
                    for (w in 3..4) {
                        val longer = nm.entries.filter { it.key.startsWith("$w|") && it.key.substringAfter('|').endsWith(" $choice") }
                            .maxByOrNull { it.value }
                        if (longer != null && longer.value >= 0.8 * best2.value) choice = longer.key.substringAfter('|')
                    }
                    if (kept.none { it == choice || it.endsWith(" $choice") || choice.endsWith(" $it") }) kept += choice
                }
                if (kept.isNotEmpty()) out[key] = kept.take(3)
            }
            return GenericKbAliasIndex(out, uses.filterKeys { it in out })
        }
    }
}
