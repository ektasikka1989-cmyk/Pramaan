package com.example.arfas

import android.util.Log

/** Cloud is a synthesis fallback only: it receives the same KB facts and provenance. */
class KnowledgeCloudGenerator(
    private val provider: GroqChatProvider
) {
    companion object { private const val TAG = "KnowledgeCloudGenerator" }

    suspend fun generate(
        packet: KnowledgeEvidencePacket,
        history: List<Pair<String, String>>,
        localDraft: String?,
        reason: String,
        maxOutputTokens: Int
    ): String? {
        val current = buildString {
            appendLine("<selected_kb_facts>")
            appendLine(packet.evidence.trim().take(9_000))
            appendLine("</selected_kb_facts>")
            if (packet.sourceDetails.isNotBlank()) {
                appendLine("<source_details>")
                appendLine(packet.sourceDetails.trim().take(4_000))
                appendLine("</source_details>")
            }
            appendLine("<resolution_contract>")
            appendLine("Use the selected KB facts above as the factual authority. Synthesize across all relevant supplied facts. Do not replace missing KB facts with outside knowledge. If the supplied facts do not establish part of the answer, state that limitation. Produce the best clear conversational answer possible.")
            appendLine("</resolution_contract>")
            appendLine("<user_question>")
            appendLine(packet.query.trim().take(3_000))
            appendLine("</user_question>")
            if (!localDraft.isNullOrBlank()) {
                appendLine("<local_draft_for_improvement>")
                appendLine(localDraft.trim().take(2_500))
                appendLine("</local_draft_for_improvement>")
                appendLine("Do not assume the local draft is correct; rebuild the answer from the selected KB facts.")
            }
        }
        val answer = provider.chatGenericKnowledgeText(
            messages = history.takeLast(8),
            currentQuery = current,
            maxOutputTokens = maxOutputTokens.coerceIn(512, 1_280)
        ).trim()
        Log.i(TAG, "KB_CLOUD_DONE reason=$reason chars=${answer.length} evidenceChars=${packet.evidence.length}")
        return FixSightOutputSanitizer.sanitize(answer).trim().takeIf { it.isNotBlank() }
    }
}
