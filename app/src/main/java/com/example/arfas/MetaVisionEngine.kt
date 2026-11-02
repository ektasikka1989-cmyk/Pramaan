package com.example.arfas

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Meta vision stack on the phone CPU (ONNX Runtime), replacing the old placeholders:
 *  - DETR ResNet-50: objects and their boxes
 *  - CLIP ViT-B/32: what the picture shows (zero-shot over health-worker items) and
 *    "find the <thing>" localisation over candidate regions
 *  - DINOv2: visual memory — the health worker teaches an item once ("remember this as
 *    ORS packet") and it is recognised later
 *  - SAM / MobileSAM: refines a box into the object's outline for the AR highlight
 *  - ML Kit: printed text (Latin + Devanagari) and barcodes
 * Every model is optional: a missing one is reported as "not checked", never faked.
 * Llama 3.2 then reasons over the rendered observation (it cannot see pixels).
 */
class MetaVisionEngine(
    private val context: Context,
    private val models: ModelFileManager,
    private val mlKit: MLKitProcessor
) {
    companion object {
        private const val TAG = "MetaVision"
        private const val DETR_LONG_SIDE = 640
        private const val CLIP_SIZE = 224
        private const val DINO_SIZE = 224
        private const val SAM_SIZE = 1024
        /** DINOv2 cosine at or above which a proposal counts as the taught item (same as localize). */
        private const val COUNT_SIM = 0.55f
        private const val CLIP_BPE_ASSET = "clip/bpe_simple_vocab_16e6.txt.gz"
        private const val LABEL_CACHE_VERSION = 1
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val mutex = Mutex()
    private val sessions = HashMap<MetaModelStore.Component, OrtSession>()
    private val failed = HashSet<MetaModelStore.Component>()
    @Volatile private var labelEmbeddings: List<FloatArray>? = null
    private val tokenizer: ClipTokenizer? by lazy {
        runCatching { context.assets.open(CLIP_BPE_ASSET).use { ClipTokenizer(it) } }
            .onFailure { Log.w(TAG, "CLIP_TOKENIZER_MISSING asset=$CLIP_BPE_ASSET ${it.message}") }
            .getOrNull()
    }
    private val memoryFile: File get() = File(models.metaStore.metaDir, "visual_memory.json")
    private var memory: MutableList<MetaVisionMath.MemoryItem>? = null

    /** OCR always works, so the camera always has some on-device eyes in Meta Stack mode. */
    val isAvailable: Boolean get() = true

    fun has(c: MetaModelStore.Component): Boolean = models.metaFile(c) != null && c !in failed

    /** "DETR ✓ • CLIP ✓ • DINOv2 – • SAM ✓ • OCR ✓" for Settings / diagnostics. */
    fun capabilitySummary(): String {
        fun mark(b: Boolean) = if (b) "✓" else "–"
        val clip = has(MetaModelStore.Component.CLIP_VISION) && has(MetaModelStore.Component.CLIP_TEXT)
        val sam = has(MetaModelStore.Component.SAM_ENCODER) && has(MetaModelStore.Component.SAM_DECODER)
        return "DETR ${mark(has(MetaModelStore.Component.DETR))} • CLIP ${mark(clip)} • DINOv2 ${mark(has(MetaModelStore.Component.DINOV2))} • SAM ${mark(sam)} • OCR ✓"
    }

    /** Closes all sessions (after a model install or under memory pressure). */
    fun reset() {
        synchronized(sessions) {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
            failed.clear()
        }
        labelEmbeddings = null
        clipSlotsSwapped = null
    }

    // ── public operations ─────────────────────────────────────────────────────────

    suspend fun observe(query: String, bitmap: Bitmap): MetaVisionMath.Observation = mutex.withLock {
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val used = ArrayList<String>()
            val missing = ArrayList<String>()
            val detections = if (has(MetaModelStore.Component.DETR)) {
                runCatching { detect(bitmap) }.onFailure { Log.w(TAG, "DETR_FAILED ${it.message}") }.getOrNull()
                    ?.also { used += "DETR" }.orEmpty()
            } else { missing += "DETR object detection"; emptyList() }
            releaseIfLowMemory()  // one vision model in memory at a time on phones under 8 GB
            val scene = if (has(MetaModelStore.Component.CLIP_VISION) && has(MetaModelStore.Component.CLIP_TEXT)) {
                runCatching { classifyScene(bitmap) }.onFailure { Log.w(TAG, "CLIP_FAILED ${it.message}") }.getOrNull()
                    ?.also { used += "CLIP" }.orEmpty()
            } else { missing += "CLIP scene labels"; emptyList() }
            releaseIfLowMemory()
            val taught = loadMemory()
            val memoryMatch = if (taught.isNotEmpty() && has(MetaModelStore.Component.DINOV2)) {
                runCatching { embedDino(bitmap) }.getOrNull()?.let { used += "DINOv2"; MetaVisionMath.matchMemory(it, taught) }
            } else null
            val (text, barcodes) = runCatching { mlKit.readTextAndBarcodes(bitmap) }.getOrDefault("" to emptyList())
            used += "ML Kit OCR"
            releaseIfLowMemory()
            val observation = MetaVisionMath.Observation(detections, scene, text, barcodes, memoryMatch, used, missing)
            Log.i(TAG, "META_OBSERVE ms=${System.currentTimeMillis() - started} det=${detections.size} top=${scene.firstOrNull()} text=${text.length} memory=${memoryMatch?.label}")
            runCatching { Diag.log("Vision", "${used.joinToString("+")} in ${System.currentTimeMillis() - started} ms: ${detections.size} objects, ${scene.firstOrNull()?.label ?: "-"}") }
            observation
        }
    }

    /**
     * "highlight the ORS packet": candidates are DETR boxes plus a grid of regions; the target is
     * matched by DETR label, taught item (DINOv2) or CLIP text-image similarity; SAM tightens the
     * winning box. Returns the AR_LOCALIZATION line, NOT_FOUND when nothing matches well.
     */
    suspend fun localize(query: String, bitmap: Bitmap): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val target = MetaVisionMath.targetFromQuery(query).ifBlank { query.trim() }
            val started = System.currentTimeMillis()
            val detections = if (has(MetaModelStore.Component.DETR)) runCatching { detect(bitmap) }.getOrDefault(emptyList()) else emptyList()
            var best: Pair<MetaVisionMath.Box, Float>? = null

            // 1) DETR knows this kind of object
            MetaVisionMath.detrLabelFor(target)?.let { coco ->
                detections.filter { it.label == coco }.maxByOrNull { it.score }?.let { best = it.box to it.score }
            }
            val candidates = (detections.map { it.box } + gridBoxes()).distinct()
            // 2) a taught item
            if (best == null && has(MetaModelStore.Component.DINOV2)) {
                val taught = loadMemory().filter { it.label.equals(target, true) || target.contains(it.label, true) }
                if (taught.isNotEmpty()) {
                    val scored = candidates.mapNotNull { box ->
                        val emb = runCatching { embedDino(crop(bitmap, box)) }.getOrNull() ?: return@mapNotNull null
                        box to taught.maxOf { MetaVisionMath.cosine(emb, it.embedding) }
                    }
                    scored.maxByOrNull { it.second }?.takeIf { it.second >= 0.55f }?.let { best = it.first to (0.6f + (it.second - 0.55f)).coerceIn(0.62f, 0.95f) }
                }
            }
            // 3) CLIP: which region looks most like "a photo of <target>"
            if (best == null && has(MetaModelStore.Component.CLIP_VISION) && has(MetaModelStore.Component.CLIP_TEXT)) {
                val textEmb = runCatching { embedText("a photo of a $target") }.getOrNull()
                if (textEmb != null) {
                    val scored = candidates.mapNotNull { box ->
                        val emb = runCatching { embedImage(crop(bitmap, box)) }.getOrNull() ?: return@mapNotNull null
                        box to MetaVisionMath.cosine(emb, textEmb)
                    }
                    val mean = scored.map { it.second }.average().toFloat()
                    scored.maxByOrNull { it.second }?.let { (box, cos) ->
                        // CLIP cosines are small (~0.2-0.35); require a clear winner over the other regions.
                        if (cos >= 0.24f && cos - mean >= 0.02f) best = box to (0.5f + (cos - 0.2f) * 4f).coerceIn(0f, 0.92f)
                    }
                }
            }
            val found = best
            val line = if (found == null) MetaVisionMath.arNotFound(target) else {
                val refined = if (has(MetaModelStore.Component.SAM_ENCODER) && has(MetaModelStore.Component.SAM_DECODER)) {
                    runCatching { refineWithSam(bitmap, found.first) }.onFailure { Log.w(TAG, "SAM_FAILED ${it.message}") }.getOrNull()
                } else null
                MetaVisionMath.arLine(target, found.second, refined ?: found.first)
            }
            releaseIfLowMemory()
            Log.i(TAG, "META_LOCALIZE target='$target' ms=${System.currentTimeMillis() - started} result=${line.take(120)}")
            line
        }
    }

    /** "remember this as ORS packet": stores the DINOv2 embedding of the frame under the label. */
    suspend fun remember(label: String, bitmap: Bitmap): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!has(MetaModelStore.Component.DINOV2)) return@withContext false
            val emb = runCatching { embedDino(bitmap) }.getOrNull() ?: return@withContext false
            val list = loadMemory()
            list += MetaVisionMath.MemoryItem(label.trim(), emb)
            saveMemory(list)
            releaseIfLowMemory()
            true
        }
    }

    fun forget(label: String): Boolean {
        val list = loadMemory()
        val removed = list.removeAll { it.label.equals(label.trim(), ignoreCase = true) }
        if (removed) saveMemory(list)
        return removed
    }

    fun rememberedLabels(): List<String> = loadMemory().map { it.label }.distinct()

    /** "remember this box as ORS packet": stores the DINOv2 embedding of the cropped area under the label. */
    suspend fun rememberBox(label: String, bitmap: Bitmap, box: MetaVisionMath.Box): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!has(MetaModelStore.Component.DINOV2)) return@withContext false
            val emb = runCatching { embedDino(crop(bitmap, box)) }.getOrNull() ?: return@withContext false
            val list = loadMemory()
            list += MetaVisionMath.MemoryItem(label.trim(), emb)
            saveMemory(list)
            releaseIfLowMemory()
            true
        }
    }

    /**
     * Offline item counter: MobileSAM tightens each grid proposal (the encoder runs
     * once per photo), DINOv2 keeps the proposals that look like the taught [label],
     * and duplicates are removed. Returns the kept boxes in reading order; empty
     * when nothing is taught for the label or DINOv2 is missing.
     */
    suspend fun countTaught(label: String, bitmap: Bitmap, onProgress: (Int, Int) -> Unit = { _, _ -> }): CountResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            try {
                val taught = loadMemory().filter { it.label.equals(label.trim(), true) || label.contains(it.label, true) }
                if (taught.isEmpty() || !has(MetaModelStore.Component.DINOV2)) return@withContext CountResult(emptyList(), false, 0)
                val grid = fineGrid()
                val sam = if (has(MetaModelStore.Component.SAM_ENCODER) && has(MetaModelStore.Component.SAM_DECODER))
                    runCatching { encodeSam(bitmap) }.getOrNull() else null
                val scored = ArrayList<Pair<MetaVisionMath.Box, Float>>()
                grid.forEachIndexed { i, proposal ->
                    onProgress(i + 1, grid.size)
                    val tight = sam?.let { runCatching { decodeSamBox(it, proposal) }.getOrNull() } ?: proposal
                    val emb = runCatching { embedDino(crop(bitmap, tight)) }.getOrNull() ?: return@forEachIndexed
                    val sim = taught.maxOf { MetaVisionMath.cosine(emb, it.embedding) }
                    if (sim >= COUNT_SIM) scored += tight to sim
                }
                val keep = MetaVisionMath.nms(scored.map { it.first }, scored.map { it.second })
                val boxes = keep.map { CountedBox(scored[it].first, scored[it].second) }
                Log.i(TAG, "META_COUNT label='$label' proposals=${grid.size} kept=${boxes.size} sam=${sam != null} ms=${System.currentTimeMillis() - started}")
                CountResult(boxes, sam != null, grid.size)
            } finally {
                releaseIfLowMemory()
            }
        }
    }

    // ── models ────────────────────────────────────────────────────────────────────

    private fun session(c: MetaModelStore.Component): OrtSession? = synchronized(sessions) {
        sessions[c]?.let { return it }
        if (c in failed) return null
        val file = models.metaFile(c) ?: return null
        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(file.absolutePath, opts).also {
                Log.i(TAG, "SESSION_OPEN $c file=${file.name} inputs=${it.inputNames} outputs=${it.outputNames}")
            }
        }.onFailure {
            failed += c
            Log.w(TAG, "SESSION_FAILED $c ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()?.also { sessions[c] = it }
    }

    /**
     * The two CLIP files may have been installed in either slot (file names vary): the one whose
     * input is input_ids is the text encoder. Decided once, from the first file opened, so only the
     * encoder a call needs is loaded.
     */
    @Volatile private var clipSlotsSwapped: Boolean? = null

    private fun clipSlotsSwapped(): Boolean? {
        clipSlotsSwapped?.let { return it }
        val a = session(MetaModelStore.Component.CLIP_VISION) ?: return null
        return a.inputNames.any { it.contains("input_ids") }.also { clipSlotsSwapped = it }
    }

    private fun clipVision(): OrtSession? {
        val swapped = clipSlotsSwapped() ?: return null
        return session(if (swapped) MetaModelStore.Component.CLIP_TEXT else MetaModelStore.Component.CLIP_VISION)
    }

    private fun clipText(): OrtSession? {
        val swapped = clipSlotsSwapped() ?: return null
        return session(if (swapped) MetaModelStore.Component.CLIP_VISION else MetaModelStore.Component.CLIP_TEXT)
    }

    private fun releaseIfLowMemory() {
        val totalMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / 1_048_576
        }.getOrDefault(4_096L)
        // Llama 3.2 3B takes ~2.5 GB; on phones under 8 GB the vision sessions are not kept.
        if (totalMb < 7_500) synchronized(sessions) {
            sessions.values.forEach { runCatching { it.close() } }
            sessions.clear()
        }
    }

    private fun pixels(bitmap: Bitmap, w: Int, h: Int): IntArray {
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val out = IntArray(w * h)
        scaled.getPixels(out, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    /** Resize the short side to [size] and take the centre square (CLIP / DINOv2 eval transform). */
    private fun centerSquare(bitmap: Bitmap, size: Int, resizeShort: Int = size): IntArray {
        val (rw, rh) = MetaVisionMath.fitShortSide(bitmap.width, bitmap.height, resizeShort)
        val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val x0 = (rw - size) / 2
        val y0 = (rh - size) / 2
        val out = IntArray(size * size)
        scaled.getPixels(out, 0, size, x0.coerceAtLeast(0), y0.coerceAtLeast(0), size, size)
        if (scaled !== bitmap) scaled.recycle()
        return out
    }

    private fun crop(bitmap: Bitmap, box: MetaVisionMath.Box): Bitmap {
        val x = (box.x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (box.y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = (box.w * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val h = (box.h * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    private fun gridBoxes(): List<MetaVisionMath.Box> {
        val out = ArrayList<MetaVisionMath.Box>()
        for (r in 0 until 3) for (c in 0 until 3) out += MetaVisionMath.Box(c * 0.3f, r * 0.3f, 0.4f, 0.4f)
        out += MetaVisionMath.Box(0.2f, 0.2f, 0.6f, 0.6f)
        return out
    }

    /** Finer overlapping grid for counting small laid-out items (5 x 5 + center). */
    private fun fineGrid(): List<MetaVisionMath.Box> {
        val out = ArrayList<MetaVisionMath.Box>()
        for (r in 0 until 5) for (c in 0 until 5) {
            val x = (c * 0.2f - 0.04f).coerceIn(0f, 0.72f)
            val y = (r * 0.2f - 0.04f).coerceIn(0f, 0.72f)
            out += MetaVisionMath.Box(x, y, 0.28f, 0.28f)
        }
        out += MetaVisionMath.Box(0.2f, 0.2f, 0.6f, 0.6f)
        return out
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun outputFloats(result: OrtSession.Result, index: Int): Pair<FloatArray, LongArray> {
        val t = result.get(index) as OnnxTensor
        val buf = t.floatBuffer
        val arr = FloatArray(buf.remaining()).also { buf.get(it) }
        return arr to t.info.shape
    }

    private fun outputIndex(session: OrtSession, vararg names: String): Int? {
        val list = session.outputNames.toList()
        for (n in names) list.indexOf(n).takeIf { it >= 0 }?.let { return it }
        return null
    }

    private fun detect(bitmap: Bitmap): List<MetaVisionMath.Detection> {
        val s = session(MetaModelStore.Component.DETR) ?: return emptyList()
        val (w, h) = MetaVisionMath.fitLongSide(bitmap.width, bitmap.height, DETR_LONG_SIDE)
        val chw = MetaVisionMath.toChw(pixels(bitmap, w, h), w, h, MetaVisionMath.IMAGENET_MEAN, MetaVisionMath.IMAGENET_STD)
        val inputs = HashMap<String, OnnxTensor>()
        try {
            val pixelName = s.inputNames.firstOrNull { it.contains("pixel_values") } ?: s.inputNames.first()
            inputs[pixelName] = floatTensor(chw, longArrayOf(1, 3, h.toLong(), w.toLong()))
            s.inputNames.firstOrNull { it.contains("pixel_mask") }?.let { name ->
                inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(w * h) { 1L }), longArrayOf(1, h.toLong(), w.toLong()))
            }
            s.run(inputs).use { r ->
                val li = outputIndex(s, "logits") ?: 0
                val bi = outputIndex(s, "pred_boxes") ?: 1
                val (logits, lShape) = outputFloats(r, li)
                val (boxes, _) = outputFloats(r, bi)
                val queries = lShape[1].toInt()
                val classes = lShape[2].toInt()
                return MetaVisionMath.detrPostprocess(logits, boxes, queries, classes, threshold = 0.7f)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun embedImage(bitmap: Bitmap): FloatArray? {
        val vision = clipVision() ?: return null
        val chw = MetaVisionMath.toChw(centerSquare(bitmap, CLIP_SIZE), CLIP_SIZE, CLIP_SIZE, MetaVisionMath.CLIP_MEAN, MetaVisionMath.CLIP_STD)
        floatTensor(chw, longArrayOf(1, 3, CLIP_SIZE.toLong(), CLIP_SIZE.toLong())).use { t ->
            vision.run(mapOf(vision.inputNames.first() to t)).use { r ->
                val idx = outputIndex(vision, "image_embeds", "image_embeddings", "embeddings") ?: 0
                val (v, shape) = outputFloats(r, idx)
                return if (shape.size == 2) v else null
            }
        }
    }

    private fun embedText(text: String): FloatArray? {
        val textSession = clipText() ?: return null
        val tok = tokenizer ?: return null
        val ids = tok.encodeForModel(text)
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs[textSession.inputNames.firstOrNull { it.contains("input_ids") } ?: textSession.inputNames.first()] =
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            textSession.inputNames.firstOrNull { it.contains("attention_mask") }?.let {
                inputs[it] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            }
            textSession.run(inputs).use { r ->
                val idx = outputIndex(textSession, "text_embeds", "text_embeddings", "embeddings") ?: 0
                val (v, s) = outputFloats(r, idx)
                return if (s.size == 2) v else null
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun classifyScene(bitmap: Bitmap): List<MetaVisionMath.Ranked> {
        val labels = MetaVisionMath.HEALTH_SCENE_LABELS
        val texts = labelEmbeddings ?: loadOrComputeLabelEmbeddings()?.also { labelEmbeddings = it } ?: return emptyList()
        val image = embedImage(bitmap) ?: return emptyList()
        if (texts.any { it.size != image.size }) {
            Log.w(TAG, "CLIP_DIM_MISMATCH image=${image.size} text=${texts.first().size} (export with projection: image_embeds/text_embeds)")
            return emptyList()
        }
        return MetaVisionMath.zeroShot(image, texts, labels.map { it.display })
    }

    /** Label text embeddings are computed once and cached on disk (then CLIP-text is not needed again). */
    private fun loadOrComputeLabelEmbeddings(): List<FloatArray>? {
        val labels = MetaVisionMath.HEALTH_SCENE_LABELS
        val textFile = models.metaFile(MetaModelStore.Component.CLIP_TEXT)
        val key = "$LABEL_CACHE_VERSION:${labels.joinToString("|") { it.prompt }.hashCode()}:${textFile?.length()}"
        val cache = File(models.metaStore.metaDir, "clip_label_embeddings.bin")
        runCatching {
            if (cache.exists()) {
                val bytes = cache.readBytes()
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val keyLen = bb.int
                val storedKey = String(bytes, 4, keyLen, Charsets.UTF_8)
                bb.position(4 + keyLen)
                if (storedKey == key) {
                    val n = bb.int; val d = bb.int
                    return List(n) { FloatArray(d) { bb.float } }
                }
            }
        }
        val embeddings = labels.map { embedText(it.prompt) ?: return null }.map(MetaVisionMath::l2normalize)
        runCatching {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val d = embeddings.first().size
            val bb = ByteBuffer.allocate(4 + keyBytes.size + 8 + embeddings.size * d * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(keyBytes.size); bb.put(keyBytes); bb.putInt(embeddings.size); bb.putInt(d)
            embeddings.forEach { e -> e.forEach(bb::putFloat) }
            cache.writeBytes(bb.array())
        }
        return embeddings
    }

    private fun embedDino(bitmap: Bitmap): FloatArray? {
        val s = session(MetaModelStore.Component.DINOV2) ?: return null
        val chw = MetaVisionMath.toChw(centerSquare(bitmap, DINO_SIZE, 256), DINO_SIZE, DINO_SIZE, MetaVisionMath.IMAGENET_MEAN, MetaVisionMath.IMAGENET_STD)
        floatTensor(chw, longArrayOf(1, 3, DINO_SIZE.toLong(), DINO_SIZE.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val idx = outputIndex(s, "embedding", "pooler_output", "last_hidden_state") ?: 0
                val (v, shape) = outputFloats(r, idx)
                return when (shape.size) {
                    2 -> MetaVisionMath.l2normalize(v)
                    3 -> MetaVisionMath.l2normalize(v.copyOfRange(0, shape[2].toInt()))  // CLS token
                    else -> null
                }
            }
        }
    }

    /**
     * SAM with a box prompt. Two decoder contracts are supported:
     *  - Pramaan export (tools/export_meta_models.py, SAM 2.1 or MobileSAM): encoder input "image"
     *    [1,3,1024,1024] RGB 0..1 (the model normalises), resize mode in model metadata
     *    "pramaan_resize" = "stretch" (SAM 2) or "pad" (SAM 1 / MobileSAM); decoder inputs = the
     *    encoder outputs (by name) + "box" [1,4] in 1024 space; output "masks" low-res logits.
     *  - official segment-anything decoder export (point_coords/point_labels/mask_input/...).
     */
    /** SAM image encoding, run once per photo; the cheap decoder then answers each box prompt. */
    private data class SamEncoding(
        val embeddings: Map<String, Pair<FloatArray, LongArray>>,
        val w: Int, val h: Int, val pramaan: Boolean, val stretch: Boolean
    )

    private fun encodeSam(bitmap: Bitmap): SamEncoding? {
        val enc = session(MetaModelStore.Component.SAM_ENCODER) ?: return null
        val dec = session(MetaModelStore.Component.SAM_DECODER) ?: return null
        val pramaan = "box" in dec.inputNames
        val stretch = pramaan && runCatching { enc.metadata.customMetadata["pramaan_resize"] }.getOrNull() == "stretch"
        val (w, h) = if (stretch) SAM_SIZE to SAM_SIZE else MetaVisionMath.fitLongSide(bitmap.width, bitmap.height, SAM_SIZE)
        val mean = if (pramaan) floatArrayOf(0f, 0f, 0f) else MetaVisionMath.SAM_MEAN
        val std = if (pramaan) floatArrayOf(1f, 1f, 1f) else MetaVisionMath.SAM_STD
        // Pramaan "pad" encoders normalise inside the model: pad with SAM's mean colour so the
        // padded area becomes 0 after normalisation, exactly like segment-anything.
        val padFill = if (pramaan && !stretch) MetaVisionMath.SAM_MEAN else null
        val chw = MetaVisionMath.toChw(pixels(bitmap, w, h), w, h, mean, std, SAM_SIZE, SAM_SIZE, padFill)
        val encoded = LinkedHashMap<String, Pair<FloatArray, LongArray>>()
        floatTensor(chw, longArrayOf(1, 3, SAM_SIZE.toLong(), SAM_SIZE.toLong())).use { t ->
            enc.run(mapOf(enc.inputNames.first() to t)).use { r ->
                enc.outputNames.forEachIndexed { i, name -> encoded[name] = outputFloats(r, i) }
            }
        }
        return SamEncoding(encoded, w, h, pramaan, stretch)
    }

    private fun decodeSamBox(enc: SamEncoding, box: MetaVisionMath.Box): MetaVisionMath.Box? {
        val dec = session(MetaModelStore.Component.SAM_DECODER) ?: return null
        val inputs = HashMap<String, OnnxTensor>()
        try {
            enc.embeddings.forEach { (name, v) -> if (name in dec.inputNames) inputs[name] = floatTensor(v.first, v.second) }
            if (enc.pramaan) {
                // Box corners in the encoder's 1024 input space.
                val sx = enc.w.toFloat(); val sy = enc.h.toFloat()
                inputs["box"] = floatTensor(floatArrayOf(box.x * sx, box.y * sy, (box.x + box.w) * sx, (box.y + box.h) * sy), longArrayOf(1, 4))
            } else {
                val (coords, labels) = MetaVisionMath.samBoxPrompt(box, enc.w, enc.h, SAM_SIZE)
                if ("image_embeddings" !in inputs) enc.embeddings.values.firstOrNull()?.let { inputs["image_embeddings"] = floatTensor(it.first, it.second) }
                inputs["point_coords"] = floatTensor(coords, longArrayOf(1, 2, 2))
                inputs["point_labels"] = floatTensor(labels, longArrayOf(1, 2))
                inputs["mask_input"] = floatTensor(FloatArray(256 * 256), longArrayOf(1, 1, 256, 256))
                inputs["has_mask_input"] = floatTensor(floatArrayOf(0f), longArrayOf(1))
                inputs["orig_im_size"] = floatTensor(floatArrayOf(enc.h.toFloat(), enc.w.toFloat()), longArrayOf(2))
            }
            val wanted = inputs.filterKeys { it in dec.inputNames }
            if (wanted.size != dec.inputNames.size) {
                Log.w(TAG, "SAM_DECODER_INPUTS_UNEXPECTED need=${dec.inputNames} have=${wanted.keys}")
                return null
            }
            dec.run(wanted).use { r ->
                val mi = outputIndex(dec, "masks", "low_res_masks") ?: 0
                val (mask, shape) = outputFloats(r, mi)
                val mh = shape[shape.size - 2].toInt()
                val mw = shape[shape.size - 1].toInt()
                val plane = FloatArray(mw * mh).also { System.arraycopy(mask, 0, it, 0, it.size) }
                // Pramaan low-res masks cover the 1024 square; with "pad" only the top-left w x h is image.
                val (vw, vh) = if (enc.pramaan && !enc.stretch) (mw * enc.w / SAM_SIZE).coerceAtLeast(1) to (mh * enc.h / SAM_SIZE).coerceAtLeast(1) else mw to mh
                val valid = if (vw == mw && vh == mh) plane else FloatArray(vw * vh) { i -> plane[(i / vw) * mw + (i % vw)] }
                val tight = MetaVisionMath.maskBox(valid, vw, vh) ?: return null
                // A mask that fills little of its own box, or is far from the prompt, is not trusted.
                if (MetaVisionMath.maskFill(valid, vw, vh, tight) < 0.25f || tight.iou(box) < 0.2f) return null
                return tight
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun refineWithSam(bitmap: Bitmap, box: MetaVisionMath.Box): MetaVisionMath.Box? =
        encodeSam(bitmap)?.let { decodeSamBox(it, box) }

    // ── visual memory ─────────────────────────────────────────────────────────────

    @Synchronized
    private fun loadMemory(): MutableList<MetaVisionMath.MemoryItem> {
        memory?.let { return it }
        val list = ArrayList<MetaVisionMath.MemoryItem>()
        runCatching {
            if (memoryFile.exists()) {
                val arr = JSONArray(memoryFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val e = o.getJSONArray("e")
                    list += MetaVisionMath.MemoryItem(o.getString("label"), FloatArray(e.length()) { e.getDouble(it).toFloat() })
                }
            }
        }.onFailure { Log.w(TAG, "VISUAL_MEMORY_READ_FAILED ${it.message}") }
        memory = list
        return list
    }

    @Synchronized
    private fun saveMemory(list: MutableList<MetaVisionMath.MemoryItem>) {
        memory = list
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().put("label", item.label).put("e", JSONArray().also { a -> item.embedding.forEach { a.put(it.toDouble()) } }))
        }
        val tmp = File(memoryFile.absolutePath + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(memoryFile)
    }
}

/** One offline-counted item: tight box + DINOv2 similarity to the taught reference. */
data class CountedBox(val box: MetaVisionMath.Box, val score: Float)

/** Offline count result: kept boxes in reading order, whether SAM ran, proposal count. */
data class CountResult(val boxes: List<CountedBox>, val usedSam: Boolean, val proposals: Int)
