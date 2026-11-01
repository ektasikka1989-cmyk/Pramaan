package com.example.arfas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KbConversationMemoryTest {
    private val binding = KbConversationMemory.bindVerifier()
    private val memory = binding.memory

    @Test
    fun forty_turns_keep_six_recent_verbatim_and_summarize_evicted_user_subjects() {
        repeat(40) { index ->
            commit(index, user = "Explain SUBJECT$index", assistant = "Verified answer $index [E$index].")
        }

        val snapshot = memory.snapshot("kb:a", "chat")

        assertEquals((34..39).map { "Explain SUBJECT$it" }, snapshot.recentTurns.map { it.user })
        assertTrue(snapshot.summary.contains("SUBJECT33"))
        assertFalse(snapshot.summary.contains("Verified answer"))
        assertEquals("SUBJECT39", snapshot.entities["lastAcceptedSubject"])
    }

    @Test
    fun rejected_none_provider_and_unaccepted_text_never_mutate_memory() {
        memory.commitRejected("kb:a", "chat", "question", "generic fallback")
        val none = acceptedFixture(1, provider = AndroBaselineRuntime.Provider.NONE)
        val mismatched = acceptedFixture(2).let { it.copy(draft = it.draft.copy(text = "different text")) }

        assertFalse(commitFixture(none))
        assertFalse(commitFixture(mismatched))
        assertEquals(emptySnapshot(), memory.snapshot("kb:a", "chat"))
    }

    @Test
    fun local_or_cloud_fallback_is_rejected_without_a_non_fallback_admission_witness() {
        val localFallback = acceptedFixture(1, assistant = "Generic fallback [E1].")
        val cloudFallback = acceptedFixture(
            2,
            assistant = "Cloud fallback [E2].",
            provider = AndroBaselineRuntime.Provider.CLOUD
        )

        assertFalse(
            commitFixture(
                localFallback,
                admission = KbMemoryAdmissionWitness.Unauthorized
            )
        )
        assertFalse(
            commitFixture(
                cloudFallback,
                admission = KbMemoryAdmissionWitness.Unauthorized
            )
        )
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun accepted_looking_result_without_verifier_admission_is_rejected() {
        val unverified = acceptedFixture(3)

        assertFalse(
            commitFixture(
                unverified,
                admission = KbMemoryAdmissionWitness.Unauthorized
            )
        )
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun verifier_token_is_bound_to_the_exact_draft_and_cannot_authorize_another_result() {
        val authorized = acceptedFixture(4)
        val other = acceptedFixture(5)
        val accepted = acceptedAnswer(binding, authorized)

        assertTrue(accepted?.commitTo(memory) == true)
        assertFalse(commitFixture(other, admission = KbMemoryAdmissionWitness.Unauthorized))
        assertEquals(listOf("Explain SUBJECT4"), memory.snapshot("kb:a", "chat").recentTurns.map { it.user })
    }

    @Test
    fun another_same_module_binding_cannot_forge_admission_for_this_memory() {
        val fixture = acceptedFixture(6)
        val otherBinding = KbConversationMemory.bindVerifier()
        val foreignAccepted = acceptedAnswer(otherBinding, fixture)

        assertFalse(foreignAccepted?.commitTo(memory) == true)
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun verifier_token_is_bound_to_result_text_and_provider() {
        val fixture = acceptedFixture(7)
        val accepted = acceptedAnswer(binding, fixture)

        assertTrue(accepted?.commitTo(memory) == true)
        assertFalse(commitFixture(fixture.copy(result = fixture.result.copy(text = "Altered [E7].")), KbMemoryAdmissionWitness.Unauthorized))
        assertFalse(commitFixture(fixture.copy(result = fixture.result.copy(provider = AndroBaselineRuntime.Provider.CLOUD)), KbMemoryAdmissionWitness.Unauthorized))
        assertEquals(1, memory.snapshot("kb:a", "chat").recentTurns.size)
    }

    @Test
    fun mutable_draft_coverage_and_source_inputs_cannot_change_after_token_issuance() {
        val base = acceptedFixture(8)
        val mutableSources = mutableListOf("S8")
        val mutableEvidence = mutableListOf("E8")
        val mutableCovered = linkedSetOf("C8")
        val mutableCitations = linkedSetOf("E8")
        val result = base.result.copy(
            sourceIds = mutableSources,
            coverage = KbCoverageSnapshot(
                listOf(base.result.coverage.clauses.single().copy(evidenceIds = mutableEvidence))
            )
        )
        val draft = base.draft.copy(
            coveredClauseIds = mutableCovered,
            citedEvidenceIds = mutableCitations
        )
        val fixture = base.copy(result = result, draft = draft)
        val accepted = acceptedAnswer(binding, fixture)
        mutableSources += "MUTATED_SOURCE"
        mutableEvidence += "MUTATED_EVIDENCE"
        mutableCovered += "MUTATED_CLAUSE"
        mutableCitations += "MUTATED_CITATION"

        assertFalse(accepted?.commitTo(memory) == true)
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun long_source_identifier_is_rejected_instead_of_being_truncated() {
        val longSourceId = "S".repeat(97)
        val fixture = acceptedFixture(9, sourceIds = mutableListOf(longSourceId))

        assertFalse(commitFixture(fixture))
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun verifier_witness_must_cover_a_supported_clause_and_its_citations() {
        val unsupported = acceptedFixture(1).let { fixture ->
            fixture.copy(
                result = fixture.result.copy(
                    coverage = KbCoverageSnapshot(
                        listOf(fixture.result.coverage.clauses.single().copy(status = KbCoverageStatus.UNSUPPORTED))
                    )
                )
            )
        }
        val unknownCitation = acceptedFixture(2, assistant = "Verified answer 2 [UNKNOWN].")

        assertFalse(commitFixture(unsupported))
        assertFalse(commitFixture(unknownCitation))
        assertTrue(memory.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun session_pack_and_conversation_keys_are_isolated() {
        commit(1, sessionKey = "kb:a", conversationId = "chat-1")
        commit(2, sessionKey = "kb:b", conversationId = "chat-1")
        commit(3, sessionKey = "kb:a", conversationId = "chat-2")

        assertEquals(listOf("Explain SUBJECT1"), memory.snapshot("kb:a", "chat-1").recentTurns.map { it.user })
        assertEquals(listOf("Explain SUBJECT2"), memory.snapshot("kb:b", "chat-1").recentTurns.map { it.user })
        assertEquals(listOf("Explain SUBJECT3"), memory.snapshot("kb:a", "chat-2").recentTurns.map { it.user })
        assertTrue(memory.snapshot("kb:b", "chat-2").recentTurns.isEmpty())
    }

    @Test
    fun lru_scope_cap_preserves_recent_active_packs_while_evicting_old_unique_keys() {
        val boundedBinding = KbConversationMemory.bindVerifier(maxScopes = 4)
        val bounded = boundedBinding.memory

        fun commitTo(index: Int, sessionKey: String, conversationId: String) {
            val fixture = acceptedFixture(index)
            assertTrue(
                acceptedAnswer(boundedBinding, fixture, sessionKey, conversationId)?.commitTo(bounded) == true
            )
        }

        commitTo(1, "kb:active", "chat")
        commitTo(2, "kb:other-pack", "chat")
        commitTo(3, "kb:old", "old-1")
        commitTo(4, "kb:old", "old-2")
        repeat(20) { index ->
            assertEquals("Explain SUBJECT1", bounded.snapshot("kb:active", "chat").recentTurns.single().user)
            assertEquals("Explain SUBJECT2", bounded.snapshot("kb:other-pack", "chat").recentTurns.single().user)
            commitTo(100 + index, "kb:transient-$index", "chat-$index")
        }

        assertEquals("Explain SUBJECT1", bounded.snapshot("kb:active", "chat").recentTurns.single().user)
        assertEquals("Explain SUBJECT2", bounded.snapshot("kb:other-pack", "chat").recentTurns.single().user)
        assertTrue(bounded.snapshot("kb:old", "old-1").recentTurns.isEmpty())
        assertTrue(bounded.snapshot("kb:old", "old-2").recentTurns.isEmpty())
        assertEquals(
            "Explain SUBJECT119",
            bounded.snapshot("kb:transient-19", "chat-19").recentTurns.single().user
        )
    }

    @Test
    fun clear_and_clear_other_packs_are_scoped_to_the_requested_conversation() {
        commit(1, sessionKey = "kb:a", conversationId = "chat-1")
        commit(2, sessionKey = "kb:b", conversationId = "chat-1")
        commit(3, sessionKey = "kb:a", conversationId = "chat-2")

        memory.clear("kb:a", "chat-1")
        assertTrue(memory.snapshot("kb:a", "chat-1").recentTurns.isEmpty())
        assertEquals(1, memory.snapshot("kb:b", "chat-1").recentTurns.size)
        assertEquals(1, memory.snapshot("kb:a", "chat-2").recentTurns.size)

        commit(4, sessionKey = "kb:a", conversationId = "chat-1")
        memory.clearOtherPacks("kb:b", "chat-1")
        assertTrue(memory.snapshot("kb:a", "chat-1").recentTurns.isEmpty())
        assertEquals(1, memory.snapshot("kb:b", "chat-1").recentTurns.size)
        assertEquals(1, memory.snapshot("kb:a", "chat-2").recentTurns.size)
    }

    @Test
    fun snapshots_are_deeply_defensive_and_immutable() {
        val sourceIds = mutableListOf("S1")
        val unresolved = linkedSetOf("C2")
        val fixture = acceptedFixture(1, sourceIds = sourceIds)
        assertTrue(commitFixture(fixture, unresolved = unresolved))
        sourceIds += "MUTATED"
        unresolved += "MUTATED"

        val snapshot = memory.snapshot("kb:a", "chat")
        assertEquals(listOf("S1"), snapshot.recentTurns.single().sourceIds)
        assertEquals(setOf("C2"), snapshot.unresolvedClauseIds)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.recentTurns as MutableList<KbAcceptedTurn>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.recentTurns.single().sourceIds as MutableList<String>).add("tamper")
        }
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.entities as MutableMap<String, String>)["tamper"] = "value"
        }
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.unresolvedClauseIds as MutableSet<String>).add("tamper")
        }
        assertEquals(1, memory.snapshot("kb:a", "chat").recentTurns.size)
    }

    @Test
    fun every_memory_dimension_is_bounded_and_keeps_the_current_subject() {
        val boundedBinding = KbConversationMemory.bindVerifier(
            maxRecentTurns = 99,
            maxSummaryChars = 120,
            maxEntities = 4,
            maxUnresolvedClauseIds = 3,
            maxUserChars = 40,
            maxAssistantChars = 50,
            maxSourceIds = 2,
            maxEntityChars = 20
        )
        val bounded = boundedBinding.memory
        repeat(20) { index ->
            val fixture = acceptedFixture(
                index,
                user = "Explain SUBJECT$index with deliberately long trailing words",
                assistant = "Verified answer $index [E$index].",
                sourceIds = mutableListOf("S$index", "S${index}b")
            )
            assertTrue(
                acceptedAnswer(boundedBinding, fixture)?.commitTo(
                    bounded,
                    linkedSetOf("C9", "C2", "C7", "C1")
                ) == true
            )
        }

        val snapshot = bounded.snapshot("kb:a", "chat")
        assertEquals(6, snapshot.recentTurns.size)
        assertTrue(snapshot.summary.length <= 120)
        assertTrue(snapshot.entities.size <= 4)
        assertEquals("SUBJECT19", snapshot.entities["lastAcceptedSubject"])
        assertEquals(3, snapshot.unresolvedClauseIds.size)
        assertTrue(snapshot.recentTurns.all { it.user.length <= 40 && it.assistant.length <= 50 })
        assertTrue(snapshot.recentTurns.all { it.sourceIds.size <= 2 })
        assertTrue(snapshot.entities.values.all { it.length <= 20 })
    }

    @Test
    fun oversized_verified_answer_is_rejected_instead_of_storing_an_unwitnessed_prefix() {
        val boundedBinding = KbConversationMemory.bindVerifier(maxAssistantChars = 32)
        val bounded = boundedBinding.memory
        val fixture = acceptedFixture(
            1,
            assistant = "Grounded answer with important detail before its citation [E1]."
        )

        assertFalse(
            acceptedAnswer(boundedBinding, fixture)?.commitTo(bounded) == true
        )
        assertTrue(bounded.snapshot("kb:a", "chat").recentTurns.isEmpty())
    }

    @Test
    fun bounded_verified_answer_keeps_complete_citation_and_source_identifiers() {
        val boundedBinding = KbConversationMemory.bindVerifier(maxAssistantChars = 32)
        val bounded = boundedBinding.memory
        val fixture = acceptedFixture(2, assistant = "Grounded answer [E2].")

        assertTrue(
            acceptedAnswer(boundedBinding, fixture)?.commitTo(bounded) == true
        )
        val turn = bounded.snapshot("kb:a", "chat").recentTurns.single()
        assertEquals("Grounded answer [E2].", turn.assistant)
        assertEquals(listOf("S2"), turn.sourceIds)
    }

    @Test
    fun answer_matching_canonicalizes_outer_whitespace_but_not_internal_whitespace() {
        val canonical = "Grounded answer [E1]."
        val outerWhitespace = acceptedFixture(1, assistant = canonical).let { fixture ->
            fixture.copy(result = fixture.result.copy(text = " \n$canonical\t "))
        }
        val internalWhitespace = acceptedFixture(2, assistant = "Grounded answer [E2].").let { fixture ->
            fixture.copy(result = fixture.result.copy(text = "Grounded  answer [E2]."))
        }

        assertTrue(commitFixture(outerWhitespace))
        assertEquals(canonical, memory.snapshot("kb:a", "chat").recentTurns.single().assistant)
        assertFalse(commitFixture(internalWhitespace))
        assertEquals(1, memory.snapshot("kb:a", "chat").recentTurns.size)
    }

    @Test
    fun referents_use_user_text_and_explicitly_cited_verified_spans_only() {
        commit(1, user = "Explain EPBG", assistant = "MALICIOUS_ENTITY is unsupported prose [E1].")
        var snapshot = memory.snapshot("kb:a", "chat")
        assertEquals("EPBG", snapshot.entities["lastAcceptedSubject"])
        assertFalse(snapshot.entities.values.any { it.contains("MALICIOUS", ignoreCase = true) })

        val sourceBacked = acceptedFixture(
            2,
            user = "Can you help?",
            assistant = "The verified subject is PAC [E2]."
        )
        val separateBinding = KbConversationMemory.bindVerifier()
        val separateMemory = separateBinding.memory
        assertTrue(
            acceptedAnswer(separateBinding, sourceBacked)?.commitTo(separateMemory) == true
        )
        snapshot = separateMemory.snapshot("kb:a", "chat")
        assertEquals("PAC", snapshot.entities["lastAcceptedSubject"])
        assertTrue(snapshot.entities.values.contains("PAC"))
    }

    private fun commit(
        index: Int,
        sessionKey: String = "kb:a",
        conversationId: String = "chat",
        user: String = "Explain SUBJECT$index",
        assistant: String = "Verified answer $index [E$index]."
    ) {
        val fixture = acceptedFixture(index, user = user, assistant = assistant)
        assertTrue(
            acceptedAnswer(binding, fixture, sessionKey, conversationId)?.commitTo(memory) == true
        )
    }

    private fun commitFixture(
        fixture: AcceptedFixture,
        admission: KbMemoryAdmissionWitness? = null,
        unresolved: Set<String> = emptySet()
    ): Boolean = if (admission != null) {
        memory.commitAccepted(
            "kb:a",
            "chat",
            fixture.user,
            fixture.result,
            fixture.draft,
            admission,
            unresolved
        )
    } else {
        acceptedAnswer(binding, fixture)?.commitTo(memory, unresolved) == true
    }

    private fun acceptedAnswer(
        target: KbConversationMemoryBinding,
        fixture: AcceptedFixture,
        sessionKey: String = "kb:a",
        conversationId: String = "chat"
    ): KbVerifierAcceptedAnswer? {
        val verification = target.verifier.verify(
            fixture.plan,
            fixture.draft.text,
            fixture.merged,
            emptyList(),
            fixture.result.coverage
        )
        return target.verifier.acceptForMemory(
            KbTurnRequest(fixture.user, sessionKey, conversationId, emptyList(), false),
            fixture.result,
            verification,
            fixture.merged
        )
    }

    private fun acceptedFixture(
        index: Int,
        user: String = "Explain SUBJECT$index",
        assistant: String = "Verified answer $index [E$index].",
        provider: AndroBaselineRuntime.Provider = AndroBaselineRuntime.Provider.LOCAL,
        sourceIds: MutableList<String> = mutableListOf("S$index")
    ): AcceptedFixture {
        val clauseId = "C$index"
        val evidenceId = "E$index"
        val clause = KbClause(clauseId, user, KbClauseIntent.EXPLANATION)
        val result = KbOrchestratorResult(
            text = assistant,
            provider = provider,
            sourceIds = sourceIds,
            coverage = KbCoverageSnapshot(
                listOf(KbClauseCoverage(clause, KbCoverageStatus.SUPPORTED, listOf(evidenceId)))
            ),
            localAttempted = provider == AndroBaselineRuntime.Provider.LOCAL,
            cloudAttempted = provider == AndroBaselineRuntime.Provider.CLOUD
        )
        val draft = KbVerifiedDraft(
            text = assistant,
            coveredClauseIds = setOf(clauseId),
            citedEvidenceIds = setOf(evidenceId)
        )
        val evidence = KbEvidence(
            evidenceId,
            setOf(clauseId),
            "chunk-$evidenceId",
            PocketRagSource("S$index", "Source $index"),
            "$user — $assistant",
            1f
        )
        val merged = KbMergedEvidence(
            listOf(evidence),
            mapOf(clauseId to listOf(evidenceId)),
            listOf(evidence.source),
            "[$evidenceId | S$index]\n$user — $assistant",
            emptySet()
        )
        val plan = KbQueryPlanV2(user, listOf(clause), listOf(KbExecutionBatch(0, listOf(clauseId))))
        return AcceptedFixture(user, result, draft, plan, merged)
    }

    private fun emptySnapshot() = KbConversationSnapshot(
        recentTurns = emptyList(),
        summary = "",
        entities = emptyMap(),
        unresolvedClauseIds = emptySet()
    )

    private data class AcceptedFixture(
        val user: String,
        val result: KbOrchestratorResult,
        val draft: KbVerifiedDraft,
        val plan: KbQueryPlanV2,
        val merged: KbMergedEvidence
    )
}
