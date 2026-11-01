package com.example.arfas

/**
 * Checks for the audit fixes (run like KbPhase14RegressionTest: `main`, no device needed).
 * Each check names the user-visible behaviour it protects.
 */
object PramaanFixesTest {
    private var passed = 0

    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (!ok) throw AssertionError("FAIL $name ${detail()}")
        passed++
        println("PASS $name")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        typos()
        lists()
        sourceTitles()
        answerProof()
        kbHealth()
        rewriteGuard()
        proofChecks()
        evidenceFraming()
        citations()
        prompt()
        latinScreenshot()
        coreFacts()
        naturalPhrasing()
        coverage()
        println("PramaanFixesTest: $passed checks passed")
    }

    private fun latinScreenshot() {
        val latin = "ASHA est activista sociale accreditata qui servit ut locum informationis et servit populos in areas rurali. " +
            "ASHA est definitio ex fontibus et servit populos."
        check("language: the Latin screenshot answer is rejected", KbReplyText.wrongLanguageForEnglish("what is asha", latin))
        check("language: a normal English answer passes",
            !KbReplyText.wrongLanguageForEnglish("what is asha", "ASHA is an Accredited Social Health Activist who works in the village and is the first contact for health services for the community [S1]."))
        check("language: Hinglish question not checked", !KbReplyText.wrongLanguageForEnglish("asha kya hai", latin))
        check("citations: (S1) and Source: S2 become markers",
            KbReplyText.normalizeCitations("Give IFA daily (S1). Refer early, Source: S2. Also (S3, S4).") == "Give IFA daily [S1]. Refer early, [S2]. Also [S3, S4].") {
            KbReplyText.normalizeCitations("Give IFA daily (S1). Refer early, Source: S2. Also (S3, S4).") }
        check("citations: plain text untouched", KbReplyText.normalizeCitations("Form S1 is filled by the buyer.") == "Form S1 is filled by the buyer.")
        check("prompt: never says 'Latin'", !KbReplyText.scriptInstruction("what is asha").contains("Latin"))
        val leaked = "ASHA is a health worker [S1].\nUNSUPPORTED: null\nC1: SUPPORTED; sources=S1,S2\nExplanation: from S1, S2."
        check("sanitizer: ledger echo removed", FixSightOutputSanitizer.sanitize(leaked).let { !it.contains("SUPPORTED") && !it.contains("Explanation") && it.contains("health worker") }) { FixSightOutputSanitizer.sanitize(leaked) }
    }

    private fun typos() {
        val p = GenericKbCorpusProfile.Builder("t")
        check("typo: swapped letters count as one edit (asah -> asha)", p.build().editDistanceWithin("asah", "asha", 1))
        check("typo: one wrong letter (asba -> asha)", p.build().editDistanceWithin("asba", "asha", 1))
        check("typo: two unrelated edits are not one", !p.build().editDistanceWithin("absh", "asha", 1))
        check("typo: inserted letter (aasha -> asha)", p.build().editDistanceWithin("aasha", "asha", 1))
    }

    private fun rewriteGuard() {
        fun rw(out: String, q: String) = GenericKbQuestionRewrite.parse(out, q)?.search
        check("rewrite: copied example rejected", rw("SEARCH: What advance payment is required for a 4-night stay in a room?\nCALC: NONE", "bacche ko dast me zinc kitna dena hai?") == null)
        check("rewrite: copied booking example for a follow-up rejected", rw("SEARCH: What advance payment is required for a booking?\nCALC: NONE", "Is it mandatory?") == null)
        check("rewrite: real Hinglish rewrite kept", rw("SEARCH: How much zinc should be given to a child with diarrhoea?\nCALC: NONE", "bacche ko dast me zinc kitna dena hai?") == "How much zinc should be given to a child with diarrhoea?")
        check("rewrite: Hinglish to English with no shared word but shared stem kept", rw("SEARCH: What is the limit for purchase without quotation?\nCALC: NONE", "bina quotation kitne tak kharid sakte hain?") != null)
        check("rewrite: a real booking question may match the example", rw("SEARCH: What advance payment is required for a booking?\nCALC: 4*3*2500", "advance payment for 4 rooms 3 nights 2500") != null)
        check("rewrite: Devanagari question rewrite kept", rw("SEARCH: What are the danger signs in a newborn?\nCALC: NONE", "नवजात शिशु में खतरे के लक्षण क्या हैं?") != null)
    }

    private fun proofChecks() {
        val cite = kotlinx.coroutines.runBlocking {
            AnswerProof.check(
                "According to the Defence Procurement Manual 2025 (Volume II) - page 69 [1], purchase of goods and services up to the value of ₹ 50,000/- may be made without inviting quotations or bids.",
                listOf("S1" to "Purchase of goods and services up to the value of ₹ 50,000/- on each occasion may be made without inviting quotations or bids."),
                { _, ps -> FloatArray(ps.size) { 9f } },
                listOf("DEFENCE PROCUREMENT MANUAL 2025 (Volume II) — page 69")
            )
        }
        check("proof: page number and title year are not claims", cite.allSupported) { cite.statements.toString() }
        check("proof: 'se adhik' vs 'up to' is a conflict", AnswerProof.directionConflict("Bina quotation ke ₹ 50,000 se adhik kharid sakte hain.", "Purchase of goods up to the value of Rs. 50,000 may be made without inviting quotations."))
        check("proof: 'up to' vs 'up to' is fine", !AnswerProof.directionConflict("Goods up to Rs 50,000 can be bought without quotation.", "Purchase of goods up to the value of Rs. 50,000 may be made without inviting quotations."))
        check("proof: 'not exceeding' is an upper limit", !AnswerProof.directionConflict("Purchases up to 25,000 need no comparison.", "value not exceeding 25,000"))
        check("proof: Hindi 'से अधिक' vs 'तक'", AnswerProof.directionConflict("50,000 रुपये से अधिक बिना कोटेशन खरीद सकते हैं।", "50,000 तक की खरीद बिना कोटेशन"))
        check("proof: no direction words, no conflict", !AnswerProof.directionConflict("The fee is 500.", "A fee of 500 is charged."))
        val stroke = listOf("Warning signs of stroke: sudden severe headache with no explanation, sudden trouble seeing in one or both eyes.")
        check("topic: pregnancy not in stroke passages", AnswerProof.missingTopicWords("What are the danger signs during pregnancy?", stroke) == listOf("danger", "pregnancy")) { AnswerProof.missingTopicWords("What are the danger signs during pregnancy?", stroke).toString() }
        check("topic: bicycle puncture not in repair passages", AnswerProof.missingTopicWords("How do I repair a bicycle puncture?", listOf("Field repairs carried out by technicians")) .containsAll(listOf("bicycle", "puncture")))
        check("topic: covered question not flagged", AnswerProof.missingTopicWords("What is a reverse auction?", listOf("Reverse auction means an online real-time purchasing technique")).isEmpty())
        check("topic: Hindi question skipped", AnswerProof.missingTopicWords("गर्भावस्था में खतरे के लक्षण क्या हैं?", stroke).isEmpty())
        check("topic: 'bought' alone is no warning", AnswerProof.missingTopicWords("Up to what value can goods be bought without quotation?", listOf("purchase of goods up to Rs 50,000 without quotation")).isEmpty())
        check("topic: 'weighing' matches 'weight'", AnswerProof.missingTopicWords("What should I do for a baby weighing less than 1.8 kg?", listOf("Babies with birth weight less than 1.8 kg should be referred")).isEmpty())
        check("topic: plural/inflection matches", AnswerProof.missingTopicWords("Who screens for hypertension in adults?", listOf("screening of hypertensive adults")).isEmpty())
    }

    private fun kbHealth() {
        check("health: mid-sentence start", KbHealthCheck.midSentenceStart("and the dose is 20 mg.") && KbHealthCheck.midSentenceStart("… continued text") && !KbHealthCheck.midSentenceStart("The dose is 20 mg."))
        check("health: Hindi start is not mid-sentence", !KbHealthCheck.midSentenceStart("नवजात शिशु की देखभाल"))
        val key = KbHealthCheck.keySentence("Page 4. If Zinc is to be administered to children 6 months to 5 years age, the dose is full tablet (20 mg). Take a spoon.")
        check("health: key sentence picks the informative one", key?.startsWith("If Zinc is to be administered") == true) { "$key" }
        check("health: file-name title", KbHealthCheck.fileNameLike("Primary-User-Registration.pdf") && !KbHealthCheck.fileNameLike("Module for ASHA on Non-Communicable Diseases"))
        val probes = (1..10).map { KbHealthCheck.Probe("Doc", it, "q$it", if (it <= 9) 1 else null, 5f, 100) }
        val clean = KbHealthCheck.Stats(1000, 10, 0, 10, 0, 0, 500, 20, 0, 10, 0)
        val r = KbHealthCheck.report("Test", probes, clean, false)
        check("health: clean pack with 9/10 found scores high", r.score >= 90 && r.findings == listOf("No problems found.")) { "${r.score} ${r.findings}" }
        val messy = KbHealthCheck.Stats(1000, 10, 200, 100, 80, 300, 500, 250, 0, 10, 8)
        val m = KbHealthCheck.report("Test", probes.map { it.copy(rank = if (it.page!! <= 5) 2 else null) }, messy, false)
        check("health: messy pack scores low with fixes listed", m.score < 50 && m.findings.size >= 5 && m.findings.first().startsWith("Search found only")) { "${m.score} ${m.findings}" }
        check("health: render lists misses", KbHealthCheck.render(m).contains("Passages search did not find"))
    }

    private fun answerProof() {
        val st = AnswerProof.statements("Here are the steps:\n1. Give ORS after every loose stool [S1].\n• Give zinc for 14 days [S2]. Refer if blood in stool.\nSource: Module 3")
        check("proof: statements split, lead-in and source line dropped", st == listOf("Give ORS after every loose stool.", "Give zinc for 14 days.", "Refer if blood in stool.")) { st.toString() }
        val passages = listOf("S1" to "Give ORS after every loose stool to prevent dehydration.", "S2" to "Zinc is given for 14 days to children with diarrhoea.")
        // Fake re-ranker: high for the passage sharing the statement's first word, low otherwise.
        val scorer: suspend (String, List<String>) -> FloatArray? = { q, ps ->
            FloatArray(ps.size) { i -> if (ps[i].lowercase().contains(q.split(" ")[1].lowercase())) 8f else -6f }
        }
        val r = kotlinx.coroutines.runBlocking { AnswerProof.check("Give ORS after every loose stool [S1].\nGive zinc for 14 days [S2].\nGive bananas twice daily to the child.", passages, scorer) }
        check("proof: two found, one not", r.supported == 2 && r.total == 3) { r.statements.toString() }
        val pruned = AnswerProof.prune("Give ORS after every loose stool [S1].\nGive zinc for 14 days [S2].\nGive bananas twice daily to the child.", r)
        check("proof: unsupported statement removed", pruned != null && !pruned.first.contains("bananas") && pruned.first.contains("zinc") && pruned.second.allSupported) { pruned.toString() }
        val wrongNumber = kotlinx.coroutines.runBlocking { AnswerProof.check("Give zinc for 21 days to the child.", passages, scorer) }
        check("proof: a number no passage has is not supported", !wrongNumber.statements.first().supported)
        val fallback = kotlinx.coroutines.runBlocking { AnswerProof.check("Zinc is given for 14 days to children.", passages, null) }
        check("proof: word-overlap fallback without re-ranker", fallback.statements.first().supported)
        check("proof: marker round trip", AnswerProof.parseMarker(listOf("kbref:S1 :: x", AnswerProof.marker(r))) == (2 to 3))
        val keepMost = AnswerProof.prune("Bananas are yellow fruit here.\nApples grow on trees in hills.", AnswerProof.Result(listOf(
            AnswerProof.Statement("Bananas are yellow fruit here.", -8f, null, true, false),
            AnswerProof.Statement("Apples grow on trees in hills.", -8f, null, true, false))))
        check("proof: never prunes most of an answer", keepMost == null)
    }

    private fun sourceTitles() {
        fun t(x: String) = KnowledgeSourcePresentation.cleanTitle(x)
        check("title: download-page noise removed", t("2 DEFENCE PROCUREMENT MANUAL 2025 (Volume I) Download (653.44 KB)") == "DEFENCE PROCUREMENT MANUAL 2025 (Volume I)") { t("2 DEFENCE PROCUREMENT MANUAL 2025 (Volume I) Download (653.44 KB)") }
        check("title: file name made readable", t("Primary-User-Registration.pdf") == "Primary User Registration") { t("Primary-User-Registration.pdf") }
        check("title: batch code dropped", t("B8-Primary-User-Registration-MoPR.pdf") == "Primary User Registration MoPR") { t("B8-Primary-User-Registration-MoPR.pdf") }
        check("title: long batch code dropped", t("B1-119_1-DEFENCE-PROCUREMENT-MANUAL-2025-Volume-II.pdf") == "DEFENCE PROCUREMENT MANUAL 2025 Volume II") { t("B1-119_1-DEFENCE-PROCUREMENT-MANUAL-2025-Volume-II.pdf") }
        check("title: clean title unchanged", t("Module for ASHA on Non-Communicable Diseases") == "Module for ASHA on Non-Communicable Diseases") { t("Module for ASHA on Non-Communicable Diseases") }
        check("title: Hindi title unchanged", t("जीवन के प्रथम 1000 दिनों की यात्रा") == "जीवन के प्रथम 1000 दिनों की यात्रा")
        check("title: L1 is not a batch code", t("L1-Purchase-Service.pdf") == "L1 Purchase Service") { t("L1-Purchase-Service.pdf") }
        check("title: L-1 keeps its dash", t("B64-Local-Filter-for-L-1-purchases-for-Difficult-Areas.pdf") == "Local Filter for L-1 purchases for Difficult Areas") { t("B64-Local-Filter-for-L-1-purchases-for-Difficult-Areas.pdf") }
        check("title: mixed separators", t("E-Sign-_-DSC-Process.pdf") == "E Sign DSC Process") { t("E-Sign-_-DSC-Process.pdf") }
        check("title: year-first title kept", t("2025 Manual for Procurement of Goods") == "2025 Manual for Procurement of Goods") { t("2025 Manual for Procurement of Goods") }
    }

    private fun lists() {
        fun clauses(q: String) = GenericKbQueryPlanner.plan(q, q).clauses.map { it.literal }
        check("list: 'what is asha, anc and pnc' is three questions",
            clauses("what is asha, anc and pnc") == listOf("what is asha", "what is anc", "what is pnc")) { clauses("what is asha, anc and pnc").toString() }
        check("list: Hinglish 'asha, anc aur pnc kya hai'",
            clauses("asha, anc aur pnc kya hai") == listOf("asha kya hai", "anc kya hai", "pnc kya hai")) { clauses("asha, anc aur pnc kya hai").toString() }
        check("list: two-word items",
            clauses("explain ANC visits, IFA dose and TT injection") == listOf("explain ANC visits", "explain IFA dose", "explain TT injection")) {
                clauses("explain ANC visits, IFA dose and TT injection").toString() }
        check("list: a comparison stays one question",
            clauses("difference between ASHA, ANM and AWW").size == 1)
        check("list: a two-item question stays one question", clauses("what is ASHA and ANM").size == 1)
        check("list: an instruction sentence is not split", clauses("If BP is high, refer and record").size == 1)
        check("list: separate questions still split on '?'", clauses("what is ASHA? what is ANC?").size == 2)
    }

    private fun evidenceFraming() {
        val raw = "[E1 | S1]\nSource ID: S1\nSource: ASHA module 6 p.12\nASHA stands for Accredited Social Health Activist [S1]."
        val clean = FixSightOutputSanitizer.sanitize(raw)
        check("raw dump: evidence headers never reach the user",
            !clean.contains("[E1") && !clean.contains("Source ID") && !clean.contains("Source:")) { clean }
        check("raw dump: the answer and its citation survive", clean == "ASHA stands for Accredited Social Health Activist [S1].") { clean }
        check("raw dump: inline header becomes a citation",
            FixSightOutputSanitizer.sanitize("IFA is given daily [E2 | S3].") == "IFA is given daily [S3].") { FixSightOutputSanitizer.sanitize("IFA is given daily [E2 | S3].") }
        check("raw dump: normal prose mentioning sources is kept",
            FixSightOutputSanitizer.sanitize("The source of iron is green vegetables.") == "The source of iron is green vegetables.")
    }

    private fun citations() {
        val packet = PocketRagPacket(
            selection = PocketRagSelection("generic", "asha", "ASHA"),
            query = "what is photosynthesis",
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "ASHA registers pregnant women and ensures four antenatal checkups.")),
            sources = listOf(PocketRagSource("S1", "ASHA module p.4")),
            coverageComplete = true
        )
        val general = "Photosynthesis is the process by which green plants convert sunlight into chemical energy [S1]."
        check("citations: a general answer with a habitual [S1] shows no KB source", packet.citedSourceIds(general).isEmpty()) {
            packet.citedSourceIds(general).toString() }
        val grounded = "ASHA registers pregnant women and ensures four antenatal checkups [S1]."
        check("citations: a grounded answer keeps its source", packet.citedSourceIds(grounded).size == 1)
    }

    private fun prompt() {
        val packet = PocketRagPacket(
            selection = PocketRagSelection("generic", "asha", "ASHA"),
            query = "ifa dose",
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "• One IFA tablet daily.")),
            sources = listOf(PocketRagSource("S1", "ASHA module p.4")),
            coverageComplete = true
        )
        val system = PocketRagPromptBuilder.buildParts(packet, emptyList()).systemAddon
        check("prompt: KB answers never switch to general knowledge", !system.contains("general knowledge"))
        check("prompt: not-in-evidence marker requested", system.contains("[[NOT_IN_EVIDENCE]]"))
        check("prompt: numbers and doses copied exactly", system.contains("doses") && system.contains("exactly"))
    }

    private fun coreFacts() {
        fun ids(q: String) = CoreHealthFacts.topicsFor(q).map { it.id }
        check("facts: ORS question gets the ORS recipe", ids("What is ORS and how do I prepare it at home?").first() == "ors")
        check("facts: ORS recipe numbers", CoreHealthFacts.systemAddon("how to make ORS")!!.contains("6 level teaspoons of sugar and half (½) a level teaspoon of salt in 1 litre"))
        check("facts: Hinglish diarrhoea", "diarrhoea" in ids("bacche ko dast ho toh kya karna chahiye?"))
        check("facts: Hindi diarrhoea", "diarrhoea" in ids("बच्चे को दस्त हो रहे हैं"))
        check("facts: zinc folded into diarrhoea", ids("zinc dose for diarrhoea").let { "diarrhoea" in it && "zinc" !in it })
        check("facts: zinc alone", ids("how much zinc for a 1 year old") == listOf("zinc"))
        check("facts: Hindi cold newborn", "newborn" in ids("नवजात बच्चा दूध नहीं पी रहा और ठंडा है, क्या करूँ?"))
        check("facts: newborn temperature in °C", CoreHealthFacts.systemAddon("newborn is cold")!!.contains("37.5 °C or more, or less than 35.5 °C"))
        check("facts: after delivery", "after_delivery" in ids("A woman is bleeding heavily after delivery at home"))
        check("facts: pregnancy", "pregnancy_danger" in ids("What are the danger signs in pregnancy?"))
        check("facts: dog bite Hinglish", "dog_bite" in ids("kutte ne kaat liya hai kya karein?"))
        check("facts: snake", "snake_bite" in ids("Someone was bitten by a snake"))
        check("facts: child fast breathing Hinglish", "child_breathing_fever" in ids("1 saal ke bacche ki saans tez chal rahi hai"))
        check("facts: none for the ASHA role", ids("आशा कार्यकर्ता का मुख्य काम क्या है?").isEmpty())
        check("facts: none for news", ids("Who is the health minister of India right now?").isEmpty())
        check("facts: none for adult fever", ids("I have fever and headache since yesterday").isEmpty())
        check("facts: none for a vaccination reminder", ids("Write a short message reminding mothers that Thursday is vaccination day.").isEmpty())
        check("facts: at most two topics", ids("pregnant woman with diarrhoea, dog bite and snake bite").size <= 2)
        check("facts: ORS brings diarrhoea danger signs", ids("How do I make ORS at home?") == listOf("ors", "diarrhoea"))
        check("facts: Hindi question gets Hindi facts", CoreHealthFacts.systemAddon("नवजात बच्चा ठंडा है")!!.contains("37.5 °C या ज़्यादा, या 35.5 °C से कम"))
        check("facts: every topic has Hindi", CoreHealthFacts.TOPICS.all { it.hindi.isNotBlank() })
        check("facts: Hindi keeps the numbers", CoreHealthFacts.TOPICS.all { t ->
            Regex("\\d+(?:\\.\\d+)?").findAll(t.facts).map { it.value }.toSet() == Regex("\\d+(?:\\.\\d+)?").findAll(t.hindi).map { it.value }.toSet()
        }) { CoreHealthFacts.TOPICS.joinToString { t -> t.id + ":" + (Regex("\\d+(?:\\.\\d+)?").findAll(t.facts).map { it.value }.toSet() - Regex("\\d+(?:\\.\\d+)?").findAll(t.hindi).map { it.value }.toSet()) + "/" + (Regex("\\d+(?:\\.\\d+)?").findAll(t.hindi).map { it.value }.toSet() - Regex("\\d+(?:\\.\\d+)?").findAll(t.facts).map { it.value }.toSet()) } }
        val hindiOrs = "घर पर ओआरएस बनाने के लिए: नमक और चीनी के समतल छोटे टुकड़े लें। एक साफ़ प्लास्टिक की बोतल में 1 लीटर साफ़ पानी डालें, फिर इन टुकड़ों को पानी में घुलाएँ।"
        CoreHealthFacts.checkAnswer("घर पर ओआरएस कैसे बनाएँ?", hindiOrs).let { out ->
            check("facts check: Hindi ORS without amounts gets the recipe line", out.contains(CoreHealthFacts.LABEL_HI) && out.contains("6 समतल चम्मच चीनी")) { out }
        }
        val goodOrs = "To make ORS at home, dissolve 6 level teaspoons of sugar and half (½) a level teaspoon of salt in 1 litre of clean drinking water."
        check("facts check: a correct ORS answer is unchanged", CoreHealthFacts.checkAnswer("How do I make ORS at home?", goodOrs) == goodOrs)
        val invented = "Mix 6 level teaspoons of sugar and half a teaspoon of salt in 1 litre of water. Give small sips. Keep breastfeeding. Refer if there is blood in the stool. Refer if fever is above 102.5°F (39°C)."
        CoreHealthFacts.checkAnswer("Explain in more detail.", invented, "How do I make ORS at home?").let { out ->
            check("facts check: invented temperature removed", !out.contains("102.5") && out.contains("6 level teaspoons")) { out }
        }
        val splitRecipe = "घर पर ओआरएस बनाने के लिए:\n1. 1 लीटर साफ़ पीने के पानी में 6 समतल चम्मच चीनी घोलें।\n2. 1 लीटर साफ़ पीने के पानी में आधा (½) समतल चम्मच नमक घोलें।\n3. दोनों घोलों को अलग-अलग बोतलों में रखें।\n4. इस्तेमाल करने से पहले घोल को अच्छी तरह से हिलाएँ।"
        CoreHealthFacts.checkAnswer("घर पर ओआरएस कैसे बनाएँ?", splitRecipe).let { out ->
            check("facts check: split ORS recipe fixed", !out.contains("अलग") && out.contains(CoreHealthFacts.LABEL_HI + "घर का ओआरएस: 1 लीटर साफ़ पीने के पानी में 6 समतल चम्मच चीनी और आधा")) { out }
        }
        val howMuch = "For a 1-year-old child, give 50-100 ml (¼ to ½ cup) of ORS after each loose stool."
        check("facts check: 'how much ORS' gets no recipe line", CoreHealthFacts.checkAnswer("And how much ORS after each loose stool?", howMuch) == howMuch)
        check("facts check: no topic, no change", CoreHealthFacts.checkAnswer("Write a reminder for vaccination day", "Thursday is vaccination day, 10 am.") == "Thursday is vaccination day, 10 am.")
        CoreHealthFacts.checkAnswer("How much zinc for a 1 year old?", "Give 20 mg (one tablet) of zinc once a day for 14 days.").let { out ->
            check("facts check: right zinc dose kept", out == "Give 20 mg (one tablet) of zinc once a day for 14 days.") { out }
        }
        CoreHealthFacts.checkAnswer("How much zinc for a 1 year old?", "Give zinc 40 mg daily. Continue ORS. Keep feeding the child. Watch for danger signs.").let { out ->
            check("facts check: wrong zinc dose removed and key line added", !out.contains("40 mg") && out.contains("20 mg a day for 6 months to 5 years")) { out }
        }
        CoreHealthFacts.checkUncoveredHealthAnswer("What medicine for high blood pressure?", "Amlodipine 5 mg once a day is commonly used. Check BP every week. Refer if BP is 180/110 or more.").let { out ->
            check("uncovered health: invented dose removed with a note", !out.contains("5 mg") && out.contains("Check BP every week") && out.contains(CoreHealthFacts.UNVERIFIED_DOSE_EN)) { out }
        }
        check("uncovered health: no dose, no change", CoreHealthFacts.checkUncoveredHealthAnswer("What causes malaria?", "Malaria is spread by mosquito bites.") == "Malaria is spread by mosquito bites.")
        check("uncovered health: not health, no change", CoreHealthFacts.checkUncoveredHealthAnswer("How many ml in a litre?", "There are 1000 ml in a litre.") == "There are 1000 ml in a litre.")
        val passage = "Rule 171. Performance security is obtained from the bidder. Performance security should be for an amount of three to five per cent of the value of the contract. It may be a bank guarantee or a fixed deposit receipt. It must remain valid for sixty days beyond the warranty period."
        val ans = "Performance security should be three to five per cent of the contract value [S1]. It must remain valid for sixty days beyond the warranty period [S1]."
        KnowledgeSourcePresentation.supportingSentences(passage, ans).let { hl ->
            check("highlight: every supporting sentence is marked", hl.size == 2 && hl[0].contains("three to five") && hl[1].contains("sixty days")) { hl.toString() }
        }
        KnowledgeSourcePresentation.passagesForRows(listOf("kbref:S1 :: Manual — page 4", KnowledgeSourcePresentation.passageMarker("S1", passage, ans))).let { rows ->
            check("highlight: marker carries both sentences", rows[0][0].highlights.size == 2) { rows.toString() }
        }
        KnowledgeSourcePresentation.supportingSentences("(ii) Under Ayushman Bharat, an annual untied fund is provided @ Rs. 50,000 for SHC level AB-HWCs and Rs.1,75,000 for PHC level AB-HWCs. (iii) Other text about amenities follows here.", "SHC level AB-HWCs get an annual untied fund of Rs. 50,000 [S1].").let { hl ->
            check("highlight: 'Rs. 50,000' stays in one sentence", hl.size == 1 && hl[0].contains("Rs. 50,000 for SHC")) { hl.toString() }
        }
        KnowledgeSourcePresentation.supportingSentences("Operational Guidelines 28 S.No Mental Health Condition Suggested Medications 1 Common Mental Disorders (CMD) Tab. Amitriptyline Cap. Escitalopram/Fluoxetine. 2 Severe Mental Disorders (SMD) Tab. Risperidone Tab. Trihexyphenidyl.",
            "The suggested medications for Common Mental Disorders are:\n\nAmitriptyline\nEscitalopram/Fluoxetine").let { hl ->
            check("highlight: list items mark the table row", hl.size == 1 && hl[0].contains("Amitriptyline")) { hl.toString() }
        }
        KnowledgeSourcePresentation.supportingSentences("3.3.3 शारीरिक गतिविधि। वयस्कों को हफ़्ते में कम से कम 150 मिनट का साधारण व्यायाम करना चाहिए। इसे पूरे सप्ताह में बांटा जा सकता है।",
            "हफ़्ते में कम से कम 150 मिनट का साधारण व्यायाम करना चाहिए। [1]").let { hl ->
            check("highlight: Hindi sentence is marked", hl.size == 1 && hl[0].contains("150 मिनट")) { hl.toString() }
        }
        check("highlight: unrelated answer marks nothing", KnowledgeSourcePresentation.supportingSentences(passage, "Bids are opened in public by the committee.").isEmpty())
        check("script: Hindi question with English terms is Hindi", KbReplyText.dominantScript("SHC स्तर के AB-HWC को कितना untied fund मिलता है?") == KbReplyText.Script.DEVANAGARI)
        check("script: English sentence with one Hindi word stays English", KbReplyText.dominantScript("What is the role of the आशा worker in immunization?") == KbReplyText.Script.LATIN)
        check("split: 'Rs. 1,75,000' is one statement", AnswerProof.statements("A PHC level AB-HWC gets Rs. 1,75,000 as untied fund every year.").single().contains("1,75,000"))
        check("split: 'Tab. Amitriptyline' is not a sentence end", "Give Tab. Amitriptyline at night. Review in two weeks.".split(KbReplyText.SENTENCE_SPLIT).size == 2)
        check("topic: 'person' is not a topic word", AnswerProof.missingTopicWords("How should a person with diabetes cut their toenails?", listOf("Clip toenails straight across. Diabetes foot care: check feet daily.")).isEmpty())
        val gate = PocketRagQualityGate()
        check("gate: answer that stops at a colon is incomplete", gate.incompleteAnswer("गैर-वित्तीय मानकों को अधिकतम कितना भार दिया जा सकता है?", "गैर-वित्तीय मानकों को अधिकतम कितना भार दिया जा सकता है? इसके लिए तीन अलग-अलग स्रोत हैं:") != null)
        check("gate: answer that only repeats the question is incomplete", gate.incompleteAnswer("What is the bid security amount?", "What is the bid security amount? [1]") == "echoes_question")
        check("gate: a short real answer is complete", gate.incompleteAnswer("What is the bid security amount?", "Bid security is 2% to 5% of the estimated value [1].") == null)
        check("gate: a very short answer is complete", gate.incompleteAnswer("Within how many days is payment made?", "30 days [1].") == null)
        check("gate: a list after a lead-in is complete", gate.incompleteAnswer("Which medicines?", "The medicines are:\n\nAmitriptyline\nEscitalopram") == null)
        GenericKbQuestionRewrite.parse("Here are the rewritten questions:\n1. Question: room ka rent kitna dena hai\nSEARCH: How much rent must be paid for a room?\nCALC: NONE\n2. Question: bacche ko dast mein zinc kitne din tak dena hai?\nSEARCH: For how many days should zinc be given to a child with diarrhoea?\nCALC: NONE",
            "bacche ko dast mein zinc kitne din tak dena hai?").let { r ->
            check("rewrite: the line after the echoed question is used", r?.search == "For how many days should zinc be given to a child with diarrhoea?") { r.toString() }
        }
        check("rewrite: prompt ends with the instruction", GenericKbQuestionRewrite.prompt("zinc kitne din", emptyList()).trim().endsWith("Write only the SEARCH and CALC lines for this last question."))
        check("rewrite: Hinglish question keeps its English rewrite", GenericKbQuestionRewrite.parse("SEARCH: When should a newborn be given the first breastfeed?\nCALC: NONE", "navjaat shishu ko pehla doodh kab pilana chahiye?")?.search?.startsWith("When should a newborn") == true)
        check("topic: anemia matches anaemia", AnswerProof.missingTopicWords("When should a woman with severe anemia be retested?", listOf("For severe anaemia, retesting is done after 1 month of treatment.")).isEmpty())
        val wp = "7.4.2 Work Program: The Contractor shall submit a Work Program within the time stipulated in the Contract (or four weeks from the issue of the LoA). The Contractor will update the program at intervals stipulated (usually every month) in the Contract."
        KnowledgeSourcePresentation.chooseQuote("Within how much time must the contractor submit the work programme?", "", "The contractor should submit the work program usually every month.", listOf("Works Manual — page 185" to wp))!!.let { (q, src) ->
            check("quote: the sentence that answers the question", q.contains("four weeks") && src.contains("page 185")) { q }
        }
        KnowledgeSourcePresentation.chooseQuote("गर्भवती महिला को आईएफए की गोलियाँ कितने दिन खानी चाहिए?", "How many days should pregnant women take IFA tablets?", "180 दिन",
            listOf("AMB — page 49" to "Pregnant women: Daily one Red-IFA tablet for 180 days starting from the second trimester of pregnancy. Each tablet contains 60 mg elemental iron."))!!.let { (q, _) ->
            check("quote: Hindi question matched through the English rewrite", q.contains("180 days")) { q }
        }
        KnowledgeSourcePresentation.chooseQuote("How should ORS be prepared?", "", "Mix one packet in one litre of water.", listOf("HBYC — page 68" to "Wash your hands thoroughly with soap and water. Pour all the ORS powder from a packet into a clean container. Measure one litre of clean drinking water and pour it into the container."))!!.let { (q, _) ->
            check("quote: acronym and word stems match (ORS, prepared ~ preparation)", q.contains("ORS powder") || q.contains("one litre")) { q }
        }
        check("quote: numbers that disagree are flagged", KnowledgeSourcePresentation.quoteDisagrees("Daily one IFA tablet for 180 days.", "IFA tablets for 100 days."))
        check("quote: numbers that agree are not flagged", !KnowledgeSourcePresentation.quoteDisagrees("Daily one IFA tablet for 180 days.", "Take one tablet daily for 180 days [1]."))
        check("quote: no shared word, no quote", KnowledgeSourcePresentation.chooseQuote("What is the capital of France?", "", "Paris", listOf("X" to "Bid security is two to five per cent of the estimated value.")) == null)
        KnowledgeSourcePresentation.quoteOf(listOf("kbref:S1 :: X", KnowledgeSourcePresentation.quoteMarker("Daily one IFA tablet for 180 days.", "AMB — page 49", true)))!!.let { q ->
            check("quote: marker round trip", q.text.startsWith("Daily one") && q.source == "AMB — page 49" && q.mismatch)
        }
        check("quote: marker is internal", KnowledgeSourcePresentation.isInternalMarker(KnowledgeSourcePresentation.quoteMarker("a b c d", "s", false)))
        check("hinglish: detected", CoreHealthFacts.isHinglish("bacche ko dast ho toh kya karna chahiye?"))
        check("hinglish: detected 2", CoreHealthFacts.isHinglish("kutte ne kaat liya hai kya karein?"))
        check("hinglish: not English", !CoreHealthFacts.isHinglish("What should I do if a child has diarrhoea and is not eating?"))
        check("hinglish: not English with 'to me'", !CoreHealthFacts.isHinglish("Explain to me how to count breathing in a child"))
        check("hinglish: not Devanagari", !CoreHealthFacts.isHinglish("बच्चे को दस्त हो तो क्या करें"))
    }

    private fun naturalPhrasing() {
        val n = KnowledgeSourcePresentation::naturalPhrasing
        check("phrasing: [S2] states that", n("Refer promptly [S1].\n\n[S2] states that fever in a newborn is a danger sign.") ==
            "Refer promptly [S1].\n\nFever in a newborn is a danger sign [S2].") { n("Refer promptly [S1].\n\n[S2] states that fever in a newborn is a danger sign.") }
        check("phrasing: [S1] also mentions", n("[S1] also mentions that you should monitor the baby's temperature.") == "You should monitor the baby's temperature [S1].") { n("[S1] also mentions that you should monitor the baby's temperature.") }
        check("phrasing: according to the evidence", n("According to the evidence, if a newborn has fever, refer the baby.") == "If a newborn has fever, refer the baby.") { n("According to the evidence, if a newborn has fever, refer the baby.") }
        check("phrasing: the document mentions", n("Keep the baby warm. The document also mentions that breastfeeding should continue.") == "Keep the baby warm. Breastfeeding should continue.") { n("Keep the baby warm. The document also mentions that breastfeeding should continue.") }
        check("phrasing: e.g. unchanged", n("Watch for danger signs (e.g. poor feeding, fever) [S1].") == "Watch for danger signs (e.g. poor feeding, fever) [S1].")
        check("phrasing: normal sentence unchanged", n("The baby should be referred if the temperature stays high [S1].") == "The baby should be referred if the temperature stays high [S1].")
        val shown = KnowledgeSourcePresentation.renumberCitations("[S2] states that fever is a danger sign.", listOf("kbref:S1 :: Module p.41"))
        check("phrasing: unconfirmed source leaves a whole sentence", shown == "Fever is a danger sign.") { shown }
    }

    private fun coverage() {
        val ev = "Acceptance of Necessity (AoN) is accorded by the CFA. Before according Expenditure Angle Sanction (EAS), concurrence of Integrated Finance shall be taken."
        check("coverage: comparison of two covered terms", AnswerProof.questionTermsCovered("what is the differemce between aon and eas", ev))
        check("coverage: a term not in the evidence", !AnswerProof.questionTermsCovered("difference between aon and gem", ev))
        check("coverage: Hindi is not checked", !AnswerProof.questionTermsCovered("एओएन क्या है", ev))
    }

}
