package com.example.arfas

/**
 * A small set of checked facts for the questions ASHA workers ask most often, given to the phone's
 * model in plain chat (no knowledge base selected). Tested with the real Llama 3.2 3B: on its own
 * it gave a wrong home ORS recipe ("1 tsp salt + ½ tsp sugar"), said "call 108 and give nothing"
 * for a child with diarrhoea, and wrote newborn fever as "100.4 °C". With these facts in the
 * system channel it uses the right numbers.
 *
 * Sources: WHO/UNICEF ORS and zinc guidance, India IMNCI / HBNC (Home Based Newborn Care) and
 * ASHA training modules, Anaemia Mukt Bharat, MoHFW first aid for dog and snake bites.
 * Only well-established numbers are included; where a dose depends on weight the facts say so
 * instead of giving one. Pure Kotlin: tested in PramaanFixesTest.
 */
object CoreHealthFacts {

    data class Topic(val id: String, val match: Regex, val facts: String, var hindi: String = "")

    private fun rx(p: String) = Regex("(?i)$p")

    val TOPICS: List<Topic> = listOf(
        Topic(
            "ors",
            rx("\\bors\\b|o\\.r\\.s|oral\\s+rehydration|jeevan\\s*rakshak|ओआरएस|ओ\\.आर\\.एस|घोल|namak.{0,20}(chini|cheeni|shakkar)|(chini|cheeni|shakkar).{0,20}namak|salt.{0,30}sugar|sugar.{0,30}salt|नमक.{0,20}चीनी|चीनी.{0,20}नमक"),
            "ORS: dissolve one ORS packet in 1 litre of clean drinking water; use it within 24 hours, then make fresh. " +
                "If no packet is available, home-made solution: 6 level teaspoons of sugar and half (½) a level teaspoon of salt in 1 litre of clean drinking water. " +
                "Give after each loose stool: child under 2 years 50–100 ml (¼ to ½ cup); child 2–10 years 100–200 ml (½ to 1 cup); older children and adults as much as they want. " +
                "Give small sips from a cup or spoon; if the child vomits, wait 10 minutes and then continue more slowly."
        ),
        Topic(
            "diarrhoea",
            rx("diarr?h(o)?ea|loose\\s+(stool|motion)|\\bdast\\b|dast\\s|patla\\s+(mal|potty|shauch)|दस्त|पतले\\s+दस्त|डायरिया|dehydrat|पानी\\s+की\\s+कमी"),
            "Diarrhoea in a child: give ORS after every loose stool and zinc for 14 days; continue breastfeeding and normal feeding, and give more fluids. " +
                "Zinc: age 2–6 months 10 mg (half a 20 mg tablet) once a day; age 6 months to 5 years 20 mg (one tablet) once a day, for 14 days even if the diarrhoea stops. " +
                "Do not give antibiotics or anti-diarrhoea medicines for ordinary watery diarrhoea. " +
                "Refer urgently (call 108, or 102 for a newborn) if the child: is not able to drink or breastfeed, vomits everything, has convulsions, is very sleepy or unconscious, " +
                "has sunken eyes or a skin pinch that goes back very slowly, has blood in the stool, has had diarrhoea for 14 days or more, or is younger than 2 months."
        ),
        Topic(
            "zinc",
            rx("\\bzinc\\b|जिंक|ज़िंक"),
            "Zinc for diarrhoea: age 2–6 months 10 mg (half a 20 mg tablet) once a day; age 6 months to 5 years 20 mg (one tablet) once a day; give for 14 days, together with ORS. " +
                "The tablet can be dissolved in a little breast milk or clean water."
        ),
        Topic(
            "newborn",
            rx("new\\s*-?born|neonat|naya?\\s*janm|navjaat|nawjat|\\bshishu\\b|नवजात|शिशु|janm\\s+ke\\s+baad\\s+bach|baby.{0,30}(cold|thand|hot|garam|feed|doodh|breath|saans)|(thanda|thand|cold).{0,20}(bachcha|baccha|bacha|baby)"),
            "Newborn danger signs (refer at once; call 102 or 108): not able to feed or suck; convulsions (fits); fast breathing — 60 or more breaths per minute; severe chest indrawing; " +
                "temperature 37.5 °C or more, or less than 35.5 °C (baby feels hot, or cold to touch); moves only when touched or not at all; yellow palms or soles; red or pus-filled umbilicus; bleeding. " +
                "Keep a newborn warm: skin-to-skin contact with the mother (Kangaroo Mother Care), cover the head, hands and feet, wrap in a dry cloth, keep the room warm, and keep breastfeeding. Do not bathe a cold or small baby. " +
                "Start breastfeeding within 1 hour of birth and give only breast milk for 6 months; a baby under 2.5 kg at birth is low birth weight and needs extra care."
        ),
        Topic(
            "child_breathing_fever",
            rx("(fast|tez)\\s+(breath|saans)|saans\\s+(tez|phool)|pneumonia|निमोनिया|सांस\\s+तेज|तेज़?\\s+सांस|chest\\s+indrawing|cough.{0,20}child|child.{0,30}(fever|cough|bukhar|khansi)|(bachch?e|bacche|bachcha|baccha|bachi|bacha).{0,30}(bukhar|bukhaar|khansi|saans)|बच्चे.{0,30}(बुखार|खांसी|सांस)|(child|baby|infant|kid).{0,30}(fever|temperature)|(fever|temperature).{0,30}(child|baby|infant|kid)"),
            "Child danger signs (refer urgently; call 108): not able to drink or breastfeed, vomits everything, has convulsions, is very sleepy or unconscious, or has chest indrawing. " +
                "Fast breathing (count for one full minute when the child is calm): under 2 months 60 or more; 2–12 months 50 or more; 1–5 years 40 or more breaths per minute — refer to the PHC. So a calm 1–5 year old normally breathes fewer than 40 times a minute, a 2–12 month old fewer than 50, and a baby under 2 months fewer than 60. " +
                "Fever: give plenty of fluids and keep breastfeeding; a fever for 7 days or more, fever in a baby under 2 months, or fever with any danger sign needs referral. " +
                "Paracetamol for fever is given by the child's weight or age as written on the packet or as the ANM advises; never give aspirin to a child. " +
                "In a malaria area, test any fever with an RDT."
        ),
        Topic(
            "pregnancy_danger",
            rx("pregnan|garbh|गर्भ|गर्भवती|prasav\\s+se\\s+pehle|antenatal|\\banc\\b|blood\\s+pressure.{0,30}pregn|swelling.{0,30}(face|haath|pair)|sujan"),
            "Danger signs in pregnancy (call 102 or 108 and take her to the hospital at once): bleeding from the vagina; severe headache or blurred vision; fits (convulsions); high fever; " +
                "severe pain in the abdomen; swelling of the face and hands; baby moving less or not moving; water leaking before labour; difficulty breathing. " +
                "Blood pressure of 140/90 or more is high and needs the doctor. Haemoglobin below 11 g/dl in pregnancy is anaemia; below 7 g/dl is severe anaemia — refer. " +
                "Iron and folic acid: one IFA tablet daily for 180 days from the fourth month of pregnancy (and 180 days after delivery). A pregnant woman needs at least 4 antenatal check-ups."
        ),
        Topic(
            "after_delivery",
            rx("after\\s+(delivery|birth|childbirth)|post\\s*-?(partum|natal)|\\bpph\\b|delivery\\s+ke\\s+baad|prasav\\s+ke\\s+baad|प्रसव\\s+के\\s+बाद|डिलीवरी\\s+के\\s+बाद|janm\\s+dene\\s+ke\\s+baad"),
            "Danger signs after delivery (call 108 or 102 and take her to the hospital at once): heavy bleeding or passing large clots; fits; severe headache or blurred vision; high fever; " +
                "foul-smelling discharge; severe abdominal pain; difficulty breathing. While waiting for the ambulance: keep her lying down and warm, and do not give anything by mouth if she is drowsy or fitting."
        ),
        Topic(
            "dog_bite",
            rx("dog\\s*bite|kutt?e\\s+ne\\s+kaat|kutta\\s+kaat|कुत्ते\\s+ने\\s+काट|कुत्ता\\s+काट|rabies|रेबीज|animal\\s+bite|bandar\\s+ne\\s+kaat"),
            "Dog or animal bite: wash the wound at once with soap and running water for 15 minutes, then apply an antiseptic. Do not apply chilli, oil or mud, and do not cover it tightly. " +
                "Send the person to the PHC or hospital the same day for anti-rabies vaccine (and rabies immunoglobulin if the bite broke the skin and bled). Complete every vaccine dose on the dates given."
        ),
        Topic(
            "snake_bite",
            rx("snake|saa?np\\s+(ne\\s+)?kaat|saanp|सांप|साँप|सर्प"),
            "Snake bite: call 108 at once and take the person to the hospital that has anti-snake venom. Keep the person calm and still, lying down; keep the bitten limb still, as with a splint, " +
                "and remove rings or tight things. Do NOT cut the wound, suck it, tie a tight tourniquet, apply ice, or use herbal remedies, and do not let the person walk if you can avoid it."
        )
    )


    init {
        TOPICS.first { it.id == "ors" }.hindi = "ओआरएस: एक ओआरएस पैकेट को 1 लीटर साफ़ पीने के पानी में घोलें; घोल 24 घंटे के अंदर इस्तेमाल करें, फिर नया बनाएँ। पैकेट न हो तो घर का घोल: 1 लीटर साफ़ पीने के पानी में 6 समतल चम्मच चीनी और आधा (½) समतल चम्मच नमक। हर पतले दस्त के बाद दें: 2 साल से छोटे बच्चे को 50–100 मि.ली. (¼ से ½ कप); 2–10 साल के बच्चे को 100–200 मि.ली. (½ से 1 कप); बड़े बच्चे और वयस्क जितना पी सकें। कप या चम्मच से घूँट-घूँट दें; उल्टी हो तो 10 मिनट रुककर धीरे-धीरे फिर दें।"
        TOPICS.first { it.id == "diarrhoea" }.hindi = "बच्चे को दस्त: हर पतले दस्त के बाद ओआरएस दें और 14 दिन तक ज़िंक दें; स्तनपान और सामान्य खाना जारी रखें, और ज़्यादा तरल दें। ज़िंक: 2–6 महीने के बच्चे को 10 मि.ग्रा. (20 मि.ग्रा. की आधी गोली) दिन में एक बार; 6 महीने से 5 साल के बच्चे को 20 मि.ग्रा. (एक गोली) दिन में एक बार, 14 दिन तक, दस्त रुक जाए तब भी। सामान्य पानी जैसे दस्त में एंटीबायोटिक या दस्त रोकने की दवा न दें। तुरंत रेफ़र करें (108 पर कॉल करें, नवजात के लिए 102) अगर बच्चा: पी नहीं पा रहा या स्तनपान नहीं कर पा रहा, सब कुछ उल्टी कर देता है, उसे झटके आ रहे हैं, बहुत सुस्त या बेहोश है, आँखें धँसी हैं या चुटकी से उठाई त्वचा बहुत धीरे वापस जाती है, मल में ख़ून है, 14 दिन या ज़्यादा से दस्त है, या 2 महीने से छोटा है।"
        TOPICS.first { it.id == "zinc" }.hindi = "दस्त में ज़िंक: 2–6 महीने के बच्चे को 10 मि.ग्रा. (20 मि.ग्रा. की आधी गोली) दिन में एक बार; 6 महीने से 5 साल के बच्चे को 20 मि.ग्रा. (एक गोली) दिन में एक बार; 14 दिन तक, ओआरएस के साथ। गोली थोड़े माँ के दूध या साफ़ पानी में घोलकर दी जा सकती है।"
        TOPICS.first { it.id == "newborn" }.hindi = "नवजात के खतरे के लक्षण (तुरंत रेफ़र करें; 102 या 108 पर कॉल करें): दूध न पी पाना या चूस न पाना; झटके आना; तेज़ साँस — एक मिनट में 60 या ज़्यादा; छाती का ज़ोर से अंदर धँसना; तापमान 37.5 °C या ज़्यादा, या 35.5 °C से कम (बच्चा छूने में गर्म या ठंडा लगे); छूने पर ही हिलना या बिल्कुल न हिलना; हथेलियाँ या तलवे पीले; नाल लाल या मवाद वाली; ख़ून बहना। नवजात को गर्म रखें: माँ से त्वचा से त्वचा का संपर्क (कंगारू मदर केयर), सिर, हाथ और पैर ढकें, सूखे कपड़े में लपेटें, कमरा गर्म रखें, और स्तनपान कराते रहें। ठंडे या छोटे बच्चे को नहलाएँ नहीं। जन्म के 1 घंटे के अंदर स्तनपान शुरू करें और 6 महीने तक सिर्फ़ माँ का दूध दें; जन्म के समय 2.5 कि.ग्रा. से कम वज़न वाला बच्चा कम वज़न का है और उसे ख़ास देखभाल चाहिए।"
        TOPICS.first { it.id == "child_breathing_fever" }.hindi = "बच्चे के खतरे के लक्षण (तुरंत रेफ़र करें; 108 पर कॉल करें): पी न पाना या स्तनपान न कर पाना, सब कुछ उल्टी कर देना, झटके आना, बहुत सुस्त या बेहोश होना, या छाती का अंदर धँसना। तेज़ साँस (बच्चा शांत हो तब पूरे एक मिनट गिनें): 2 महीने से छोटे में 60 या ज़्यादा; 2–12 महीने में 50 या ज़्यादा; 1–5 साल में 40 या ज़्यादा साँस प्रति मिनट — पीएचसी रेफ़र करें। यानी शांत 1–5 साल का बच्चा सामान्य रूप से एक मिनट में 40 से कम साँस लेता है, 2–12 महीने का 50 से कम, और 2 महीने से छोटा 60 से कम। बुखार: ख़ूब तरल दें और स्तनपान जारी रखें; 7 दिन या ज़्यादा का बुखार, 2 महीने से छोटे बच्चे को बुखार, या किसी खतरे के लक्षण के साथ बुखार हो तो रेफ़र करें। बुखार में पैरासिटामोल बच्चे के वज़न या उम्र के हिसाब से, पैकेट पर लिखे अनुसार या एएनएम की सलाह से दें; बच्चे को कभी एस्पिरिन न दें। मलेरिया वाले इलाक़े में हर बुखार की आरडीटी से जाँच करें।"
        TOPICS.first { it.id == "pregnancy_danger" }.hindi = "गर्भावस्था में खतरे के लक्षण (102 या 108 पर कॉल करें और तुरंत अस्पताल ले जाएँ): योनि से ख़ून आना; तेज़ सिरदर्द या धुंधला दिखना; झटके आना; तेज़ बुखार; पेट में तेज़ दर्द; चेहरे और हाथों पर सूजन; बच्चे का कम हिलना या न हिलना; प्रसव से पहले पानी आना; साँस लेने में तकलीफ़। 140/90 या ज़्यादा ब्लड प्रेशर ज़्यादा है, डॉक्टर को दिखाएँ। गर्भावस्था में 11 g/dl से कम हीमोग्लोबिन एनीमिया है; 7 g/dl से कम गंभीर एनीमिया है — रेफ़र करें। आयरन और फ़ोलिक एसिड: गर्भ के चौथे महीने से 180 दिन तक रोज़ एक आईएफ़ए गोली (और प्रसव के बाद 180 दिन)। गर्भवती महिला की कम से कम 4 प्रसव-पूर्व जाँच होनी चाहिए।"
        TOPICS.first { it.id == "after_delivery" }.hindi = "प्रसव के बाद खतरे के लक्षण (108 या 102 पर कॉल करें और तुरंत अस्पताल ले जाएँ): बहुत ज़्यादा ख़ून बहना या बड़े थक्के आना; झटके आना; तेज़ सिरदर्द या धुंधला दिखना; तेज़ बुखार; बदबूदार स्राव; पेट में तेज़ दर्द; साँस लेने में तकलीफ़। एम्बुलेंस का इंतज़ार करते समय: उसे लिटाकर रखें और गर्म रखें, और अगर वह सुस्त है या उसे झटके आ रहे हैं तो मुँह से कुछ न दें।"
        TOPICS.first { it.id == "dog_bite" }.hindi = "कुत्ते या जानवर का काटना: घाव को तुरंत साबुन और बहते पानी से 15 मिनट तक धोएँ, फिर एंटीसेप्टिक लगाएँ। मिर्च, तेल या मिट्टी न लगाएँ और कसकर पट्टी न बाँधें। उसी दिन पीएचसी या अस्पताल भेजें ताकि एंटी-रेबीज़ टीका लगे (और अगर काटने से त्वचा फटी और ख़ून निकला तो रेबीज़ इम्युनोग्लोबुलिन)। टीके की हर ख़ुराक दी गई तारीख़ पर पूरी करें।"
        TOPICS.first { it.id == "snake_bite" }.hindi = "साँप का काटना: तुरंत 108 पर कॉल करें और उस अस्पताल ले जाएँ जहाँ एंटी-स्नेक वेनम हो। व्यक्ति को शांत और स्थिर रखें, लिटाकर; काटे गए हाथ या पैर को हिलने न दें, जैसे खपच्ची से, और अंगूठी या कसी चीज़ें उतार दें। घाव को न काटें, न चूसें, कसकर बंध न बाँधें, बर्फ़ न लगाएँ, जड़ी-बूटी न लगाएँ, और जहाँ तक हो सके व्यक्ति को चलने न दें।"
    }

    /** The topics a question is about, most specific first, at most [max]. */
    fun topicsFor(question: String, max: Int = 2): List<Topic> {
        val q = question.trim()
        if (q.length < 3) return emptyList()
        val hits = TOPICS.filter { it.match.containsMatchIn(q) }
        // "zinc" is already inside the diarrhoea facts.
        var trimmed = if (hits.any { it.id == "diarrhoea" }) hits.filterNot { it.id == "zinc" } else hits
        // ORS is given for diarrhoea: bring its danger signs, or the model invents its own.
        if (trimmed.firstOrNull()?.id == "ors" && trimmed.none { it.id == "diarrhoea" })
            trimmed = listOf(trimmed.first(), TOPICS.first { it.id == "diarrhoea" }) + trimmed.drop(1)
        return trimmed.take(max)
    }

    /** The system-channel addition for a plain-chat question, or null when no topic matches. */
    fun systemAddon(question: String): String? {
        val topics = topicsFor(question)
        if (topics.isEmpty()) return null
        // A Hindi question gets the facts in Hindi: the 3B model copies them well but translates
        // English facts badly ("cover the skin with breast milk").
        val hindi = question.count { it.code in 0x0900..0x097F } * 2 > question.count { it.isLetter() }
        return buildString {
            if (hindi) {
                append("इस सवाल के लिए जाँचे हुए तथ्य (WHO और भारत के आशा / IMNCI दिशानिर्देशों से)। ")
                append("जवाब इन्हीं तथ्यों पर दें, इनके शब्द और संख्याएँ जैसे लिखे हैं वैसे ही इस्तेमाल करें — कोई संख्या, इकाई या सीमा न बदलें, न उलटें — ")
                append("और कोई दूसरी दवा, ख़ुराक, नुस्ख़ा या संख्या न जोड़ें। उपयोगकर्ता ने जो लक्षण बताए हैं सिर्फ़ उन्हें ही मौजूद बताएँ। ")
                append("पहले सवाल का जवाब सरल हिंदी में दें, फिर वे खतरे के लक्षण जिनमें रेफ़र करना है।\n")
                topics.forEach { append("- ").append(it.hindi.ifBlank { it.facts }).append("\n") }
            } else {
                append("Checked facts for this question (from WHO and India's ASHA / IMNCI guidelines). ")
                append("Base your answer on these facts. Use their numbers, units and limits exactly as written — never change, reverse or round them — ")
                append("and do not add any other dose, recipe, number or danger sign. Say a person has a sign only if the user said so. ")
                append("Answer the user's question first, in simple words, then the danger signs that mean referral.\n")
                topics.forEach { append("- ").append(it.facts).append("\n") }
            }
        }.trim()
    }

    // ── After the answer: remove invented doses, add the key line if it is missing ───────────

    /** [answerMust]: what one sentence of the answer must contain, all together; [removeIf]: sentences to drop. */
    private class Key(val answerMust: List<Regex>, val en: String, val hi: String, val askedWhen: Regex? = null, val removeIf: Regex? = null)

    private val KEYS = mapOf(
        "ors" to Key(
            // The recipe must be in one sentence: the 3B model once split it into "sugar in 1 litre",
            // "salt in 1 litre", "keep the two in separate bottles".
            listOf(Regex("(?i)\\b6\\b|\\bsix\\b|छह|छः"), Regex("(?i)sugar|चीनी|cheeni|chini|shakkar"), Regex("(?i)salt|नमक|namak"),
                Regex("(?i)½|half|1/2|आधा|0\\.5"), Regex("(?i)lit(re|er)|लीटर|\\b1\\s*l\\b")),
            "Home-made ORS: 6 level teaspoons of sugar and ½ level teaspoon of salt in 1 litre of clean drinking water (or one ORS packet in 1 litre).",
            "घर का ओआरएस: 1 लीटर साफ़ पीने के पानी में 6 समतल चम्मच चीनी और आधा (½) समतल चम्मच नमक (या एक ओआरएस पैकेट 1 लीटर पानी में)।",
            // Only when the question is how to make it ("how much ORS to give" needs no recipe).
            Regex("(?i)make|prepar|recipe|mix|what\\s+is|ingredient|bana|ghol|बना|घोल|क्या\\s+है|तैयार"),
            Regex("(?i)separate|alag|अलग")
        ),
        "diarrhoea" to Key(
            listOf(Regex("(?i)zinc|ज़िंक|जिंक")),
            "For diarrhoea: ORS after every loose stool, zinc once a day for 14 days (10 mg for 2–6 months, 20 mg for 6 months to 5 years), and keep breastfeeding and feeding.",
            "दस्त में: हर पतले दस्त के बाद ओआरएस, 14 दिन तक रोज़ एक बार ज़िंक (2–6 महीने में 10 मि.ग्रा., 6 महीने से 5 साल में 20 मि.ग्रा.), और स्तनपान व खाना जारी रखें।"
        ),
        "zinc" to Key(
            listOf(Regex("(?i)\\b(10|20)\\s*(mg|मि)")),
            "Zinc: 10 mg a day for 2–6 months, 20 mg a day for 6 months to 5 years, for 14 days.",
            "ज़िंक: 2–6 महीने में 10 मि.ग्रा. रोज़, 6 महीने से 5 साल में 20 मि.ग्रा. रोज़, 14 दिन तक।"
        ),
        "child_breathing_fever" to Key(
            listOf(Regex("\\b(40|50|60)\\b")),
            "Fast breathing (count for 1 full minute): 60 or more under 2 months, 50 or more at 2–12 months, 40 or more at 1–5 years — refer to the PHC.",
            "तेज़ साँस (पूरा 1 मिनट गिनें): 2 महीने से छोटे में 60 या ज़्यादा, 2–12 महीने में 50 या ज़्यादा, 1–5 साल में 40 या ज़्यादा — पीएचसी रेफ़र करें।"
        )
    )

    /** Numbers with a dose or temperature unit: invented ones of these are the dangerous kind. */
    private val DOSE = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:°\\s*[CF]\\b|degrees?|डिग्री|mg\\b|मि\\.?\\s?ग्रा|milligrams?|ml\\b|मि\\.?\\s?ली|millilit|teaspoons?|tsp\\b|चम्मच|tablets?|गोली)")

    const val LABEL_EN = "Checked guideline: "
    const val LABEL_HI = "जाँचा हुआ दिशानिर्देश: "

    /**
     * The plain-chat answer after the facts check: sentences with a dose or temperature that none
     * of the facts contains are removed (when most of the answer remains), and the topic's key line
     * is added when the answer left it out (the 3B model's Hindi ORS answer dropped the amounts).
     * [question] is the one whose facts were given ([factsQuestion] for a follow-up).
     */
    fun checkAnswer(question: String, answer: String, factsQuestion: String = question): String {
        val topics = topicsFor(factsQuestion)
        if (topics.isEmpty() || answer.isBlank()) return answer
        val hindi = (question + " " + answer).let { t -> t.count { it.code in 0x0900..0x097F } * 2 > t.count { it.isLetter() } }
        val known = topics.flatMap { t -> listOf(t.facts, t.hindi) + listOfNotNull(KEYS[t.id]?.en) }
            .flatMap { Regex("\\d+(?:\\.\\d+)?").findAll(it).map { m -> m.value } }.toSet() + setOf("1000", "1", "2")
        val main = KEYS[topics.first().id]
        val sentences = answer.split(Regex("(?<=[.!?।])\\s+|\\n")).filter { it.isNotBlank() }
        val bad = sentences.filter { st ->
            DOSE.findAll(st).any { it.groupValues[1].replace(",", ".") !in known } || main?.removeIf?.containsMatchIn(st) == true
        }
        var out = answer
        if (bad.isNotEmpty() && bad.size * 2 <= sentences.size) {
            for (b in bad) out = out.replace(b, "")
            out = out.lines().filter { line -> line.isBlank() || line.any { it.isLetter() } }
                .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        }
        // Only the main topic's key line: an ORS question does not need the zinc line.
        val left = out.split(Regex("(?<=[.!?।])\\s+|\\n"))
        val key = main?.takeIf { k ->
            left.none { st -> k.answerMust.all { it.containsMatchIn(st) } } &&
                (k.askedWhen == null || k.askedWhen.containsMatchIn("$question $factsQuestion"))
        }
        if (key != null) out = out.trimEnd() + "\n\n" + (if (hindi) LABEL_HI + key.hi else LABEL_EN + key.en)
        return out
    }

    private val HEALTH = Regex("(?i)\\b(dose|dosage|medicine|tablet|syrup|mg|injection|vaccine|fever|pain|bleed|pregnan|child|baby|infant|diabet|sugar\\s+level|blood\\s+pressure|bp\\b|infection|antibiotic|cough|vomit|rash|wound|burn|poison|treat|cure|symptom|disease|malaria|dengue|tb\\b|tuberculosis|anaemia|anemia|dawa|dawai|goli|bukhar|dard|ilaj|इलाज|दवा|दवाई|गोली|बुखार|दर्द|ख़ुराक|खुराक|बीमारी|लक्षण|टीका|इंजेक्शन)")

    const val UNVERIFIED_DOSE_EN = "I can't check medicine doses offline, so I have left them out. Ask the ANM or medical officer for the dose, or tap Check online."
    const val UNVERIFIED_DOSE_HI = "ऑफ़लाइन मैं दवा की ख़ुराक की जाँच नहीं कर सकता, इसलिए उसे छोड़ दिया है। ख़ुराक के लिए एएनएम या मेडिकल ऑफ़िसर से पूछें, या Check online दबाएँ।"

    /**
     * A health answer from the phone's model with no checked facts behind it: a dose or
     * temperature it gives cannot be verified offline, so those sentences are removed and the
     * answer says so (the rest — what to watch for, when to refer — stays).
     */
    fun checkUncoveredHealthAnswer(question: String, answer: String): String {
        if (topicsFor(question).isNotEmpty() || !HEALTH.containsMatchIn(question) || answer.isBlank()) return answer
        val sentences = answer.split(Regex("(?<=[.!?।])\\s+|\\n")).filter { it.isNotBlank() }
        val doses = sentences.filter { st -> DOSE.findAll(st).any { m -> !Regex("(?i)°|degree|डिग्री").containsMatchIn(m.value) } }
        if (doses.isEmpty()) return answer
        var out = answer
        for (d in doses) out = out.replace(d, "")
        out = out.lines().filter { line -> line.isBlank() || line.any { it.isLetter() } }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        val hindi = (question + " " + answer).let { t -> t.count { it.code in 0x0900..0x097F } * 2 > t.count { it.isLetter() } }
        return (if (out.isBlank()) "" else out + "\n\n") + (if (hindi) UNVERIFIED_DOSE_HI else UNVERIFIED_DOSE_EN)
    }

    private val HINGLISH_WORDS = setOf(
        "ko", "ho", "hai", "hain", "toh", "to", "kya", "kaise", "kaisa", "karna", "karein", "karen", "kare", "chahiye", "mein", "me", "ka", "ki", "ke",
        "nahi", "nahin", "bhi", "aur", "bacche", "bachche", "bachcha", "baccha", "kab", "kitna", "kitni", "kyun", "kyon", "raha", "rahi", "gaya", "gayi",
        "hota", "hoti", "dena", "dein", "lena", "wala", "wali", "agar", "par", "se", "tak", "abhi", "aaj", "mujhe", "unko", "isko", "uska", "uski"
    )

    /**
     * Everyday Hindi words written in English letters, with their English meaning. The phone's model
     * rewrites a Hinglish question into English for the search, and on its own it read "dast"
     * (diarrhoea) as "cough". The meanings of the words a question uses are given to it as hints.
     */
    private val HINGLISH_GLOSSARY = linkedMapOf(
        "dast" to "diarrhoea", "daast" to "diarrhoea", "loose motion" to "diarrhoea", "patla" to "loose / watery",
        "bukhar" to "fever", "bukhaar" to "fever", "khansi" to "cough", "khaansi" to "cough", "zukam" to "cold",
        "ulti" to "vomiting", "ultee" to "vomiting", "pet dard" to "abdominal pain", "sir dard" to "headache",
        "jhatke" to "convulsions / fits", "daura" to "fits", "behosh" to "unconscious", "saans" to "breathing",
        "sujan" to "swelling", "soojan" to "swelling", "khoon" to "blood", "khoon ki kami" to "anaemia",
        "kamzori" to "weakness", "peeliya" to "jaundice", "piliya" to "jaundice", "chechak" to "chickenpox / measles",
        "khasra" to "measles", "tika" to "vaccine", "teeka" to "vaccine", "tikakaran" to "immunization",
        "garbhvati" to "pregnant woman", "garbhwati" to "pregnant woman", "pregnant mahila" to "pregnant woman",
        "prasav" to "delivery", "janam" to "birth", "navjaat" to "newborn", "shishu" to "infant / newborn",
        "baccha" to "child", "bacche" to "child", "bachche" to "child", "bachcha" to "child", "doodh" to "milk",
        "stanpan" to "breastfeeding", "maa ka doodh" to "breast milk", "khana" to "food", "poshan" to "nutrition",
        "kuposhit" to "malnourished", "vajan" to "weight", "wajan" to "weight", "goli" to "tablet", "dawai" to "medicine",
        "dawa" to "medicine", "kitni" to "how many / how much", "kitna" to "how much", "kab" to "when", "kaise" to "how",
        "kya kare" to "what to do", "kharid" to "purchase", "kharidari" to "procurement / purchase", "nivida" to "tender",
        "bhugtan" to "payment", "jamanat" to "security / guarantee", "thekedar" to "contractor", "chhutti" to "leave"
    )

    /** "dast = diarrhoea; bacche = child" for the Hinglish words in [question]; empty when none. */
    fun hinglishHints(question: String): List<String> {
        val q = " " + question.lowercase().replace(Regex("[^a-z]+"), " ").trim() + " "
        if (!isHinglish(question)) return emptyList()
        return HINGLISH_GLOSSARY.entries.filter { (hi, _) -> q.contains(" $hi ") }.map { (hi, en) -> "$hi = $en" }.distinct().take(8)
    }

    /** Hindi written in English letters ("bacche ko dast ho toh kya karna chahiye"). */
    fun isHinglish(text: String): Boolean {
        if (text.any { it.code in 0x0900..0x097F }) return false
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        if (words.size < 3) return false
        val hits = words.count { it in HINGLISH_WORDS }
        val strong = words.count { it in setOf("kya", "hai", "hain", "chahiye", "karna", "kaise", "nahi", "nahin", "toh", "mein", "bacche", "kitna", "kitni") }
        return strong >= 1 && hits >= 2 && hits * 4 >= words.size
    }

    const val HINGLISH_ADDON = "The user wrote Hindi in English letters (Hinglish). Understand it, and write your whole reply in clear, simple English sentences — not in Hinglish."

    /** Everything plain chat adds to the system prompt for this question, or null. */
    fun chatAddon(question: String): String? {
        val parts = listOfNotNull(systemAddon(question), if (isHinglish(question)) HINGLISH_ADDON else null)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
