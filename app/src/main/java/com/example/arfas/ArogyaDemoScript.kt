package com.example.arfas

/*
 * Part B (presenter-controlled demo): the script as pure data plus the small pure
 * helpers the host tests check — navigation bounds, the AI-steps table and the
 * before/after table. No Android or Compose imports.
 *
 * Demo v2: three workflows (11/12/11 screens), picked on the demo home screen. Every screen has an
 * aim and three short sentences in English and Hindi.
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
    const val NOTE_W1_EN = "Sarita Yadav, 10 weeks pregnant, first pregnancy, first visit. Pregnancy confirmed with the test; used 1 pregnancy test kit. " +
        "Complains of mild nausea. No bleeding, no headache, no fever. " +
        "ANM checked at VHSND: weight 49 kg, height 152 cm, pulse 82, BP 110/70, Hb 10.2. No pallor, no swelling. " +
        "Urine albumin nil, urine sugar nil. Blood group B positive. HIV test done. ANM gave Td-1 injection. " +
        "Delivery planned at CHC Mishrikh. IFA and calcium start from the next visit, in the second trimester. Next check-up at 14 weeks."
    const val NOTE_W1_HI = "सरिता यादव, 10 हफ़्ते की गर्भवती, पहली गर्भावस्था, पहली विज़िट। टेस्ट से गर्भ पक्का किया; 1 pregnancy test kit इस्तेमाल की। " +
        "शिकायत: हल्की मतली। खून नहीं, सिरदर्द नहीं, बुखार नहीं। " +
        "VHSND पर ANM ने जाँचा: वज़न 49 kg, ऊँचाई 152 cm, नब्ज़ 82, BP 110/70, Hb 10.2। पीलापन नहीं, सूजन नहीं। " +
        "पेशाब में एल्बुमिन नहीं, पेशाब में शुगर नहीं। ब्लड ग्रुप B positive। HIV जाँच हुई। ANM ने Td-1 इंजेक्शन दिया। " +
        "प्रसव CHC मिश्रिख में होगा। IFA और calcium अगली विज़िट से, दूसरी तिमाही में शुरू होंगे। अगली जाँच 14 हफ़्ते पर।"

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

    private fun sc(id: String, title: String, hi: String, en: List<String>, hiN: List<String>, screen: String, screenHi: String, aim: String, aimHi: String) =
        Scene(id, title, hi, en, hiN, screen, screenHi, aim, aimHi)

    /** Screen 1 of every workflow: who Sunita is + today's plan (fixed schedules). */
    private fun plan(story: String, storyHi: String) = sc("plan", "Meet Sunita and today's plan", "मिलिए सुनीता से, और आज की योजना",
        listOf("Sunita is the ASHA for Rampur, a village of about 1,000 people.", story, "These visits follow the fixed schedules in the government guidelines."),
        listOf("सुनीता रामपुर गाँव की आशा हैं, जहाँ लगभग 1,000 लोग रहते हैं।", storyHi, "ये विज़िट सरकारी दिशानिर्देशों के तय शेड्यूल के अनुसार होती हैं।"),
        "Home · Today's plan", "होम · आज की योजना", "Introduce the ASHA and plan today's visits.", "आशा का परिचय और आज की विज़िट की योजना।")

    private fun formOpens(what: String, whatHi: String) = sc("form", "The right form opens", "सही फ़ॉर्म खुलता है",
        listOf(what, "The app opens the official form for this visit, with every row as printed.", "This choice is a fixed rule, not AI."),
        listOf(whatHi, "ऐप इस विज़िट का सरकारी फ़ॉर्म खोलता है, हर पंक्ति छपे फ़ॉर्म जैसी।", "यह चुनाव तय नियम है, AI नहीं।"),
        "Visit · Official form", "विज़िट · सरकारी फ़ॉर्म", "Open the official form for this visit.", "इस विज़िट का सरकारी फ़ॉर्म खोलना।")

    private fun voice(form: String, formHi: String) = sc("voice", "Speak once, the form fills", "एक बार बोलें, फ़ॉर्म भरे",
        listOf("Sunita says what happened in one short note.", "AI fills the $form and shows the words each value came from; fixed rules catch numbers the AI misses.", "Sunita checks the values and confirms them."),
        listOf("सुनीता एक छोटे नोट में बताती हैं कि क्या हुआ।", "AI $formHi भरता है और हर मान के शब्द दिखाता है; जो अंक AI छोड़े, तय नियम पकड़ते हैं।", "सुनीता मान जाँचकर पक्का करती हैं।"),
        "Visit · Voice note", "विज़िट · वॉइस नोट", "Fill the official form from her spoken note, with evidence.", "बोले गए नोट से सरकारी फ़ॉर्म भरना, सबूत के साथ।")

    private fun danger(en: String, hi: String) = sc("danger", "Danger check", "खतरे की जाँच",
        listOf("Before anything else, the app checks for danger signs.", "It uses the fixed danger rules from the government guides, never AI.", en),
        listOf("सबसे पहले ऐप खतरे के लक्षण जाँचता है।", "इसके लिए सरकारी गाइड के तय नियम हैं, AI कभी नहीं।", hi),
        "Visit · Danger signs", "विज़िट · खतरे के लक्षण", "Catch every danger sign by fixed rules and act on it.", "तय नियमों से हर खतरे का लक्षण पकड़ना और कदम उठाना।")

    private fun ask(en: String, hi: String) = sc("sops", "Ask the official guides", "सरकारी गाइड से पूछें",
        listOf(en, "The answer comes only from the official guides on her phone, with the exact page.", "She can ask a follow-up, and open the PDF at that page in one tap."),
        listOf(hi, "उत्तर सिर्फ़ फ़ोन में रखी सरकारी गाइड से आता है, सही पेज के साथ।", "वे दोबारा सवाल पूछ सकती हैं, और एक टैप में उसी पेज पर PDF खोल सकती हैं।"),
        "Ask · Official guides", "पूछें · सरकारी गाइड", "Answer questions from official guides, with the source.", "सरकारी गाइड से जवाब, स्रोत के साथ।")

    private fun explain(en: String, hi: String) = sc("explain", "Explain to the family", "परिवार को समझाना",
        listOf(en, "AI rewrites the advice in simple Hindi, adding nothing that is not in the source.", "Sunita can read it aloud, even offline."),
        listOf(hi, "AI सलाह को सरल हिंदी में लिखता है, स्रोत से बाहर कुछ नहीं जोड़ता।", "सुनीता इसे ऑफ़लाइन भी पढ़कर सुना सकती हैं।"),
        "Visit · Explain", "विज़िट · समझाना", "Help the family understand the advice in their language.", "परिवार को उनकी भाषा में सलाह समझाना।")

    private fun save(en: String, hi: String) = sc("saved", "One save updates everything", "एक सेव, सब अपडेट",
        listOf("Sunita saves once.", en, "Each updated record has an Open button, so it can be checked right away."),
        listOf("सुनीता एक बार सेव करती हैं।", hi, "हर अपडेट रिकॉर्ड के साथ खोलें बटन है, ताकि तुरंत जाँचा जा सके।"),
        "Visit · Save", "विज़िट · सेव", "Write the visit into every record in one step, and show the proof.", "एक कदम में विज़िट हर रिकॉर्ड में लिखना, और सबूत दिखाना।")

    private fun card(en: String, hi: String) = sc("mcpcard", "The official MCP card", "आधिकारिक MCP कार्ड",
        listOf("This is the official MCP card, in the printed card's layout, with the state, district and sub-centre.", en, "Today's changes are highlighted in yellow."),
        listOf("यह आधिकारिक MCP कार्ड है, छपे कार्ड जैसा, राज्य, ज़िला और उप-केंद्र के साथ।", hi, "आज के बदलाव पीले रंग में हैं।"),
        "MCP card · Official", "MCP कार्ड · आधिकारिक", "Show the official card filled from this visit.", "इस विज़िट से भरा आधिकारिक कार्ड दिखाना।")

    private val practice = sc("practice", "Three questions a day", "रोज़ तीन सवाल",
        listOf("At the end of the day, the app asks Sunita three short questions.", "AI makes them from the official guides, and each answer shows its source page.", "A few minutes a day keeps her knowledge fresh."),
        listOf("दिन के अंत में ऐप सुनीता से तीन छोटे सवाल पूछता है।", "AI इन्हें सरकारी गाइड से बनाता है, और हर जवाब का स्रोत पेज दिखता है।", "रोज़ कुछ मिनट से उनका ज्ञान ताज़ा रहता है।"),
        "Learn · Daily practice", "सीखें · रोज़ अभ्यास", "Keep the ASHA's knowledge fresh, from official guides.", "आशा का ज्ञान ताज़ा रखना, सरकारी गाइड से।")

    private val summary = sc("summary", "AI behind the visit, and time saved", "विज़िट के पीछे AI, और बचा समय",
        listOf("Each feature has a tool on the phone and a tool in the cloud; Meta's models are marked in blue.", "Danger signs, schedules and stock stay fixed rules.", "The time table shows what AI did today; by-hand times are estimates."),
        listOf("हर सुविधा का एक टूल फ़ोन पर और एक क्लाउड में है; Meta के मॉडल नीले रंग में हैं।", "खतरे के लक्षण, शेड्यूल और स्टॉक तय नियम ही रहते हैं।", "समय तालिका बताती है कि आज AI ने क्या किया; हाथ वाले समय अनुमान हैं।"),
        "AI summary · Impact", "AI सारांश · असर", "Show which AI did what, and the time it saved.", "दिखाना कि किस AI ने क्या किया, और कितना समय बचा।")

    val workflow1 = Workflow(1, "New pregnancy: register and first ANC visit", "नई गर्भावस्था: पंजीकरण और पहली ANC विज़िट",
        "Slip photo → registers → voice note → danger check → guide answer → family advice → MCP card, IFA and calcium from the kit",
        "पर्ची फ़ोटो → रजिस्टर → वॉइस नोट → खतरे की जाँच → गाइड उत्तर → परिवार को सलाह → MCP कार्ड, किट से IFA और कैल्शियम", listOf(
        plan("Today a young woman, Sarita, has found out she is pregnant, so she must be registered first.", "आज एक युवती, सरिता, को पता चला है कि वह गर्भवती है, इसलिए पहले उनका पंजीकरण होगा।"),
        sc("addperson", "Register Sarita from a photo", "फ़ोटो से सरिता का पंजीकरण",
            listOf("Sunita photographs Sarita's registration slip.", "The phone reads every column: name, husband, age, mobile, house, village, category and LMP; any Aadhaar number is removed.", "She checks the details, saves, and opens the registers to see Sarita added."),
            listOf("सुनीता सरिता की पंजीकरण पर्ची की फ़ोटो लेती हैं।", "फ़ोन हर कॉलम पढ़ता है: नाम, पति, उम्र, मोबाइल, मकान, गाँव, श्रेणी और LMP; आधार नंबर हटा दिया जाता है।", "वे जानकारी जाँचकर सेव करती हैं, और रजिस्टर खोलकर देखती हैं कि सरिता जुड़ गईं।"),
            "Register · Add person", "रजिस्टर · नया व्यक्ति", "Register a new pregnant woman without copying by hand.", "बिना हाथ से नकल किए नई गर्भवती का पंजीकरण।"),
        formOpens("From her LMP the app knows she is in the first trimester: MCP card ANC page, visit 1.", "LMP से ऐप जानता है कि वह पहली तिमाही में हैं: MCP कार्ड ANC पेज, विज़िट 1।"),
        voice("ANC page", "ANC पेज"),
        danger("For Sarita, the rules find no danger sign today.", "सरिता के लिए आज नियमों को कोई खतरे का लक्षण नहीं मिला।"),
        ask("Sunita asks the guides which danger signs in pregnancy need urgent referral.", "सुनीता गाइड से पूछती हैं कि गर्भावस्था के कौन से खतरे के लक्षणों में तुरंत रेफ़र करना है।"),
        explain("Sarita's family must know these danger signs and when to go to the hospital.", "सरिता के परिवार को ये खतरे के लक्षण और अस्पताल कब जाना है, पता होना चाहिए।"),
        save("The ANC page, the MCP card, the pregnancy register, the stock and the next due date update together.", "ANC पेज, MCP कार्ड, गर्भवती रजिस्टर, स्टॉक और अगली तारीख़ एक साथ अपडेट होते हैं।"),
        card("Her details fill page 3, and today's visit fills page 5.", "उनकी जानकारी पेज 3 पर, और आज की विज़िट पेज 5 पर भरी है।"),
        practice, summary))

    val workflow2 = Workflow(2, "Newborn day-7 visit with a danger sign", "नवजात की 7वें दिन की विज़िट, खतरे के लक्षण के साथ",
        "Card photo → voice note → danger rules → ANM WhatsApp → guide answer → family advice → HBNC form, MCP card and stock",
        "कार्ड फ़ोटो → वॉइस नोट → खतरे के नियम → ANM को WhatsApp → गाइड उत्तर → परिवार को सलाह → HBNC फ़ॉर्म, MCP कार्ड और स्टॉक", listOf(
        plan("Today, Neha's baby, born at home, is due for the day-7 newborn visit.", "आज नेहा के बच्चे की, जो घर पर जन्मा, 7वें दिन की नवजात विज़िट है।"),
        sc("card", "Read the family's MCP card", "परिवार का MCP कार्ड पढ़ें",
            listOf("The ANM wrote the birth vaccines on the family's card.", "Sunita photographs the card; the phone reads the vaccines and dates.", "She ticks what is correct, and opens the card to see it updated."),
            listOf("ANM ने जन्म के टीके परिवार के कार्ड पर लिखे।", "सुनीता कार्ड की फ़ोटो लेती हैं; फ़ोन टीके और तारीख़ें पढ़ता है।", "वे सही बातों पर निशान लगाती हैं, और कार्ड खोलकर अपडेट देखती हैं।"),
            "MCP card · Photo", "MCP कार्ड · फ़ोटो", "Bring the ANM's entries into the app without retyping.", "ANM की लिखी बातें बिना दोबारा लिखे ऐप में लाना।"),
        formOpens("The baby is 7 days old, so the app opens the home-visit form, Day 7 column.", "बच्चा 7 दिन का है, इसलिए ऐप गृह भ्रमण फ़ॉर्म का 7वें दिन का कॉलम खोलता है।"),
        voice("HBNC form", "HBNC फ़ॉर्म"),
        danger("A temperature of 38.4 and poor feeding are danger signs: refer today.", "38.4 का तापमान और कम दूध पीना खतरे के लक्षण हैं: आज ही रेफ़र करें।"),
        sc("anm", "Tell the ANM on WhatsApp", "ANM को WhatsApp पर बताएँ",
            listOf("The ANM must know about the referral at once.", "AI drafts the message from Sunita's own findings; names stay on the phone.", "She reads it, edits it if needed, and sends it on WhatsApp."),
            listOf("ANM को रेफ़रल के बारे में तुरंत पता होना चाहिए।", "AI सुनीता के अपने निष्कर्षों से संदेश लिखता है; नाम फ़ोन पर ही रहते हैं।", "वे इसे पढ़कर, ज़रूरत हो तो बदलकर, WhatsApp पर भेजती हैं।"),
            "Visit · Escalate", "विज़िट · सूचना", "Escalate the danger sign to the ANM in seconds.", "खतरे की सूचना सेकंडों में ANM तक पहुँचाना।"),
        ask("Neha asks what to put on the cord; Sunita asks the guides.", "नेहा पूछती हैं कि नाल पर क्या लगाएँ; सुनीता गाइड से पूछती हैं।"),
        explain("Neha's family must understand why the baby goes to the PHC today.", "नेहा के परिवार को समझना चाहिए कि बच्चा आज PHC क्यों जा रहा है।"),
        save("The HBNC form, the MCP card for baby and mother, the growth chart, the referral and the stock update together.", "HBNC फ़ॉर्म, बच्चे और माँ का MCP कार्ड, वज़न चार्ट, रेफ़रल और स्टॉक एक साथ अपडेट होते हैं।"),
        card("Today's visit filled the 7th Day column, the vaccines and the growth chart.", "आज की विज़िट से 7वें दिन का कॉलम, टीके और वज़न चार्ट भरे।"),
        practice, summary))

    val workflow3 = Workflow(3, "Sick child: medicines and the drug kit", "बीमार बच्चा: दवाइयाँ और दवा किट",
        "Voice note → danger rules → guide answer → advice for the mother → ORS and zinc leave the kit → refill request → new stock scanned",
        "वॉइस नोट → खतरे के नियम → गाइड उत्तर → माँ को सलाह → किट से ORS और ज़िंक → रिफिल अनुरोध → नया स्टॉक स्कैन", listOf(
        plan("Today, little Rohan has loose stools, and his mother has called Sunita.", "आज छोटे रोहन को दस्त हैं, और उसकी माँ ने सुनीता को बुलाया है।"),
        formOpens("Rohan is unwell, so the app opens the sick-child form.", "रोहन बीमार है, इसलिए ऐप बीमार-बच्चा फ़ॉर्म खोलता है।"),
        voice("sick-child form", "बीमार-बच्चा फ़ॉर्म"),
        danger("Rohan has no general danger sign, so he is treated at home and seen again in 2 days.", "रोहन में कोई सामान्य खतरे का लक्षण नहीं है, इसलिए घर पर इलाज और 2 दिन बाद फिर जाँच।"),
        ask("Sunita checks in the guides how to prepare ORS at home.", "सुनीता गाइड में देखती हैं कि घर पर ORS कैसे बनाएँ।"),
        explain("Rohan's mother must know how to prepare and give ORS at home.", "रोहन की माँ को पता होना चाहिए कि घर पर ORS कैसे बनाएँ और पिलाएँ।"),
        save("The form, the follow-up date and the stock register update together; zinc has now run out.", "फ़ॉर्म, अगली तारीख़ और स्टॉक रजिस्टर एक साथ अपडेट होते हैं; ज़िंक अब ख़त्म हो गया है।"),
        sc("refill", "Ask the ANM for a refill", "ANM से रिफिल माँगें",
            listOf("The app sees which kit items are out or low.", "It writes the refill request with exact balances, in a fixed format.", "Sunita sends it to the ANM on WhatsApp in one tap."),
            listOf("ऐप देखता है कि किट की कौन सी दवाइयाँ ख़त्म या कम हैं।", "वह सही बचे हुए नंबरों के साथ तय प्रारूप में रिफिल अनुरोध लिखता है।", "सुनीता एक टैप में इसे WhatsApp पर ANM को भेजती हैं।"),
            "Drug kit · Refill", "दवा किट · रिफिल", "Ask for a refill before the kit runs dry.", "किट ख़ाली होने से पहले रिफिल माँगना।"),
        sc("medscan", "New stock: scan and count", "नया स्टॉक: स्कैन और गिनती",
            listOf("The refill arrives at the sub-centre.", "Sunita photographs the new strip; the phone reads name, batch and expiry, and it goes into stock.", "Online, Meta SAM 3.1 helps count sachets; offline she counts by hand."),
            listOf("रिफिल उप-केंद्र पर पहुँचता है।", "सुनीता नई पट्टी की फ़ोटो लेती हैं; फ़ोन नाम, बैच और एक्सपायरी पढ़ता है, और वह स्टॉक में जुड़ती है।", "ऑनलाइन, Meta SAM 3.1 पैकेट गिनने में मदद करता है; ऑफ़लाइन वे हाथ से गिनती हैं।"),
            "Drug kit · Receive", "दवा किट · प्राप्ति", "Add new stock from a photo, with batch and expiry.", "फ़ोटो से नया स्टॉक जोड़ना, बैच और एक्सपायरी के साथ।"),
        practice, summary))

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

    /** The Ask SOP question and follow-up for each workflow. */
    fun question(wf: Int, hindi: Boolean): String = when (wf) {
        // Workflow questions are about procedures and referral from the official guides, never doses.
        1 -> if (hindi) "गर्भावस्था के कौन से खतरे के लक्षणों में तुरंत रेफ़र करना चाहिए?" else "Which danger signs in pregnancy need urgent referral?"
        3 -> if (hindi) "घर पर ORS कैसे बनाएँ?" else "How do I prepare ORS at home?"
        else -> if (hindi) QUESTION_HI else QUESTION_EN
    }
    fun followUp(wf: Int, hindi: Boolean): String = when (wf) {
        1 -> if (hindi) "ऐसा लक्षण दिखे तो आशा को क्या करना चाहिए?" else "What should the ASHA do if she finds one of these signs?"
        3 -> if (hindi) "दस्त वाले बच्चे को कब रेफ़र करना चाहिए?" else "When should a child with diarrhoea be referred?"
        else -> if (hindi) FOLLOWUP_HI else FOLLOWUP_EN
    }

    /**
     * Danger check for the demo, by fixed rules only: the danger-sign engine on her own words (newborn /
     * postnatal mother), then the official form's own action rules on the confirmed values. Never AI.
     */
    fun dangerLines(note: String, spec: FormSpec, values: Map<String, String>, visit: ArogyaVisit, hindi: Boolean): List<String> {
        val a = ArogyaDangerSigns.evaluate(note, visit)
        val who = { s: ArogyaDangerSigns.Subject -> when (s) { ArogyaDangerSigns.Subject.INFANT -> if (hindi) "बच्चा: " else "Baby: "; ArogyaDangerSigns.Subject.MOTHER -> if (hindi) "माँ: " else "Mother: "; else -> "" } }
        val rules = a.findings.map { f ->
            (if (f.level == ArogyaDangerSigns.Level.URGENT) "⚠ " else "• ") + who(f.subject) + (if (hindi) f.sign.hi else f.sign.en) +
                (if (f.evidence.isNotBlank()) " — “${f.evidence}”" else "")
        }
        val form = ArogyaOfficialForms.dangers(spec, values).map { "• $it" }
        return (rules + form).distinct()
    }

    /** Rows the danger rules check on this form (shown when nothing is found, so "none" is visibly a checked result). */
    fun dangerChecked(spec: FormSpec, hindi: Boolean): List<String> =
        spec.fields.filter { it.dangerIf.isNotBlank() }.map { if (hindi) it.hi else it.label }.take(8)

    /** The fixed referral advice the family explanation starts from when danger signs were found (rules, not AI). */
    fun referralAdvice(dangers: List<String>): String =
        if (dangers.isEmpty()) "" else "Danger signs found: " + dangers.joinToString("; ") { it.removePrefix("⚠ ").removePrefix("• ") } +
            ". Refer now: take the baby to the PHC today. Keep the baby warm and keep breastfeeding on the way."

    /** A test kit used from the ASHA's drug kit ("used 1 pregnancy test kit") also leaves the stock; "used pads" does not. */
    private val USED_KIT = Regex("(?i)\\bused\\b[^.;]*\\bkits?\\b|kit[^।;]*इस्तेमाल")
    private val GIVE_WORDS = Regex("(?i)\\b(gave|given|give|issued)\\b|दी|दिए|दिया|दीं")

    /**
     * Medicines handed over, read by fixed rules from the sentences of [note] that say "gave":
     * each "N <kit item>" in those sentences. Used where the form has no medicine rows (HBNC).
     */
    fun medicinesGiven(note: String, catalog: List<String>): List<VoiceStockLine> =
        note.split(Regex("(?<!\\d)\\.|\\.(?!\\d)|[।\\n]")).flatMap { it.split(";") }.filter { GIVE_WORDS.containsMatchIn(it) || USED_KIT.containsMatchIn(it) }
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
