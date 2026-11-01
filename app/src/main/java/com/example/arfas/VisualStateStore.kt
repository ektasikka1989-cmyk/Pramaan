package com.example.arfas

import android.util.Log

/**
 * Lightweight visual state store per handoff §26–§27.
 *
 * The handoff requires maintaining awareness of visual changes over time so
 * the assistant can say "the display changed from 30 to 29 after you pressed
 * the button." This cannot be achieved by treating every frame as an
 * unrelated image.
 *
 * Retains last 5 meaningful observations (text descriptions from the cloud
 * vision model). Does NOT store raw bitmaps — only the factual observation
 * text returned by the vision capability.
 *
 * The visual model is the assistant's EYES, not its conversational personality.
 * It reports observable facts; the main LLM reasons over them (handoff §25).
 */
class VisualStateStore {
    companion object {
        private const val TAG = "VisualStateStore"
        private const val MAX_OBSERVATIONS = 5
    }

    data class VisualObservation(
        val timestampMs: Long = System.currentTimeMillis(),
        val description: String,
        val turnNumber: Int,
        val source: String = "vision_observer"
    )

    private val observations = ArrayDeque<VisualObservation>()
    private val lock = Any()

    /**
     * Records a new visual observation from the vision capability.
     * Only records non-empty, meaningful descriptions.
     */
    fun record(description: String, turnNumber: Int) {
        if (description.isBlank() || description == "NO_USEFUL_VISUAL_EVIDENCE") return
        synchronized(lock) {
            observations.addLast(
                VisualObservation(
                    description = description.trim(),
                    turnNumber = turnNumber
                )
            )
            while (observations.size > MAX_OBSERVATIONS) observations.removeFirst()
            Log.d(TAG, "VISUAL_STATE_RECORDED turn=$turnNumber desc='${description.take(60)}'")
        }
    }

    fun latest(): VisualObservation? = synchronized(lock) { observations.lastOrNull() }

    fun previous(): VisualObservation? = synchronized(lock) {
        if (observations.size >= 2) observations[observations.size - 2] else null
    }

    fun all(): List<VisualObservation> = synchronized(lock) { observations.toList() }

    fun clear() = synchronized(lock) { observations.clear() }

    /**
     * Builds a change-aware visual context string for the main LLM.
     *
     * When the visual state changed since the last observation, the LLM context
     * explicitly notes the previous and current state so the assistant can
     * naturally say "the display changed from X to Y."
     *
     * If there is no previous observation, provides just the current one.
     */
    fun buildContextForLLM(currentObservation: String): String {
        if (currentObservation.isBlank() ||
            currentObservation == "NO_USEFUL_VISUAL_EVIDENCE") {
            return "<internal_visual_context>\n" +
                "A current camera frame exists, but no reliable visual observation " +
                "is available for this turn. Do not claim to see specific visual details.\n" +
                "</internal_visual_context>"
        }

        val prev = previous()
        return buildString {
            append("<internal_visual_context>\n")
            if (prev != null && prev.description.isNotBlank()) {
                append("Previous visual state (turn ${prev.turnNumber}): ${prev.description}\n")
                append("Current visual state: $currentObservation\n")
                val prevNorm = prev.description.lowercase().trim()
                val currNorm = currentObservation.lowercase().trim()
                if (prevNorm != currNorm) {
                    append("Note: The visual state has changed since the previous observation.\n")
                    Log.d(TAG, "VISUAL_CHANGE_DETECTED")
                }
            } else {
                append("Current camera-frame observation from the vision capability:\n")
                append(currentObservation)
                append("\n")
            }
            append("</internal_visual_context>")
        }
    }


    /** Latest bounded state for ordinary local turns in Video mode. */
    fun buildPriorContext(maxAgeMs: Long = 15_000L): String = synchronized(lock) {
        val latest = observations.lastOrNull() ?: return@synchronized ""
        if (System.currentTimeMillis() - latest.timestampMs > maxAgeMs) return@synchronized ""
        observations.takeLast(3).joinToString("\n") { obs ->
            "Visual state at turn ${obs.turnNumber}: ${obs.description.take(1200)}"
        }
    }

    fun hasFreshState(maxAgeMs: Long = 15_000L): Boolean = synchronized(lock) {
        val latest = observations.lastOrNull() ?: return@synchronized false
        System.currentTimeMillis() - latest.timestampMs <= maxAgeMs
    }

    fun hasChangedFrom(newDescription: String): Boolean {
        val prev = latest() ?: return true
        val prevNorm = prev.description.lowercase().replace(Regex("\\s+"), " ").trim()
        val currNorm = newDescription.lowercase().replace(Regex("\\s+"), " ").trim()
        return prevNorm != currNorm
    }
}
