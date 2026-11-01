package com.example.arfas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KbCoverageLedgerTest {
    @Test
    fun coverage_cannot_jump_from_unsupported_to_supported_without_evidence() {
        val ledger = KbCoverageLedger(
            listOf(KbClause("C1", "What is EPBG?", KbClauseIntent.EXPLANATION))
        )

        assertFailsWith<IllegalArgumentException> {
            ledger.markSupported("C1", emptyList())
        }
    }

    @Test
    fun query_plan_preserves_original_text() {
        val original = "PAC kya hai aur iski validity kya hai?"
        val request = KbTurnRequest(original, "generic:procurement", "session-1", emptyList(), false)

        assertEquals(original, request.originalQuery)
    }
}
