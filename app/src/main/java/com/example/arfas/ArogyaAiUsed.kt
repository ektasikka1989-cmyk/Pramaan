package com.example.arfas

/**
 * "Which model gave this answer?" for the small tag beside every AI result. Turns the engine text each feature
 * already records ("On-phone model (offline): Llama-3.2-3B…", "Muse Spark 1.3 (Meta, online): read the photo ·
 * On-phone rules…") into the steps that ran, in order, each named honestly. Pure Kotlin (host-tested).
 */
object ArogyaAiUsed {
    data class Step(val model: String, val maker: String, val online: Boolean, val ai: Boolean, val raw: String)

    private fun step(part: String): Step? {
        val p = part.trim().trim('+', '·', ' ')
        if (p.isBlank()) return null
        val l = p.lowercase()
        return when {
            "muse spark" in l || "muse-spark" in l || "pramaan • online" in l -> Step("Muse Spark 1.3", "Meta", true, true, p)
            "glimmer" in l -> Step("Muse Glimmer 30B", "Meta, via Together AI", true, true, p)
            "sam" in l.split(Regex("[^a-z0-9.]+")) || "sam 3" in l || "sam-3" in l -> Step("SAM 3.1", "Meta", true, true, p)
            "muse voice" in l || "voice transcribe" in l -> Step("Muse Voice Transcribe", "Meta", true, true, p)
            "whisper" in l -> Step("Whisper (whisper.cpp)", "OpenAI open weights", false, true, p)
            "llama" in l || "on-phone model" in l || "pramaan • offline" in l || "phone model" in l || "local model" in l ->
                Step("Llama 3.2 3B Instruct", "Meta open weights", false, true, p)
            "ml kit" in l || "mlkit" in l -> Step("ML Kit text recognition", "Google", false, true, p)
            "rule" in l || "regex" in l || "pattern" in l || "नियम" in p -> Step("Fixed rules (not AI)", "Pramaan", false, false, p)
            "e5" in l || "rerank" in l || "guide search" in l -> Step("multilingual-e5-small + mMiniLMv2 re-ranker", "open weights", false, true, p)
            "offline" in l || "ऑफ़लाइन" in p -> Step(p, "", false, true, p)
            else -> Step(p, "", true, true, p)
        }
    }

    /** The steps behind one engine label, in the order they ran (duplicates removed). */
    fun steps(engine: String): List<Step> =
        engine.split(" · ", " + ", " → ").mapNotNull { step(it) }.distinctBy { it.model }

    /** Short text for the tag: the model that made the answer (a cloud model if one did), e.g. "✨ Muse Spark 1.3". */
    fun short(engine: String): String {
        val s = steps(engine)
        val main = s.firstOrNull { it.online && it.ai } ?: s.firstOrNull { it.ai && it.model.startsWith("Llama") } ?: s.firstOrNull { it.ai } ?: s.firstOrNull()
            ?: return ""
        return (if (main.ai) "✨ " else "⚙ ") + main.model + if (main.online) " ☁" else " 📴"
    }
}
