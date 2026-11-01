package com.example.arfas

fun main() {
    val technicalTerms = KnowledgeLexicalText.tokens(
        "tell me something about technical.bid",
        dropStopWords = true
    )
    check("technical" in technicalTerms && "bid" in technicalTerms) {
        "punctuation-linked query must retain searchable component terms: $technicalTerms"
    }
    check("technical.bid" in technicalTerms) {
        "punctuation-linked query must retain the original identifier token: $technicalTerms"
    }
    check(technicalTerms.none { it in setOf("tell", "me", "something", "about") }) {
        "conversational filler must not enter lexical retrieval: $technicalTerms"
    }

    val technicalBid = listOf(
        RagFlowMobileRanker.Candidate(
            "comparison-sheet", 0.842f,
            "commercial comparison sheet and bid awarding summary", "Bid Awarding",
            denseRank = 1, lexicalRank = 1, lexicalScore = 12f
        ),
        RagFlowMobileRanker.Candidate(
            "technical-process", 0.900f,
            "Open Technical Bid and assign the technical evaluator", "Technical Evaluator",
            denseRank = 2, lexicalRank = 2, lexicalScore = 11f
        ),
        RagFlowMobileRanker.Candidate(
            "navigation-noise", 0.885f,
            "dashboard navigation and published opportunities", "Dashboard",
            denseRank = 3
        )
    )
    val technicalRanked = RagFlowMobileRanker.rank(
        "tell me something about technical.bid",
        technicalBid
    )
    check(technicalRanked.firstOrNull()?.id == "technical-process") {
        "direct technical-bid evidence must outrank an incidental bid-only result: $technicalRanked"
    }
    check(technicalRanked.none { it.id == "navigation-noise" }) {
        "uncorroborated semantic-only noise must be removed when direct evidence exists: $technicalRanked"
    }
    check(
        RagFlowMobileRanker.rank("explain technical evaluation", technicalBid)
            .firstOrNull()?.id == "technical-process"
    ) { "technical-evaluation wording must resolve to the evaluator evidence" }

    val candidates = listOf(
        RagFlowMobileRanker.Candidate("semantic-only", 0.90f, "unrelated words", "misc", denseRank = 1),
        RagFlowMobileRanker.Candidate(
            "hybrid", 0.86f, "upload draft bid procedure", "Bid Creation Service",
            denseRank = 2, lexicalRank = 1, lexicalScore = 12f
        ),
        RagFlowMobileRanker.Candidate("weak", 0.40f, "random material", "other", denseRank = 3)
    )
    val ranked = RagFlowMobileRanker.rank("how to upload draft bid", candidates)
    check(ranked.first().id == "hybrid") { "lexically grounded candidate should outrank semantic-only neighbor: $ranked" }
    check(ranked.none { it.id == "weak" }) { "weak evidence should be filtered" }

    val many = (1..20).map { i ->
        RagFlowMobileRanker.Candidate(
            "c$i", 0.88f - i * 0.001f, "bid upload procedure step $i", "Bid Manual",
            denseRank = i
        )
    }
    val kept = RagFlowMobileRanker.rank("bid upload procedure", many)
    check(kept.size > 4) { "ranker must not impose a top-4 evidence cap" }

    val hindi = listOf(
        RagFlowMobileRanker.Candidate("hi", 0.864f, "बोली अपलोड करने की प्रक्रिया", "बोली प्रक्रिया", denseRank = 1),
        RagFlowMobileRanker.Candidate("other", 0.846f, "अलग सामान्य जानकारी", "अन्य", denseRank = 2)
    )
    check(RagFlowMobileRanker.rank("बोली अपलोड प्रक्रिया", hindi).first().id == "hi")

    val outOfDomain = listOf(
        RagFlowMobileRanker.Candidate("dense-noise", 0.835f, "unrelated procurement passage", denseRank = 1),
        RagFlowMobileRanker.Candidate(
            "lexical-noise", 0.81f, "incidental matching words", denseRank = null,
            lexicalRank = 1, lexicalScore = 9f
        )
    )
    check(RagFlowMobileRanker.rank("how should malaria be treated in pregnancy", outOfDomain).isEmpty()) {
        "weak single-channel evidence must fail closed"
    }

    val lexicalRescue = listOf(
        RagFlowMobileRanker.Candidate("dense", 0.89f, "general overview", denseRank = 1),
        RagFlowMobileRanker.Candidate(
            "exact", 0.84f, "ZX-417 reset sequence and verification", "ZX-417 manual",
            denseRank = 2, lexicalRank = 1, lexicalScore = 15f
        )
    )
    check(RagFlowMobileRanker.rank("ZX-417 reset sequence", lexicalRescue).any { it.id == "exact" }) {
        "independent lexical evidence must survive when hybrid-supported"
    }

    val contextRelevant = RagFlowMobileRanker.Candidate(
        "context-relevant", 0.80f, "ZX-417 reset sequence verification", denseRank = null
    )
    val contextNoise = RagFlowMobileRanker.Candidate(
        "context-noise", 0.80f, "general unrelated material", denseRank = null
    )
    check(
        RagFlowMobileRanker.contextScore("ZX-417 reset sequence", contextRelevant, 0.60f) >
            RagFlowMobileRanker.contextScore("ZX-417 reset sequence", contextNoise, 0.60f)
    ) { "query-responsive neighboring context must outrank noise" }
    check(RagFlowMobileRanker.contextScore("ZX-417 reset sequence", contextNoise, 0.60f) == 0.60f) {
        "context scoring must remain finite for broad semantic retrieval"
    }

    val packed = RagFlowMobileRanker.packEvidence(
        listOf(
            RagFlowMobileRanker.Evidence("a1", 0.9f, "source-a", 10, "A".repeat(240), "same"),
            RagFlowMobileRanker.Evidence("a2", 0.89f, "source-a", 10, "B".repeat(220), "same"),
            RagFlowMobileRanker.Evidence("b1", 0.88f, "source-b", 3, "independent supporting evidence", "other")
        ),
        charBudget = 900
    )
    check(packed.any { it.id == "b1" }) { "packet must reserve room for an independent source" }
    check(RagFlowMobileRanker.estimatedPacketChars(packed) <= 900) {
        "packet text plus provenance headers exceeded character budget"
    }

    check(RagFlowMobileRanker.supportsQuery("technical.bid", "Open the technical bid for evaluation"))
    check(!RagFlowMobileRanker.supportsQuery("technical.bid", "Commercial comparison sheet")) {
        "grounding gate must reject evidence that does not cover the user's subject"
    }

    val tenderPacket = PocketRagPacket(
        selection = PocketRagSelection("generic", "procurement", "Procurement"),
        query = "tell me something about different types of tenders",
        evidenceBlocks = listOf(
            PocketRagEvidenceBlock(
                "E1", "S1",
                "The procurement manual lists different types of tenders, including OTE, LTE, and PAC."
            )
        ),
        sources = listOf(PocketRagSource("S1", "Procurement manual")),
        coverageComplete = true
    )
    val gate = PocketRagQualityGate()
    check(
        gate.evaluate(
            tenderPacket.query,
            "Delegation of powers and re-delegation are described in this section.",
            tenderPacket
        ).acceptLocal.not()
    ) { "a fluent answer about delegation must not pass a tender question" }
    check(
        gate.evaluate(
            tenderPacket.query,
            "The different types of tenders listed are OTE, LTE, and PAC.",
            tenderPacket
        ).acceptLocal
    ) { "a direct answer covering the query terms should pass grounding" }

    val visibleSources = KnowledgeSourcePresentation.visibleSources(
        listOf(
            "kbref:S1 :: Technical Evaluator Manual — page 19 | https://example.test/manual.pdf",
            "kbref:S2 :: Technical Evaluator Manual — page 22 | https://example.test/manual.pdf",
            "kbref:S3 :: Bid Awarding Manual — page 18 | https://example.test/award.pdf"
        ) + (4..12).map { "kbref:S$it :: Unique Manual $it — page $it" }
    )
    check(visibleSources.size == 11) {
        "source display must deduplicate documents without an artificial eight-item cap: $visibleSources"
    }
    check(visibleSources.first().contains("pages 19, 22")) {
        "pages from the same document must be grouped into one source: $visibleSources"
    }

    println("ExactE5HybridMobileRankerTest PASS")
}
