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

/** Items counted from Pramaan's SAM 3.1 boxes: confident matches only (when confidence is returned). */
object ArogyaSamCount {
    fun count(confidences: List<Float?>, minConf: Float = 0.5f) = confidences.count { (it ?: 1f) >= minConf }
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
