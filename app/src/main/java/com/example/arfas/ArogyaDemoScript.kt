package com.example.arfas

/*
 * Batch 5 (demo redesign): the demo script as pure data plus the WAV reader for the
 * human voice notes. No Android or Compose imports, so the host tests can check it:
 * 12 scenes, short titles, one-sentence narrations in both languages, and the decoder.
 */
object ArogyaDemoScript {
    const val HERO_PREFIX = "Baby of Neha"
    const val CARD_FILE = "ArogyaDoot_sample_MCP_card.jpg"
    /** Human recordings the owner provides (WAV, 16-bit PCM or float32, any rate, mono/stereo, <= 60 s). */
    const val VOICE_EN = "demo/note_en.wav"
    const val VOICE_HI = "demo/note_hi.wav"
    /** Bundled ORS-sachet photo for the SAM 3.1 scene (owner to provide). */
    const val SAM_PHOTO = "demo/ors.jpg"
    /** How long each scene holds after its real work finishes (D7). */
    const val HOLD_MS = 3500L

    /** Typed fallback when the English recording is missing (same facts the recording must carry). */
    const val NOTE_EN = "Baby of Neha, day 7 visit. Baby weight 2650 grams. Baby temperature 38.4 C. Baby is feeding less since morning and the cry is weak. " +
        "No pus on the cord, eyes are clean. Baby is kept warm near the mother. Mother has taken 4 full meals, changed 3 pads today, mother temperature 37 C."
    /** Typed fallback when the Hindi recording is missing. Latin digits so numbers stay groundable. */
    const val NOTE_HI = "रितु के बच्चे की 7 दिन की विज़िट। बच्चे का वज़न 2650 ग्राम। बच्चे का तापमान 38.4 डिग्री। सुबह से बच्चा कम दूध पी रहा है और रोना कमज़ोर है। " +
        "नाल पर मवाद नहीं, आँखें साफ़ हैं। बच्चा माँ के पास गर्म रखा है। माँ ने 4 बार पूरा खाना खाया, आज 3 पैड बदले, माँ का तापमान 37 डिग्री।"

    /** M1 Q7 (owner-confirmed pages int_b p18, asha_fac p58-61): fits the story, answered in both modes. */
    const val QUESTION_EN = "What are the danger signs in a newborn that need immediate referral?"
    /** M1 Q14, the same question in Hindi (cross-lingual, owner-confirmed pages). */
    const val QUESTION_HI = "नवजात शिशु में खतरे के लक्षण क्या हैं?"

    data class Scene(val title: String, val hi: String, val narration: String, val narrationHi: String)
    val scenes = listOf(
        Scene("Meet Sunita", "मिलिए सुनीता से",
            "Sunita is an ASHA in Demo Gaon who visits families and fills paper registers by hand.",
            "सुनीता डेमो गाँव की आशा हैं, जो घर-घर जाती हैं और कागज़ी रजिस्टर हाथ से भरती हैं।"),
        Scene("Her day, planned", "आज का काम",
            "Every morning the app lists who is due from the official schedules, which are fixed rules, not AI.",
            "हर सुबह ऐप सरकारी शेड्यूल से बताता है कि आज किसके घर जाना है — ये तय नियम हैं, AI नहीं।"),
        Scene("Today's checks", "आज की जाँच",
            "Baby of Neha was born at home seven days ago, so the app opens the official Home Visit Form at the Day 7 column.",
            "रितु का बच्चा सात दिन पहले घर में पैदा हुआ, इसलिए ऐप आधिकारिक गृह भ्रमण फ़ॉर्म का 7 दिन वाला कॉलम खोलता है।"),
        Scene("Voice note → form", "वॉइस नोट → फ़ॉर्म",
            "Sunita speaks one note, AI fills the form, and every value shows the words it came from.",
            "सुनीता एक नोट बोलती हैं, AI फ़ॉर्म भरता है, और हर मान के साथ उसके शब्द दिखते हैं।"),
        Scene("Danger → refer", "खतरा → रेफ़र",
            "The form's own rules flag fever and poor feeding in a newborn and tell her to refer now.",
            "फ़ॉर्म के अपने नियम नवजात में बुखार और कम दूध पीना पकड़कर तुरंत रेफ़र करने को कहते हैं।"),
        Scene("Ask the SOP", "SOP से पूछें",
            "She asks about newborn danger signs, and the answer comes only from the official ASHA guides, with document and page.",
            "वे नवजात के खतरे के लक्षण पूछती हैं — उत्तर सिर्फ़ आधिकारिक आशा गाइड से, दस्तावेज़ और पेज के साथ।"),
        Scene("Tell ANM + PHC", "ANM-PHC सूचना",
            "AI drafts the ANM message from the visit facts, and one tap calls 108, the ANM, or WhatsApp.",
            "AI विज़िट के तथ्यों से ANM संदेश लिखता है — एक टैप में 108, ANM या WhatsApp।"),
        Scene("Card photo → card", "कार्ड फ़ोटो → कार्ड",
            "The family's MCP card photo is on the phone, AI reads it, and only values printed on the card are kept.",
            "परिवार के MCP कार्ड की फ़ोटो फ़ोन में है — AI उसे पढ़ता है, सिर्फ़ कार्ड पर छपी जानकारी रखी जाती है।"),
        Scene("Saved everywhere", "हर जगह सेव",
            "One tap saves the form, MCP card, diary, visit list and next date, and the form prints exactly like the paper one.",
            "एक टैप में फ़ॉर्म, MCP कार्ड, डायरी, विज़िट सूची और अगली तारीख सेव — फ़ॉर्म कागज़ जैसा ही छपता है।"),
        Scene("Registers + claim", "रजिस्टर + दावा",
            "Registers, the monthly report and her incentive claim update themselves from the saved visit.",
            "सेव विज़िट से रजिस्टर, मासिक रिपोर्ट और प्रोत्साहन दावा अपने आप अपडेट होते हैं।"),
        Scene("Count the drug kit", "दवा किट गिनें",
            "She photographs the ORS stock and SAM 3.1 counts the sachets, but she confirms the number before it reaches the register.",
            "वे ORS स्टॉक की फ़ोटो लेती हैं, SAM 3.1 पैकेट गिनता है — रजिस्टर से पहले वे संख्या पक्की करती हैं।"),
        Scene("Done — what worked", "हो गया — क्या चला",
            "Everything you saw works without internet on the phone, and with signal Meta's cloud models take over.",
            "आपने जो देखा सब बिना इंटरनेट फ़ोन पर चलता है — सिग्नल हो तो Meta के क्लाउड मॉडल संभालते हैं।")
    )
}

/** One AI step the demo really ran: the feature, the engine that ran it, and how long it took. */
data class ArogyaAiTick(val feature: String, val featureHi: String, val model: String, val ms: Long, val cloud: Boolean)

/**
 * Minimal WAV reader for the demo voice notes: RIFF/WAVE with 16-bit PCM or float32
 * samples, any channel count (mixed to mono) and any rate (linearly resampled to 16 kHz
 * for Whisper / Muse Voice Transcribe). Returns null when the bytes are not a WAV.
 */
object ArogyaWav {
    const val TARGET_RATE = 16000
    const val MAX_SECONDS = 60

    fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.size < 44) return null
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        if (tag(0) != "RIFF" || tag(8) != "WAVE") return null
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        var dataAt = -1; var dataLen = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = tag(at); val len = u32(at + 4)
            if (len < 0 || at + 8 + len > bytes.size) return null
            when (id) {
                "fmt " -> {
                    if (len < 16) return null
                    format = u16(at + 8); channels = u16(at + 10); rate = u32(at + 12); bits = u16(at + 22)
                }
                "data" -> { dataAt = at + 8; dataLen = len }
            }
            at += 8 + len + (len and 1)
        }
        if (channels !in 1..8 || rate <= 0 || dataAt < 0 || dataLen <= 0) return null
        val bytesPer = when {
            format == 1 && bits == 16 -> 2
            format == 3 && bits == 32 -> 4
            else -> return null
        }
        val frames = dataLen / (bytesPer * channels)
        if (frames <= 0) return null
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (ch in 0 until channels) {
                val o = dataAt + (f * channels + ch) * bytesPer
                sum += if (bytesPer == 2) (bytes[o].toInt() or (bytes[o + 1].toInt() shl 8)).toShort() / 32768f
                else Float.fromBits(u32(o))
            }
            (sum / channels).coerceIn(-1f, 1f)
        }
        if (rate == TARGET_RATE) return mono.take(MAX_SECONDS * TARGET_RATE).toFloatArray()
        val outLen = ((mono.size.toLong() * TARGET_RATE) / rate).toInt().coerceAtMost(MAX_SECONDS * TARGET_RATE)
        if (outLen <= 0) return null
        return FloatArray(outLen) { i ->
            val pos = i.toDouble() * mono.size / outLen
            val lo = pos.toInt().coerceIn(0, mono.size - 1)
            val hi = (lo + 1).coerceIn(0, mono.size - 1)
            (mono[lo] + (mono[hi] - mono[lo]) * (pos - lo).toFloat()).coerceIn(-1f, 1f)
        }
    }
}
