package com.example.arfas

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Selected-KB cross-encoder re-ranker (assets `reranker/model.onnx`; model
 * cross-encoder/mmarco-mMiniLMv2-L12-H384-v1, int8 ARM64). It reads the question and each
 * candidate passage together and returns a relevance logit per passage. No word lists: relevance
 * and "not in the knowledge base" are judged by the model.
 *
 * Tokenisation reuses the E5 tokenizer loaded by [SemanticSearchEngine]; both models use the same
 * XLM-R SentencePiece vocabulary (250,002 pieces, verified identical). The re-ranker's own
 * pre-tokenizer splits on whitespace first, so runs of whitespace are collapsed before encoding.
 * Pair layout (XLM-R): <s> question </s></s> passage </s>.
 *
 * The model file is re-extracted whenever the installed APK changes, so an app update can never
 * keep using an older extracted copy. The re-ranker is used only after it passes its own parity
 * check on the device ([ready]); otherwise, or on any error, retrieval keeps its normal order.
 * It only re-orders candidates; it never removes evidence.
 *
 * The on-device check runs in the background ([ready] never waits for it): questions asked before
 * it finishes are answered in the normal order. Only the session creation is locked, and nothing
 * that holds that lock calls back into a locked section (a non-reentrant Mutex held by the
 * verification while it loaded the session made every KB question hang).
 */
class GenericKbReranker(
    private val context: Context,
    /** SentencePiece ids of a text (no special tokens), at most maxPieces; null when the tokenizer is not loaded. */
    private val pieces: (String, Int) -> IntArray?
) {
    constructor(context: Context, tokens: SemanticSearchEngine) :
        this(context, { text, maxPieces -> tokens.sentencePieceIds(text, maxPieces) })

    companion object {
        private const val TAG = "GenericKbReranker"
        const val ASSET = "reranker/model.onnx"
        /** Parity fixture shipped with the model: made on the PC with the re-ranker's own tokenizer. */
        const val PARITY_ASSET = "reranker/parity.json"
        /**
         * Allowed |phone logit − PC logit| per parity case, with token ids required to match exactly.
         * int8 activation quantisation differs by CPU. Measured on the target phone (Samsung, 8 cases,
         * ids identical in all 8): deltas 0.01, 0.08, 0.10, 0.15, 0.22, 0.28, 0.29, 0.51 on logits
         * from −6.1 to +10.9; case order preserved except two cases 0.03 apart on the PC. 0.75 = the
         * measured maximum plus 50 %; a broken model or wrong tokenizer differs by whole logits.
         */
        const val LOGIT_TOLERANCE = 0.75f
        private const val MAX_QUERY_PIECES = 64
        const val MAX_PAIR_TOKENS = 320
        /** Pairs per re-ranker call (grouped by length). */
        private const val BATCH_SIZE = 8
        private const val SCORE_CACHE_PAIRS = 1_000
        private const val BOS = 0L
        private const val PAD = 1L
        private const val EOS = 2L
    }

    private val sessionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verificationStarted = AtomicBoolean(false)
    @Volatile private var session: OrtSession? = null
    @Volatile private var failed = false

    @Volatile private var verified: Boolean? = null

    /**
     * True only when the model asset is present AND it passed its own parity check on this device
     * (assets/reranker/parity.json: pairs with the token ids produced by the re-ranker's own
     * tokenizer and the logits produced on the PC). Checked once per installed APK; the verdict is
     * cached. Any failure -> false, and retrieval keeps its normal order.
     *
     * Never waits: the first call starts the check in the background and returns false until the
     * check has finished.
     */
    fun ready(): Boolean {
        verified?.let { return it && !failed }
        if (verificationStarted.compareAndSet(false, true)) {
            Log.i(TAG, "RERANKER_VERIFYING background=true")
            scope.launch {
                val verdict = runCatching { verify() }
                    .onFailure { Log.w(TAG, "RERANKER_OFF reason=verify_error ${it.javaClass.simpleName}:${it.message}") }
                    .getOrDefault(false)
                if (verdict == null) verificationStarted.set(false) else verified = verdict
            }
        }
        return false
    }

    /** Suspends until the background check has finished (tests and diagnostics only). */
    internal suspend fun awaitVerdictForTest(): Boolean {
        while (verified == null) kotlinx.coroutines.delay(10)
        return verified == true && !failed
    }

    /** true/false = verdict (cached per APK); null = not decidable yet (tokenizer not loaded), retried later. */
    private suspend fun verify(): Boolean? = withContext(Dispatchers.IO) {
        if (runCatching { context.assets.open(ASSET).use { true } }.getOrDefault(false).not()) {
            Log.i(TAG, "RERANKER_OFF reason=no_model_asset"); return@withContext false
        }
        val stamp = File(context.packageCodePath).let { "${it.lastModified()}:${it.length()}" }
        val cache = File(context.filesDir, "reranker_parity.verdict")
        runCatching { cache.readLines() }.getOrNull()?.takeIf { it.size >= 2 && it[0] == stamp }?.let {
            Log.i(TAG, "RERANKER_PARITY cached=${it[1]}"); return@withContext it[1] == "pass"
        }
        val json = runCatching { context.assets.open(PARITY_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        if (json == null) {
            Log.w(TAG, "RERANKER_OFF reason=no_parity_fixture asset=$PARITY_ASSET"); return@withContext false
        }
        val cases = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return@withContext false
        if (pieces("tokenizer probe", MAX_QUERY_PIECES) == null) {
            Log.i(TAG, "RERANKER_VERIFY_DEFERRED reason=tokenizer_not_loaded"); return@withContext null
        }
        var passed = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val question = case.getString("question")
            val passage = case.getString("passage")
            val expectedJson = case.getJSONArray("token_ids")
            val expected = IntArray(expectedJson.length()) { expectedJson.getInt(it) }
            val ids = pairIds(question, passage)?.map { it.toInt() }?.toIntArray()
            val idsMatch = ids != null && ids.contentEquals(expected)
            val logit = if (idsMatch) scoreUnchecked(question, listOf(passage))?.firstOrNull() else null
            val delta = logit?.let { kotlin.math.abs(it - case.getDouble("logit").toFloat()) }
            val ok = idsMatch && delta != null && delta <= LOGIT_TOLERANCE
            if (ok) passed++
            Log.i(TAG, "RERANKER_PARITY_CASE ok=$ok idsMatch=$idsMatch logit=$logit expected=${case.getDouble("logit")} delta=$delta")
        }
        val pass = cases.length() > 0 && passed == cases.length()
        Log.i(TAG, "RERANKER_PARITY_RESULT passed=$passed/${cases.length()} tolerance=$LOGIT_TOLERANCE active=$pass")
        runCatching { cache.writeText("$stamp\n${if (pass) "pass" else "fail"}\n") }
        pass
    }

    /** Relevance logits for [passages] (same order); null when the re-ranker is not verified or fails. */
    suspend fun score(question: String, passages: List<String>): FloatArray? {
        if (!ready()) return null
        val started = System.currentTimeMillis()
        // Pairs already read (the same question searched again: Check online, a retry, the rewrite's
        // passages that the literal search also found) are not read again; the scores are exact.
        val keys = passages.map { pairKey(question, it) }
        val out = FloatArray(passages.size)
        val missing = ArrayList<Int>()
        synchronized(scoreCache) { keys.forEachIndexed { i, k -> scoreCache[k]?.let { out[i] = it } ?: missing.add(i) } }
        val fresh = if (missing.isEmpty()) FloatArray(0) else scoreUnchecked(question, missing.map { passages[it] })
        if (fresh != null) {
            synchronized(scoreCache) { missing.forEachIndexed { j, i -> out[i] = fresh[j]; scoreCache[keys[i]] = fresh[j] } }
        }
        Log.i(TAG, "RERANK_TIMING pairs=${passages.size} cached=${passages.size - missing.size} ok=${fresh != null} elapsed=${System.currentTimeMillis() - started}ms")
        return if (fresh != null) out else null
    }

    /**
     * UI2 speed pass: (question, passage) pairs with DIFFERENT questions, read in one batched run
     * (the answer-proof check: one statement per pair). Same scores and cache as [score]; one
     * call instead of one per statement (~1.1 s each on the phone).
     */
    suspend fun scorePairs(pairs: List<Pair<String, String>>): FloatArray? {
        if (!ready()) return null
        if (pairs.isEmpty()) return FloatArray(0)
        val started = System.currentTimeMillis()
        val keys = pairs.map { pairKey(it.first, it.second) }
        val out = FloatArray(pairs.size)
        val missing = ArrayList<Int>()
        synchronized(scoreCache) { keys.forEachIndexed { i, k -> scoreCache[k]?.let { out[i] = it } ?: missing.add(i) } }
        val fresh = if (missing.isEmpty()) FloatArray(0) else withContext(Dispatchers.IO) {
            val rows = missing.map { i -> pairIds(pairs[i].first, pairs[i].second) ?: return@withContext null }
            scoreRows(rows)
        }
        if (fresh != null) {
            synchronized(scoreCache) { missing.forEachIndexed { j, i -> out[i] = fresh[j]; scoreCache[keys[i]] = fresh[j] } }
        }
        Log.i(TAG, "RERANK_TIMING pairs=${pairs.size} cached=${pairs.size - missing.size} ok=${fresh != null} mixed=true elapsed=${System.currentTimeMillis() - started}ms")
        return if (fresh != null) out else null
    }

    private fun pairKey(question: String, passage: String): String = "${question.length}:${question.hashCode()}:${passage.length}:${passage.hashCode()}:${question.take(40)}"

    /** Recent (question, passage) scores, least recently used dropped first. */
    private val scoreCache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > SCORE_CACHE_PAIRS
    }

    /** XLM-R pair layout <s> q </s></s> p </s>, passage truncated to fit (as HF "only_second"). */
    private fun pairIds(question: String, passage: String): LongArray? {
        val q = pieces(collapse(question), MAX_QUERY_PIECES) ?: return null
        val room = MAX_PAIR_TOKENS - q.size - 4
        if (room < 16) return null
        val body = pieces(collapse(passage), room) ?: return null
        return LongArray(q.size + body.size + 4).also { ids ->
            var k = 0
            ids[k++] = BOS
            q.forEach { ids[k++] = it.toLong() }
            ids[k++] = EOS; ids[k++] = EOS
            body.forEach { ids[k++] = it.toLong() }
            ids[k] = EOS
        }
    }

    private suspend fun scoreUnchecked(question: String, passages: List<String>): FloatArray? = withContext(Dispatchers.IO) {
        if (passages.isEmpty()) return@withContext FloatArray(0)
        val rows = passages.map { p -> pairIds(question, p) ?: return@withContext null }
        scoreRows(rows)
    }

    /** Logits for ready-made pair rows (same order). */
    private suspend fun scoreRows(rows: List<LongArray>): FloatArray? = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext FloatArray(0)
        val sess = ensureSession() ?: return@withContext null
        // Small batches of similar length: each batch is padded only to its own longest pair and
        // memory stays bounded. One batch of all pairs padded to the longest measured ~1.2 s per
        // pair on the phone (94 pairs, 110 s); questions with ~20 pairs had measured ~0.5 s per pair.
        val out = FloatArray(rows.size)
        val order = rows.indices.sortedBy { rows[it].size }
        val env = OrtEnvironment.getEnvironment()
        for (batch in order.chunked(BATCH_SIZE)) {
            // UI5: a cancelled caller (the answer-proof timeout) stops between batches instead of
            // reading every remaining pair (measured: a 12 s proof timeout ran on for 42 s).
            ensureActive()
            val width = batch.maxOf { rows[it].size }
            val ids = LongArray(batch.size * width) { PAD }
            val mask = LongArray(batch.size * width)
            batch.forEachIndexed { r, index ->
                rows[index].forEachIndexed { c, v -> ids[r * width + c] = v; mask[r * width + c] = 1L }
            }
            val shape = longArrayOf(batch.size.toLong(), width.toLong())
            val logits = runCatching {
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskT ->
                        sess.run(mapOf("input_ids" to idsT, "attention_mask" to maskT)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            (result.get(0).value as Array<FloatArray>).map { it[0] }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "RERANK_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrNull()
                ?: return@withContext null
            batch.forEachIndexed { r, index -> out[index] = logits[r] }
        }
        out
    }

    private fun collapse(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private suspend fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (failed) return null
        return sessionMutex.withLock {
            session ?: runCatching {
                val file = extract() ?: error("asset missing")
                // Re-ranking runs before the answer model starts (which uses the GPU), so it may use
                // half of the CPU cores (2..4). Measured with 2 threads: ~0.5 s per passage.
                // The fast cores (see LlamaCppCore.threadsFor): 4 on a 4 big + 4 little phone, 6 on a
                // phone whose cores are all fast. Re-ranking runs before the answer model starts.
                val threads = LlamaCppCore.threadsFor(Runtime.getRuntime().availableProcessors(), LlamaCppCore.coreMaxFreqs()).coerceAtMost(6)
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
                OrtEnvironment.getEnvironment().createSession(file.absolutePath, opts).also {
                    Log.i(TAG, "RERANKER_READY bytes=${file.length()} inputs=${it.inputNames}")
                }
            }.onFailure { failed = true; Log.w(TAG, "RERANKER_UNAVAILABLE ${it.message}") }.getOrNull()
                ?.also { session = it }
        }
    }

    /** Copies the asset out of the APK; re-copies whenever the installed APK changes. */
    private fun extract(): File? {
        val out = File(context.filesDir, ASSET.replace("/", "_"))
        val stampFile = File(out.absolutePath + ".stamp")
        val stamp = File(context.packageCodePath).let { "${it.lastModified()}:${it.length()}" }
        if (out.exists() && out.length() > 1000L && stampFile.exists() && stampFile.readText() == stamp) return out
        val tmp = File(out.absolutePath + ".tmp")
        context.assets.open(ASSET).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 1 shl 20) } }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) return null
        stampFile.writeText(stamp)
        Log.i(TAG, "RERANKER_EXTRACTED bytes=${out.length()} stamp=$stamp")
        return out
    }
}
