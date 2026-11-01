package com.example.arfas

import android.util.Log
import kotlinx.coroutines.flow.collect

/** PocketSage-style single local generation over a selected evidence packet. */
class PocketRagLocalGenerator(
    private val modelManager: LocalModelManager?,
    /** Receives the answer while it is written (on-screen preview); null = no preview. */
    private val onDelta: ((String) -> Unit)? = null
) : PocketRagLocalAnswerer {
    companion object { private const val TAG = "PocketRagLocalGenerator" }

    override suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutputTokens: Int,
        recoveryMode: Boolean
    ): String? {
        val lm = modelManager ?: return null
        if (!lm.isReady || !lm.isTextWarm) {
            Log.w(TAG, "POCKET_RAG_LOCAL_NOT_READY state=${lm.state} ready=${lm.isReady} textWarm=${lm.isTextWarm} disabled=${lm.permanentlyDisabled}")
            return null
        }
        val prompt = PocketRagPromptBuilder.buildParts(packet, memory, recoveryMode)
        var completed: BaselineGenerationResult? = null
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        val timedDelta = onDelta?.let { cb ->
            { piece: String ->
                if (firstTokenMs < 0 && piece.isNotBlank()) firstTokenMs = System.currentTimeMillis() - t0
                cb(piece)
            }
        }
        lm.inferTextGrounded(
            evidence = packet.modelEvidenceText,
            userQueryOnly = prompt.userMessage,
            history = emptyList(),
            strictGrounding = false,
            sourceGroundingRequired = true,
            knowledgePackGrounding = true,
            maxOutputTokenOverride = maxOutputTokens.coerceAtLeast(256),
            systemPromptAddon = prompt.systemAddon,
            onToken = timedDelta,
            onComplete = { completed = it }
        ).collect { }
        val result = completed ?: return null
        val clean = KbReplyText.normalizeCitations(FixSightOutputSanitizer.sanitize(result.text).trim())
        Log.i(TAG, "POCKET_RAG_LOCAL_END completed=${result.completed} raw=${result.text.length} clean=${clean.length} stop=${result.stopReason} error=${result.error}")
        Log.i(TAG, "POCKET_RAG_LOCAL_GEN_MS total=${System.currentTimeMillis() - t0} firstToken=$firstTokenMs chars=${clean.length} recovery=$recoveryMode")
        // The decoder's loop guard stopped a repeating generation. Its text is broken, not a
        // shorter answer: it is never shown; the engine retries once and otherwise fails safely.
        if (result.stopReason.contains("loop_guard")) throw PocketRagDegenerateOutput("decoder_loop_guard")
        return clean.takeIf { result.completed && it.isNotBlank() }
    }
}
