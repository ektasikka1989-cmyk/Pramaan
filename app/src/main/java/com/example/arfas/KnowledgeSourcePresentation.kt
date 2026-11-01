package com.example.arfas

/** Converts chunk-level provenance into truthful document-level source rows. */
object KnowledgeSourcePresentation {
    /**
     * A readable document name from a pack's stored title or file name: drops ".pdf", download-page
     * text ("Download (653.44 KB)"), list numbers and batch codes ("B8-", "B1-119_1-"), and turns
     * file-name dashes into spaces. Text that is already a clean title is returned unchanged.
     */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.isBlank()) return t
        val fileLike = Regex("(?i)\\.(pdf|docx?|pptx?|xlsx?|txt|html?)$").containsMatchIn(t) || (' ' !in t && Regex("[-_]").containsMatchIn(t))
        t = t.replace(Regex("(?i)\\.(pdf|docx?|pptx?|xlsx?|txt|html?)$"), "")
        t = t.replace(Regex("(?i)\\s*download\\s*\\(\\s*[\\d.]+\\s*[kmg]b\\s*\\)\\s*"), " ")
        t = t.replace(Regex("(?i)\\s*\\(\\s*[\\d.]+\\s*[kmg]b\\s*\\)"), "")
        if (fileLike) {
            // Batch codes as packs store them ("B8-", "B1-119_1-"); "L1-Purchase" keeps its L1.
            t = t.replace(Regex("^B\\d+(?:-\\d+)?(?:_\\d+)?[-_]+(?=[A-Za-z])"), "")
            // "L-1", "COVID-19": a letter code joined to its number keeps its dash.
            t = t.replace(Regex("\\b([A-Za-z]{1,5})-(\\d{1,3})\\b"), "$1\u0000$2")
            t = t.replace(Regex("[-_]{2,}"), " ").replace('_', ' ').replace(Regex("(?<=\\S)-(?=\\S)"), " ")
            t = t.replace('\u0000', '-')
        }
        t = t.replace(Regex("^\\d{1,2}\\s+(?=[A-Z])"), "")
        return t.replace(Regex("\\s+"), " ").trim().ifBlank { raw.trim() }
    }

    private data class SourceGroup(
        val title: String,
        val url: String?,
        val pages: LinkedHashSet<Int> = linkedSetOf()
    )

    private data class ParsedRef(val id: String?, val key: String, val title: String, val url: String?, val pages: List<Int>)

    /** Page ranges ("— pages 38–40") are expanded; a range longer than this is kept as its ends. */
    private const val MAX_EXPANDED_RANGE = 50
    private val PAGE = Regex("(?i)\\s+—\\s+pages?\\s+(\\d+)(?:\\s*[–-]\\s*(\\d+))?")
    private val PAGE_TAIL = Regex("(?i)\\s+—\\s+pages?\\s+\\d+.*$")
    private val REF_ID = Regex("(?i)^(S\\d+)\\s*::\\s*")
    /** Source entries can carry their passage ID more than once ("S3 :: S3 :: title"); strip all. */
    private val REF_ID_PREFIXES = Regex("(?i)^(?:S\\d+\\s*::\\s*)+")

    private fun parse(sourceId: String): ParsedRef? {
        if (!sourceId.startsWith("kbref:")) return null
        val raw = sourceId.removePrefix("kbref:").trim().takeIf { it.isNotBlank() } ?: return null
        val id = REF_ID.find(raw)?.groupValues?.getOrNull(1)?.uppercase()
        val withoutId = raw.replace(REF_ID_PREFIXES, "").trim()
        val url = Regex("https?://\\S+").find(withoutId)?.value?.trimEnd('|', ',', ')', ']', '.')
        val label = if (url == null) withoutId else withoutId.replace(url, "").trim().trimEnd('|').trim()
        val pages = PAGE.find(label)?.let { m ->
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues.getOrNull(2)?.toIntOrNull()
            when {
                start == null -> emptyList()
                end == null || end <= start -> listOf(start)
                end - start > MAX_EXPANDED_RANGE -> listOf(start, end)
                else -> (start..end).toList()
            }
        }.orEmpty()
        val title = label.replace(PAGE_TAIL, "").trim().ifBlank { label.ifBlank { url.orEmpty() } }
        return ParsedRef(id, url?.lowercase() ?: title.lowercase(), title, url, pages)
    }

    fun visibleSources(sourceIds: List<String>): List<String> {
        val groups = linkedMapOf<String, SourceGroup>()
        sourceIds.mapNotNull(::parse).forEach { ref ->
            groups.getOrPut(ref.key) { SourceGroup(ref.title, ref.url) }.pages += ref.pages
        }
        return groups.values.map { group ->
            buildString {
                append(group.title)
                if (group.pages.isNotEmpty()) {
                    val runs = pageRuns(group.pages.sorted())
                    append(if (group.pages.size == 1) " — page " else " — pages ")
                    append(runs)
                }
                group.url?.let { append(" | ").append(it) }
            }
        }
    }

    /** "2, 19, 38–40" from sorted pages. */
    private fun pageRuns(sorted: List<Int>): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            out += if (j > i) "${sorted[i]}–${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return out.joinToString(", ")
    }

    /**
     * Replaces the answer model's internal passage markers ([S2], [S3, S7]) with the numbers of the
     * document rows shown under the answer ([1], [1, 2]): several passages of one document become
     * that document's number, and markers of passages that are not listed are removed. The shown
     * list is [visibleSources] of the same [sourceIds], in the same order.
     */
    /**
     * Report-style openings a small model writes around the evidence ("[S2] states that X",
     * "According to the evidence, X", "The document also mentions that X") become the plain
     * statement "X", with the citation moved to the end of the sentence. Removing an unconfirmed
     * [S2] otherwise left sentences starting "states that …".
     */
    fun naturalPhrasing(answer: String): String {
        val start = "(^|(?<=[.!?:।])\\s+|\\n\\s*(?:[-•*]\\s*)?)"
        val cite = "((?:\\[\\s*S\\d+(?:\\s*[,;]\\s*S\\d+)*\\s*]\\s*)+)"
        val verb = "(?:also\\s+)?(?:states?|mentions?|says?|notes?|explains?|indicates?|suggests?|adds?|highlights?|emphasi[sz]es?|recommends?|advises?)(?:\\s+that)?[,:]?\\s+"
        var out = answer
        // "[S2] states that X." / "Source [S2] also mentions X." -> "X [S2]."
        out = Regex(start + "(?:(?:source|document|passage)\\s*)?" + cite + verb + "([^.!?।\\n]*)", RegexOption.IGNORE_CASE).replace(out) { m ->
            m.groupValues[1] + capitalize(m.groupValues[3].trim()) + " " + m.groupValues[2].trim()
        }
        // "According to the evidence/document(s) [S1], X" / "The evidence (also) states that X" -> "X"
        out = Regex(start + "(?:according to|as per|based on)\\s+(?:the\\s+)?(?:supplied\\s+|provided\\s+|available\\s+)?(?:evidence|documents?|sources?|passages?|knowledge base|guidelines?)\\s*(?:\\[[^\\]]*]\\s*)*,?\\s*(\\p{L})", RegexOption.IGNORE_CASE).replace(out) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
        out = Regex(start + "(?:the|this|that)\\s+(?:evidence|document|source|passage|text|module|guideline)s?\\s+" + verb + "(\\p{L})", RegexOption.IGNORE_CASE).replace(out) { m ->
            m.groupValues[1] + m.groupValues[2].uppercase()
        }
        return out
    }

    private fun capitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun renumberCitations(answer: String, sourceIds: List<String>): String {
        val docNumber = HashMap<String, Int>()
        val keys = LinkedHashMap<String, Int>()
        sourceIds.mapNotNull(::parse).forEach { ref ->
            val n = keys.getOrPut(ref.key) { keys.size + 1 }
            ref.id?.let { docNumber[it] = n }
        }
        val marker = Regex("\\[\\s*(S\\d+(?:\\s*[,;]\\s*S\\d+)*)\\s*]", RegexOption.IGNORE_CASE)
        var out = marker.replace(naturalPhrasing(answer)) { m ->
            val numbers = m.groupValues[1].split(Regex("\\s*[,;]\\s*"))
                .mapNotNull { docNumber[it.trim().uppercase()] }.distinct().sorted()
            if (numbers.isEmpty()) "" else numbers.joinToString(", ", "[", "]")
        }
        // "[1][1]" or "[1] [1]" after merging passages of one document -> "[1]".
        out = Regex("(\\[[\\d, ]+])(?:\\s*\\1)+").replace(out) { it.groupValues[1] }
        // Tidy spaces left by removed markers.
        out = Regex("[ \\t]+([.,;:!?])").replace(out, "$1")
        out = Regex("[ \\t]{2,}").replace(out, " ")
        return out.trim()
    }

    /**
     * Link that opens a source row's document at its first cited page (PDF viewers that support it
     * jump to "#page=N"); null when the row has no link.
     */
    fun openTarget(row: String): String? {
        val url = Regex("https?://\\S+").find(row)?.value?.trimEnd('|', ',', ')', ']', '.') ?: return null
        val page = Regex("(?i)—\\s+pages?\\s+(\\d+)").find(row)?.groupValues?.get(1)
        return if (page != null && !url.contains('#')) "$url#page=$page" else url
    }

    // ── Passage view: tap a source row to read the passages used, best-matching sentence marked ──

    const val PASSAGE_PREFIX = "kbpassage:"
    const val NOT_FOUND_PREFIX = "kbnotfound:"
    const val WEAK_PREFIX = "kbweak:"
    /** The match is clearly off-topic: the warning is shown above the answer. */
    const val VERY_WEAK = "kbveryweak"
    private const val FIELD = "\u001F"
    private const val MAX_PASSAGE_CHARS = 1_500

    private const val HL_SEP = "\u001E"

    /**
     * Sentence ends for highlighting: not after "Rs.", "No.", "Dr.", "e.g." and similar, and not
     * before a number ("Rs. 50,000" stays one sentence).
     */
    private val SENTENCE_END = Regex(
        "(?<!\\b(?:Rs|No|Nos|Dr|Tab|Cap|Inj|Syp|Mr|Mrs|Ms|Smt|Sh|Shri|St|Govt|Dept|viz|etc|vs|approx|Para|para|Art|Sec|Fig|Vol|Ch|Sl|S\\.No|e\\.g|i\\.e)\\.)" +
            "(?<=[.;?!\u0964])\\s+(?!\\d)"
    )

    /** A passage behind a source row, with the sentences the answer's statements come from. */
    data class SourcePassage(val text: String, val highlights: List<String>) {
        val highlight: String get() = highlights.firstOrNull().orEmpty()
    }

    /** Marker carrying passage [id]'s text and the sentences that support the answer (kept out of reports and source rows). */
    fun passageMarker(id: String, text: String, answer: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(MAX_PASSAGE_CHARS)
        val marks = supportingSentences(clean, answer).ifEmpty { listOfNotNull(bestSentence(clean, answer).takeIf { it.isNotBlank() }) }
        return PASSAGE_PREFIX + id.uppercase() + FIELD + marks.joinToString(HL_SEP) { it.replace(FIELD, " ") } + FIELD + clean
    }

    /**
     * The passage sentences the answer's statements come from: for each statement of the answer,
     * the passage sentence sharing the most words with it (at least 3, or 2 with a shared number).
     * At most 6, in passage order; a statement drawn from two sentences marks both. Statement by statement, so an answer drawn from three sentences marks three.
     */
    fun supportingSentences(passage: String, answer: String, max: Int = 6): List<String> {
        // Letters with their combining marks: Hindi words ("व्यायाम") are not broken at each matra.
        fun words(t: String) = Regex("[\\p{L}\\p{M}\\p{N}]{4,}").findAll(t.lowercase()).map { it.value }.toSet()
        fun nums(t: String) = Regex("\\d+(?:[.,]\\d+)*").findAll(t).map { it.value.replace(",", "") }.filter { it.length >= 2 }.toSet()
        val sentences = passage.split(SENTENCE_END).map { it.trim() }.filter { it.length >= 20 }
        if (sentences.isEmpty()) return emptyList()
        val sw = sentences.map { words(it) }
        val sn = sentences.map { nums(it) }
        // The answer's statements, plus its short list items ("Amitriptyline", "Escitalopram/Fluoxetine").
        val items = answer.lines().map { l ->
            l.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim().replace(Regex("^\\d{1,2}[.)]\\s+"), "")
                .replace(Regex("\\s*\\[(?:S?\\d+(?:\\s*[,–-]\\s*S?\\d+)*)]"), "").replace("**", "").trim()
        }.filter { it.isNotBlank() && it.split(Regex("\\s+")).size <= 3 && !it.endsWith(":") }
        val chosen = LinkedHashSet<Int>()
        for (st in (AnswerProof.statements(answer) + items).distinct()) {
            val w = words(st); val n = nums(st)
            if (w.isEmpty()) continue
            val short = st.split(Regex("\\s+")).size <= 3
            val ranked = sentences.indices.sortedByDescending { i -> sw[i].count { it in w } + 2 * sn[i].count { it in n } }
            val best = ranked.firstOrNull() ?: continue
            val shared = sw[best].count { it in w }
            val numHit = sn[best].any { it in n }
            val ok = if (short) shared == w.size else shared >= 3 || (shared >= 2 && numHit)
            if (ok) {
                chosen += best
                // A statement that joins two sentences ("Lady Medical Officers at the PHC can be trained,
                // after training at a tertiary centre"): the second one, when it adds 3+ of its words.
                ranked.getOrNull(1)?.let { second ->
                    val extra = sw[second].count { it in w && it !in sw[best] }
                    if (!short && extra >= 3 && chosen.size < max) chosen += second
                }
            }
            if (chosen.size >= max) break
        }
        return chosen.sorted().map { sentences[it] }
    }

    // ── "The document says": the source's own sentence shown before the model's explanation ──

    const val QUOTE_PREFIX = "kbquote:"

    data class Quote(val text: String, val source: String, val mismatch: Boolean)

    /**
     * The sentence(s) of the cited passages that answer the question, in the document's own words:
     * each sentence is scored by the question's words (the user's and the English search rewrite,
     * so a Hindi question over English documents still matches), the answer's words, and numbers
     * shared with the answer; the first cited passage wins ties. A lead-in ("…are:") brings the
     * next sentence or two. Null when no sentence shares a content word with the question.
     */
    fun chooseQuote(question: String, rewrite: String, answer: String, passages: List<Pair<String, String>>): Pair<String, String>? {
        // Word stems (first 5 letters: "prepared" ~ "preparation"), and short acronyms ("ORS", "IFA").
        fun words(t: String) = (Regex("[\\p{L}\\p{M}\\p{N}]{4,}").findAll(t.lowercase()).map { it.value }
            .filterNot { it in QUOTE_STOP }.map { it.take(5) } +
            Regex("\\b[A-Z]{3}\\b").findAll(t).map { it.value.lowercase() }).toSet()
        fun nums(t: String) = Regex("\\d+(?:[.,]\\d+)*").findAll(t).map { it.value.replace(",", "") }.filter { it.length >= 2 || (it.toDoubleOrNull() ?: 0.0) >= 2 }.toSet()
        val qw = words(question) + words(rewrite)
        val aw = words(answer)
        val an = nums(answer.replace(Regex("(?i)pages?\\s*\\d+"), ""))
        var best: Triple<Double, Int, Int>? = null
        val split = passages.map { (_, text) ->
            text.replace(Regex("\\s+"), " ").split(SENTENCE_END).map { it.trim() }.filter { it.length in 25..600 }
        }
        split.forEachIndexed { pi, sentences ->
            sentences.forEachIndexed { si, s ->
                val sw = words(s)
                val q = sw.count { it in qw }
                if (q == 0) return@forEachIndexed
                val score = 2.0 * q + sw.count { it in aw } + 3.0 * nums(s).count { it in an } - 0.5 * pi
                if (best == null || score > best!!.first) best = Triple(score, pi, si)
            }
        }
        val (_, pi, si) = best ?: return null
        val sentences = split[pi]
        // One sentence only: extending a short sentence pulled in the next bullet's text
        // ("…cord stump" + eye-ointment instructions). A lead-in ("…are:") still takes what
        // follows it, at most two sentences. Bullet/number markers are stripped.
        var quote = stripBullet(sentences[si])
        var next = si + 1
        var added = 0
        while (quote.endsWith(":") && next < sentences.size && quote.length < 380 && added < 2) {
            quote += " " + stripBullet(sentences[next]); next++; added++
        }
        return quote.take(600) to passages[pi].first
    }

    /** Strips a bullet or list-number marker ("* ", "• ", "- ", "1. ", "2) ") from a quote. */
    fun stripBullet(s: String): String = s.trim()
        .replace(Regex("^[*•\\-–]+\\s+"), "")
        .replace(Regex("^\\d{1,2}[.)]\\s+"), "")
        .trim()

    private val QUOTE_STOP = setOf(
        "what", "which", "when", "where", "should", "would", "could", "about", "there", "their", "these", "those",
        "with", "from", "that", "this", "have", "will", "your", "they", "them", "than", "into", "also", "such", "been",
        "kitna", "kitne", "kitni", "karna", "chahiye", "karein", "kaise", "क्या", "कैसे", "कितने", "कितनी", "कितना", "चाहिए"
    )

    /** True when the answer gives numbers and the quote gives numbers, and they share none. */
    fun quoteDisagrees(quote: String, answer: String): Boolean {
        fun nums(t: String) = Regex("\\d+(?:[.,]\\d+)*").findAll(t.replace(Regex("(?i)pages?\\s*\\d+|\\[S?\\d+]"), ""))
            .map { it.value.replace(",", "") }.filter { it.length >= 2 || (it.toDoubleOrNull() ?: 0.0) >= 2 }.toSet()
        val q = nums(quote); val a = nums(answer)
        return q.isNotEmpty() && a.isNotEmpty() && (q intersect a).isEmpty()
    }

    fun quoteMarker(text: String, source: String, mismatch: Boolean): String =
        QUOTE_PREFIX + (if (mismatch) "1" else "0") + FIELD + source.replace(FIELD, " ") + FIELD + text.replace(FIELD, " ")

    fun quoteOf(sourceIds: List<String>): Quote? = sourceIds.firstOrNull { it.startsWith(QUOTE_PREFIX) }?.let { m ->
        val parts = m.removePrefix(QUOTE_PREFIX).split(FIELD, limit = 3)
        if (parts.size < 3 || parts[2].isBlank()) null else Quote(parts[2], parts[1], parts[0] == "1")
    }

    /** Internal markers that are not sources (never listed in reports or source rows). */
    fun isInternalMarker(sourceId: String): Boolean =
        sourceId.startsWith(PASSAGE_PREFIX) || sourceId.startsWith(NOT_FOUND_PREFIX) || sourceId.startsWith(WEAK_PREFIX) ||
            sourceId.startsWith(QUOTE_PREFIX) ||
            sourceId == "verification:optional" || sourceId == VERY_WEAK || sourceId.startsWith("proof:") ||
            sourceId == "visit_note" || sourceId == "due_list"

    /** Passages for each row of [visibleSources] (same order); empty lists when none were recorded. */
    fun passagesForRows(sourceIds: List<String>): List<List<SourcePassage>> {
        val keys = LinkedHashMap<String, Int>()
        val rowOfId = HashMap<String, Int>()
        sourceIds.mapNotNull(::parse).forEach { ref ->
            val row = keys.getOrPut(ref.key) { keys.size }
            ref.id?.let { rowOfId[it] = row }
        }
        val out = List(keys.size) { mutableListOf<SourcePassage>() }
        sourceIds.filter { it.startsWith(PASSAGE_PREFIX) }.forEach { m ->
            val parts = m.removePrefix(PASSAGE_PREFIX).split(FIELD, limit = 3)
            if (parts.size < 3) return@forEach
            val row = rowOfId[parts[0].uppercase()] ?: return@forEach
            if (out[row].none { it.text == parts[2] }) out[row] += SourcePassage(parts[2], parts[1].split(HL_SEP).filter { it.isNotBlank() })
        }
        return out
    }

    /** "Closest topics" carried by a not-found / weak-match marker. */
    fun topicsOf(marker: String): List<String> =
        marker.substringAfter(':').split(FIELD).map { it.trim() }.filter { it.isNotBlank() }

    fun topicsMarker(prefix: String, topics: List<String>): String =
        prefix + topics.map { it.replace(FIELD, " ").trim() }.filter { it.isNotBlank() }.distinct().take(3).joinToString(FIELD)

    /** Short topic label of a source display ("S2 :: GFR 2017 — page 45 | url" → "GFR 2017 — page 45"). */
    fun topicLabel(display: String): String {
        val withoutId = display.replace(REF_ID_PREFIXES, "").trim()
        val url = Regex("https?://\\S+").find(withoutId)?.value
        return (if (url == null) withoutId else withoutId.replace(url, "")).trim().trimEnd('|').trim().take(120)
    }

    /** The sentence of [passage] sharing the most words (4+ letters) with [answer]; empty when none. */
    fun bestSentence(passage: String, answer: String): String {
        fun words(t: String) = Regex("[\\p{L}\\p{M}\\p{N}]{4,}").findAll(t.lowercase()).map { it.value }.toSet()
        val answerWords = words(answer)
        if (answerWords.isEmpty()) return ""
        val sentences = passage.split(SENTENCE_END).map { it.trim() }.filter { it.length >= 20 }
        val best = sentences.maxByOrNull { s -> words(s).count { it in answerWords } } ?: return ""
        return if (words(best).count { it in answerWords } >= 2) best else ""
    }
}
