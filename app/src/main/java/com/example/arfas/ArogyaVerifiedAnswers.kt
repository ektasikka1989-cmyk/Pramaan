package com.example.arfas

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject

/**
 * Part C1: the 100 verified Q&A steer live answers (never canned). The AI always
 * generates the answer live; matches only pin evidence (C2) and give a fallback (C3).
 * Pure Kotlin + org.json + java.io so the host tests cover parsing, scoring and the
 * embedding cache codec; Android entry points take bytes/files, never a Context.
 */
object ArogyaVerifiedAnswers {
    private const val TAG = "ArogyaVerifiedAnswers"
    const val DIM = 384
    /** Strong match: pin passages + reference notes. Tune from the gold100 benchmark. */
    const val STRONG = 0.80
    /** Medium match: pin passages only. Below this: no change. */
    const val MEDIUM = 0.65
    /** Final F1: pin only when the best item beats the runner-up by at least this. */
    const val MARGIN = 0.03
    /** Final F1: cosine rescale window. E5 puts unrelated text near 0.75-0.80. */
    const val COS_FLOOR = 0.75
    const val COS_SPAN = 0.20
    /** Final F1: a keyword-only match needs at least this many shared content words. */
    const val MIN_SHARED_KW = 2
    const val CACHE_NAME = "verified_q_emb.bin"
    const val QUERY_PREFIX = "query: "

    data class VerifiedSource(
        val chunkId: Int, val sourceName: String, val title: String,
        val authority: String, val page: Int, val sourceUrl: String, val quote: String
    )

    data class VerifiedItem(
        val id: String, val qEn: String, val qHi: String,
        val keywords: List<String>, val aEn: String, val sources: List<VerifiedSource>,
        /** Part C3: Hindi answer for the stored-copy fallback ("" when the asset lacks it). */
        val aHi: String = ""
    )

    data class Scored(
        val item: VerifiedItem, val score: Double, val strong: Boolean, val medium: Boolean,
        /** Final F1: runner-up score (-1 when only one item). */
        val second: Double = -1.0
    )

    /** Roman-Hindi words mapped to English before keyword overlap (spec C1). */
    val HINGLISH = mapOf(
        "naal" to "cord", "doodh" to "milk", "dudh" to "milk", "tika" to "vaccine",
        "bukhar" to "fever", "dast" to "diarrhoea", "garbhvati" to "pregnant",
        "saans" to "breathing", "peeliya" to "jaundice"
    )

    /**
     * Final F1: function words dropped before keyword overlap (EN, Hinglish, Hindi), so
     * "what is the ..." can no longer match an item on grammar alone.
     */
    val STOP_WORDS: Set<String> = setOf(
        // English
        "a", "an", "the", "is", "are", "am", "was", "were", "be", "been", "being", "do", "does", "did",
        "done", "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "should", "would",
        "could", "can", "will", "shall", "may", "might", "must", "i", "me", "my", "mine", "we", "us", "our",
        "you", "your", "he", "him", "his", "she", "her", "it", "its", "they", "them", "their", "this",
        "that", "these", "those", "of", "in", "on", "at", "to", "for", "from", "by", "with", "about", "as",
        "into", "and", "or", "but", "if", "not", "no", "so", "than", "then", "there", "here", "any", "some",
        "all", "much", "many", "very", "have", "has", "had", "get", "got", "make", "tell", "please", "know",
        "today", "now", "up", "out", "best", "good",
        // Hinglish (Roman Hindi)
        "hai", "hain", "kya", "ki", "ka", "ke", "ko", "mein", "me", "mai", "se", "par", "pe", "aur", "ya",
        "ek", "yeh", "ye", "woh", "wo", "toh", "bhi", "hi", "ne", "kar", "kare", "karein", "karen", "karna",
        "karni", "chahiye", "kaise", "kab", "kaun", "kahan", "kitna", "kitni", "kitne", "kyon", "kyu",
        "kyun", "jab", "tak", "liye", "ho", "tha", "thi", "nahi", "nahin", "main", "hum", "aap", "mujhe",
        "apne", "apna", "apni", "is", "us", "koi", "kuch", "saath", "raha", "rahi", "rahe", "sakta",
        "sakti", "sakte", "hota", "hoti", "hote", "wala", "wali", "de", "dena", "dein", "lena", "batao",
        "bataye", "aaj",
        // Hindi (Devanagari)
        "है", "हैं", "क्या", "की", "का", "के", "को", "में", "से", "पर", "और", "या", "एक", "यह", "वह",
        "ये", "वो", "तो", "भी", "ही", "ने", "कर", "करें", "करना", "करनी", "चाहिए", "कैसे", "कब", "कौन",
        "कहाँ", "कहां", "कितना", "कितनी", "कितने", "क्यों", "जब", "तक", "लिए", "हो", "था", "थी", "थे",
        "नहीं", "मैं", "हम", "आप", "मुझे", "अपने", "अपना", "अपनी", "इस", "उस", "कोई", "कुछ", "साथ",
        "रहा", "रही", "रहे", "सकता", "सकती", "सकते", "होता", "होती", "होते", "वाला", "वाली", "दें",
        "देना", "लेना", "बताएं", "बताइए", "आज"
    )

    /** Letters, digits and combining marks (Devanagari matras/virama stay inside the word). */
    fun tokens(text: String): Set<String> =
        text.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.isNotEmpty() }.toSet()

    fun applyHinglish(tokens: Set<String>): Set<String> = tokens.map { HINGLISH[it] ?: it }.toSet()

    /** Final F1: query tokens after the Hinglish map, minus stop-words and 1-letter tokens. */
    fun contentTokens(text: String): Set<String> =
        applyHinglish(tokens(text)).filter { it.length > 1 && it !in STOP_WORDS }.toSet()

    private fun pool(item: VerifiedItem): Set<String> =
        tokens(item.qEn) + tokens(item.qHi) + item.keywords.map { it.lowercase(java.util.Locale.ROOT) }

    /** Final F1: content words the query shares with the item's questions + keywords. */
    fun sharedContent(query: String, item: VerifiedItem): Int = contentTokens(query).intersect(pool(item)).size

    /** Shared content words / query content words (stop-words removed, Hinglish mapped). */
    fun keywordOverlap(query: String, item: VerifiedItem): Double {
        val q = contentTokens(query)
        if (q.isEmpty()) return 0.0
        return q.intersect(pool(item)).size.toDouble() / q.size
    }

    /** Final F1: cosAdj = clamp((cos - 0.75) / 0.20, 0, 1). */
    fun cosAdj(cos: Double): Double = ((cos - COS_FLOOR) / COS_SPAN).coerceIn(0.0, 1.0)

    /** Final F1: strong/medium only when top1 clears its threshold AND beats top2 by MARGIN. */
    fun classify(top1: Double, top2: Double): Pair<Boolean, Boolean> {
        val clear = top1 - top2 >= MARGIN - 1e-9
        return (clear && top1 >= STRONG) to (clear && top1 >= MEDIUM && top1 < STRONG)
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != DIM || b.size != DIM) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until DIM) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / Math.sqrt(na * nb)
    }

    /**
     * 0.7 x cosAdj (best of EN/HI, rescaled) + 0.3 x keyword overlap. Keyword-only until
     * vectors exist, and then only with at least [MIN_SHARED_KW] shared content words.
     */
    fun score(query: String, item: VerifiedItem, queryVec: FloatArray?, vecEn: FloatArray?, vecHi: FloatArray?): Double {
        val kw = keywordOverlap(query, item)
        if (queryVec == null || (vecEn == null && vecHi == null))
            return if (sharedContent(query, item) >= MIN_SHARED_KW) kw else 0.0
        val cos = maxOf(
            if (vecEn != null) cosine(queryVec, vecEn) else 0.0,
            if (vecHi != null) cosine(queryVec, vecHi) else 0.0
        )
        return 0.7 * cosAdj(cos) + 0.3 * kw
    }

    fun parseJson(text: String): List<VerifiedItem> {
        val out = ArrayList<VerifiedItem>()
        val items = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull() ?: return out
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val q = o.optJSONObject("question")
            val a = o.optJSONObject("answer")
            val kws = ArrayList<String>()
            val ka = o.optJSONArray("keywords")
            if (ka != null) for (k in 0 until ka.length()) kws += ka.optString(k)
            val srcs = ArrayList<VerifiedSource>()
            val sa = o.optJSONArray("sources")
            if (sa != null) for (s in 0 until sa.length()) {
                val so = sa.optJSONObject(s) ?: continue
                srcs += VerifiedSource(
                    so.optInt("chunk_id"), so.optString("source_name"), so.optString("title"),
                    so.optString("authority"), so.optInt("page"), so.optString("source_url"), so.optString("quote")
                )
            }
            out += VerifiedItem(
                o.optString("id"), q?.optString("en").orEmpty(), q?.optString("hi").orEmpty(),
                kws, a?.optString("en").orEmpty(), srcs, a?.optString("hi").orEmpty()
            )
        }
        return out
    }

    // ── Embedding cache codec (filesDir/verified_q_emb.bin) ──

    private const val MAGIC = "VAEMB1"

    fun assetFingerprint(assetBytes: ByteArray): Pair<Long, Int> {
        var h = 1125899906842597L
        for (b in assetBytes) {
            h = (h xor (b.toLong() and 0xFF)) * 1099511628211L
        }
        return assetBytes.size.toLong() to (h and 0xFFFFFFFFL).toInt()
    }

    /** Encodes item ids + EN/HI vectors, guarded by the asset fingerprint. */
    fun encodeCache(assetBytes: ByteArray, vecs: Map<String, Pair<FloatArray, FloatArray>>): ByteArray {
        val (len, hash) = assetFingerprint(assetBytes)
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeUTF(MAGIC)
        d.writeLong(len)
        d.writeInt(hash)
        d.writeInt(vecs.size)
        vecs.forEach { (id, v) ->
            d.writeUTF(id)
            for (f in v.first) d.writeFloat(f)
            for (f in v.second) d.writeFloat(f)
        }
        d.flush()
        return bos.toByteArray()
    }

    /** Null when the bytes are absent, corrupt, or from another asset version (→ rebuild). */
    fun decodeCache(assetBytes: ByteArray, cache: ByteArray?): Map<String, Pair<FloatArray, FloatArray>>? {
        if (cache == null || cache.isEmpty()) return null
        return runCatching {
            val d = DataInputStream(ByteArrayInputStream(cache))
            if (d.readUTF() != MAGIC) return null
            val (len, hash) = assetFingerprint(assetBytes)
            if (d.readLong() != len || d.readInt() != hash) return null
            val n = d.readInt()
            if (n < 0 || n > 1000) return null
            LinkedHashMap<String, Pair<FloatArray, FloatArray>>().also { out ->
                repeat(n) {
                    val id = d.readUTF()
                    val en = FloatArray(DIM) { d.readFloat() }
                    val hi = FloatArray(DIM) { d.readFloat() }
                    out[id] = en to hi
                }
            }
        }.getOrNull()
    }

    // ── Live store: parsed items + vectors, warmed in the background ──

    @Volatile private var items: List<VerifiedItem> = emptyList()
    @Volatile private var vecs: Map<String, Pair<FloatArray, FloatArray>> = emptyMap()
    @Volatile private var warming = false
    @Volatile private var lastQuery = ""
    @Volatile private var lastVec: FloatArray? = null
    @Volatile private var warmArgs: Triple<ByteArray, File, (String) -> FloatArray?>? = null

    /** True once question vectors exist (semantic match); else keyword overlap only. */
    fun isSemantic(): Boolean = vecs.isNotEmpty()

    fun count(): Int = items.size

    /** Parse-only load so keyword matching works immediately, before vectors exist. */
    fun configure(assetBytes: ByteArray) {
        if (items.isEmpty()) items = parseJson(assetBytes.decodeToString())
    }

    /**
     * Build the vector cache in the background at first start: read the cache file,
     * else embed the 200 question strings with the KB's own call and prefix, then save.
     * [embed] is SemanticSearchEngine.embed (384-dim, "query: " prefix inside).
     */
    fun warmAsync(assetBytes: ByteArray, cacheFile: File, embed: (String) -> FloatArray?) {
        configure(assetBytes)
        warmArgs = Triple(assetBytes, cacheFile, embed)
        if (warming || isSemantic()) return
        synchronized(this) {
            if (warming || isSemantic()) return
            warming = true
        }
        Thread({
            try {
                val cached = decodeCache(assetBytes, runCatching { cacheFile.readBytes() }.getOrNull())
                if (cached != null && cached.isNotEmpty()) {
                    vecs = cached
                    Log.i(TAG, "AROGYA_GOLD cache=hit items=${cached.size}")
                    return@Thread
                }
                val built = LinkedHashMap<String, Pair<FloatArray, FloatArray>>()
                for (it in items) {
                    val en = runCatching { embed(QUERY_PREFIX + it.qEn) }.getOrNull()?.takeIf { v -> v.size == DIM }
                    val hi = runCatching { embed(QUERY_PREFIX + it.qHi) }.getOrNull()?.takeIf { v -> v.size == DIM }
                    if (en != null && hi != null) built[it.id] = en to hi
                }
                if (built.isNotEmpty()) {
                    vecs = built
                    runCatching { cacheFile.writeBytes(encodeCache(assetBytes, built)) }
                    Log.i(TAG, "AROGYA_GOLD cache=built items=${built.size}")
                } else {
                    Log.w(TAG, "AROGYA_GOLD cache=empty (engine not ready; keyword-only)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AROGYA_GOLD warm failed ${t.message}")
            } finally {
                warming = false
            }
        }, "arogya-gold-warm").apply { isDaemon = true; start() }
    }

    /**
     * Rank the items for [query]. [queryVec] reuses the caller's vector when reachable;
     * else the last query's cached vector; else [embed] runs once. Null when no items.
     */
    fun match(query: String, queryVec: FloatArray? = null, embed: ((String) -> FloatArray?)? = null): Scored? {
        val all = items
        if (all.isEmpty() || query.isBlank()) return null
        // Vectors missed at start (engine not ready): retry in the background, answer keyword-only now.
        warmArgs?.let { if (!isSemantic()) runCatching { warmAsync(it.first, it.second, it.third) } }
        val qv = queryVec?.takeIf { it.size == DIM }
            ?: (if (query == lastQuery) lastVec?.takeIf { it.size == DIM } else null)
            ?: runCatching { embed?.invoke(QUERY_PREFIX + query)?.takeIf { it.size == DIM } }.getOrNull()
        if (qv != null) {
            lastQuery = query
            lastVec = qv
        }
        val v = vecs
        var best: VerifiedItem? = null
        var bestScore = -1.0
        var secondScore = -1.0
        for (it in all) {
            val s = score(query, it, qv, v[it.id]?.first, v[it.id]?.second)
            if (s > bestScore) {
                secondScore = bestScore
                bestScore = s
                best = it
            } else if (s > secondScore) {
                secondScore = s
            }
        }
        val top = best ?: return null
        val (strong, medium) = classify(bestScore, secondScore)
        val out = Scored(top, bestScore, strong, medium, secondScore)
        Log.i(TAG, "AROGYA_GOLD match top=${top.id} score=${"%.3f".format(java.util.Locale.US, bestScore)} " +
            "second=${"%.3f".format(java.util.Locale.US, secondScore)} pin=${strong || medium} semantic=${qv != null && v.isNotEmpty()}")
        return out
    }
}
