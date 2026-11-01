package com.example.arfas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KbOrchestratorTest {
    @Test
    fun generic_supported_question_does_not_call_cloud_and_commits_verified_answer() = runTest {
        val fixture = fixture(
            localResponses = listOf("EPBG is a bank guarantee [E1]."),
            evidence = mapOf("C1" to "EPBG is a bank guarantee."),
            cloudAvailable = true
        )

        val result = fixture.orchestrator.answer(request("What is EPBG?"))

        assertFalse(result.cloudAttempted)
        assertEquals(0, fixture.cloud.requests.size)
        assertEquals(AndroBaselineRuntime.Provider.LOCAL, result.provider)
        assertEquals(1, fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.size)
    }

    @Test
    fun only_unresolved_clause_is_sent_to_cloud_and_verified_combination_is_committed() = runTest {
        val fixture = fixture(
            localResponses = listOf("C1: EPBG is a bank guarantee [E1].", null),
            evidence = mapOf(
                "C1" to "EPBG is a bank guarantee.",
                "C2" to "The release process completes after all contractual obligations are fulfilled."
            ),
            cloudAvailable = true,
            cloudResponse = "C2: The release process completes after all contractual obligations are fulfilled [E2]."
        )

        val result = fixture.orchestrator.answer(request("Define EPBG; Explain the release process"))

        assertEquals(listOf("C2"), fixture.cloud.requests.single().clauses.map { it.id })
        assertTrue(result.cloudAttempted)
        assertEquals(AndroBaselineRuntime.Provider.CLOUD, result.provider)
        assertTrue(result.text.contains("C1:"))
        assertTrue(result.text.contains("C2:"))
        assertEquals(1, fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.size)
    }

    @Test
    fun rejected_cloud_output_preserves_verified_local_clause_and_is_not_committed() = runTest {
        val fixture = fixture(
            localResponses = listOf("C1: EPBG is a bank guarantee [E1].", null),
            evidence = mapOf(
                "C1" to "EPBG is a bank guarantee.",
                "C2" to "The release process completes after fulfilment."
            ),
            cloudAvailable = true,
            cloudResponse = "C2: It always takes 999 days [E99]."
        )

        val result = fixture.orchestrator.answer(request("Define EPBG; Explain the release process"))

        assertTrue(result.text.contains("EPBG is a bank guarantee"))
        assertFalse(result.text.contains("999"))
        assertTrue(result.coverage.clauses.single { it.clause.id == "C2" }.status == KbCoverageStatus.SUPPORTED)
        assertTrue(result.cloudAttempted)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun unsupported_fallback_is_never_committed_to_memory() = runTest {
        val fixture = fixture(
            localResponses = listOf("C1: Unsupported by the selected knowledge base."),
            evidence = emptyMap(),
            cloudAvailable = false
        )

        val result = fixture.orchestrator.answer(request("Unknown corpus fact"))

        assertEquals(AndroBaselineRuntime.Provider.NONE, result.provider)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun required_freshness_without_cloud_uses_the_single_fail_closed_message() = runTest {
        val fixture = fixture(
            localResponses = listOf("The selected KB describes the older position [E1]."),
            evidence = mapOf("C1" to "The selected KB describes the older position."),
            cloudAvailable = false
        )

        val result = fixture.orchestrator.answer(
            request("Give the latest position in 2026", freshnessRequired = true)
        )

        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, result.text)
        assertEquals(AndroBaselineRuntime.Provider.NONE, result.provider)
        assertFalse(result.cloudAttempted)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun explicit_latest_request_escalates_even_when_request_flag_is_false() = runTest {
        val fixture = fixture(
            localResponses = listOf("The selected KB describes the published position [E1]."),
            evidence = mapOf("C1" to "The selected KB describes the published position."),
            cloudAvailable = true,
            cloudResponse = "The selected KB describes the published position [E1]."
        )

        val result = fixture.orchestrator.answer(request("Give the latest published position"))

        assertTrue(result.cloudAttempted)
        assertEquals(KbEscalationReason.FRESH_REQUIRED, fixture.cloud.requests.single().reason)
        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, result.text)
        assertEquals(AndroBaselineRuntime.Provider.NONE, result.provider)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun source_bearing_post_2024_evidence_can_verify_and_commit_a_freshness_answer() = runTest {
        val fresh = KbCloudFreshEvidence(
            id = "F1",
            clauseIds = setOf("C1"),
            source = PocketRagSource(
                id = "WEB1",
                display = "Official 2026 update",
                document = "Official update notice",
                locator = "https://authority.example/update-2026"
            ),
            text = "The current published position is the revised position.",
            asOfYear = 2026
        )
        val fixture = fixture(
            localResponses = listOf("The selected KB describes the older position [E1]."),
            evidence = mapOf("C1" to "The selected KB describes the older position."),
            cloudAvailable = true,
            cloudResult = KbCloudGenerationResult(
                text = "The current published position is the revised position [F1].",
                freshEvidence = listOf(fresh)
            )
        )

        val result = fixture.orchestrator.answer(request("Give the latest published position"))

        assertEquals(AndroBaselineRuntime.Provider.CLOUD, result.provider)
        assertEquals(listOf("WEB1"), result.sourceIds)
        assertTrue(result.text.contains("revised position"))
        assertEquals(1, fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.size)
    }

    @Test
    fun freshness_answer_with_valid_fresh_metadata_but_only_stale_kb_citation_fails_closed() = runTest {
        val fresh = KbCloudFreshEvidence(
            id = "F1",
            clauseIds = setOf("C1"),
            source = PocketRagSource(
                id = "WEB1",
                display = "Official 2026 update",
                document = "Official update notice",
                locator = "https://authority.example/update-2026"
            ),
            text = "The current published position is the revised position.",
            asOfYear = 2026
        )
        val fixture = fixture(
            localResponses = listOf("The selected KB describes the older position [E1]."),
            evidence = mapOf("C1" to "The selected KB describes the older position."),
            cloudAvailable = true,
            cloudResult = KbCloudGenerationResult(
                text = "The selected KB describes the older position [E1].",
                freshEvidence = listOf(fresh)
            )
        )

        val result = fixture.orchestrator.answer(request("Give the latest published position"))

        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, result.text)
        assertEquals(AndroBaselineRuntime.Provider.NONE, result.provider)
        assertTrue(result.cloudAttempted)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun rejected_freshness_cloud_answer_uses_the_same_fail_closed_message() = runTest {
        val fixture = fixture(
            localResponses = listOf("The selected KB describes the older position [E1]."),
            evidence = mapOf("C1" to "The selected KB describes the older position."),
            cloudAvailable = true,
            cloudResponse = "The current position changed in 2026 [E99]."
        )

        val result = fixture.orchestrator.answer(request("Give the latest position"))

        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, result.text)
        assertTrue(result.cloudAttempted)
        assertEquals(AndroBaselineRuntime.Provider.NONE, result.provider)
        assertTrue(fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.isEmpty())
    }

    @Test
    fun verifier_accepted_cloud_synthesis_can_resolve_partial_evidence_coverage() = runTest {
        val fixture = fixture(
            localResponses = listOf("EPBG is a bank guarantee [E1].", null),
            evidence = mapOf("C1" to "EPBG is a bank guarantee."),
            cloudAvailable = true,
            cloudResponse = "EPBG is a bank guarantee [E1].",
            retrievalConfident = false
        )

        val result = fixture.orchestrator.answer(request("What is EPBG?"))

        assertEquals(AndroBaselineRuntime.Provider.CLOUD, result.provider)
        assertEquals(KbCoverageStatus.SUPPORTED, result.coverage.clauses.single().status)
        assertEquals(1, fixture.binding.memory.snapshot("generic:pack", "chat").recentTurns.size)
    }

    private data class Fixture(
        val orchestrator: KbOrchestrator,
        val cloud: CapturingCloud,
        val binding: KbConversationMemoryBinding
    )

    private fun fixture(
        localResponses: List<String?>,
        evidence: Map<String, String>,
        cloudAvailable: Boolean,
        cloudResponse: String? = null,
        cloudResult: KbCloudGenerationResult? = cloudResponse?.let { KbCloudGenerationResult(it) },
        retrievalConfident: Boolean = true
    ): Fixture {
        val responses = localResponses.iterator()
        val composer = KbAnswerComposer(KbV2LocalAnswerer { if (responses.hasNext()) responses.next() else null })
        val cloud = CapturingCloud(cloudResult)
        val binding = KbConversationMemory.bindVerifier()
        val orchestrator = KbOrchestrator(
            analyzer = KbQueryAnalyzer(),
            retrieveClause = { clause ->
                val text = evidence[clause.id]
                KbClauseRetrieval(
                    clauseId = clause.id,
                    hits = if (text == null) emptyList() else listOf(hit(clause.id, text)),
                    diagnostics = KbRetrievalDiagnostics(
                        retrievalMode = if (text == null) KbRetrievalMode.NONE else KbRetrievalMode.HYBRID,
                        confident = text != null && retrievalConfident
                    )
                )
            },
            evidenceMerger = KbEvidenceMerger(),
            mathExecutor = KbMathExecutor(),
            composer = composer,
            memoryBinding = binding,
            escalation = KbEscalationController(cloud, canUseCloud = { cloudAvailable })
        )
        return Fixture(orchestrator, cloud, binding)
    }

    private class CapturingCloud(private val response: KbCloudGenerationResult?) : KbV2CloudAnswerer {
        val requests = mutableListOf<KbCloudGenerationRequest>()
        override suspend fun generate(request: KbCloudGenerationRequest): KbCloudGenerationResult? {
            requests += request
            return response
        }
    }

    private fun request(query: String, freshnessRequired: Boolean = false) = KbTurnRequest(
        originalQuery = query,
        sessionKey = "generic:pack",
        conversationId = "chat",
        memory = emptyList(),
        freshnessRequired = freshnessRequired
    )

    private fun hit(clauseId: String, text: String) = GenericKnowledgePackRepository.Hit(
        chunkId = "chunk-$clauseId",
        originalChunkId = "chunk-$clauseId",
        docId = "doc-$clauseId",
        sourceName = "Source $clauseId",
        pageNumber = 1,
        locator = "section-$clauseId",
        headingContext = "Heading $clauseId",
        unitKind = "paragraph",
        content = text,
        similarity = 0.95f,
        rankScore = 0.95f,
        smallFragment = false
    )
}
