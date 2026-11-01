package com.example.arfas

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * CLIP's byte-level BPE tokenizer (port of OpenAI CLIP simple_tokenizer.py, MIT licence), reading
 * the original merges file (assets/clip/bpe_simple_vocab_16e6.txt.gz). Produces the same ids as
 * the reference for lower-cased text: <start_of_text>=49406 ... <end_of_text>=49407.
 */
class ClipTokenizer(bpeGz: InputStream) {
    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT = 49406
        const val EOT = 49407

        private val PATTERN = Regex(
            """<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )

        /** OpenAI's reversible byte -> printable-unicode table. */
        val BYTE_ENCODER: Array<String> = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val table = arrayOfNulls<String>(256)
            for (i in bs.indices) table[bs[i]] = String(Character.toChars(cs[i]))
            Array(256) { table[it]!! }
        }

        private val BYTE_ORDER: IntArray = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            for (b in 0 until 256) if (b !in bs) bs += b
            bs.toIntArray()
        }
    }

    private val encoder = HashMap<String, Int>(50_000)
    private val ranks = HashMap<String, Int>(50_000)
    private val cache = HashMap<String, List<String>>()

    init {
        val lines = GZIPInputStream(bpeGz).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val merges = lines.subList(1, 49152 - 256 - 2 + 1).map { it.split(' ') }
        val vocab = ArrayList<String>(49408)
        val base = (0 until 256).map { BYTE_ENCODER[orderIndex(it)] }
        vocab += base
        vocab += base.map { "$it</w>" }
        merges.forEach { vocab += it[0] + it[1] }
        vocab += "<start_of_text>"
        vocab += "<end_of_text>"
        vocab.forEachIndexed { i, t -> encoder[t] = i }
        merges.forEachIndexed { i, m -> ranks[m[0] + " " + m[1]] = i }
    }

    /** list(bytes_to_unicode().values()) order: the printable bytes first, then the rest. */
    private fun orderIndex(i: Int): Int = BYTE_ORDER[i]

    val vocabSize: Int get() = encoder.size

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // Split into code points (byte-encoded chars may be outside the BMP? no: all < 512).
        var word: MutableList<String> = token.map { it.toString() }.toMutableList()
        word[word.size - 1] = word.last() + "</w>"
        if (word.size == 1) return word.also { cache[token] = it }
        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] + " " + word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = word[i] to word[i + 1] }
            }
            val (first, second) = best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += word[i]
                    i += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        cache[token] = word
        return word
    }

    /** Token ids without start/end markers (lower-cased, whitespace collapsed). */
    fun encode(text: String): List<Int> {
        val clean = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
        val out = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val bytes = m.value.toByteArray(Charsets.UTF_8)
            val mapped = buildString { bytes.forEach { append(BYTE_ENCODER[it.toInt() and 0xFF]) } }
            bpe(mapped).forEach { piece -> encoder[piece]?.let(out::add) }
        }
        return out
    }

    /** [SOT] + tokens + [EOT], truncated to [CONTEXT_LENGTH] (EOT kept), unpadded. */
    fun encodeForModel(text: String): LongArray {
        val ids = encode(text)
        val body = if (ids.size > CONTEXT_LENGTH - 2) ids.subList(0, CONTEXT_LENGTH - 2) else ids
        return (listOf(SOT) + body + listOf(EOT)).map { it.toLong() }.toLongArray()
    }
}
