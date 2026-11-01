package com.example.arfas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KbAnswerVerifierTest {
    private val verifier = KbAnswerVerifier()

    @Test
    fun correct_paraphrase_passes_without_repeating_query_verb() {
        val result = verifier.verify(
            plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION)),
            draft = "The designated acceptance authority signs and releases it [E1].",
            merged = evidence("E1", "PAC is issued by the designated acceptance authority."),
            calculations = emptyList()
        )

        assertTrue(result.accepted)
        assertEquals(setOf("C1"), result.verifiedDraft?.coveredClauseIds)
    }

    @Test
    fun missing_second_clause_is_rejected_for_targeted_repair() {
        val plan = plan(
            KbClause("C1", "What is EPBG?", KbClauseIntent.EXPLANATION),
            KbClause("C2", "How is it submitted?", KbClauseIntent.PROCEDURE)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "EPBG is a bank guarantee."),
            "E2" to (setOf("C2") to "The supplier submits the guarantee through the designated portal.")
        )

        val turn = verifier.beginTurn(KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false))
        val result = verifier.verify(turn, plan, "C1: EPBG is a bank guarantee [E1].", merged, emptyList())

        assertFalse(result.accepted)
        assertEquals(setOf("C2"), result.unresolvedClauseIds)
        assertEquals(setOf("E2"), result.repairRequest?.evidenceIds)
    }

    @Test
    fun unknown_citation_and_unsupported_number_are_rejected() {
        val plan = plan(KbClause("C1", "What is the validity?", KbClauseIntent.VALUE))
        val result = verifier.verify(
            plan,
            "Validity is 180 days [E99].",
            evidence("E1", "The validity is 90 days."),
            emptyList()
        )

        assertFalse(result.accepted)
        assertEquals(setOf("E99"), result.invalidCitationIds)
        assertEquals(setOf("180"), result.unsupportedNumericClaims)
    }

    @Test
    fun morphology_and_evidence_ownership_cover_the_right_clause_only() {
        val plan = plan(
            KbClause("C1", "Who approves the request?", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Who releases the order?", KbClauseIntent.EXPLANATION)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "The competent authority approved the request."),
            "E2" to (setOf("C2") to "The purchasing officer releases the order.")
        )

        val result = verifier.verify(
            plan,
            "C1: The request is approved by the competent authority [E1].",
            merged,
            emptyList()
        )

        assertEquals(setOf("C1"), result.verifiedDraft?.coveredClauseIds)
        assertEquals(setOf("C2"), result.unresolvedClauseIds)
    }

    @Test
    fun unsupported_clause_must_be_labelled_and_remains_unresolved() {
        val plan = plan(KbClause("C1", "State the external market price.", KbClauseIntent.VALUE))
        val empty = emptyEvidence(plan)

        val unsafe = verifier.verify(plan, "The market price is 500.", empty, emptyList())
        val safe = verifier.verify(
            plan,
            "C1: Unsupported by the selected knowledge base.",
            empty,
            emptyList()
        )

        assertFalse(unsafe.accepted)
        assertTrue(unsafe.reason.contains("unsupported clause not labelled"))
        assertEquals(setOf("C1"), safe.unresolvedClauseIds)
        assertFalse(safe.reason.contains("not labelled"))
    }

    @Test
    fun deterministic_calculation_value_is_accepted_but_invented_value_is_not() {
        val clause = KbClause("C1", "Calculate 18% of 250000", KbClauseIntent.CALCULATION, calculationExpression = "18% of 250000")
        val plan = plan(clause)
        val calculation = KbMathResult.Success(
            displayValue = "45000",
            formula = "18 / 100 * 250000",
            operands = listOf(KbMathOperand("18", emptyList(), listOf("%")), KbMathOperand("250000", emptyList(), emptyList())),
            units = listOf("%")
        )

        assertTrue(verifier.verify(plan, "The deterministic result is 45000.", emptyEvidence(plan), listOf(calculation)).accepted)
        assertEquals(
            setOf("46000"),
            verifier.verify(plan, "The result is 46000.", emptyEvidence(plan), listOf(calculation)).unsupportedNumericClaims
        )
    }

    @Test
    fun composer_serializes_complete_contract_and_memory_as_non_authoritative_context() {
        val plan = plan(
            KbClause("C1", "What is EPBG?", KbClauseIntent.EXPLANATION),
            KbClause("C2", "How is it submitted?", KbClauseIntent.PROCEDURE)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "EPBG is a bank guarantee."),
            "E2" to (setOf("C2") to "Submit it through the portal.")
        )
        val coverage = KbCoverageSnapshot(listOf(
            KbClauseCoverage(plan.clauses[0], KbCoverageStatus.SUPPORTED, listOf("E1")),
            KbClauseCoverage(plan.clauses[1], KbCoverageStatus.SUPPORTED, listOf("E2"))
        ))
        val memory = KbConversationSnapshot(
            recentTurns = listOf(KbAcceptedTurn("Earlier question", "Earlier verified answer [E0].", listOf("S0"))),
            summary = "Verified request: earlier",
            entities = mapOf("lastAcceptedSubject" to "EPBG"),
            unresolvedClauseIds = setOf("C9")
        )
        val composer = KbAnswerComposer(KbV2LocalAnswerer { null })

        val request = composer.buildRequest(plan, merged, coverage, memory, emptyList())

        assertTrue(request.userMessage.contains(plan.originalQuery))
        assertTrue(request.userMessage.contains("C1") && request.userMessage.contains("C2"))
        assertTrue(request.userMessage.contains("E1") && request.userMessage.contains("E2"))
        assertTrue(request.userMessage.contains("Earlier question"))
        assertTrue(request.systemPrompt.contains("not factual authority", ignoreCase = true))
        assertTrue(request.systemPrompt.contains("Unsupported", ignoreCase = true))
    }

    @Test
    fun repair_is_single_use_and_contains_only_unresolved_clause_evidence() = runBlocking {
        val calls = mutableListOf<KbLocalGenerationRequest>()
        val composer = KbAnswerComposer(KbV2LocalAnswerer { request ->
            calls += request
            "Repaired C2 [E2]."
        })
        val plan = plan(
            KbClause("C1", "Define EPBG", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Explain submission", KbClauseIntent.PROCEDURE)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "EPBG is a guarantee."),
            "E2" to (setOf("C2") to "Submission uses the portal.")
        )
        val turn = verifier.beginTurn(KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false))
        val failed = verifier.verify(turn, plan, "C1: EPBG is a guarantee [E1].", merged, emptyList())
        val repair = assertNotNull(failed.repairRequest)

        assertEquals("Repaired C2 [E2].", composer.repair(repair, plan, merged, emptyMemory(), emptyList()))
        assertNull(composer.repair(repair, plan, merged, emptyMemory(), emptyList()))
        assertEquals(1, calls.size)
        assertTrue(calls.single().evidenceText.contains("[E2"))
        assertFalse(calls.single().evidenceText.contains("[E1"))
        assertEquals(setOf("C2"), repair.clauseIds)
    }

    @Test
    fun only_verifier_accepted_result_can_commit_to_its_paired_memory() {
        val binding = KbConversationMemory.bindVerifier()
        val plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION))
        val merged = evidence("E1", "PAC is issued by the designated acceptance authority.")
        val verification = binding.verifier.verify(
            plan,
            "The designated acceptance authority issues PAC [E1].",
            merged,
            emptyList()
        )
        val result = KbOrchestratorResult(
            text = "The designated acceptance authority issues PAC [E1].",
            provider = AndroBaselineRuntime.Provider.LOCAL,
            sourceIds = listOf("S1"),
            coverage = KbCoverageSnapshot(listOf(
                KbClauseCoverage(plan.clauses.single(), KbCoverageStatus.SUPPORTED, listOf("E1"))
            )),
            localAttempted = true,
            cloudAttempted = false
        )
        val accepted = assertNotNull(binding.verifier.acceptForMemory(
            KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false),
            result,
            verification,
            merged
        ))

        assertTrue(accepted.commitTo(binding.memory))
        assertEquals(1, binding.memory.snapshot("kb:a", "chat").recentTurns.size)
        assertNull(KbAnswerVerifier().acceptForMemory(
            KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false),
            result,
            verification,
            merged
        ))
    }

    @Test
    fun shared_evidence_cannot_mark_an_unrelated_clause_as_answered() {
        val plan = plan(
            KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION),
            KbClause("C2", "What is EPBG validity?", KbClauseIntent.VALUE)
        )
        val merged = evidence(
            "E1" to (setOf("C1", "C2") to
                "PAC is issued by the acceptance authority. EPBG remains valid for 90 days.")
        )

        val result = verifier.verify(
            plan,
            "C1: The acceptance authority issues PAC [E1].",
            merged,
            emptyList()
        )

        assertEquals(setOf("C1"), result.verifiedDraft?.coveredClauseIds)
        assertEquals(setOf("C2"), result.unresolvedClauseIds)
    }

    @Test
    fun calculation_result_is_bound_to_its_clause_section() {
        val plan = plan(
            KbClause("C1", "Calculate 10 + 5", KbClauseIntent.CALCULATION, calculationExpression = "10+5"),
            KbClause("C2", "Calculate 20 + 5", KbClauseIntent.CALCULATION, calculationExpression = "20+5")
        )
        val calculations = listOf(
            calculation("15", "10 + 5", "10", "5"),
            calculation("25", "20 + 5", "20", "5")
        )

        val result = verifier.verify(
            plan,
            "C1: The result is 15.\nC2: The result is 15.",
            emptyEvidence(plan),
            calculations
        )

        assertEquals(setOf("C2"), result.unresolvedClauseIds)
        assertEquals(setOf("C1"), result.verifiedDraft?.coveredClauseIds)
    }

    @Test
    fun numeric_grounding_is_clause_subject_unit_and_date_specific() {
        val plan = plan(
            KbClause("C1", "State EPBG validity and effective date", KbClauseIntent.VALUE),
            KbClause("C2", "State PAC validity", KbClauseIntent.VALUE)
        )
        val merged = evidence(
            "E1" to (setOf("C1", "C2") to
                "EPBG validity is 90 days effective 2024-01-01. PAC validity is 180 days.")
        )

        val wrongSubject = verifier.verify(plan, "C1: EPBG validity is 180 days [E1].\nC2: PAC validity is 180 days [E1].", merged, emptyList())
        val wrongUnit = verifier.verify(plan, "C1: EPBG validity is 90 months effective 2024-01-01 [E1].\nC2: PAC validity is 180 days [E1].", merged, emptyList())
        val wrongDate = verifier.verify(plan, "C1: EPBG validity is 90 days effective 2024-02-01 [E1].\nC2: PAC validity is 180 days [E1].", merged, emptyList())

        assertTrue("C1" in wrongSubject.unresolvedClauseIds)
        assertTrue("C1" in wrongUnit.unresolvedClauseIds)
        assertTrue("C1" in wrongDate.unresolvedClauseIds)
    }

    @Test
    fun authority_negation_and_modal_contradictions_are_rejected() {
        val plan = plan(KbClause("C1", "Who must approve the request?", KbClauseIntent.PROCEDURE))
        val merged = evidence("E1", "The competent authority must approve the request.")

        val wrongAuthority = verifier.verify(plan, "The finance officer must approve the request [E1].", merged, emptyList())
        val wrongPolarity = verifier.verify(plan, "The competent authority must not approve the request [E1].", merged, emptyList())
        val wrongModal = verifier.verify(plan, "The competent authority may approve the request [E1].", merged, emptyList())

        assertFalse(wrongAuthority.accepted)
        assertFalse(wrongPolarity.accepted)
        assertFalse(wrongModal.accepted)
    }

    @Test
    fun procedural_answer_must_cover_all_explicit_evidence_steps() {
        val plan = plan(KbClause("C1", "How is the request submitted?", KbClauseIntent.PROCEDURE))
        val merged = evidence(
            "E1",
            "1. Verify the supplier identity. 2. Record the approving authority. 3. Submit the request through the portal."
        )

        val result = verifier.verify(
            plan,
            "Verify the supplier identity, then submit through the portal [E1].",
            merged,
            emptyList()
        )

        assertFalse(result.accepted)
        assertEquals(setOf("C1"), result.unresolvedClauseIds)
    }

    @Test
    fun surface_divergent_but_evidence_grounded_paraphrase_passes() {
        val plan = plan(KbClause(
            "C1",
            "Identify the body responsible for formalizing provisional acceptance.",
            KbClauseIntent.EXPLANATION
        ))
        val merged = evidence("E1", "PAC is issued by the designated acceptance authority.")

        val result = verifier.verify(
            plan,
            "Responsibility rests with the designated acceptance authority [E1].",
            merged,
            emptyList()
        )

        assertTrue(result.accepted)
    }

    @Test
    fun accepted_memory_artifact_rejects_substituted_coverage_or_source_provenance() {
        val binding = KbConversationMemory.bindVerifier()
        val plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION))
        val merged = evidence("E1", "PAC is issued by the designated acceptance authority.")
        val draft = "The designated acceptance authority issues PAC [E1]."
        val coverage = KbCoverageSnapshot(listOf(
            KbClauseCoverage(plan.clauses.single(), KbCoverageStatus.SUPPORTED, listOf("E1"))
        ))
        val verification = binding.verifier.verify(plan, draft, merged, emptyList(), coverage)
        fun result(sources: List<String>, resultCoverage: KbCoverageSnapshot) = KbOrchestratorResult(
            draft, AndroBaselineRuntime.Provider.LOCAL, sources, resultCoverage, true, false
        )
        val request = KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false)

        assertNull(binding.verifier.acceptForMemory(request, result(listOf("S9"), coverage), verification, merged))
        assertNull(binding.verifier.acceptForMemory(
            request,
            result(listOf("S1"), KbCoverageSnapshot(listOf(coverage.clauses.single().copy(evidenceIds = listOf("E9"))))),
            verification,
            merged
        ))
        assertNotNull(binding.verifier.acceptForMemory(request, result(listOf("S1"), coverage), verification, merged))
    }

    @Test
    fun repair_preserves_partial_status_is_case_insensitive_and_has_one_budget_per_plan() = runBlocking {
        val calls = mutableListOf<KbLocalGenerationRequest>()
        val composer = KbAnswerComposer(KbV2LocalAnswerer { request -> calls += request; "repair" })
        val plan = plan(
            KbClause("C1", "Define EPBG", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Explain every EPBG submission step", KbClauseIntent.PROCEDURE)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "EPBG is a guarantee."),
            "E2" to (setOf("C2") to "Submit the EPBG through the designated portal.")
        )
        val coverage = KbCoverageSnapshot(listOf(
            KbClauseCoverage(plan.clauses[0], KbCoverageStatus.SUPPORTED, listOf("E1")),
            KbClauseCoverage(plan.clauses[1], KbCoverageStatus.PARTIAL, listOf("E2"), "One step is missing")
        ))
        val turn = verifier.beginTurn(KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false))
        val first = verifier.verify(turn, plan, "C1: EPBG is a guarantee [E1].\nc2: unsupported by the selected knowledge base.", merged, emptyList(), coverage)
        val request = assertNotNull(first.repairRequest)

        assertEquals("repair", composer.repair(request, plan, merged, emptyMemory(), emptyList()))
        val copiedPlan = plan.copy(clauses = plan.clauses.map { it.copy() })
        val secondVerification = verifier.verify(turn, copiedPlan, "C1: EPBG is a guarantee [E1].", merged, emptyList(), coverage)
        assertNull(composer.repair(assertNotNull(secondVerification.repairRequest), copiedPlan, merged, emptyMemory(), emptyList()))
        assertEquals(1, calls.size)
        assertTrue(calls.single().userMessage.contains("status=PARTIAL"))
        assertTrue(calls.single().userMessage.contains("C2"))
        assertFalse(calls.single().userMessage.contains("C1"))
        assertTrue(calls.single().evidenceText.contains("E2"))
        assertFalse(calls.single().evidenceText.contains("E1"))
    }

    @Test
    fun number_in_question_is_not_grounding_authority() {
        val plan = plan(KbClause("C1", "Is EPBG validity 180 days?", KbClauseIntent.VALUE))
        val merged = evidence("E1", "EPBG validity is 90 days.")

        val result = verifier.verify(plan, "EPBG validity is 180 days [E1].", merged, emptyList())

        assertFalse(result.accepted)
        assertEquals(setOf("180"), result.unsupportedNumericClaims)
    }

    @Test
    fun claim_checks_do_not_mix_authority_or_modality_across_evidence_sentences() {
        val plan = plan(KbClause("C1", "Who must approve PAC?", KbClauseIntent.PROCEDURE))
        val merged = evidence(
            "E1",
            "The competent authority must approve PAC. The finance officer may review an unrelated invoice."
        )

        val result = verifier.verify(plan, "The finance officer must approve PAC [E1].", merged, emptyList())

        assertFalse(result.accepted)
    }

    @Test
    fun every_numbered_procedure_step_is_required_even_with_unfamiliar_action_verbs() {
        val plan = plan(KbClause("C1", "How is the package processed?", KbClauseIntent.PROCEDURE))
        val merged = evidence("E1", "1. Upload the form. 2. Attach the certificate. 3. Route the package to audit.")

        val result = verifier.verify(plan, "Upload the form and route the package to audit [E1].", merged, emptyList())

        assertFalse(result.accepted)
    }

    @Test
    fun memory_provenance_uses_only_cited_sources_and_binds_exact_result_identity() {
        val binding = KbConversationMemory.bindVerifier()
        val plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION))
        val merged = evidence(
            "E1" to (setOf("C1") to "PAC is issued by the acceptance authority."),
            "E2" to (setOf("C1") to "An unrelated appendix contains background material.")
        )
        val draft = "The acceptance authority issues PAC [E1]."
        val coverage = KbCoverageSnapshot(listOf(
            KbClauseCoverage(plan.clauses.single(), KbCoverageStatus.SUPPORTED, listOf("E1", "E2"))
        ))
        val verification = binding.verifier.verify(plan, draft, merged, emptyList(), coverage)
        val request = KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false)
        val exact = KbOrchestratorResult(draft, AndroBaselineRuntime.Provider.LOCAL, listOf("S1"), coverage, true, false)

        assertNotNull(binding.verifier.acceptForMemory(request, exact, verification, merged))
        assertNull(binding.verifier.acceptForMemory(request, exact.copy(), verification, merged))
        assertNull(binding.verifier.acceptForMemory(request, exact.copy(provider = AndroBaselineRuntime.Provider.CLOUD), verification, merged))
        assertNull(binding.verifier.acceptForMemory(request, exact.copy(localAttempted = false), verification, merged))
        assertNull(binding.verifier.acceptForMemory(request, exact.copy(sourceIds = listOf("S1", "S2")), verification, merged))
    }

    @Test
    fun memory_admission_rejects_changed_content_behind_the_same_evidence_and_source_ids() {
        val binding = KbConversationMemory.bindVerifier()
        val plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION))
        val merged = evidence("E1", "PAC is issued by the acceptance authority.")
        val draft = "The acceptance authority issues PAC [E1]."
        val coverage = KbCoverageSnapshot(listOf(
            KbClauseCoverage(plan.clauses.single(), KbCoverageStatus.SUPPORTED, listOf("E1"))
        ))
        val verification = binding.verifier.verify(plan, draft, merged, emptyList(), coverage)
        val result = KbOrchestratorResult(
            draft, AndroBaselineRuntime.Provider.LOCAL, listOf("S1"), coverage, true, false
        )
        val request = KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false)
        val substituted = merged.copy(
            evidence = merged.evidence.map { it.copy(text = "PAC is issued by a substituted authority.") },
            evidenceText = "[E1 | S1]\nPAC is issued by a substituted authority."
        )

        assertNull(binding.verifier.acceptForMemory(request, result, verification, substituted))
        assertNull(binding.verifier.acceptForMemory(request, result, verification))
        assertNotNull(binding.verifier.acceptForMemory(request, result, verification, merged))
    }

    @Test
    fun repair_capability_rejects_substituted_plan_or_evidence_snapshot() = runBlocking {
        val composer = KbAnswerComposer(KbV2LocalAnswerer { "repair" })
        val plan = plan(
            KbClause("C1", "Define PAC", KbClauseIntent.EXPLANATION),
            KbClause("C2", "Explain PAC procedure", KbClauseIntent.PROCEDURE)
        )
        val merged = evidence(
            "E1" to (setOf("C1") to "PAC is an acceptance certificate."),
            "E2" to (setOf("C2") to "Issue PAC after inspection.")
        )
        val turn = verifier.beginTurn(KbTurnRequest(plan.originalQuery, "kb:a", "chat", emptyList(), false))
        val verification = verifier.verify(turn, plan, "C1: PAC is an acceptance certificate [E1].", merged, emptyList())
        val repair = assertNotNull(verification.repairRequest)
        val substituted = merged.copy(evidence = merged.evidence.map { if (it.id == "E2") it.copy(text = "tampered") else it })

        assertNull(composer.repair(repair, plan, substituted, emptyMemory(), emptyList()))
    }

    @Test
    fun bracketed_clause_heading_is_not_parsed_as_an_evidence_citation() {
        val plan = plan(KbClause("C1", "Who issues PAC?", KbClauseIntent.EXPLANATION))
        val result = verifier.verify(
            plan,
            "[C1]: The acceptance authority issues PAC [E1].",
            evidence("E1", "PAC is issued by the acceptance authority."),
            emptyList()
        )

        assertTrue(result.accepted)
        assertTrue(result.invalidCitationIds.isEmpty())
    }

    private fun plan(vararg clauses: KbClause) = KbQueryPlanV2(
        originalQuery = clauses.joinToString(" ") { it.text },
        clauses = clauses.toList(),
        batches = listOf(KbExecutionBatch(0, clauses.map { it.id }))
    )

    private fun evidence(id: String, text: String): KbMergedEvidence =
        evidence(id to (setOf("C1") to text))

    private fun evidence(vararg blocks: Pair<String, Pair<Set<String>, String>>): KbMergedEvidence {
        val evidence = blocks.mapIndexed { index, (id, owned) ->
            KbEvidence(id, owned.first, "chunk-$id", PocketRagSource("S${index + 1}", "Source ${index + 1}"), owned.second, 1f)
        }
        val clauseIds = evidence.flatMap { it.clauseIds }.distinct()
        return KbMergedEvidence(
            evidence = evidence,
            byClause = clauseIds.associateWith { clause -> evidence.filter { clause in it.clauseIds }.map { it.id } },
            sources = evidence.map { it.source },
            evidenceText = evidence.joinToString("\n\n") { "[${it.id} | ${it.source.id}]\n${it.text}" },
            overflowClauseIds = emptySet()
        )
    }

    private fun emptyEvidence(plan: KbQueryPlanV2) = KbMergedEvidence(
        emptyList(), plan.clauses.associate { it.id to emptyList() }, emptyList(), "", emptySet()
    )

    private fun emptyMemory() = KbConversationSnapshot(emptyList(), "", emptyMap(), emptySet())

    private fun calculation(value: String, formula: String, vararg operands: String) = KbMathResult.Success(
        displayValue = value,
        formula = formula,
        operands = operands.map { KbMathOperand(it, emptyList(), emptyList()) },
        units = emptyList()
    )
}
