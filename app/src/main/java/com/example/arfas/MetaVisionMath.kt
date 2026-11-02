package com.example.arfas

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure maths of the Meta vision stack (no Android, no ONNX types) so it is unit-tested on a PC:
 * pre-processing, DETR post-processing, CLIP zero-shot, DINOv2 memory matching, SAM prompts and
 * mask geometry, and the observation text handed to Llama.
 */
object MetaVisionMath {

    val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    /** SAM normalises 0..255 pixels with these. */
    val SAM_MEAN = floatArrayOf(123.675f / 255f, 116.28f / 255f, 103.53f / 255f)
    val SAM_STD = floatArrayOf(58.395f / 255f, 57.12f / 255f, 57.375f / 255f)

    /** DETR (facebook/detr-resnet-50) COCO id -> label; "N/A" ids are unused. Index 91 = no object. */
    val DETR_LABELS: List<String> = listOf(
        "N/A", "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "N/A", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
        "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "N/A", "backpack", "umbrella", "N/A",
        "N/A", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat",
        "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "N/A", "wine glass", "cup", "fork", "knife",
        "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza",
        "donut", "cake", "chair", "couch", "potted plant", "bed", "N/A", "dining table", "N/A", "N/A",
        "toilet", "N/A", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven",
        "toaster", "sink", "refrigerator", "N/A", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    /**
     * What the camera is pointed at: health-work items plus everyday subjects. CLIP labels are *objects and documents only*:
     * no disease or body-condition labels, because a zero-shot guess must never look like a diagnosis.
     */
    data class SceneLabel(val display: String, val prompt: String)

    val HEALTH_SCENE_LABELS: List<SceneLabel> = listOf(
        SceneLabel("medicine tablet strip", "a photo of a strip of medicine tablets in a blister pack"),
        SceneLabel("medicine bottle", "a photo of a medicine syrup bottle"),
        SceneLabel("ORS packet", "a photo of an oral rehydration salts sachet"),
        SceneLabel("vaccine vial", "a photo of a small glass vaccine vial"),
        SceneLabel("syringe", "a photo of a syringe"),
        SceneLabel("thermometer", "a photo of a digital thermometer"),
        SceneLabel("blood pressure monitor", "a photo of a blood pressure monitor"),
        SceneLabel("weighing scale", "a photo of a weighing scale"),
        SceneLabel("measuring tape", "a photo of a measuring tape"),
        SceneLabel("glucometer", "a photo of a glucometer blood sugar meter"),
        SceneLabel("test kit", "a photo of a rapid diagnostic test cassette"),
        SceneLabel("health card or form", "a photo of a printed health card or form"),
        SceneLabel("register or notebook", "a photo of a handwritten register"),
        SceneLabel("phone screen", "a photo of a mobile phone screen"),
        SceneLabel("newborn baby", "a photo of a newborn baby"),
        SceneLabel("pregnant woman", "a photo of a pregnant woman"),
        SceneLabel("child", "a photo of a young child"),
        SceneLabel("adult person", "a photo of an adult person"),
        SceneLabel("mosquito net", "a photo of a mosquito net"),
        SceneLabel("stored water container", "a photo of a water storage container"),
        SceneLabel("food", "a photo of a plate of food"),
        SceneLabel("hand washing", "a photo of hands being washed with soap"),
        // Everyday subjects, so a photo outside health work (a hand, a machine, a plant) is named
        // instead of being forced onto the nearest health label.
        SceneLabel("human hand", "a close-up photo of a human hand"),
        SceneLabel("human face", "a close-up photo of a person's face"),
        SceneLabel("skin close-up", "a close-up photo of human skin"),
        SceneLabel("plant or leaf", "a photo of a plant or a leaf"),
        SceneLabel("fruit or vegetable", "a photo of fruits or vegetables"),
        SceneLabel("animal", "a photo of an animal"),
        SceneLabel("dog or cat", "a photo of a dog or a cat"),
        SceneLabel("car or motorcycle", "a photo of a car or a motorcycle"),
        SceneLabel("vehicle engine", "a photo of a vehicle engine"),
        SceneLabel("electrical wiring or switchboard", "a photo of electrical wiring or a switch board"),
        SceneLabel("electronic circuit board", "a photo of an electronic circuit board"),
        SceneLabel("home appliance", "a photo of a home appliance such as a fan, fridge or washing machine"),
        SceneLabel("computer or laptop", "a photo of a computer or a laptop"),
        SceneLabel("tool", "a photo of a hand tool"),
        SceneLabel("printed document", "a photo of a printed page or document"),
        SceneLabel("building", "a photo of a building"),
        SceneLabel("outdoor scene", "a photo of an outdoor scene"),
        SceneLabel("clothing", "a photo of clothes"),
        SceneLabel("furniture", "a photo of furniture"),
        SceneLabel("kitchen utensil", "a photo of a kitchen utensil"),
        SceneLabel("room or background", "a photo of an empty room")
    )

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        val area: Float get() = w * h
        fun iou(o: Box): Float {
            val ix = max(0f, min(x + w, o.x + o.w) - max(x, o.x))
            val iy = max(0f, min(y + h, o.y + o.h) - max(y, o.y))
            val inter = ix * iy
            val union = area + o.area - inter
            return if (union <= 0f) 0f else inter / union
        }
    }

    /**
     * Non-maximum suppression: the highest score wins; boxes overlapping a winner
     * by [iou] or more are dropped as the same object seen twice. Returns kept
     * indexes in reading order (top to bottom, left to right) for numbered dots.
     */
    fun nms(boxes: List<Box>, scores: List<Float>, iou: Float = 0.5f): List<Int> {
        require(boxes.size == scores.size) { "boxes (${boxes.size}) != scores (${scores.size})" }
        val dead = BooleanArray(boxes.size)
        val kept = ArrayList<Int>()
        for (i in scores.indices.sortedByDescending { scores[it] }) {
            if (dead[i]) continue
            kept += i
            for (j in boxes.indices) {
                if (j != i && !dead[j] && boxes[i].iou(boxes[j]) >= iou) dead[j] = true
            }
        }
        return kept.sortedWith(compareBy({ boxes[it].y }, { boxes[it].x }))
    }

    data class Detection(val label: String, val score: Float, val box: Box)

    data class Ranked(val label: String, val score: Float)

    // ── pre-processing ─────────────────────────────────────────────────────────────

    /** Size keeping the aspect ratio with the longer side = [longSide]. */
    fun fitLongSide(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val s = longSide.toFloat() / max(w, h)
        return max(1, (w * s + 0.5f).toInt()) to max(1, (h * s + 0.5f).toInt())
    }

    /** Size keeping the aspect ratio with the shorter side = [shortSide]. */
    fun fitShortSide(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val s = shortSide.toFloat() / min(w, h)
        return max(1, (w * s + 0.5f).toInt()) to max(1, (h * s + 0.5f).toInt())
    }

    /**
     * ARGB pixels (row-major, [w]x[h]) -> normalised CHW floats. With [padW]/[padH] larger than the
     * image, the extra area is zero (SAM pads bottom/right after normalisation).
     */
    fun toChw(
        pixels: IntArray, w: Int, h: Int, mean: FloatArray, std: FloatArray,
        padW: Int = w, padH: Int = h,
        /** Value of the padded area per channel (already in output space); default 0. */
        padFill: FloatArray? = null
    ): FloatArray {
        val plane = padW * padH
        val out = FloatArray(3 * plane)
        if (padFill != null && (padW > w || padH > h)) for (c in 0 until 3) java.util.Arrays.fill(out, c * plane, (c + 1) * plane, padFill[c])
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val i = y * padW + x
            out[i] = (r - mean[0]) / std[0]
            out[plane + i] = (g - mean[1]) / std[1]
            out[2 * plane + i] = (b - mean[2]) / std[2]
        }
        return out
    }

    // ── DETR ──────────────────────────────────────────────────────────────────────

    /**
     * [logits] Q x C (C = labels + 1 "no object"), [boxes] Q x 4 (cx, cy, w, h, normalised).
     * Keeps detections above [threshold], best first, overlapping duplicates of the same label removed.
     */
    fun detrPostprocess(logits: FloatArray, boxes: FloatArray, queries: Int, classes: Int, threshold: Float = 0.7f): List<Detection> {
        val out = ArrayList<Detection>()
        for (q in 0 until queries) {
            val row = FloatArray(classes) { logits[q * classes + it] }
            val probs = softmax(row)
            var best = -1
            var bestP = 0f
            for (c in 0 until classes - 1) if (probs[c] > bestP) { bestP = probs[c]; best = c }
            if (best < 0 || bestP < threshold) continue
            val label = DETR_LABELS.getOrNull(best) ?: continue
            if (label == "N/A") continue
            val cx = boxes[q * 4]; val cy = boxes[q * 4 + 1]; val bw = boxes[q * 4 + 2]; val bh = boxes[q * 4 + 3]
            val x0 = (cx - bw / 2f).coerceIn(0f, 1f); val y0 = (cy - bh / 2f).coerceIn(0f, 1f)
            val x1 = (cx + bw / 2f).coerceIn(0f, 1f); val y1 = (cy + bh / 2f).coerceIn(0f, 1f)
            if (x1 - x0 < 0.01f || y1 - y0 < 0.01f) continue
            out += Detection(label, bestP, Box(x0, y0, x1 - x0, y1 - y0))
        }
        val sorted = out.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) if (kept.none { it.label == d.label && it.box.iou(d.box) > 0.6f }) kept += d
        return kept
    }

    // ── CLIP / DINOv2 ─────────────────────────────────────────────────────────────

    fun softmax(v: FloatArray): FloatArray {
        val m = v.maxOrNull() ?: return v
        val e = FloatArray(v.size) { exp((v[it] - m).toDouble()).toFloat() }
        val s = e.sum()
        return FloatArray(v.size) { e[it] / s }
    }

    fun l2normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().takeIf { it > 1e-12f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d < 1e-12) 0f else (dot / d).toFloat()
    }

    /** CLIP zero-shot: softmax over 100 x cosine (CLIP's learned logit scale), best first. */
    fun zeroShot(image: FloatArray, texts: List<FloatArray>, labels: List<String>, logitScale: Float = 100f): List<Ranked> {
        val img = l2normalize(image)
        val logits = FloatArray(texts.size) { logitScale * cosine(img, texts[it]) }
        val p = softmax(logits)
        return labels.indices.map { Ranked(labels[it], p[it]) }.sortedByDescending { it.score }
    }

    data class MemoryItem(val label: String, val embedding: FloatArray)

    data class MemoryMatch(val label: String, val similarity: Float, val margin: Float)

    /**
     * Nearest taught item by cosine; accepted only when similar enough and clearly better than the
     * best *different* label (so two similar packets do not get mixed up).
     */
    fun matchMemory(query: FloatArray, items: List<MemoryItem>, minSimilarity: Float = 0.62f, minMargin: Float = 0.05f): MemoryMatch? {
        if (items.isEmpty()) return null
        val bestPerLabel = items.groupBy { it.label }.mapValues { (_, list) -> list.maxOf { cosine(query, it.embedding) } }
        val ranked = bestPerLabel.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: -1f
        val margin = best.value - second
        return if (best.value >= minSimilarity && margin >= minMargin) MemoryMatch(best.key, best.value, margin) else null
    }

    // ── SAM ───────────────────────────────────────────────────────────────────────

    /** Box (normalised) -> SAM decoder point prompt in the 1024 long-side input space: corners, labels 2 and 3. */
    fun samBoxPrompt(box: Box, origW: Int, origH: Int, longSide: Int = 1024): Pair<FloatArray, FloatArray> {
        val scale = longSide.toFloat() / max(origW, origH)
        val coords = floatArrayOf(
            box.x * origW * scale, box.y * origH * scale,
            (box.x + box.w) * origW * scale, (box.y + box.h) * origH * scale
        )
        return coords to floatArrayOf(2f, 3f)
    }

    /** Tight normalised box around mask values > [threshold]; null when the mask is empty. */
    fun maskBox(mask: FloatArray, mw: Int, mh: Int, threshold: Float = 0f): Box? {
        var minX = mw; var minY = mh; var maxX = -1; var maxY = -1
        for (y in 0 until mh) for (x in 0 until mw) if (mask[y * mw + x] > threshold) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        return Box(minX.toFloat() / mw, minY.toFloat() / mh, (maxX - minX + 1).toFloat() / mw, (maxY - minY + 1).toFloat() / mh)
    }

    /** Share of the box covered by the mask (a mask far smaller than its box is a weak result). */
    fun maskFill(mask: FloatArray, mw: Int, mh: Int, box: Box, threshold: Float = 0f): Float {
        val x0 = (box.x * mw).toInt(); val y0 = (box.y * mh).toInt()
        val x1 = ((box.x + box.w) * mw).toInt().coerceAtMost(mw); val y1 = ((box.y + box.h) * mh).toInt().coerceAtMost(mh)
        var inside = 0; var total = 0
        for (y in y0 until y1) for (x in x0 until x1) { total++; if (mask[y * mw + x] > threshold) inside++ }
        return if (total == 0) 0f else inside.toFloat() / total
    }

    /** Outline of the mask as a convex polygon (normalised x,y pairs) for the AR overlay. */
    fun maskPolygon(mask: FloatArray, mw: Int, mh: Int, threshold: Float = 0f, stride: Int = 4): FloatArray {
        val pts = ArrayList<Pair<Float, Float>>()
        for (y in 0 until mh step stride) {
            var first = -1; var last = -1
            for (x in 0 until mw) if (mask[y * mw + x] > threshold) { if (first < 0) first = x; last = x }
            if (first >= 0) { pts += first.toFloat() / mw to y.toFloat() / mh; pts += last.toFloat() / mw to y.toFloat() / mh }
        }
        val hull = convexHull(pts)
        return FloatArray(hull.size * 2) { if (it % 2 == 0) hull[it / 2].first else hull[it / 2].second }
    }

    /** Andrew's monotone chain. */
    fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val p = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (p.size < 3) return p
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Float, Float>>()
        for (pt in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), pt) <= 0) lower.removeAt(lower.size - 1); lower += pt }
        val upper = ArrayList<Pair<Float, Float>>()
        for (pt in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), pt) <= 0) upper.removeAt(upper.size - 1); upper += pt }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    // ── language helpers ───────────────────────────────────────────────────────────

    private val TARGET_VERBS = Regex(
        """(?i)\b(please|can you|could you|highlight|show me|show|find|locate|point( to| at)?|mark|circle|box|where is|where's|identify|the|a|an|this|that|in the (frame|picture|image|camera)|kahan hai|kahaan hai|kaha hai|dikhao|dikhaiye|batao|kya hai|kidhar)\b"""
    )

    /** "highlight the ORS packet" -> "ors packet"; "thermometer kahan hai" -> "thermometer". */
    fun targetFromQuery(query: String): String =
        TARGET_VERBS.replace(query.lowercase(), " ").replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")

    /** DETR's COCO names for words a health worker uses. */
    val DETR_SYNONYMS: Map<String, List<String>> = mapOf(
        "bottle" to listOf("bottle", "syrup", "medicine bottle", "dawai", "davai", "ors bottle", "water bottle"),
        "person" to listOf("person", "woman", "man", "mother", "patient", "child", "baby", "girl", "boy", "aadmi", "mahila", "bachcha"),
        "cell phone" to listOf("phone", "mobile", "cell phone", "smartphone"),
        "book" to listOf("book", "register", "notebook", "card", "mcp card", "diary"),
        "cup" to listOf("cup", "glass", "tumbler"),
        "scissors" to listOf("scissors", "kainchi"),
        "bowl" to listOf("bowl", "katori"),
        "clock" to listOf("clock", "watch", "ghadi"),
        "chair" to listOf("chair", "kursi"),
        "bed" to listOf("bed", "cot", "charpai")
    )

    fun detrLabelFor(target: String): String? {
        val t = target.lowercase().trim()
        if (t.isBlank()) return null
        DETR_LABELS.firstOrNull { it != "N/A" && (t == it || t.contains(it)) }?.let { return it }
        return DETR_SYNONYMS.entries.firstOrNull { (_, words) -> words.any { w -> t == w || t.contains(w) } }?.key
    }

    /** One-line AR result in the format AndroBaselineRuntime.parseArLocalizationObservation reads. */
    fun arLine(target: String, confidence: Float, box: Box, marker: String = "BOX"): String =
        "AR_LOCALIZATION: FOUND | target=${target.replace('|', ' ')} | confidence=${"%.2f".format(java.util.Locale.US, confidence)} | " +
            "x=${"%.3f".format(java.util.Locale.US, box.x)} | y=${"%.3f".format(java.util.Locale.US, box.y)} | " +
            "w=${"%.3f".format(java.util.Locale.US, box.w)} | h=${"%.3f".format(java.util.Locale.US, box.h)} | marker=$marker"

    fun arNotFound(target: String): String = "AR_LOCALIZATION: NOT_FOUND | target=${target.replace('|', ' ')}"

    /** Everything the Meta vision models established about one frame. */
    data class Observation(
        val detections: List<Detection> = emptyList(),
        val scene: List<Ranked> = emptyList(),
        val text: String = "",
        val barcodes: List<String> = emptyList(),
        val memory: MemoryMatch? = null,
        val modelsUsed: List<String> = emptyList(),
        val modelsMissing: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = detections.isEmpty() && scene.isEmpty() && text.isBlank() && barcodes.isEmpty() && memory == null

        /**
         * The VISIBLE_FACTS contract the runtime already uses for camera turns. Only what a model
         * measured is stated; confidence is given; nothing is diagnosed.
         */
        fun render(): String = buildString {
            val facts = ArrayList<String>()
            memory?.let { facts += "matches the taught item \"${it.label}\" (DINOv2 similarity ${"%.2f".format(java.util.Locale.US, it.similarity)})" }
            if (detections.isNotEmpty()) {
                facts += "objects detected (DETR): " + detections.take(8).joinToString(", ") { d ->
                    "${d.label} ${(d.score * 100).toInt()}% at ${position(d.box)}"
                }
            }
            if (text.isNotBlank()) facts += "printed text read (OCR): \"${text.replace('\n', ' ').trim().take(600)}\""
            if (barcodes.isNotEmpty()) facts += "barcode: " + barcodes.take(3).joinToString("; ")
            append("VISIBLE_FACTS: ").append(if (facts.isEmpty()) "nothing was recognised with confidence in this frame." else facts.joinToString("; ")).append('\n')
            val inferences = ArrayList<String>()
            val top = scene.firstOrNull()
            if (top != null && top.score >= 0.35f && top.label != "room or background") {
                val alt = scene.drop(1).take(2).filter { it.score >= 0.10f }
                inferences += "the picture most likely shows a ${top.label} (CLIP ${(top.score * 100).toInt()}%" +
                    (if (alt.isNotEmpty()) "; other possibilities: " + alt.joinToString(", ") { "${it.label} ${(it.score * 100).toInt()}%" } else "") + ")"
            }
            append("SUPPORTED_INFERENCES: ").append(if (inferences.isEmpty()) "none" else inferences.joinToString("; ")).append('\n')
            append("VISIBLE_STATUS_TEXT: ").append(text.replace('\n', ' ').trim().take(300).ifBlank { "none" }).append('\n')
            val unknown = ArrayList<String>()
            // (No fixed "medical condition cannot be judged" line: it made every photo answer, a hand
            // or a machine, talk about diagnosis. The answer prompts already forbid diagnosing from a picture.)
            if (text.isBlank()) unknown += "no readable text"
            if (modelsMissing.isNotEmpty()) unknown += "not checked (model not installed): " + modelsMissing.joinToString(", ")
            append("UNKNOWN: ").append(unknown.joinToString("; ")).append('\n')
            append("PERCEPTION: on-device Meta models — ").append(modelsUsed.joinToString(", ").ifBlank { "OCR only" })
        }

        private fun position(b: Box): String {
            val cx = b.x + b.w / 2; val cy = b.y + b.h / 2
            val col = when { cx < 0.33f -> "left"; cx > 0.67f -> "right"; else -> "centre" }
            val row = when { cy < 0.33f -> "top"; cy > 0.67f -> "bottom"; else -> "middle" }
            return if (row == "middle" && col == "centre") "the centre" else "$row $col"
        }
    }

    /** "remember this as ORS packet" / "ise ORS packet naam se yaad rakho" -> "ORS packet". */
    fun parseTeachCommand(query: String): String? {
        val q = query.trim()
        val patterns = listOf(
            Regex("""(?i)^\s*(?:please\s+)?(?:remember|save|learn|teach)\s+(?:this|it|that)\s+(?:as|is)\s+(?:an?\s+|the\s+)?(.{2,60}?)\s*[.!]?\s*$"""),
            Regex("""(?i)^\s*(?:this|it)\s+is\s+(?:an?\s+|the\s+|my\s+)?(.{2,60}?)[,.]?\s+(?:remember|save|learn)\s+(?:it|this)\s*[.!]?\s*$"""),
            Regex("""(?i)^\s*(?:ise|isko|ye|yeh)\s+(.{2,60}?)\s+(?:naam\s+se\s+|ke\s+roop\s+me\s+|ke\s+naam\s+se\s+)?yaad\s+(?:rakho|rakhna|rakhiye|karo)\s*[.!]?\s*$"""),
            Regex("""^\s*(?:इसे|इसको|ये|यह)\s+(.{2,60}?)\s+(?:नाम\s+से\s+|के\s+रूप\s+में\s+)?याद\s+(?:रखो|रखना|रखिए|करो)\s*[।.!]?\s*$""")
        )
        for (p in patterns) p.find(q)?.let { m -> return m.groupValues[1].trim().trim('"', '\'', '“', '”').takeIf { it.isNotBlank() } }
        return null
    }

    /** "forget ORS packet" / "ORS packet bhool jao". */
    fun parseForgetCommand(query: String): String? {
        val q = query.trim()
        Regex("""(?i)^\s*(?:please\s+)?forget\s+(?:the\s+)?(.{2,60}?)\s*[.!]?\s*$""").find(q)?.let { return it.groupValues[1].trim() }
        Regex("""(?i)^\s*(.{2,60}?)\s+(?:ko\s+)?bhool\s+(?:jao|jaao|jaiye)\s*[.!]?\s*$""").find(q)?.let { return it.groupValues[1].trim() }
        return null
    }
}
