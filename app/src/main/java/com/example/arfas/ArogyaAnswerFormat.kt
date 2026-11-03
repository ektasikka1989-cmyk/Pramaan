package com.example.arfas

/**
 * Item 4: answer presentation helpers. Pure Kotlin, host-testable.
 * - [isDuplicate]: the "guide says" quote is hidden when the answer already says
 *   the same thing (>= 70% token overlap after normalising), so no sentence shows twice.
 * - [sections]: the Answer: / What to do: / When to tell the ANM: shape (English or
 *   Hindi labels) into headed blocks; anything else renders as one plain block.
 * - [sentenceCount]: sentence pieces for the AROGYA_ANSWER_LEN log line.
 */
object ArogyaAnswerFormat {
    /** One headed block of an answer; [heading] is empty for plain (unlabelled) text. */
    data class Section(val heading: String, val body: String)

    /** Model-written labels (lowercased for Latin) to the canonical heading shown. */
    private val LABELS = linkedMapOf(
        "answer" to "Answer",
        "why it matters" to "Why it matters",
        "what to do" to "What to do",
        "when to tell the anm" to "When to tell the ANM",
        "when to tell anm" to "When to tell the ANM",
        "common mistakes to avoid" to "Common mistakes to avoid",
        "common mistakes" to "Common mistakes to avoid",
        "उत्तर" to "उत्तर",
        "क्यों ज़रूरी" to "क्यों ज़रूरी",
        "क्यों जरूरी" to "क्यों ज़रूरी",
        "क्या करें" to "क्या करें",
        "क्या करे" to "क्या करें",
        "anm को कब बताएं" to "ANM को कब बताएं",
        "anm को कब बताये" to "ANM को कब बताएं",
        "एएनएम को कब बताएं" to "ANM को कब बताएं",
        "आम गलतियाँ" to "आम गलतियाँ",
        "आम गलतिया" to "आम गलतियाँ"
    )

    /** A line starting with a known label + colon opens a section; returns (heading, rest). */
    private fun matchLabel(line: String): Pair<String, String>? {
        val l = line.trimStart('•', '-', '–', '*', ' ', '#')
        for ((key, heading) in LABELS) {
            val hit = if (key[0] in 'a'..'z') l.lowercase(java.util.Locale.ROOT).startsWith(key) else l.startsWith(key)
            if (!hit) continue
            val rest = l.drop(key.length).trimStart()
            if (!rest.startsWith(":") && !rest.startsWith("：")) continue
            return heading to rest.drop(1).trim()
        }
        return null
    }

    fun sections(text: String): List<Section> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Section>()
        var heading = ""
        val body = StringBuilder()
        fun flush() {
            val b = body.toString().trim()
            if (b.isNotEmpty() || heading.isNotEmpty()) out += Section(heading, b)
            body.clear()
        }
        for (raw in ArogyaMarkdown.strip(text).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (body.isNotEmpty()) body.append('\n')
                continue
            }
            val label = matchLabel(line)
            if (label != null) {
                flush()
                heading = label.first
                if (label.second.isNotEmpty()) body.append(label.second)
            } else {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }
        }
        flush()
        return out.ifEmpty { listOf(Section("", ArogyaMarkdown.strip(text).trim())) }
    }

    /** Lowercased word tokens (Devanagari matras kept: \\p{M}); one-letter bits dropped. */
    private fun tokens(t: String): Set<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(t.lowercase(java.util.Locale.ROOT))
            .map { it.value }.filter { it.length >= 2 }.toSet()

    /**
     * Containment of the shorter text in the longer one: shared tokens over the
     * smaller token set. 1.0 when the answer contains the whole quote.
     */
    fun overlap(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.intersect(tb).size
        return shared.toDouble() / minOf(ta.size, tb.size)
    }

    /** True when the quote adds nothing over the answer: show the answer once, with the source line. */
    fun isDuplicate(answer: String, quote: String): Boolean {
        if (answer.isBlank() || quote.isBlank()) return false
        return overlap(answer, quote) >= 0.7
    }

    /** Sentence pieces: enumerators stripped, split on end marks (incl. । ॥) and newlines. */
    fun sentenceCount(text: String): Int {
        if (text.isBlank()) return 0
        var n = 0
        for (line in text.lines()) {
            val noEnum = line.trim().replace(Regex("^([•\\-*–]|\\d+[.)])\\s+"), "")
            n += noEnum.split(Regex("[.!?…।॥]+")).count { it.trim().length >= 3 }
        }
        return n
    }

    private val SUP = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')

    /** Part B7: a citation id as inline superscript ("10" → "¹⁰"; other chars unchanged). */
    fun supDigits(id: String): String = buildString(id.length) { id.forEach { append(SUP[it] ?: it) } }

    /**
     * UI6: the model's own source tags ([S1], [S1, S2], [1], [1][2], 【1】, (S1), [W2], [Source: S1])
     * are removed from the shown text. The app shows its own tappable superscripts from the
     * checked citations instead, so the tags only duplicated them ("[S1] ¹"). Markdown links
     * ("[text](url)") and other bracketed text stay.
     */
    fun stripCitationMarkers(text: String): String {
        if (text.isBlank()) return text
        val id = "(?:[SW]?\\d{1,2})"
        val list = "\\s*(?:(?:sources?|src)\\s*:?\\s*)?$id(?:\\s*(?:,|;|–|-|and|&)\\s*$id)*\\s*"
        var out = Regex("(?i)[\\[【]$list[\\]】](?!\\()").replace(text, "")
        out = Regex("(?i)\\((?:(?:sources?|src)\\s*:?\\s*)?[SW]\\d{1,2}(?:\\s*(?:,|;|and|&)\\s*[SW]\\d{1,2})*\\)").replace(out, "")
        // Tidy what the tags leave behind: "word ." → "word.", doubled spaces.
        out = out.replace(Regex("[ \\t]+([.,;:!?।])"), "$1").replace(Regex("[ \\t]{2,}"), " ")
        return out.lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    /**
     * UI6: where a citation superscript is shown. A run of sentences from the same source(s)
     * gets one superscript at its end (like a footnote per paragraph), not one after every sentence.
     */
    fun citeAt(idsPerSentence: List<List<String>>): List<Boolean> =
        idsPerSentence.mapIndexed { i, ids -> ids.isNotEmpty() && (i == idsPerSentence.lastIndex || idsPerSentence[i + 1] != ids) }

    /** Part B7: a numbered step or bullet starts its own line; plain sentences flow together. */
    fun needsNewLine(sentence: String): Boolean =
        Regex("^([•\\-*–]|\\d+[.)])\\s+").containsMatchIn(sentence.trimStart())
}
