package com.example.arfas

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 1.4 per-pack corpus profile, computed once from the installed pack itself.
 *
 * Everything here is relative to the pack: no fixed similarity constant, no vocabulary list.
 *  - mean embedding: used to mean-center query and chunk vectors (reduces E5 score compression
 *    and "hub" chunks that sit close to every query);
 *  - document frequency: corpus vocabulary for meaningful-word decisions and spelling variants;
 *  - quality penalty per chunk: repeated boilerplate across documents, very short fragments,
 *    mis-encoded text, and minority extraction method. Penalties only RE-RANK; they never
 *    remove a chunk, so sole evidence (including OCR-only evidence) stays selectable;
 *  - structure index: which labels enumerate sections (identifier lane).
 *
 * The weights below are ranking hyperparameters expressed in z-units of the query's own
 * score distribution; they are not tied to any corpus or domain.
 */
class GenericKbCorpusProfile private constructor(
    val signature: String,
    val chunkCount: Int,
    val mean: FloatArray,
    val meanNormSq: Float,
    private val documentFrequency: Map<String, Int>,
    private val vocabularyByLength: Map<Int, List<String>>,
    private val penalty: Map<String, Float>,
    val structure: GenericKbIdentifierLane.StructureIndex,
    val shortFragmentChars: Int,
    /** Median passage length of this pack (characters): the size a passage normally has. */
    val medianChars: Int = 0,
    /**
     * Documents whose passages are mostly fragments (median passage shorter than half the pack
     * median): slide decks and screenshot manuals, where one step is one caption and a procedure
     * runs across consecutive pages. Learned from the pack, not from names.
     */
    val fragmentaryDocuments: Set<String> = emptySet(),
    /** Abbreviations of this pack and their long forms (learned at install; see GenericKbAliasIndex). */
    val aliases: GenericKbAliasIndex = GenericKbAliasIndex.EMPTY
) {
    /** Long forms of the abbreviations used in [question] ("epbg" -> "performance security"). */
    fun expansions(question: String): List<String> = aliases.expansions(question, { documentFrequency(it) }, passages = chunkCount)
    fun isAboutTerm(question: String): Boolean = aliases.isAboutTerm(question)
    fun terms(question: String): List<Pair<String, String>> = aliases.terms(question) { documentFrequency(it) }

    companion object {
        const val REPEAT_WEIGHT = 0.9f
        const val SHORT_WEIGHT = 0.7f
        const val GARBLE_WEIGHT = 1.5f
        const val MINORITY_EXTRACTION_WEIGHT = 0.15f
        const val MAX_PENALTY = 2.0f
        /** Extra penalty for a passage also in a newer edition (0.5 x this in re-ranker logits). */
        const val OLDER_EDITION_WEIGHT = 4.0f
        private const val EDITION_SHARE = 0.3
        private const val SUPERSEDED_SHARE = 0.5
        private const val MIN_EDITION_SHARED = 10
        private const val MAX_EDITIONS = 8
        /** Diagnostic: how many passages were marked as older-edition copies at the last build. */
        @Volatile var editionNote: String = ""

        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        /**
         * Latest date written in a document name, as yyyymmdd ("…-upto-18-Apr-2023" → 20230418,
         * "…dated-11.11.2021" → 20211111, "…Second-Edition-2024" → 20240000); 0 when none.
         */
        fun latestDate(name: String): Long {
            val n = name.lowercase(java.util.Locale.ROOT)
            val found = ArrayList<Long>()
            Regex("(\\d{1,2})[-_. ]+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-_. ,]+((?:19|20)\\d{2})").findAll(n).forEach {
                found += it.groupValues[3].toLong() * 10_000 + (MONTHS.indexOf(it.groupValues[2]) + 1) * 100 + it.groupValues[1].toLong()
            }
            Regex("(\\d{1,2})[-.](\\d{1,2})[-.]((?:19|20)\\d{2})").findAll(n).forEach {
                val m = it.groupValues[2].toLong()
                if (m in 1..12) found += it.groupValues[3].toLong() * 10_000 + m * 100 + it.groupValues[1].toLong()
            }
            Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-_. ,]+((?:19|20)\\d{2})").findAll(n).forEach {
                found += it.groupValues[2].toLong() * 10_000 + (MONTHS.indexOf(it.groupValues[1]) + 1) * 100
            }
            Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)").findAll(n).forEach { found += it.groupValues[1].toLong() * 10_000 }
            return found.maxOrNull() ?: 0L
        }
        /** A word present in at least this share of chunks behaves like a function word. */
        const val FUNCTION_WORD_SHARE = 0.10f
        private const val FORMAT = 4

        private fun vocabularyOf(df: Map<String, Int>): Map<Int, List<String>> =
            df.keys.filter { it.length >= 3 && it.all(Char::isLetter) }.groupBy { it.length }

        /**
         * Loads a profile saved by [writeTo] when it was built for [expectedSignature] (same pack
         * file, size, date and table); otherwise null, and the caller rebuilds from the pack.
         */
        fun readFrom(file: java.io.File, expectedSignature: String): GenericKbCorpusProfile? = runCatching {
            if (!file.exists()) return@runCatching null
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                if (input.readInt() != FORMAT || input.readUTF() != expectedSignature) return@runCatching null
                val chunkCount = input.readInt()
                val mean = FloatArray(input.readInt()) { input.readFloat() }
                val meanNormSq = input.readFloat()
                val dfSize = input.readInt()
                val df = HashMap<String, Int>(dfSize * 4 / 3 + 16)
                repeat(dfSize) { df[input.readUTF()] = input.readInt() }
                val penaltySize = input.readInt()
                val penalty = HashMap<String, Float>(penaltySize * 4 / 3 + 16)
                repeat(penaltySize) { penalty[input.readUTF()] = input.readFloat() }
                val structure = GenericKbIdentifierLane.StructureIndex.readFrom(input)
                val shortFragmentChars = input.readInt()
                val medianChars = input.readInt()
                val fragmentary = HashSet<String>()
                repeat(input.readInt()) { fragmentary += input.readUTF() }
                val aliases = GenericKbAliasIndex.readFrom(input)
                GenericKbCorpusProfile(
                    expectedSignature, chunkCount, mean, meanNormSq, df, vocabularyOf(df), penalty,
                    structure, shortFragmentChars, medianChars, fragmentary, aliases
                )
            }
        }.getOrNull()
    }

    /** Saves this profile so the next app start loads it instead of re-reading the whole pack. */
    fun writeTo(file: java.io.File): Boolean = runCatching {
        val tmp = java.io.File(file.path + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(signature)
            out.writeInt(chunkCount)
            out.writeInt(mean.size)
            mean.forEach { out.writeFloat(it) }
            out.writeFloat(meanNormSq)
            out.writeInt(documentFrequency.size)
            documentFrequency.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
            out.writeInt(penalty.size)
            penalty.forEach { (k, v) -> out.writeUTF(k); out.writeFloat(v) }
            structure.writeTo(out)
            out.writeInt(shortFragmentChars)
            out.writeInt(medianChars)
            out.writeInt(fragmentaryDocuments.size)
            fragmentaryDocuments.forEach { out.writeUTF(it) }
            aliases.writeTo(out)
        }
        tmp.renameTo(file)
    }.getOrDefault(false)

    fun penaltyFor(chunkId: String): Float = penalty[chunkId] ?: 0f
    fun documentFrequency(token: String): Int = documentFrequency[token] ?: 0

    /** Meaningful for anchors/residuals: not numeric, and either unseen or not function-word frequent. */
    fun isMeaningful(token: String): Boolean {
        if (token.length < 2 || token.all(Char::isDigit)) return false
        val df = documentFrequency(token)
        return df == 0 || df < chunkCount * FUNCTION_WORD_SHARE
    }

    /** Question words of 3+ letters the pack never uses (other language, misspelling, slang). */
    fun unknownWords(question: String): List<String> =
        GenericKbText.tokens(question).filter { it.length >= 3 && it.all(Char::isLetter) && documentFrequency(it) == 0 }

    fun idf(token: String): Double = ln((chunkCount + 1.0) / (documentFrequency(token) + 1.0))

    /**
     * Deterministic sample of mid-frequency pack words (for calibration pseudo-questions):
     * words present in at least three chunks and below the function-word share.
     */
    fun sampleWords(count: Int, seed: Long): List<String> {
        val pool = documentFrequency.entries
            .filter { (w, df) -> w.length >= 4 && w.all(Char::isLetter) && df >= 3 && df < chunkCount * FUNCTION_WORD_SHARE }
            .map { it.key }
            .sorted()
        if (pool.isEmpty()) return emptyList()
        val random = java.util.Random(seed)
        return List(count) { pool[random.nextInt(pool.size)] }
    }

    /**
     * Closest pack word for an unknown token of four or more letters: edit distance 1 for
     * words shorter than eight letters, 2 otherwise; ties go to the more frequent word.
     */
    fun correct(token: String): String? {
        if (token.length < 4 || !token.all { it.isLetter() } || documentFrequency(token) > 0) return null
        val maxEdits = if (token.length < 8) 1 else 2
        var best: String? = null
        var bestDf = 0
        for (len in (token.length - maxEdits)..(token.length + maxEdits)) {
            for (word in vocabularyByLength[len].orEmpty()) {
                if (word[0] != token[0] && maxEdits == 1) continue
                val df = documentFrequency(word)
                if (df < 2 || df <= bestDf) continue
                if (editDistanceWithin(token, word, maxEdits)) { best = word; bestDf = df }
            }
        }
        return best
    }

    /**
     * Optimal-string-alignment distance <= [limit]: insert, delete, substitute, and swap of two
     * neighbouring letters count as one edit each, so "asah" -> "asha" is one edit (a typo on a
     * phone keyboard), not two.
     */
    internal fun editDistanceWithin(a: String, b: String, limit: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + 1)
                cur[j] = v
                rowMin = min(rowMin, v)
            }
            if (rowMin > limit) return false
            prev2 = prev
            prev = cur
        }
        return prev[b.length] <= limit
    }

    class Builder(private val signature: String) {
        private var rows = 0
        private var sum: DoubleArray? = null
        private var vectors = 0
        private val df = HashMap<String, Int>(64_000)
        private val textKeyDocs = HashMap<String, MutableSet<String>>()
        private val chunkTextKey = HashMap<String, String>()
        private val chunkChars = HashMap<String, Int>()
        private val docChars = HashMap<String, MutableList<Int>>()
        private val chunkOrigin = HashMap<String, String>()
        private val originCounts = HashMap<String, Int>()
        private val blockLetters = HashMap<Character.UnicodeBlock, Long>()
        private val chunkBlocks = HashMap<String, Map<Character.UnicodeBlock, Int>>()
        private val structure = GenericKbIdentifierLane.StructureIndex.Builder()
        private val aliasBuilder = GenericKbAliasIndex.Builder()

        fun addRow(
            chunkId: String,
            documentKey: String,
            heading: String,
            locator: String,
            content: String,
            extractionOrigin: String,
            vector: FloatArray?,
            /** The document's file name or title; its dates tell editions apart ("…-upto-18-Apr-2023"). */
            documentName: String = ""
        ) {
            if (chunkId.isBlank()) return
            rows++
            chunkDoc[chunkId] = documentKey
            if (documentName.isNotBlank()) docName.putIfAbsent(documentKey, documentName)
            if (vector != null) {
                val s = sum ?: DoubleArray(vector.size).also { sum = it }
                if (s.size == vector.size) { for (i in vector.indices) s[i] += vector[i]; vectors++ }
            }
            GenericKbText.tokens(heading + " " + content).toHashSet().forEach { df[it] = (df[it] ?: 0) + 1 }
            val key = content.lowercase(Locale.ROOT).filter { it.isLetter() }.take(300)
            if (key.isNotEmpty()) {
                textKeyDocs.getOrPut(key) { HashSet() }.add(documentKey)
                chunkTextKey[chunkId] = key
            }
            chunkChars[chunkId] = content.trim().length
            if (documentKey.isNotBlank()) docChars.getOrPut(documentKey) { ArrayList() }.add(content.trim().length)
            val origin = extractionOrigin.trim().lowercase(Locale.ROOT)
            chunkOrigin[chunkId] = origin
            originCounts[origin] = (originCounts[origin] ?: 0) + 1
            val blocks = HashMap<Character.UnicodeBlock, Int>()
            var i = 0
            while (i < content.length) {
                val cp = content.codePointAt(i)
                if (Character.isLetter(cp)) {
                    Character.UnicodeBlock.of(cp)?.let { b -> blocks[b] = (blocks[b] ?: 0) + 1 }
                }
                i += Character.charCount(cp)
            }
            blocks.forEach { (b, n) -> blockLetters[b] = (blockLetters[b] ?: 0L) + n }
            chunkBlocks[chunkId] = blocks
            structure.add(heading, locator, content, documentKey)
            aliasBuilder.add(heading + " " + content)
        }

        private val chunkDoc = HashMap<String, String>()
        private val docName = HashMap<String, String>()

        /**
         * Chunks whose text is also in a newer edition of the same document. Two documents are
         * editions of one another when at least [EDITION_SHARE] of the smaller one's passages are in
         * both (four DAP 2020 versions share 84–98%); "newer" is the latest date in the document's
         * name. Learned from the pack: no document names or dates are known in advance.
         */
        private fun olderEditionChunks(): Set<String> {
            val keysPerDoc = HashMap<String, Int>()
            val shared = HashMap<Pair<String, String>, Int>()
            for (docs in textKeyDocs.values) {
                docs.forEach { keysPerDoc[it] = (keysPerDoc[it] ?: 0) + 1 }
                if (docs.size < 2 || docs.size > MAX_EDITIONS) continue
                val sorted = docs.sorted()
                for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                    val k = sorted[i] to sorted[j]
                    shared[k] = (shared[k] ?: 0) + 1
                }
            }
            val family = shared.filter { (pair, n) ->
                n >= MIN_EDITION_SHARED && n >= EDITION_SHARE * min(keysPerDoc[pair.first] ?: 0, keysPerDoc[pair.second] ?: 0)
            }.keys
            if (family.isEmpty()) return emptySet()
            val date = HashMap<String, Long>()
            fun dateOf(doc: String) = date.getOrPut(doc) { latestDate(docName[doc] ?: doc) }
            val older = HashSet<String>()
            // Whole document superseded: most of it (SUPERSEDED_SHARE of its own passages) is in a newer
            // edition. Its other passages are the same text cut at different places, so they rank lower too.
            val superseded = HashSet<String>()
            for (pair in family) {
                val (a, b) = pair
                val n = shared[pair] ?: 0
                val (old, new) = if (dateOf(a) < dateOf(b)) a to b else if (dateOf(b) < dateOf(a)) b to a else continue
                if (dateOf(old) > 0L && n >= SUPERSEDED_SHARE * (keysPerDoc[old] ?: Int.MAX_VALUE)) superseded += old
            }
            if (superseded.isNotEmpty()) chunkDoc.forEach { (chunkId, doc) -> if (doc in superseded) older += chunkId }
            for ((chunkId, key) in chunkTextKey) {
                val doc = chunkDoc[chunkId] ?: continue
                val docs = textKeyDocs[key] ?: continue
                if (docs.size < 2) continue
                val mine = dateOf(doc)
                if (mine <= 0L) continue
                if (docs.any { other -> other != doc && (minOf(doc, other) to maxOf(doc, other)) in family && dateOf(other) > mine }) older += chunkId
            }
            return older
        }

        fun build(): GenericKbCorpusProfile {
            val dim = sum?.size ?: 0
            val mean = FloatArray(dim) { if (vectors > 0) (sum!![it] / vectors).toFloat() else 0f }
            val meanNormSq = mean.fold(0f) { acc, v -> acc + v * v }

            // Letters in blocks that together make up the rarest 1% of the pack are "unusual";
            // mis-encoded PDF text lands there, whatever the pack's normal script is.
            val totalLetters = blockLetters.values.sum().coerceAtLeast(1L)
            var cumulative = 0L
            val common = HashSet<Character.UnicodeBlock>()
            for ((block, n) in blockLetters.entries.sortedByDescending { it.value }) {
                if (cumulative >= totalLetters * 0.99) break
                common += block
                cumulative += n
            }
            val sortedChars = chunkChars.values.sorted()
            // Fragments are the pack's own shortest decile (captions, stray labels), not short answers.
            val p25 = sortedChars.getOrElse(sortedChars.size / 10) { 0 }.coerceAtLeast(1)
            // Repetition saturates at sqrt(#documents): text shared by that many documents is
            // boilerplate in this pack (footers, captions), whatever the pack size.
            val documentCount = textKeyDocs.values.flatMapTo(HashSet()) { it }.size
            val repeatScale = ln(sqrt(documentCount.toDouble()).coerceAtLeast(3.0))
            val majorityOrigin = originCounts.maxByOrNull { it.value }?.key.orEmpty()

            val penalty = HashMap<String, Float>(chunkChars.size)
            for ((chunkId, chars) in chunkChars) {
                var p = 0f
                val docs = chunkTextKey[chunkId]?.let { textKeyDocs[it]?.size } ?: 1
                if (docs >= 2) p += REPEAT_WEIGHT * min(1.0, ln(docs.toDouble()) / repeatScale).toFloat()
                if (chars < p25) p += SHORT_WEIGHT * (1f - chars.toFloat() / p25)
                val blocks = chunkBlocks[chunkId].orEmpty()
                val letters = blocks.values.sum()
                if (letters > 0) {
                    val unusual = blocks.filterKeys { it !in common }.values.sum()
                    p += GARBLE_WEIGHT * (unusual.toFloat() / letters)
                }
                if (chunkOrigin[chunkId].orEmpty() != majorityOrigin) p += MINORITY_EXTRACTION_WEIGHT
                if (p > 0f) penalty[chunkId] = min(p, MAX_PENALTY)
            }
            // An older edition's copy of a passage ranks below the newest edition's copy.
            val older = olderEditionChunks()
            older.forEach { penalty[it] = (penalty[it] ?: 0f) + OLDER_EDITION_WEIGHT }
            if (older.isNotEmpty()) editionNote = "older_edition_chunks=${older.size}"
            val median = sortedChars.getOrElse(sortedChars.size / 2) { 0 }
            val byLength = vocabularyOf(df)
            return GenericKbCorpusProfile(
                signature, rows, mean, meanNormSq, df, byLength, penalty, structure.build(), p25,
                median,
                docChars.filterValues { v -> v.sorted()[v.size / 2] < median / 2 }.keys,
                aliasBuilder.build()
            )
        }
    }
}

/**
 * Mean-centered cosine for several query vectors at once:
 *   cos(q - mu, d - mu) = (qc . d - qc . mu) / |d - mu|,   qc = (q - mu) / |q - mu|
 */
class GenericKbCenteredScorer(private val mean: FloatArray, private val meanNormSq: Float, queries: List<FloatArray>) {
    private val centered: List<FloatArray> = queries.map { q ->
        val c = FloatArray(q.size) { q[it] - mean.getOrElse(it) { 0f } }
        val n = sqrt(c.fold(0f) { acc, v -> acc + v * v }).coerceAtLeast(1e-6f)
        FloatArray(c.size) { c[it] / n }
    }
    private val offsets = FloatArray(centered.size) { i -> dot(centered[i], mean) }
    val size: Int get() = centered.size

    fun score(d: FloatArray, out: FloatArray) {
        val dd = dot(d, d)
        val dm = dot(d, mean)
        val norm = sqrt(max(dd - 2f * dm + meanNormSq, 1e-6f))
        for (i in centered.indices) out[i] = (dot(centered[i], d) - offsets[i]) / norm
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}

/** Streaming mean / standard deviation (Welford). */
class GenericKbRunningStats {
    var count = 0L; private set
    private var mean = 0.0
    private var m2 = 0.0
    fun add(x: Float) { count++; val d = x - mean; mean += d / count; m2 += d * (x - mean) }
    fun mean(): Float = mean.toFloat()
    fun sd(): Float = if (count < 2) 1f else sqrt(m2 / (count - 1)).toFloat().coerceAtLeast(1e-4f)
    fun z(x: Float): Float = (x - mean()) / sd()
}

/**
 * Per-pack evidence gate in z-units, calibrated from the pack's own score distribution:
 * the 90th percentile of the best z reached by incoherent pseudo-questions made of random
 * pack words (what noise reaches in this pack), capped at the 25th percentile reached by
 * the pack's own headings against their sections so real questions are not starved.
 */
data class GenericKbScoreCalibration(
    val gateZ: Float,
    val noiseP90: Float,
    val positiveP10: Float,
    val positiveMedian: Float,
    val samples: Int
) {
    val separates: Boolean get() = positiveMedian > gateZ

    fun encode(): String = listOf(gateZ, noiseP90, positiveP10, positiveMedian, samples).joinToString(",")

    companion object {
        fun from(positiveZ: List<Float>, nullTopZ: List<Float>): GenericKbScoreCalibration? {
            if (nullTopZ.size < 8) return null
            val nulls = nullTopZ.sorted()
            val pos = positiveZ.sorted()
            fun q(xs: List<Float>, p: Double) = if (xs.isEmpty()) 0f else xs[((xs.size - 1) * p).toInt()]
            val noise = q(nulls, 0.90)
            // Recall guard: never stricter than the level reached by 75% of the pack's own
            // heading-to-section matches.
            val gate = if (pos.isEmpty()) noise else minOf(noise, q(pos, 0.25))
            return GenericKbScoreCalibration(gate, noise, q(pos, 0.10), q(pos, 0.50), nulls.size + pos.size)
        }

        fun decode(raw: String): GenericKbScoreCalibration? {
            val p = raw.split(',')
            if (p.size != 5) return null
            return runCatching {
                GenericKbScoreCalibration(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toInt())
            }.getOrNull()
        }
    }
}

/**
 * Shared calibration procedure (used by the app and by the desktop evaluation tool so both
 * compute the identical gate): pseudo-question texts, then z of the best chunk per text.
 */
object GenericKbCalibrationPlan {
    const val NOISE_QUESTIONS = 16
    const val WORDS_PER_NOISE_QUESTION = 5
    const val HEADING_QUESTIONS = 16

    data class Plan(val noise: List<String>, val headings: List<String>, val targets: Map<String, Set<String>>) {
        val texts: List<String> get() = noise + headings
    }

    /** [headingRows] = (heading, chunkId) pairs from the pack, in pack order. */
    fun plan(profile: GenericKbCorpusProfile, headingRows: Sequence<Pair<String, String>>): Plan? {
        val words = profile.sampleWords(NOISE_QUESTIONS * WORDS_PER_NOISE_QUESTION, profile.signature.hashCode().toLong())
        if (words.size < NOISE_QUESTIONS * WORDS_PER_NOISE_QUESTION) return null
        val noise = words.chunked(WORDS_PER_NOISE_QUESTION).map { it.joinToString(" ") }
        val targets = LinkedHashMap<String, MutableSet<String>>()
        for ((rawHeading, chunkId) in headingRows) {
            val heading = rawHeading.replace('|', ' ').replace(Regex("\\s+"), " ").trim()
            if (heading.length !in 12..160 || profile.penaltyFor(chunkId) > 0f) continue
            if (GenericKbText.tokens(heading).count { profile.isMeaningful(it) } < 2) continue
            targets.getOrPut(heading) { HashSet() }.add(chunkId)
        }
        val all = targets.keys.toList()
        val headings = if (all.size <= HEADING_QUESTIONS) all else List(HEADING_QUESTIONS) { i -> all[i * all.size / HEADING_QUESTIONS] }
        return Plan(noise, headings, headings.associateWith { targets[it].orEmpty() })
    }

    /** [vectors] are the embeddings of [Plan.texts] ("query: " + text), in order. */
    fun calibrate(
        profile: GenericKbCorpusProfile,
        plan: Plan,
        vectors: List<FloatArray>,
        rows: Sequence<Pair<String, FloatArray>>
    ): GenericKbScoreCalibration? {
        val (positiveZ, noiseZ) = zScores(profile, plan, vectors, rows) ?: return null
        return GenericKbScoreCalibration.from(positiveZ, noiseZ)
    }

    /** Best-chunk z per calibration question: (heading positives, noise). */
    fun zScores(
        profile: GenericKbCorpusProfile,
        plan: Plan,
        vectors: List<FloatArray>,
        rows: Sequence<Pair<String, FloatArray>>
    ): Pair<List<Float>, List<Float>>? {
        if (vectors.size != plan.texts.size) return null
        val scorer = GenericKbCenteredScorer(profile.mean, profile.meanNormSq, vectors)
        val stats = Array(vectors.size) { GenericKbRunningStats() }
        val best = FloatArray(vectors.size) { Float.NEGATIVE_INFINITY }
        val out = FloatArray(vectors.size)
        val noiseCount = plan.noise.size
        for ((chunkId, v) in rows) {
            scorer.score(v, out)
            for (i in vectors.indices) {
                stats[i].add(out[i])
                val isTarget = i < noiseCount || chunkId in plan.targets[plan.headings[i - noiseCount]].orEmpty()
                if (isTarget && out[i] > best[i]) best[i] = out[i]
            }
        }
        val noiseZ = (0 until noiseCount).map { stats[it].z(best[it]) }
        val positiveZ = plan.headings.indices.map { noiseCount + it }.filter { best[it].isFinite() }.map { stats[it].z(best[it]) }
        return positiveZ to noiseZ
    }
}
