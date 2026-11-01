package com.example.arfas

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by a local answerer whose generation degenerated (decoder loop): never an answer. */
class PocketRagDegenerateOutput(val reason: String) : RuntimeException(reason)

interface PocketRagLocalAnswerer {
    suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutputTokens: Int,
        recoveryMode: Boolean = false
    ): String?
}

interface PocketRagCloudAnswerer {
    suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        localDraft: String?,
        reason: String,
        maxOutputTokens: Int
    ): String?
}

class PocketRagEngine(
    private val local: PocketRagLocalAnswerer,
    private val cloud: PocketRagCloudAnswerer,
    private val qualityGate: PocketRagQualityGate,
    private val sessions: PocketRagSessionStore,
    private val canUseCloud: () -> Boolean,
    private val contextWindowTokens: () -> Int = { 4_096 },
    private val localTimeoutMs: Long = 20_000L,
    private val localRecoveryTimeoutMs: Long = 12_000L,
    /**
     * False: no caller-side wall-clock deadline. The local engine's own watchdog still aborts a
     * generation that stalls (no first token within its prompt-scaled budget, or no new token for
     * its idle budget), but a healthy answer that is still being written is never discarded.
     */
    private val callerTimeoutEnabled: Boolean = true,
    /**
     * Called when a draft that was shown while being written is not accepted as it is (rejected,
     * salvaged or regenerated): the on-screen preview is cleared before the next attempt.
     */
    private val onDraftRejected: (() -> Unit)? = null,
    /** User setting "Online backup" (off by default). */
    private val onlineBackupEnabled: () -> Boolean = { false }
) {
    companion object {
        private const val TAG = "PocketRagEngine"
        /** Cloud's reply when the KB evidence does not answer a "Check online" question. */
        const val NOT_IN_EVIDENCE = "[[NOT_IN_KB]]"
        /** escalationReason: "Check online" found no answer in the KB evidence (a web search follows). */
        const val CHECK_ONLINE_NOT_IN_KB = "check_online_not_in_kb"
        private const val MIN_OUTPUT_TOKENS = 256
        /**
         * Hard ceiling per question part (~230 words). The model does not reliably follow the length
         * tiers (a definition came back at 3,297 characters in 37 s on the phone); at the measured
         * ~90 characters a second a ceiling-length answer now takes ~15 s.
         */
        const val MAX_ANSWER_TOKENS_PER_PART = 400
        /**
         * Review: a cloud answer gets 20 s to first words and 60 s total, then falls
         * back to the phone (D7's 300 s stays the outer net). The log names the limit.
         */
        const val CLOUD_FIRST_TOKEN_MS = 20_000L
        const val CLOUD_TOTAL_MS = 60_000L
        /** Length of an extractive (verbatim key facts) answer. */
        const val EXTRACTIVE_CHARS = 700

        /**
         * Enforced partial-coverage disclosure: every question part that retrieval marked
         * UNSUPPORTED is named in an app-owned line, whatever the model wrote. This is
         * deterministic and language-independent (it does not try to detect the model's own
         * wording). Skipped only when the answer already carries the app's marker sentence,
         * which replaced a model [[NOT_IN_EVIDENCE]] marker.
         */
        fun discloseUnsupportedClauses(packet: PocketRagPacket, answer: String): String {
            val unsupported = packet.clauseCoverage.filter { it.state == GenericKbCoverageState.UNSUPPORTED }
            if (unsupported.isEmpty()) return answer
            if (answer.contains(PocketRagQualityGate.UNSUPPORTED_PART_SENTENCE)) return answer
            val clauses = GenericKbQueryPlanner.plan(packet.query, packet.query).clauses.associateBy { it.id }
            val lines = unsupported.mapNotNull { c -> clauses[c.clauseId]?.literal?.trim()?.takeIf { it.isNotBlank() } }
            if (lines.isEmpty()) return answer
            return answer.trimEnd() + "\n\n" + lines.joinToString("\n") { "Not covered in the selected knowledge base: \u201c$it\u201d." }
        }
    }

    /** How Cloud takes part in a selected-KB turn (always user-chosen; never automatic). */
    enum class CloudMode {
        /** Local only (Local First). */
        NONE,
        /** Cloud First setting: Cloud answers from the same KB evidence; Local only if Cloud is unavailable or fails. */
        CLOUD_FIRST,
        /** "Check online" on a local answer: Cloud checks that answer against the KB evidence and corrects it. */
        VERIFY
    }

    /** The quality check with its own timing line (the per-question breakdown reads these). */
    private fun evalGate(query: String, text: String?, packet: PocketRagPacket, stage: String): PocketRagQualityDecision {
        val t = System.currentTimeMillis()
        val d = qualityGate.evaluate(query, text, packet)
        Log.i(TAG, "POCKET_RAG_GATE_MS stage=$stage ms=${System.currentTimeMillis() - t} accept=${d.acceptLocal} reason=${d.reason}")
        return d
    }

    suspend fun answer(
        packet: PocketRagPacket,
        cloudMode: CloudMode = CloudMode.NONE,
        localDraftToVerify: String? = null,
        /** R10-lite: the wide (12 x 1600) packet the cloud reads; null = the cloud reads [packet]. */
        cloudPacket: PocketRagPacket? = null
    ): PocketRagResult {
        val key = packet.selection.sessionKey
        val memory = sessions.context(key)
        val maxOutput = outputBudget(packet)
        // The cloud reads the wide packet when there is one (Cloud First); the phone always
        // reads the narrow one. "Check online" keeps its own already-wide packet.
        val cpacket = if (cloudMode == CloudMode.CLOUD_FIRST) (cloudPacket ?: packet) else packet
        val maxCloudOutput = outputBudget(cpacket)

        if (cloudMode != CloudMode.NONE) {
            if (canUseCloud()) {
                val reason = if (cloudMode == CloudMode.VERIFY) "check_online" else "cloud_first"
                val tCloud = System.currentTimeMillis()
                // The resolver is fresh per turn, so didStream starts false: it marks first words.
                val streamFlag = cloud as? PocketRagCloudResolver
                var limitFired: String? = null
                val cloudAnswer: String? = try {
                    withTimeout(CLOUD_TOTAL_MS) {
                        coroutineScope {
                            val job = async {
                                try {
                                    cloud.generate(cpacket, memory, localDraftToVerify, reason, maxCloudOutput)?.trim()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    Log.w(TAG, "POCKET_RAG_CLOUD_EXCEPTION mode=$cloudMode ${t.javaClass.simpleName}:${t.message}")
                                    null
                                }
                            }
                            // First-token deadline: streamed words or a finished call within 20 s,
                            // else cancel and fall back to the phone. (With streaming off, didStream
                            // stays false and a finished call counts as first tokens.)
                            val firstOk = withTimeoutOrNull(CLOUD_FIRST_TOKEN_MS) {
                                while (streamFlag != null && !streamFlag.didStream && job.isActive) delay(50)
                                true
                            } != null
                            if (!firstOk) {
                                limitFired = "first_token"
                                withTimeoutOrNull(5_000) { job.cancelAndJoin() }
                                null
                            } else job.await()
                        }
                    }
                } catch (ce: CancellationException) {
                    // Our own deadline falls back to the phone; an outer deadline or a real
                    // cancellation propagates (a real cancel never falls back).
                    if (ce is TimeoutCancellationException && System.currentTimeMillis() - tCloud >= CLOUD_TOTAL_MS) {
                        limitFired = "total"
                        if (streamFlag?.didStream == true) runCatching { onDraftRejected?.invoke() }
                        null
                    } else throw ce
                }
                if (limitFired != null) {
                    Log.w(TAG, "POCKET_RAG_CLOUD_TIMEOUT mode=$cloudMode limit=$limitFired ms=${if (limitFired == "first_token") CLOUD_FIRST_TOKEN_MS else CLOUD_TOTAL_MS} fallback=local")
                    if (limitFired == "first_token" && streamFlag?.didStream == true) runCatching { onDraftRejected?.invoke() }
                }
                if (cloudMode == CloudMode.VERIFY && cloudAnswer?.contains(NOT_IN_EVIDENCE) == true) {
                    // The KB evidence does not answer it: the caller runs a live web search (labelled).
                    Log.i(TAG, "POCKET_RAG_CHECK_ONLINE_NOT_IN_KB")
                    if ((cloud as? PocketRagCloudResolver)?.didStream == true) runCatching { onDraftRejected?.invoke() }
                    return PocketRagResult(
                        "", AndroBaselineRuntime.Provider.LOCAL, emptyList(),
                        localAttempted = false, cloudAttempted = true, escalationReason = CHECK_ONLINE_NOT_IN_KB, accepted = false
                    )
                }
                if (!cloudAnswer.isNullOrBlank()) {
                    // Cloud First is grounded like a local answer (same checks). A "Check online" answer
                    // may add newer information the KB does not have; the prompt makes Cloud label it.
                    val decision = if (cloudMode == CloudMode.CLOUD_FIRST) evalGate(packet.query, cloudAnswer, cpacket, "cloud_first")
                    else PocketRagQualityDecision(true, "cloud_verified")
                    if (decision.acceptLocal) return acceptCloud(key, cpacket, cloudAnswer, "$reason:${decision.reason}")
                    Log.w(TAG, "POCKET_RAG_CLOUD_REJECT mode=$cloudMode reason=${decision.reason}")
                    if ((cloud as? PocketRagCloudResolver)?.didStream == true) runCatching { onDraftRejected?.invoke() }
                }
            } else {
                Log.w(TAG, "POCKET_RAG_CLOUD_UNAVAILABLE mode=$cloudMode fallback=local")
            }
            if (cloudMode == CloudMode.VERIFY) {
                return PocketRagResult(
                    "I couldn't reach the online AI to check this answer. Please check the internet connection and that a Meta API key is set in Settings → Online Meta AI, then try again.",
                    AndroBaselineRuntime.Provider.LOCAL, emptyList(),
                    localAttempted = false, cloudAttempted = true, escalationReason = "check_online_unavailable", accepted = false
                )
            }
        }

        val first = runLocal(packet, memory, maxOutput, recoveryMode = false, timeoutMs = localTimeoutMs)
        val initialMarker = qualityGate.sanitizeUnsupportedMarkers(first.text)
        val localDraft = initialMarker.text
        var decision = if (first.degenerate != null) {
            PocketRagQualityDecision(false, "degenerate_output:${first.degenerate}")
        } else if (initialMarker.markerOnly) {
            PocketRagQualityDecision(false, "local_declared_insufficient")
        } else {
            evalGate(packet.query, localDraft, packet, "local_first")
        }
        Log.i(TAG, "POCKET_RAG_LOCAL_GATE reason=${decision.reason} accepted=${decision.acceptLocal} draftChars=${localDraft.length} coverage=${packet.coverageComplete}")
        if (!decision.acceptLocal) runCatching { onDraftRejected?.invoke() }
        if (!decision.acceptLocal && localDraft.isNotBlank()) {
            Log.d(TAG, "POCKET_RAG_REJECTED_DRAFT reason=${decision.reason} text='${localDraft.take(400).replace('\n', ' ')}'")
        }
        if (decision.acceptLocal && localDraft.isNotBlank()) {
            return acceptLocal(key, packet, localDraft, decision.reason, cloudAttempted = false)
        }
        // An otherwise grounded draft rejected only for an unsupported number: remove just that
        // sentence first (instant) instead of regenerating the whole answer.
        if (decision.reason.startsWith("unsupported_numeric_claim") && localDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageUnsupportedNumericSentences(packet.query, localDraft, packet)
            if (!salvaged.isNullOrBlank() && salvaged != localDraft) {
                val salvageDecision = evalGate(packet.query, salvaged, packet, "numeric_salvage")
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_ACCEPT removedUnsupported=true stage=first_draft")
                    return acceptLocal(key, packet, salvaged, "local_recovery:numeric_salvage", cloudAttempted = false)
                }
            }
        }

        // A statement that copied a passage but swapped its named term (APBG -> ePBG): drop only
        // those statements and keep the rest when it still passes every check.
        if (decision.reason.startsWith("term_swap") && localDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageSwappedTerms(localDraft, packet)
            if (!salvaged.isNullOrBlank() && salvaged != localDraft) {
                val salvageDecision = evalGate(packet.query, salvaged, packet, "term_swap_salvage")
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_TERM_SWAP_SALVAGE_ACCEPT stage=first_draft")
                    return acceptLocal(key, packet, salvaged, "local_recovery:term_swap_salvage", cloudAttempted = false)
                }
            }
        }

        // A model refusal is not the same thing as missing KB evidence. When retrieval
        // produced a complete, query-related packet, give the local model one bounded
        // recovery pass before considering Cloud. This is especially important offline.
        var bestLocalDraft = localDraft
        if (shouldRetryLocally(decision, packet)) {
            Log.i(TAG, "POCKET_RAG_LOCAL_RECOVERY_START reason=${decision.reason} sources=${packet.sourceIds.size}")
            val recovery = runLocal(
                packet = packet,
                memory = memory,
                maxOutput = maxOutput,
                recoveryMode = true,
                timeoutMs = localRecoveryTimeoutMs
            )
            val recoveryMarker = qualityGate.sanitizeUnsupportedMarkers(recovery.text)
            val recoveryDraft = recoveryMarker.text
            val recoveryDecision = if (recovery.degenerate != null) {
                PocketRagQualityDecision(false, "degenerate_output:${recovery.degenerate}")
            } else if (recoveryMarker.markerOnly) {
                PocketRagQualityDecision(false, "local_declared_insufficient")
            } else {
                evalGate(packet.query, recoveryDraft, packet, "local_recovery")
            }
            if (recoveryDecision.acceptLocal && recoveryDraft.isNotBlank()) {
                return acceptLocal(key, packet, recoveryDraft, "local_recovery:${recoveryDecision.reason}", cloudAttempted = false)
            }
            Log.w(TAG, "POCKET_RAG_LOCAL_RECOVERY_REJECT reason=${recoveryDecision.reason}")
            runCatching { onDraftRejected?.invoke() }
            if (recoveryDraft.isNotBlank()) {
                Log.d(TAG, "POCKET_RAG_REJECTED_DRAFT reason=${recoveryDecision.reason} text='${recoveryDraft.take(400).replace('\n', ' ')}'")
            }
            // A blank or timed-out recovery must not replace the first draft's diagnosis: the
            // numeric salvage below still applies to the first draft.
            if (recoveryDraft.isNotBlank()) {
                bestLocalDraft = recoveryDraft
                decision = recoveryDecision
            }
        }

        // Numeric grounding failures are often formatting/local-generation defects rather
        // than evidence failures. After the bounded local rewrite, salvage only the
        // unsupported numeric sentence/line and re-run the full gate. This preserves
        // grounded local content without inventing or silently changing a number.
        if (decision.reason.startsWith("unsupported_numeric_claim") && bestLocalDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageUnsupportedNumericSentences(
                packet.query,
                bestLocalDraft,
                packet
            )
            if (!salvaged.isNullOrBlank() && salvaged != bestLocalDraft) {
                val salvageDecision = qualityGate.evaluate(packet.query, salvaged, packet)
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_ACCEPT removedUnsupported=true")
                    return acceptLocal(
                        key,
                        packet,
                        salvaged,
                        "local_recovery:numeric_salvage",
                        cloudAttempted = false
                    )
                }
                Log.w(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_REJECT reason=${salvageDecision.reason}")
                decision = salvageDecision
            }
        }

        // Phase 1.1 selected-KB policy: ordinary KB questions are local-only. Cloud
        // is introduced later only by an explicit user action or a manifest-backed
        // freshness requirement; a weak local draft is not consent to send KB data.
        if (canUseCloud() && shouldEscalateToCloud(decision, packet)) {
            val cloudAnswer = runCatching {
                cloud.generate(packet, memory, bestLocalDraft, decision.reason, maxOutput)
            }.onFailure { Log.w(TAG, "POCKET_RAG_CLOUD_EXCEPTION ${it.javaClass.simpleName}:${it.message}") }
                .getOrNull()
            val cloudDecision = evalGate(packet.query, cloudAnswer, packet, "cloud_escalation")
            if (cloudDecision.acceptLocal && !cloudAnswer.isNullOrBlank()) {
                val citedSources = packet.citedSourceIds(cloudAnswer)
                val disclosedCloud = KnowledgeSourcePresentation.renumberCitations(discloseUnsupportedClauses(packet, cloudAnswer), citedSources)
                sessions.record(key, packet.query, disclosedCloud, citedSources)
                Log.i(TAG, "POCKET_RAG_ACCEPT provider=CLOUD reason=${cloudDecision.reason} sources=${citedSources.size}")
                return PocketRagResult(
                    disclosedCloud,
                    AndroBaselineRuntime.Provider.CLOUD,
                    citedSources,
                    localAttempted = true,
                    cloudAttempted = true,
                    escalationReason = decision.reason
                )
            }
            if (!cloudAnswer.isNullOrBlank()) {
                Log.w(TAG, "POCKET_RAG_CLOUD_REJECT reason=${cloudDecision.reason}")
            }
        }

        // R3-lite: an answer rejected only for weak evidence support keeps its supported
        // sentences (at least 2 must remain) instead of falling back to copied key points.
        if (ArogyaFlags.r3Salvage && decision.reason.startsWith("low_evidence_support") && bestLocalDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageUnsupportedStatements(bestLocalDraft, packet.evidenceText)
            if (!salvaged.isNullOrBlank() && salvaged != bestLocalDraft) {
                val salvageDecision = evalGate(packet.query, salvaged, packet, "statement_salvage")
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_STATEMENT_SALVAGE_ACCEPT removedUnsupported=true")
                    return acceptLocal(key, packet, salvaged, "local_recovery:statement_salvage", cloudAttempted = false)
                }
                Log.w(TAG, "POCKET_RAG_STATEMENT_SALVAGE_REJECT reason=${salvageDecision.reason}")
            }
        }

        // The model could not write a checked answer, but retrieval matched the question well: show
        // the key facts themselves (verbatim source sentences, with their sources) instead of a
        // failure message. Nothing is generated, so nothing can be invented.
        extractiveAnswer(packet)?.let { extractive ->
            Log.i(TAG, "POCKET_RAG_EXTRACTIVE_ANSWER reason=${decision.reason} chars=${extractive.length} best=${packet.bestRelevance}")
            return acceptLocal(key, packet, extractive, "extractive:${decision.reason}", cloudAttempted = false)
        }

        // Do not mislabel a generation failure as "out of context" when retrieval did
        // find evidence. The fallback states exactly what failed and keeps source refs.
        val fallback = if (packet.evidenceBlocks.isNotEmpty() && decision.reason.startsWith("local_declared_insufficient")) {
            // The model read the retrieved passages twice and found no answer in them. Say only
            // what is known: the search did not find an answer (not that the KB lacks one).
            "I couldn't find an answer to this in the selected knowledge base."
        } else if (packet.evidenceBlocks.isNotEmpty()) {
            "I found relevant information in the selected knowledge base, but the local model could not produce a reliable grounded answer from it."
        } else {
            "I couldn't find enough relevant information in the selected knowledge base to answer that reliably."
        }
        Log.w(
            TAG,
            "POCKET_RAG_INSUFFICIENT reason=${decision.reason} cloudAvailable=${canUseCloud()} " +
                "cloudAttempted=${canUseCloud() && shouldEscalateToCloud(decision, packet)} evidence=${packet.evidenceBlocks.size}"
        )
        return PocketRagResult(
            fallback,
            AndroBaselineRuntime.Provider.LOCAL,
            emptyList(),
            localAttempted = true,
            cloudAttempted = canUseCloud() && shouldEscalateToCloud(decision, packet),
            escalationReason = decision.reason,
            accepted = false
        )
    }

    /**
     * Key-fact lines of the evidence ("• sentence" plus the app's calculation line) as a cited
     * answer, best facts first, about [EXTRACTIVE_CHARS] characters. Null when the re-ranker judged
     * the evidence weak (the facts would not answer the question) or there are no key-fact lines.
     */
    internal suspend fun extractiveAnswer(packet: PocketRagPacket): String? {
        val relevance = packet.bestRelevance ?: return null
        if (relevance < PocketRagPromptBuilder.WEAK_EVIDENCE_RELEVANCE) return null
        // The passages as the model read them (whole windows or key sentences), without the
        // "Source ID:" / "Source:" header lines and the bullet marks.
        val passageTexts = packet.evidenceBlocks.map { block ->
            block.sourceId to block.text.lines().map { it.trim() }
                .filterNot { it.isBlank() || it.startsWith("Source ID:") || it.startsWith("Source:") || it.startsWith("Note:") }
                .joinToString(" ") { it.removePrefix("\u2022").trim() }
                // List items inside a passage ("• Zinc helps … • Body loses zinc …", PDF bullet glyphs,
                // gaps of 3+ spaces left by removed glyphs) become sentence ends, so each item can be
                // chosen on its own instead of one long run-on line.
                .replace(Regex("\\s*[\\u2022\\u25AA\\u25CF\\uF0B7\\uF0A7\\uF0D8\\u0080-\\u009F]\\s*|(?:\\s*[\\u00A0\\u2000-\\u200B\\u202F\\u3000]\\s*)+|\\s{3,}"), "; ")
                .replace(Regex("(?:[;.]\\s*){2,}"), "; ")
                .replace(Regex("([:\\-–])\\s*;\\s*"), "$1 ")
                .replace(Regex("\\s+"), " ").replace("…", " ").trim().trimStart('.', ';', ' ')
        }.filter { it.second.isNotBlank() }
        if (passageTexts.isEmpty()) return null
        // The sentences that answer the question (word overlap with the question, best first; no
        // model involved), at most ~EXTRACTIVE_CHARS, shown in their passage order with their source.
        val facts = runCatching {
            GenericKbKeyFacts.select(packet.query, passageTexts.map { it.second }, null, EXTRACTIVE_CHARS, 6)
        }.getOrDefault(emptyList())
        val lines = mutableListOf<String>()
        facts.sortedWith(compareBy({ it.passage }, { it.index })).forEach { f ->
            val text = cleanFactSentence(f.text)
            if (text.length >= 25 && lines.none { it.contains(text.take(60)) }) lines += "\u2022 $text [${passageTexts[f.passage].first}]"
        }
        if (lines.isEmpty()) {
            // No sentence shares the question's words (e.g. a Hindi question over English text):
            // the start of the best passage, cut at a sentence end.
            val (id, text) = passageTexts.first()
            val cut = text.take(EXTRACTIVE_CHARS).let { t -> t.lastIndexOfAny(charArrayOf('.', '।')).takeIf { it > 80 }?.let { t.take(it + 1) } ?: t }
            lines += "\u2022 ${cleanFactSentence(cut)} [$id]"
        }
        // The pack's own long form of an abbreviation in the question (its glossary) leads the facts.
        packet.terms.split(';').map { it.trim() }.filter { it.contains('=') }.take(2).reversed().forEach { t ->
            val (abbr, long) = t.split('=', limit = 2).map { it.trim() }
            if (abbr.isNotBlank() && long.isNotBlank()) lines.add(0, "\u2022 $abbr: ${long.split(' ').joinToString(" ") { w -> if (w.length > 3) w.replaceFirstChar { it.uppercase() } else w }}")
        }
        val header = if (KbReplyText.dominantScript(packet.query) == KbReplyText.Script.DEVANAGARI)
            "चुने गए ज्ञान आधार में यह लिखा है:" else "Here is what the selected knowledge base says:"
        return header + "\n" + lines.joinToString("\n")
    }

    /** A source sentence as shown to the user: page furniture (running titles, page numbers, bullets) removed. */
    private fun cleanFactSentence(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Leading running header in capitals ("HANDBOOK FOR ASHA ON HOME BASED CARE … 60 5.4.9 Zinc…").
        t = t.replace(Regex("^(?:[A-Z][A-Z&,'’\\-]+\\s+){3,}(?:\\d+\\s+)?"), "")
        // Leading page / section numbers ("60 5.4.9 ").
        t = t.replace(Regex("^(?:\\d+(?:\\.\\d+)*\\.?\\s+){1,3}(?=[A-Za-z\\u0900-\\u097F(])"), "")
        // A paragraph number glued between a heading and its text ("… (AoN) 10. After …") ends a sentence.
        t = t.replace(Regex("(?<=[A-Za-z)])\\s+\\d{1,3}\\.\\s+(?=[A-Z])"), ". ")
        t = t.trim(' ', '•', '-', '–', '|', ';', ',')
        return t
    }

    private fun acceptCloud(key: String, packet: PocketRagPacket, answer: String, reason: String): PocketRagResult {
        val citedSources = packet.citedSourceIds(answer)
        val disclosed = KnowledgeSourcePresentation.renumberCitations(discloseUnsupportedClauses(packet, answer), citedSources)
        sessions.record(key, packet.query, disclosed, citedSources)
        Log.i(TAG, "POCKET_RAG_ACCEPT provider=CLOUD reason=$reason sources=${citedSources.size}")
        return PocketRagResult(
            disclosed, AndroBaselineRuntime.Provider.CLOUD, citedSources,
            localAttempted = false, cloudAttempted = true, escalationReason = reason
        )
    }

    private fun shouldEscalateToCloud(
        decision: PocketRagQualityDecision,
        packet: PocketRagPacket
    ): Boolean {
        // Only when the user turned on "Online backup" (off by default): the phone's answer failed its
        // checks twice and there is evidence to answer from. The online AI gets the same passages and
        // its answer passes the same checks, so it cannot add facts the knowledge base does not have.
        return runCatching { onlineBackupEnabled() }.getOrDefault(false) && packet.evidenceBlocks.isNotEmpty()
    }

    /** One local generation: its text, or why it is unusable ([degenerate] set: never shown). */
    private class LocalDraft(val text: String?, val degenerate: String? = null)

    private suspend fun runLocal(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutput: Int,
        recoveryMode: Boolean,
        timeoutMs: Long
    ): LocalDraft {
        val started = System.currentTimeMillis()
        var degenerate: String? = null

        suspend fun call() = runCatching { local.generate(packet, memory, maxOutput, recoveryMode) }
            .onFailure {
                if (it is PocketRagDegenerateOutput) degenerate = it.reason
                else Log.w(TAG, "POCKET_RAG_LOCAL_EXCEPTION recovery=$recoveryMode ${it.javaClass.simpleName}:${it.message}")
            }
            .getOrNull()
        val result = if (callerTimeoutEnabled) withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { call() } else call()
        
        val elapsed = System.currentTimeMillis() - started
        // No question or answer text in the log (health data); timings are the generation's own.
        Log.i(
            TAG,
            "POCKET_RAG_LOCAL_DONE recovery=$recoveryMode best=${packet.bestRelevance?.let { "%.2f".format(it) } ?: "-"} " +
                "evidence=${packet.evidenceBlocks.size} history=${memory.size} totalMs=$elapsed " +
                "chars=${result?.length ?: 0} timedOut=${result == null && degenerate == null}"
        )
        // Glued or spaced repetition anywhere in the draft ("heinheinhein…", "है है है है …").
        if (degenerate == null && result != null) degenerate = KbReplyText.degenerateRepetition(result)
        if (degenerate != null) {
            Log.w(TAG, "POCKET_RAG_LOCAL_DEGENERATE recovery=$recoveryMode reason=$degenerate chars=${result?.length ?: 0} elapsed=${System.currentTimeMillis() - started}ms")
            return LocalDraft(null, degenerate)
        }
        if (result == null) {
            Log.w(TAG, "POCKET_RAG_LOCAL_TIMEOUT recovery=$recoveryMode timeout=${timeoutMs}ms elapsed=${System.currentTimeMillis() - started}ms")
        }
        // A degenerate repetition at the end ("है है है …") is cut; the answer before it is kept and
        // then checked by the quality gate like any other draft.
        // Near the output budget and not ending a sentence: it was cut at the limit. Characters per
        // token are at least ~2.5 for the scripts in use, so length >= budget x 2.5 marks the limit.
        val cleaned = result?.let { KbReplyText.removeRepeatedSentences(KbReplyText.trimRepeatedTail(it)) }
        val trimmed = cleaned?.let { KbReplyText.completeAtLimit(it, likelyAtLimit = it.length >= maxOutput * 5 / 2) }
        if (trimmed != null && trimmed.length != result.length) {
            Log.w(TAG, "POCKET_RAG_LOCAL_REPETITION_TRIMMED recovery=$recoveryMode before=${result.length} after=${trimmed.length}")
        }
        return LocalDraft(trimmed)
    }

    private fun shouldRetryLocally(decision: PocketRagQualityDecision, packet: PocketRagPacket): Boolean {
        if (packet.evidenceBlocks.isEmpty()) return false
        // Wrong reply script is a formatting slip, not missing evidence: always one retry. A broken
        // (looping, incoherent or unsupported) generation also gets one fresh generation.
        if (decision.reason.startsWith("reply_script_mismatch") ||
            decision.reason.startsWith("degenerate_") ||
            decision.reason.startsWith("term_swap") ||
            decision.reason.startsWith("low_evidence_support") ||
            decision.reason.startsWith("wrong_expansion")
        ) return true
        if (!packet.coverageComplete) return false
        return decision.reason.startsWith("local_answer_blank") ||
            decision.reason.startsWith("local_declared_insufficient") ||
            decision.reason.startsWith("answer_query_mismatch") ||
            decision.reason.startsWith("no_lexical_grounding_signal") ||
            decision.reason.startsWith("reply_script_mismatch") ||
            decision.reason.startsWith("unsupported_numeric_claim")
    }

    private suspend fun acceptLocal(
        key: String,
        packet: PocketRagPacket,
        answer: String,
        reason: String,
        cloudAttempted: Boolean
    ): PocketRagResult {
        val citedSources = packet.citedSourceIds(answer)
        // A written answer that no retrieved passage supports (no citation and no shared content,
        // e.g. the model answered in another language or from memory) is not shown as a KB answer:
        // the passages' own key facts are shown instead, with their sources.
        // A Hindi answer from English passages cannot share their words, so it carries no checked
        // citation; it is kept (the runtime's proof check matches it to its passages across languages)
        // rather than replaced by English passage text under a Hindi heading.
        val crossScript = KbReplyText.dominantScript(answer) == KbReplyText.Script.DEVANAGARI &&
            KbReplyText.dominantScript(packet.evidenceText) != KbReplyText.Script.DEVANAGARI
        if (citedSources.isEmpty() && !reason.startsWith("extractive") && !crossScript) {
            extractiveAnswer(packet)?.let { extractive ->
                Log.w(TAG, "POCKET_RAG_UNGROUNDED_DRAFT reason=$reason -> extractive")
                return acceptLocal(key, packet, extractive, "extractive:ungrounded_draft", cloudAttempted)
            }
        }
        // Internal passage markers ([S3], [S7]) become the numbers of the document rows shown.
        val withDisclosure = discloseUnsupportedClauses(packet, answer)
        val disclosed = KnowledgeSourcePresentation.renumberCitations(withDisclosure, citedSources)
        sessions.record(key, packet.query, disclosed, citedSources)
        Log.i(
            TAG,
            "POCKET_RAG_ACCEPT provider=LOCAL reason=$reason sources=${citedSources.size} " +
                "unsupportedDisclosed=${withDisclosure.length != answer.length} coverage=${packet.clauseCoverage.joinToString(",") { "${it.clauseId}:${it.state}" }}"
        )
        return PocketRagResult(
            disclosed,
            AndroBaselineRuntime.Provider.LOCAL,
            citedSources,
            localAttempted = true,
            cloudAttempted = cloudAttempted,
            escalationReason = if (reason.startsWith("local_recovery:")) reason else ""
        )
    }

    /** Evidence-driven token budget; no topic/keyword routing. */
    private fun outputBudget(packet: PocketRagPacket): Int {
        val blockCount = packet.evidenceBlocks.size
        val chars = packet.evidenceText.length
        val desired = GenericKbTokenBudget.desiredOutputTokens(
            query = packet.query,
            clauses = packet.clauseCoverage.size.coerceAtLeast(GenericKbQueryPlanner.plan(packet.query, packet.query).clauses.size),
            evidence = chars + blockCount * 64
        )
        val context = contextWindowTokens().coerceAtLeast(1_024)
        val inputEstimate = GenericKbTokenBudget.estimateTokens(chars + packet.query.length) + 620
        val availableOutput = (context - inputEstimate).coerceAtLeast(256)
        // The answer model picks the length from the question (PocketRagPromptBuilder.LENGTH); this
        // is the hard ceiling behind its largest tier, per independently asked question part.
        // R11: procedures and lists get room for every step/item; facts keep 400.
        val parts = packet.clauseCoverage.size.coerceAtLeast(1)
        val perPart = if (ArogyaFlags.r11LowReasoning) {
            runCatching { ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.kindOf(packet.query)) }
                .getOrDefault(MAX_ANSWER_TOKENS_PER_PART)
        } else MAX_ANSWER_TOKENS_PER_PART
        val ceiling = perPart * parts
        return desired.coerceAtMost(ceiling).coerceAtMost(availableOutput).coerceAtLeast(MIN_OUTPUT_TOKENS)
    }
}
