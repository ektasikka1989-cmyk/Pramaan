package com.example.arfas

/**
 * Cheap mobile sufficiency/grounding gate.
 * Deliberately avoids a second LLM call, sentence-by-sentence embedding validation,
 * or domain/topic keyword routing.
 */
class PocketRagQualityGate {
    companion object {
        const val UNSUPPORTED_PART_SENTENCE = "This part is not covered in the selected knowledge base."
        /** Content words a statement needs before it is checked against the evidence. */
        private const val MIN_STATEMENT_TERMS = 5
        /** Share of a statement's content words that must occur in the evidence. */
        private const val MIN_STATEMENT_SUPPORT = 0.4
        /** Checked statements needed before the answer can be rejected (more than half unsupported). */
        private const val MIN_CHECKED_STATEMENTS = 2
        private const val STEM_CHARS = 5
        private const val SWAP_MIN_WORDS = 5
        private const val SWAP_MIN_OVERLAP = 0.6
    }

    private val notInEvidenceMarker = Regex("""(?i)\[\[\s*NOT_IN_EVIDENCE\b[^\]]*]]""")

    data class MarkerSanitization(
        val text: String,
        val markerOnly: Boolean
    )

    /**
     * Markers are control output, never user-visible text. A marker-only result asks
     * the engine for recovery; a mixed result retains grounded clauses and replaces
     * only the unsupported clause with an app-owned, language-neutral fallback line.
     */
    fun sanitizeUnsupportedMarkers(answer: String?): MarkerSanitization {
        val raw = answer?.trim().orEmpty()
        if (raw.isBlank() || !notInEvidenceMarker.containsMatchIn(raw)) {
            return MarkerSanitization(raw, markerOnly = false)
        }
        val withoutMarkers = notInEvidenceMarker.replace(raw, "").trim()
        if (withoutMarkers.isBlank()) return MarkerSanitization("", markerOnly = true)
        return MarkerSanitization(
            text = notInEvidenceMarker.replace(
                raw,
                UNSUPPORTED_PART_SENTENCE
            ).trim(),
            markerOnly = false
        )
    }

    fun evaluate(query: String, answer: String?, packet: PocketRagPacket): PocketRagQualityDecision {
        val text = answer?.trim().orEmpty()
        // PocketSage trusts the single grounded generation.  FixSight adds only cheap
        // failure/grounding guards required for local->Cloud fallback; answer length or
        // number of retrieved chunks is never treated as a quality signal.
        if (text.isBlank()) return PocketRagQualityDecision(false, "local_answer_blank")
        // Partial clause coverage is answerable: the prompt must answer supported
        // clauses and explicitly mark unsupported ones. Treating partial retrieval as
        // a hard failure caused ordinary compound KB questions to escalate to Cloud.
        if (Regex("(?is)<(?:analysis|reasoning|thinking|tool_call|function_call)\\b").containsMatchIn(text)) {
            return PocketRagQualityDecision(false, "local_control_output")
        }
        // Broken generation (decoder loop, one phrase over and over): never an answer, whatever
        // words it shares with the question or the evidence.
        KbReplyText.degenerateRepetition(text)?.let { return PocketRagQualityDecision(false, "degenerate_output:$it") }
        // Cut off or empty: "<the question repeated>? There are three different sources:" and nothing
        // more (seen with a Hindi question). Never an answer.
        incompleteAnswer(query, text)?.let { return PocketRagQualityDecision(false, "incomplete_answer:$it") }
        // Wrong script for the question (e.g. an English question answered in Devanagari).
        if (KbReplyText.scriptMismatch(query, text)) {
            return PocketRagQualityDecision(false, "reply_script_mismatch")
        }
        if (KbReplyText.wrongLanguageForEnglish(query, text)) {
            return PocketRagQualityDecision(false, "reply_language_mismatch")
        }
        val unsupportedNumbers = unsupportedNumericClaims(query, text, packet.evidenceText)
        if (unsupportedNumbers.isNotEmpty()) {
            return PocketRagQualityDecision(false, "unsupported_numeric_claim:${unsupportedNumbers.take(3).joinToString(",")}")
        }
        wrongExpansion(text, packet.terms)?.let { return PocketRagQualityDecision(false, "wrong_expansion:$it") }
        val swapped = swappedTermStatements(text, packet)
        if (swapped.isNotEmpty()) {
            return PocketRagQualityDecision(false, "term_swap:${swapped.size}")
        }

        // A grounded answer must address the current question, not merely repeat a
        // word that happens to occur in a nearby page.  This is deliberately a small
        // lexical guard: it catches fluent answers about the wrong subject without
        // requiring topic-specific routing or another model call.
        val queryTerms = KnowledgeLexicalText.tokens(query, dropStopWords = true)
            .filter { it.length >= 3 }
            .toSet()
        val answerTerms = KnowledgeLexicalText.tokens(text, dropStopWords = true).toSet()
        val evidenceTerms = KnowledgeLexicalText.tokens(packet.evidenceText, dropStopWords = true).toSet()
        if (queryTerms.isNotEmpty() && !scriptsDiffer(query, packet.evidenceText)) {
            // Natural questions often contain a relation word plus one entity:
            // "who issues PAC", "validity of PAC", "how is X defined". Requiring
            // two literal query tokens rejects a correct answer when the model
            // expresses the relation with a synonym (for example "is issued by")
            // or answers with the issuing authority rather than repeating "issues".
            // For short questions, the entity/evidence overlap is the grounding
            // anchor; longer compound questions still require two anchors.
            val required = when {
                queryTerms.size <= 2 -> 1
                else -> minOf(2, queryTerms.size)
            }
            val answerCoverage = queryTerms.count { term -> termSatisfied(term, answerTerms, text) }
            if (answerCoverage < required) {
                // A faithful expansion or paraphrase can omit the literal query
                // token while still being strongly grounded in the selected packet.
                // Do not escalate such an answer merely because lexical wording
                // differs; reject only when it also lacks evidence anchors.
                val groundedAnchors = answerTerms.intersect(evidenceTerms).size
                if (groundedAnchors < 3) {
                    return PocketRagQualityDecision(false, "answer_query_mismatch")
                }
            }
        }

        if (!scriptsDiffer(text, packet.evidenceText)) {
            val answerSignificantTerms = significantTerms(text)
            val evidenceSignificantTerms = significantTerms(packet.evidenceText)
            if (answerSignificantTerms.isNotEmpty() && answerSignificantTerms.intersect(evidenceSignificantTerms).isEmpty()) {
                return PocketRagQualityDecision(false, "no_lexical_grounding_signal")
            }
        }

        // Claim-to-evidence check: most statements of the answer must be traceable to the exact
        // evidence packet, not merely share a few words with it.
        evidenceSupport(text, packet.evidenceText)?.let { (unsupported, checked) ->
            if (checked >= MIN_CHECKED_STATEMENTS && unsupported * 2 > checked) {
                return PocketRagQualityDecision(false, "low_evidence_support:$unsupported/$checked")
            }
        }

        return PocketRagQualityDecision(true, "local_grounded_answer")
    }

    /**
     * The answer states a different long form for an abbreviation than the pack itself uses
     * ("AoN stands for Authority of Nominee" where the documents write "Acceptance of Necessity
     * (AoN)"). [terms] is "AON = acceptance of necessity; …" from the pack's alias index. Returns the
     * abbreviation when a stated expansion shares less than half of the pack's long-form words.
     */
    internal fun wrongExpansion(answer: String, terms: String): String? {
        if (terms.isBlank()) return null
        fun words(t: String) = Regex("[\\p{L}]+").findAll(t.lowercase()).map { it.value }.filter { it.length >= 3 }.toSet()
        for (pair in terms.split(';')) {
            val abbr = pair.substringBefore('=').trim().takeIf { it.isNotEmpty() } ?: continue
            val known = words(pair.substringAfter('=', ""))
            if (known.isEmpty()) continue
            val a = Regex.escape(abbr)
            val stated = buildList {
                Regex("(?i)\\b$a\\b[^.\\n]{0,30}?(?:stands\\s+for|is\\s+short\\s+for|full\\s+form[^.\\n]{0,15}?\\bis|expands\\s+to|means)\\s*[:\\-]?\\s*[\"“*]*([\\p{L}][\\p{L} \\-]{3,80})")
                    .findAll(answer).forEach { add(it.groupValues[1]) }
                Regex("(?i)(?:full\\s+form|expansion|meaning)\\s+of\\s+$a\\s+(?:is|=|:)\\s*[\"“*]*([\\p{L}][\\p{L} \\-]{3,80})")
                    .findAll(answer).forEach { add(it.groupValues[1]) }
                Regex("([\\p{L}][\\p{L} \\-]{3,80}?)\\s*\\(\\s*$a\\s*\\)", RegexOption.IGNORE_CASE)
                    .findAll(answer).forEach { add(it.groupValues[1].split(Regex("\\s+")).takeLast(known.size + 2).joinToString(" ")) }
            }
            for (phrase in stated) {
                val w = words(phrase) - words(abbr)
                if (w.isEmpty()) continue
                val shared = w.count { it in known }
                if (shared * 2 < known.size) return abbr
            }
        }
        return null
    }

    /**
     * Statements that copy an evidence sentence but put a different named term in it: the answer
     * says "The ePBG shall be for a period exceeding … by three months" where the passage says
     * "The APBG shall be for a period exceeding … by three months". Detected generically: the
     * statement matches one evidence sentence closely ([SWAP_MIN_OVERLAP] of its content words), it
     * names a term (a word with two or more capitals) that its source passage never contains, and
     * that evidence sentence names a term the statement does not.
     */
    private val ROMAN = Regex("^[ivxlcdm]+$")

    fun swappedTermStatements(answer: String, packet: PocketRagPacket): List<String> {
        val termRx = Regex("(?<![\\p{L}\\p{N}])[\\p{L}]*\\p{Lu}[\\p{L}]*\\p{Lu}[\\p{L}\\p{N}]*")
        fun terms(t: String) = termRx.findAll(t).map { it.value.lowercase() }.filter { it.length in 2..12 }.toSet()
        fun words(t: String) = KnowledgeLexicalText.tokens(t.lowercase(), dropStopWords = true, maxTokens = Int.MAX_VALUE)
            .filter { it.length >= 3 && !it.all(Char::isDigit) }.toSet()
        val sourceTitleText = packet.sources.joinToString(" ") { it.display }.lowercase()
        val sentences = packet.evidenceBlocks.flatMap { block ->
            val blockLower = block.text.lowercase()
            block.text.split(KbReplyText.SENTENCE_SPLIT).map { Triple(it, words(it), blockLower) }
        }
        val out = ArrayList<String>()
        for (statement in answer.split(KbReplyText.SENTENCE_SPLIT)) {
            val sw = words(statement.replace(Regex("\\[[\\d,\\s]+]"), " "))
            val named = terms(statement)
            if (sw.size < SWAP_MIN_WORDS || named.isEmpty()) continue
            val best = sentences.maxByOrNull { (_, ew, _) -> sw.count { it in ew } } ?: continue
            if (sw.count { it in best.second }.toDouble() / sw.size < SWAP_MIN_OVERLAP) continue
            // Roman numerals ("Volume II") and words of the documents' own titles (the model naming its
            // source) are not technical terms that could have been swapped.
            val foreign = named.filter { it !in best.third && !ROMAN.matches(it) && it !in sourceTitleText }
            val evidenceOnly = terms(best.first) - named
            if (foreign.isNotEmpty() && evidenceOnly.isNotEmpty()) out += statement.trim()
        }
        return out
    }

    /** Removes the statements of [answer] that [swappedTermStatements] flags; null when nothing is left. */
    fun salvageSwappedTerms(answer: String, packet: PocketRagPacket): String? {
        val bad = swappedTermStatements(answer, packet).toSet()
        if (bad.isEmpty()) return answer
        val kept = answer.split(KbReplyText.SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotBlank() && it !in bad }
        return kept.joinToString(" ").trim().ifBlank { null }
    }

    /**
     * Statement-level support of [answer] by [evidence]: (unsupported, checked). A statement (a
     * sentence or list line with at least [MIN_STATEMENT_TERMS] content words) is supported when at
     * least [MIN_STATEMENT_SUPPORT] of its content words occur in the evidence (same word, or the same
     * first [STEM_CHARS] letters, so inflections match). Only statements written in the evidence's own
     * main script are checked: an answer translated from the evidence (Hindi from English passages)
     * cannot be matched word for word and is not judged here. Null when nothing can be checked.
     */
    /**
     * R3-lite (behind ArogyaFlags.r3Salvage): drop only the unsupported statements and
     * keep the rest when at least [minSupported] supported statements remain; else null
     * (the caller falls back to copied key points). Support is judged exactly like
     * [evidenceSupport]. Item 4 retries with 1: one good sentence is shown with
     * "More detail" instead of a one-line dead end.
     */
    fun salvageUnsupportedStatements(answer: String, evidence: String, minSupported: Int = 2): String? {
        val evidenceScript = KbReplyText.dominantScript(evidence).takeIf { it != KbReplyText.Script.NONE } ?: return null
        val evidenceTerms = KnowledgeLexicalText.tokens(evidence.lowercase(), dropStopWords = true, maxTokens = Int.MAX_VALUE).toHashSet()
        if (evidenceTerms.isEmpty()) return null
        val evidenceStems = evidenceTerms.filter { it.length >= STEM_CHARS }.mapTo(HashSet()) { it.take(STEM_CHARS) }
        fun supported(statement: String): Boolean? {
            if (KbReplyText.dominantScript(statement) != evidenceScript) return null
            val terms = KnowledgeLexicalText.tokens(statement.lowercase().replace(Regex("\\[[\\d,\\s]+]"), " "), dropStopWords = true)
                .filter { it.length >= 3 && !it.all(Char::isDigit) }
                .distinct()
            if (terms.size < MIN_STATEMENT_TERMS) return null
            val found = terms.count { it in evidenceTerms || (it.length >= STEM_CHARS && it.take(STEM_CHARS) in evidenceStems) }
            return found.toDouble() / terms.size >= MIN_STATEMENT_SUPPORT
        }
        val pieces = answer.split(KbReplyText.SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotBlank() }
        if (pieces.size <= 1) return null
        val kept = pieces.filter { supported(it) != false }
        if (kept.size == pieces.size) return answer
        if (kept.count { supported(it) == true } < minSupported) return null
        return kept.joinToString(" ").trim().ifBlank { null }
    }

    fun evidenceSupport(answer: String, evidence: String): Pair<Int, Int>? {
        val evidenceScript = KbReplyText.dominantScript(evidence).takeIf { it != KbReplyText.Script.NONE } ?: return null
        val evidenceTerms = KnowledgeLexicalText.tokens(evidence.lowercase(), dropStopWords = true, maxTokens = Int.MAX_VALUE).toHashSet()
        if (evidenceTerms.isEmpty()) return null
        val evidenceStems = evidenceTerms.filter { it.length >= STEM_CHARS }.mapTo(HashSet()) { it.take(STEM_CHARS) }
        var checked = 0
        var unsupported = 0
        for (statement in answer.split(KbReplyText.SENTENCE_SPLIT)) {
            if (KbReplyText.dominantScript(statement) != evidenceScript) continue
            val terms = KnowledgeLexicalText.tokens(statement.lowercase().replace(Regex("\\[[\\d,\\s]+]"), " "), dropStopWords = true)
                .filter { it.length >= 3 && !it.all(Char::isDigit) }
                .distinct()
            if (terms.size < MIN_STATEMENT_TERMS) continue
            checked++
            val found = terms.count { it in evidenceTerms || (it.length >= STEM_CHARS && it.take(STEM_CHARS) in evidenceStems) }
            if (found.toDouble() / terms.size < MIN_STATEMENT_SUPPORT) unsupported++
        }
        return if (checked == 0) null else unsupported to checked
    }

    /**
     * Returns numeric claims that are genuinely unsupported after lightweight
     * normalization. Formatting differences such as 2,02,024 vs 202024,
     * 2020-24 vs 202024, or 11.11 vs 11.11 must not force a Cloud escalation.
     */
    fun unsupportedNumericClaims(query: String, answer: String, evidence: String): Set<String> {
        val queryForms = numericForms(query)
        val evidenceForms = numericForms(evidence)
        return numericClaims(answer).filterTo(linkedSetOf()) { claim ->
            val forms = claimForms(claim)
            forms.none { it in queryForms || it in evidenceForms }
        }
    }

    /**
     * Deterministic local salvage used only after a bounded local rewrite also
     * fails numeric validation. It removes only complete lines/sentences that
     * contain unsupported numeric claims; it never invents replacement facts.
     */
    /** "ends_with_colon" when the answer stops at a lead-in; "echoes_question" when it is little more than the question. */
    fun incompleteAnswer(query: String, answer: String): String? {
        val clean = answer.replace(Regex("\\s*\\[(?:S?\\d+(?:\\s*[,–-]\\s*S?\\d+)*)]"), "").replace("**", "").trim()
        val lastLine = clean.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (lastLine.endsWith(":") || lastLine.endsWith("：")) return "ends_with_colon"
        fun letters(t: String) = t.count { Character.isLetter(it) }
        val q = query.trim().trimEnd('?', '？', '।', '.').trim()
        if (q.length >= 8 && clean.contains(q, ignoreCase = true) && letters(clean.replace(q, "", ignoreCase = true)) < 25) return "echoes_question"
        return null
    }

    fun salvageUnsupportedNumericSentences(query: String, answer: String?, packet: PocketRagPacket): String? {
        val text = answer?.trim().orEmpty()
        if (text.isBlank()) return null
        val unsupported = unsupportedNumericClaims(query, text, packet.evidenceText)
        if (unsupported.isEmpty()) return text

        val pieces = KbReplyText.SENTENCE_SPLIT
            .split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (pieces.size <= 1) return null

        val kept = pieces.filter { piece ->
            val claims = numericClaims(piece)
            claims.none { claim -> claim in unsupported }
        }
        val salvaged = kept.joinToString(" ").trim()
        if (salvaged.isBlank()) return null
        return salvaged
    }

    private fun numericClaims(text: String): Set<String> {
        val withoutEnumerators = text
            .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
            .replace(Regex("\\[S\\d+\\]"), "")
        // Numbers of two or more digits, and every percentage (a single digit "5%" for "50%" is a
        // real error: it changes a rule's value).
        return Regex("(?<![\\p{L}])\\d{2,}(?:[.,]\\d+)?%?(?![\\p{L}])|(?<![\\p{L}\\p{N}.,])\\d(?:[.,]\\d+)?\\s?%")
            .findAll(withoutEnumerators).map { it.value.replace(" ", "") }.toSet()
    }

    private fun numericForms(text: String): Set<String> {
        val forms = linkedSetOf<String>()
        // Normal numeric tokens.
        Regex("(?<![\\p{L}])\\d{1,}(?:[.,]\\d+)?%?(?![\\p{L}])")
            .findAll(text).forEach { forms += claimForms(it.value) }
        // A number written with a percent word in the evidence ("5 Percentage", "50 per cent",
        // "5 प्रतिशत") supports the same number written with "%".
        Regex("(?i)(?<![\\p{L}\\p{N}.,])(\\d+(?:[.,]\\d+)?)\\s*(?:per\\s?cent|percentage|प्रतिशत)")
            .findAll(text).forEach { forms += claimForms(it.groupValues[1] + "%") }
        // Date/version/range-like groups whose punctuation can be lost by the model.
        Regex("(?<![\\p{L}])\\d+(?:\\s*[-/.,]\\s*\\d+)+%?(?![\\p{L}])")
            .findAll(text).forEach { m ->
                val digits = m.value.filter(Char::isDigit)
                if (digits.length >= 2) forms += "d:$digits"
            }
        return forms
    }

    private fun claimForms(claim: String): Set<String> {
        val raw = claim.trim().lowercase()
        val forms = linkedSetOf<String>()
        forms += "r:$raw"
        val noThousands = raw.replace(",", "")
        forms += "r:$noThousands"
        val digits = raw.filter(Char::isDigit)
        if (digits.length >= 2) forms += "d:$digits"
        return forms
    }

    private fun significantTerms(text: String): Set<String> =
        Regex("[\\p{L}][\\p{L}\\p{M}\\p{N}_/-]{4,}")
            .findAll(text.lowercase())
            .map { it.value.trim('-', '/', '_') }
            .filter { it.length >= 5 }
            .take(700)
            .toSet()

    /** Textual overlap is not a valid rejection signal across writing systems. */
    private fun scriptsDiffer(first: String, second: String): Boolean {
        fun hasDevanagari(value: String): Boolean = value.any { it.code in 0x0900..0x097F }
        fun hasLatin(value: String): Boolean = value.any { it.code in 0x0041..0x024F }
        return (hasDevanagari(first) && !hasDevanagari(second)) ||
            (hasLatin(first) && !hasLatin(second) && hasDevanagari(second))
    }

    /** Accept a faithful expansion of a short acronym (for example, an answer
     * spelling out the words instead of repeating the abbreviated query token). */
    private fun termSatisfied(term: String, answerTerms: Set<String>, answer: String): Boolean {
        if (term in answerTerms) return true
        if (term.length !in 2..8 || !term.all { it.isLetter() }) return false
        val initials = Regex("[\\p{L}][\\p{L}\\p{N}'/-]*")
            .findAll(answer.lowercase())
            .map { it.value.first() }
            .joinToString("")
        return initials.contains(term.lowercase())
    }
}
