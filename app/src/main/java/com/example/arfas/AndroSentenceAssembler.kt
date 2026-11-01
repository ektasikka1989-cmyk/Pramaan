package com.example.arfas

/**
 * Direct package-adapted port of AndroLLM's SentenceAssembler.
 * Turns streamed model deltas into natural speech chunks so TTS can start
 * before the complete answer is finished.
 */
data class AndroSpokenChunk(
    val text: String,
    val startOffset: Int
)

class AndroSentenceAssembler(
    private val maxWords: Int = MAX_WORDS,
    private val minChunkChars: Int = MIN_CHUNK_CHARS
) {
    private val buffer = StringBuilder()
    private var charsFed = 0

    fun feed(delta: String): List<AndroSpokenChunk> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        charsFed += delta.length
        val text = buffer.toString()
        val chunks = mutableListOf<AndroSpokenChunk>()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (isSentenceEnd(c)) {
                // Do not speak list ordinals ("1.", "2.", …) as standalone TTS
                // utterances. Hold the marker until the following instruction closes,
                // so spoken output becomes "1. Restart the laptop." as one chunk.
                if (c == '.' && isBareNumberedListMarker(text, start, i)) continue
                val end = if (c == '\n') i else i + 1
                emit(text, start, end, chunks)
                start = i + 1
            } else if (isClauseBoundary(c) && (i - start) >= minChunkChars) {
                emit(text, start, i + 1, chunks)
                start = i + 1
            }
        }
        buffer.setLength(0)
        buffer.append(text.substring(start))

        // Upstream AndroLLM has a MAX_WORDS emergency flush. In FixSight the LiteRT
        // callback may deliver a delta that ends in the middle of a lexical word
        // (observed on-device as "spe" / "cifically"). Never flush at that arbitrary
        // callback edge. Emit only through a whitespace boundary after maxWords complete
        // words and retain the partial/new word for the next delta.
        while (true) {
            val whole = buffer.toString()
            val safeEnd = completeWordBoundary(whole, maxWords)
            if (safeEnd <= 0) break
            val raw = whole.substring(0, safeEnd)
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                chunks.add(AndroSpokenChunk(trimmed, charsFed - whole.length + leading))
            }
            buffer.setLength(0)
            buffer.append(whole.substring(safeEnd))
        }
        return chunks
    }

    fun flush(force: Boolean = false): AndroSpokenChunk? {
        val raw = buffer.toString()
        buffer.setLength(0)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length < minChunkChars && !force) return null
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return AndroSpokenChunk(trimmed, charsFed - raw.length + leading)
    }

    private fun emit(text: String, start: Int, end: Int, out: MutableList<AndroSpokenChunk>) {
        val raw = text.substring(start, end)
        val trimmed = raw.trim()
        if (trimmed.length < minChunkChars) return
        val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        out.add(AndroSpokenChunk(trimmed, charsFed - text.length + start + leading))
    }

    private fun isBareNumberedListMarker(text: String, start: Int, periodIndex: Int): Boolean {
        val candidate = text.substring(start, periodIndex + 1).trim()
        if (!candidate.matches(Regex("""\d{1,3}\."""))) return false
        // A marker at the end of the currently buffered stream must be retained
        // until more text arrives. A marker already followed by whitespace is also
        // retained so the next sentence boundary emits marker + instruction.
        return periodIndex == text.lastIndex ||
            text.getOrNull(periodIndex + 1)?.isWhitespace() == true
    }


    /**
     * Returns the index immediately after at least [words] COMPLETE words, using
     * whitespace as proof that the final word is complete. Returns -1 when the
     * current model delta may still end inside that word.
     */
    private fun completeWordBoundary(text: String, words: Int): Int {
        if (words <= 0 || text.isBlank()) return -1
        var inWord = false
        var completed = 0
        for (i in text.indices) {
            if (text[i].isWhitespace()) {
                if (inWord) {
                    completed++
                    inWord = false
                    if (completed >= words) return i + 1
                }
            } else {
                inWord = true
            }
        }
        return -1
    }
    private fun countWords(s: CharSequence): Int {
        if (s.isBlank()) return 0
        return s.split(Regex("\\s+")).size
    }

    private fun isSentenceEnd(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n' || c == '。' || c == '！' || c == '？'

    private fun isClauseBoundary(c: Char): Boolean =
        c == ',' || c == ';' || c == ':' || c == '，' || c == '；'

    companion object {
        const val MAX_WORDS = 14
        const val MIN_CHUNK_CHARS = 2
    }
}
