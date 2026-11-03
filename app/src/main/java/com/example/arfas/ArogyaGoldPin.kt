package com.example.arfas

/**
 * Part C2: pure gold-pin helpers (host-testable). Verified-answer passages are
 * prepended to the evidence in the same "Source ID" block format, trailing normal
 * blocks are dropped so the total stays within today's size, and a strong match
 * adds reference notes to the prompt. The AI still generates every answer live.
 */
object ArogyaGoldPin {

    /** One verified-answer passage resolved from the guide database. */
    data class GoldPassage(
        val chunkId: String,
        val docId: String,
        val title: String,
        val locator: String,
        val text: String,
        /** UI5: official document link (opens the PDF at the page); "" when unknown. */
        val url: String = ""
    )

    fun referenceBlock(answerEn: String): String =
        "Reference notes prepared from the official guides for a similar question: $answerEn. " +
            "Use them to check that your answer is complete and correct. Answer the user's actual question. " +
            "Every fact must come from the guide passages above and carry their citation. Do not mention these notes."

    fun blockText(id: String, docId: String, body: String): String =
        "Source ID: $id\nSource: $id :: $docId\n\n$body"

    fun sourceRef(id: String, title: String, locator: String, url: String = ""): String =
        "$id :: ${title.replace('\n', ' ').take(500)}" + (if (locator.isNotBlank()) " — $locator" else "") +
            // UI5: same " | url" tail as normal passages, so the answer keeps "Open official document".
            (if (url.startsWith("http")) " | $url" else "")

    /**
     * Trim trailing normal blocks so gold + normal fit [budget] (today's normal size);
     * gold is always kept fully. At least one normal block survives for grounding
     * diversity, unless there are none.
     */
    fun trimNormal(goldChars: Int, normal: List<String>, budget: Int): List<String> {
        val out = ArrayList<String>()
        var used = goldChars
        for (b in normal) {
            if (used > 0 && used + b.length + 2 > budget && out.isNotEmpty()) break
            out += b
            used += b.length + 2
        }
        return out
    }

    /** Rough prompt tokens from characters (matches the ~4 chars/token estimate in use). */
    fun estimateTokens(chars: Int): Int = (chars + 3) / 4

    /**
     * Part C3: stored-copy fallback, strong matches only. The label is part of the
     * text so it can never show silently as an AI answer. Returns text + sources.
     */
    fun storedCopyIfStrong(scored: ArogyaVerifiedAnswers.Scored, hindi: Boolean): Pair<String, List<ArogyaSource>>? {
        if (!scored.strong) return null
        val item = scored.item
        val label = if (hindi) "आधिकारिक दिशानिर्देशों से (संग्रहीत प्रति) — AI उपलब्ध नहीं।"
        else "From official guidelines (stored copy) — AI unavailable."
        val answer = (if (hindi) item.aHi.ifBlank { item.aEn } else item.aEn).trim()
        if (answer.isBlank()) return null
        val sources = item.sources.mapIndexed { i, s ->
            ArogyaSource(
                id = "${i + 1}", title = s.title, locator = "page ${s.page}",
                excerpt = s.quote, url = s.sourceUrl,
                highlights = listOf(s.quote).filter { it.isNotBlank() }, kind = "guide"
            )
        }
        return (label + "\n\n" + answer) to sources
    }
}
