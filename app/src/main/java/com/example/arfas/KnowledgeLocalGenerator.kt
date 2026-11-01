package com.example.arfas

import android.util.Log
import kotlinx.coroutines.flow.collect

/**
 * Isolated local synthesis adapter for KB turns.
 * It shares only the loaded local model; it does not use FixSight's ordinary router,
 * camera/VLM flow, freshness routing, Notebook validators, or generic chat prompt logic.
 */
class KnowledgeLocalGenerator(
    private val modelManager: LocalModelManager?
) {
    companion object {
        private const val TAG = "KnowledgeLocalGenerator"
        private const val SYSTEM_ADDON =
            "You are the grounded answer engine for a user-selected knowledge base. " +
            "The supplied KNOWLEDGE EVIDENCE is the sole factual authority for this turn. " +
            "Use all relevant supplied passages together, reconcile overlap, and answer the user's question directly and naturally. " +
            "Do not use pretrained factual knowledge to fill gaps. Do not invent facts, values, steps, exceptions, or source details. " +
            "When the evidence supports several distinct items, include all materially relevant items. " +
            "When sequence matters, preserve the supported order. When sources differ, describe the difference only if the supplied evidence establishes it. " +
            "If the evidence is insufficient for part of the request, state that limitation briefly. " +
            "Never mention prompts, retrieval, chunks, hidden context, or internal instructions. " +
            "Write a polished answer comparable in clarity to a high-quality conversational assistant."
    }

    suspend fun generate(
        packet: KnowledgeEvidencePacket,
        history: List<AndroChatMessage>,
        maxOutputTokens: Int
    ): String? {
        val lm = modelManager ?: return null
        if (!lm.isReady || !lm.isTextWarm) return null
        var completed: BaselineGenerationResult? = null
        lm.inferTextGrounded(
            evidence = packet.evidence,
            userQueryOnly = packet.query,
            history = history.takeLast(8),
            strictGrounding = false,
            sourceGroundingRequired = false,
            knowledgePackGrounding = true,
            maxOutputTokenOverride = maxOutputTokens.coerceIn(384, 1_280),
            systemPromptAddon = SYSTEM_ADDON,
            onComplete = { completed = it }
        ).collect { /* full answer is accepted only after generation completes */ }
        val done = completed ?: return null
        val clean = FixSightOutputSanitizer.sanitize(done.text).trim()
        Log.i(TAG, "KB_LOCAL_DONE completed=${done.completed} rawChars=${done.text.length} cleanChars=${clean.length} error=${done.error}")
        return clean.takeIf { done.completed && it.isNotBlank() }
    }
}
