package com.example.arfas

/**
 * The first-run setup wizard's plan and words (pure Kotlin, host-tested):
 *  1. Offline AI models — the Meta models zip (Cloudflare first, Hugging Face standby, or a zip the ASHA already has),
 *  2. Health guides — the guide bundled in the APK; downloaded only if that install fails,
 *  3. Meta API key — optional; "Create a free key" opens Meta's developer page.
 * Every step can be skipped; [summary] says honestly what works with what is installed.
 */
object ArogyaSetupPlan {
    const val MODELS_URL_PRIMARY = "https://pub-c8d63c87f3ff45c6919a4bdeb91cb247.r2.dev/Pramaan_Meta_Models.zip"
    const val MODELS_URL_FALLBACK = "https://huggingface.co/datasets/EktaSikka/Pramaan_Meta_Models/resolve/main/Pramaan_Meta_Models.zip?download=true"
    /** Back-compat alias: the primary (Cloudflare) link. */
    const val MODELS_URL = MODELS_URL_PRIMARY
    const val MODELS_FILE = "Pramaan_Meta_Models.zip"
    const val MODELS_BYTES = 3_260_000_000L
    const val GUIDE_URL = "https://huggingface.co/datasets/EktaSikka/Pramaan_Knowledge_Base/resolve/main/ASHA_Health_Guide_v2.db"
    const val GUIDE_FILE = "ASHA_Health_Guide_v2.db"
    const val GUIDE_BYTES = 24_500_000L
    const val KEY_PAGE = "https://dev.meta.ai/"

    /** Free space needed before the models download: the zip, the installed models, and a margin. */
    const val MODELS_FREE_NEEDED = MODELS_BYTES * 2 + 300_000_000L

    /** The Hugging Face models download is complete when the file reached the published size
     *  (the real file is slightly larger; anything smaller is a truncated download). Pure, host-tested. */
    fun downloadComplete(size: Long): Boolean = size >= MODELS_BYTES

    /** First bytes of a real zip: PK\x03\x04 (entries), PK\x05\x06 (empty), PK\x07\x08 (spanned). Pure, host-tested. */
    fun zipMagicOk(head: ByteArray): Boolean {
        if (head.size < 4) return false
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) return false
        return (head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
            (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()) ||
            (head[2] == 0x07.toByte() && head[3] == 0x08.toByte())
    }

    /** True when the first bytes look like an HTML error page (after BOM/whitespace). Pure, host-tested. */
    fun looksLikeHtml(head: ByteArray): Boolean {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size) {
            val b = head[i]
            if (b == 0x20.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte() || b == 0x09.toByte()) i++ else break
        }
        if (i >= head.size || head[i] != 0x3C.toByte()) return false // '<'
        val tag = String(head, i, minOf(head.size - i, 16), Charsets.US_ASCII).lowercase(java.util.Locale.US)
        return tag.startsWith("<!doctype") || tag.startsWith("<html") || tag.startsWith("<head") ||
            tag.startsWith("<body") || tag.startsWith("<title") || tag.startsWith("<h1") || tag.startsWith("<p>")
    }

    enum class Step { MODELS, GUIDE, KEY }

    data class Status(val models: Boolean, val guide: Boolean, val key: Boolean) {
        val doneCount: Int get() = listOf(models, guide, key).count { it }
        val complete: Boolean get() = doneCount == 3
        /** The first step still to do (the wizard opens there from the Home banner). */
        val firstOpen: Step? get() = when { !models -> Step.MODELS; !guide -> Step.GUIDE; !key -> Step.KEY; else -> null }
    }

    fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    fun mb(bytes: Long): String = "${bytes / 1_000_000} MB"
    private fun size(bytes: Long) = if (bytes >= 1_000_000_000L) gb(bytes) else mb(bytes)

    /** "1.4 GB / 3.3 GB · 3.1 MB/s · about 7 min left" (speed and time only when known). */
    fun progressLine(done: Long, total: Long, bytesPerSec: Long, hindi: Boolean): String {
        val parts = mutableListOf(size(done) + " / " + size(total.coerceAtLeast(done)))
        if (bytesPerSec > 0) {
            parts += String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
            if (total > done) {
                val secs = (total - done) / bytesPerSec
                parts += when {
                    secs < 60 -> if (hindi) "1 मिनट से कम बाकी" else "less than a minute left"
                    secs < 3600 -> if (hindi) "लगभग ${secs / 60} मिनट बाकी" else "about ${secs / 60} min left"
                    else -> if (hindi) "लगभग ${secs / 3600} घंटे ${(secs % 3600) / 60} मिनट बाकी" else "about ${secs / 3600} h ${(secs % 3600) / 60} min left"
                }
            }
        }
        return parts.joinToString(" · ")
    }

    /** A key that cannot be a Meta Model API key is refused before any test call. */
    fun keyProblem(key: String, hindi: Boolean): String? {
        val k = key.trim()
        return when {
            k.isEmpty() -> if (hindi) "पहले key चिपकाएँ।" else "Paste the key first."
            k.startsWith("gsk_") -> if (hindi) "यह Groq key है। Pramaan सिर्फ़ Meta key से चलता है।" else "This is a Groq key. Pramaan uses a Meta key only."
            k.length < 20 || k.any { it.isWhitespace() } -> if (hindi) "यह पूरी key नहीं लगती। फिर से कॉपी करके चिपकाएँ।" else "This does not look like a complete key. Copy it again and paste."
            else -> null
        }
    }

    /** Home banner text while setup is not complete, e.g. "Setup 2 of 3 done · Meta key not added". */
    fun bannerLine(s: Status, hindi: Boolean): String {
        val missing = listOfNotNull(
            (if (hindi) "ऑफ़लाइन AI मॉडल नहीं" else "offline AI models not installed").takeIf { !s.models },
            (if (hindi) "गाइड नहीं" else "health guide not installed").takeIf { !s.guide },
            (if (hindi) "Meta key नहीं" else "Meta key not added").takeIf { !s.key })
        return (if (hindi) "सेटअप ${s.doneCount} / 3 पूरा" else "Setup ${s.doneCount} of 3 done") + " · " + missing.joinToString(", ")
    }

    data class Summary(val headline: String, val offline: String, val online: String, val tip: String)

    /** What the app can do with what is installed — shown on the last screen, in plain words. */
    fun summary(s: Status, hindi: Boolean): Summary {
        fun t(en: String, hi: String) = if (hindi) hi else en
        val headline = when {
            s.complete -> t("All set — everything is in place", "सब तैयार है")
            s.models && s.guide -> t("Ready to work offline", "ऑफ़लाइन काम के लिए तैयार")
            s.key -> t("Ready — AI answers need internet", "तैयार — AI जवाब के लिए इंटरनेट चाहिए")
            else -> t("Ready for registers and visits", "रजिस्टर और विज़िट के लिए तैयार")
        }
        val offline = when {
            s.models && s.guide -> t("📴 Offline: answers from the official guide by Llama 3.2 on this phone, voice notes (Whisper), visit forms, photo reading, registers, danger checks.",
                "📴 ऑफ़लाइन: इसी फ़ोन पर Llama 3.2 से आधिकारिक गाइड के जवाब, वॉइस नोट (Whisper), विज़िट फ़ॉर्म, फ़ोटो पढ़ना, रजिस्टर, खतरे की जाँच।")
            s.models -> t("📴 Offline: voice notes, visit forms, photo reading, registers and danger checks. Guide answers need the health guide (step 2).",
                "📴 ऑफ़लाइन: वॉइस नोट, विज़िट फ़ॉर्म, फ़ोटो पढ़ना, रजिस्टर और खतरे की जाँच। गाइड के जवाब के लिए गाइड चाहिए (कदम 2)।")
            s.guide -> t("📴 Offline: the guide's own text, voice notes (Whisper), registers, schedules and danger checks. AI answers offline need the models (step 1).",
                "📴 ऑफ़लाइन: गाइड का अपना पाठ, वॉइस नोट (Whisper), रजिस्टर, शेड्यूल और खतरे की जाँच। ऑफ़लाइन AI जवाब के लिए मॉडल चाहिए (कदम 1)।")
            else -> t("📴 Offline: registers, visit forms, schedules and danger checks (fixed government rules). AI needs step 1 or step 3.",
                "📴 ऑफ़लाइन: रजिस्टर, विज़िट फ़ॉर्म, शेड्यूल और खतरे की जाँच (सरकारी नियम)। AI के लिए कदम 1 या 3 चाहिए।")
        }
        val online = if (s.key) t("☁ Online: better answers from Meta Muse Spark, online voice (Muse Voice), drug-kit counting (SAM 3.1) and photo reading. With no signal the phone takes over automatically.",
            "☁ ऑनलाइन: Meta Muse Spark से बेहतर जवाब, ऑनलाइन आवाज़ (Muse Voice), दवा किट गिनती (SAM 3.1) और फ़ोटो पढ़ना। सिग्नल न हो तो फ़ोन अपने आप काम संभालता है।")
            else t("☁ No Meta key: answers come from the phone (Llama 3.2 and the guide). Add a key any time for better online answers, online voice and drug-kit counting.",
                "☁ Meta key नहीं: जवाब फ़ोन से (Llama 3.2 और गाइड)। बेहतर ऑनलाइन जवाब, ऑनलाइन आवाज़ और दवा किट गिनती के लिए कभी भी key जोड़ें।")
        val tip = if (s.complete) "" else t("You can finish the remaining steps any time from the yellow strip on Home or More → Setup.",
            "बाकी कदम कभी भी होम की पीली पट्टी या More → Setup से पूरे करें।")
        return Summary(headline, offline, online, tip)
    }
}
