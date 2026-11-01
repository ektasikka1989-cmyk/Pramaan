package com.example.arfas

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Phase 1.4 query-side parity gate. Runs only when the debug asset `kb_parity.json`
 * (the parity.json fixture produced by e5_residual_check.py) is bundled in app assets;
 * production builds without that asset do nothing. Results are written to the log:
 *   KB_PARITY_CASE ... idsMatch=true cos=0.99998 ...
 *   KB_PARITY_RESULT passed=12/12
 * Pass condition per string: identical token ids and embedding cosine >= 0.999 (unchanged until
 * repeatable cross-device measurements justify a different tolerance). Also logs KB_PARITY_ASSET
 * with the SHA-256 of the packaged and extracted model and tokenizer.
 */
object KbParityCheck {
    private const val TAG = "KbParityCheck"
    private const val ASSET = "kb_parity.json"
    @Volatile private var attempted = false

    suspend fun runOnceIfFixturePresent(context: Context, engine: SemanticSearchEngine) {
        if (attempted || !engine.isReady) return
        attempted = true
        val json = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull() ?: return
        val cases = runCatching { JSONArray(json) }.getOrNull() ?: return
        logModelHashes(context)
        var passed = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            val expectedIdsJson = case.getJSONArray("token_ids")
            val expectedIds = IntArray(expectedIdsJson.length()) { expectedIdsJson.getInt(it) }
            val expectedEmbJson = case.getJSONArray("embedding")
            val expectedEmb = FloatArray(expectedEmbJson.length()) { expectedEmbJson.getDouble(it).toFloat() }
            val snapshot = engine.paritySnapshot(text)
            val idsMatch = snapshot != null && snapshot.first.contentEquals(expectedIds)
            val cos = snapshot?.second?.let { engine.cosineSimilarity(it, expectedEmb) } ?: 0f
            val ok = idsMatch && cos >= 0.999f
            if (ok) passed++
            Log.i(
                TAG,
                "KB_PARITY_CASE ok=$ok idsMatch=$idsMatch cos=${"%.5f".format(java.util.Locale.US, cos)} " +
                    "text=${text.take(60)} phoneIds=${snapshot?.first?.joinToString(",")?.take(200)} expectedIds=${expectedIds.joinToString(",").take(200)}"
            )
        }
        Log.i(TAG, "KB_PARITY_RESULT passed=$passed/${cases.length()}")
    }

    /**
     * SHA-256 of the packaged model/tokenizer assets and of the extracted copies the engine
     * actually loads (filesDir/<root>_model.onnx etc.), so the log proves which files are in use.
     * Compare with `sha256sum model.onnx tokenizer.json` on the PC that built the pack.
     */
    private fun logModelHashes(context: Context) {
        for (root in listOf("semantic", "semantics", "reranker")) {
            for (name in listOf("model.onnx", "tokenizer.json")) {
                val asset = "$root/$name"
                val packaged = runCatching { context.assets.open(asset).use { sha256(it) } }.getOrNull() ?: continue
                val extracted = java.io.File(context.filesDir, asset.replace("/", "_"))
                val extractedHash = if (extracted.exists()) runCatching { extracted.inputStream().use { sha256(it) } }.getOrNull() else null
                Log.i(
                    TAG,
                    "KB_PARITY_ASSET name=$asset packagedSha256=$packaged extractedSha256=${extractedHash ?: "-"} " +
                        "extractedBytes=${if (extracted.exists()) extracted.length() else -1} same=${extractedHash == packaged}"
                )
            }
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
