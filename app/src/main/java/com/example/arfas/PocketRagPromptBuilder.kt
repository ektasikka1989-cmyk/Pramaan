package com.example.arfas


data class PocketRagPrompt(
    val systemAddon: String,
    val userMessage: String
)

object PocketRagPromptBuilder {
    /** Characters of the previous answer used for follow-ups (retrieval context and answer prompt). */
    const val FOLLOW_UP_ANSWER_CHARS = 300
    /**
     * Best re-ranker relevance below which the answer model is told the passages match only weakly.
     * A prompt instruction, not a filter: nothing is removed. ASHA desktop check: questions the pack
     * does not cover (pre-delivery counselling, ANC visits, JSY) had best relevance -0.58 to 0.78;
     * most answerable ones 1.9 to 10. Some answerable ones are weaker (folic acid 0.79, BMI -1.16):
     * their passages state the answer explicitly, which the instruction still allows.
     */
    const val WEAK_EVIDENCE_RELEVANCE = 1.0f
    /**
     * Below this the match is clearly off-topic (dog bite in a KB without it: -3.4). Only then is the
     * model told to answer strictly and the warning shown above the answer. Between this and
     * [WEAK_EVIDENCE_RELEVANCE] are many good matches for broad questions ("explain the types of
     * tenders" with the right passage: -1.3): the strict instruction made those one-line answers.
     */
    const val VERY_WEAK_EVIDENCE_RELEVANCE = -2.5f

    /**
     * Short, numbered instructions: small answer models (2 B and below) follow five plain rules
     * far better than a long paragraph, and every token of instruction is a token less of
     * attention on the evidence. The evidence itself is the question's key facts (one bullet per
     * source sentence, GenericKbKeyFacts) and, when the question gives amounts, the app's own
     * calculation line.
     */
    private const val SYSTEM =
        "You are ArogyaDoot. Answer only from the KNOWLEDGE EVIDENCE (passages from the documents the user selected). " +
            "Always start directly with the answer itself (no heading or label); when the question asks what to do, start with the most urgent action the evidence gives. " +
            "Combine what every relevant passage says (several documents often cover one topic); do not rely on a single passage. " +
            "Never write 'according to the evidence', 'the document says' or 'the passage mentions', and never start a sentence with a source ID. " +
            "Copy names, numbers, doses, percentages, limits and rule numbers exactly as the evidence writes them; " +
            "use a 'Calculated by the app' figure exactly as given and do no arithmetic yourself. " +
            "Put the source ID after each fact, like [S1]. Never write evidence headers such as [E1 | S1] or 'Source ID:'. " +
            "When a rule applies only to some people or cases, do not present a special case as the general rule: say which case it covers. " +
            "Use only what the evidence states; do not add background from outside the evidence. " +
            "If one part of the question is not in the evidence, answer the other parts and say that part is not covered. " +
            "If the evidence does not answer the question at all, reply with exactly [[NOT_IN_EVIDENCE]] and nothing else " +
            "(the app then says so and gives a clearly labelled general answer). " +
            "Reply in the language and script the question is written in."

    /**
     * Length tiers (Part B3): full answers in both languages; PocketRagEngine's output
     * ceiling is the hard limit behind the largest. The last-line lengthLine carries the
     * per-question shape; these tiers must never contradict it with a shorter ask.
     */
    const val LENGTH =
        " LENGTH: a fact, definition or yes/no: Answer (two to three sentences), Why it matters " +
            "(one to two), What to do (steps from the evidence), When to tell the ANM (only if the " +
            "evidence gives conditions), Common mistakes to avoid (only if the evidence gives them); " +
            "six to ten sentences on the phone, eight to fourteen from the cloud; types or a list: " +
            "one line per item for every item in the evidence, each with a one-line explanation; " +
            "a procedure, comparison, or a question asking to explain or give detail: every numbered " +
            "step in order plus what to watch for; urgent: the most useful action first, then the " +
            "steps and referral conditions. Never answer with only one sentence when the " +
            "evidence has more on the question."

    const val COMPLETE_ANSWER =
        "Answer completely in your own words: cover every point the material gives on this question " +
            "(for a \"what is\" question: what it is, its role or purpose, and its key details; for a comparison or a " +
            "\"difference between\" question: first what each one is, then how they differ), with the most " +
            "useful action first, in six to ten sentences or a complete list. The app already shows one quoted " +
            "line above your answer, so do not just repeat that line."

    private const val RECOVERY_ADDON =
        " A relevant knowledge-base evidence packet has already been retrieved for this question. " +
            "Re-read the evidence carefully and make one grounded recovery attempt. Do not refuse " +
            "merely because the evidence uses different wording. For a definition, identify the " +
            "requested term and state the definition or description supported by the evidence. " +
            "For a short factual question, answer concisely from the strongest matching evidence. " +
            "For numbers, dates, limits, percentages, rule numbers, clause numbers, and amounts, use only " +
            "values that are explicitly present in the KNOWLEDGE EVIDENCE or in the current question. " +
            "If a number is uncertain, omit that number and keep the supported explanation instead of refusing " +
            "the entire answer. Do not add facts that are not present in the evidence."

    fun build(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, recoveryMode: Boolean = false): String =
        buildParts(packet, memory, recoveryMode).let { it.systemAddon + "\n\n" + it.userMessage }

    fun buildParts(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        recoveryMode: Boolean = false
    ): PocketRagPrompt {
        val continuity = continuityMemory(packet.query, memory, packet.followUpOf.isNotBlank()).let { relevantMemory ->
            relevantMemory.fold(StringBuilder()) { out, turn ->
                val next = "User: ${turn.user}\nAssistant: ${turn.assistant}\n"
                if (out.length + next.length <= 24_000) out.append(next) else out
            }.toString()
        }
        val user = buildString {
            if (continuity.isNotBlank()) {
                appendLine("Previous KB conversation for referent continuity only; it is not factual authority:")
                appendLine(continuity)
                appendLine()
            }
            // The coverage ledger ("C1: SUPPORTED; sources=S1,S2") is NOT shown to the model: a 3B
            // model copied it into the answer. The app adds its own line for uncovered parts
            // (PocketRagEngine.discloseUnsupportedClauses); the model only gets a plain instruction.
            if (packet.clauseCoverage.any { it.state == GenericKbCoverageState.UNSUPPORTED || it.state == GenericKbCoverageState.PARTIAL }) {
                appendLine("Part of this question may not be in the evidence: answer what the evidence covers, and say in one " +
                    "short sentence which part is not covered.")
            }
            if (packet.followUpOf.isNotBlank()) {
                appendLine("This question follows up the previous question: \"${packet.followUpOf.take(400)}\". " +
                    "Resolve references such as 'it', 'the second one' or 'the other option' against that question and the evidence.")
                // Retrieval judged this turn a follow-up (evidence-gated), so the previous answer is
                // shown for reference only: which items/steps "the second one" or "in detail" refer to.
                // Facts must still come from the evidence below.
                followUpAnswer(packet.followUpOf, memory)?.let { previous ->
                    appendLine("Your previous answer, start and end (reference only, not evidence): \"$previous\"")
                    appendLine("If the user asks to continue, give only what comes after the end of the previous answer; do not repeat it.")
                }
            }
            if (packet.bestRelevance != null && packet.bestRelevance < VERY_WEAK_EVIDENCE_RELEVANCE) {
                // The re-ranker found no passage that clearly answers this question. The passages are
                // still given (they may hold part of the answer), but the model must not fill the gap.
                appendLine("The passages matched this question only weakly. Use only what they state explicitly. " +
                    "If they do not answer the question, reply with exactly [[NOT_IN_EVIDENCE]] and nothing else " +
                    "(the app then says the knowledge base does not cover it and answers in general terms).")
            }
            if (packet.terms.isNotBlank()) {
                appendLine("In these documents: ${packet.terms.take(300)}. Use exactly this meaning; never guess another expansion.")
            }
            if (packet.valueConflict.isNotBlank()) {
                appendLine("The sources give different values (${packet.valueConflict.take(300)}). Give each value with its " +
                    "source ID and say which case or document it applies to; do not pick one or combine them.")
            }
            if (packet.goldReference.isNotBlank()) {
                appendLine(packet.goldReference.take(2_000))
                appendLine()
            }
            appendLine(KbReplyText.scriptInstruction(packet.query))
            append("Current question: ").append(packet.query)
            // Last line the model reads (a 3B model follows the latest instruction best): a complete
            // answer, not one copied sentence. The app shows the key quote above the answer already.
            // R2: the line matches the question type (else the legacy one-size line).
            // Item 4: "More detail" carries its own longer line on the packet (same passages).
            val complete = if (packet.lengthLineOverride.isNotBlank()) packet.lengthLineOverride
            else if (ArogyaFlags.r2LengthPrompt) {
                runCatching { ArogyaQuestionType.lengthLine(ArogyaQuestionType.kindOf(packet.query), packet.query) }
                    .getOrDefault(COMPLETE_ANSWER)
            } else COMPLETE_ANSWER
            append("\n").append(complete)
        }
        // The pack's own name tells the model what kind of documents these are (health guidelines,
        // procurement rules, ...): the same instructions then fit every knowledge base.
        val packLine = packet.selection.displayName.trim().takeIf { it.isNotBlank() }
            ?.let { " The selected knowledge base is \"${it.take(80)}\"." }.orEmpty()
        return PocketRagPrompt(
            systemAddon = SYSTEM + packLine + LENGTH + (if (recoveryMode) RECOVERY_ADDON else ""),
            userMessage = user
        )
    }

    /**
     * Bounded conversation context for the answer: only when retrieval already resolved this
     * turn as a follow-up ([followUp] = evidence-gated, never merely short), the last 2
     * exchanges go in, trimmed. Self-contained questions stay isolated (no topic hijack).
     * Behind [ArogyaFlags.sopFollowUpContext]; the cloud resolver uses the same window.
     */
    fun continuityMemory(query: String, memory: List<PocketRagTurnMemory>, followUp: Boolean = false): List<PocketRagTurnMemory> {
        if (!ArogyaFlags.sopFollowUpContext || !followUp) return emptyList()
        return memory.takeLast(2).map { t ->
            t.copy(user = t.user.take(400), assistant = t.assistant.replace(Regex("\\s+"), " ").trim().take(800))
        }
    }

    /**
     * The start of the answer given to [followUpOf] in this KB session, or null. Used only when
     * retrieval already resolved the turn as a follow-up; same length as the retrieval context
     * (also used by GenericKnowledgeContextProvider) so the prompt envelope stays bounded.
     */
    fun followUpAnswer(followUpOf: String, memory: List<PocketRagTurnMemory>): String? {
        val previous = memory.lastOrNull { it.user.trim().equals(followUpOf.trim(), ignoreCase = true) }
            ?.assistant?.replace(KbReplyText.MORE_EN, "")?.replace(KbReplyText.MORE_HI, "")
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Start and end of the previous answer: the start says what it was about, the end says where
        // it stopped (so "continue" / "next steps" can pick up there instead of repeating it).
        return if (previous.length <= FOLLOW_UP_ANSWER_CHARS) previous
        else previous.take(FOLLOW_UP_ANSWER_CHARS / 2) + " … " + previous.takeLast(FOLLOW_UP_ANSWER_CHARS / 2)
    }

    /**
     * Continuity is based on linguistic dependency, never merely on question length.
     * This keeps self-contained questions such as "what is PAC?" isolated from the
     * previous answer while preserving elliptical follow-ups such as "explain it".
     */
}
