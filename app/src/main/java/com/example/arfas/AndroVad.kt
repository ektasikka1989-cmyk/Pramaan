package com.example.arfas

/**
 * Phase 23 compatibility stub.
 *
 * Acoustic VAD is intentionally disabled across FixSight. Voice input is explicit
 * tap-to-record / tap-to-stop, and interruption is an explicit UI action. Keeping
 * this no-op class prevents an older call site from re-enabling acoustic cancellation
 * if a stale source file is merged accidentally.
 */
@Deprecated("Acoustic VAD is disabled in Phase 23")
class AndroVad(
    private val threshold: Float = 1f,
    private val hangoverFrames: Int = 0,
    private val activationFrames: Int = Int.MAX_VALUE
) {
    fun process(samples: FloatArray): Boolean = false
    fun reset() = Unit
}
