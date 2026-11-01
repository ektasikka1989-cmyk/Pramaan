package com.example.arfas

/**
 * Small, domain-neutral guard for reconciling a user's visual claim with a
 * freshly accepted frame observation.  It does not route a capability or
 * diagnose a fault; it only prevents a reported visual claim from silently
 * becoming a confirmed frame fact when the two descriptions have no meaningful
 * semantic overlap.
 */
object VisualEvidenceReconciler {
    private val visualClaimCues = listOf(
        "i see", "i can see", "shows", "showing", "screen", "display", "monitor",
        "visible", "looks like", "appears", "there is", "there's", "in the image",
        "in the frame", "dikh", "nazar aa", "screen par"
    )

    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "can", "do", "for", "from",
        "i", "in", "is", "it", "me", "my", "of", "on", "or", "see", "show", "the",
        "this", "to", "what", "with", "you", "your", "visible", "current", "frame",
        "image", "there", "looks", "like", "appears",
        "dikh", "raha", "rahi", "hai", "par"
    )

    fun isPotentialConflict(userText: String, visualObservation: String): Boolean {
        val user = normalize(userText)
        val visual = normalize(visualObservation)
        if (user.isBlank() || visual.isBlank()) return false
        if (hasModelConflictMarker(visual)) return true
        if (!hasVisualClaimCue(user)) return false

        // Keep the first asserted visual clause separate from later narrative
        // context (for example, "I see a blue screen, but Android Studio was
        // open earlier"). Otherwise the later context could mask the conflict.
        val claimPortion = user.split(
            Regex("\\b(where|but|however|while|although)\\b")
        ).firstOrNull().orEmpty()
        val userTokens = meaningfulTokens(claimPortion)
        val visualTokens = meaningfulTokens(visual)
        if (userTokens.size < 2 || visualTokens.isEmpty()) return false

        // A claim that shares no, or only one, meaningful token with the current
        // frame is a candidate conflict. It is deliberately conservative: the
        // caller asks for verification instead of declaring either side false.
        val overlap = userTokens.intersect(visualTokens).size
        return overlap <= 1
    }

    fun formatConflict(userText: String, visualObservation: String): String = buildString {
        append("USER-REPORTED VISUAL CLAIM (not verified): ")
        append(userText.trim().take(500))
        append(" | CURRENT FRAME OBSERVATION: ")
        append(visualObservation.trim().take(700))
        append(" | STATUS: CONFLICT_REQUIRES_VERIFICATION")
    }

    private fun hasVisualClaimCue(normalized: String): Boolean =
        visualClaimCues.any { normalized.contains(it) }

    private fun hasModelConflictMarker(normalized: String): Boolean {
        val marker = "contradictions"
        val tail = normalized.substringAfter(marker, "").trim()
        val first = tail.split(' ').firstOrNull().orEmpty()
        return tail.isNotBlank() && first !in setOf("none", "no", "unknown")
    }

    private fun meaningfulTokens(normalized: String): Set<String> = normalized
        .split(' ')
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 3 && it !in stopWords }
        .toSet()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
