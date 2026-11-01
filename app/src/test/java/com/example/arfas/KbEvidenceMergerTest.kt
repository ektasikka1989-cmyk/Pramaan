package com.example.arfas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KbEvidenceMergerTest {
    private val merger = KbEvidenceMerger()

    @Test fun every_supported_clause_receives_evidence_before_extra_chunks() {
        val merged = merger.merge(twoClausePlan(), retrievalsWithManyC1AndOneC2(), maxChars = 900)

        assertTrue(merged.byClause.getValue("C1").isNotEmpty())
        assertTrue(merged.byClause.getValue("C2").isNotEmpty())
        assertEquals(setOf("C1", "C2"), merged.byClause.keys)
    }

    @Test fun identical_chunk_shared_by_two_clauses_is_stored_once() {
        val merged = merger.merge(twoClausePlan(), sameChunkForBothClauses())

        assertEquals(1, merged.evidence.size)
        assertEquals(setOf("C1", "C2"), merged.evidence.single().clauseIds)
        assertEquals(listOf(merged.evidence.single().id), merged.byClause.getValue("C1"))
        assertEquals(listOf(merged.evidence.single().id), merged.byClause.getValue("C2"))
    }

    @Test fun normalized_same_source_text_is_deduplicated_after_chunk_deduplication() {
        val retrievals = listOf(
            KbClauseRetrieval("C1", listOf(hit("chunk-a", "The  policy\n applies  to all bidders.", score = 0.9f))),
            KbClauseRetrieval("C2", listOf(hit("chunk-b", "the policy applies to all bidders", score = 0.8f)))
        )

        val merged = merger.merge(twoClausePlan(), retrievals)

        assertEquals(1, merged.evidence.size)
        assertEquals(setOf("C1", "C2"), merged.evidence.single().clauseIds)
    }

    @Test fun conflicting_versioned_sources_are_flagged_not_silently_collapsed() {
        val retrievals = listOf(KbClauseRetrieval("C1", listOf(
            hit("current", "A bid security is required before submission.", source = "Procurement Manual", document = "manual-2025", effective = "2025-01-01"),
            hit("old", "A bid security is required before submission.", source = "Procurement Manual", document = "manual-2024", effective = "2024-01-01")
        )))

        val merged = merger.merge(oneClausePlan(), retrievals)

        assertEquals(2, merged.evidence.size)
        assertTrue(merged.evidence.all { it.conflictGroup != null })
        assertEquals(merged.evidence[0].conflictGroup, merged.evidence[1].conflictGroup)
    }

    @Test fun budget_includes_labels_and_separators_and_reports_unserved_clause() {
        val retrievals = listOf(
            KbClauseRetrieval("C1", listOf(hit("c1", "First clause evidence.".repeat(8), score = 0.9f))),
            KbClauseRetrieval("C2", listOf(hit("c2", "Second clause evidence.".repeat(8), score = 0.8f)))
        )
        val merged = merger.merge(twoClausePlan(), retrievals, maxChars = 12)

        assertTrue(merged.evidenceText.length <= 12)
        assertTrue(merged.overflowClauseIds.isNotEmpty())
        assertTrue(merged.overflowClauseIds.all { merged.byClause.getValue(it).isEmpty() })
    }

    @Test fun selected_ids_and_sources_have_no_dangling_references() {
        val merged = merger.merge(twoClausePlan(), retrievalsWithManyC1AndOneC2(), maxChars = 900)
        val evidenceIds = merged.evidence.map { it.id }.toSet()
        val sourceIds = merged.sources.map { it.id }.toSet()

        assertTrue(merged.byClause.values.flatten().all { it in evidenceIds })
        assertTrue(merged.evidence.all { it.source.id in sourceIds })
        assertEquals((1..merged.evidence.size).map { "E$it" }, merged.evidence.map { it.id })
        assertEquals((1..merged.sources.size).map { "S$it" }, merged.sources.map { it.id })
        assertFalse(merged.evidenceText.length > 900)
    }

    @Test fun exact_tight_budget_reserves_future_separator_costs() {
        val retrievals = listOf(
            KbClauseRetrieval("C1", listOf(hit("c1", "abc", score = 0.9f))),
            KbClauseRetrieval("C2", listOf(hit("c2", "z", score = 0.8f, source = "Source B")))
        )

        val merged = merger.merge(twoClausePlan(), retrievals, maxChars = 24)

        assertEquals(24, merged.evidenceText.length)
        assertTrue(merged.byClause.getValue("C1").isNotEmpty())
        assertTrue(merged.byClause.getValue("C2").isNotEmpty())
        assertTrue(merged.overflowClauseIds.isEmpty())
    }

    @Test fun same_chunk_with_divergent_versions_preserves_conflict_variants_and_owners() {
        val merged = merger.merge(twoClausePlan(), listOf(
            KbClauseRetrieval("C1", listOf(hit("shared", "Current version text", source = "Manual", document = "manual-2025", effective = "2025-01-01"))),
            KbClauseRetrieval("C2", listOf(hit("shared", "Superseded version text", source = "Manual", document = "manual-2024", effective = "2024-01-01")))
        ))

        assertEquals(2, merged.evidence.size)
        assertEquals(setOf("C1"), merged.byClause.getValue("C1").flatMap { id -> merged.evidence.first { it.id == id }.clauseIds }.toSet())
        assertEquals(setOf("C2"), merged.byClause.getValue("C2").flatMap { id -> merged.evidence.first { it.id == id }.clauseIds }.toSet())
        assertTrue(merged.evidence.all { it.conflictGroup != null })
    }

    @Test fun same_text_at_different_pages_is_preserved_as_distinct_provenance() {
        val merged = merger.merge(twoClausePlan(), listOf(
            KbClauseRetrieval("C1", listOf(hit("page-1", "The policy applies.", page = 1))),
            KbClauseRetrieval("C2", listOf(hit("page-2", "The policy applies.", page = 2)))
        ))

        assertEquals(2, merged.evidence.size)
        assertEquals(setOf("1", "2"), merged.evidence.map { it.source.page }.toSet())
    }

    @Test fun normalized_text_preserves_comparison_operators() {
        val merged = merger.merge(oneClausePlan(), listOf(KbClauseRetrieval("C1", listOf(
            hit("greater", "amount > 10"),
            hit("less", "amount < 10")
        ))))

        assertEquals(2, merged.evidence.size)
        assertEquals(setOf("amount > 10", "amount < 10"), merged.evidence.map { it.text }.toSet())
    }

    @Test fun equal_scores_have_stable_order_when_input_is_reversed() {
        val forward = listOf(
            hit("b", "Beta evidence", source = "Source B"),
            hit("a", "Alpha evidence", source = "Source A")
        )
        val plan = oneClausePlan()
        val first = merger.merge(plan, listOf(KbClauseRetrieval("C1", forward)))
        val second = merger.merge(plan, listOf(KbClauseRetrieval("C1", forward.reversed())))

        assertEquals(
            first.evidence.map { Triple(it.id, it.chunkId, it.source.id) },
            second.evidence.map { Triple(it.id, it.chunkId, it.source.id) }
        )
    }

    @Test fun missing_optional_metadata_does_not_create_a_conflict() {
        val merged = merger.merge(twoClausePlan(), listOf(
            KbClauseRetrieval("C1", listOf(hit("a", "The policy applies."))),
            KbClauseRetrieval("C2", listOf(hit("b", "The policy applies.", effective = "2025-01-01")))
        ))

        assertEquals(1, merged.evidence.size)
        assertEquals(null, merged.evidence.single().conflictGroup)
        assertEquals(setOf("C1", "C2"), merged.evidence.single().clauseIds)
    }

    @Test fun coherent_extra_is_chosen_before_unrelated_equal_score_extra() {
        val retrievals = listOf(
            KbClauseRetrieval("C1", listOf(
                hit("anchor", "A", score = 0.9f, source = "Manual", document = "manual", section = "Rules"),
                hit("coherent", "B", score = 0.7f, source = "Manual", document = "manual", section = "Rules"),
                hit("unrelated", "C", score = 0.7f, source = "Other", document = "other", section = "Other")
            )),
            KbClauseRetrieval("C2", listOf(hit("second", "D", score = 0.8f, source = "Second", document = "second")))
        )

        val merged = merger.merge(twoClausePlan(), retrievals, maxChars = 37)

        assertEquals(listOf("anchor", "second", "coherent"), merged.evidence.map { it.chunkId })
    }

    @Test fun negative_budget_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            merger.merge(oneClausePlan(), emptyList(), maxChars = -1)
        }
    }

    @Test fun equal_metadata_counts_with_missing_owner_have_stable_variant_ownership_when_reversed() {
        val forward = listOf(
            KbClauseRetrieval("C1", listOf(hit("shared", "Same text", effective = "2024-01-01"))),
            KbClauseRetrieval("C2", listOf(hit("shared", "Same text", effective = "2025-01-01"))),
            KbClauseRetrieval("C3", listOf(hit("shared", "Same text")))
        )
        val plan = threeClausePlan()
        val first = merger.merge(plan, forward)
        val second = merger.merge(plan, forward.reversed())

        assertEquals(evidenceOwnership(first), evidenceOwnership(second))
        assertEquals(first.byClause, second.byClause)
        assertEquals(first, second)
        assertEquals(listOf(setOf("C1", "C3"), setOf("C2")), first.evidence.map { it.clauseIds })
        assertEquals(mapOf("C1" to listOf("E1"), "C2" to listOf("E2"), "C3" to listOf("E1")), first.byClause)
        assertEquals(setOf("C1", "C2", "C3"), first.evidence.flatMap { it.clauseIds }.toSet())
    }

    @Test fun full_identity_prevents_java_hash_collision_from_dropping_distinct_evidence() {
        // Both normalized chunk/text fields are equal-length Java-hash collisions:
        // "0z" == "2<" and "= z01 ! b" == "<> 1 < 0z" by String.hashCode().
        assertEquals("0z".hashCode(), "2<".hashCode())
        assertEquals("= z01 ! b".hashCode(), "<> 1 < 0z".hashCode())
        val merged = merger.merge(twoClausePlan(), listOf(
            KbClauseRetrieval("C1", listOf(hit("0z", "=z01!b"))),
            KbClauseRetrieval("C2", listOf(hit("2<", "<>1<0z")))
        ))

        assertEquals(2, merged.evidence.size)
        assertEquals(setOf("0z", "2<"), merged.evidence.map { it.chunkId }.toSet())
        assertEquals(setOf("C1"), merged.byClause.getValue("C1").flatMap { id -> merged.evidence.first { it.id == id }.clauseIds }.toSet())
        assertEquals(setOf("C2"), merged.byClause.getValue("C2").flatMap { id -> merged.evidence.first { it.id == id }.clauseIds }.toSet())
    }

    @Test fun normalized_ties_choose_the_same_literal_text_when_input_is_reversed() {
        val forward = listOf(hit("shared", "same text"), hit("shared", "Same text"))

        val first = merger.merge(oneClausePlan(), listOf(KbClauseRetrieval("C1", forward)))
        val second = merger.merge(oneClausePlan(), listOf(KbClauseRetrieval("C1", forward.reversed())))

        assertEquals("Same text", first.evidence.single().text)
        assertEquals(first, second)
    }

    @Test fun identity_field_separators_cannot_collapse_distinct_sources() {
        val merged = merger.merge(twoClausePlan(), listOf(
            KbClauseRetrieval("C1", listOf(hit("shared", "Same text", source = "Source A\u001fpart", document = "document-a"))),
            KbClauseRetrieval("C2", listOf(hit("shared", "Same text", source = "Source A", document = "part\u001fdocument-a")))
        ))

        assertEquals(2, merged.evidence.size)
        assertEquals(2, merged.sources.size)
        assertEquals("Source A\u001fpart", merged.evidence.first { "C1" in it.clauseIds }.source.display)
        assertEquals("Source A", merged.evidence.first { "C2" in it.clauseIds }.source.display)
    }

    private fun twoClausePlan() = KbQueryPlanV2(
        originalQuery = "Explain the rule and the exception",
        clauses = listOf(
            KbClause("C1", "Explain the rule", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Explain the exception", KbClauseIntent.EXPLANATION)
        ),
        batches = listOf(KbExecutionBatch(0, listOf("C1", "C2")))
    )

    private fun oneClausePlan() = KbQueryPlanV2(
        originalQuery = "Explain the rule",
        clauses = listOf(KbClause("C1", "Explain the rule", KbClauseIntent.EXPLANATION)),
        batches = listOf(KbExecutionBatch(0, listOf("C1")))
    )

    private fun threeClausePlan() = KbQueryPlanV2(
        originalQuery = "Explain three versions",
        clauses = listOf(
            KbClause("C1", "Explain version one", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Explain version two", KbClauseIntent.EXPLANATION),
            KbClause("C3", "Explain version three", KbClauseIntent.EXPLANATION)
        ),
        batches = listOf(KbExecutionBatch(0, listOf("C1", "C2", "C3")))
    )

    private fun evidenceOwnership(merged: KbMergedEvidence) = merged.evidence.map {
        Triple(it.id, it.chunkId, it.clauseIds.toSortedSet())
    }

    private fun retrievalsWithManyC1AndOneC2() = listOf(
        KbClauseRetrieval("C1", listOf(
            hit("c1-best", "The main rule applies to all qualifying submissions.", score = 0.99f),
            hit("c1-extra", "The main rule is evaluated after the eligibility review.", score = 0.92f),
            hit("c1-extra-2", "The main rule has a documented review record.", score = 0.87f)
        )),
        KbClauseRetrieval("C2", listOf(hit("c2-only", "The exception applies when the documented waiver is approved.", score = 0.70f)))
    )

    private fun sameChunkForBothClauses() = listOf(
        KbClauseRetrieval("C1", listOf(hit("shared", "Shared evidence supports both requested parts.", score = 0.9f))),
        KbClauseRetrieval("C2", listOf(hit("shared", "Shared evidence supports both requested parts.", score = 0.8f)))
    )

    private fun hit(
        chunkId: String,
        content: String,
        score: Float = 0.9f,
        source: String = "Source A",
        document: String = "document-a",
        effective: String? = null,
        page: Int = 4,
        section: String = "Requirements",
        locator: String = "paragraph 2",
        originalChunkId: String? = null
    ) = GenericKnowledgePackRepository.Hit(
        chunkId = chunkId,
        originalChunkId = originalChunkId,
        docId = document,
        sourceName = source,
        pageNumber = page,
        locator = locator,
        headingContext = section,
        unitKind = null,
        content = content,
        similarity = score,
        rankScore = score,
        smallFragment = false,
        sourceEffectiveDate = effective
    )
}
