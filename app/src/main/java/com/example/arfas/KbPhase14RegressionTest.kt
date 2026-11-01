package com.example.arfas

/**
 * Standalone Phase 1.4 regression checks (no Android, no model, no database).
 * Run: kotlinc ... && kotlin -cp ... com.example.arfas.KbPhase14RegressionTest
 * Each check mirrors a verified ground-truth case or an agreed negative case.
 */
object KbPhase14RegressionTest {
    private var passed = 0
    private fun check(name: String, condition: Boolean, detail: () -> String = { "" }) {
        if (!condition) throw AssertionError("FAIL $name ${detail()}")
        passed++
        println("PASS $name")
    }

    private fun view(heading: String, content: String, locator: String = "", source: String = "") =
        GenericKbIdentifierLane.ChunkView(heading, locator, content, source)

    private fun structure(): GenericKbIdentifierLane.StructureIndex {
        val b = GenericKbIdentifierLane.StructureIndex.Builder()
        // The pack enumerates rules, paragraphs and chapters; amounts appear after "Rs".
        for (n in 150..170) b.add("Rule $n Some Heading", "", "Rule $n Some Heading\n(i) text")
        for (n in 1..12) b.add("Para $n Heading", "", "Para $n. body text")
        b.add("", "page:161", "Rs. 25 lakh limit\nRs. 200 crore ceiling\nRs. 50,000 purchase\nRs. 11 lakh")
        b.add("", "", "Rs. 5 lakh. Rs. 90 lakh. Rs. 300 crore.")
        // Consecutive amounts in table rows only: must still not become a structural label.
        for (n in 1..9) b.add("Price schedule", "", "Rs. $n per unit")
        return b.build()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        identifierLane()
        profileAndRanking()
        planning()
        engineEndToEnd()
        sourcesAndSections()
        coverageDisclosure()
        followUpsAndProcedures()
        packLibrary()
        generationAcceptance()
        smallModelPath()
        liveQuestions()
        abbreviationQuestions()
        repeatedRuleOnce()
        expansionGuard()
        println("KbPhase14RegressionTest: $passed checks passed")
    }

    private fun identifierLane() {
        definitionsVersusMentions()
        val index = structure()
        check("structural labels learned from the pack", "rule" in index.structuralLabels && "para" in index.structuralLabels) {
            index.structuralLabels.toString()
        }
        check("amount label is not structural", "rs" !in index.structuralLabels) { index.structuralLabels.toString() }

        val ids = GenericKbIdentifierLane.parseQuery("what ks gfr rule 161.2 and rs 11.11 lakh", index)
        val rule = ids.single { it.parent == "161" }
        val amount = ids.single { it.parent == "11" }
        check("rule 161.2 is a key with child 2", rule.isKey && rule.childOrdinal == 2 && rule.label == "rule") { rule.toString() }
        check("rs 11.11 is a value, never a key", !amount.isKey) { amount.toString() }
        check("date is not parsed as 11.11", GenericKbIdentifierLane.parseQuery("order dated 11.11.2021", index).none { it.parent == "11" && it.childOrdinal == 11 })
        val one = GenericKbIdentifierLane.parseQuery("GFR Rule 161.1", index).single()
        check("rule 161.1 parses as child 1", one.isKey && one.childOrdinal == 1)
        val bare = GenericKbIdentifierLane.parseQuery("what is 161.2", index).single()
        check("unlabelled 161.2 stays a value", !bare.isKey)

        val anchors = setOf("gfr")
        // Verified GFR page-38 layout: parent in heading, children as body enumerators.
        val gfr = view(
            heading = "Rule 161 Advertised Tender Enquiry | procurement of | Scientific Equipment",
            content = "Rule 161 Advertised Tender Enquiry\n(i) Subject to exceptions incorporated under Rule 154, 155 and 166, invitation to tenders by advertisement should be used.\n(ii) The organisation should post the complete bidding document on its website and GeM-CPPP.",
            locator = "page:38",
            source = "General Financial Rules 2017"
        )
        val a2 = GenericKbIdentifierLane.admit(rule, gfr, anchors)
        check("161.2 admitted hierarchically from GFR", a2?.kind == GenericKbIdentifierLane.Kind.HIERARCHICAL) { a2.toString() }
        val a1 = GenericKbIdentifierLane.admit(one, gfr, anchors)
        check("161.1 admitted hierarchically from GFR", a1?.kind == GenericKbIdentifierLane.Kind.HIERARCHICAL) { a1.toString() }

        // Negative: 161(iv) must never answer 161.1.
        val iv = view("PLI Scheme", "in accordance with Rule 161(iv)(b) of GFR, 2017, global tender enquiry shall not be issued")
        val aIv = GenericKbIdentifierLane.admit(one, iv, anchors)
        check("161(iv) never admitted for 161.1", aIv == null || aIv.kind != GenericKbIdentifierLane.Kind.EXACT) { aIv.toString() }
        check("in-sentence 161(iv) mention creates no hierarchy for 161.1", aIv == null) { aIv.toString() }

        // Negative: a page locator is never parent evidence.
        val page = view("Annexure", "(ii) unrelated item listed on this page", locator = "page:161")
        check("page:161 locator is not a parent", GenericKbIdentifierLane.admit(rule, page, anchors) == null)

        // Negative: Rule 162's (ii) must not answer 161.2.
        val sibling = view("", "Rule 161 Advertised Tender Enquiry\n(i) General rule.\nRule 162 Limited Tender Enquiry\n(i) This method may be adopted.\n(ii) Purchase through limited tender.")
        val aSib = GenericKbIdentifierLane.admit(rule, sibling, setOf("tender"))
        check("Rule 162 (ii) excluded for 161.2", aSib?.kind == GenericKbIdentifierLane.Kind.PARENT_ONLY) { aSib.toString() }

        // Negative: a bare number in a table without any co-anchor.
        val crore = view("", "| item | 161.2 crore | total |")
        check("161.2 crore table cell not admitted", GenericKbIdentifierLane.admit(rule, crore, setOf("gfr")) == null)

        // Negative: an in-sentence reference is not a section opening.
        val mention = view("Question 6", "Procurement of imported item is governed by Rule 161 of GFR. Other points: (i) first (ii) second")
        check("in-sentence Rule 161 mention creates no hierarchy", GenericKbIdentifierLane.admit(rule, mention, anchors) == null)

        // Parent fallback: section exists, child absent.
        val parentOnly = view("Rule 161 Advertised Tender Enquiry", "(iii) Minimum three weeks shall be given.")
        check("parent-only fallback when child absent", GenericKbIdentifierLane.admit(rule, parentOnly, anchors)?.kind == GenericKbIdentifierLane.Kind.PARENT_ONLY)

        // Exact adjacent forms are still recognised.
        val adj = view("", "as provided in Rule 161 (ii) of GFR the document shall be posted")
        check("adjacent 161 (ii) form is EXACT", GenericKbIdentifierLane.admit(rule, adj, anchors)?.kind == GenericKbIdentifierLane.Kind.EXACT)
        val dotted = view("", "see Rule 161.2 for posting requirements")
        check("adjacent 161.2 form is EXACT", GenericKbIdentifierLane.admit(rule, dotted, anchors)?.kind == GenericKbIdentifierLane.Kind.EXACT)

        // Script-neutral: a Devanagari label opening a section after the Hindi full stop.
        val hb = GenericKbIdentifierLane.StructureIndex.Builder()
        for (n in 1..20) hb.add("नियम $n खरीद", "", "नियम $n खरीद\n(i) पहला")
        val hIndex = hb.build()
        val hKey = GenericKbIdentifierLane.parseQuery("नियम 12.2 क्या है", hIndex).single()
        val hView = view("", "पिछला भाग समाप्त। नियम 12 खरीद\n(i) पहला\n(ii) दूसरा बिंदु")
        check("Devanagari label and danda boundary work", hKey.isKey &&
            GenericKbIdentifierLane.admit(hKey, hView, emptySet())?.kind == GenericKbIdentifierLane.Kind.HIERARCHICAL)
        check("locator text is never parent evidence", GenericKbIdentifierLane.admit(rule, view("", "(ii) item", locator = "Rule 161"), anchors) == null)

        // A value is never admitted even where the digits appear.
        check("value numbers never admitted", GenericKbIdentifierLane.admit(amount, view("Rule 11", "(xi) Rs 11.11 lakh"), setOf("lakh")) == null)
    }

    private fun unit(vararg v: Float): FloatArray {
        val n = kotlin.math.sqrt(v.fold(0f) { a, x -> a + x * x })
        return FloatArray(v.size) { v[it] / n }
    }

    private fun basis(i: Int, dim: Int = 8, common: Float): FloatArray =
        unit(*FloatArray(dim) { j -> if (j == 0) common else if (j == i) 1f else 0f })

    /** "What is GFR Rule 144?": the rule book's own Rule 144 must lead, not the manuals that refer to it. */
    private fun definitionsVersusMentions() {
        val b = GenericKbIdentifierLane.StructureIndex.Builder()
        for (n in 130..180) b.add("Rule $n Heading $n", "", "Rule $n Heading $n. Text of rule $n.", "rulebook")
        b.add("Restrictions under Rule 144 (xi) of the GFR", "", "(22)", "manual")
        b.add("Removal of difficulties", "", "1.11.3 Restrictions on bidders (Rule 144 (xi), GFR 2017) apply.", "manual")
        b.add("Click on Confirm", "", "Certify rate reasonability. Rule 149 of GFR of 2017 provides that the buyer shall certify.", "gem-guide")
        b.add("FORM GFR 4", "", "144 FORM GFR 4 OBJECT HEAD WISE SUMMARY", "rulebook")
        for (n in 1..12) b.add("Para $n Heading", "", "Para $n. body", "manual")
        val index = b.build()
        check("the rule book enumerates rules around 144", index.enumerates("rulebook", "rule", "144"))
        check("a manual quoting Rule 144 does not enumerate rules", !index.enumerates("manual", "rule", "144"))
        check("a guide quoting Rule 149 does not enumerate rules", !index.enumerates("gem-guide", "rule", "149"))
        check("structure index round-trips per-document openings", run {
            val f = java.io.File.createTempFile("p14", ".structure2").also { it.deleteOnExit() }
            java.io.DataOutputStream(f.outputStream()).use { index.writeTo(it) }
            val r = java.io.DataInputStream(f.inputStream()).use { GenericKbIdentifierLane.StructureIndex.readFrom(it) }
            r.enumerates("rulebook", "rule", "144") && !r.enumerates("manual", "rule", "144")
        })
        val key = GenericKbIdentifierLane.parseQuery("What is GFR Rule 144?", index).single()
        val anchors = setOf("gfr")
        fun admit(heading: String, content: String, doc: String) = GenericKbIdentifierLane.admit(
            key, GenericKbIdentifierLane.ChunkView(heading, "", content, "General Financial Rules", doc), anchors
        )?.let { a -> if (index.enumerates(doc, key.label, key.parent)) a else a.copy(enumerated = false) }
        val own = admit("Ch. 6 PROCUREMENT OF GOODS", "The term goods includes services. Rule 144 Fundamental principles of public buying.", "rulebook")
        check("the rule book's Rule 144 defines the section", own?.defines == true) { own.toString() }
        val mention = admit("Restrictions under Rule 144 (xi) of the GFR", "(22)", "manual")
        check("a heading that refers to Rule 144 is a mention", mention != null && mention.evidence == GenericKbIdentifierLane.HEADING_MENTION && !mention.defines) { mention.toString() }
        check("a mention gets less identifier bonus than a defining line",
            GenericKbRetrievalPlan.identifierBonus(mention) < GenericKbRetrievalPlan.identifierBonus(own!!))
        val pageNumber = admit("FORM GFR 4", "144 FORM GFR 4 OBJECT HEAD WISE SUMMARY", "rulebook")
        check("an unlabelled page number is not Rule 144", pageNumber == null || !pageNumber.defines) { pageNumber.toString() }
        val key149 = GenericKbIdentifierLane.parseQuery("What is GFR Rule 149?", index).single()
        val quote = GenericKbIdentifierLane.admit(key149, GenericKbIdentifierLane.ChunkView("Click on Confirm", "",
            "Certify rate reasonability. Rule 149 of GFR of 2017 provides that the buyer shall certify.", "GeM guide", "gem-guide"), anchors)
            ?.let { if (index.enumerates("gem-guide", key149.label, key149.parent)) it else it.copy(enumerated = false) }
        check("a guide quoting Rule 149 at a sentence start does not define it", quote != null && !quote.defines) { quote.toString() }
    }

    /** A broken or unsupported generation is never shown, saved or remembered. */
    private fun generationAcceptance() {
        // Repetition gate: glued and spaced loops in any script; ordinary text, tables and citations pass.
        check("glued loop is degenerate", KbReplyText.degenerateRepetition("ASHA should visit " + "hein".repeat(40)) != null)
        check("spaced Hindi loop is degenerate", KbReplyText.degenerateRepetition("उत्तर: " + "है ".repeat(12)) != null)
        check("repeated phrase loop is degenerate",
            KbReplyText.degenerateRepetition("Visit the mother. " + "The ASHA visits the pregnant woman before delivery. ".repeat(5)) != null)
        val normal = "ASHA makes five additional home visits for each young child, at 3, 6, 9, 12 and 15 months [1]. " +
            "She records each visit on the HBYC card, which is the basis for her incentive, and the MCP card stays with the mother [1]."
        check("a normal answer is not degenerate", KbReplyText.degenerateRepetition(normal) == null) { KbReplyText.degenerateRepetition(normal).orEmpty() }
        check("identical table cells are not degenerate",
            KbReplyText.degenerateRepetition("| Visit | Weight | Feeding |\n| Yes | Yes | Yes | Yes | Yes | Yes |") == null)
        check("repeated citations are not degenerate", KbReplyText.degenerateRepetition("Folic acid is needed daily [1] [1] [1] [1] [1] [1] [1].") == null)
        check("the Hindi folic acid answer is not degenerate",
            KbReplyText.degenerateRepetition("फोलिक एसिड की दैनिक आवश्यकता 400 माइक्रोग्राम है। गर्भधारण से 3 माह पहले से फोलिक एसिड युक्त भोजन खाएं [1]।") == null)

        val evidence = "Quarterly home visits under HBYC will need to be carefully planned as this intervention covers children in various age groups. " +
            "To plan your visits, you need to have the following: 1. ASHA diary/register- To help you list all young children between 3 months-15 months of age in your area. " +
            "2. HBYC card- HBYC card will be used to record details of activities undertaken by you for receiving incentives for five additional home visits for each young child. " +
            "HBYC card will be the basis for your payment. The MCP card will be retained by the mother/ caregiver. 3. Job-Aid- Job-Aid will be provided to you to conduct the home visits more effectively."
        fun asha(q: String) = PocketRagPacket(
            selection = PocketRagSelection("generic", "asha", "ASHA"),
            query = q,
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", evidence)),
            sources = listOf(PocketRagSource("S1", "Handbook for ASHA on HBYC p.17")),
            coverageComplete = true
        )
        val gate = PocketRagQualityGate()
        val q = "How should ASHA plan HBYC home visits?"
        val good = "To plan HBYC home visits, keep your ASHA diary or register with the list of young children aged 3 to 15 months in your area [1].\n" +
            "Record each activity on the HBYC card; it is the basis for your incentive for the five additional home visits [1].\n" +
            "Use the Job-Aid during the visits, and leave the MCP card with the mother or caregiver [1]."
        val goodDecision = gate.evaluate(q, good, asha(q))
        check("a faithful paraphrased answer is accepted", goodDecision.acceptLocal) { goodDecision.reason }
        val unrelated = "The ASHA should plan the tender carefully before publishing the bid on the portal.\n" +
            "Bidders upload the technical and financial documents and the buyer evaluates the technical bids first.\n" +
            "After evaluation, the contract is awarded to the lowest responsive bidder within the validity period."
        val unrelatedDecision = gate.evaluate(q, unrelated, asha(q))
        check("a fluent answer not traceable to the evidence is rejected",
            !unrelatedDecision.acceptLocal && unrelatedDecision.reason.startsWith("low_evidence_support")) { unrelatedDecision.reason }
        val garbage = "ASHA home visits HBYC " + "heinhein".repeat(20)
        check("garbage sharing the question's words is rejected", !gate.evaluate(q, garbage, asha(q)).acceptLocal)
        val hindiFromEnglish = gate.evidenceSupport("आशा को 3 से 15 माह के बच्चों की सूची अपनी डायरी में रखनी चाहिए और हर गृह भ्रमण HBYC कार्ड पर दर्ज करना चाहिए।", evidence)
        check("a Hindi answer from English evidence is not judged word by word", hindiFromEnglish == null) { hindiFromEnglish.toString() }

        // Engine: a looping generation is retried once; a second loop gives the safe failure, which
        // is marked not accepted (so it is not saved as history or used as follow-up memory).
        fun engine(first: () -> String?, second: () -> String?): Pair<PocketRagEngine, PocketRagSessionStore> {
            val store = PocketRagSessionStore()
            return PocketRagEngine(
                local = object : PocketRagLocalAnswerer {
                    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? =
                        if (recoveryMode) second() else first()
                },
                cloud = object : PocketRagCloudAnswerer {
                    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
                },
                qualityGate = gate, sessions = store, canUseCloud = { false }
            ) to store
        }
        val (retryEngine, retryStore) = engine({ throw PocketRagDegenerateOutput("decoder_loop_guard") }, { good })
        val retried = kotlinx.coroutines.runBlocking { retryEngine.answer(asha(q)) }
        check("decoder loop: one fresh generation is used", retried.accepted && retried.text.startsWith("To plan HBYC home visits")) { retried.text }
        check("decoder loop: the accepted retry is remembered", retryStore.context("generic:asha").size == 1)
        val (failEngine, failStore) = engine({ "ASHA " + "hein".repeat(60) }, { throw PocketRagDegenerateOutput("decoder_loop_guard") })
        val failed = kotlinx.coroutines.runBlocking { failEngine.answer(asha(q)) }
        check("two broken generations give the safe failure, never the broken text",
            !failed.accepted && !failed.text.contains("hein") && failed.sourceIds.isEmpty()) { failed.text }
        check("a failed turn is not remembered for follow-ups", failStore.context("generic:asha").isEmpty())

        // A changed percentage ("5%" where the evidence says "50%") is an unsupported number.
        val iddmEvidence = "Buy (Indian-IDDM) category refers to the acquisition of products from an Indian vendor with a minimum of 50% Indigenous Content (IC) on cost basis."
        check("a single-digit percentage that the evidence does not state is caught",
            gate.unsupportedNumericClaims("explain in detail", "It needs a minimum of 5% Indigenous Content.", iddmEvidence) == setOf("5%"))
        check("the correct percentage passes", gate.unsupportedNumericClaims("q", "It needs a minimum of 50% Indigenous Content.", iddmEvidence).isEmpty())
        check("a percent word in the evidence supports the % form",
            gate.unsupportedNumericClaims("q", "The buyer can select between 0.01% and 5% of the estimated price.",
                "System provide option to select Between 0.01 to 5 Percentage Of Estimated Price").isEmpty())

        // A copied passage with its named term swapped (APBG passage answered as ePBG) is caught.
        val leasing = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"), query = "what is epbg",
            evidenceBlocks = listOf(
                PocketRagEvidenceBlock("E1", "S1", "ePBG (Electronic Performance Bank Guarantee) Update the required details if ePBG is required"),
                PocketRagEvidenceBlock("E2", "S2", "The APBG is a guarantee for such a fee. The Advance Payment Bank Guarantee (APBG) shall be for a period exceeding the period for delivery of leased equipment by three months.")
            ),
            sources = listOf(PocketRagSource("S1", "Global Tender p.24"), PocketRagSource("S2", "DAP p.628")), coverageComplete = true
        )
        val swappedAnswer = "ePBG is the Electronic Performance Bank Guarantee [1]. The ePBG shall be for a period exceeding the period for delivery of leased equipment by three months."
        val swapDecision = gate.evaluate("what is epbg", swappedAnswer, leasing)
        check("a statement with a swapped term is rejected", swapDecision.reason.startsWith("term_swap")) { swapDecision.reason }
        check("salvage keeps only the faithful statement",
            gate.salvageSwappedTerms(swappedAnswer, leasing) == "ePBG is the Electronic Performance Bank Guarantee [1].") { gate.salvageSwappedTerms(swappedAnswer, leasing).orEmpty() }
        check("a faithful copy with the right term passes",
            gate.swappedTermStatements("The APBG shall be for a period exceeding the period for delivery of leased equipment by three months.", leasing).isEmpty())

        // Weak evidence: the answer model is told to say the pack does not contain it rather than fill gaps.
        val weak = PocketRagPromptBuilder.buildParts(asha(q).copy(bestRelevance = -3.0f), emptyList()).userMessage
        val midRange = PocketRagPromptBuilder.buildParts(asha(q).copy(bestRelevance = -1.3f), emptyList()).userMessage
        check("mid-range match (broad question) is answered normally, not strictly", !midRange.contains("only weakly"))
        val strong = PocketRagPromptBuilder.buildParts(asha(q).copy(bestRelevance = 5.9f), emptyList()).userMessage
        val noRr = PocketRagPromptBuilder.buildParts(asha(q), emptyList()).userMessage
        check("weakly matching passages: model told to say the KB does not contain it",
            weak.contains("matched this question only weakly") && weak.contains("[[NOT_IN_EVIDENCE]]"))
        check("strong passages or no re-ranker: no weak-evidence instruction",
            !strong.contains("only weakly") && !noRr.contains("only weakly"))
    }

    /** Quick KB switching: packs move between the active slot and the library with their prepared files. */
    private fun packLibrary() {
        val dir = java.nio.file.Files.createTempDirectory("kblib").toFile().also { it.deleteOnExit() }
        val lib = GenericKbPackLibrary(dir, "active_generic_knowledge.db", "active_generic_knowledge.")
        fun writeActive(tag: String) {
            java.io.File(dir, "active_generic_knowledge.db").writeText("db-$tag")
            java.io.File(dir, "active_generic_knowledge.fts.db").writeText("fts-$tag")
            java.io.File(dir, "active_generic_knowledge.p14profile").writeText("profile-$tag")
        }
        writeActive("gem")
        java.io.File(dir, "active_generic_knowledge.db.candidate").writeText("candidate")
        val stamp = java.io.File(dir, "active_generic_knowledge.fts.db").also { it.setLastModified(1_600_000_000_000L) }.lastModified()
        val gemDir = lib.stashActive(GenericKbPackLibrary.Meta("gem01", "GeM procurement", 17628))
        check("active pack moves into the library with its prepared files",
            gemDir != null && java.io.File(gemDir, "active_generic_knowledge.fts.db").exists() &&
                !java.io.File(dir, "active_generic_knowledge.db").exists())
        check("a pending install is not moved", java.io.File(dir, "active_generic_knowledge.db.candidate").exists())
        check("moving keeps file dates (saved preparation stays valid)",
            java.io.File(gemDir!!, "active_generic_knowledge.fts.db").lastModified() == stamp)
        writeActive("asha")
        check("library lists the stashed pack", lib.entries() == listOf(GenericKbPackLibrary.Meta("gem01", "GeM procurement", 17628)))
        check("restore refuses to overwrite an active pack", lib.restore(gemDir) == null)
        val ashaDir = lib.stashActive(GenericKbPackLibrary.Meta("asha01", "ASHA Rajasthan", 2425))!!
        val back = lib.restore(lib.entry("gem01")!!)
        check("switching back restores the pack and its files",
            back?.displayName == "GeM procurement" &&
                java.io.File(dir, "active_generic_knowledge.p14profile").readText() == "profile-gem" &&
                lib.entry("gem01") == null && lib.entry("asha01") != null)
        check("library pack can be removed", lib.remove("asha01") && lib.entries().isEmpty() && !ashaDir.exists())
    }

    private fun uniqueWord(i: Int): String = "w" + ('a' + i % 26) + ('a' + (i / 26) % 26) + "x"

    private fun profileAndRanking() {
        val b = GenericKbCorpusProfile.Builder("sig")
        val footer = "All content, materials and resources contained herein are the intellectual property of the portal owner."
        for (d in 1..5) b.addRow("footer$d", "doc$d", "", "", footer, "", basis(0, common = 1f))
        b.addRow("proc", "doc1", "Bid Creation", "", "To create a bid, open the dashboard, choose Bids, then Create Bid, fill the bid details, attach the documents and review everything before publishing the bid.", "", basis(3, common = 2f))
        b.addRow("ocr", "doc2", "Bid Creation", "", "Bid creation screen shows the buyer dashboard with the create bid button highlighted for publishing.", "ocr_recovery", basis(4, common = 2f))
        b.addRow("garbled", "doc3", "", "", "FLOW CHART WƌĞůŝŵŝŶĂƌǇ WůĂŶŶŝŶŐ ƐƚĂŐĞ ǁŝƚŚ ĂƉƉƌŽǀĂů", "", basis(5, common = 2f))
        b.addRow("login", "doc4", "", "", "Click here to login.", "", basis(6, common = 2f))
        for (i in 1..60) b.addRow("topic$i", "doc${i + 5}", "Heading $i", "", "Substantive procedure text ${uniqueWord(i)} describing steps, approvals and the tender upload and the bid process in detail for buyers and sellers.", "", basis(1 + (i % 7), common = 2f))
        val profile = b.build()

        check("repeated footer is penalised", profile.penaltyFor("footer1") >= 0.5f) { profile.penaltyFor("footer1").toString() }
        check("substantive native chunk has no penalty", profile.penaltyFor("proc") == 0f) { profile.penaltyFor("proc").toString() }
        check("minority extraction is only a tie-break", profile.penaltyFor("ocr") in 0.1f..0.2f) { profile.penaltyFor("ocr").toString() }
        check("mis-encoded text is penalised", profile.penaltyFor("garbled") >= 0.8f) { profile.penaltyFor("garbled").toString() }
        check("very short fragment is penalised", profile.penaltyFor("login") > 0.3f) { profile.penaltyFor("login").toString() }

        // Startup cache: the saved profile must behave exactly like the freshly built one.
        val saved = java.io.File.createTempFile("p14", ".profile").also { it.deleteOnExit() }
        check("profile saves to disk", profile.writeTo(saved))
        val loaded = GenericKbCorpusProfile.readFrom(saved, "sig")
        check("saved profile loads for the same pack", loaded != null)
        check("saved profile is ignored for another pack", GenericKbCorpusProfile.readFrom(saved, "other") == null)
        loaded!!
        check("loaded profile keeps counts, mean and structure",
            loaded.chunkCount == profile.chunkCount && loaded.mean.contentEquals(profile.mean) &&
                loaded.meanNormSq == profile.meanNormSq && loaded.medianChars == profile.medianChars &&
                loaded.shortFragmentChars == profile.shortFragmentChars &&
                loaded.fragmentaryDocuments == profile.fragmentaryDocuments &&
                loaded.structure.structuralLabels == profile.structure.structuralLabels)
        check("loaded profile keeps penalties and vocabulary",
            listOf("footer1", "proc", "ocr", "garbled", "login").all { loaded.penaltyFor(it) == profile.penaltyFor(it) } &&
                listOf("bid", "procedure", "footer", "zzz").all { loaded.documentFrequency(it) == profile.documentFrequency(it) } &&
                loaded.correct("procedre") == profile.correct("procedre"))
        val idx = structure()
        val idxFile = java.io.File.createTempFile("p14", ".structure").also { it.deleteOnExit() }
        java.io.DataOutputStream(idxFile.outputStream()).use { idx.writeTo(it) }
        val idx2 = java.io.DataInputStream(idxFile.inputStream()).use { GenericKbIdentifierLane.StructureIndex.readFrom(it) }
        check("structure index round-trips",
            idx2.structuralLabels == idx.structuralLabels && idx2.isKey("161", "rule") && !idx2.isKey("11", "rs") &&
                idx2.labelledNumberCount("para") == idx.labelledNumberCount("para"))
        val stats = KbCorpusStatistics()
        stats.rebuild("s1", listOf(
            HybridKnowledgeRetriever.Document("a", "GFR rule 161 limited tender"),
            HybridKnowledgeRetriever.Document("b", "rule 149 GeM purchase"),
            HybridKnowledgeRetriever.Document("c", "rule text only")
        ))
        val statsFile = java.io.File.createTempFile("kbstats", ".bin").also { it.deleteOnExit() }
        check("corpus statistics save", stats.save(statsFile))
        val stats2 = KbCorpusStatistics()
        check("corpus statistics ignored for another pack", !stats2.load(statsFile, "s2") && !stats2.isCurrent("s1"))
        check("corpus statistics load for the same pack", stats2.load(statsFile, "s1") && stats2.isCurrent("s1"))
        check("loaded statistics give the same literal slots",
            stats2.meaningfulLiteralSlots("rule 161 limited tender") == stats.meaningfulLiteralSlots("rule 161 limited tender"))

        // Mean-centering: a hub chunk close to the corpus mean wins raw cosine, loses centered.
        val hub = basis(0, common = 1f)
        val target = basis(3, common = 2f)
        val q = unit(5f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
        fun cos(a: FloatArray, c: FloatArray) = a.indices.fold(0f) { s, i -> s + a[i] * c[i] }
        check("hub wins raw cosine (reproduces the problem)", cos(q, hub) > cos(q, target))
        val scorer = GenericKbCenteredScorer(profile.mean, profile.meanNormSq, listOf(q))
        val outHub = FloatArray(1); val outTarget = FloatArray(1)
        scorer.score(hub, outHub); scorer.score(target, outTarget)
        check("mean-centering ranks the target above the hub", outTarget[0] > outHub[0]) { "${outTarget[0]} vs ${outHub[0]}" }

        // Fusion: penalties re-rank but never exclude; admission needs semantic + full-text agreement.
        val L = GenericKbRetrievalPlan.Lane.LITERAL
        fun scored(id: String, z: Float, dense: Int?, lexical: Int?) =
            GenericKbRetrievalPlan.Scored(id, mapOf(L to z), emptyMap(), lexical, null, profile.penaltyFor(id), 0f, dense)
        val footerS = scored("footer1", 3.3f, 1, 1)
        val procS = scored("proc", 3.0f, 2, 2)
        check("native procedure outranks a higher-ranked repeated footer",
            GenericKbRetrievalPlan.fused(procS) > GenericKbRetrievalPlan.fused(footerS))
        check("both lanes ranking a passage first scores 2.0 before bonuses",
            kotlin.math.abs(GenericKbRetrievalPlan.rankFusion(1, 1) - 2f) < 1e-4f)
        check("OCR-only evidence stays admissible with rank agreement", GenericKbRetrievalPlan.admitted(scored("ocr", 3.0f, 5, 9)))
        check("garbled chunk is admissible when both lanes rank it", GenericKbRetrievalPlan.admitted(scored("garbled", 3.0f, 3, 4)))
        check("a high semantic score alone is not admitted", !GenericKbRetrievalPlan.admitted(scored("proc", 9.0f, 1, null)))
        check("a full-text hit alone is not admitted", !GenericKbRetrievalPlan.admitted(scored("proc", 0.5f, null, 1)))
        val idOnly = GenericKbRetrievalPlan.Scored("gfr", mapOf(L to 0.5f), emptyMap(), null,
            GenericKbIdentifierLane.Admission(GenericKbIdentifierLane.Kind.HIERARCHICAL,
                GenericKbIdentifierLane.QueryIdentifier("161.2", "161", 2, "rule", true), "child:(ii)"), 0f, 0f)
        check("identifier admission needs no rank agreement", GenericKbRetrievalPlan.admitted(idOnly))

        // Calibration: gate is the pack's own noise ceiling, not a constant.
        val cal = GenericKbScoreCalibration.from(
            positiveZ = List(20) { 5f + it * 0.1f },
            nullTopZ = List(40) { 3f + it * 0.02f }
        )!!
        check("calibrated gate sits at the noise p90", cal.gateZ in 3.65f..3.8f) { cal.toString() }
        check("calibration separates real matches from noise", cal.separates)
        check("calibration round-trips", GenericKbScoreCalibration.decode(cal.encode()) == cal)
        val overlapping = GenericKbScoreCalibration.from(List(20) { 3f + it * 0.05f }, List(40) { 3f + it * 0.05f })!!
        check("recall guard caps the gate at the positives' p25", overlapping.gateZ < overlapping.noiseP90) { overlapping.toString() }
        check("too few samples gives no calibration", GenericKbScoreCalibration.from(listOf(5f), listOf(3f)) == null)
    }

    private fun planning() {
        val b = GenericKbCorpusProfile.Builder("sig2")
        for (i in 1..30) b.addRow("c$i", "d$i", "", "", "the policy and the rules and the process for item $i", "", null)
        b.addRow("u1", "d40", "Uploading", "", "upload the tender document and publish the tender", "", null)
        b.addRow("u2", "d41", "Uploading", "", "upload bid document tender", "", null)
        b.addRow("t1", "d42", "Tender", "", "tender types include open tender and limited tender", "", null)
        val profile = b.build()

        check("spelling: uplod -> upload", profile.correct("uplod") == "upload") { profile.correct("uplod").toString() }
        check("spelling: tendr -> tender", profile.correct("tendr") == "tender") { profile.correct("tendr").toString() }
        check("spelling leaves romanised Hindi alone", profile.correct("kaisey") == null) { profile.correct("kaisey").toString() }
        check("spelling ignores short tokens", profile.correct("ks") == null)
        val variant = GenericKbRetrievalPlan.spellingVariant("tendr uplod kaise kre", profile)
        check("spelling variant rewrites only unknown words", variant == "tender upload kaise kre") { variant.toString() }
        check("no variant when nothing is misspelled", GenericKbRetrievalPlan.spellingVariant("upload tender", profile) == null)

        check("function words are not anchors", "the" !in GenericKbRetrievalPlan.anchors("what is the tender", profile))
        val residual = GenericKbRetrievalPlan.residualVariant(
            "tender kya hai and use upload kaisey karein",
            listOf("Tender types include open tender and limited tender"),
            profile
        )
        check("residual keeps the uncovered part plus the covered subject",
            residual != null && "upload" in residual && "tender" in residual && residual.split(" ").size < 9) { residual.toString() }
        check("no residual when everything is covered",
            GenericKbRetrievalPlan.residualVariant("upload tender", listOf("upload the tender document"), profile) == null)
        check("no residual when nothing is covered",
            GenericKbRetrievalPlan.residualVariant("upload tender", listOf("unrelated text"), profile) == null)

        val order = GenericKbRetrievalPlan.coverageOrder(listOf("a", "b", "c", "d"), listOf("c", null, "d"))
        check("each covered part is reserved before rank fill", order == listOf("c", "d", "a", "b")) { order.toString() }
    }

    private data class Doc(val id: String, val doc: String, val heading: String, val content: String, val vector: FloatArray, val locator: String = "")

    private fun engineEndToEnd() {
        val dim = 10
        fun v(i: Int, common: Float = 2f) = unit(*FloatArray(dim) { j -> if (j == 0) common else if (j == i) 1f else 0f })
        val docs = mutableListOf(
            Doc("gfr161", "gfr", "Rule 161 Advertised Tender Enquiry",
                "Rule 161 Advertised Tender Enquiry\n(i) Subject to exceptions under Rule 154 invitation to tenders by advertisement should be used.\n(ii) The organisation should post the complete bidding document on its website and GeM-CPPP.",
                v(7), "page:38"),
            Doc("bidcreate", "manual", "Bid Creation",
                "To create a bid on the portal, open the dashboard, choose Bids, then Create Bid, fill the bid details, attach documents and publish the bid.",
                v(3)),
            Doc("tenderdef", "manual2", "Tender types",
                "Tender types include open tender, limited tender, single tender and global tender, each used under different conditions.",
                v(4))
        )
        val footer = "All content, materials and resources contained herein are the intellectual property of the portal owner and are protected."
        for (d in 1..9) docs += Doc("footer$d", "fdoc$d", "", footer, v(0, common = 1f))
        for (n in 150..170) if (n != 161) docs += Doc("rule$n", "gfr", "Rule $n Heading", "Rule $n Heading\n(i) general provision text for rule $n.", v(8))
        for (i in 1..80) docs += Doc("filler$i", "fill$i", "Heading ${uniqueWord(i)}", "Substantive text ${uniqueWord(i)} about approvals, committees, budgets and contract management in several paragraphs of detail.", v(1 + i % 2))
        docs += Doc("login", "ldoc", "", "Click here to login.", v(3, common = 2.2f))

        val b = GenericKbCorpusProfile.Builder("e2e")
        docs.forEach { b.addRow(it.id, it.doc, it.heading, it.locator, it.content, "", it.vector) }
        val profile = b.build()
        var gate = 2.0f
        val engine = { GenericKbPhase14Engine<String>(profile, gateZ = gate) }

        val queries = mapOf(
            "query: what is gfr rule 161.2" to v(9),   // dense lane finds nothing specific
            "query: how to create a bid" to unit(*FloatArray(dim) { j -> if (j == 0) 5f else if (j == 3) 1f else 0f }),
            "query: weather in delhi today" to v(9),
            "query: what are tender types and how to create a bid" to unit(*FloatArray(dim) { j -> if (j == 0) 4f else if (j == 3 || j == 4) 1f else 0f }),
            "query: what are tender types" to unit(*FloatArray(dim) { j -> if (j == 0) 4f else if (j == 4) 1f else 0f }),
            "query: how to create a bid" to unit(*FloatArray(dim) { j -> if (j == 0) 4f else if (j == 3) 1f else 0f })
        )
        // Toy full-text lane for the test pack: rank passages by shared words (3+ letters).
        val testStop = setOf("how", "what", "the", "and", "are", "for", "use")
        fun toyLexical(text: String): Map<String, Int> {
            val terms = GenericKbText.tokens(text).filter { it.length >= 3 && it !in testStop && it.none(Char::isDigit) }.toSet()
            if (terms.isEmpty()) return emptyMap()
            return docs.map { d -> d.id to GenericKbText.tokens(d.heading + " " + d.content).toSet().count { it in terms } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(16)
                .mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
        }
        fun run(q: String, clauses: List<GenericKbClause>) = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = q,
                clauses = clauses,
                pool = 12,
                scanRows = { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "General Financial Rules", d.vector) { d.id } } },
                embed = { text -> queries[text] ?: v(9) },
                lexicalRanks = { text -> toyLexical(text) },
                metadataCos = { _, _ -> 0f }
            )!!
        }

        val r161 = run("what is gfr rule 161.2", GenericKbQueryPlanner.plan("what is gfr rule 161.2", "what is gfr rule 161.2").clauses)
        check("e2e: GFR 161.2 retrieved through the identifier lane", r161.ordered.firstOrNull()?.first?.chunkId == "gfr161") { r161.trace }
        check("e2e: 161.2 clause is SUPPORTED (child found)", r161.clauseStates.single().state == GenericKbCoverageState.SUPPORTED) { r161.clauseStates.toString() }
        check("e2e: no other rule section admitted for 161.2", r161.ordered.none { it.first.chunkId.startsWith("rule") && it.first.admission != null }) { r161.trace }

        val bid = run("how to create a bid", GenericKbQueryPlanner.plan("how to create a bid", "how to create a bid").clauses)
        val bidOrder = bid.ordered.map { it.first.chunkId }
        check("e2e: procedure outranks repeated footer and login fragment",
            bidOrder.indexOf("bidcreate") >= 0 && bidOrder.indexOf("bidcreate") < bidOrder.indexOf("footer1").let { if (it < 0) Int.MAX_VALUE else it } &&
                bidOrder.indexOf("bidcreate") < bidOrder.indexOf("login").let { if (it < 0) Int.MAX_VALUE else it }) { bid.trace }

        // Out-of-KB: no full-text agreement -> nothing admitted, UNSUPPORTED (the answer's
        // grounded refusal check stays the hard rejection path when something is admitted).
        gate = 50f
        val none = run("weather in delhi today", GenericKbQueryPlanner.plan("weather in delhi today", "weather in delhi today").clauses)
        check("e2e: no rank agreement admits nothing", none.ordered.isEmpty()) { none.trace }
        check("e2e: a semantic top score without full-text agreement is not evidence",
            none.trace.contains("reason=no_rank_agreement")) { none.trace }
        // Near-miss diagnostics: a known query forced below the gate must log exactly five
        // rejected candidates, ordered by score, each with z, lexical rank, source and preview.
        val miss = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = "how to create a bid",
                clauses = GenericKbQueryPlanner.plan("how to create a bid", "how to create a bid").clauses,
                pool = 12,
                scanRows = { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "General Financial Rules", d.vector) { d.id } } },
                embed = { text -> queries[text] ?: v(9) },
                // Full-text hits that the semantic lane does not rank: no agreement, so rejected.
                lexicalRanks = { (151..156).withIndex().associate { (i, n) -> "rule$n" to i + 1 } },
                metadataCos = { _, _ -> 0f },
                describe = { id -> if (id == "rule151") "GFR@p38" else "$id@p1" }
            )!!
        }
        val mt = miss.trace
        check("near-miss: admitted=0, bestZ, reason and gate are logged",
            miss.ordered.isEmpty() && mt.contains(" admitted=0 ") && Regex(" bestZ=-?[0-9]+\\.[0-9]{2} ").containsMatchIn(mt) &&
                mt.contains(" reason=no_rank_agreement ") && mt.contains(" gate=50.00 ")) { mt }
        val nearEntries = Regex(" near=(\\S+)").find(mt)?.groupValues?.get(1)?.split(';').orEmpty()
        val entryShape = Regex("^[^:]+:z=(-?[0-9]+\\.[0-9]{2}):lx=([0-9]+|-):src=[^:]+:txt=\\S+$")
        check("near-miss: exactly five entries", nearEntries.size == 5) { mt }
        check("near-miss: every entry has z, lx, src and txt", nearEntries.all { entryShape.matches(it) }) { nearEntries.joinToString("\n") }
        val nearZ = nearEntries.mapNotNull { entryShape.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
        check("near-miss: entries ordered by score", nearZ.size == 5 && nearZ.zipWithNext().all { (a, b) -> a >= b }) { nearZ.toString() }
        check("near-miss: bestZ equals the first entry's score",
            Regex(" bestZ=(-?[0-9.]+) ").find(mt)?.groupValues?.get(1)?.toFloatOrNull() == nearZ.firstOrNull()) { mt }
        check("near-miss: the known passage carries its (absent) lexical rank, document/page and opening text",
            nearEntries.singleOrNull { it.startsWith("bidcreate:") }?.let { e ->
                e.contains(":lx=-:") && e.contains(":src=bidcreate@p1:") && e.contains(":txt=Bid_Creation_To_create_a_bid_on_the_portal")
            } == true) { nearEntries.joinToString("\n") }
        val ftsEntries = Regex(" fts=(\\S+)").find(mt)?.groupValues?.get(1)?.split(';').orEmpty()
        check("fts: exactly five lexical candidates logged", ftsEntries.size == 5) { mt }
        check("fts: every entry has z, lexical rank, source/page and preview", ftsEntries.all { entryShape.matches(it) }) { ftsEntries.joinToString("\n") }
        check("fts: entries ordered by lexical rank 1..5",
            ftsEntries.mapNotNull { entryShape.find(it)?.groupValues?.get(2)?.toIntOrNull() } == listOf(1, 2, 3, 4, 5)) { ftsEntries.joinToString("\n") }
        check("fts: the rank-1 passage carries its document/page and opening text",
            ftsEntries.firstOrNull()?.let { e ->
                e.startsWith("rule151:") && e.contains(":src=GFR@p38:") && e.contains(":txt=Rule_151_Heading")
            } == true) { ftsEntries.firstOrNull().orEmpty() }
        check("fts: the rank-6 hit is not logged", ftsEntries.none { it.startsWith("rule156:") }) { ftsEntries.joinToString("\n") }
        check("e2e: unadmitted clause is UNSUPPORTED", none.clauseStates.all { it.state == GenericKbCoverageState.UNSUPPORTED })
        val idStillWorks = run("what is gfr rule 161.2", GenericKbQueryPlanner.plan("what is gfr rule 161.2", "what is gfr rule 161.2").clauses)
        check("e2e: identifier admission works without rank agreement", idStillWorks.ordered.firstOrNull()?.first?.chunkId == "gfr161") { idStillWorks.trace }
        gate = 2.0f

        val compoundText = "what are tender types? how to create a bid"
        val plan = GenericKbQueryPlanner.plan(compoundText, compoundText)
        check("e2e: compound question splits into two parts", plan.clauses.size == 2) { plan.clauses.toString() }
        val compound = run(compoundText, plan.clauses)
        val firstTwo = compound.ordered.take(2).map { it.first.chunkId }.toSet()
        check("e2e: each covered part reserves a block first", firstTwo == setOf("tenderdef", "bidcreate")) { compound.trace }
        check("e2e: both parts SUPPORTED", compound.clauseStates.all { it.state == GenericKbCoverageState.SUPPORTED }) { compound.clauseStates.toString() }

        // Build-time likely questions: a passage worded unlike the question ("registration" vs
        // "sign up") is found through its question vector and question text, and is still returned
        // as the original passage (the questions are searchable only).
        val reg = Doc("reg", "man3", "Primary user registration",
            "Primary user registration requires the approval of the head of department.", v(6))
        val likelyQuestion = "How do I sign up as a buyer on the portal?"
        val signUp = "how do i sign up as a buyer"
        val qVec = unit(*FloatArray(dim) { j -> if (j == 0) 2f else if (j == 5) 1f else 0f })
        val all = docs + reg
        fun search(enriched: Boolean) = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = signUp,
                clauses = GenericKbQueryPlanner.plan(signUp, signUp).clauses,
                pool = 12,
                scanRows = { all.asSequence().map { d ->
                    GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "Manual", d.vector,
                        questionVector = if (enriched && d.id == "reg") qVec else null) { d.id } } },
                embed = { text -> if (text == "query: $signUp") qVec else v(9) },
                lexicalRanks = { text ->
                    val terms = GenericKbText.tokens(text).filter { it.length >= 3 && it !in testStop }.toSet()
                    all.map { d -> d.id to GenericKbText.tokens(d.heading + " " + d.content + if (enriched && d.id == "reg") " $likelyQuestion" else "").toSet().count { it in terms } }
                        .filter { it.second > 0 }.sortedByDescending { it.second }.take(16).mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
                },
                metadataCos = { _, _ -> 0f }
            )!!
        }
        val plain = search(enriched = false)
        check("enrichment: without likely questions the reworded passage is not admitted",
            plain.ordered.none { it.first.chunkId == "reg" }) { plain.trace }
        val enriched = search(enriched = true)
        check("enrichment: with likely questions the reworded passage is admitted first",
            enriched.ordered.firstOrNull()?.first?.chunkId == "reg") { enriched.trace }
        check("enrichment: the trace marks the question-vector match", enriched.trace.contains("reg:") && enriched.trace.contains(":via=q")) { enriched.trace }
        // Cross-encoder re-ranker: its relevance judgement replaces rank agreement when present.
        fun withRerank(q: String, scorer: ((String) -> Float)?) = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = q,
                clauses = GenericKbQueryPlanner.plan(q, q).clauses,
                pool = 12,
                scanRows = { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "Manual", d.vector) { d.id } } },
                embed = { text -> queries[text] ?: v(9) },
                lexicalRanks = { text -> toyLexical(text) },
                metadataCos = { _, _ -> 0f },
                rerank = scorer?.let { f ->
                    val fn: suspend (String, List<String>) -> FloatArray? = { _, passages -> FloatArray(passages.size) { f(passages[it]) } }
                    fn
                }
            )!!
        }
        val judged = withRerank("how to create a bid") { p -> if (p.startsWith("Bid Creation")) 6f else -5f }
        check("rerank: the passage the re-ranker judges relevant is admitted first",
            judged.ordered.firstOrNull()?.first?.chunkId == "bidcreate") { judged.trace }
        check("rerank: low-scored candidates are kept (re-order only), after the relevant one",
            judged.ordered.size > 1 && judged.ordered.drop(1).all { (it.first.rerankScore ?: 0f) <= 6f }) { judged.trace }
        check("rerank: the trace records the re-ranker", judged.trace.contains(" rr=on:") && judged.trace.contains(":rr=6.00")) { judged.trace }
        // Pack-declared precedence: a primary-source passage the re-ranker scores a little lower moves ahead.
        fun withPrecedence(declared: Boolean) = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = "how to create a bid", clauses = GenericKbQueryPlanner.plan("how to create a bid", "how to create a bid").clauses,
                pool = 12,
                scanRows = { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "Manual", d.vector) { d.id } } },
                embed = { text -> queries[text] ?: v(9) }, lexicalRanks = { text -> toyLexical(text) }, metadataCos = { _, _ -> 0f },
                describe = { id -> id },
                rerank = { _, passages -> FloatArray(passages.size) { if (passages[it].startsWith("Bid Creation")) 6f else if (passages[it].contains("161")) 5f else -5f } },
                precedenceOf = { id -> if (!declared) null else if (id == "gfr161") 1 else 2 }
            )!!
        }
        val noPrecedence = withPrecedence(false)
        check("precedence: undeclared packs keep the re-ranker order", noPrecedence.ordered.firstOrNull()?.first?.chunkId == "bidcreate") { noPrecedence.trace }
        val withPrec = withPrecedence(true)
        check("precedence: a primary source within the step moves ahead", withPrec.ordered.firstOrNull()?.first?.chunkId == "gfr161") { withPrec.trace }
        check("precedence: the trace shows the bonus", withPrec.trace.contains(":prec=+2.0")) { withPrec.trace }
        check("precedence: an irrelevant primary passage does not beat a clearly relevant one",
            withPrec.ordered.indexOfFirst { it.first.chunkId == "bidcreate" } < withPrec.ordered.indexOfFirst { (it.first.rerankScore ?: 0f) < -4f && it.first.precedenceBonus > 0f }.let { if (it < 0) Int.MAX_VALUE else it }) { withPrec.trace }
        val lowAll = withRerank("how to create a bid") { -6f }
        check("rerank: never blocks answers - low scores still leave evidence for the grounded-answer check",
            lowAll.ordered.isNotEmpty()) { lowAll.trace }
        val idWithRerank = withRerank("what is gfr rule 161.2") { -6f }
        check("rerank: identifier admission survives a low re-ranker score",
            idWithRerank.ordered.firstOrNull()?.first?.chunkId == "gfr161") { idWithRerank.trace }
        val failedRerank = kotlinx.coroutines.runBlocking {
            engine().search("how to create a bid", GenericKbQueryPlanner.plan("how to create a bid", "how to create a bid").clauses, 12,
                { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "Manual", d.vector) { d.id } } },
                { text -> queries[text] ?: v(9) }, { text -> toyLexical(text) }, { _, _ -> 0f },
                rerank = { _, _ -> null })!!
        }
        check("rerank: an unavailable re-ranker falls back to rank agreement",
            failedRerank.trace.contains("rr=failed") && failedRerank.ordered.any { it.first.chunkId == "bidcreate" }) { failedRerank.trace }
        // Dense retrieval off (vector-space mismatch) or no query embedding: full-text still answers.
        fun lexicalMode(dense: Boolean, embedWorks: Boolean) = kotlinx.coroutines.runBlocking {
            engine().search(
                literal = "how to create a bid", clauses = GenericKbQueryPlanner.plan("how to create a bid", "how to create a bid").clauses,
                pool = 12,
                scanRows = { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "Manual", d.vector) { d.id } } },
                embed = { text -> if (embedWorks) queries[text] ?: v(9) else null },
                lexicalRanks = { text -> toyLexical(text) },
                metadataCos = { _, _ -> 0f },
                denseEnabled = dense
            )
        }
        val noDense = lexicalMode(dense = false, embedWorks = true)
        check("dense off: full-text evidence is still returned (never a blocked KB)",
            noDense != null && noDense.ordered.firstOrNull()?.first?.chunkId == "bidcreate") { noDense?.trace.orEmpty() }
        check("dense off: the trace says lexical_only and why", noDense?.trace?.contains("mode=lexical_only reason=dense_disabled") == true) { noDense?.trace.orEmpty() }
        val noEmbed = lexicalMode(dense = true, embedWorks = false)
        check("no query embedding: falls back to full-text instead of giving up",
            noEmbed != null && noEmbed.ordered.isNotEmpty() && noEmbed.trace.contains("reason=no_query_embedding")) { noEmbed?.trace.orEmpty() }
        val idOnly2 = kotlinx.coroutines.runBlocking {
            engine().search("what is gfr rule 161.2", GenericKbQueryPlanner.plan("what is gfr rule 161.2", "what is gfr rule 161.2").clauses, 12,
                { docs.asSequence().map { d -> GenericKbPhase14Engine.Row(d.id, d.heading, d.locator, d.content, "GFR", d.vector) { d.id } } },
                { null }, { emptyMap() }, { _, _ -> 0f }, denseEnabled = false)
        }
        check("dense off: rule/section identifiers still admit their section", idOnly2?.ordered?.firstOrNull()?.first?.chunkId == "gfr161") { idOnly2?.trace.orEmpty() }
        check("profile-free fallback: full-text order without re-ranker",
            GenericKbRetrievalPlan.lexicalFallbackOrder(listOf(3, 1, 2), null) == listOf(1, 2, 0))
        check("profile-free fallback: re-ranker order when verified scores exist",
            GenericKbRetrievalPlan.lexicalFallbackOrder(listOf(3, 1, 2), floatArrayOf(5f, -1f, 2f)) == listOf(0, 2, 1))
        check("profile-free fallback: wrong-sized scores are ignored (never a broken order)",
            GenericKbRetrievalPlan.lexicalFallbackOrder(listOf(3, 1, 2), floatArrayOf(5f)) == listOf(1, 2, 0))
        check("vector-space check: matched pack passes, mismatched fails (measured values)",
            KbVectorSpaceCheck.evaluate(listOf(0.9980f, 0.9966f, 0.9974f)).passed && !KbVectorSpaceCheck.evaluate(listOf(0.9804f, 0.9703f, 0.9906f)).passed)
        check("enrichment: the returned evidence is the original passage, not the questions",
            enriched.ordered.first().first.payload == "reg" && !reg.content.contains("sign up"))
    }

    private fun followUpsAndProcedures() {
        // Fragmentary documents (slide decks) are learned from passage lengths, not from names.
        val b = GenericKbCorpusProfile.Builder("frag")
        for (i in 1..12) b.addRow("deck$i", "deck", "", "page:$i", "Click here to proceed step $i.", "", null)
        for (i in 1..12) b.addRow("man$i", "manual", "", "page:$i",
            "The procuring entity shall publish the complete tender document on its website and on the portal, " .repeat(12) + i, "", null)
        val profile = b.build()
        check("fragmentary: a slide deck of captions is detected", "deck" in profile.fragmentaryDocuments) { profile.fragmentaryDocuments.toString() }
        check("fragmentary: a manual of full paragraphs is not", "manual" !in profile.fragmentaryDocuments)
        check("fragmentary: the pack median passage length is recorded", profile.medianChars > 100) { profile.medianChars.toString() }

        // A resolved follow-up reaches the answer prompt; a stand-alone question does not get one.
        fun packet(followUpOf: String) = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"),
            query = "Explain the second one.",
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "Two packet bid: technical bids are opened first, then financial bids of qualified bidders.")),
            sources = listOf(PocketRagSource("S1", "Manual p.3")),
            coverageComplete = true,
            followUpOf = followUpOf
        )
        val withPrevious = PocketRagPromptBuilder.buildParts(packet("What are the different types of bids?"), emptyList()).userMessage
        check("follow-up: the previous question is given to the answering model",
            withPrevious.contains("follows up the previous question") && withPrevious.contains("What are the different types of bids?")) { withPrevious }
        val standalone = PocketRagPromptBuilder.buildParts(packet(""), emptyList()).userMessage
        check("follow-up: no previous question when retrieval did not resolve one", !standalone.contains("follows up")) { standalone }
        val system = PocketRagPromptBuilder.buildParts(packet(""), emptyList()).systemAddon
        check("answer prompt: reply in the question's language and script", system.contains("language and script"))
        check("answer prompt: length is chosen from the question in three tiers",
            system.contains("80 words") && system.contains("150 to 200 words") && system.contains("250 words") &&
                system.contains("numbered steps"))
        check("answer prompt: never a one-sentence answer when the evidence has more",
            system.contains("Never answer with only one sentence") && !system.contains("in one sentence."))
        check("answer prompt: a special case is never presented as the general rule",
            system.contains("do not present a special case") && system.contains("say which case it covers"))
        check("answer prompt: nothing from outside the evidence", system.contains("do not add background from outside the evidence"))
        check("answer prompt: no user setting decides length", !system.contains("LENGTH: SHORT") && !system.contains("LENGTH: MEDIUM"))

        // The previous answer is shown only for a resolved follow-up, only from this KB session's
        // answer to that exact question, and bounded.
        val memory = listOf(
            PocketRagTurnMemory("What is GeM?", "GeM is the national public procurement portal.", listOf("S1")),
            PocketRagTurnMemory("What are the different types of bids?", "There are two types: single packet bid and two packet bid. " + "x".repeat(2_000), listOf("S1"))
        )
        val followUp = PocketRagPromptBuilder.buildParts(packet("What are the different types of bids?"), memory).userMessage
        check("follow-up: the start of the previous answer is given for reference",
            followUp.contains("Your previous answer, start and end") && followUp.contains("single packet bid and two packet bid")) { followUp }
        check("follow-up: the previous answer is bounded",
            followUp.substringAfter("(reference only, not evidence): \"").substringBefore("\"\n").length <= PocketRagPromptBuilder.FOLLOW_UP_ANSWER_CHARS + 3) {
                followUp.substringAfter("(reference only, not evidence): \"").substringBefore("\"\n").length.toString() }
        check("follow-up: the answer to a different earlier question is not used",
            !followUp.contains("national public procurement portal")) { followUp }
        val standaloneWithMemory = PocketRagPromptBuilder.buildParts(packet(""), memory).userMessage
        check("stand-alone: no previous answer even when the session has one",
            !standaloneWithMemory.contains("previous answer") && !standaloneWithMemory.contains("two packet")) { standaloneWithMemory }
    }

    private fun sourcesAndSections() {
        val packet = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"),
            query = "what is rule 144 of gfr",
            evidenceBlocks = listOf(
                PocketRagEvidenceBlock("E1", "S1", "Rule 144 Fundamental principles of public buying. Every authority delegated with financial powers shall procure goods in an efficient, economical and transparent manner."),
                PocketRagEvidenceBlock("E2", "S2", "Registration of bidders from countries sharing a land border is mandatory before participation in procurement."),
                PocketRagEvidenceBlock("E3", "S3", "Login to the portal and click Submit to proceed further.")
            ),
            sources = listOf(PocketRagSource("S1", "GFR p.30"), PocketRagSource("S2", "GTC p.46"), PocketRagSource("S3", "Manual p.6")),
            coverageComplete = true
        )
        val answer = "Rule 144 sets the fundamental principles of public buying: every authority delegated with financial powers must procure goods in an efficient, economical and transparent manner."
        check("sources: uncited answer attributes only the block it draws on",
            packet.citedSourceIds(answer) == listOf("kbref:S1 :: GFR p.30")) { packet.citedSourceIds(answer).toString() }
        check("sources: explicit [S#] markers still win", packet.citedSourceIds("See [S2].") == listOf("kbref:S2 :: GTC p.46"))
        val habitual = "Rule 144 sets the fundamental principles of public buying: every authority delegated with financial powers must procure goods in an efficient, economical and transparent manner [S1][S3]."
        check("sources: a cited passage that shares nothing with the answer is dropped",
            packet.citedSourceIds(habitual) == listOf("kbref:S1 :: GFR p.30")) { packet.citedSourceIds(habitual).toString() }
        val hindi = "नियम 144 सार्वजनिक खरीद के मूल सिद्धांत बताता है [S1]।"
        check("sources: a paraphrase in another script keeps the model's own citation",
            packet.citedSourceIds(hindi) == listOf("kbref:S1 :: GFR p.30")) { packet.citedSourceIds(hindi).toString() }
        check("sources: an answer sharing nothing specific lists no sources",
            packet.citedSourceIds("I could not determine this from the documents provided here.").isEmpty())
        check("sources: words repeated from the question do not create support",
            packet.supportedSourceKeys("Rule 144 of GFR is what you asked about.").isEmpty())

        val id = GenericKbIdentifierLane.QueryIdentifier("rule 144", "144", null, "rule", true)
        val heading = GenericKbIdentifierLane.Admission(GenericKbIdentifierLane.Kind.SECTION, id, "heading")
        val body = GenericKbIdentifierLane.Admission(GenericKbIdentifierLane.Kind.SECTION, id, "body")
        check("sections: the rule's own heading outranks a body line quoting it",
            GenericKbRetrievalPlan.identifierBonus(heading) > GenericKbRetrievalPlan.identifierBonus(body))
    }

    private fun coverageDisclosure() {
        val q = "what are different type of tenders? how to create a bid on gem"
        fun packet(states: List<GenericKbCoverageState>) = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"),
            query = q,
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "Open tender, limited tender and single tender are the types of tender.")),
            sources = listOf(PocketRagSource("S1", "Manual p.4")),
            coverageComplete = false,
            clauseCoverage = states.mapIndexed { i, st -> GenericKbClauseCoverage("C${i + 1}", st, if (st == GenericKbCoverageState.SUPPORTED) setOf("S1") else emptySet()) }
        )
        val answer = "The types of tender are open tender, limited tender and single tender [S1]."
        val mixed = packet(listOf(GenericKbCoverageState.SUPPORTED, GenericKbCoverageState.UNSUPPORTED))
        val out = PocketRagEngine.discloseUnsupportedClauses(mixed, answer)
        check("coverage: unsupported part is named in the returned answer",
            out.startsWith(answer) && out.contains("Not covered in the selected knowledge base") && out.contains("how to create a bid on gem")) { out }
        check("coverage: the supported part is not named as unsupported", !out.substringAfter(answer).contains("different type of tenders")) { out }
        check("coverage: nothing appended when every part is supported",
            PocketRagEngine.discloseUnsupportedClauses(packet(listOf(GenericKbCoverageState.SUPPORTED, GenericKbCoverageState.SUPPORTED)), answer) == answer)
        val alreadyMarked = answer + " " + PocketRagQualityGate.UNSUPPORTED_PART_SENTENCE
        check("coverage: no duplicate when the model's marker was already converted",
            PocketRagEngine.discloseUnsupportedClauses(mixed, alreadyMarked) == alreadyMarked)

        // Full engine path (same class the app runs): uncited answer to a mixed question.
        val uncited = "The types of tender are open tender, limited tender and single tender."
        fun engine(reply: String?) = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean) = reply
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? =
                    error("Cloud must not be called for an ordinary KB question")
            },
            qualityGate = PocketRagQualityGate(),
            sessions = PocketRagSessionStore(),
            canUseCloud = { true }
        )
        val result = kotlinx.coroutines.runBlocking { engine(uncited).answer(mixed) }
        check("engine: uncited answer still shows the supporting source", result.sourceIds == listOf("kbref:S1 :: Manual p.4")) { result.sourceIds.toString() }
        check("engine: returned text names the unsupported part", result.text.contains("how to create a bid on gem")) { result.text }
        check("engine: no Cloud call and provider stays LOCAL", !result.cloudAttempted && result.provider == AndroBaselineRuntime.Provider.LOCAL)
        // Online backup: only when switched on, and only after the phone's answer failed its checks.
        fun backupEngine(on: Boolean) = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean) =
                    "Bananas are yellow fruit grown in tropical places."
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? =
                    if (on) uncited else error("Cloud must not be called when Online backup is off")
            },
            qualityGate = PocketRagQualityGate(),
            sessions = PocketRagSessionStore(),
            canUseCloud = { true },
            onlineBackupEnabled = { on }
        )
        val off = kotlinx.coroutines.runBlocking { backupEngine(false).answer(mixed) }
        check("online backup off: failed local answer never goes online", !off.cloudAttempted && off.provider == AndroBaselineRuntime.Provider.LOCAL)
        val on = kotlinx.coroutines.runBlocking { backupEngine(true).answer(mixed) }
        check("online backup on: failed local answer is answered online from the same passages",
            on.provider == AndroBaselineRuntime.Provider.CLOUD && on.cloudAttempted) { "${on.provider} ${on.text}" }
        // A healthy but slow local answer is kept when the caller deadline is off (generic KB).
        fun slowEngine(callerTimeout: Boolean) = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? {
                    kotlinx.coroutines.delay(1_500); return uncited
                }
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
            },
            qualityGate = PocketRagQualityGate(), sessions = PocketRagSessionStore(), canUseCloud = { false },
            localTimeoutMs = 1_000, localRecoveryTimeoutMs = 1_000, callerTimeoutEnabled = callerTimeout
        )
        val slowKept = kotlinx.coroutines.runBlocking { slowEngine(false).answer(mixed) }
        check("engine: a slow but healthy answer is kept without a caller deadline", slowKept.text.startsWith(uncited)) { slowKept.text }
        val slowCut = kotlinx.coroutines.runBlocking { slowEngine(true).answer(mixed) }
        check("engine: with the caller deadline (other packs) behaviour is unchanged", !slowCut.text.startsWith(uncited)) { slowCut.text }

        // One unsupported number: that sentence is removed at once; no regeneration is spent.
        var calls = 0
        val numericEngine = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? {
                    calls++
                    return if (recoveryMode) null else "The types of tender are open tender, limited tender and single tender. About 16,017 tenders were issued."
                }
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
            },
            qualityGate = PocketRagQualityGate(), sessions = PocketRagSessionStore(), canUseCloud = { false }
        )
        val salvaged = kotlinx.coroutines.runBlocking { numericEngine.answer(mixed) }
        check("engine: an unsupported number is removed without regenerating",
            salvaged.text.startsWith("The types of tender are open tender") && !salvaged.text.contains("16,017") && calls == 1) { "calls=$calls text=${salvaged.text}" }
        // Hard output ceiling behind the largest length tier, per question part.
        var seenBudget = 0
        val ceilingEngine = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? {
                    seenBudget = maxOutputTokens; return uncited
                }
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
            },
            qualityGate = PocketRagQualityGate(), sessions = PocketRagSessionStore(), canUseCloud = { false },
            contextWindowTokens = { 8_192 }
        )
        val bigEvidence = mixed.copy(evidenceBlocks = (1..9).map { PocketRagEvidenceBlock("E$it", "S1", "Open tender, limited tender and single tender are the types of tender. ".repeat(12)) })
        kotlinx.coroutines.runBlocking { ceilingEngine.answer(bigEvidence) }
        check("length: output never exceeds the per-part ceiling",
            seenBudget in 256..(PocketRagEngine.MAX_ANSWER_TOKENS_PER_PART * bigEvidence.clauseCoverage.size.coerceAtLeast(1))) { "budget=$seenBudget" }

        // Inline markers are renumbered to the document rows shown under the answer.
        val cited = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"), query = "what is pac",
            evidenceBlocks = listOf(
                PocketRagEvidenceBlock("E1", "S2", "PAC means proprietary article certificate issued by the buyer for a proprietary item."),
                PocketRagEvidenceBlock("E2", "S3", "The PAC certificate is uploaded by the buyer during bid creation on the portal."),
                PocketRagEvidenceBlock("E3", "S7", "Only the original equipment manufacturer can supply a proprietary article under PAC.")
            ),
            sources = listOf(
                PocketRagSource("S2", "S2 :: Intent of Buying PAC — page 2"),
                PocketRagSource("S3", "S3 :: Intent of Buying PAC — page 19"),
                PocketRagSource("S7", "S7 :: Goods Manual — page 54")
            ),
            coverageComplete = true
        )
        val pacAnswer = "PAC means proprietary article certificate issued by the buyer for a proprietary item [S2]. The certificate is uploaded by the buyer during bid creation [S3][S2]. Only the original equipment manufacturer can supply it [S7, S3]."
        val shown = cited.citedSourceIds(pacAnswer)
        val renumbered = KnowledgeSourcePresentation.renumberCitations(pacAnswer, shown)
        check("citations: passages of one document share its number", renumbered.contains("proprietary item [1].") && renumbered.contains("bid creation [1].")) { renumbered }
        check("citations: several documents are listed together", renumbered.contains("supply it [1, 2].")) { renumbered }
        check("citations: no internal S-markers remain", !Regex("\\[S\\d").containsMatchIn(renumbered)) { renumbered }
        check("citations: numbers match the source rows", KnowledgeSourcePresentation.visibleSources(shown).let {
            it.size == 2 && it[0].startsWith("Intent of Buying PAC — pages 2, 19") && it[1].startsWith("Goods Manual — page 54") }) { KnowledgeSourcePresentation.visibleSources(shown).toString() }
        check("citations: a marker for a passage that is not listed is removed",
            KnowledgeSourcePresentation.renumberCitations("Rule text [S9].", shown) == "Rule text.")
        check("sources: a passage ID repeated in the entry does not split one document into rows",
            KnowledgeSourcePresentation.visibleSources(listOf("kbref:S3 :: S3 :: Intent of Buying PAC — page 19", "kbref:S2 :: S2 :: Intent of Buying PAC — page 2"))
                == listOf("Intent of Buying PAC — pages 2, 19"))
        check("sources: a page range is grouped and shown as a range",
            KnowledgeSourcePresentation.visibleSources(listOf("kbref:S1 :: Bid Creation — pages 38–40", "kbref:S2 :: Bid Creation — page 12"))
                == listOf("Bid Creation — pages 12, 38–40")) {
            KnowledgeSourcePresentation.visibleSources(listOf("kbref:S1 :: Bid Creation — pages 38–40", "kbref:S2 :: Bid Creation — page 12")).toString() }
        // Reply script comes from the question's characters; loops are cut.
        check("script: an English question with typos is Latin", KbReplyText.dominantScript("differenxe between single bud and double bid system") == KbReplyText.Script.LATIN)
        check("script: romanised Hindi is Latin", KbReplyText.dominantScript("GeM par bid kaise create karte hain?") == KbReplyText.Script.LATIN)
        check("script: a Hindi question is Devanagari", KbReplyText.dominantScript("सिंगल और डबल बिड में क्या अंतर है?") == KbReplyText.Script.DEVANAGARI)
        val hindiAnswer = "सिंगल पैकेट बिड सिस्टम में खरीदार सभी निविदा का मूल्यांकन एक चरण में करता है।"
        check("script: English question answered in Devanagari is a mismatch", KbReplyText.scriptMismatch("differenxe between single bud and double bid system", hindiAnswer))
        check("script: an English answer quoting one Hindi word is fine", !KbReplyText.scriptMismatch("what is gem", "GeM (सरकारी ई-मार्केटप्लेस) is the government e-marketplace for public procurement."))
        check("script: a Hindi question answered only in English is a mismatch", KbReplyText.scriptMismatch("जेम क्या है?", "GeM is the government e-marketplace."))
        check("script: the gate rejects a wrong-script answer",
            PocketRagQualityGate().evaluate("differenxe between single bud and double bid system", hindiAnswer, mixed).reason == "reply_script_mismatch")
        check("script: the prompt states the reply script", PocketRagPromptBuilder.buildParts(mixed.copy(query = "differenxe between single bud and double bid system"), emptyList())
            .userMessage.contains("typed in the English alphabet"))
        val looped = "डबल पैड बिड में निविदा का मूल्यांकन करने खरीदार दो चरणों का उपयोग होता " + "है ".repeat(300)
        check("repetition: the screenshot loop is cut after one copy",
            KbReplyText.trimRepeatedTail(looped) == "डबल पैड बिड में निविदा का मूल्यांकन करने खरीदार दो चरणों का उपयोग होता है") { KbReplyText.trimRepeatedTail(looped).takeLast(60) }
        check("repetition: a repeated phrase is cut", KbReplyText.trimRepeatedTail("Bids are opened. the same step again the same step again the same step again the same step again") == "Bids are opened. the same step again")
        val sellerLoop = "The Seller Type tab displays breakup of sellers based on orders received. Other tabs show bids. " +
            "Seller Type tab displays breakup of sellers based on orders received. Seller Type displays breakup breakup sellers based on orders received. " +
            "Seller Type breakup breakup sellers based on orders received."
        val cleaned = KbReplyText.removeRepeatedSentences(sellerLoop)
        check("repetition: near-duplicate sentences are removed (screenshot)",
            cleaned == "The Seller Type tab displays breakup of sellers based on orders received. Other tabs show bids.") { cleaned }
        check("repetition: stuttered words collapse, also in Hindi",
            KbReplyText.removeRepeatedSentences("GeM is the portal portal for buyers. यह पोर्टल है है।") == "GeM is the portal for buyers. यह पोर्टल है।") {
            KbReplyText.removeRepeatedSentences("GeM is the portal portal for buyers. यह पोर्टल है है।") }
        val steps = "1. Login to GeM.\n2. Click Create Bid.\n3. Select the category.\n4. Publish the bid."
        check("repetition: numbered steps and line breaks are kept", KbReplyText.removeRepeatedSentences(steps) == steps) { KbReplyText.removeRepeatedSentences(steps) }
        val parallel = "1. Click on the Submit button to proceed further.\n2. Click on the Cancel button to proceed further."
        check("repetition: two parallel steps are both kept", KbReplyText.removeRepeatedSentences(parallel) == parallel) { KbReplyText.removeRepeatedSentences(parallel) }
        val distinct = "Open tender is advertised to all bidders. Limited tender is sent to selected bidders only. Single tender is sent to one bidder."
        check("repetition: similar but different sentences are kept", KbReplyText.removeRepeatedSentences(distinct) == distinct) { KbReplyText.removeRepeatedSentences(distinct) }
        // Output limit: cut back to the last complete sentence/step, say there is more.
        val cut = "1. Log in to GeM.\n2. Open Create Bid and choose the category.\n3. Fill in the quantity and deli"
        check("limit: a cut-off answer ends at its last complete step with a continue note",
            KbReplyText.completeAtLimit(cut, likelyAtLimit = true) == "1. Log in to GeM.\n2. Open Create Bid and choose the category.\n\n" + KbReplyText.MORE_EN) {
            KbReplyText.completeAtLimit(cut, likelyAtLimit = true) }
        check("limit: a complete answer is unchanged", KbReplyText.completeAtLimit("PAC is a proprietary article certificate.", likelyAtLimit = true) == "PAC is a proprietary article certificate.")
        check("limit: a short answer is never treated as cut", KbReplyText.completeAtLimit(cut, likelyAtLimit = false) == cut)
        check("limit: a Hindi answer gets the Hindi note",
            KbReplyText.completeAtLimit("पहला चरण लॉगिन है। दूसरा चरण बिड बनाना है। तीसरा चरण मात्रा भरना", true).endsWith(KbReplyText.MORE_HI))
        val longPrev = listOf(PocketRagTurnMemory("How do I create a bid?", "Step one is to log in. " + "Middle detail. ".repeat(60) + "Step five is to publish the bid.\n\n" + KbReplyText.MORE_EN, listOf("S1")))
        val contPacket = PocketRagPacket(
            selection = PocketRagSelection("generic", "p", "Pack"), query = "continue",
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "Log in, create the bid, fill the details and publish it.")),
            sources = listOf(PocketRagSource("S1", "Manual p.3")), coverageComplete = true, followUpOf = "How do I create a bid?"
        )
        val cont = PocketRagPromptBuilder.buildParts(contPacket, longPrev).userMessage
        check("continue: the end of the previous answer is given, without the note",
            cont.contains("Step one is to log in") && cont.contains("Step five is to publish the bid") && !cont.contains("Type \"continue\"")) { cont }
        check("continue: the model is told not to repeat", cont.contains("do not repeat it"))
        val normal = "1. Open tender. 2. Limited tender. 3. Single tender. Each type has very very different rules."
        check("repetition: normal text is unchanged", KbReplyText.trimRepeatedTail(normal) == normal)
        var scriptCalls = 0
        val scriptEngine = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? {
                    scriptCalls++
                    return if (!recoveryMode) "टेंडर के प्रकार ओपन टेंडर, लिमिटेड टेंडर और सिंगल टेंडर हैं।" else uncited
                }
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
            },
            qualityGate = PocketRagQualityGate(), sessions = PocketRagSessionStore(), canUseCloud = { false }
        )
        val rescripted = kotlinx.coroutines.runBlocking { scriptEngine.answer(mixed) }
        check("script: a wrong-script draft is retried once and the English answer is used",
            rescripted.text.startsWith(uncited) && scriptCalls == 2) { "calls=$scriptCalls ${rescripted.text}" }
        val refused = kotlinx.coroutines.runBlocking { engine("[[NOT_IN_EVIDENCE]]").answer(mixed) }
        check("engine: refusal says the search found no answer, with zero sources",
            refused.text == "I couldn't find an answer to this in the selected knowledge base." && refused.sourceIds.isEmpty() && !refused.cloudAttempted) { refused.text }
    }

    /** Abbreviation index, key facts, question rewrite, app-side calculation and extractive answers. */
    private fun smallModelPath() {
        // Abbreviations learned from the pack's own text.
        val ab = GenericKbAliasIndex.Builder()
        repeat(3) { ab.add("The bidder shall submit Performance Security (ePBG) within 14 days of the contract.") }
        repeat(2) { ab.add("These are the General Financial Rules (GFR) of the Government.") }
        ab.add("Material conforming to IS 1234 is accepted. It is the buyer who decides.")
        val aliases = ab.build()
        check("alias: named phrase before (ePBG)", aliases.longForms("epbg", 3) == listOf("performance security")) { aliases.longForms("epbg", 3).toString() }
        check("alias: Schwartz-Hearst long form for GFR", aliases.longForms("GFR", 2).contains("general financial rules")) { aliases.longForms("gfr", 2).toString() }
        check("alias: an ordinary word is never expanded", aliases.longForms("is", 10).isEmpty())
        val expanded = aliases.expansions("what should be the epbg for 20 acs", { 3 })
        check("alias: question expansion", expanded == listOf("performance security")) { expanded.toString() }
        val bytes = java.io.ByteArrayOutputStream()
        aliases.writeTo(java.io.DataOutputStream(bytes))
        val restored = GenericKbAliasIndex.readFrom(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())))
        check("alias: survives the profile cache", restored.longForms("epbg", 3) == listOf("performance security"))

        // Key facts: sentence units, scorer-driven selection, lexical fallback, budget.
        val passageA = "Performance security is to be obtained from the successful bidder awarded the contract. " +
            "Performance security should be for an amount of three to five per cent of the value of the contract. " +
            "It may be furnished as a bank guarantee. The bank guarantee is returned after the warranty period."
        val passageB = "The buyer can create a bid on the portal. Bids are opened on the date given in the bid document. " +
            "Late bids are not accepted under any circumstances by the buyer."
        val units = GenericKbKeyFacts.units(passageA)
        check("key facts: passage split into sentence units", units.size >= 3) { units.toString() }
        val scorer: suspend (String, List<String>) -> FloatArray? = { _, texts ->
            FloatArray(texts.size) { i -> if (texts[i].contains("five per cent")) 9f else if (texts[i].contains("Performance security")) 3f else -5f }
        }
        val facts = kotlinx.coroutines.runBlocking { GenericKbKeyFacts.select("how much performance security", listOf(passageA, passageB), scorer, budget = 300) }
        check("key facts: best sentence first", facts.firstOrNull()?.text?.contains("five per cent") == true) { facts.toString() }
        check("key facts: within budget", facts.sumOf { it.text.length } <= 300 + 200) { facts.sumOf { it.text.length }.toString() }
        check("key facts: unrelated passage not used", facts.none { it.passage == 1 }) { facts.toString() }
        val lexical = kotlinx.coroutines.runBlocking { GenericKbKeyFacts.select("when are late bids accepted", listOf(passageA, passageB), null, budget = 200) }
        check("key facts: lexical scoring without the re-ranker", lexical.firstOrNull()?.text?.contains("Late bids") == true) { lexical.toString() }
        val failing: suspend (String, List<String>) -> FloatArray? = { _, _ -> null }
        val fallback = kotlinx.coroutines.runBlocking { GenericKbKeyFacts.select("when are late bids accepted", listOf(passageA, passageB), failing, budget = 200) }
        check("key facts: a failed re-ranker falls back to lexical", fallback.firstOrNull()?.text?.contains("Late bids") == true) { fallback.toString() }

        val noOverlap = kotlinx.coroutines.runBlocking { GenericKbKeyFacts.select("बोली कैसे बनाएं", listOf(passageA, passageB), null) }
        check("key facts: no re-ranker and no shared words keeps whole passages", noOverlap.isEmpty()) { noOverlap.toString() }

        // Sentence units: a list introduction stays with its first item; a 600-character legal
        // sentence is split at "; " into clauses; near-repeats are not chosen twice.
        val listUnits = GenericKbKeyFacts.units("It is reiterated that any clause incorporated by the Buyer regarding the following shall be treated as null and void: i. Seeking Earnest Money Deposits in contravention to the exemption provided to such sellers. ii. Asking for any Tender fee in case of bid.")
        check("units: list introduction merged with its first item", listUnits.first().contains("null and void") && listUnits.first().contains("Seeking Earnest Money")) { listUnits.toString() }
        val longSentence = "5. All estimates should be prepared on gross basis and voted and charged portions must be shown separately; " + "even expenditure met partly or fully from receipts taken in reduction of such expenditure must be reported in such estimates on gross basis ".repeat(3) + "; care should also be taken that all notional receipts are properly matched."
        val longUnits = GenericKbKeyFacts.units(longSentence)
        check("units: long sentence split into clauses", longUnits.size >= 2 && longUnits.first().startsWith("5. All estimates should be prepared on gross basis")) { longUnits.toString() }
        val repeats = listOf(
            GenericKbKeyFacts.Unit(0, 0, "Buyer can ask EMD and EPBG details, which is allowed only if Bid value is greater than 5 Lakhs, though not mandatory."),
            GenericKbKeyFacts.Unit(1, 0, "Buyer can ask EMD and ePBG details. EMD is allowed only if Bid value is greater than 5 Lakhs, though not mandatory."),
            GenericKbKeyFacts.Unit(2, 0, "Amount of Bid Security shall ordinarily range between two percent to five percent of the estimated value.")
        )
        val distinct = GenericKbKeyFacts.pick(repeats, floatArrayOf(3f, 2.9f, 1f), 1_300, 7)
        check("key facts: a near-repeat is not chosen twice", distinct.size == 2 && distinct.any { it.text.contains("two percent") }) { distinct.toString() }

        // Tables, abbreviations, long lists, conflicting values, multi-part rewrites.
        val tableUnits = GenericKbKeyFacts.units("Chloroquine tablets (150 mg base) Age (in years) Day 1 Day 2 Day 3 Tab. Chloroquine Tab. Chloroquine Tab. Chloroquine <1 ½ ½ ¼ 1-4 1 1 ½ 5-8 2 2 1 9-14 3 3 1½ 15 & above 4 4 2")
        check("units: a dose table stays in one unit with its header", tableUnits.size == 1 && tableUnits[0].contains("Day 1") && tableUnits[0].contains("5-8 2 2 1")) { tableUnits.toString() }
        val listLong = GenericKbKeyFacts.units("Following categories of Sellers shall be exempted from furnishing Bid Security/EMD: i. " + "Micro and Small Enterprises holding valid Udyam Registration who are manufacturers of the offered product or service provider. ".repeat(4) + "ii. Startups recognised by DPIIT.")
        check("units: a list introduction keeps its first item even when the item is long", listLong.first().contains("exempted") && listLong.first().contains("Micro and Small")) { listLong.toString() }
        val conflictFacts = listOf(
            GenericKbKeyFacts.Fact(0, 0, "In case of delay in supplies, the vendor shall be levied LD @ 1.5% per week of delay subject to maximum of 15%.", 2.4f),
            GenericKbKeyFacts.Fact(1, 0, "LD deduction is 0.5 % per week and maximum up to 10% of the order value.", 2.2f),
            GenericKbKeyFacts.Fact(2, 0, "The purchase was made within 10 days.", -4f)
        )
        val conflict = GenericKbKeyFacts.conflict(conflictFacts, { p -> "doc$p" })
        check("conflict: different documents, different percentages", conflict?.kind == "percent" && conflict.statements.size == 2) { conflict.toString() }
        check("conflict: one document only is no conflict", GenericKbKeyFacts.conflict(conflictFacts.take(2), { "same" }) == null)
        val two = GenericKbQuestionRewrite.parse("SEARCH: Who must approve procurement outside GeM?\nSEARCH: What report is needed for procurement outside GeM?\nCALC: NONE", "who approves buying outside gem and what report is needed")
        check("rewrite: two-part question gives two searches", two?.parts?.size == 2 && two.search.startsWith("Who must approve")) { two.toString() }
        check("rewrite: long questions are worth rewriting", GenericKbQuestionRewrite.worthRewriting("who must approve buying outside gem when the item is available on gem and what report is needed"))

        // Whole-passage evidence (PocketSage style): best passage first, cut around its best sentence.
        val longPage = "Intro sentence about the guide. ".repeat(40) + "Performance security should be for an amount of three to five per cent of the value of the contract. " + "Closing remarks of the page. ".repeat(40)
        val win = GenericKbKeyFacts.passageWindows(
            listOf("Short other passage about bids.", longPage),
            listOf(GenericKbKeyFacts.Fact(1, 0, "Performance security should be for an amount of three to five per cent of the value of the contract.", 8f))
        )
        // The re-ranker's best passage (index 0) leads; the passage of the best sentence follows it.
        check("windows: the re-ranker's best passage comes first, then the best sentence's", win.map { it.first }.take(2) == listOf(0, 1)) { win.toString() }
        val winCut = win.first { it.first == 1 }.second
        check("windows: a long page is cut around its best sentence", winCut.contains("three to five per cent") && winCut.length <= GenericKbKeyFacts.WINDOW_CHARS + 20) { winCut.length.toString() }
        check("windows: short passages are kept whole", win.any { it.second == "Short other passage about bids." }) { win.toString() }

        // Continuation protocol and user-chosen Cloud modes.
        val cut = "Rule text … more.\n\n" + KbReplyText.MORE_EN
        check("continue: reply to the app's own note", KbReplyText.isContinuationReply("Continue.", cut))
        check("continue: not without the note", !KbReplyText.isContinuationReply("continue", "A complete answer."))
        check("continue: other words are normal questions", !KbReplyText.isContinuationReply("continue to next step on GeM", cut))
        fun cloudEngine(cloudText: String?, reachable: Boolean) = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? =
                    "To plan HBYC home visits, keep your ASHA diary or register with the list of young children aged 3 to 15 months in your area [S1]."
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = cloudText
            },
            qualityGate = PocketRagQualityGate(), sessions = PocketRagSessionStore(), canUseCloud = { reachable }
        )
        val ashaPacket = PocketRagPacket(
            selection = PocketRagSelection("generic", "asha", "ASHA"),
            query = "How should ASHA plan HBYC home visits?",
            evidenceBlocks = listOf(PocketRagEvidenceBlock("E1", "S1", "Keep your ASHA diary or register with the list of young children aged 3 to 15 months in your area. Record each activity on the HBYC card.")),
            sources = listOf(PocketRagSource("S1", "Handbook for ASHA on HBYC p.17")),
            coverageComplete = true
        )
        val verified = kotlinx.coroutines.runBlocking { cloudEngine("Corrected: keep the ASHA diary with children aged 3 to 15 months and record each visit on the HBYC card [S1].", true).answer(ashaPacket, PocketRagEngine.CloudMode.VERIFY, "draft") }
        check("check online: the Cloud answer is shown as Cloud", verified.provider == AndroBaselineRuntime.Provider.CLOUD && verified.text.startsWith("Corrected")) { verified.text }
        val offline = kotlinx.coroutines.runBlocking { cloudEngine("x", false).answer(ashaPacket, PocketRagEngine.CloudMode.VERIFY, "draft") }
        check("check online offline: honest message, not saved", !offline.accepted && offline.text.contains("couldn't reach")) { offline.text }
        val cloudFirstOffline = kotlinx.coroutines.runBlocking { cloudEngine("x", false).answer(ashaPacket, PocketRagEngine.CloudMode.CLOUD_FIRST) }
        check("cloud first offline: falls back to the local answer", cloudFirstOffline.provider == AndroBaselineRuntime.Provider.LOCAL && cloudFirstOffline.accepted) { cloudFirstOffline.text }

        // Source passages, not-found and weak-match markers.
        val answerText = "Performance security should be three to five per cent of the contract value [1]."
        val ids = listOf(
            "kbref:S1 :: GFR 2017 — page 45",
            KnowledgeSourcePresentation.passageMarker("S1", "Rule 171. Performance security is obtained from the bidder. Performance security should be for an amount of three to five per cent of the value of the contract. It may be a bank guarantee.", answerText),
            KnowledgeSourcePresentation.topicsMarker(KnowledgeSourcePresentation.WEAK_PREFIX, listOf("GFR 2017 — page 45", "GeM guide"))
        )
        val rows = KnowledgeSourcePresentation.passagesForRows(ids)
        check("passages: attached to their source row", rows.size == 1 && rows[0].size == 1) { rows.toString() }
        check("passages: the best-matching sentence is marked", rows[0][0].highlight.contains("three to five per cent")) { rows[0][0].highlight }
        check("markers: not listed as sources", KnowledgeSourcePresentation.visibleSources(ids) == listOf("GFR 2017 — page 45")) { KnowledgeSourcePresentation.visibleSources(ids).toString() }
        check("markers: weak-match topics", KnowledgeSourcePresentation.topicsOf(ids[2]) == listOf("GFR 2017 — page 45", "GeM guide"))
        check("markers: internal markers excluded from reports", ids.drop(1).all(KnowledgeSourcePresentation::isInternalMarker) && !KnowledgeSourcePresentation.isInternalMarker(ids[0]))

        val store2 = PocketRagSessionStore()
        store2.record("kb", "explain PAC rules", "Rule text. ".repeat(500) + "\n\n" + KbReplyText.MORE_EN, emptyList())
        val kept = store2.context("kb").last().assistant
        check("continue: a long cut answer keeps its 'type continue' note in memory", KbReplyText.isContinuationReply("continue", kept)) { kept.takeLast(120) }
        check("sources: a linked row opens at its cited page", KnowledgeSourcePresentation.openTarget("GFR 2017 — pages 45, 47 | https://x.gov.in/gfr.pdf") == "https://x.gov.in/gfr.pdf#page=45")

        // Calculator: evaluation, percentages stated in the facts, Indian grouping.
        check("calc: 20*42000", KbCalculator.evaluate("20 * 42,000")?.compareTo(java.math.BigDecimal(840000)) == 0)
        check("calc: x and brackets", KbCalculator.evaluate("(4 x 3) x 2500")?.compareTo(java.math.BigDecimal(30000)) == 0)
        check("calc: rejects text", KbCalculator.evaluate("ten times 5") == null && KbCalculator.evaluate("NONE") == null)
        val pcts = KbCalculator.percentages("three to five per cent; 3 to 5 per cent of the value; EMD 2%")
        check("calc: percentage range and single", pcts.map { it.toInt() } == listOf(3, 5, 2)) { pcts.toString() }
        val calc = KbCalculator.calculate("20*42000", listOf("The bank guarantee is returned later.", "Performance security should be for an amount of three (3) to five (5) per cent (3 to 10% for Works) of the contract value."))
        val line = calc?.describe().orEmpty()
        check("calc: line states value and each percentage", line.contains("8,40,000") && line.contains("25,200") && line.contains("42,000")) { line }
        check("calc: only the rule's own percentages, not its exceptions", !line.contains("10%")) { line }
        check("calc: number words with digits", KbCalculator.leadingPercentages("three (3) to five (5) per cent").map { it.toInt() } == listOf(3, 5))
        check("calc: Indian grouping", KbCalculator.indian(java.math.BigDecimal("12345678.5")) == "1,23,45,678.50")

        // Question rewrite parsing: only the user's numbers may be used.
        val q = "i am going to procure 20 acs at price of 42000 each what should be the epbg"
        check("rewrite: numeric question may need calculation", GenericKbQuestionRewrite.mayNeedCalculation(q))
        check("rewrite: plain question does not", !GenericKbQuestionRewrite.mayNeedCalculation("what is GFR rule 149"))
        val rw = GenericKbQuestionRewrite.parse("SEARCH: How much performance security (ePBG) should be taken for a contract?\nCALC: 20*42000", q)
        check("rewrite: parsed", rw?.search?.startsWith("How much performance security") == true && rw.calc == "20*42000") { rw.toString() }
        val invented = GenericKbQuestionRewrite.parse("SEARCH: What is the ePBG amount?\nCALC: 20*42000*0.05", q)
        check("rewrite: invented numbers drop the calculation, keep the search", invented?.calc == null && invented?.search != null) { invented.toString() }
        check("rewrite: unusable output", GenericKbQuestionRewrite.parse("I think the answer is 5%", q) == null)
        val prompt = GenericKbQuestionRewrite.prompt(q, listOf("performance security"))
        check("rewrite: prompt carries the pack's abbreviation hints", prompt.contains("performance security") && prompt.contains("Question: $q"))

        // Extractive answer: verbatim key facts with sources when the model fails on strong evidence.
        fun factsPacket(relevance: Float?) = PocketRagPacket(
            selection = PocketRagSelection("generic", "gem", "GeM"),
            query = "how much epbg",
            evidenceBlocks = listOf(
                PocketRagEvidenceBlock("E1", "S1", "Source ID: S1\nSource: S1 :: GFR 2017\n\u2022 Performance security should be for an amount of three to five per cent of the value of the contract.\nCalculated by the app: 20 × 42000 = 8,40,000; 3% of 8,40,000 = 25,200."),
                PocketRagEvidenceBlock("E2", "S2", "Source ID: S2\nSource: S2 :: GeM guide\n\u2022 It may be furnished as a bank guarantee.")
            ),
            sources = listOf(PocketRagSource("S1", "GFR 2017"), PocketRagSource("S2", "GeM guide")),
            coverageComplete = true,
            bestRelevance = relevance
        )
        val store = PocketRagSessionStore()
        val broken = PocketRagEngine(
            local = object : PocketRagLocalAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? =
                    throw PocketRagDegenerateOutput("decoder_loop_guard")
            },
            cloud = object : PocketRagCloudAnswerer {
                override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
            },
            qualityGate = PocketRagQualityGate(), sessions = store, canUseCloud = { false }
        )
        val strong = kotlinx.coroutines.runBlocking { broken.answer(factsPacket(6f)) }
        check("extractive: strong evidence gives the facts verbatim with sources",
            strong.accepted && strong.text.contains("three to five per cent") && strong.text.contains("25,200") && strong.sourceIds.isNotEmpty()) { strong.text }
        val weak = kotlinx.coroutines.runBlocking { broken.answer(factsPacket(0.2f)) }
        check("extractive: weak evidence keeps the honest failure", !weak.accepted && !weak.text.contains("per cent")) { weak.text }
    }

    /** Current/live questions go online (web search); ordinary and KB questions do not. */
    private fun liveQuestions() {
        listOf("what is the weather of mumbai", "latest moon missions of india", "latest moon mission of india in 2026",
            "gold price today", "news about isro", "will it rain tomorrow in delhi", "sensex today")
            .forEach { q -> check("live question goes online: $q", FreshnessTemporalGuard.requiresFreshVerification(q)) }
        listOf("what is the performance security for a contract", "who can sanction leave", "explain photosynthesis",
            "what is a weather forecast?", "traffic rules for two wheelers", "how is ePBG calculated for 20 acs at 42000",
            "rewrite this email to my boss about weather")
            .forEach { q -> check("ordinary question stays offline: $q", !FreshnessTemporalGuard.requiresFreshVerification(q)) }
    }

    /** A short question about a common abbreviation gets its long form; a longer question does not. */
    private fun abbreviationQuestions() {
        val b = GenericKbAliasIndex.Builder()
        repeat(3) { b.add("Accord of Acceptance of Necessity (AoN) by the competent authority.") }
        val index = b.build()
        // AoN in 10% of 100 passages: common, so a long question is searched with the letters only.
        val df = { _: String -> 4 } // AoN in 4% of 100 passages: common (above MAX_SHARE)
        check("full form of aon expands", index.expansions("full form of aon", df, passages = 100) == listOf("acceptance of necessity")) {
            index.expansions("full form of aon", df, passages = 100).toString()
        }
        check("what is aon expands", index.expansions("what is aon", df, passages = 100).isNotEmpty())
        check("long question with common abbreviation does not expand",
            index.expansions("who is competent to accord aon for buy indian cases above 300 crore", df, passages = 100).isEmpty())
        check("check online never guesses: sentinel defined", PocketRagEngine.NOT_IN_EVIDENCE == "[[NOT_IN_KB]]")
    }

    /** The same rule copied from two editions appears once; half-sentences at passage ends are trimmed. */
    private fun repeatedRuleOnce() {
        val twice = "AoN would lapse for all cases where the RFP is not issued within the original validity period of AoN [1]. " +
            "AoN will remain valid for six months [2]. AoN would lapse for all cases where the RFP is not issued within the original validity period of AoN [3]."
        val once = KbReplyText.removeRepeatedSentences(twice)
        check("same rule from two editions kept once", Regex("would lapse").findAll(once).count() == 1 && once.contains("six months")) { once }
        val window = "… The validity of AoN would commence from the date of formal issue of Minutes of Meeting. " +
            "AoN Validity. AoN for Buy schemes will be valid for six months, while AoN will be valid for one year for turnkey projects. " +
            "For cases where the original RFP has been issued and later retracted, the AoN would"
        val trimmed = GenericKbKeyFacts.completeSentences(window)
        check("half sentence at the end is trimmed", !trimmed.contains("the AoN would") && trimmed.contains("turnkey projects.")) { trimmed }
        check("complete passage is unchanged", GenericKbKeyFacts.completeSentences("EMD is 2 to 5 per cent.") == "EMD is 2 to 5 per cent.")
    }

    /** An answer may not give an abbreviation a different full form than the documents do. */
    private fun expansionGuard() {
        val g = PocketRagQualityGate()
        val terms = "AON = acceptance of necessity"
        check("wrong expansion is caught", g.wrongExpansion("AoN stands for Authority of Nominee [S1].", terms) == "AON")
        check("wrong expansion in full-form phrasing is caught", g.wrongExpansion("The full form of AoN is Accord of Need.", terms) == "AON")
        check("right expansion passes", g.wrongExpansion("AoN stands for Acceptance of Necessity [S1]. It is accorded by the DAC.", terms) == null)
        check("right expansion in brackets passes", g.wrongExpansion("The Acceptance of Necessity (AoN) is accorded by the DAC [S1].", terms) == null)
        check("answer without an expansion passes", g.wrongExpansion("AoN is valid for six months [S2].", terms) == null)
        check("no known terms: nothing checked", g.wrongExpansion("AoN stands for anything.", "") == null)
        val b = GenericKbAliasIndex.Builder()
        repeat(3) { b.add("Accord of Acceptance of Necessity (AoN) by the competent authority.") }
        check("pack terms found for a long question", b.build().terms("who accords aon for buy cases above 300 crore") { 4 } == listOf("AON" to "acceptance of necessity"))
    }
}
