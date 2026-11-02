package com.example.arfas

/*
 * Part B (presenter-controlled demo): the script as pure data plus the small pure
 * helpers the host tests check — navigation bounds, the AI-steps table and the
 * before/after table. No Android or Compose imports.
 *
 * 14 scenes, each with 3-6 short sentences in English and Hindi. One hero
 * everywhere: Baby of Neha (नेहा का बच्चा).
 */
object ArogyaDemoScript {
    const val HERO_PREFIX = "Baby of Neha"
    /** Human recordings the owner provides (WAV, 16-bit PCM or float32, any rate, mono/stereo, <= 60 s). */
    const val VOICE_EN = "demo/note_en.wav"
    const val VOICE_HI = "demo/note_hi.wav"

    /** Typed fallback when the English recording is missing (same facts the recording must carry). */
    const val NOTE_EN = "Baby of Neha, day 7 visit. Baby weight 2650 grams. Baby temperature 38.4 C. Baby is feeding less since morning and the cry is weak. " +
        "No pus on the cord, eyes are clean. Baby is kept warm near the mother. Mother has taken 4 full meals, changed 3 pads today, mother temperature 37 C."
    /** Typed fallback when the Hindi recording is missing. Latin digits so numbers stay groundable. */
    const val NOTE_HI = "नेहा के बच्चे की 7 दिन की विज़िट। बच्चे का वज़न 2650 ग्राम। बच्चे का तापमान 38.4 डिग्री। सुबह से बच्चा कम दूध पी रहा है और रोना कमज़ोर है। " +
        "नाल पर मवाद नहीं, आँखें साफ़ हैं। बच्चा माँ के पास गर्म रखा है। माँ ने 4 बार पूरा खाना खाया, आज 3 पैड बदले, माँ का तापमान 37 डिग्री।"

    /** M1 Q7 (owner-confirmed pages int_b p18, asha_fac p58-61): fits the story, answered in both modes. */
    const val QUESTION_EN = "What are the danger signs in a newborn that need immediate referral?"
    /** M1 Q14, the same question in Hindi (cross-lingual, owner-confirmed pages). */
    const val QUESTION_HI = "नवजात शिशु में खतरे के लक्षण क्या हैं?"
    /** The one follow-up on the Ask SOP scene; the runtime session remembers the first question. */
    const val FOLLOWUP_EN = "What is the first thing she should do for this baby?"
    const val FOLLOWUP_HI = "इस बच्चे के लिए उसे सबसे पहले क्या करना चाहिए?"

    data class Scene(val id: String, val title: String, val hi: String, val narration: List<String>, val narrationHi: List<String>)
    val scenes = listOf(
        Scene("meet", "Meet Sunita, and the problem", "सुनीता से मिलिए, और समस्या",
            listOf(
                "This is Sunita. She is an ASHA in our sample village, Rampur.",
                "Every day she plans visits, fills paper forms, copies registers, and answers health questions from memory.",
                "With no signal in the village, she cannot look anything up.",
                "ArogyaDoot puts her visits, official guides, and registers on her phone, and it works offline."),
            listOf(
                "ये हैं सुनीता। वे हमारे नमूना गाँव, रामपुर की आशा हैं।",
                "रोज़ वे विज़िट तय करती हैं, कागज़ी फ़ॉर्म भरती हैं, रजिस्टर नकल करती हैं, और स्वास्थ्य के सवालों के जवाब याद से देती हैं।",
                "गाँव में सिग्नल नहीं होता, तो वे कुछ देख नहीं पातीं।",
                "ArogyaDoot उनकी विज़िट, आधिकारिक गाइड और रजिस्टर फ़ोन में रखता है, और बिना इंटरनेट चलता है।")),
        Scene("due", "Today's visits in one look", "आज की विज़िट एक नज़र में",
            listOf(
                "Every morning the app lists who Sunita must visit, and why.",
                "These dates come from the government's fixed schedules. They are rules, not AI.",
                "Baby of Neha needs a Day 7 visit, so she taps the name and starts.",
                "No diary to flip through, no date to calculate."),
            listOf(
                "हर सुबह ऐप बताता है कि सुनीता को किसके घर जाना है, और क्यों।",
                "ये तारीख़ें सरकार के तय शेड्यूल से आती हैं। ये नियम हैं, AI नहीं।",
                "नेहा के बच्चे की 7वें दिन की विज़िट बाकी है, तो वे नाम दबाकर शुरू करती हैं।",
                "न डायरी पलटनी है, न तारीख़ गिननी है।")),
        Scene("form", "Baby of Neha, Day 7: the right form, right column", "नेहा का बच्चा, 7वाँ दिन: सही फ़ॉर्म, सही कॉलम",
            listOf(
                "For a 7-day-old baby born at home, the app opens the official Home Visit Form at the Day 7 column.",
                "This is the government's own form. Same rows, same order.",
                "Sunita does not choose the form. The app picks it from the baby's age.",
                "Her job is to check the baby and speak what she sees."),
            listOf(
                "घर में पैदा हुए 7 दिन के बच्चे के लिए ऐप आधिकारिक गृह भ्रमण फ़ॉर्म का 7वें दिन वाला कॉलम खोलता है।",
                "ये सरकार का अपना फ़ॉर्म है। वही पंक्तियाँ, वही क्रम।",
                "सुनीता को फ़ॉर्म नहीं चुनना पड़ता। बच्चे की उम्र से ऐप खुद चुनता है।",
                "उनका काम है बच्चे को देखना और जो दिखे वो बोलना।")),
        Scene("voice", "Speak once, the form fills itself", "एक बार बोलिए, फ़ॉर्म खुद भरेगा",
            listOf(
                "Sunita speaks one short note about mother and baby.",
                "The phone turns her speech into text, then AI fills the form rows.",
                "Every value shows the exact words it came from, so she can check.",
                "Nothing is saved until she confirms each value herself."),
            listOf(
                "सुनीता माँ और बच्चे के बारे में एक छोटा नोट बोलती हैं।",
                "फ़ोन उनकी आवाज़ को लिखता है, फिर AI फ़ॉर्म की पंक्तियाँ भरता है।",
                "हर मान के साथ वही शब्द दिखते हैं जिनसे वो आया, ताकि वे जाँच सकें।",
                "जब तक वे हर मान पक्का न करें, कुछ सेव नहीं होता।")),
        Scene("danger", "Danger sign: refer now", "खतरे का लक्षण: तुरंत रेफ़र",
            listOf(
                "Fever and poor feeding in a newborn are danger signs printed on the form itself.",
                "These are fixed government rules, not AI. They can never guess.",
                "The app tells Sunita to refer the baby to the PHC today.",
                "AI only drafts the message to the ANM. Sunita reads it and decides to send."),
            listOf(
                "नवजात में बुखार और कम दूध पीना फ़ॉर्म पर छपे खतरे के लक्षण हैं।",
                "ये तय सरकारी नियम हैं, AI नहीं। ये अंदाज़ा नहीं लगा सकते।",
                "ऐप सुनीता को बच्चे को आज ही PHC रेफ़र करने को कहता है।",
                "AI सिर्फ़ ANM को संदेश लिखता है। सुनीता उसे पढ़ती हैं और भेजने का फ़ैसला करती हैं।")),
        Scene("guides", "Where answers come from", "उत्तर कहाँ से आते हैं",
            listOf(
                "Every answer in this app comes only from official Government of India documents.",
                "The guides were downloaded from government portals, cut into small passages, and stored on this phone.",
                "That is why answers work offline, and always name the document and page.",
                "Tap the link to open the library and read the documents yourself."),
            listOf(
                "इस ऐप का हर उत्तर सिर्फ़ भारत सरकार के आधिकारिक दस्तावेज़ों से आता है।",
                "गाइड सरकारी पोर्टलों से उतारे गए, छोटे हिस्सों में काटे गए, और इसी फ़ोन में रखे गए।",
                "इसीलिए उत्तर ऑफ़लाइन मिलते हैं, और हमेशा दस्तावेज़ और पेज बताते हैं।",
                "लाइब्रेरी खोलने के लिए लिंक दबाएँ और दस्तावेज़ खुद पढ़ें।")),
        Scene("sops", "Ask SOP, then ask again", "SOP से पूछिए, फिर दोबारा पूछिए",
            listOf(
                "Sunita asks when a newborn must be referred at once.",
                "The app searches the official guides, shows the top passages with document and page, then writes the answer from them.",
                "Every sentence carries its citation, and she opens the official document with one tap.",
                "Then she asks a follow-up, and the app remembers the first question.",
                "When the phone is online, she also taps Check online and compares both answers."),
            listOf(
                "सुनीता पूछती हैं कि नवजात को तुरंत कब रेफ़र करना चाहिए।",
                "ऐप आधिकारिक गाइड खोजता है, दस्तावेज़ और पेज के साथ मुख्य हिस्से दिखाता है, फिर उन्हीं से उत्तर लिखता है।",
                "स्रोत दबाकर वे छपी हुई असली पंक्तियाँ पढ़ती हैं।",
                "हर वाक्य के साथ उसका हवाला है, और एक दबाने पर आधिकारिक दस्तावेज़ खुलता है।",
                "फिर वे दोबारा पूछती हैं, और ऐप पहला सवाल याद रखता है।",
                "फ़ोन ऑनलाइन हो तो वे ऑनलाइन जाँच भी दबाती हैं और दोनों उत्तर मिलाती हैं।")),
        Scene("card", "MCP card photo becomes the card", "MCP कार्ड की फ़ोटो बनती है कार्ड",
            listOf(
                "Sunita photographs the family's MCP card. No typing.",
                "The phone reads the printed text and pulls out vaccines, weights, and dates.",
                "Only values printed on the card are kept, and she ticks each one.",
                "Confirmed vaccines leave the due list, and the weight is added to the card."),
            listOf(
                "सुनीता परिवार के MCP कार्ड की फ़ोटो लेती हैं। लिखना नहीं पड़ता।",
                "फ़ोन छपा अक्षर पढ़ता है और टीके, वज़न और तारीख़ें निकालता है।",
                "सिर्फ़ कार्ड पर छपी जानकारी रखी जाती है, और वे हर मान पर निशान लगाती हैं।",
                "पक्के टीके बाकी सूची से हट जाते हैं, और वज़न कार्ड में जुड़ जाता है।")),
        Scene("medscan", "Scan a medicine, count the kit", "दवा स्कैन करिए, किट गिनिए",
            listOf(
                "To record new stock, Sunita photographs a strip. Name, batch, and expiry are read offline.",
                "She confirms, and it becomes a stock entry in the drug register.",
                "To count, she photographs the shelf and the cloud counts each packet.",
                "Counting needs internet, so offline the app says so and skips it. It never guesses."),
            listOf(
                "नया स्टॉक दर्ज करने के लिए सुनीता पत्ते की फ़ोटो लेती हैं। नाम, बैच और एक्सपायरी ऑफ़लाइन पढ़े जाते हैं।",
                "वे पक्का करती हैं, और ये दवा रजिस्टर में स्टॉक बन जाता है।",
                "गिनने के लिए वे शेल्फ़ की फ़ोटो लेती हैं और क्लाउड हर पैकेट गिनता है।",
                "गिनती को इंटरनेट चाहिए, इसलिए ऑफ़लाइन ऐप बता देता है और छोड़ देता है। अंदाज़ा कभी नहीं लगाता।")),
        Scene("saved", "Saved everywhere at once", "एक साथ हर जगह सेव",
            listOf(
                "One tap saves the visit form, the MCP card, the diary, and the next visit date.",
                "The registers are proper tables now, not copied pages.",
                "The form prints and shares exactly like the paper one.",
                "Sunita checks everything on screen before it is saved."),
            listOf(
                "एक टैप में विज़िट फ़ॉर्म, MCP कार्ड, डायरी और अगली विज़िट की तारीख़ सेव हो जाते हैं।",
                "रजिस्टर अब सही तालिकाएँ हैं, नकल किए पन्ने नहीं।",
                "फ़ॉर्म कागज़ जैसा ही छपता और भेजा जाता है।",
                "सेव होने से पहले सुनीता सब कुछ स्क्रीन पर देखती हैं।")),
        Scene("practice", "Learn every day", "रोज़ सीखिए",
            listOf(
                "Every day the app makes three small questions from the official guides.",
                "Sunita answers, sees right or wrong at once, and reads the source.",
                "Each answer names the document and page it came from.",
                "Her weekly score grows. Practice, not exams."),
            listOf(
                "रोज़ ऐप आधिकारिक गाइड से तीन छोटे सवाल बनाता है।",
                "सुनीता जवाब देती हैं, सही-गलत तुरंत देखती हैं, और स्रोत पढ़ती हैं।",
                "हर उत्तर बताता है कि वो किस दस्तावेज़ के किस पेज से आया।",
                "उनका हफ़्ते का स्कोर बढ़ता है। अभ्यास, परीक्षा नहीं।")),
        Scene("setting", "One setting for everything", "सबके लिए एक सेटिंग",
            listOf(
                "The whole app follows one setting: Local first or Cloud first.",
                "Local first keeps everything on the phone and works without signal.",
                "Cloud first uses Meta's larger models when there is signal and a key.",
                "Whichever she picks, the phone is always the fallback. Nothing stops offline."),
            listOf(
                "पूरा ऐप एक सेटिंग मानता है: Local first या Cloud first।",
                "Local first सब कुछ फ़ोन पर रखता है और बिना सिग्नल चलता है।",
                "Cloud first सिग्नल और key होने पर Meta के बड़े मॉडल इस्तेमाल करता है।",
                "वे जो भी चुनें, फ़ोन हमेशा बैकअप रहता है। ऑफ़लाइन कुछ रुकता नहीं।")),
        Scene("aitable", "What AI did in this visit", "इस विज़िट में AI ने क्या किया",
            listOf(
                "This table lists every AI step the demo really ran, with its model and measured time.",
                "A phone icon means it ran offline on this phone. A cloud icon means Meta's cloud.",
                "Danger rules are marked as fixed rules. They are not AI.",
                "Built with Muse Code."),
            listOf(
                "ये तालिका डेमो में चले हर AI चरण को उसके मॉडल और नपे समय के साथ बताती है।",
                "फ़ोन का निशान मतलब इसी फ़ोन पर ऑफ़लाइन चला। क्लाउड का निशान मतलब Meta का क्लाउड।",
                "खतरे के नियम तय नियम लिखे हैं। वे AI नहीं हैं।",
                "Muse Code से बनाया गया।")),
        Scene("beforeafter", "Sunita's day, before and after", "सुनीता का दिन, पहले और अब",
            listOf(
                "Before, the form, registers, and diary were all copied by hand.",
                "After, one voice note fills them. The table shows only times this demo measured.",
                "Where nothing was timed, it says so honestly.",
                "No invented percentages, ever."),
            listOf(
                "पहले फ़ॉर्म, रजिस्टर और डायरी सब हाथ से नकल होते थे।",
                "अब एक वॉइस नोट से सब भर जाता है। तालिका में सिर्फ़ वही समय है जो इस डेमो ने नापा।",
                "जहाँ कुछ नापा नहीं, वहाँ साफ़ लिखा है।",
                "मनगढ़ंत प्रतिशत कभी नहीं।"))
    )
}

/** One AI step the demo really ran: the feature, the engine that ran it, and how long it took. */
data class ArogyaAiTick(val feature: String, val featureHi: String, val model: String, val ms: Long, val cloud: Boolean)

/** Presenter navigation: scene indexes 0..scenes.size, where scenes.size is the end screen. */
object ArogyaDemoNav {
    fun next(i: Int, n: Int): Int = (i + 1).coerceIn(0, n)
    fun back(i: Int): Int = (i - 1).coerceAtLeast(0)
    fun isEnd(i: Int, n: Int): Boolean = i >= n
    fun label(i: Int, n: Int, hindi: Boolean): String =
        if (isEnd(i, n)) { if (hindi) "डेमो पूरा" else "Demo complete" }
        else { if (hindi) "सीन ${i + 1} / $n" else "Scene ${i + 1} of $n" }
}

/** The "What AI did" and "Before/after" tables, built only from ticks the demo recorded. */
object ArogyaDemoTicks {
    data class Row(val step: String, val benefit: String, val model: String, val cloud: Boolean, val ms: Long)
    data class BeforeAfter(val task: String, val before: String, val afterMs: Long)

    private fun benefit(feature: String, hindi: Boolean): String = when (feature) {
        "speech-to-text" -> if (hindi) "बोलना लिखने से तेज़" else "Speaking beats typing"
        "form filling + evidence check" -> if (hindi) "एक नोट से पूरा फ़ॉर्म" else "One note fills the whole form"
        "guide search + grounded answer" -> if (hindi) "सही SOP तुरंत, स्रोत के साथ" else "The right SOP at once, with its source"
        "guide follow-up" -> if (hindi) "दोबारा पूछने पर भी जवाब" else "Follow-ups work too"
        "online check" -> if (hindi) "आधिकारिक वेबसाइट से दोबारा जाँच" else "Official websites double-check"
        "card text reading + extraction" -> if (hindi) "कार्ड नकल नहीं करना पड़ता" else "No copying the card by hand"
        "medicine scan" -> if (hindi) "पत्ता फ़ोटो से स्टॉक में" else "A strip photo becomes stock"
        "SAM 3.1 count" -> if (hindi) "शेल्फ़ गिनने में मदद" else "Counting help for the shelf"
        "ANM message" -> if (hindi) "संदेश लिखने में मदद" else "The message writes itself"
        "daily practice" -> if (hindi) "रोज़ अभ्यास, स्रोत के साथ" else "Daily practice, with sources"
        "reading aloud" -> if (hindi) "पढ़ना न आए तब भी चले" else "Works even when reading is hard"
        else -> if (hindi) "सुनीता की मदद" else "Helps Sunita"
    }

    /** Every recorded tick becomes one row; the danger-rules row is ⚙, never AI. */
    fun rows(ticks: List<ArogyaAiTick>, dangerRan: Boolean, hindi: Boolean): List<Row> {
        val out = ticks.map { t ->
            Row(if (hindi) t.featureHi else t.feature, benefit(t.feature, hindi), t.model, t.cloud, t.ms)
        }.toMutableList()
        if (dangerRan) out += Row(if (hindi) "खतरे के नियम" else "Danger rules",
            if (hindi) "तय नियम — तुरंत रेफ़र" else "Fixed rules — refer at once",
            if (hindi) "⚙ तय नियम (AI नहीं)" else "⚙ Fixed rules (not AI)", false, 0)
        return out
    }

    /**
     * Before/after rows only for work this run actually measured. "Before" is never
     * timed (paper), so it always says so; tasks without a tick are left out entirely.
     */
    fun beforeAfter(ticks: List<ArogyaAiTick>, hindi: Boolean): List<BeforeAfter> {
        fun msOf(vararg feats: String) = ticks.firstOrNull { it.feature in feats }?.ms ?: 0
        val paper = if (hindi) "हाथ से — नापा नहीं" else "By hand — not timed"
        return listOf(
            Triple(if (hindi) "7वें दिन का फ़ॉर्म भरना" else "Fill the Day-7 form", paper, msOf("form filling + evidence check")),
            Triple(if (hindi) "SOP का जवाब ढूँढना" else "Find the SOP answer", paper, msOf("guide search + grounded answer")),
            Triple(if (hindi) "MCP कार्ड नकल करना" else "Copy the MCP card", paper, msOf("card text reading + extraction")),
            Triple(if (hindi) "ANM संदेश लिखना" else "Write the ANM message", paper, msOf("ANM message")),
            Triple(if (hindi) "अभ्यास के सवाल बनाना" else "Make practice questions", paper, msOf("daily practice"))
        ).filter { it.third > 0 }.map { BeforeAfter(it.first, it.second, it.third) }
    }
}

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
