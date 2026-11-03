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
    const val CACHE_NAME = "verified_q_emb.bin"
    const val QUERY_PREFIX = "query: "

    data class VerifiedSource(
        val chunkId: Int, val sourceName: String, val title: String,
        val authority: String, val page: Int, val sourceUrl: String, val quote: String
    )

    data class VerifiedItem(
        val id: String, val qEn: String, val qHi: String,
        val keywords: List<String>, val aEn: String, val sources: List<VerifiedSource>
    )

    data class Scored(val item: VerifiedItem, val score: Double, val strong: Boolean, val medium: Boolean)

    /** Roman-Hindi words mapped to English before keyword overlap (spec C1). */
    val HINGLISH = mapOf(
        "naal" to "cord", "doodh" to "milk", "dudh" to "milk", "tika" to "vaccine",
        "bukhar" to "fever", "dast" to "diarrhoea", "garbhvati" to "pregnant",
        "saans" to "breathing", "peeliya" to "jaundice"
    )

    fun tokens(text: String): Set<String> =
        text.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }.toSet()

    fun applyHinglish(tokens: Set<String>): Set<String> = tokens.map { HINGLISH[it] ?: it }.toSet()

    /** Lowercase query tokens vs the item's questions + keywords, after the Hinglish map. */
    fun keywordOverlap(query: String, item: VerifiedItem): Double {
        val q = applyHinglish(tokens(query))
        if (q.isEmpty()) return 0.0
        val pool = tokens(item.qEn) + tokens(item.qHi) + item.keywords.map { it.lowercase(java.util.Locale.ROOT) }
        return q.intersect(pool).size.toDouble() / q.size
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

    /** 0.7 x cosine (best of EN/HI) + 0.3 x keyword overlap; keyword-only until vectors exist. */
    fun score(query: String, item: VerifiedItem, queryVec: FloatArray?, vecEn: FloatArray?, vecHi: FloatArray?): Double {
        val kw = keywordOverlap(query, item)
        if (queryVec == null || (vecEn == null && vecHi == null)) return kw
        val cos = maxOf(
            if (vecEn != null) cosine(queryVec, vecEn) else 0.0,
            if (vecHi != null) cosine(queryVec, vecHi) else 0.0
        )
        return 0.7 * cos + 0.3 * kw
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
                kws, a?.optString("en").orEmpty(), srcs
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
        for (it in all) {
            val s = score(query, it, qv, v[it.id]?.first, v[it.id]?.second)
            if (s > bestScore) {
                bestScore = s
                best = it
            }
        }
        val top = best ?: return null
        val out = Scored(top, bestScore, bestScore >= STRONG, bestScore >= MEDIUM && bestScore < STRONG)
        Log.i(TAG, "AROGYA_GOLD match top=${top.id} score=${"%.3f".format(java.util.Locale.US, bestScore)} semantic=${qv != null && v.isNotEmpty()}")
        return out
    }
}
