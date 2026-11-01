package com.example.arfas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KbRuntimeRoutingTest {
    @Test
    fun rollout_flag_defaults_to_legacy() {
        assertFalse(KbFeatureFlags.orchestratorV2Enabled)
    }

    @Test
    fun enabled_route_invokes_only_v2() = runTest {
        var v2Calls = 0
        var legacyCalls = 0
        val router = KbSelectedTurnRouter(
            isV2Enabled = { true },
            v2 = {
                v2Calls += 1
                "v2"
            },
            legacy = {
                legacyCalls += 1
                "legacy"
            }
        )

        assertEquals("v2", router.answer())
        assertEquals(1, v2Calls)
        assertEquals(0, legacyCalls)
    }

    @Test
    fun disabled_route_invokes_only_legacy() = runTest {
        var v2Calls = 0
        var legacyCalls = 0
        val router = KbSelectedTurnRouter(
            isV2Enabled = { false },
            v2 = {
                v2Calls += 1
                "v2"
            },
            legacy = {
                legacyCalls += 1
                "legacy"
            }
        )

        assertEquals("legacy", router.answer())
        assertEquals(0, v2Calls)
        assertEquals(1, legacyCalls)
    }

    @Test
    fun enabled_without_wired_v2_deterministically_uses_legacy() = runTest {
        var legacyCalls = 0
        val router = KbSelectedTurnRouter(
            isV2Enabled = { true },
            v2 = null,
            legacy = {
                legacyCalls += 1
                "legacy"
            }
        )

        assertEquals("legacy", router.answer())
        assertEquals(1, legacyCalls)
    }

    @Test
    fun recoverable_v2_exception_rolls_back_once_to_legacy() = runTest {
        var v2Calls = 0
        var legacyCalls = 0
        var failures = 0
        val router = KbSelectedTurnRouter(
            isV2Enabled = { true },
            v2 = {
                v2Calls += 1
                error("v2 unavailable")
            },
            legacy = {
                legacyCalls += 1
                "legacy"
            },
            onV2Failure = { failures += 1 }
        )

        assertEquals("legacy", router.answer())
        assertEquals(1, v2Calls)
        assertEquals(1, legacyCalls)
        assertEquals(1, failures)
    }

    @Test
    fun cancellation_never_starts_legacy_or_failure_callback() = runTest {
        var legacyCalls = 0
        var failures = 0
        val router = KbSelectedTurnRouter(
            isV2Enabled = { true },
            v2 = { throw CancellationException("cancelled") },
            legacy = {
                legacyCalls += 1
                "legacy"
            },
            onV2Failure = { failures += 1 }
        )

        assertFailsWith<CancellationException> { router.answer() }
        assertEquals(0, legacyCalls)
        assertEquals(0, failures)
    }
}
