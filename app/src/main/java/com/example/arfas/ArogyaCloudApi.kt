package com.example.arfas


/*
 * Meta cloud models, used only when there is signal and a key is saved. Everything has an offline fallback on the phone.
 * Endpoints and model ids are from Meta's Model API docs (dev.meta.ai/docs/models, /speech-to-text, /media-segmentation,
 * /muse-glimmer/deploy), read 31 Oct 2026. Not yet called live from this app (no key yet).
 *  - Muse Spark 1.3     → POST https://api.meta.ai/v1/chat/completions      (OpenAiCompatibleClient)
 *  - Muse Voice Transcribe and SAM 3.1 → Pramaan's existing MetaModelApi (via MetaOnline), which masks names and logs egress.
 *  - Muse Glimmer 30B   → POST https://api.together.xyz/v1/chat/completions model "meta-models/Muse-Glimmer-30B" (Together AI key)
 */
object MetaCloud {
    const val BASE = "https://api.meta.ai/v1"
    const val SPARK = "muse-spark-1.3"
    const val TOGETHER_BASE = "https://api.together.xyz/v1"
    const val GLIMMER = "meta-models/Muse-Glimmer-30B"

}

/** Home mode-switch status line: text + ok (true = green, false = amber). Pure, host-tested. */
object ArogyaModeStatus {
    fun line(cloudFirst: Boolean, online: Boolean, hasKey: Boolean, hindi: Boolean): Pair<String, Boolean> {
        fun tr(en: String, hi: String) = if (hindi) hi else en
        return when {
            !cloudFirst -> tr("Offline · everything runs on this phone", "ऑफ़लाइन · सब कुछ इसी फ़ोन पर चलता है") to true
            !online -> tr("Online selected · no internet — using phone", "ऑनलाइन चुना · इंटरनेट नहीं — फ़ोन इस्तेमाल होगा") to false
            !hasKey -> tr("Online needs a Meta key — tap to add", "ऑनलाइन को Meta key चाहिए — जोड़ने के लिए दबाएँ") to false
            else -> tr("Online · Meta key ✓ · internet ✓", "ऑनलाइन · Meta key ✓ · इंटरनेट ✓") to true
        }
    }
}

/** Items counted from Pramaan's SAM 3.1 boxes: confident matches only (when confidence is returned). */
object ArogyaSamCount {
    fun count(confidences: List<Float?>, minConf: Float = 0.5f) = confidences.count { (it ?: 1f) >= minConf }
}

/** One counted item on the photo, in normalized 0..1 photo coordinates. */
data class CountDot(val x: Float, val y: Float)

/** Pure dots-and-confirm logic (host-tested): boxes become numbered dots; taps add or remove them. */
object ArogyaCountDots {
    const val MAX = 200

    /** Dot centers from kept boxes (already in reading order when they come from [MetaVisionMath.nms]). */
    fun fromBoxes(boxes: List<MetaVisionMath.Box>): List<CountDot> =
        boxes.map { CountDot((it.x + it.w / 2).coerceIn(0f, 1f), (it.y + it.h / 2).coerceIn(0f, 1f)) }

    /**
     * Tap the photo: a tap within [radius] of a dot removes that dot (the nearest
     * one); anywhere else adds a dot. Coordinates are clamped into the photo.
     */
    fun toggle(dots: List<CountDot>, x: Float, y: Float, radius: Float = 0.035f): List<CountDot> {
        val cx = x.coerceIn(0f, 1f); val cy = y.coerceIn(0f, 1f)
        val near = dots.indices.minByOrNull { val d = dots[it]; (d.x - cx) * (d.x - cx) + (d.y - cy) * (d.y - cy) }
        if (near != null) {
            val d = dots[near]
            val dist = kotlin.math.sqrt((d.x - cx) * (d.x - cx) + (d.y - cy) * (d.y - cy))
            if (dist <= radius) return dots.filterIndexed { i, _ -> i != near }
        }
        if (dots.size >= MAX) return dots
        return dots + CountDot(cx, cy)
    }
}

/** What the camera should look for, per drug-kit item (the ASHA can change it). */
object ArogyaKitPhrases {
    val byItem = mapOf("ORS" to "ORS sachet", "Zinc tablets" to "tablet strip", "IFA tablets (adult)" to "tablet strip", "IFA syrup (child)" to "syrup bottle",
        "Paracetamol" to "tablet strip", "Albendazole" to "tablet", "Condoms" to "condom packet", "Oral contraceptive pills" to "pill strip",
        "Emergency contraceptive pill" to "pill packet", "Sanitary napkins" to "sanitary pad packet", "Pregnancy test kit" to "test kit box")
    fun phrase(item: String) = byItem[item] ?: "packet"
    /** Register units in one photographed item (e.g. 10 tablets per strip). The ASHA can change it. */
    fun perItem(item: String) = if (phrase(item) == "tablet strip") 10 else 1
}

/** What the AI router needs: the chosen cloud model and key (null = none usable now) and the Settings order. */
data class ArogyaCloudChoice(val endpoint: ArogyaModelEndpoint?, val key: String, val cloudFirst: Boolean)

/** Pure routing order (host-tested): which engines to try, in order. */
object ArogyaRouting {
    fun order(cloudFirst: Boolean, cloudUsable: Boolean): List<String> = when {
        !cloudUsable -> listOf("phone")
        cloudFirst -> listOf("cloud", "phone")
        else -> listOf("phone", "cloud")
    }
}
