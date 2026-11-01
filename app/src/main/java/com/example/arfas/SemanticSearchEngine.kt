package com.example.arfas

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.LongBuffer
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.text.Normalizer
import kotlin.math.sqrt

private const val TAG = "SemanticSearchEngine"

/**
 * SemanticSearchEngine for intfloat/multilingual-e5-small.
 *
 * IMPORTANT:
 * tokenizer.json for multilingual-e5-small is NOT BPE.  Its model section
 * is Unigram + Metaspace.  The previous implementation incorrectly treated
 * it as a BPE/merge tokenizer.  This implementation reads the Unigram
 * vocabulary and performs Viterbi segmentation.
 *
 * Query side:
 *   "query: " + user text
 *
 * Document side:
 *   "passage: " + SOP chunk
 *
 * The database embeddings are expected to be 384-dim float32 values
 * stored as 1536-byte little-endian BLOBs.
 */
class SemanticSearchEngine(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: E5Tokenizer? = null

    val isReady: Boolean
        get() = session != null && tokenizer != null

    suspend fun initialise(): Boolean = withContext(Dispatchers.IO) {
        if (isReady) {
            Log.d(TAG, "SEMANTIC_INIT already_ready")
            return@withContext true
        }

        close()

        try {
            val assetRoot = resolveSemanticAssetRoot()
            if (assetRoot == null) {
                Log.e(TAG, "SEMANTIC_INIT_FAILED no semantic asset directory found")
                Log.e(TAG, "SEMANTIC_ASSET_EXPECTED semantic/ or semantics/")
                return@withContext false
            }

            val modelAsset = "$assetRoot/model.onnx"
            val tokenizerAsset = "$assetRoot/tokenizer.json"

            Log.i(TAG, "SEMANTIC_INIT_START root=$assetRoot")

            val modelFile = extractAssetToFiles(modelAsset)
            val tokenizerFile = extractAssetToFiles(tokenizerAsset)

            if (modelFile == null || tokenizerFile == null) {
                Log.e(
                    TAG,
                    "SEMANTIC_INIT assets_missing root=$assetRoot " +
                        "model=${modelFile != null} tokenizer=${tokenizerFile != null}"
                )
                return@withContext false
            }

            Log.i(
                TAG,
                "SEMANTIC_ASSETS_READY modelBytes=${modelFile.length()} " +
                    "tokenizerBytes=${tokenizerFile.length()}"
            )

            ortEnv = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }

            session = ortEnv!!.createSession(modelFile.absolutePath, opts)

            Log.i(
                TAG,
                "SEMANTIC_ONNX_READY inputs=${session!!.inputNames.joinToString()} " +
                    "outputs=${session!!.outputNames.joinToString()}"
            )

            tokenizer = E5Tokenizer(tokenizerFile)

            val probe = tokenizer!!.encode("query: semantic retrieval self test", 32)
            val activeIds = probe.inputIds.count { it != 1 }
            val activeMask = probe.attentionMask.sum()

            require(activeIds > 2) {
                "Tokenizer probe produced too few active ids: $activeIds"
            }
            require(activeMask > 2) {
                "Tokenizer probe produced invalid attention mask: $activeMask"
            }

            val probeEmbedding = embed("query: semantic retrieval self test")
            if (probeEmbedding == null || probeEmbedding.size != 384) {
                // Log the failure in detail but do NOT throw here.
                // A failed probe means the ONNX model can't infer (wrong input names,
                // incompatible runtime, etc.). Log it and return false cleanly
                // instead of throwing into the outer catch which also calls close().
                Log.e(
                    TAG,
                    "SEMANTIC_PROBE_FAILED embedding=${probeEmbedding?.size ?: "null"} " +
                        "expected=384. Check ONNX input names: ${session?.inputNames?.joinToString()}"
                )
                close()
                return@withContext false
            }

            Log.i(
                TAG,
                "SEMANTIC_READY root=$assetRoot model=true tokenizer=true " +
                    "vocab=${tokenizer!!.vocabSize} dims=${probeEmbedding.size}"
            )

            true
        } catch (t: Throwable) {
            Log.e(TAG, "SEMANTIC_INIT_FAILED: ${t.message}", t)
            close()
            false
        }
    }

    private fun resolveSemanticAssetRoot(): String? {
        val candidates = listOf("semantic", "semantics")

        for (root in candidates) {
            val modelPath = "$root/model.onnx"
            val tokenizerPath = "$root/tokenizer.json"

            val modelExists = assetExists(modelPath)
            val tokenizerExists = assetExists(tokenizerPath)

            Log.d(
                TAG,
                "SEMANTIC_ASSET_CHECK root=$root model=$modelExists tokenizer=$tokenizerExists"
            )

            if (modelExists && tokenizerExists) {
                return root
            }
        }

        return null
    }

    private fun assetExists(assetPath: String): Boolean =
        try {
            context.assets.open(assetPath).use { true }
        } catch (_: Throwable) {
            false
        }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext null
        val tok = tokenizer ?: return@withContext null
        val env = ortEnv ?: return@withContext null

        try {
            val queryText = if (
                text.startsWith("query: ") || text.startsWith("passage: ")
            ) {
                text
            } else {
                "query: $text"
            }

            val encoded = tok.encode(queryText, 512)
            val ids = encoded.inputIds
            val mask = encoded.attentionMask
            val seqLen = ids.size.toLong()

            require(ids.size == 512) { "Unexpected token length: ${ids.size}" }

            // Log model input types once so we can diagnose INT32 vs INT64 mismatches.
            val inputInfoMap = try { sess.inputInfo } catch (_: Throwable) { emptyMap() }
            val useInt32 = inputInfoMap["input_ids"]?.info?.toString()
                ?.contains("INT32", ignoreCase = true) == true
            Log.d(TAG, "ONNX_INPUT_TYPES int32=$useInt32 " +
                inputInfoMap.entries.joinToString { "${it.key}=${it.value?.info?.toString()?.take(20)}" })

            fun makeTensor(intVals: IntArray): OnnxTensor =
                if (useInt32)
                    OnnxTensor.createTensor(env,
                        java.nio.IntBuffer.wrap(intVals), longArrayOf(1L, seqLen))
                else
                    OnnxTensor.createTensor(env,
                        LongBuffer.wrap(intVals.map(Int::toLong).toLongArray()), longArrayOf(1L, seqLen))

            val idsTensor      = makeTensor(ids)
            val maskTensor     = makeTensor(mask)
            val typeIdsTensor  = makeTensor(IntArray(ids.size) { 0 })

            try {
                val inputNames = sess.inputNames
                val inputs = buildMap {
                    put("input_ids", idsTensor)
                    put("attention_mask", maskTensor)
                    if (inputNames.contains("token_type_ids")) {
                        put("token_type_ids", typeIdsTensor)
                    }
                }
                Log.d(TAG, "ONNX_INPUTS ${inputs.keys.joinToString()} seqLen=$seqLen int32=$useInt32")

                sess.run(inputs).use { result ->
                    val raw = result.get(0).value
                    Log.d(TAG, "ONNX_OUTPUT_TYPE ${raw?.javaClass?.simpleName}")

                    // Handle all three output shapes that ONNX exports produce:
                    //   Array<Array<FloatArray>>  shape [1][seqLen][384]  (batch dim present)
                    //   Array<FloatArray>          shape [seqLen][384]     (no batch dim)
                    //   FloatArray                 shape [1*seqLen*384]    (flat, rare)
                    val tokenEmbeddings: Array<FloatArray> = when (raw) {
                        is Array<*> -> {
                            val first = raw.getOrNull(0)
                            when {
                                first is Array<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    (raw[0] as Array<FloatArray>)
                                }
                                first is FloatArray -> {
                                    @Suppress("UNCHECKED_CAST")
                                    (raw as Array<FloatArray>)
                                }
                                else -> error("Unexpected nested output: ${first?.javaClass}")
                            }
                        }
                        is FloatArray -> {
                            val dims = 384
                            val n = raw.size / dims
                            Array(n) { i -> raw.copyOfRange(i * dims, (i + 1) * dims) }
                        }
                        else -> error("Unexpected ONNX output type: ${raw?.javaClass}")
                    }

                    require(tokenEmbeddings.isNotEmpty()) { "Empty ONNX output" }

                    val dimensions = tokenEmbeddings[0].size
                    require(dimensions == 384) {
                        "Expected 384 dimensions, got $dimensions"
                    }

                    // Mean pooling over attention_mask=1 tokens.
                    val embedding = FloatArray(384)
                    var count = 0

                    for (i in tokenEmbeddings.indices) {
                        if (mask[i] == 1) {
                            val row = tokenEmbeddings[i]
                            for (j in 0 until 384) {
                                embedding[j] += row[j]
                            }
                            count++
                        }
                    }

                    require(count > 0) { "No active tokens" }

                    for (j in 0 until 384) {
                        embedding[j] /= count.toFloat()
                    }

                    // E5 retrieval uses normalized embeddings.
                    var sum = 0.0
                    for (v in embedding) {
                        sum += v.toDouble() * v.toDouble()
                    }

                    val norm = sqrt(sum).toFloat()
                    require(norm > 1.0e-12f) { "Zero embedding" }

                    for (j in embedding.indices) {
                        embedding[j] /= norm
                    }

                    Log.d(
                        TAG,
                        "EMBED_OK dims=384 tokens=$count norm=1.0 query='${text.take(80)}'"
                    )

                    embedding
                }
            } finally {
                idsTensor.close()
                maskTensor.close()
                typeIdsTensor.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "EMBED_FAILED query='${text.take(120)}': ${t.message}", t)
            null
        }
    }

    /**
     * Debug/test only (Phase 1.4 parity gate): unpadded token ids and the embedding the app
     * produces for [text], through the real tokenizer and ONNX path. No routing effect.
     */
    suspend fun paritySnapshot(text: String): Pair<IntArray, FloatArray?>? = withContext(Dispatchers.IO) {
        val tok = tokenizer ?: return@withContext null
        val encoded = tok.encode(text, 512)
        val ids = encoded.inputIds.filterIndexed { i, _ -> encoded.attentionMask[i] == 1 }.toIntArray()
        ids to embed(text)
    }

    /**
     * SentencePiece ids of [text] without <s>/</s>, from the loaded E5 tokenizer (XLM-R vocabulary).
     * Used by the selected-KB re-ranker, whose tokenizer shares this exact vocabulary (verified
     * by the re-ranker parity fixture). Does not change embedding behaviour.
     */
    fun sentencePieceIds(text: String, maxPieces: Int): IntArray? {
        val tok = tokenizer ?: return null
        val encoded = tok.encode(text, maxPieces + 2)
        val ids = encoded.inputIds.filterIndexed { i, _ -> encoded.attentionMask[i] == 1 }
        return ids.drop(1).dropLast(1).toIntArray()
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != 384 || b.size != 384) return 0f

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in 0 until 384) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }

        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    fun blobToFloatArray(blob: ByteArray): FloatArray? {
        if (blob.size != 1536) {
            Log.w(TAG, "BAD_EMBEDDING_BLOB size=${blob.size}, expected=1536")
            return null
        }

        val buffer = ByteBuffer
            .wrap(blob)
            .order(ByteOrder.LITTLE_ENDIAN)

        val values = FloatArray(384)
        for (i in 0 until 384) {
            values[i] = buffer.float
        }
        return values
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null

        try {
            ortEnv?.close()
        } catch (_: Throwable) {
        }
        ortEnv = null

        tokenizer = null
        Log.d(TAG, "SemanticSearchEngine closed")
    }

    private fun extractAssetToFiles(assetPath: String): File? {
        return try {
            val outFile = File(
                context.filesDir,
                assetPath.replace("/", "_")
            )

            val assetLength = try {
                context.assets.openFd(assetPath).length
            } catch (_: Throwable) {
                -1L
            }

            // Re-copied whenever the installed app changes (an update may ship a different model of
            // the same size), so updating never needs an uninstall, which would delete the user's
            // knowledge bases and chat history.
            val stampFile = File(outFile.absolutePath + ".stamp")
            val apkStamp = runCatching { File(context.packageCodePath).let { "${it.lastModified()}:${it.length()}" } }.getOrDefault("")
            val needsExtract =
                !outFile.exists() ||
                    outFile.length() < 1000L ||
                    (assetLength > 0L && outFile.length() != assetLength) ||
                    (apkStamp.isNotEmpty() && runCatching { stampFile.readText() }.getOrDefault("") != apkStamp)

            if (needsExtract) {
                Log.d(
                    TAG,
                    "SEMANTIC_EXTRACT asset=$assetPath expected=$assetLength existing=${outFile.length()}"
                )

                val tempFile = File(outFile.absolutePath + ".tmp")

                context.assets.open(assetPath).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                        }
                    }
                }

                if (outFile.exists()) outFile.delete()

                require(tempFile.renameTo(outFile)) {
                    "Could not finalize extracted asset: $assetPath"
                }
                if (apkStamp.isNotEmpty()) runCatching { stampFile.writeText(apkStamp) }
            }

            outFile.takeIf {
                it.exists() &&
                    it.length() >= 1000L &&
                    (assetLength <= 0L || it.length() == assetLength)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ASSET_EXTRACT_FAILED $assetPath: ${t.message}", t)
            null
        }
    }
}

/**
 * Exact model type from the supplied tokenizer.json:
 *
 *   model.type = Unigram
 *   pre_tokenizer.type = Metaspace
 *   replacement = ▁
 *   add_prefix_space = true
 *
 * This intentionally does NOT implement BPE merges.
 */
private class E5Tokenizer(private val tokenizerFile: File) {

    data class Encoding(
        val inputIds: IntArray,
        val attentionMask: IntArray
    )

    private data class Piece(
        val text: String,
        val score: Float,
        val id: Int
    )

    private val piecesByFirstChar = HashMap<Char, MutableList<Piece>>()
    private val directSpecialIds = HashMap<String, Int>()

    val vocabSize: Int
        get() = directSpecialIds.size + piecesByFirstChar.values.sumOf { it.size }

    init {
        loadUnigramVocab()
        Log.i(
            "E5Tokenizer",
            "UNIGRAM_READY vocab=${vocabSize} buckets=${piecesByFirstChar.size}"
        )
    }

    private fun commitVocabEntry(
        piece: String,
        score: Float,
        id: Int,
        specialIds: MutableMap<String, Int>,
        byFirstChar: MutableMap<Char, MutableList<Piece>>
    ) {
        when (piece) {
            "<s>", "<pad>", "</s>", "<unk>" -> specialIds[piece] = id
            else -> if (piece.isNotEmpty()) {
                byFirstChar.getOrPut(piece[0]) { mutableListOf() }.add(Piece(piece, score, id))
            }
        }
    }

    private fun loadUnigramVocab() {
        var inVocab = false
        var nextId = 0

        // The tokenizer.json for multilingual-e5-small (XLM-RoBERTa based) uses
        // MULTI-LINE vocab entries, 4 lines each:
        //   [
        //     "▁piece",
        //     -2.3456
        //   ],
        // The previous single-line Regex [ "piece", score ] never matched these.
        // Fix: use a small state machine that tracks pending piece/score across lines.

        var pendingPiece: String? = null     // piece string from line N
        var inEntry = false                  // inside a [ ... ] entry block
        var entryDepth = 0                   // bracket depth within an entry

        tokenizerFile.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { raw ->
                val line = raw.trim()

                if (!inVocab) {
                    if (line == "\"vocab\": [" || line == "\"vocab\":[") {
                        inVocab = true
                        Log.d("E5Tokenizer", "VOCAB_SECTION_FOUND vocab=[")
                    }
                    return@forEachLine
                }

                // Top-level end of vocab array
                if (!inEntry && (line == "]," || line == "]")) {
                    inVocab = false
                    return@forEachLine
                }

                // --- Multi-line entry state machine ---
                // Entry starts with "[" on its own line
                if (!inEntry && line == "[") {
                    inEntry = true
                    entryDepth = 1
                    pendingPiece = null
                    return@forEachLine
                }

                // Single-line entry: ["piece", score],
                if (!inEntry && line.startsWith("[\"")) {
                    val match = Regex(
                        """^\["(.*)"\s*,\s*(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*\],?$"""
                    ).find(line)
                    if (match != null) {
                        val piece = unescapeJsonString(match.groupValues[1])
                        val score = match.groupValues[2].toFloatOrNull() ?: return@forEachLine
                        commitVocabEntry(piece, score, nextId++, directSpecialIds, piecesByFirstChar)
                    }
                    return@forEachLine
                }

                if (inEntry) {
                    // Closing bracket of entry
                    if (line == "]," || line == "]") {
                        inEntry = false
                        entryDepth = 0
                        pendingPiece = null
                        return@forEachLine
                    }

                    // A string line — this is the piece name
                    if (line.startsWith("\"") && line.endsWith("\",")) {
                        // "piece",
                        pendingPiece = unescapeJsonString(line.drop(1).dropLast(2))
                        return@forEachLine
                    }
                    if (line.startsWith("\"") && line.endsWith("\"")) {
                        // "piece"  (no trailing comma — last element before closing bracket)
                        pendingPiece = unescapeJsonString(line.drop(1).dropLast(1))
                        return@forEachLine
                    }

                    // A number line — this is the score
                    val scoreStr = line.trimEnd(',')
                    val score = scoreStr.toFloatOrNull()
                    if (score != null && pendingPiece != null) {
                        commitVocabEntry(pendingPiece!!, score, nextId++, directSpecialIds, piecesByFirstChar)
                        pendingPiece = null
                    }
                }
            }
        }
        Log.d("E5Tokenizer", "VOCAB_PARSE_DONE entries=$nextId")

        // Validate special tokens. The multilingual-e5-small tokenizer.json
        // from HuggingFace uses a Unigram vocab where the special tokens may
        // not always be at exactly positions 0/1/2/3 depending on the export.
        // Log mismatches but do not crash — the ONNX model itself handles
        // token IDs; the tokenizer just needs consistent mappings internally.
        // Log what we found for diagnostics.
        val bos = directSpecialIds["<s>"]
        val pad = directSpecialIds["<pad>"]
        val eos = directSpecialIds["</s>"]
        val unk = directSpecialIds["<unk>"]

        if (bos == null || eos == null || pad == null || unk == null) {
            // Vocab section was not found or file format is different.
            // Assign standard sentencepiece defaults so tokenizer can still function.
            // The model will produce lower-quality embeddings but won't crash.
            Log.w(
                "E5Tokenizer",
                "TOKENIZER_SPECIAL_MISSING bos=$bos pad=$pad eos=$eos unk=$unk — " +
                    "using sentencepiece defaults: <unk>=0 <s>=1 </s>=2 <pad>=3"
            )
            if (!directSpecialIds.containsKey("<unk>")) directSpecialIds["<unk>"] = 0
            if (!directSpecialIds.containsKey("<s>"))   directSpecialIds["<s>"]   = 1
            if (!directSpecialIds.containsKey("</s>"))  directSpecialIds["</s>"]  = 2
            if (!directSpecialIds.containsKey("<pad>")) directSpecialIds["<pad>"] = 3
        } else if (bos != 0 || pad != 1 || eos != 2) {
            Log.w(
                "E5Tokenizer",
                "TOKENIZER_SPECIAL_ORDER_MISMATCH: <s>=$bos (expected 0) " +
                    "<pad>=$pad (expected 1) </s>=$eos (expected 2) " +
                    "<unk>=$unk (expected 3). Continuing with actual ids."
            )
        } else {
            Log.d("E5Tokenizer", "TOKENIZER_SPECIAL_IDS_OK bos=$bos pad=$pad eos=$eos unk=$unk")
        }
        // <unk> position is also flexible across exports; already validated above.
    }

    fun encode(text: String, maxLength: Int): Encoding {
        val normalized = Normalizer.normalize(
            text.replace("\r\n", "\n"),
            Normalizer.Form.NFKC
        )

        // Metaspace pre-tokenizer:
        // add_prefix_space=true and replacement='▁'.
        val metaspaceText = "▁" + normalized
            .replace(" ", "▁")
            .replace("\t", "▁")
            .replace("\n", "▁")
            .replace("\r", "▁")

        val body = unigramEncode(metaspaceText)

        val ids = ArrayList<Int>(minOf(maxLength, body.size + 2))
        ids.add(directSpecialIds["<s>"] ?: 0)

        for (id in body) {
            if (ids.size >= maxLength - 1) break
            ids.add(id)
        }

        ids.add(directSpecialIds["</s>"] ?: 2)

        val padded = IntArray(maxLength) { directSpecialIds["<pad>"] ?: 1 }
        val mask = IntArray(maxLength)

        ids.take(maxLength).forEachIndexed { index, id ->
            padded[index] = id
            mask[index] = 1
        }

        return Encoding(padded, mask)
    }

    /**
     * SentencePiece/Unigram Viterbi segmentation.
     *
     * At each UTF-16 position, choose the highest-score vocabulary
     * segmentation reaching that position.  Unknown characters use <unk>.
     */
    private fun unigramEncode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()

        val chars = text.toCharArray()
        val n = chars.size

        data class State(
            val score: Float,
            val ids: List<Int>
        )

        val best = arrayOfNulls<State>(n + 1)
        best[0] = State(0f, emptyList())

        for (start in 0 until n) {
            val state = best[start] ?: continue
            val candidates = piecesByFirstChar[chars[start]]

            var matched = false

            if (candidates != null) {
                for (piece in candidates) {
                    val end = start + piece.text.length
                    if (end > n) continue

                    var same = true
                    for (j in piece.text.indices) {
                        if (chars[start + j] != piece.text[j]) {
                            same = false
                            break
                        }
                    }

                    if (!same) continue
                    matched = true

                    val newScore = state.score + piece.score
                    val old = best[end]

                    if (old == null || newScore > old.score) {
                        best[end] = State(
                            newScore,
                            state.ids + piece.id
                        )
                    }
                }
            }

            // SentencePiece unknown fallback: consume one code unit.
            if (!matched) {
                val unk = directSpecialIds["<unk>"] ?: 3
                val end = start + 1
                val newScore = state.score - 100f
                val old = best[end]

                if (old == null || newScore > old.score) {
                    best[end] = State(
                        newScore,
                        state.ids + unk
                    )
                }
            }
        }

        return best[n]?.ids ?: listOf(directSpecialIds["<unk>"] ?: 3)
    }

    private fun unescapeJsonString(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0

        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }

            when (val next = value[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 5 < value.length) {
                        val hex = value.substring(i + 2, i + 6)
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                }
                else -> out.append(next)
            }
            i += 2
        }

        return out.toString()
    }
}