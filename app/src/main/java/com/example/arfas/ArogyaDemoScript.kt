package com.example.arfas

/*
 * Part B (presenter-controlled demo): the script as pure data plus the small pure
 * helpers the host tests check — navigation bounds, the AI-steps table and the
 * before/after table. No Android or Compose imports.
 *
 * 16 scenes (demo UI pass), three short sentences each in English and Hindi. One hero
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

    /** The slide example (Integrated ASHA Training Module Part B, p.18): answered in both modes. */
    const val QUESTION_EN = "What should be applied on the baby's cord stump?"
    const val QUESTION_HI = "बच्चे की नाल पर क्या लगाना चाहिए?"
    /** The one follow-up on the Ask SOP scene; the runtime session remembers the first question. */
    const val FOLLOWUP_EN = "How often should the cord be checked?"
    const val FOLLOWUP_HI = "नाल की जाँच कितनी बार करनी चाहिए?"
    /** Part D3 gold scene: Hinglish + Hindi paraphrases, answered live (designed for VA-005, Part B p.19). */
    const val GOLD_EN = "baby ko pehla gaadha doodh dena chahiye?"
    const val GOLD_HI = "क्या बच्चे को पहला गाढ़ा दूध देना चाहिए?"

    /** [screen]: the app screen this scene shows, as a heading ("Register · Add person"). */
    data class Scene(val id: String, val title: String, val hi: String, val narration: List<String>, val narrationHi: List<String>,
                     val screen: String = "", val screenHi: String = "")
    // Demo UI pass: 16 scenes, three short sentences each, nothing repeated scene to scene.
    // "She confirms every AI result" is said once (scene 1); the cards show it after that.
    val scenes = listOf(
        Scene("meet", "Meet Sunita, ASHA of Rampur", "मिलिए सुनीता से, रामपुर की आशा",
            listOf(
                "Sunita is the ASHA of Rampur, a sample village.",
                "Her day is home visits, paper forms, registers and health questions, often with no signal.",
                "Pramaan does that paperwork with AI on her phone, and she confirms every result."),
            listOf(
                "सुनीता नमूना गाँव रामपुर की आशा हैं।",
                "उनका दिन घर-घर विज़िट, कागज़ी फ़ॉर्म, रजिस्टर और स्वास्थ्य के सवालों में जाता है, अक्सर बिना सिग्नल।",
                "Pramaan यह काम फ़ोन पर AI से करता है, और हर नतीजा सुनीता पक्का करती हैं।"),
            "Introduction", "परिचय"),
        Scene("due", "Today's visits", "आज की विज़िट",
            listOf(
                "The app lists who is due today, and why.",
                "Dates come from fixed government schedules, not AI.",
                "Baby of Neha is due for the Day 7 visit."),
            listOf(
                "ऐप बताता है कि आज किसकी विज़िट बाकी है, और क्यों।",
                "तारीख़ें सरकार के तय शेड्यूल से आती हैं, AI से नहीं।",
                "नेहा के बच्चे की 7वें दिन की विज़िट बाकी है।"),
            "Home · Today", "होम · आज"),
        Scene("addperson", "New family, added from a photo", "नया परिवार, फ़ोटो से दर्ज",
            listOf(
                "Sunita photographs the new family's register page.",
                "AI reads it into a review card on the phone, and removes any Aadhaar number.",
                "One tap adds them to the register and the due list."),
            listOf(
                "सुनीता नए परिवार के रजिस्टर पेज की फ़ोटो लेती हैं।",
                "AI फ़ोन पर ही उसे जाँच कार्ड में पढ़ता है, और आधार नंबर हटा देता है।",
                "एक टैप में वे रजिस्टर और बाकी सूची में जुड़ जाते हैं।"),
            "Register · Add person", "रजिस्टर · नया व्यक्ति"),
        Scene("form", "The right form opens itself", "सही फ़ॉर्म खुद खुलता है",
            listOf(
                "The baby is 7 days old, so the official Home Visit Form opens at the Day 7 column.",
                "Same rows, same order as the government paper form.",
                "Sunita only has to examine the baby."),
            listOf(
                "बच्चा 7 दिन का है, तो सरकारी गृह भ्रमण फ़ॉर्म 7वें दिन के कॉलम पर खुलता है।",
                "पंक्तियाँ और क्रम सरकारी कागज़ी फ़ॉर्म जैसे ही।",
                "सुनीता को बस बच्चे को देखना है।"),
            "Visit · Official form", "विज़िट · सरकारी फ़ॉर्म"),
        Scene("voice", "Speak once, the form fills", "एक बार बोलें, फ़ॉर्म भरे",
            listOf(
                "Sunita speaks one note about mother and baby.",
                "Speech becomes text, and AI fills each form row.",
                "Every value shows the words it came from."),
            listOf(
                "सुनीता माँ और बच्चे पर एक नोट बोलती हैं।",
                "आवाज़ लिखे शब्द बनती है, और AI फ़ॉर्म की हर पंक्ति भरता है।",
                "हर मान के साथ वे शब्द दिखते हैं जिनसे वह आया।"),
            "Visit · Voice note", "विज़िट · वॉइस नोट"),
        Scene("danger", "Danger sign: refer today", "खतरे का लक्षण: आज रेफ़र",
            listOf(
                "Fever and poor feeding are danger signs printed on the form.",
                "Fixed rules catch them, so the app says refer to the PHC today.",
                "AI drafts the message to the ANM, and Sunita decides to send it."),
            listOf(
                "बुखार और कम दूध पीना फ़ॉर्म पर छपे खतरे के लक्षण हैं।",
                "तय नियम इन्हें पकड़ते हैं, और ऐप आज ही PHC रेफ़र करने को कहता है।",
                "AI ANM के लिए संदेश लिखता है, और भेजना सुनीता तय करती हैं।"),
            "Visit · Danger signs", "विज़िट · खतरे के लक्षण"),
        Scene("explain", "Explained to the family in Hindi", "परिवार को हिंदी में समझाया",
            listOf(
                "AI turns the guide's advice into short, simple Hindi.",
                "The phone reads it aloud, even offline.",
                "Nothing is added beyond what the guide says."),
            listOf(
                "AI गाइड की सलाह को छोटी, आसान हिंदी में बदलता है।",
                "फ़ोन इसे बोलकर सुनाता है, ऑफ़लाइन भी।",
                "गाइड से बाहर कुछ नहीं जुड़ता।"),
            "Visit · Family", "विज़िट · परिवार"),
        Scene("guides", "Answers only from official guides", "उत्तर सिर्फ़ सरकारी गाइड से",
            listOf(
                "Official Government of India guides are stored on this phone.",
                "Every answer names its document and page.",
                "Tap the button to open the library."),
            listOf(
                "भारत सरकार की आधिकारिक गाइड इसी फ़ोन में रखी हैं।",
                "हर उत्तर अपना दस्तावेज़ और पेज बताता है।",
                "लाइब्रेरी खोलने के लिए बटन दबाएँ।"),
            "Guide library", "गाइड लाइब्रेरी"),
        Scene("sops", "Ask, and get a cited answer", "पूछें, और हवाले वाला उत्तर पाएँ",
            listOf(
                "Sunita asks what to apply on the baby's cord stump.",
                "The answer is written from guide passages, with page citations she can tap.",
                "A follow-up works too, and online she can compare the cloud answer."),
            listOf(
                "सुनीता पूछती हैं कि बच्चे की नाल पर क्या लगाएँ।",
                "उत्तर गाइड के हिस्सों से लिखा जाता है, पेज के हवाले के साथ जिन्हें वे दबा सकती हैं।",
                "दोबारा सवाल भी चलता है, और ऑनलाइन वे क्लाउड का उत्तर मिला सकती हैं।"),
            "Ask the ASHA guide", "ASHA गाइड से पूछें"),
        Scene("card", "MCP card photo, read by AI", "MCP कार्ड की फ़ोटो, AI ने पढ़ी",
            listOf(
                "Sunita photographs the family's MCP card.",
                "AI pulls out the vaccines, weights and dates printed on it.",
                "She ticks each value, and the register updates."),
            listOf(
                "सुनीता परिवार के MCP कार्ड की फ़ोटो लेती हैं।",
                "AI उस पर छपे टीके, वज़न और तारीख़ें निकालता है।",
                "वे हर मान पर निशान लगाती हैं, और रजिस्टर अपडेट होता है।"),
            "MCP card · Photo", "MCP कार्ड · फ़ोटो"),
        Scene("mcpcard", "Neha's official MCP card, updated", "नेहा का आधिकारिक MCP कार्ड, अपडेट",
            listOf(
                "This is Neha's official MCP card, filled from today's visit.",
                "Highlighted fields changed today.",
                "NA means not applicable yet, and Not recorded means still to be filled."),
            listOf(
                "यह नेहा का आधिकारिक MCP कार्ड है, आज की विज़िट से भरा।",
                "हाइलाइट वाले खाने आज बदले।",
                "NA मतलब अभी लागू नहीं, और दर्ज नहीं मतलब अभी भरना बाकी।"),
            "MCP card · Official", "MCP कार्ड · आधिकारिक"),
        Scene("medscan", "Medicine scan and kit count", "दवा स्कैन और किट गिनती",
            listOf(
                "A photo of a medicine strip gives its name, batch and expiry, fully offline.",
                "One tap adds it to the stock register.",
                "Online, Meta SAM 3.1 also counts the packets in a kit photo."),
            listOf(
                "दवा के पत्ते की फ़ोटो से नाम, बैच और एक्सपायरी मिलती है, पूरी तरह ऑफ़लाइन।",
                "एक टैप में यह स्टॉक रजिस्टर में जुड़ जाती है।",
                "ऑनलाइन, Meta SAM 3.1 किट की फ़ोटो में पैकेट भी गिनता है।"),
            "Stock register", "स्टॉक रजिस्टर"),
        Scene("saved", "One save updates everything", "एक सेव, सब अपडेट",
            listOf(
                "One tap saves the form, the MCP card, the diary and the next visit date.",
                "Registers are real tables, ready to print or share.",
                "The list below shows exactly what changed."),
            listOf(
                "एक टैप में फ़ॉर्म, MCP कार्ड, डायरी और अगली विज़िट की तारीख़ सेव।",
                "रजिस्टर असली तालिकाएँ हैं, छापने या भेजने को तैयार।",
                "नीचे की सूची बताती है कि ठीक क्या बदला।"),
            "Registers · Saved", "रजिस्टर · सेव"),
        Scene("practice", "Three questions a day", "रोज़ तीन सवाल",
            listOf(
                "AI makes three questions a day from the official guides.",
                "Sunita sees right or wrong at once, with the source page.",
                "Practice, not exams."),
            listOf(
                "AI रोज़ सरकारी गाइड से तीन सवाल बनाता है।",
                "सुनीता सही-गलत तुरंत देखती हैं, स्रोत पेज के साथ।",
                "अभ्यास, परीक्षा नहीं।"),
            "Daily practice", "रोज़ अभ्यास"),
        Scene("aitable", "AI tools behind this visit", "इस विज़िट के पीछे AI टूल",
            listOf(
                "Each row is a feature, with the tool that runs it on the phone and in the cloud.",
                "Meta's tools are marked, and a tick shows what ran in this demo.",
                "Danger signs stay fixed rules, not AI."),
            listOf(
                "हर पंक्ति एक सुविधा है, साथ में वह टूल जो उसे फ़ोन पर और क्लाउड में चलाता है।",
                "Meta के टूल चिह्नित हैं, और सही का निशान बताता है कि इस डेमो में क्या चला।",
                "खतरे के लक्षण तय नियम हैं, AI नहीं।"),
            "AI summary", "AI सारांश"),
        Scene("beforeafter", "How AI eased Sunita's visit", "AI ने सुनीता की विज़िट कैसे आसान की",
            listOf(
                "By hand, these tasks take long on every visit.",
                "The table shows what AI did, today's time, and the usual time online and offline.",
                "Hand times are estimates, and AI times are measured."),
            listOf(
                "हाथ से ये काम हर विज़िट में बहुत समय लेते हैं।",
                "तालिका बताती है कि AI ने क्या किया, आज का समय, और ऑनलाइन-ऑफ़लाइन का आम समय।",
                "हाथ वाले समय अनुमान हैं, और AI वाले समय नापे गए।"),
            "Impact", "असर")
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
        "gold-layer answer" -> if (hindi) "अपने शब्दों में पूछो, हवाले वाला जवाब" else "Ask in her own words, get a cited answer"
        "online check" -> if (hindi) "आधिकारिक वेबसाइट से दोबारा जाँच" else "Official websites double-check"
        "card text reading + extraction" -> if (hindi) "कार्ड नकल नहीं करना पड़ता" else "No copying the card by hand"
        "medicine scan" -> if (hindi) "पत्ता फ़ोटो से स्टॉक में" else "A strip photo becomes stock"
        "SAM 3.1 count" -> if (hindi) "शेल्फ़ गिनने में मदद" else "Counting help for the shelf"
        "ANM message" -> if (hindi) "संदेश लिखने में मदद" else "The message writes itself"
        "family explanation" -> if (hindi) "परिवार हिंदी में समझा" else "The family understands in Hindi"
        "add person from photo" -> if (hindi) "फ़ोटो से नाम जुड़ा" else "A photo registers the newcomer"
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

    // ── Demo UI pass: the "AI tools" table and the "How AI eased the visit" table ──

    /**
     * One app feature with the tool that runs it on the phone (offline) and in the cloud (online).
     * [metaOffline]/[metaOnline]: the tool is Meta's. [keys]: the tick features that prove it ran today.
     * [rule]: fixed government rules, never AI. [phoneOnly]: kept on the phone on purpose (privacy).
     */
    data class Tool(
        val feature: String, val featureHi: String,
        val offline: String, val offlineHi: String, val metaOffline: Boolean,
        val online: String, val onlineHi: String, val metaOnline: Boolean,
        val keys: List<String>, val rule: Boolean = false, val phoneOnly: Boolean = false
    )
    data class ToolRow(val tool: Tool, val ranMs: Long, val ranCloud: Boolean?, val ran: Boolean)

    private const val LLAMA = "Llama 3.2 3B (Meta)"
    private const val SPARK = "Muse Spark 1.3 (Meta)"
    private const val OCR = "ML Kit text (Google)"

    /** Every AI feature in the app, with its real tools (as wired in ArogyaVisitAi / MetaModelApi). */
    val tools = listOf(
        Tool("Speech to text", "बोलकर लिखना", "Whisper (OpenAI)", "Whisper (OpenAI)", false,
            "Muse Voice Transcribe (Meta)", "Muse Voice Transcribe (Meta)", true, listOf("speech-to-text")),
        Tool("Form filled from the voice note", "वॉइस नोट से फ़ॉर्म भरना", LLAMA, LLAMA, true, SPARK, SPARK, true,
            listOf("form filling + evidence check")),
        Tool("Danger signs", "खतरे के लक्षण", "Fixed government rules", "तय सरकारी नियम", false,
            "Fixed government rules", "तय सरकारी नियम", false, emptyList(), rule = true),
        Tool("ANM message draft", "ANM संदेश ड्राफ़्ट", LLAMA, LLAMA, true, SPARK, SPARK, true, listOf("ANM message")),
        Tool("Family explanation in Hindi", "परिवार को हिंदी में समझाना", LLAMA, LLAMA, true, SPARK, SPARK, true,
            listOf("family explanation")),
        Tool("Guide search + cited answer", "गाइड खोज + हवाले वाला उत्तर",
            "E5 search + reranker + $LLAMA", "E5 खोज + रीरैंकर + $LLAMA", true, SPARK, SPARK, true,
            listOf("guide search + grounded answer", "guide follow-up", "online check")),
        Tool("Add person from a photo", "फ़ोटो से नया व्यक्ति", "$OCR + $LLAMA", "$OCR + $LLAMA", true,
            "Stays on the phone (names never leave)", "फ़ोन पर ही (नाम बाहर नहीं जाते)", false,
            listOf("add person from photo"), phoneOnly = true),
        Tool("MCP card reading", "MCP कार्ड पढ़ना", "$OCR + $LLAMA", "$OCR + $LLAMA", true,
            "Stays on the phone (names never leave)", "फ़ोन पर ही (नाम बाहर नहीं जाते)", false,
            listOf("card text reading + extraction"), phoneOnly = true),
        Tool("Medicine scan", "दवा स्कैन", "$OCR + rules, then $LLAMA", "$OCR + नियम, फिर $LLAMA", true,
            "$SPARK (names removed)", "$SPARK (नाम हटाकर)", true, listOf("medicine scan")),
        Tool("Drug-kit count", "दवा किट गिनती", "Counted by hand", "हाथ से गिनती", false,
            "SAM 3.1 (Meta)", "SAM 3.1 (Meta)", true, listOf("SAM 3.1 count")),
        Tool("Daily practice questions", "रोज़ अभ्यास के सवाल", LLAMA, LLAMA, true, SPARK, SPARK, true, listOf("daily practice")),
        Tool("Read aloud", "पढ़कर सुनाना", "Android voice (offline)", "Android आवाज़ (ऑफ़लाइन)", false,
            "Android voice", "Android आवाज़", false, listOf("reading aloud"))
    )

    /** The catalogue with what ran in this demo (first matching tick; danger rules when they ran). */
    fun toolRows(ticks: List<ArogyaAiTick>, dangerRan: Boolean): List<ToolRow> = tools.map { t ->
        val tick = ticks.lastOrNull { it.feature in t.keys }   // latest run (after a Local | Cloud flip)
        when {
            t.rule -> ToolRow(t, 0, null, dangerRan)
            tick != null -> ToolRow(t, tick.ms, tick.cloud, true)
            else -> ToolRow(t, 0, null, false)
        }
    }

    /** How many catalogue tools are Meta's (offline, online). */
    fun metaCounts(): Pair<Int, Int> = tools.count { it.metaOffline } to tools.count { it.metaOnline }

    /**
     * A visit task, its rough by-hand time (an ESTIMATE, labelled as such on screen, never measured)
     * and the tick features that measure the AI doing it.
     */
    data class Task(val task: String, val taskHi: String, val handMin: Int, val keys: List<String>)
    val tasks = listOf(
        Task("Fill the Day 7 visit form", "7वें दिन का फ़ॉर्म भरना", 10, listOf("form filling + evidence check")),
        Task("Find the answer in the guides", "गाइड में उत्तर ढूँढना", 15, listOf("guide search + grounded answer")),
        Task("Copy the MCP card", "MCP कार्ड नकल करना", 10, listOf("card text reading + extraction")),
        Task("Register a new person", "नया व्यक्ति दर्ज करना", 8, listOf("add person from photo")),
        Task("Write the ANM message", "ANM संदेश लिखना", 5, listOf("ANM message")),
        Task("Prepare the family explanation", "परिवार के लिए समझाना तैयार करना", 5, listOf("family explanation")),
        Task("Enter new stock", "नया स्टॉक दर्ज करना", 3, listOf("medicine scan")),
        Task("Count the drug kit", "दवा किट गिनना", 5, listOf("SAM 3.1 count"))
    )
    data class Impact(val task: Task, val todayMs: Long, val todayCloud: Boolean?, val onlineMs: Long, val offlineMs: Long)

    /**
     * Rows for tasks with any measurement (today, or remembered from an earlier run in either mode).
     * [remembered]: feature → (last online ms, last offline ms), from [timesDecode].
     */
    fun impact(ticks: List<ArogyaAiTick>, remembered: Map<String, Pair<Long, Long>>): List<Impact> = tasks.mapNotNull { t ->
        val tick = ticks.lastOrNull { it.feature in t.keys }
        val mem = t.keys.mapNotNull { remembered[it] }
        val on = mem.maxOfOrNull { it.first } ?: 0L
        val off = mem.maxOfOrNull { it.second } ?: 0L
        if (tick == null && on == 0L && off == 0L) null
        else Impact(t, tick?.ms ?: 0L, tick?.cloud, on, off)
    }

    /** Last measured time per feature and mode, kept across demo runs: "feature|on|off;..." */
    fun timesEncode(map: Map<String, Pair<Long, Long>>): String =
        map.entries.joinToString(";") { (k, v) -> "${k.replace(";", ",").replace("|", "/")}|${v.first}|${v.second}" }

    fun timesDecode(s: String): Map<String, Pair<Long, Long>> = s.split(";").mapNotNull { e ->
        val p = e.split("|")
        if (p.size != 3) null else p[0] to ((p[1].toLongOrNull() ?: 0L) to (p[2].toLongOrNull() ?: 0L))
    }.toMap()

    /** [map] with [tick] recorded under its mode (only measured ticks, ms > 0). */
    fun timesRecord(map: Map<String, Pair<Long, Long>>, tick: ArogyaAiTick): Map<String, Pair<Long, Long>> {
        if (tick.ms <= 0) return map
        val cur = map[tick.feature] ?: (0L to 0L)
        return map + (tick.feature to if (tick.cloud) tick.ms to cur.second else cur.first to tick.ms)
    }

    /** Total hand minutes (estimate) for the rows shown. */
    fun handTotal(rows: List<Impact>): Int = rows.sumOf { it.task.handMin }

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
            Triple(if (hindi) "परिवार को हिंदी में समझाना" else "Explain to the family in Hindi", paper, msOf("family explanation")),
            Triple(if (hindi) "नया व्यक्ति दर्ज करना" else "Register a new person", paper, msOf("add person from photo")),
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
