package com.example.arfas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class KbEscalationControllerTest {
    @Test
    fun generic_supported_clause_never_becomes_cloud_eligible() {
        val fixture = fixture(cloudAvailable = true)
        val plan = plan(KbClause("C1", "Explain the policy", KbClauseIntent.EXPLANATION))

        val decision = fixture.controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )

        assertFalse(decision.shouldCallCloud)
        assertTrue(decision.clauseIds.isEmpty())
        assertEquals(KbEscalationReason.NONE, decision.reason)
    }

    @Test
    fun unresolved_ids_are_deduplicated_and_ordered_by_the_query_plan() {
        val fixture = fixture(cloudAvailable = true)
        val plan = plan(
            KbClause("C1", "First", KbClauseIntent.GENERAL),
            KbClause("C2", "Second", KbClauseIntent.GENERAL),
            KbClause("C3", "Third", KbClauseIntent.GENERAL)
        )

        val decision = fixture.controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = linkedSetOf("C3", "C2", "C3", "UNKNOWN"),
            localGenerationFailed = false
        )

        assertEquals(listOf("C2", "C3"), decision.clauseIds)
        assertEquals(KbEscalationReason.UNRESOLVED, decision.reason)
        assertTrue(decision.shouldCallCloud)
    }

    @Test
    fun explicit_current_latest_and_post_2024_years_require_freshness_but_2024_does_not() {
        val fixture = fixture(cloudAvailable = true)
        val clauses = listOf(
            KbClause("C1", "Explain the rule in 2024", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Give the latest position", KbClauseIntent.VALUE),
            KbClause("C3", "What is current status?", KbClauseIntent.VALUE),
            KbClause("C4", "Explain the 2026 amendment", KbClauseIntent.EXPLANATION)
        )
        val plan = plan(*clauses.toTypedArray())

        val decision = fixture.controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )

        assertEquals(listOf("C2", "C3", "C4"), decision.clauseIds)
        assertEquals(KbEscalationReason.FRESH_REQUIRED, decision.reason)
        assertTrue(decision.freshnessRequired)
    }

    @Test
    fun arithmetic_and_currency_numbers_are_not_misclassified_as_post_2024_years() {
        val fixture = fixture(cloudAvailable = true)
        val plan = plan(
            KbClause("C1", "Calculate 2025 + 5", KbClauseIntent.CALCULATION, calculationExpression = "2025 + 5"),
            KbClause("C2", "The amount is INR 2026", KbClauseIntent.VALUE),
            KbClause("C3", "Explain the rule issued in 2026", KbClauseIntent.EXPLANATION)
        )

        val decision = fixture.controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )

        assertEquals(listOf("C3"), decision.clauseIds)
    }

    @Test
    fun semantic_freshness_flag_applies_when_no_explicit_temporal_clause_was_found() {
        val fixture = fixture(cloudAvailable = true)
        val plan = plan(KbClause("C1", "Has the governing position changed?", KbClauseIntent.VALUE))

        val decision = fixture.controller.decide(
            request = request(plan.originalQuery, freshnessRequired = true),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )

        assertEquals(listOf("C1"), decision.clauseIds)
        assertEquals(KbEscalationReason.FRESH_REQUIRED, decision.reason)
    }

    @Test
    fun every_unavailable_freshness_path_returns_the_same_2024_aware_message() {
        val fixture = fixture(cloudAvailable = false)
        val plan = plan(KbClause("C1", "Give the latest position in 2026", KbClauseIntent.VALUE))

        val explicit = fixture.controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )
        val semantic = fixture.controller.decide(
            request = request("Has it changed?", freshnessRequired = true),
            plan = plan(KbClause("C1", "Has it changed?", KbClauseIntent.VALUE)),
            coverage = coverage(plan(KbClause("C1", "Has it changed?", KbClauseIntent.VALUE)), KbCoverageStatus.UNSUPPORTED),
            unresolvedClauseIds = setOf("C1"),
            localGenerationFailed = true
        )

        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, explicit.failureMessage)
        assertEquals(KbEscalationController.FRESHNESS_UNAVAILABLE_MESSAGE, semantic.failureMessage)
        assertTrue(explicit.failureMessage!!.contains("2024"))
        assertFalse(explicit.shouldCallCloud)
        assertFalse(semantic.shouldCallCloud)
    }

    @Test
    fun cloud_request_contains_only_target_clauses_and_their_owned_evidence() = runTest {
        val fixture = fixture(cloudAvailable = true, response = "C2: Supported [E2].")
        val plan = plan(
            KbClause("C1", "First", KbClauseIntent.GENERAL),
            KbClause("C2", "Second", KbClauseIntent.GENERAL)
        )
        val merged = merged(plan)
        val decision = KbEscalationDecision(
            clauseIds = listOf("C2"),
            reason = KbEscalationReason.UNRESOLVED,
            freshnessRequired = false,
            shouldCallCloud = true,
            failureMessage = null
        )

        fixture.controller.generate(
            request = request(plan.originalQuery),
            plan = plan,
            merged = merged,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            memory = emptyMemory(),
            decision = decision
        )

        val captured = fixture.cloud.requests.single()
        assertEquals(listOf("C2"), captured.clauses.map { it.id })
        assertEquals(listOf("E2"), captured.evidence.map { it.id })
        assertFalse(captured.evidenceText.contains("first evidence", ignoreCase = true))
        assertTrue(captured.evidenceText.contains("second evidence", ignoreCase = true))
    }

    @Test
    fun cancellation_from_cloud_is_never_converted_into_a_fallback() = runTest {
        val cloud = KbV2CloudAnswerer { throw CancellationException("cancel turn") }
        val controller = KbEscalationController(cloud, canUseCloud = { true })
        val plan = plan(KbClause("C1", "Explain latest position", KbClauseIntent.VALUE))
        val decision = controller.decide(
            request = request(plan.originalQuery),
            plan = plan,
            coverage = coverage(plan, KbCoverageStatus.SUPPORTED),
            unresolvedClauseIds = emptySet(),
            localGenerationFailed = false
        )

        assertFailsWith<CancellationException> {
            controller.generate(request(plan.originalQuery), plan, merged(plan), coverage(plan, KbCoverageStatus.SUPPORTED), emptyMemory(), decision)
        }
    }

    private data class Fixture(
        val cloud: CapturingCloud,
        val controller: KbEscalationController
    )

    private fun fixture(cloudAvailable: Boolean, response: String? = null): Fixture {
        val cloud = CapturingCloud(response?.let { KbCloudGenerationResult(it) })
        return Fixture(cloud, KbEscalationController(cloud, canUseCloud = { cloudAvailable }))
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

    private fun plan(vararg clauses: KbClause) = KbQueryPlanV2(
        originalQuery = clauses.joinToString("; ") { it.text },
        clauses = clauses.toList(),
        batches = listOf(KbExecutionBatch(0, clauses.map { it.id }))
    )

    private fun coverage(plan: KbQueryPlanV2, status: KbCoverageStatus) = KbCoverageSnapshot(
        plan.clauses.map { clause ->
            KbClauseCoverage(clause, status, if (status == KbCoverageStatus.SUPPORTED) listOf("E${clause.id.drop(1)}") else emptyList())
        }
    )

    private fun merged(plan: KbQueryPlanV2): KbMergedEvidence {
        val evidence = plan.clauses.mapIndexed { index, clause ->
            val id = "E${index + 1}"
            KbEvidence(id, setOf(clause.id), "chunk-${index + 1}", source("S${index + 1}"), "${if (index == 0) "first" else "second"} evidence", 0.9f)
        }
        return KbMergedEvidence(
            evidence = evidence,
            byClause = evidence.associate { it.clauseIds.single() to listOf(it.id) },
            sources = evidence.map { it.source },
            evidenceText = evidence.joinToString("\n\n") { "[${it.id} | ${it.source.id}]\n${it.text}" },
            overflowClauseIds = emptySet()
        )
    }

    private fun source(id: String) = PocketRagSource(id, "Source $id")
    private fun emptyMemory() = KbConversationSnapshot(emptyList(), "", emptyMap(), emptySet())
}
