package com.example.arfas

import java.util.Locale

/**
 * Acceptance and recovery for KB-off camera troubleshooting only.
 * This checks a provider's contract, not the truth of its image recognition.
 * Providers inspect the same frame independently; a rejected local description
 * is never supplied to Cloud as fact or promoted into the notebook.
 */
object TroubleshootingCameraReview {
    enum class Provider { LOCAL, CLOUD, NONE }
    data class Result(
        val answer: String,
        val observation: String,
        val provider: Provider,
        val draft: Draft? = null
    )
    data class Draft(
        val evidence: String,
        val uncertainty: String,
        val claim: String,
        val measurement: String,
        val next: String,
        val answer: String,
        /** Working diagnosis with its status, e.g. "loose power cable (suspected)"; "" when not given. */
        val likelyCause: String = ""
    )

    private val fields = listOf(
        "VISUAL_EVIDENCE", "UNCERTAINTY", "CLAIM_CHECK", "MEASUREMENT_CHECK", "NEXT_STEP", "ANSWER"
    )
    /** Optional seventh field; drafts without it (older behaviour) still parse. */
    private const val CAUSE_FIELD = "LIKELY_CAUSE"
    /** Word overlap at which a new step counts as a repeat of an earlier one. */
    private const val REPEAT_SIMILARITY = 0.7
    private val headings = Regex(
        "(?im)(?:^|[\\r\\n|;,{])\\s*(?:[-*]\\s*)?[\"']?\\*{0,2}" +
            "(VISUAL[ _-]?EVIDENCE|UNCERTAINTY|CLAIM[ _-]?CHECK|MEASUREMENT[ _-]?CHECK|LIKELY[ _-]?CAUSE|NEXT[ _-]?STEP|ANSWER)" +
            "\\*{0,2}[\"']?\\s*(?::|=)\\s*\\*{0,2}[ \t]*"
    )
    private val percentage = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:%|percent\\b|per cent\\b)")
    // Output failure phrases, not domain keywords or a topic-routing classifier.
    private val refusal = Regex(
        "(?i)\\b(?:beyond|outside)\\s+(?:the\\s+)?scope\\b|" +
            "\\b(?:cannot|can't|unable to)\\s+(?:help|assist|handle|troubleshoot|answer)\\b|" +
            "\\b(?:not equipped|not designed)\\s+to\\s+(?:help|assist|handle|troubleshoot)\\b"
    )

    fun prompt(user: String, recent: String, notebook: String, sessionState: String = ""): String = buildString {
        appendLine("<current_user_request>")
        appendLine(user.trim().take(3_000))
        appendLine("</current_user_request>")
        appendLine("You are ArogyaDoot, a troubleshooter: you work through any problem with the user as a conversation: a health concern or symptoms, a device, machine or vehicle that is not working, software or a phone app, a process or anything else. Look carefully at the attached CURRENT camera frame before anything else.")
        appendLine("SEE: in VISUAL_EVIDENCE first name what is shown (a device, machine or vehicle part, a screen, a document or label, a medicine, or a visible body sign such as a rash, wound or swelling), then list the visible details that matter for the problem: indicator lights and their colours, readable screen text, error codes or readings, labels, switch or knob positions, cables and connections, leaks, burns, cracks or damage, or the colour, size and location of a visible sign. Copy text exactly as shown; if it is unreadable, say so. Never state a diagnosis from a picture alone.")
        appendLine("Treat the user's complaint as a SYMPTOM, not as a visual claim. Use CLAIM_CHECK=NOT_APPLICABLE unless the user asserted a specific current visible state. Use CONFLICT only when this frame clearly contradicts that claim. Use NOT_VISIBLE only when a needed detail is outside the frame or unreadable.")
        appendLine("Do not invent measurements, labels, error codes, readings or causes. A number is supported only when its subject/label/unit are visibly readable.")
        appendLine("METHOD (keep following it across turns, using the case state below):")
        appendLine("1. From the symptom and what you see, decide the most likely cause, preferring causes that are common and quick and safe to check.")
        appendLine("2. Give ONE step that tests that cause, and say in a few words what the result will tell us.")
        appendLine("3. If the user's message reports the result of the last requested step, use that result first: confirm the cause, or rule it out and move to the next most likely cause.")
        appendLine("4. When a cause is confirmed or the evidence clearly points to it, state the fault and give the fix. If the safe checks are used up, say so and say who should take it further (for a health problem the ANM, PHC or a doctor; for equipment a qualified technician or the supplier).")
        appendLine("5. Never repeat a step already requested in the case state unless the user says they could not do it. If a better camera view was already requested, work with what is visible now instead of asking again.")
        appendLine("Ask about the symptom only when the frame cannot show it (for example what happens when it is switched on). Do not ask what the user has already tried.")
        appendLine("Safety: for a health problem, check for danger signs first; if there is one, say to call 108 (102 for a pregnant woman or newborn) or go to the nearest PHC or hospital now, before any other step. For mains electricity, gas, fuel, high pressure or hot parts, a step must be safe for a non-expert (switch off and isolate first) or recommend a qualified person.")
        appendLine("Never ask the user to describe a fact already present in VISUAL_EVIDENCE.")
        appendLine("Return exactly these seven fields, once each:")
        appendLine("VISUAL_EVIDENCE: what is shown and the relevant visible details, or NO_USEFUL_VISUAL_EVIDENCE")
        appendLine("UNCERTAINTY: one important unknown, or none")
        appendLine("CLAIM_CHECK: MATCH | CONFLICT | NOT_VISIBLE | NOT_APPLICABLE")
        appendLine("MEASUREMENT_CHECK: NONE | SUPPORTED | UNSUPPORTED | INSTRUCTION")
        appendLine("LIKELY_CAUSE: the current most likely cause followed by (suspected) or (confirmed); add 'ruled out: <cause>' when the latest result eliminated one; or unknown")
        appendLine("NEXT_STEP: CHECK | CLARIFY | REVIEW")
        appendLine("ANSWER: 1-3 short sentences, the only text the user sees and hears: the step (or the fault and its fix) and what it will tell us")
        if (sessionState.isNotBlank()) appendLine(sessionState.takeLast(3_200))
        // `recent` and `notebook` remain in the function signature for source/API
        // compatibility with older harnesses, but are intentionally not injected.
        // TroubleshootingSessionState is the sole accepted historical context.
        @Suppress("UNUSED_VARIABLE") val ignoredLegacyContext = recent.length + notebook.length
    }

    private fun canonicalField(raw: String): String = raw
        .uppercase(Locale.ROOT)
        .replace('-', '_')
        .replace(' ', '_')

    private fun cleanValue(raw: String): String = raw.trim()
        .trim('`', '*', ' ', '\t', '\n', '\r', ',', '}', '{')
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()

    private fun normalizeClaim(raw: String): String? {
        val v = cleanValue(raw).uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return when (v) {
            "MATCH", "MATCHES", "SUPPORTED", "AGREES" -> "MATCH"
            "CONFLICT", "CONTRADICTS", "CONTRADICTION" -> "CONFLICT"
            "NOT_VISIBLE", "NOT_SEEN", "UNREADABLE" -> "NOT_VISIBLE"
            "NOT_APPLICABLE", "N/A", "NA", "NONE" -> "NOT_APPLICABLE"
            else -> null
        }
    }

    private fun normalizeMeasurement(raw: String): String? {
        val v = cleanValue(raw).uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return when (v) {
            "NONE", "N/A", "NA", "NOT_APPLICABLE" -> "NONE"
            "SUPPORTED", "VISIBLE", "READABLE" -> "SUPPORTED"
            "UNSUPPORTED", "NOT_SUPPORTED", "UNVERIFIED" -> "UNSUPPORTED"
            "INSTRUCTION", "INSTRUCTIONAL" -> "INSTRUCTION"
            else -> null
        }
    }

    private fun normalizeNext(raw: String): String? {
        val v = cleanValue(raw).uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return when (v) {
            "CHECK", "ACTION", "INSPECT", "TEST" -> "CHECK"
            "CLARIFY", "QUESTION", "ASK", "REACQUIRE", "REACQUISITION" -> "CLARIFY"
            "REVIEW", "ESCALATE", "VERIFY" -> "REVIEW"
            else -> null
        }
    }

    fun parse(raw: String): Draft? {
        val text = raw
            .replace("\r\n", "\n")
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val matches = headings.findAll(text).toList()
        if (matches.size != fields.size && matches.size != fields.size + 1) return null

        // Allow harmless model preambles / JSON opening punctuation, but never accept
        // a second unstructured answer before the contract itself.
        val prefix = text.substring(0, matches.first().range.first)
            .replace(Regex("(?i)^(?:here(?:'s| is)?|response|output|result)\\s*:?\\s*"), "")
            .trim(' ', '\t', '\n', '\r', '{', '[', ',', ':')
        if (prefix.isNotBlank()) return null

        // Require all six semantic fields exactly once. Formatting may vary (JSON,
        // markdown, pipes, semicolons, '='), but the semantic contract does not.
        val names = matches.map { canonicalField(it.groupValues[1]) }
        if (names.toSet() - CAUSE_FIELD != fields.toSet() || names.size != names.distinct().size) return null
        val values = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val sectionEnd = matches.getOrNull(index + 1)?.range?.first ?: text.length
            values[canonicalField(match.groupValues[1])] =
                cleanValue(text.substring(match.range.last + 1, sectionEnd))
        }
        if (fields.any { values[it].isNullOrBlank() }) return null

        val claim = normalizeClaim(values.getValue("CLAIM_CHECK")) ?: return null
        val measurement = normalizeMeasurement(values.getValue("MEASUREMENT_CHECK")) ?: return null
        val next = normalizeNext(values.getValue("NEXT_STEP")) ?: return null
        return Draft(
            evidence = values.getValue("VISUAL_EVIDENCE"),
            uncertainty = values.getValue("UNCERTAINTY"),
            claim = claim,
            measurement = measurement,
            next = next,
            answer = values.getValue("ANSWER"),
            likelyCause = values[CAUSE_FIELD].orEmpty().takeUnless { it.trimEnd('.').equals("unknown", true) }.orEmpty()
        )
    }

    private fun hasEvidence(draft: Draft): Boolean =
        !draft.evidence.equals("NO_USEFUL_VISUAL_EVIDENCE", ignoreCase = true)

    private fun uncertain(draft: Draft): Boolean =
        !draft.uncertainty.trimEnd('.').equals("none", ignoreCase = true)

    private fun disagreement(draft: Draft): Boolean =
        draft.claim == "CONFLICT"

    private fun unbackedPercentage(draft: Draft): Boolean {
        val readings = percentage.findAll(draft.answer).map { it.groupValues[1].replace(',', '.') }.toSet()
        if (readings.isEmpty() || draft.measurement == "INSTRUCTION") return false
        if (draft.measurement != "SUPPORTED") return true
        val visibleReadings = percentage.findAll(draft.evidence).map { it.groupValues[1].replace(',', '.') }.toSet()
        return !visibleReadings.containsAll(readings)
    }

    private fun failure(draft: Draft?): String? = when {
        draft == null -> "invalid_contract"
        draft.answer.isBlank() -> "empty_answer"
        refusal.containsMatchIn(draft.answer) -> "non_answer"
        // UNSUPPORTED and REVIEW are valid conservative outcomes requested by the
        // contract. Reject only if the answer repeats an unsupported numeric claim.
        unbackedPercentage(draft) -> "unbacked_percentage"
        disagreement(draft) && draft.next != "CLARIFY" -> "unresolved_claim"
        // CLARIFY is a semantic next-step state, not a punctuation contract. A valid
        // local answer such as "Please show the left side of the laptop more clearly"
        // must not be escalated to Cloud merely because it does not contain '?'.
        // The parser still requires the six-field contract, and disagreement still
        // requires NEXT_STEP=CLARIFY, so this relaxation cannot admit an unrelated
        // malformed draft.
        !hasEvidence(draft) && draft.next !in setOf("CLARIFY", "REVIEW") -> "missing_visual_evidence"
        else -> null
    }

    private fun reviewReason(draft: Draft?): String? = failure(draft)

    private fun words(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    /** The earlier step this answer repeats (same request in near-identical words), or null. */
    fun repeatedStep(answer: String, previousSteps: List<String>): String? {
        val now = words(answer)
        if (now.size < 3) return null
        return previousSteps.firstOrNull { earlier ->
            val before = words(earlier)
            before.isNotEmpty() &&
                (now intersect before).size.toDouble() / (now union before).size.toDouble() >= REPEAT_SIMILARITY
        }
    }


    private fun observation(draft: Draft): String {
        if (!hasEvidence(draft)) return ""
        return buildString {
            append("FRAME INTERPRETATION (model observation, not independently verified): ")
            append(draft.evidence.take(450))
            if (uncertain(draft)) append(" UNKNOWN: ${draft.uncertainty.take(140)}")
            if (disagreement(draft)) append(" CONTRADICTIONS: user report and current-frame interpretation require clarification (${draft.claim}).")
        }
    }

    suspend fun run(
        user: String,
        recent: String,
        notebook: String,
        local: suspend (String) -> String?,
        cloud: suspend (String) -> String?,
        cloudAvailable: () -> Boolean,
        sanitize: (String) -> String = { it },
        shapeFailure: (String) -> String? = { null },
        event: (String) -> Unit = {},
        sessionState: String = "",
        cloudReviewNeeded: (Draft) -> Boolean = { false },
        /** The last few steps already given in this case; a new answer must not simply repeat one. */
        previousSteps: List<String> = emptyList(),
        /** The user chose Cloud First: the cloud model reads the frame first, the phone is the backup. */
        cloudFirst: Boolean = false
    ): Result {
        val request = prompt(user, recent, notebook, sessionState)
        fun assess(raw: String?): Draft? {
            val parsed = raw?.let { parse(it) } ?: return null
            val cleaned = parsed.copy(
                evidence = sanitize(parsed.evidence).trim(),
                uncertainty = sanitize(parsed.uncertainty).trim(),
                answer = sanitize(parsed.answer).trim()
            )
            if (cleaned.evidence.isBlank() || cleaned.uncertainty.isBlank() ||
                shapeFailure(cleaned.answer) != null) return null
            return cleaned
        }
        // Provider timeout/unavailability and malformed text are different failure modes.
        // Never spend another full VLM pass after a timeout/null result: native LiteRT
        // generation is not cooperatively cancellable enough for that to be a useful
        // latency strategy. A compact local repair is allowed only when Local actually
        // returned text that failed the six-field contract.
        fun repeatOf(draft: Draft?): String? = draft?.let { repeatedStep(it.answer, previousSteps) }
        // Cloud First: the user chose the stronger cloud model; it reads the same frame first.
        val cloudFirstTried = cloudFirst && cloudAvailable()
        if (cloudFirstTried) {
            event("CLOUD_FIRST_BEGIN sameFrame=true")
            val first = assess(cloud(request))
            if (first != null && failure(first) == null && repeatOf(first) == null) {
                event("CLOUD_FIRST_ACCEPTED")
                return Result(first.answer, observation(first), Provider.CLOUD, first)
            }
            event("CLOUD_FIRST_NO_ACCEPTED_ANSWER reason=${failure(first) ?: if (first != null) "repeated_step" else "no_text"}")
        }
        val localRaw = local(request)
        var localDraft = assess(localRaw)
        var reason = reviewReason(localDraft) ?: repeatOf(localDraft)?.let { "repeated_step" }

        if (reason != null && !localRaw.isNullOrBlank()) {
            event("LOCAL_RECOVERY_BEGIN reason=$reason")
            val repeatNote = repeatOf(localDraft)?.let {
                "\nREPEAT BLOCKED: your ANSWER repeats an earlier step (\"${it.take(200)}\"). The user has already been given it. " +
                    "Use the user's latest message and the case state: take the next step of the METHOD, or state the fault and fix."
            }.orEmpty()
            val retryRequest = request + repeatNote + "\nSTRICT LOCAL RECOVERY: output only the seven fields. Keep every value brief; ANSWER is one or two short sentences. Preserve uncertainty and visible facts; invent nothing."
            val repairedRaw = local(retryRequest)
            val repaired = assess(repairedRaw)
            if (repaired != null && failure(repaired) == null && repeatOf(repaired) == null) {
                localDraft = repaired
                reason = null
                event("LOCAL_RECOVERY_ACCEPTED")
            } else if (repaired != null && failure(repaired) == null) {
                // Valid but still a repeat: keep it only as the last resort below.
                localDraft = repaired
                reason = "repeated_step"
                event("LOCAL_RECOVERY_STILL_REPEATS")
            } else {
                event("LOCAL_RECOVERY_FAILED reason=${failure(repaired) ?: reason}")
            }
        } else if (reason != null) {
            event("LOCAL_RECOVERY_SKIPPED reason=provider_no_text")
        }

        // A valid draft that only repeats an earlier step is a last resort (better than no answer).
        val repeatedLocal = reason == "repeated_step" && localDraft != null && failure(localDraft) == null
        if (repeatedLocal) event("LOCAL_REPEAT_NOT_ACCEPTED")
        val acceptedLocal = reason == null && localDraft != null
        val deliberateCloudReview = acceptedLocal && cloudReviewNeeded(localDraft!!)
        if (acceptedLocal && !deliberateCloudReview) {
            event(if (localDraft!!.next == "REVIEW") "LOCAL_SAFE_REVIEW_ACCEPTED" else "LOCAL_ACCEPTED")
            return Result(localDraft.answer, observation(localDraft), Provider.LOCAL, localDraft)
        }

        if (deliberateCloudReview) event("CLOUD_REVIEW_REQUIRED reason=persistent_local_uncertainty")
        else event("REVIEW_REQUIRED reason=$reason")

        val attempted = cloudAvailable() && !cloudFirst
        if (attempted) {
            event("CLOUD_BEGIN sameFrame=true reason=${if (deliberateCloudReview) "persistent_local_uncertainty" else reason}")
            // Cloud receives the same current frame + provider-neutral case state,
            // never the rejected local draft as a claimed fact.
            val cloudDraft = assess(cloud(request))
            if (cloudDraft != null && failure(cloudDraft) == null && repeatOf(cloudDraft) == null) {
                event("CLOUD_ACCEPTED")
                return Result(cloudDraft.answer, observation(cloudDraft), Provider.CLOUD, cloudDraft)
            }
            event("CLOUD_NO_ACCEPTED_ANSWER reason=${failure(cloudDraft) ?: "unresolved_uncertainty"}")
        } else {
            event("CLOUD_UNAVAILABLE")
        }

        // If Local produced a valid conservative step, preserve it when Cloud is
        // unavailable/fails. A failed escalation must never make the answer worse.
        if ((acceptedLocal || repeatedLocal) && localDraft != null) {
            event(if (repeatedLocal) "LOCAL_REPEAT_KEPT_AS_LAST_RESORT" else "LOCAL_ACCEPTED_AFTER_CLOUD_FAILURE")
            return Result(localDraft.answer, observation(localDraft), Provider.LOCAL, localDraft)
        }

        val limitation = if (attempted || cloudFirstTried) "I couldn't complete cloud verification." else "Cloud review is unavailable."
        event("CLARIFICATION_REQUIRED")
        return Result(
            "$limitation I haven't verified the current view. Keep the camera on the fault area and show it a little closer or from another angle.",
            "", Provider.NONE, null
        )
    }
}
