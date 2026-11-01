package com.example.arfas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KbQueryAnalyzerTest {
    private val analyzer = KbQueryAnalyzer()

    @Test
    fun mixed_language_compound_query_retains_both_requests() = runTest {
        val plan = analyzer.analyze(request("PAC kya hai aur iski validity kya hai?"), emptySnapshot())

        assertEquals("PAC kya hai aur iski validity kya hai?", plan.originalQuery)
        assertEquals(2, plan.clauses.size)
        assertEquals(listOf("C1", "C2"), plan.clauses.map { it.id })
    }

    @Test
    fun fifteen_clauses_become_two_batches_without_loss() = runTest {
        val query = (1..15).joinToString("; ") { "Explain requirement $it" }
        val plan = analyzer.analyze(request(query), emptySnapshot())

        assertEquals(15, plan.clauses.size)
        assertEquals(listOf(12, 3), plan.batches.map { it.clauseIds.size })
        assertEquals((1..15).map { "C$it" }, plan.batches.flatMap { it.clauseIds })
        assertEquals((1..15).map { "Explain requirement $it" }, plan.clauses.map { it.text })
    }

    @Test
    fun punctuation_free_hinglish_does_not_drop_tail_request() = runTest {
        val plan = analyzer.analyze(
            request("EPBG kya hai aur kab submit hoti hai aur release ka process batao"),
            emptySnapshot()
        )

        assertEquals(
            listOf("EPBG kya hai", "kab submit hoti hai", "release ka process batao"),
            plan.clauses.map { it.text }
        )
    }

    @Test
    fun detail_follow_up_resolves_last_accepted_subject() = runTest {
        val snapshot = snapshotWithReferent("EPBG")
        val plan = analyzer.analyze(request("Explain it in detail"), snapshot)

        assertEquals("EPBG", plan.clauses.single().continuationOf)
    }

    @Test
    fun self_contained_question_does_not_inherit_an_unrelated_referent() = runTest {
        val plan = analyzer.analyze(request("What is photosynthesis?"), snapshotWithReferent("EPBG"))

        assertNull(plan.clauses.single().continuationOf)
    }

    @Test
    fun comparison_object_is_not_split_by_a_later_request() = runTest {
        val plan = analyzer.analyze(request("Compare cats and dogs and explain care"), emptySnapshot())

        assertEquals(listOf("Compare cats and dogs", "explain care"), plan.clauses.map { it.text })
    }

    @Test
    fun rejects_reversed_overlapping_and_duplicated_semantic_segments() = runTest {
        val original = "Alpha task beta task gamma task"
        val overlapOriginal = "Alpha beta gamma delta"
        val duplicateOriginal = "Alpha beta"
        val reversed = KbQueryAnalyzer { listOf("beta task", "Alpha task", "gamma task") }
        val overlapping = KbQueryAnalyzer { listOf("Alpha beta gamma", "beta gamma delta") }
        val duplicated = KbQueryAnalyzer { listOf("Alpha beta", "Alpha beta") }

        assertEquals(listOf(original), reversed.analyze(request(original), emptySnapshot()).clauses.map { it.text })
        assertEquals(
            listOf(overlapOriginal),
            overlapping.analyze(request(overlapOriginal), emptySnapshot()).clauses.map { it.text }
        )
        assertEquals(
            listOf(duplicateOriginal),
            duplicated.analyze(request(duplicateOriginal), emptySnapshot()).clauses.map { it.text }
        )
    }

    @Test
    fun accepts_monotonic_non_overlapping_literal_semantic_segments() = runTest {
        val original = "Alpha beta gamma delta"
        val semantic = KbQueryAnalyzer { listOf("Alpha beta", "gamma delta") }

        assertEquals(
            listOf("Alpha beta", "gamma delta"),
            semantic.analyze(request(original), emptySnapshot()).clauses.map { it.text }
        )
    }

    @Test
    fun inline_numbered_list_splits_without_splitting_decimal_or_ordinary_numbers() = runTest {
        val numbered = analyzer.analyze(request("1) Explain A 2) Explain B"), emptySnapshot())
        val numericText = analyzer.analyze(request("Version 1.2 has 2 items"), emptySnapshot())

        assertEquals(listOf("1) Explain A", "2) Explain B"), numbered.clauses.map { it.text })
        assertEquals(listOf("Version 1.2 has 2 items"), numericText.clauses.map { it.text })
    }

    @Test
    fun safe_arithmetic_is_marked_without_accepting_code_like_text() = runTest {
        val safe = analyzer.analyze(request("Calculate 18% of 250000"), emptySnapshot())
        val unsafe = analyzer.analyze(
            request("Runtime.getRuntime().exec('x')"),
            emptySnapshot()
        )

        assertEquals("18% of 250000", safe.clauses.single().calculationExpression)
        assertEquals(KbClauseIntent.CALCULATION, safe.clauses.single().intent)
        assertNull(unsafe.clauses.single().calculationExpression)
    }

    private fun request(query: String) = KbTurnRequest(
        originalQuery = query,
        sessionKey = "selected-kb",
        conversationId = "conversation-1",
        memory = emptyList(),
        freshnessRequired = false
    )

    private fun emptySnapshot() = KbConversationSnapshot(
        recentTurns = emptyList(),
        summary = "",
        entities = emptyMap(),
        unresolvedClauseIds = emptySet()
    )

    private fun snapshotWithReferent(referent: String) = emptySnapshot().copy(
        entities = mapOf("lastAcceptedSubject" to referent)
    )
}
