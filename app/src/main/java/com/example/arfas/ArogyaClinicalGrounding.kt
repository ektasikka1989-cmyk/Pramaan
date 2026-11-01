package com.example.arfas

/**
 * Claim-level clinical grounding for ASHA answers. It replaces the verbatim-only rule
 * (ArogyaEvidenceSafety.citedExtractive), which rejected every faithful paraphrase and every
 * Hindi answer to English evidence.
 *
 * A paraphrase is allowed. A statement is REJECTED when, compared with the passage it cites, it:
 *  1. cites a source that was not retrieved;
 *  2. states a number, or a number+unit, the passage does not contain (°C/°F equivalence and
 *     Hindi unit words are handled; numbers the worker typed in the question are allowed);
 *  3. reverses polarity ("give water" where the aligned source sentence says "do not give water");
 *  4. moves the advice to a population the passage is not about (pregnant woman vs newborn …);
 *  5. downgrades urgency ("can wait", "no need to refer") where the evidence says refer urgently;
 *  6. names a therapeutic medicine or dose (programme supplements such as IFA/ORS/zinc are allowed
 *     only when the cited passage names them, and their numbers pass rule 2);
 *  7. introduces clinical concepts the passage does not mention (concept lexicon; works across
 *     Hindi/Hinglish/English) or, for same-script statements, shares too few content words.
 *
 * This is a rule-based contradiction filter, NOT a proof of entailment. Support is additionally
 * established by AnswerProof (multilingual cross-encoder) in the visit path. Statements in Hindi
 * that contain no recognisable clinical concept or number are left to AnswerProof.
 */
object ArogyaClinicalGrounding {

    data class StatementResult(val statement: String, val sourceIds: List<String>, val problem: String?)
    data class Verdict(val accepted: Boolean, val reason: String, val statements: List<StatementResult>)

    private val CITE = Regex("\\[((?:S?\\d+)(?:\\s*[,;–-]\\s*S?\\d+)*)]")
    private val SPLIT = Regex("(?<=[.!?।])\\s+|\\n+")

    /** Parses [S3], [3], [S1, S2], [1-2]. Numeric ids map to [order] (1-based) when given. */
    fun citationIds(fragment: String, order: List<String>, known: Set<String>): List<String> =
        CITE.findAll(fragment).flatMap { m ->
            m.groupValues[1].split(Regex("\\s*[,;]\\s*")).flatMap { part ->
                val range = part.split(Regex("\\s*[–-]\\s*"))
                if (range.size == 2) {
                    val a = range[0].trimStart('S').toIntOrNull(); val b = range[1].trimStart('S').toIntOrNull()
                    if (a != null && b != null && b >= a && b - a < 10) (a..b).map { idFor(it.toString(), part.startsWith("S"), order, known) } else emptyList()
                } else listOf(idFor(part.trim().trimStart('S'), part.trim().startsWith("S"), order, known))
            }
        }.distinct().toList()

    private fun idFor(n: String, prefixed: Boolean, order: List<String>, known: Set<String>): String {
        if (prefixed || "S$n" in known && order.isEmpty()) return "S$n"
        val i = n.toIntOrNull() ?: return "S$n"
        return order.getOrNull(i - 1) ?: "S$n"
    }

    // ---------- numbers & units ----------
    private val UNIT_ALIASES = listOf(
        "c" to Regex("^(?:°\\s*c|c|celsius|centigrade|सेल्सियस|डिग्री सेल्सियस)$"),
        "f" to Regex("^(?:°\\s*f|f|fahrenheit|फ़ारेनहाइट|फारेनहाइट)$"),
        "day" to Regex("^(?:days?|din|दिन)$"), "week" to Regex("^(?:weeks?|hafte|हफ्ते|हफ़्ते|सप्ताह)$"),
        "month" to Regex("^(?:months?|mahine|महीने|महीना|माह)$"), "hour" to Regex("^(?:hours?|hrs?|ghante|घंटे|घंटा)$"),
        "kg" to Regex("^(?:kg|kgs|kilo|किलो)$"), "g" to Regex("^(?:g|gm|gms|grams?|ग्राम)$"), "mg" to Regex("^(?:mg|मिलीग्राम)$"),
        "ml" to Regex("^(?:ml|मिली|मिलीलीटर)$"), "min" to Regex("^(?:per minute|/min|minutes?|मिनट|breaths?(?: per minute)?|breaths?/min|times (?:per|a) minute|सांस(?: प्रति मिनट)?|बार प्रति मिनट)$"),
        "times" to Regex("^(?:times|baar|बार)$"), "tablets" to Regex("^(?:tablets?|goli|गोली|गोलियां|गोलियाँ)$"),
    )
    private val NUM_UNIT = Regex("(\\d+(?:\\.\\d+)?)\\s*(°\\s*[cf]|celsius|centigrade|fahrenheit|days?|din|दिन|weeks?|hafte|हफ्ते|हफ़्ते|सप्ताह|months?|mahine|महीने|महीना|माह|hours?|hrs?|ghante|घंटे|घंटा|kgs?|kilo|किलो|gms?|grams?|ग्राम|g|mg|ml|times (?:per|a) minute|बार प्रति मिनट|breaths? per minute|breaths?/min|breaths?|per minute|/min|minutes?|सांस प्रति मिनट|मिनट|times|baar|बार|tablets?|goli|गोली|गोलियां|गोलियाँ|[cf])?(?![\\p{L}])")

    private fun unitOf(raw: String): String? {
        val u = raw.trim().lowercase(); if (u.isEmpty()) return null
        return UNIT_ALIASES.firstOrNull { it.second.matches(u) }?.first
    }

    data class Quantity(val value: Double, val unit: String?)

    // Number words count as numbers ("one IFA tablet" vs "two"). "do" (Hinglish two / do) only before goli/tablet.
    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "twenty" to 20, "once" to 1, "twice" to 2,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10,
        "ek" to 1, "teen" to 3, "char" to 4, "paanch" to 5, "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10)
    private fun digitsForWords(t: String): String {
        var out = Regex("(?<![\\p{L}\\p{M}])([\\p{L}][\\p{L}\\p{M}]*)(?![\\p{L}\\p{M}])").replace(t) { m -> NUMBER_WORDS[m.value]?.toString() ?: m.value }
        out = Regex("\\bdo(?= (?:goli|tablet))").replace(out, "2")
        return out
    }

    fun quantities(text: String): List<Quantity> {
        val t = digitsForWords(ArogyaClinicalText.normalise(text)).replace(CITE, " ").replace(Regex("(?m)^\\s*\\d{1,2}[.)]\\s+"), " ")
        return NUM_UNIT.findAll(t).mapNotNull { m -> m.groupValues[1].toDoubleOrNull()?.let { Quantity(it, unitOf(m.groupValues[2])) } }.toList()
    }

    private fun equivalent(q: Quantity, passage: List<Quantity>): Boolean = passage.any { p ->
        fun close(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.051
        when {
            q.unit == null || p.unit == null -> close(q.value, p.value)
            q.unit == p.unit -> close(q.value, p.value)
            q.unit == "c" && p.unit == "f" -> kotlin.math.abs(q.value - (p.value - 32) * 5 / 9) < 0.11
            q.unit == "f" && p.unit == "c" -> kotlin.math.abs(q.value - (p.value * 9 / 5 + 32)) < 0.2
            else -> false
        } && (q.unit == null || p.unit == null || q.unit == p.unit || setOf(q.unit, p.unit) == setOf("c", "f"))
    }

    // ---------- polarity, population, urgency ----------
    private val NEG = Regex("(?i)\\b(?:no|not|never|without|avoid|don't|do not|doesn't|should not|must not|cannot|can't|stop|delay|only)\\b|" +
        "\\b(?:nahi|nahin|mat|na|bina)\\b|नहीं|मत |न दें|न करें|बिना|से बचें|केवल|सिर्फ")
    private val DOWNGRADE = Regex("(?i)\\b(?:can wait|wait and (?:see|watch)|no need to (?:refer|go|worry|see)|not (?:urgent|serious|necessary to refer)|" +
        "observe at home|monitor at home|manage at home|treat at home|next (?:visit|week)|until (?:the )?next|tomorrow|after a few days|" +
        "intezaar|ghar par (?:hi )?(?:dekh|rakh|ilaj)|zaroorat nahi)\\b|इंतज़ार|इंतजार|ज़रूरत नहीं|जरूरत नहीं|घर पर ही|अगली मुलाकात|कल तक")
    private val URGENT_REFERRAL = Regex("(?i)refer(?:red|ral)? (?:\\w+ )?(?:urgently|immediately)|(?:urgent|immediate) referral|" +
        "(?:urgently|immediately) (?:refer|take|transfer)|danger sign|referral is required|immediate referral required|" +
        "taken to a health facility immediately|तुरंत रेफ|तत्काल रेफ")

    private val POPULATIONS = mapOf(
        "infant" to Regex("(?i)new ?born|neonat|\\binfant|\\bbab(?:y|ies)\\b|young infant|navjaa?t|नवजात|शिशु|\\bshishu\\b"),
        "child" to Regex("(?i)\\bchild(?:ren)?\\b|\\btoddler|\\bunder[- ]?five|\\b(?:[2-9]|1[0-9]) (?:years?|months?)|बच्च[ेो]ं? \\(|बालक"),
        "pregnant" to Regex("(?i)pregnan|antenatal|\\banc\\b|garbhvati|गर्भवती|गर्भावस्था"),
        "mother" to Regex("(?i)\\bmother|\\bpostnatal|post-?partum|after delivery|lactating|\\bmaa\\b|माँ|मां|माता|प्रसूता|प्रसव के बाद"),
        "adolescent" to Regex("(?i)adolescen|teen(?:age)?|kishor|किशोर"),
    )
    // A statement about a newborn may cite a passage that says "baby" or "young infant", etc.
    // A statement about a newborn needs a young-infant source; older-child guidance never applies automatically.
    private val POPULATION_COMPATIBLE = mapOf("infant" to setOf("infant"), "child" to setOf("child", "infant"),
        "pregnant" to setOf("pregnant"), "mother" to setOf("mother", "pregnant"), "adolescent" to setOf("adolescent"))

    /**
     * Known high-risk field misconceptions that generic negation matching cannot catch because the
     * source states them by implication. Each rule: the statement recommends X, the evidence states
     * the rule that excludes X. Reviewed list, deliberately short.
     */
    private data class Contradiction(val id: String, val statement: Regex, val evidence: Regex, val item: Regex = Regex("(?!)"))
    private val CONTRADICTIONS = listOf(
        Contradiction("exclusive_breastfeeding",
            Regex("(?i)\\b(?:give|giving|offer|feed|add|pilay?e?|pilana|de|dein|dena)\\b[^.]{0,40}\\b(?:water|honey|ghutti|janam ghutti|cow'?s? milk|goat'?s? milk|formula|other milk|sugar water|tea|paani|pani|shahad)\\b|(?:पानी|शहद|घुट्टी|गाय का दूध|ऊपर का दूध)[^.]{0,20}(?:दें|पिलाएं|पिलाएँ|दे सकते)"),
            Regex("(?i)exclusive(?:ly)? breast ?fe|do not require anything else|nothing (?:else )?(?:other than|except) breast ?milk|only breast ?milk|no water|केवल स्तनपान"),
            Regex("(?i)\\b(?:water|honey|ghutti|formula|other milk|cow'?s? milk|paani|pani|shahad)\\b|पानी|शहद|घुट्टी")),
        Contradiction("bathing_newborn_early",
            Regex("(?i)\\bbathe? (?:the )?(?:new ?born|baby) (?:immediately|right after birth|on the first day|within (?:the first )?24 hours)"),
            Regex("(?i)(?:do not|don't|delay|avoid)[^.]{0,30}bath")),
    )

    /** Passages scoped to older children (2 months – 5 years etc.) with no young-infant scope. */
    private val OLDER_CHILD_SCOPE = Regex("(?i)2 months?\\s*(?:up ?to|to|-|–)\\s*(?:5|five) years|\\(?2\\s*m(?:onths)?\\s*[-–]\\s*5\\s*y|older (?:children|child)|" +
        "(?:12|6) months? (?:up ?to|to|-|–) (?:5|five) years|children (?:aged )?(?:1|2|3)[–-]5 years|under[- ]five children")
    private val YOUNG_INFANT_SCOPE = Regex("(?i)young infant|new ?born|neonat|first (?:28|42) days|(?:0|zero)\\s*[-–]\\s*2 months|(?:less|younger) than 2 months|up ?to 2 months|नवजात")
    /** The passage without bullet/parenthetical segments scoped to older children ("2 months up to 12 months – 50 breaths"). */
    private val OLDER_RANGE = Regex("(?i)\\b(?:[2-9]|1[0-9]|2[0-9]|two|six|twelve) months?\\s*(?:up ?to|to|-|–)\\s*\\d+\\s*(?:months?|years?)|\\b\\d+\\s*(?:up ?to|to|-|–)\\s*\\d+\\s*years?")
    fun withoutOlderChildSegments(passage: String): String =
        passage.split(Regex("•|\\(|\\)|;|\\n")).filterNot { OLDER_RANGE.containsMatchIn(it) }.joinToString(" ")
    fun childOnly(passage: String) = OLDER_CHILD_SCOPE.containsMatchIn(passage) && !YOUNG_INFANT_SCOPE.containsMatchIn(passage)

    private fun populations(t: String): Set<String> {
        val found = POPULATIONS.filter { it.value.containsMatchIn(t) }.keys
        // "baby"/"infant" inside a 2 months–5 years protocol is not young-infant guidance.
        return if (childOnly(t)) found - "infant" + "child" else found
    }

    // (1) Numbers from the worker's question may describe the case (age, duration), never set a rule.
    private val THRESHOLD_WORDS = Regex("(?i)\\b(?:or more|or above|or higher|or less|or below|above|below|over|under|more than|less than|at least|at most|greater|higher|lower|exceed\\w*|threshold|cut-?off|normal|fever is|means)\\b|[≥≤<>]|से (?:अधिक|ज़्यादा|ज्यादा|कम|ऊपर|नीचे)|या (?:उससे )?(?:अधिक|ज़्यादा|ज्यादा|कम)|सामान्य")
    private fun queryAllowed(q: Quantity, statement: String, queryQ: List<Quantity>): Boolean {
        if (!equivalent(q, queryQ)) return false
        if (q.unit !in setOf(null, "day", "week", "month", "hour")) return false
        // Threshold words next to THIS number ("38°C or more", "above 38") make it a rule, not a case detail.
        val v = if (q.value % 1.0 == 0.0) q.value.toLong().toString() else q.value.toString()
        return Regex("(?<![\\d.])" + Regex.escape(v) + "(?![\\d]|\\.\\d)").findAll(statement).none { m ->
            THRESHOLD_WORDS.containsMatchIn(statement.substring(maxOf(0, m.range.first - 16), minOf(statement.length, m.range.last + 1 + 36)))
        }
    }

    // (5) Clinical claims need traceable support.
    private val RECOMMEND = Regex("(?i)\\b(?:should|must|give|refer|take|keep|feed|apply|avoid|do not|don't|wash|measure|start|continue|stop|bathe|put|counsel)\\b|" +
        "करें|करे |दें|लें|ले जाएं|ले जाएँ|रखें|पिलाएं|पिलाएँ|चाहिए|\\b(?:karein|karen|dein|den|rakhen|rakhein|le jayen|pilayen|pilaye|chahiye|lein)\\b")

    // Concepts that framing sentences may use without the passage repeating them.
    private val FRAMING_CONCEPTS = setOf("newborn", "mother", "referral", "urgent", "danger", "temperature")

    private val STOP = setOf("this", "that", "with", "from", "have", "should", "must", "will", "your", "they", "them", "their", "there",
        "which", "when", "what", "also", "very", "into", "only", "more", "than", "then", "each", "every", "about", "after", "before",
        "until", "keep", "give", "make", "take", "help", "need", "needs", "does", "done", "being", "been", "were", "where", "while")

    private fun contentWords(t: String) = Regex("[a-z]{4,}").findAll(t.lowercase()).map { it.value }.filter { it !in STOP }
        .map { it.removeSuffix("ing").removeSuffix("ed").removeSuffix("es").removeSuffix("s").take(6) }.toSet()

    private fun sentencesOf(passage: String) = passage.split(Regex("(?<=[.!?।;])\\s+|\\n+|\\s•\\s")).map { it.trim() }.filter { it.length > 8 }

    /** The source sentence that best matches the statement (shared concepts first, then content words). */
    private fun aligned(statement: String, passages: List<String>): String? {
        val sc = ArogyaClinicalText.conceptsIn(statement); val sw = contentWords(statement)
        return passages.flatMap { sentencesOf(it) }.maxByOrNull { s ->
            ArogyaClinicalText.conceptsIn(s).count { it in sc } * 10 + contentWords(s).count { it in sw }
        }
    }

    private fun polarityConflict(statement: String, passages: List<String>): Boolean {
        val sentences = passages.flatMap { sentencesOf(it) }
        for (clause in statement.split(Regex(";|,?\\s+but\\s+|,?\\s+lekin\\s+|लेकिन")).map { it.trim() }.filter { it.length > 8 }) {
            val cc = ArogyaClinicalText.conceptsIn(clause) - FRAMING_CONCEPTS
            val cw = contentWords(clause)
            if (cc.isEmpty() && cw.size < 3) continue
            fun score(x: String): Double {
                val xc = ArogyaClinicalText.conceptsIn(x); val xw = contentWords(x)
                val conceptOk = cc.isNotEmpty() && cc.all { it in xc }
                val overlap = if (cw.isEmpty()) 0.0 else cw.count { it in xw }.toDouble() / cw.size
                // Measured on the phone: concept-level alignment produced false conflicts on real Llama
                // answers (danger-sign lists are full of "not able to feed", "no movement"). A generic
                // polarity flip is judged only against near-copies of a source sentence.
                val back = if (xw.isEmpty()) 0.0 else xw.count { it in cw }.toDouble() / xw.size
                return if (overlap >= 0.85 && back >= 0.85 && cw.size >= 3) overlap else -1.0
            }
            val scored = sentences.map { it to score(it) }.filter { it.second >= 0 }
            if (scored.isEmpty()) continue
            val top = scored.maxOf { it.second }
            val best = scored.filter { it.second >= top - 0.05 }.map { it.first }
            val cNeg = NEG.containsMatchIn(clause.lowercase())
            // A negated misconception ("do not give water") agrees with the rule its evidence states by implication.
            if (cNeg && CONTRADICTIONS.any { c -> c.item.containsMatchIn(clause) && passages.any { c.evidence.containsMatchIn(it) } }) continue
            if (best.none { NEG.containsMatchIn(it.lowercase()) == cNeg }) return true
        }
        return false
    }

    /**
     * For an answer with no citation tags: each sentence gets the evidence block that best supports it
     * (shared clinical concepts, numbers, content words). Sentences with no supporting block are
     * dropped. Answers that already cite are returned unchanged. Pure provenance assignment: every
     * check in [verify] still runs on the result.
     */
    fun attachCitations(answer: String, evidence: Map<String, String>): String {
        if (evidence.isEmpty() || CITE.containsMatchIn(answer)) return answer
        val out = ArrayList<String>()
        for (raw in answer.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }) {
            val s = ArogyaClinicalText.normalise(raw)
            val sc = ArogyaClinicalText.conceptsIn(raw); val sw = contentWords(s); val sq = quantities(raw)
            val scored = evidence.map { (id, text) ->
                val pc = ArogyaClinicalText.conceptsIn(text); val pw = contentWords(text)
                val score = sc.count { it in pc } * 3.0 + sw.count { it in pw } + (if (sq.isNotEmpty() && sq.all { equivalent(it, quantities(text)) }) 2.0 else 0.0)
                id to score
            }.sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: continue
            if (best.second < 3.0) { continue }  // nothing in the guide supports this sentence: drop it
            val body = raw.trimEnd()
            val end = if (body.endsWith(".") || body.endsWith("।") || body.endsWith("!") || body.endsWith("?")) body.last().toString() else ""
            out += body.dropLast(end.length).trimEnd() + " [${best.first}]" + end
        }
        return if (out.isEmpty()) answer else out.joinToString(" ")
    }

    /** Problems that make the whole answer unsafe; anything else only removes that one sentence. */
    private val HARD = listOf("number_not_in_cited_source", "population_mismatch", "older_child_source_for_newborn", "treatment_out_of_scope",
        "supplement_not_in_cited_source", "urgency_downgrade", "polarity_conflict", "contradicts_source_rule", "unknown_citation")

    /**
     * Removes sentences the guide does not support (soft problems) and keeps the rest with their
     * citations. Returns null when a hard problem is present or nothing supported remains.
     */
    fun prune(answer: String, evidence: Map<String, String>, order: List<String>, query: String): String? {
        val v = verify(answer, evidence, order, query)
        if (v.accepted) return answer
        if (v.statements.isEmpty()) return null
        if (v.statements.any { st -> st.problem != null && HARD.any { st.problem.startsWith(it) } }) return null
        val kept = v.statements.filter { it.problem == null }
        if (kept.isEmpty()) return null
        val text = kept.joinToString(" ") { st -> st.statement.trimEnd(' ', '.') + " " + st.sourceIds.joinToString("") { "[$it]" } + "." }
        return if (verify(text, evidence, order, query).accepted) text else null
    }

    fun verify(answer: String, evidence: Map<String, String>, order: List<String>, query: String): Verdict {
        val text = answer.trim()
        if (text.isBlank()) return Verdict(false, "blank", emptyList())
        val known = evidence.keys
        val allIds = citationIds(text, order, known)
        if (allIds.isEmpty()) return Verdict(false, "asha_no_citation", emptyList())
        allIds.firstOrNull { it !in known }?.let { return Verdict(false, "asha_unknown_citation:$it", emptyList()) }
        val queryQuantities = quantities(query)
        val qn = ArogyaClinicalText.normalise(query)
        val infantQuery = (ArogyaDangerSigns.textAgeDays(qn)?.let { it <= 59 } ?: false) || Regex("new ?born|neonat|navjaa?t|नवजात|young infant").containsMatchIn(qn)
        val evidenceUrgent = evidence.values.any { URGENT_REFERRAL.containsMatchIn(it) }

        // Statements with their citations; an uncited statement inherits the next citation in its paragraph.
        val results = ArrayList<StatementResult>()
        for (paragraph in text.split(Regex("\\n\\s*\\n|\\n(?=\\s*(?:[•*-]|\\d{1,2}[.)])\\s)"))) {
            val parts = paragraph.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            val ids = parts.map { citationIds(it, order, known) }
            parts.forEachIndexed { i, s ->
                val own = ids[i].ifEmpty { ids.drop(i + 1).firstOrNull { it.isNotEmpty() } ?: emptyList() }
                val clean = s.replace(CITE, "").trim().trimStart('•', '*', '-').trim()
                if (clean.length < 3) return@forEachIndexed
                results += StatementResult(clean, own, problem(clean, own, evidence, queryQuantities, evidenceUrgent, infantQuery))
            }
        }
        val bad = results.firstOrNull { it.problem != null }
        return if (bad != null) Verdict(false, "asha_${bad.problem}", results) else Verdict(true, "asha_grounded_paraphrase", results)
    }

    private fun problem(statement: String, ids: List<String>, evidence: Map<String, String>, queryQ: List<Quantity>, evidenceUrgent: Boolean,
                        infantQuery: Boolean = false): String? {
        val s = ArogyaClinicalText.normalise(statement)
        val allConcepts = ArogyaClinicalText.conceptsIn(statement)
        val clinical = (allConcepts - FRAMING_CONCEPTS).isNotEmpty() || quantities(statement).isNotEmpty() || RECOMMEND.containsMatchIn(s) ||
            ArogyaEvidenceSafety.medication(statement)
        // (5) a clinical claim must be traceable to a cited passage (own citation or the paragraph's).
        if (ids.isEmpty() && clinical) return "uncited_clinical_claim"
        val passages = if (ids.isEmpty()) evidence.values.toList() else ids.mapNotNull { evidence[it] }
        val joined = passages.joinToString("\n")
        // (2) a newborn question answered from older-child guidance only.
        if (infantQuery && clinical && passages.isNotEmpty() && passages.all { childOnly(it) }) return "older_child_source_for_newborn"
        // 6. medicines
        if (ArogyaEvidenceSafety.medication(statement)) return "treatment_out_of_scope"
        ArogyaEvidenceSafety.supplement(statement)?.let { term ->
            if (!ArogyaEvidenceSafety.supplementNamedIn(term, joined)) return "supplement_not_in_cited_source:$term"
        }
        // 3a. known misconception rules (statement must not itself be negated)
        CONTRADICTIONS.firstOrNull { c -> c.statement.containsMatchIn(s) && !Regex("(?i)\\b(?:do not|don't|never|avoid|no)\\b|मत|न दें|नहीं").containsMatchIn(s) &&
            c.evidence.containsMatchIn(joined) }?.let { return "contradicts_source_rule:${it.id}" }
        // 5. urgency downgrade
        if (DOWNGRADE.containsMatchIn(s) && evidenceUrgent) return "urgency_downgrade"
        // 2. numbers
        // A newborn statement may only use numbers from the passage's young-infant parts.
        val infantStatement = infantQuery || "infant" in populations(s)
        val pq = quantities(if (infantStatement) withoutOlderChildSegments(joined) else joined)
        quantities(statement).firstOrNull { q -> !equivalent(q, pq) && !queryAllowed(q, s, queryQ) }?.let {
            return "number_not_in_cited_source:${it.value}${it.unit ?: ""}"
        }
        // 4. population
        val sp = populations(s); val pp = populations(joined.lowercase())
        if (pp.isNotEmpty()) sp.firstOrNull { pop -> POPULATION_COMPATIBLE.getValue(pop).none { it in pp } }?.let { return "population_mismatch:$it" }
        // 7. concepts
        val sc = ArogyaClinicalText.conceptsIn(statement) - FRAMING_CONCEPTS
        val pc = ArogyaClinicalText.conceptsIn(joined)
        sc.firstOrNull { it !in pc }?.let { return "concept_not_in_cited_source:$it" }
        // 3. polarity, clause by clause: conflict only when the best-matching source sentence(s) all
        //    have the opposite polarity (a tied/equal match with the same polarity clears it).
        if (polarityConflict(s, passages)) return "polarity_conflict"
        // 7b. same-script lexical support (English statement vs English evidence)
        if (!ArogyaClinicalText.isDevanagari(statement) && !ArogyaClinicalText.isDevanagari(joined)) {
            val words = contentWords(s)
            if (words.size >= 4) {
                val ev = contentWords(joined)
                val found = words.count { it in ev }
                if (found * 2 < words.size) return "low_word_support:$found/${words.size}"
            }
        }
        // (5) positive anchor: a shared clinical concept, a matched number, or (same script) shared content words.
        if (clinical) {
            val pc2 = ArogyaClinicalText.conceptsIn(joined)
            // Population words (baby, mother) alone are not support.
            val conceptAnchor = (allConcepts - setOf("newborn", "mother", "pregnancy")).any { it in pc2 }
            val numberAnchor = quantities(statement).any { equivalent(it, pq) }
            val wordAnchor = !ArogyaClinicalText.isDevanagari(statement) && contentWords(s).count { it in contentWords(joined) } >= 2
            if (!conceptAnchor && !numberAnchor && !wordAnchor) return "untraceable_clinical_claim"
        }
        return null
    }
}
