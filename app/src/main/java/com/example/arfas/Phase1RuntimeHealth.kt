package com.example.arfas

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class Phase1HealthState { READY, DEGRADED, UNAVAILABLE, UNKNOWN }

data class Phase1RuntimeHealthSnapshot(
    val local: Phase1HealthState,
    val cloud: Phase1HealthState,
    val lastProvider: AndroBaselineRuntime.Provider,
    val lastTurnMs: Long,
    val localFailures: Int,
    val cloudFailures: Int,
    val note: String = ""
) {
    fun compactLabel(): String = "L:${local.name.first()} C:${cloud.name.first()} ${lastProvider.name} ${lastTurnMs}ms"
}

/**
 * Small Phase-1 health ledger. It does not route turns; it only observes the
 * existing local/cloud paths and exposes deterministic diagnostics to UI/logs.
 */
class Phase1RuntimeHealth {
    private val localState = AtomicReference(Phase1HealthState.UNKNOWN)
    private val cloudState = AtomicReference(Phase1HealthState.UNKNOWN)
    private val lastProvider = AtomicReference(AndroBaselineRuntime.Provider.NONE)
    private val lastTurnMs = AtomicLong(0L)
    private val localFailures = AtomicInteger(0)
    private val cloudFailures = AtomicInteger(0)
    private val note = AtomicReference("")

    fun setLocalReady(ready: Boolean) {
        localState.set(if (ready) Phase1HealthState.READY else Phase1HealthState.UNAVAILABLE)
    }

    fun observeCloudStatus(status: GroqProviderStatus) {
        cloudState.set(
            when (status) {
                GroqProviderStatus.READY -> Phase1HealthState.READY
                GroqProviderStatus.NO_KEY, GroqProviderStatus.AUTH_FAILED -> Phase1HealthState.UNAVAILABLE
                GroqProviderStatus.RATE_LIMITED, GroqProviderStatus.NETWORK_ERROR -> Phase1HealthState.DEGRADED
                GroqProviderStatus.UNKNOWN -> Phase1HealthState.UNKNOWN
            }
        )
    }

    fun localFailure(reason: String) {
        localFailures.incrementAndGet()
        localState.set(Phase1HealthState.DEGRADED)
        note.set("local:$reason")
        log()
    }


    fun localAnswerRejected(reason: String) {
        // A rejected answer is not an engine/generation failure, so do not increment
        // the backend failure counter. Keep rejection visible through the note only.
        // A rejected answer is not the same as an unhealthy engine. Keep the Local
        // readiness state intact so diagnostics do not report the backend as degraded
        // when Gemma generated successfully but an acceptance/grounding gate vetoed it.
        if (localState.get() == Phase1HealthState.UNKNOWN) {
            localState.set(Phase1HealthState.READY)
        }
        note.set("local_rejected:$reason")
        log()
    }

    fun cloudFailure(reason: String, status: GroqProviderStatus) {
        cloudFailures.incrementAndGet()
        observeCloudStatus(status)
        if (cloudState.get() == Phase1HealthState.READY) cloudState.set(Phase1HealthState.DEGRADED)
        note.set("cloud:$reason")
        log()
    }

    fun turnAccepted(provider: AndroBaselineRuntime.Provider, elapsedMs: Long, cloudStatus: GroqProviderStatus) {
        lastProvider.set(provider)
        lastTurnMs.set(elapsedMs)
        when (provider) {
            AndroBaselineRuntime.Provider.LOCAL -> localState.set(Phase1HealthState.READY)
            AndroBaselineRuntime.Provider.CLOUD -> observeCloudStatus(cloudStatus)
            else -> Unit
        }
        note.set("")
        log()
    }

    fun snapshot(): Phase1RuntimeHealthSnapshot = Phase1RuntimeHealthSnapshot(
        local = localState.get(),
        cloud = cloudState.get(),
        lastProvider = lastProvider.get(),
        lastTurnMs = lastTurnMs.get(),
        localFailures = localFailures.get(),
        cloudFailures = cloudFailures.get(),
        note = note.get()
    )

    private fun log() {
        Log.i("Phase1RuntimeHealth", "RUNTIME_HEALTH ${snapshot()}")
    }
}
