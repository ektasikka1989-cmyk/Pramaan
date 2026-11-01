package com.example.arfas

import android.util.Log
import java.util.Locale
import java.util.ArrayDeque

/**
 * Bounded, provider-neutral state for long (30-40+ turn) live troubleshooting.
 *
 * This is intentionally isolated from KB/RAG, general chat and the resident LLM
 * conversation. It stores only compact accepted troubleshooting facts/actions so
 * Local <-> Cloud switching never creates a second investigation.
 */
class TroubleshootingSessionState {
    companion object {
        private const val TAG = "TroubleshootingState"
        private const val MAX_TURNS = 48
        private const val MAX_FACTS = 18
        private const val MAX_ACTIONS = 14
        private const val MAX_AR_TARGETS = 6
    }

    data class Turn(
        val number: Int,
        val user: String,
        val answer: String,
        val evidence: String,
        val uncertainty: String,
        val claim: String,
        val measurement: String,
        val next: String,
        val provider: String,
        val likelyCause: String = "",
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class ArTarget(
        val label: String,
        val placement: String,
        val turnNumber: Int,
        val timestampMs: Long = System.currentTimeMillis()
    )

    data class ResolutionSnapshot(
        val userAcknowledgement: String,
        val resolutionAction: String,
        val acknowledgedAtMs: Long,
        val finalized: Boolean
    )

    private val lock = Any()
    private val turns = ArrayDeque<Turn>()
    private val arTargets = ArrayDeque<ArTarget>()
    private var turnCounter = 0
    private var unresolvedStreak = 0
    private var localNoProgressStreak = 0
    private var resolutionSnapshot: ResolutionSnapshot? = null

    /**
     * Cloud review is useful only after local recovery has genuinely stalled.
     * A single cautious REVIEW/CLARIFY is normal technician behaviour and must
     * not trigger network escalation by itself.
     */
    fun shouldEscalateAcceptedLocal(draft: TroubleshootingCameraReview.Draft): Boolean = synchronized(lock) {
        val unresolvedNow = isUnresolved(draft)
        if (!unresolvedNow) return@synchronized false

        // Cloud review is for LOCAL STALL, not merely for an unresolved case.
        // A previous cloud turn must never make the next local turn auto-escalate.
        val previous = turns.lastOrNull() ?: return@synchronized false
        if (!previous.provider.equals("LOCAL", ignoreCase = true)) return@synchronized false
        if (!isUnresolved(previous)) return@synchronized false

        // New current-frame evidence means the investigation is progressing even if
        // the answer is still REVIEW/CLARIFY. Keep that progress local.
        if (evidenceProgressed(previous.evidence, draft.evidence)) return@synchronized false

        // Require one already-recorded local no-progress turn. This makes cloud a
        // response to repeated local stagnation, not to two normal diagnostic steps.
        localNoProgressStreak >= 1
    }

    fun recordAccepted(
        user: String,
        draft: TroubleshootingCameraReview.Draft,
        provider: String
    ) = synchronized(lock) {
        turnCounter += 1
        val unresolvedNow = isUnresolved(draft)
        unresolvedStreak = if (unresolvedNow) unresolvedStreak + 1 else 0

        val previous = turns.lastOrNull()
        localNoProgressStreak = when {
            !provider.equals("LOCAL", ignoreCase = true) -> 0
            !unresolvedNow -> 0
            previous == null -> 0
            !previous.provider.equals("LOCAL", ignoreCase = true) -> 0
            !isUnresolved(previous) -> 0
            evidenceProgressed(previous.evidence, draft.evidence) -> 0
            else -> localNoProgressStreak + 1
        }

        turns.addLast(
            Turn(
                number = turnCounter,
                user = user.trim().take(700),
                answer = draft.answer.trim().take(900),
                evidence = draft.evidence.trim().take(700),
                uncertainty = draft.uncertainty.trim().take(260),
                claim = draft.claim,
                measurement = draft.measurement,
                next = draft.next,
                provider = provider,
                likelyCause = draft.likelyCause.replace(Regex("\\s+"), " ").trim().take(240)
            )
        )
        while (turns.size > MAX_TURNS) turns.removeFirst()
        Log.i(TAG, "TURN_RECORDED n=$turnCounter provider=$provider next=${draft.next} unresolved=$unresolvedStreak localNoProgress=$localNoProgressStreak")
    }

    private fun isUnresolved(draft: TroubleshootingCameraReview.Draft): Boolean =
        draft.next == "REVIEW" ||
            draft.next == "CLARIFY" ||
            draft.evidence.equals("NO_USEFUL_VISUAL_EVIDENCE", ignoreCase = true)

    private fun isUnresolved(turn: Turn): Boolean =
        turn.next == "REVIEW" ||
            turn.next == "CLARIFY" ||
            turn.evidence.equals("NO_USEFUL_VISUAL_EVIDENCE", ignoreCase = true)

    private fun evidenceProgressed(previous: String, current: String): Boolean {
        val a = normalizeEvidence(previous)
        val b = normalizeEvidence(current)
        if (a == b) return false
        if (a == "no_useful_visual_evidence" && b != a) return true
        if (b == "no_useful_visual_evidence") return false
        if (a.isBlank() || b.isBlank()) return a != b

        val aTokens = a.split(' ').filter { it.length >= 3 }.toSet()
        val bTokens = b.split(' ').filter { it.length >= 3 }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return a != b
        val union = aTokens union bTokens
        val overlap = aTokens intersect bTokens
        val similarity = overlap.size.toDouble() / union.size.toDouble()
        return similarity < 0.72
    }

    private fun normalizeEvidence(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    fun hasAcceptedTurns(): Boolean = synchronized(lock) { turns.isNotEmpty() }

    /** The last [n] steps given to the user (for the repeat check). */
    fun recentSteps(n: Int = 3): List<String> = synchronized(lock) {
        turns.toList().takeLast(n).map { it.answer }.filter { it.isNotBlank() }
    }

    /** The latest stated working diagnosis without its "ruled out" part, or "". */
    fun workingTheory(): String = synchronized(lock) {
        val cause = turns.toList().asReversed().firstOrNull { it.likelyCause.isNotBlank() }?.likelyCause.orEmpty()
        cause.split(Regex("(?i)[;,]?\\s*ruled out")).first().trim().trimEnd(';', ',', '.', '-').trim()
    }

    /** Cause the latest accepted turn eliminated ("ruled out: X"), or null. */
    fun latestRuledOut(): String? = synchronized(lock) {
        val cause = turns.lastOrNull()?.likelyCause.orEmpty()
        Regex("(?i)ruled out\\s*:?\\s*([^;)]+)").find(cause)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.length > 2 }
    }

    /** The step requested in the previous turn and the user's reply in the latest turn. */
    fun latestStepAndReply(): Pair<String, String>? = synchronized(lock) {
        val all = turns.toList()
        if (all.size < 2) return@synchronized null
        val step = all[all.size - 2].answer.trim()
        val reply = all.last().user.trim()
        if (step.isBlank() || reply.isBlank()) null else step to reply
    }

    fun acknowledgeResolved(userAcknowledgement: String): ResolutionSnapshot = synchronized(lock) {
        val lastAction = turns.toList().asReversed()
            .firstOrNull { it.next == "CHECK" && it.answer.isNotBlank() }
            ?.answer
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(700)
            .orEmpty()
        val snapshot = ResolutionSnapshot(
            userAcknowledgement = userAcknowledgement.trim().take(500),
            resolutionAction = lastAction,
            acknowledgedAtMs = System.currentTimeMillis(),
            finalized = false
        )
        resolutionSnapshot = snapshot
        Log.i(TAG, "RESOLUTION_ACKNOWLEDGED action=${lastAction.take(120)}")
        snapshot
    }

    fun finalizeResolution(): ResolutionSnapshot? = synchronized(lock) {
        val current = resolutionSnapshot ?: return@synchronized null
        val finalized = current.copy(finalized = true)
        resolutionSnapshot = finalized
        Log.i(TAG, "RESOLUTION_FINALIZED action=${finalized.resolutionAction.take(120)}")
        finalized
    }

    fun continueAfterResolutionPrompt() = synchronized(lock) {
        resolutionSnapshot = null
        unresolvedStreak = 0
        localNoProgressStreak = 0
        Log.i(TAG, "RESOLUTION_PROMPT_CONTINUE")
    }

    fun pendingResolution(): ResolutionSnapshot? = synchronized(lock) { resolutionSnapshot }

    fun recordArGuidance(target: String, placement: String) = synchronized(lock) {
        arTargets.addLast(ArTarget(target.trim().take(120), placement, turnCounter + 1))
        while (arTargets.size > MAX_AR_TARGETS) arTargets.removeFirst()
    }

    fun renderForPrompt(maxChars: Int = 3_200): String = synchronized(lock) {
        if (turns.isEmpty() && arTargets.isEmpty()) return@synchronized ""

        val allTurns = turns.toList()
        val recent = allTurns.takeLast(6)
        val visualFacts = allTurns.asReversed()
            .filter { it.evidence.isNotBlank() && !it.evidence.equals("NO_USEFUL_VISUAL_EVIDENCE", true) }
            .map { it.evidence.replace(Regex("\\s+"), " ").trim() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_FACTS)
            .reversed()
        // Every step already given (checks AND camera/clarification requests), each paired with
        // what the user said next, so the model sees results instead of re-asking.
        val actions = allTurns.mapIndexedNotNull { index, turn ->
            val step = turn.answer.replace(Regex("\\s+"), " ").trim()
            if (step.isBlank()) return@mapIndexedNotNull null
            val reply = allTurns.getOrNull(index + 1)?.user?.replace(Regex("\\s+"), " ")?.trim()
            "T${turn.number} step: ${step.take(200)} -> user then said: ${reply?.take(140) ?: "(no reply yet; this is the latest step)"}"
        }.takeLast(MAX_ACTIONS)
        val theories = allTurns.filter { it.likelyCause.isNotBlank() }
            .map { "T${it.number}: ${it.likelyCause}" }
            .takeLast(6)
        val latest = allTurns.lastOrNull()
        val openGoal = latest?.takeIf { isUnresolved(it) }?.let {
            buildString {
                if (it.uncertainty.isNotBlank() && !it.uncertainty.trimEnd('.').equals("none", true)) {
                    append("Resolve: ${it.uncertainty.replace(Regex("\\s+"), " ").trim().take(220)}")
                }
                if (it.answer.isNotBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append("Last requested step: ${it.answer.replace(Regex("\\s+"), " ").trim().take(260)}")
                }
            }
        }.orEmpty()

        val body = buildString {
            appendLine("<troubleshooting_case_state>")
            appendLine("This is the authoritative accepted case state. Do not treat rejected model drafts or raw Notebook prose as facts.")
            appendLine("Accepted turns: $turnCounter. Unresolved streak: $unresolvedStreak. Local no-progress streak: $localNoProgressStreak.")
            if (openGoal.isNotBlank()) appendLine("Current unresolved goal: $openGoal")
            if (visualFacts.isNotEmpty()) {
                appendLine("Accepted visual facts (newer evidence supersedes older conflicting evidence):")
                visualFacts.takeLast(8).forEach { appendLine("- ${it.take(320)}") }
            }
            if (theories.isNotEmpty()) {
                appendLine("Diagnosis so far (latest last; keep it unless a result changes it):")
                theories.forEach { appendLine("- ${it.take(260)}") }
            }
            if (actions.isNotEmpty()) {
                appendLine("Steps already given and the user's replies; do not give the same step again:")
                actions.takeLast(8).forEach { appendLine("- ${it.take(380)}") }
            }
            if (recent.isNotEmpty()) {
                appendLine("Recent accepted exchanges for follow-up/pronoun continuity:")
                recent.forEach {
                    appendLine("- T${it.number} ${it.provider} USER=${it.user.replace(Regex("\\s+"), " ").trim().take(180)}")
                    appendLine("  RESULT=${it.answer.replace(Regex("\\s+"), " ").trim().take(220)}")
                    appendLine("  STATE=evidence:${it.evidence.replace(Regex("\\s+"), " ").trim().take(220)} | uncertainty:${it.uncertainty.replace(Regex("\\s+"), " ").trim().take(140)} | next:${it.next}")
                }
            }
            if (arTargets.isNotEmpty()) {
                appendLine("Recent AR-guided targets (re-localize from the current frame; never reuse old screen coordinates):")
                arTargets.toList().takeLast(MAX_AR_TARGETS).forEach {
                    appendLine("- ${it.label} (${it.placement})")
                }
            }
            appendLine("</troubleshooting_case_state>")
        }

        // Keep the opening contract and latest state visible. If the state grows beyond
        // the bound, prefer the most recent accepted exchanges rather than raw history.
        if (body.length <= maxChars) body else buildString {
            appendLine("<troubleshooting_case_state>")
            appendLine("Accepted turns: $turnCounter. Unresolved streak: $unresolvedStreak. Local no-progress streak: $localNoProgressStreak.")
            if (openGoal.isNotBlank()) appendLine("Current unresolved goal: $openGoal")
            theories.lastOrNull()?.let { appendLine("Current diagnosis: ${it.take(240)}") }
            if (actions.isNotEmpty()) {
                appendLine("Steps already given and the user's replies; do not repeat:")
                actions.takeLast(5).forEach { appendLine("- ${it.take(300)}") }
            }
            appendLine("Recent accepted exchanges:")
            recent.takeLast(3).forEach {
                appendLine("- T${it.number} U=${it.user.replace(Regex("\\s+"), " ").trim().take(150)}")
                appendLine("  A=${it.answer.replace(Regex("\\s+"), " ").trim().take(190)}")
                appendLine("  V=${it.evidence.replace(Regex("\\s+"), " ").trim().take(190)} | ?=${it.uncertainty.replace(Regex("\\s+"), " ").trim().take(120)} | next=${it.next}")
            }
            appendLine("</troubleshooting_case_state>")
        }.take(maxChars)
    }

    fun clear() = synchronized(lock) {
        turns.clear()
        arTargets.clear()
        turnCounter = 0
        unresolvedStreak = 0
        localNoProgressStreak = 0
        resolutionSnapshot = null
    }
}
