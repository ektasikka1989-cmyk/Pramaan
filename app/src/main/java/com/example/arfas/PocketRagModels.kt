package com.example.arfas

/** PocketSage-inspired domain model, deliberately independent of UI/AR/general routing. */
data class PocketRagSelection(
    val kind: String,
    val id: String,
    val displayName: String
) {
    val sessionKey: String get() = "$kind:$id"
}

data class PocketRagAttachmentRecord(
    val sourceId: String,
    val text: String,
    val attributes: Map<String, String>
)

data class PocketRagSource(
    val id: String,
    val display: String,
    val document: String = "",
    val page: String = "",
    val section: String = "",
    val locator: String = "",
    val recordId: String = ""
)

data class PocketRagEvidenceBlock(
    val id: String,
    val sourceId: String,
    val text: String
)

data class PocketRagPacket(
    val selection: PocketRagSelection,
    val query: String,
    val evidenceBlocks: List<PocketRagEvidenceBlock>,
    val sources: List<PocketRagSource>,
    val coverageComplete: Boolean,
    val clauseCoverage: List<GenericKbClauseCoverage> = emptyList(),
    /** Previous user question when retrieval resolved this turn as its follow-up; empty otherwise. */
    val followUpOf: String = "",
    /** Re-ranker relevance of the best passage for this question (logit); null when it did not run. */
    val bestRelevance: Float? = null,
    /** Values of the same kind stated differently by different sources ("S1: 1.5%; S3: 0.5 %"); empty when none. */
    val valueConflict: String = "",
    /** Abbreviations in the question with the pack's own long forms ("AON = acceptance of necessity"); empty when none. */
    val terms: String = "",
    /** Part C2: reference notes from a strongly matched verified answer (guidance only, never evidence). */
    val goldReference: String = "",
    /** Item 4 "More detail": replaces the type length line (ArogyaQuestionType.detailLine); empty = normal line. */
    val lengthLineOverride: String = "",
    /** Item 4 "More detail": the output budget is the full per-part ceiling, not the evidence estimate. */
    val detailMode: Boolean = false
) {
    val evidenceText: String = evidenceBlocks.joinToString("\n\n") { block ->
        "[${block.id} | ${block.sourceId}]\n${block.text.trim()}"
    }
    /**
     * The evidence as the answer model reads it: one "[S1] title — page — section" line per passage,
     * then its text. The full form above repeats the source ID three times ("[E1 | S1]", "Source ID:
     * S1", "Source: S1 ::") and carries the document URL: ~45 tokens per passage of prompt that the
     * phone must read before the first word, and header text a 3B model sometimes copied.
     */
    val modelEvidenceText: String = evidenceBlocks.joinToString("\n\n") { block ->
        val lines = block.text.trim().lines()
        val label = lines.firstOrNull { it.startsWith("Source:") }
            ?.removePrefix("Source:")?.trim()
            ?.replace(Regex("^S\\d+\\s*::\\s*"), "")
            ?.replace(Regex("\\s*\\|\\s*https?://\\S+\\s*$"), "")
            ?.trim().orEmpty()
        val body = lines.filterNot { it.startsWith("Source ID:") || it.startsWith("Source:") }.joinToString("\n").trim()
        "[${block.sourceId}]" + (if (label.isNotEmpty()) " $label" else "") + "\n" + body
    }
    val sourceIds: List<String> = sources.map { "kbref:${it.id} :: ${it.display}" }.distinct()
    private val evidenceSourceKeys: Set<String> = evidenceBlocks.map { it.sourceId.uppercase() }.toSet()

    /**
     * Source references supporting the generated answer: the [S#] markers the model wrote,
     * or, when it wrote none, the evidence blocks the answer demonstrably draws on.
     */
    companion object {
        /** Distinctive answer items a passage must share to count as support (citation check and fallback). */
        const val MIN_SHARED_ITEMS = 3
    }

    fun citedSourceIds(answer: String): List<String> {
        // Single ([S3]) and combined ([S3, S7]) markers.
        val cited = Regex("\\[\\s*(S\\d+(?:\\s*[,;]\\s*S\\d+)*)\\s*]", RegexOption.IGNORE_CASE).findAll(answer)
            .flatMap { m -> m.groupValues[1].split(Regex("\\s*[,;]\\s*")).asSequence() }
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        // A small model often cites [S1] by habit. A cited source is kept only when its passage
        // shares distinctive content with the answer; if none does (e.g. a Hindi paraphrase of
        // English evidence), the model's own citations are kept rather than showing nothing.
        val checked = if (cited.isEmpty()) emptySet() else {
            val shared = sharedDistinctiveItems(answer)
            val supported = cited.filter { (shared[it] ?: 0) >= MIN_SHARED_ITEMS }.toSet()
            // A Hindi paraphrase of English evidence shares no words with it: only then are the
            // model's own citations trusted. Otherwise an unsupported [S1] is not shown as a source.
            when {
                supported.isNotEmpty() -> supported
                // Nothing distinctive to check ("See [S2].") or a cross-script paraphrase: trust the marker.
                shared.isEmpty() || KbReplyText.dominantScript(answer) != KbReplyText.dominantScript(evidenceText) -> cited
                // A fluent answer whose cited passages do not contain its content: no source is shown.
                else -> emptySet()
            }
        }
        val ids = checked.ifEmpty { supportedSourceKeys(answer) }
        return sources.filter { it.id.uppercase() in ids }
            .map { "kbref:${it.id} :: ${it.display}" }.distinct()
    }

    /** Per source ID: how many distinctive answer items (see [supportedSourceKeys]) its passage contains. */
    fun sharedDistinctiveItems(answer: String): Map<String, Int> {
        val answerItems = distinctiveItems(Regex("\\[S\\d+\\]", RegexOption.IGNORE_CASE).replace(answer, " ")) - distinctiveItems(query)
        if (answerItems.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        evidenceBlocks.forEach { b ->
            val n = distinctiveItems(b.text).count { it in answerItems }
            out[b.sourceId.uppercase()] = maxOf(out[b.sourceId.uppercase()] ?: 0, n)
        }
        return out
    }

    private fun distinctiveItems(text: String): Set<String> = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}.,/-]*")
        .findAll(text.lowercase())
        .map { it.value.trim('.', ',', '/', '-') }
        .filter { v -> v.length >= 5 || (v.any(Char::isDigit) && v.count(Char::isDigit) >= 2) }
        .toSet()

    /**
     * Fallback attribution when the answer carries no [S#] markers. A block counts only if it
     * shares the answer's distinctive content: words of five or more characters and numbers,
     * each weighted by how few blocks contain it (a word present in every block proves nothing).
     * A block is attributed when it shares at least three such items and scores at least half
     * of the best block. Otherwise nothing is attributed rather than listing retrieved sources.
     *
     * TEMPORARY conservative display rule, not final citation logic: it cannot credit a valid
     * Hindi/Hinglish paraphrase of English evidence (it shows no source instead). To be replaced
     * by claim/support-record attribution.
     */
    fun supportedSourceKeys(answer: String): Set<String> {
        fun items(text: String): Set<String> = distinctiveItems(text)
        val answerItems = items(Regex("\\[S\\d+\\]", RegexOption.IGNORE_CASE).replace(answer, " ")) - items(query)
        if (answerItems.size < 3 || evidenceBlocks.isEmpty()) return emptySet()
        val blockItems = evidenceBlocks.map { it.sourceId.uppercase() to items(it.text) }
        val blocksWith = HashMap<String, Int>()
        blockItems.forEach { (_, set) -> set.forEach { if (it in answerItems) blocksWith[it] = (blocksWith[it] ?: 0) + 1 } }
        val scored = blockItems.map { (id, set) ->
            val shared = set.filter { it in answerItems }
            Triple(id, shared.size, shared.sumOf { 1.0 / (blocksWith[it] ?: 1) })
        }
        val best = scored.maxOfOrNull { it.third } ?: return emptySet()
        if (best <= 0.0) return emptySet()
        return scored.filter { (_, count, score) -> count >= MIN_SHARED_ITEMS && score >= best * 0.5 }.map { it.first }.toSet()
    }
}

data class PocketRagTurnMemory(
    val user: String,
    val assistant: String,
    val sourceIds: List<String>
)

data class PocketRagQualityDecision(
    val acceptLocal: Boolean,
    val reason: String
)

data class PocketRagResult(
    val text: String,
    val provider: AndroBaselineRuntime.Provider,
    val sourceIds: List<String>,
    val localAttempted: Boolean,
    val cloudAttempted: Boolean,
    val escalationReason: String = "",
    /** False for the app's fallback message: nothing was answered, so it is not saved or remembered. */
    val accepted: Boolean = true
)
