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
    /** Human recordings the owner provides (WAV, 16-bit PCM or float32, any rate, mono/stereo, <= 60 s). Workflow 2 only. */
    const val VOICE_EN = "demo/note_en.wav"
    const val VOICE_HI = "demo/note_hi.wav"

    /** Workflow 2 (HBNC day 7) note. Latin digits so numbers stay groundable. */
    const val NOTE_EN = "Baby of Neha, day 7 visit. Baby weight 2650 grams. Baby temperature 38.4 C. Baby is feeding less since morning and the cry is weak. " +
        "No pus on the cord, eyes are clean. Baby is kept warm near the mother. Mother has taken 4 full meals, changed 3 pads today, mother temperature 37 C. " +
        "Gave mother 30 IFA tablets and 60 calcium tablets."
    const val NOTE_HI = "नेहा के बच्चे की 7 दिन की विज़िट। बच्चे का वज़न 2650 ग्राम। बच्चे का तापमान 38.4 डिग्री। सुबह से बच्चा कम दूध पी रहा है और रोना कमज़ोर है। " +
        "नाल पर मवाद नहीं, आँखें साफ़ हैं। बच्चा माँ के पास गर्म रखा है। माँ ने 4 बार पूरा खाना खाया, आज 3 पैड बदले, माँ का तापमान 37 डिग्री। " +
        "माँ को 30 IFA गोलियाँ और 60 calcium गोलियाँ दीं।"

    /** Workflow 1: new pregnancy, first ANC home visit (MCP card ANC page). */
    const val NOTE_W1_EN = "Sarita Yadav, 10 weeks pregnant, first visit. No bleeding, no headache, no fever. " +
        "ANM checked at VHSND: weight 49 kg, BP 110/70, Hb 10.2. Gave 30 IFA tablets and 60 calcium tablets. Next check-up at 14 weeks."
    const val NOTE_W1_HI = "सरिता यादव, 10 हफ़्ते की गर्भवती, पहली विज़िट। खून नहीं, सिरदर्द नहीं, बुखार नहीं। " +
        "VHSND पर ANM ने जाँचा: वज़न 49 kg, BP 110/70, Hb 10.2। 30 IFA गोलियाँ और 60 calcium गोलियाँ दीं। अगली जाँच 14 हफ़्ते पर।"

    /** Workflow 3: sick child with diarrhoea (sick-child form; ORS and zinc leave the kit). */
    const val NOTE_W3_EN = "Child Rohan, 14 months. Loose stools 4 times since yesterday. Drinking well, no blood in stool, not lethargic, no vomiting, no fits. " +
        "Temperature 37.2. Breathing normal. Gave 4 ORS packets and 14 zinc tablets. Not referred. Follow up in 2 days."
    const val NOTE_W3_HI = "बच्चा रोहन, 14 महीने। कल से 4 बार दस्त। ठीक से पी रहा है, मल में खून नहीं, सुस्त नहीं, उल्टी नहीं, दौरे नहीं। " +
        "तापमान 37.2। साँस सामान्य। 4 ORS पैकेट और 14 zinc गोलियाँ दीं। रेफ़र नहीं किया। 2 दिन बाद फिर देखना।"

    /** Workflow 1 person (the registration slip photo carries exactly these details; used typed if the photo is skipped). */
    const val W1_NAME = "Sarita Yadav"
    const val W1_RELATIVE = "Ramesh Yadav"
    const val W1_AGE = "23"
    const val W1_PHONE = "9000000301"
    const val W1_HOUSE = "H-051"
    const val W1_WEEKS = 10L
    /** Workflow 3 person (in the sample village). */
    const val W3_NAME = "Child Rohan"

    /** The slide example (Integrated ASHA Training Module Part B, p.18): answered in both modes. */
    const val QUESTION_EN = "What should be applied on the baby's cord stump?"
    const val QUESTION_HI = "बच्चे की नाल पर क्या लगाना चाहिए?"
    /** The one follow-up on the Ask SOP scene; the runtime session remembers the first question. */
    const val FOLLOWUP_EN = "How often should the cord be checked?"
    const val FOLLOWUP_HI = "नाल की जाँच कितनी बार करनी चाहिए?"
    /** Part D3 gold scene: Hinglish + Hindi paraphrases, answered live (designed for VA-005, Part B p.19). */
    const val GOLD_EN = "baby ko pehla gaadha doodh dena chahiye?"
    const val GOLD_HI = "क्या बच्चे को पहला गाढ़ा दूध देना चाहिए?"

    /**
     * One demo screen. [aim]: what this screen of the app is for (shown first, as "Aim");
     * [narration]: how it is done, read aloud sentence by sentence; [screen]: the app-screen heading.
     */
    data class Scene(val id: String, val title: String, val hi: String, val narration: List<String>, val narrationHi: List<String>,
                     val screen: String = "", val screenHi: String = "", val aim: String = "", val aimHi: String = "")

    /** A complete workflow the presenter picks on the demo home screen. */
    data class Workflow(val id: Int, val title: String, val hi: String, val line: String, val lineHi: String, val scenes: List<Scene>)

    private fun meet(story: String, storyHi: String) = Scene("meet", "Meet Sunita, ASHA of Rampur", "मिलिए सुनीता से, रामपुर की आशा",
        listOf("Sunita is the ASHA of Rampur, about 1,000 people.", story, "Pramaan does the paperwork with AI on her phone, and she confirms every result."),
        listOf("सुनीता रामपुर की आशा हैं, लगभग 1,000 लोग।", storyHi, "Pramaan यह काग़ज़ी काम फ़ोन पर AI से करता है, और हर नतीजा सुनीता पक्का करती हैं।"),
        "Introduction", "परिचय", "Show who uses Pramaan and what this visit needs.", "दिखाना कि Pramaan कौन चलाता है और इस विज़िट में क्या चाहिए।")

    private fun due(who: String, whoHi: String) = Scene("due", "Plan the day", "दिन की योजना",
        listOf("Every morning the app lists who is due, and why.", "Dates come from fixed government schedules, never from AI.", who),
        listOf("हर सुबह ऐप बताता है कि किसकी विज़िट बाकी है, और क्यों।", "तारीख़ें सरकार के तय शेड्यूल से आती हैं, AI से कभी नहीं।", whoHi),
        "Home · Today's plan", "होम · आज की योजना", "Plan today's visits from the official schedules.", "सरकारी शेड्यूल से आज की विज़िट तय करना।")

    private fun records(what: String, whatHi: String) = Scene("records", "Everything updated", "सब अपडेट",
        listOf("One save updated every record that needs this visit.", what, "Nothing is sent anywhere; the records stay encrypted on her phone."),
        listOf("एक सेव से इस विज़िट से जुड़ा हर रिकॉर्ड अपडेट हुआ।", whatHi, "कहीं कुछ नहीं भेजा गया; रिकॉर्ड उनके फ़ोन पर सुरक्षित (एन्क्रिप्टेड) हैं।"),
        "Registers · Today", "रजिस्टर · आज", "Show every form, card, register and stock line this visit changed.", "दिखाना कि इस विज़िट से कौन-कौन से फ़ॉर्म, कार्ड, रजिस्टर और स्टॉक बदले।")

    private val impact = Scene("beforeafter", "How AI eased the visit", "AI ने विज़िट कैसे आसान की",
        listOf("By hand, this paperwork takes many minutes on every visit.", "The table shows what AI did and how long it took today.", "By-hand times are estimates; AI times are measured."),
        listOf("हाथ से यह काग़ज़ी काम हर विज़िट में कई मिनट लेता है।", "तालिका बताती है कि आज AI ने क्या किया और कितना समय लगा।", "हाथ वाले समय अनुमान हैं; AI वाले समय नापे गए।"),
        "Impact", "असर", "Show the time saved, honestly measured.", "बचा समय दिखाना, ईमानदारी से नापा हुआ।")

    val workflow1 = Workflow(1, "New pregnancy: register and first ANC visit", "नई गर्भावस्था: पंजीकरण और पहली ANC विज़िट",
        "Slip photo → person added → voice note → MCP card ANC page, IFA and calcium from the kit", "पर्ची की फ़ोटो → नया व्यक्ति → वॉइस नोट → MCP कार्ड ANC पेज, किट से IFA और कैल्शियम", listOf(
        meet("Today a young woman, Sarita, has just found out she is pregnant.", "आज एक युवती, सरिता, को अभी पता चला है कि वह गर्भवती है।"),
        due("Sarita is new, so first she must be registered.", "सरिता नई हैं, इसलिए पहले उनका पंजीकरण होगा।"),
        Scene("addperson", "Register Sarita from a photo", "फ़ोटो से सरिता का पंजीकरण",
            listOf("Sunita photographs Sarita's registration slip.", "The phone reads the name, husband, age, mobile, house and LMP; any Aadhaar number is removed.", "She checks the details, and Sarita joins the village and pregnancy registers."),
            listOf("सुनीता सरिता की पंजीकरण पर्ची की फ़ोटो लेती हैं।", "फ़ोन नाम, पति, उम्र, मोबाइल, मकान और LMP पढ़ता है; आधार नंबर हटा दिया जाता है।", "वे जानकारी जाँचती हैं, और सरिता गाँव और गर्भवती रजिस्टर में जुड़ जाती हैं।"),
            "Register · Add person", "रजिस्टर · नया व्यक्ति", "Register a new pregnant woman without copying by hand.", "बिना हाथ से नकल किए नई गर्भवती का पंजीकरण।"),
        Scene("form", "The right form opens", "सही फ़ॉर्म खुलता है",
            listOf("From her LMP the app knows she is in the first trimester.", "So it opens the MCP card ANC page, first visit.", "This choice is a fixed rule, not AI."),
            listOf("LMP से ऐप जानता है कि वह पहली तिमाही में हैं।", "इसलिए वह MCP कार्ड का ANC पेज, पहली विज़िट, खोलता है।", "यह चुनाव तय नियम है, AI नहीं।"),
            "Visit · Official form", "विज़िट · सरकारी फ़ॉर्म", "Open the official form for this stage of pregnancy.", "गर्भावस्था के इस चरण का सरकारी फ़ॉर्म खोलना।"),
        Scene("voice", "Speak once, the form fills", "एक बार बोलें, फ़ॉर्म भरे",
            listOf("Sunita says what happened in one short note.", "AI puts each fact in its row and shows the words it came from.", "Numbers such as weight, BP, Hb and the tablets given must appear in her words."),
            listOf("सुनीता एक छोटे नोट में बताती हैं कि क्या हुआ।", "AI हर बात उसकी पंक्ति में रखता है और दिखाता है कि वह किन शब्दों से आई।", "वज़न, BP, Hb और दी गई गोलियाँ जैसे अंक उनके शब्दों में होने ज़रूरी हैं।"),
            "Visit · Voice note", "विज़िट · वॉइस नोट", "Fill the ANC page from her spoken note, with evidence.", "बोले गए नोट से ANC पेज भरना, सबूत के साथ।"),
        Scene("saved", "One save updates everything", "एक सेव, सब अपडेट",
            listOf("Sunita saves once.", "The ANC page, the pregnancy register, the stock, the diary and the next due date update together.", "The IFA and calcium she gave leave the drug kit at the same moment."),
            listOf("सुनीता एक बार सेव करती हैं।", "ANC पेज, गर्भवती रजिस्टर, स्टॉक, डायरी और अगली तारीख़ एक साथ अपडेट होते हैं।", "दी गई IFA और कैल्शियम उसी पल दवा किट से घट जाती हैं।"),
            "Visit · Save", "विज़िट · सेव", "Write the visit into every record in one step.", "एक कदम में विज़िट हर रिकॉर्ड में लिखना।"),
        Scene("mcpcard", "Sarita's official MCP card", "सरिता का आधिकारिक MCP कार्ड",
            listOf("This is Sarita's official MCP card, in the printed card's layout.", "Her details fill page 3 and today's ANC visit fills page 5.", "Today's changes are highlighted in yellow."),
            listOf("यह सरिता का आधिकारिक MCP कार्ड है, छपे कार्ड जैसा।", "उनकी जानकारी पेज 3 पर और आज की ANC विज़िट पेज 5 पर भरी है।", "आज के बदलाव पीले रंग में हैं।"),
            "MCP card · Official", "MCP कार्ड · आधिकारिक", "Show the official card filled from this visit.", "इस विज़िट से भरा आधिकारिक कार्ड दिखाना।"),
        records("Her ANC check-up and Td are now on tomorrow's plan.", "उनकी ANC जाँच और Td अब आगे की योजना में हैं।"),
        impact))

    val workflow2 = Workflow(2, "Newborn day-7 visit with a danger sign", "नवजात की 7वें दिन की विज़िट, खतरे के लक्षण के साथ",
        "Card photo → voice note → danger rule → referral → guide answer → HBNC form and MCP card", "कार्ड फ़ोटो → वॉइस नोट → खतरे का नियम → रेफ़रल → गाइड उत्तर → HBNC फ़ॉर्म और MCP कार्ड", listOf(
        meet("Today, Neha's baby, born at home, is due for the day-7 newborn visit.", "आज नेहा के बच्चे की, जो घर पर जन्मा, 7वें दिन की नवजात विज़िट है।"),
        due("Baby of Neha is due for the day-7 visit.", "नेहा के बच्चे की 7वें दिन की विज़िट बाकी है।"),
        Scene("card", "Read the family's MCP card", "परिवार का MCP कार्ड पढ़ें",
            listOf("The ANM wrote the birth vaccines on the family's card.", "Sunita photographs the card; the phone reads the vaccines and dates.", "She ticks what is correct, and the app's card catches up."),
            listOf("ANM ने जन्म के टीके परिवार के कार्ड पर लिखे।", "सुनीता कार्ड की फ़ोटो लेती हैं; फ़ोन टीके और तारीख़ें पढ़ता है।", "वे सही बातों पर निशान लगाती हैं, और ऐप का कार्ड अपडेट हो जाता है।"),
            "MCP card · Photo", "MCP कार्ड · फ़ोटो", "Bring the ANM's entries into the app without retyping.", "ANM की लिखी बातें बिना दोबारा लिखे ऐप में लाना।"),
        Scene("voice", "Speak once, the form fills", "एक बार बोलें, फ़ॉर्म भरे",
            listOf("Sunita says what she saw in one note.", "AI fills the day-7 column of the HBNC form, with the words each value came from.", "The medicines she gave the mother are read from the same note."),
            listOf("सुनीता एक नोट में बताती हैं कि उन्होंने क्या देखा।", "AI HBNC फ़ॉर्म का 7वें दिन का कॉलम भरता है, हर मान के शब्दों के साथ।", "माँ को दी गई दवाइयाँ भी उसी नोट से पढ़ी जाती हैं।"),
            "Visit · Voice note", "विज़िट · वॉइस नोट", "Fill the HBNC form from her spoken note, with evidence.", "बोले गए नोट से HBNC फ़ॉर्म भरना, सबूत के साथ।"),
        Scene("danger", "Danger sign: refer today", "खतरे का लक्षण: आज रेफ़र",
            listOf("A temperature of 38.4 and poor feeding are danger signs.", "Fixed government rules flag them before any AI runs.", "The app offers 108 and the ANM, and drafts the message."),
            listOf("38.4 का तापमान और कम दूध पीना खतरे के लक्षण हैं।", "सरकार के तय नियम किसी भी AI से पहले इन्हें पकड़ते हैं।", "ऐप 108 और ANM का विकल्प देता है, और संदेश तैयार करता है।"),
            "Visit · Danger signs", "विज़िट · खतरे के लक्षण", "Catch danger signs by fixed rules and refer at once.", "तय नियमों से खतरे के लक्षण पकड़ना और तुरंत रेफ़र करना।"),
        Scene("explain", "Explain to the family", "परिवार को समझाना",
            listOf("The family must understand why the baby goes to the PHC today.", "AI rewrites the guide's advice in simple Hindi, adding nothing new.", "Sunita can read it aloud, offline."),
            listOf("परिवार को समझना चाहिए कि बच्चा आज PHC क्यों जा रहा है।", "AI गाइड की सलाह को सरल हिंदी में लिखता है, कुछ नया नहीं जोड़ता।", "सुनीता इसे ऑफ़लाइन पढ़कर सुना सकती हैं।"),
            "Visit · Explain", "विज़िट · समझाना", "Explain the advice to the family in their language.", "सलाह परिवार की भाषा में समझाना।"),
        Scene("sops", "Ask the guide", "गाइड से पूछें",
            listOf("Neha asks what to put on the cord.", "Sunita asks the official guides on her phone, in Hindi or English.", "The answer cites the exact page, and opens the PDF in one tap."),
            listOf("नेहा पूछती हैं कि नाल पर क्या लगाएँ।", "सुनीता फ़ोन पर सरकारी गाइड से हिंदी या अंग्रेज़ी में पूछती हैं।", "उत्तर सही पेज का हवाला देता है, और एक टैप में PDF खोलता है।"),
            "Ask · Official guides", "पूछें · सरकारी गाइड", "Answer the family's question from official guides, with the source.", "परिवार के सवाल का जवाब सरकारी गाइड से, स्रोत के साथ।"),
        Scene("saved", "One save updates everything", "एक सेव, सब अपडेट",
            listOf("Sunita saves once.", "The HBNC form, the baby's and mother's MCP card, the growth chart, the referral and the stock update together.", "The IFA and calcium for the mother leave the drug kit."),
            listOf("सुनीता एक बार सेव करती हैं।", "HBNC फ़ॉर्म, बच्चे और माँ का MCP कार्ड, वज़न चार्ट, रेफ़रल और स्टॉक एक साथ अपडेट होते हैं।", "माँ के लिए IFA और कैल्शियम दवा किट से घटते हैं।"),
            "Visit · Save", "विज़िट · सेव", "Write the visit into every record in one step.", "एक कदम में विज़िट हर रिकॉर्ड में लिखना।"),
        Scene("mcpcard", "Neha's official MCP card", "नेहा का आधिकारिक MCP कार्ड",
            listOf("This is the official MCP card, in the printed card's layout.", "Today's visit filled the 7th Day column, the vaccines and the growth chart.", "Today's changes are highlighted in yellow."),
            listOf("यह आधिकारिक MCP कार्ड है, छपे कार्ड जैसा।", "आज की विज़िट से 7वें दिन का कॉलम, टीके और वज़न चार्ट भरे।", "आज के बदलाव पीले रंग में हैं।"),
            "MCP card · Official", "MCP कार्ड · आधिकारिक", "Show the official card filled from this visit.", "इस विज़िट से भरा आधिकारिक कार्ड दिखाना।"),
        records("The referral is recorded, and the day-14 visit is on the plan.", "रेफ़रल दर्ज है, और 14वें दिन की विज़िट योजना में है।"),
        Scene("aitable", "AI tools behind this visit", "इस विज़िट के पीछे AI टूल",
            listOf("Each feature has a tool on the phone and a tool in the cloud.", "Meta's models are marked in blue.", "Danger signs stay fixed rules."),
            listOf("हर सुविधा का एक टूल फ़ोन पर और एक क्लाउड में है।", "Meta के मॉडल नीले रंग में हैं।", "खतरे के लक्षण तय नियम ही रहते हैं।"),
            "AI summary", "AI सारांश", "Show which AI tool did what, offline and online.", "दिखाना कि कौन सा AI टूल क्या करता है, ऑफ़लाइन और ऑनलाइन।"),
        impact))

    val workflow3 = Workflow(3, "Sick child: medicines and the drug kit", "बीमार बच्चा: दवाइयाँ और दवा किट",
        "Voice note → sick-child form → ORS and zinc leave the kit → refill request → new strip scanned", "वॉइस नोट → बीमार-बच्चा फ़ॉर्म → किट से ORS और ज़िंक → रिफिल अनुरोध → नई पट्टी स्कैन", listOf(
        meet("Today, little Rohan has loose stools and his mother has called Sunita.", "आज छोटे रोहन को दस्त हैं और उसकी माँ ने सुनीता को बुलाया है।"),
        due("Rohan's visit is added as a sick-child visit.", "रोहन की विज़िट बीमार-बच्चा विज़िट के रूप में जुड़ती है।"),
        Scene("voice", "Speak once, the form fills", "एक बार बोलें, फ़ॉर्म भरे",
            listOf("Sunita checks the danger signs and says what she found.", "AI fills the sick-child form and shows the words behind each value.", "The ORS and zinc she gave are rows on the same form."),
            listOf("सुनीता खतरे के लक्षण जाँचती हैं और बताती हैं कि क्या मिला।", "AI बीमार-बच्चा फ़ॉर्म भरता है और हर मान के पीछे के शब्द दिखाता है।", "दिए गए ORS और ज़िंक उसी फ़ॉर्म की पंक्तियाँ हैं।"),
            "Visit · Voice note", "विज़िट · वॉइस नोट", "Fill the sick-child form from her spoken note, with evidence.", "बोले गए नोट से बीमार-बच्चा फ़ॉर्म भरना, सबूत के साथ।"),
        Scene("saved", "Save: medicines leave the kit", "सेव: दवाइयाँ किट से घटीं",
            listOf("Sunita saves once.", "The form, the follow-up date and the stock register update together.", "Zinc has now run out."),
            listOf("सुनीता एक बार सेव करती हैं।", "फ़ॉर्म, अगली तारीख़ और स्टॉक रजिस्टर एक साथ अपडेट होते हैं।", "ज़िंक अब ख़त्म हो गया है।"),
            "Visit · Save", "विज़िट · सेव", "Record the visit and take the medicines out of the kit.", "विज़िट दर्ज करना और दवाइयाँ किट से घटाना।"),
        Scene("refill", "Ask the ANM for a refill", "ANM से रिफिल माँगें",
            listOf("The app sees which kit items are low.", "It writes the refill request with exact balances.", "Sunita sends it to the ANM in one tap."),
            listOf("ऐप देखता है कि किट की कौन सी दवाइयाँ कम हैं।", "वह सही बचे हुए नंबरों के साथ रिफिल अनुरोध लिखता है।", "सुनीता एक टैप में इसे ANM को भेजती हैं।"),
            "Drug kit · Refill", "दवा किट · रिफिल", "Ask for a refill before the kit runs dry.", "किट ख़ाली होने से पहले रिफिल माँगना।"),
        Scene("medscan", "New stock: scan and count", "नया स्टॉक: स्कैन और गिनती",
            listOf("The refill arrives at the sub-centre.", "Sunita photographs the new strip; the phone reads name, batch and expiry, and it goes into stock.", "Online, Meta SAM 3.1 helps count sachets; offline she counts by hand."),
            listOf("रिफिल उप-केंद्र पर पहुँचता है।", "सुनीता नई पट्टी की फ़ोटो लेती हैं; फ़ोन नाम, बैच और एक्सपायरी पढ़ता है, और वह स्टॉक में जुड़ती है।", "ऑनलाइन, Meta SAM 3.1 पैकेट गिनने में मदद करता है; ऑफ़लाइन वे हाथ से गिनती हैं।"),
            "Drug kit · Receive", "दवा किट · प्राप्ति", "Add new stock from a photo, with batch and expiry.", "फ़ोटो से नया स्टॉक जोड़ना, बैच और एक्सपायरी के साथ।"),
        records("The stock register shows every tablet in and out.", "स्टॉक रजिस्टर हर गोली का आना-जाना दिखाता है।"),
        impact))

    val workflows = listOf(workflow1, workflow2, workflow3)

    /** The scenes of workflow [wf] (1–3); anything else falls back to workflow 2. */
    fun flow(wf: Int): List<Scene> = (workflows.firstOrNull { it.id == wf } ?: workflow2).scenes

    /** Backwards-compatible: the original hero workflow (newborn day 7). */
    val scenes: List<Scene> get() = workflow2.scenes

    /** The typed note for workflow [wf] in the chosen language. */
    fun note(wf: Int, hindi: Boolean): String = when (wf) {
        1 -> if (hindi) NOTE_W1_HI else NOTE_W1_EN
        3 -> if (hindi) NOTE_W3_HI else NOTE_W3_EN
        else -> if (hindi) NOTE_HI else NOTE_EN
    }

    private val GIVE_WORDS = Regex("(?i)\\b(gave|given|give|issued)\\b|दी|दिए|दिया|दीं")

    /**
     * Medicines handed over, read by fixed rules from the sentences of [note] that say "gave":
     * each "N <kit item>" in those sentences. Used where the form has no medicine rows (HBNC).
     */
    fun medicinesGiven(note: String, catalog: List<String>): List<VoiceStockLine> =
        note.split(Regex("[.।\\n]")).filter { GIVE_WORDS.containsMatchIn(it) }
            .flatMap { ArogyaVoiceStock.parseRules(it, catalog) }
            .map { it.copy(op = "give") }.distinctBy { it.item }
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
