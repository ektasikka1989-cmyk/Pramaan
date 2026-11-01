package com.example.arfas

import android.util.Log

/** Grounded Cloud resolver. It receives only selected-KB evidence and KB-only continuity. */
class PocketRagCloudResolver(
    private val provider: GroqChatProvider
) : PocketRagCloudAnswerer {
    companion object {
        private const val TAG = "PocketRagCloudResolver"
        /** Cloud's reply when the KB evidence does not answer a "Check online" question. */
        const val NOT_IN_EVIDENCE = PocketRagEngine.NOT_IN_EVIDENCE
    }

    override suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        localDraft: String?,
        reason: String,
        maxOutputTokens: Int
    ): String? {
        val referenced = packet.evidenceBlocks.map { it.sourceId }.toSet()
        val sources = packet.sources
            .filter { referenced.isEmpty() || it.id in referenced }
            .joinToString("\n") { source -> "${source.id}: ${source.display}" }
        val current = buildString {
            appendLine("<selected_kb_evidence>")
            appendLine(packet.evidenceText)
            appendLine("</selected_kb_evidence>")
            appendLine("<source_details>")
            appendLine(sources)
            appendLine("</source_details>")
            appendLine("<user_question>${packet.query}</user_question>")
            if (packet.followUpOf.isNotBlank()) {
                appendLine("<previous_question>${packet.followUpOf.take(400)}</previous_question>")
                appendLine("The user's question follows up the previous question; resolve 'it', 'continue', 'the second one' etc. against it.")
            }
            if (reason == "check_online") {
                if (!localDraft.isNullOrBlank()) {
                    appendLine("<on_device_answer>")
                    appendLine(localDraft.take(4_000))
                    appendLine("</on_device_answer>")
                }
                appendLine("The user asked to check the on-device answer online. Verify it against the selected KB evidence: " +
                    "correct every wrong or missing fact, keep what is right, and give the corrected complete answer (not a review). " +
                    "Cite evidence with its source IDs like [S1]. Use only the evidence: never fill a gap from your own memory " +
                    "(for example, never guess what an abbreviation stands for). If the evidence does not state the answer, " +
                    "reply with exactly $NOT_IN_EVIDENCE and nothing else; the app then searches the web instead.")
            } else {
                appendLine("Use only the selected KB evidence above as factual authority. Synthesize the best complete conversational answer, citing source IDs like [S1]. If the evidence cannot establish part of the request, say so rather than using outside knowledge.")
            }
        }
        val messages = PocketRagPromptBuilder.continuityMemory(packet.query, memory).flatMap { turn ->
            listOf("user" to turn.user, "assistant" to turn.assistant)
        }
        val raw = provider.chatGenericKnowledgeText(
            messages = messages,
            currentQuery = current,
            maxOutputTokens = maxOutputTokens.coerceAtLeast(256)
        ).trim()
        Log.i(TAG, "POCKET_RAG_CLOUD_END reason=$reason chars=${raw.length} evidenceChars=${packet.evidenceText.length}")
        // Cloud is intentionally not rewritten or revalidated by the local model.
        return raw.takeIf { it.isNotBlank() }
    }
}
