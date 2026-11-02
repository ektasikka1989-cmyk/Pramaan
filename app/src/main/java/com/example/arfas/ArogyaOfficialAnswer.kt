package com.example.arfas

/**
 * Item 5d: per-sentence citations for guide and official-website answers. Pure Kotlin,
 * host-tested. Every shown sentence carries the ids of the sources whose text supports
 * it (guide ids first, then web W-ids); a sentence no source supports is not shown —
 * except when NOTHING matches (a cross-script paraphrase that passed the gate), which
 * stays as one block cited to every source rather than vanishing.
 */
object ArogyaOfficialAnswer {
    /** One shown sentence/bullet with its citation ids (["1"] guide, ["W1"] web). */
    data class CitedSegment(val text: String, val sourceIds: List<String>)

    /** Share of a sentence's content words that must occur in a source to cite it. */
    const val MIN_SUPPORT = 0.6
    /** Below this symmetric overlap an online sentence counts as new information. */
    const val NEW_INFO_OVERLAP = 0.5

    /** Sentences (decimal-safe, Hindi danda-safe); list lines stay whole. */
    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (Regex("^([•\\-*–]|\\d+[.)])\\s+").containsMatchIn(l)) {
                out += l.replace(Regex("^([•\\-*–]|\\d+[.)])\\s+"), "").trim()
                continue
            }
            // Hide the model's own section labels from citation splitting (the card renders them).
            val noLabel = Regex("(?i)^(answer|what to do|when to tell (the )?anm|उत्तर|क्या करें|anm को कब बताएं|एएनएम को कब बताएं)\\s*[:：]\\s*").replace(l, "")
            for (m in Regex("(?:[^.!?।\\n]|(?<=\\d)\\.(?=\\d))+[.!?।]?").findAll(noLabel)) {
                val s = m.value.trim().trimEnd(':')
                if (s.length >= 3) out += s
            }
        }
        return out
    }

    private fun tokens(t: String): Set<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(t.lowercase(java.util.Locale.ROOT))
            .map { it.value }.filter { it.length >= 3 && !it.all(Char::isDigit) }.toSet()

    /** Share of [sentence]'s content words occurring in [evidence]. */
    fun support(sentence: String, evidence: String): Double {
        val st = tokens(sentence)
        if (st.isEmpty()) return 0.0
        val ev = tokens(evidence)
        return st.count { it in ev }.toDouble() / st.size
    }

    /** Symmetric token overlap (Jaccard) of two sentences. */
    fun overlap(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    private fun matchIds(sentence: String, texts: List<Pair<String, String>>): List<String> =
        texts.filter { support(sentence, it.second) >= MIN_SUPPORT }.map { it.first }

    /**
     * Guide-answer segments: each sentence cited to the guide excerpts supporting it.
     * Sentences no excerpt supports are dropped; when none match at all, the whole
     * answer stays as one block cited to every source (cross-script safety net).
     */
    fun guideSegments(answer: String, sources: List<ArogyaSource>): List<CitedSegment> {
        if (sources.isEmpty()) return emptyList()
        val texts = sources.map { it.id to (it.excerpt + "\n" + it.title) }
        val kept = splitSentences(answer).mapNotNull { s ->
            val ids = matchIds(s, texts)
            if (ids.isEmpty()) null else CitedSegment(s, ids)
        }
        if (kept.isNotEmpty()) return kept
        return listOf(CitedSegment(splitSentences(answer).joinToString(" ").ifBlank { answer.trim() }, sources.map { it.id }))
    }

    /**
     * Tier 2 web segments: each sentence cited to the fetched official pages (W-ids)
     * and/or the guide excerpts (guide ids first) supporting it. Medication/dose and
     * unsupported sentences are dropped; when nothing survives, the caller falls back
     * to Tier 3 (empty list).
     */
    fun webSegments(
        answer: String,
        pages: List<ArogyaOfficialSources.Fetched>,
        guides: List<ArogyaSource>
    ): List<CitedSegment> {
        val webTexts = pages.mapIndexed { i, p -> "W${i + 1}" to (p.text + "\n" + p.title) }
        val guideTexts = guides.map { it.id to (it.excerpt + "\n" + it.title) }
        return splitSentences(answer).mapNotNull { s ->
            if (ArogyaEvidenceSafety.medication(s)) return@mapNotNull null
            val ids = matchIds(s, guideTexts) + matchIds(s, webTexts)
            if (ids.isEmpty()) null else CitedSegment(s, ids)
        }
    }

    /**
     * Online sentences adding information the offline answer lacks: below
     * [NEW_INFO_OVERLAP] symmetric overlap with every offline sentence. The card
     * highlights these in the Online tab.
     */
    fun newSentences(offline: String, online: String): Set<String> {
        val off = splitSentences(offline)
        return splitSentences(online).filter { on -> off.none { overlap(on, it) >= NEW_INFO_OVERLAP } }.toSet()
    }

    /**
     * Tier for an answer: 1 = found in the guides; 2 = online, guides silent, an
     * allowlisted official page grounds it; 3 = not found anywhere official.
     */
    fun selectTier(hasGuideAnswer: Boolean, online: Boolean, liveWeb: Boolean, tier2Grounded: Boolean): Int =
        when {
            hasGuideAnswer -> 1
            online && liveWeb && tier2Grounded -> 2
            else -> 3
        }
}
