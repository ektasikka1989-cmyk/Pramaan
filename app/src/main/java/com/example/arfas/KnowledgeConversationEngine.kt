package com.example.arfas

import android.util.Log

/**
 * Standalone conversational RAG orchestrator.
 * Selected KB turns never enter FixSight's ordinary brain after this engine owns them.
 */
class KnowledgeConversationEngine(
    private val local: KnowledgeLocalGenerator,
    private val cloud: KnowledgeCloudGenerator,
    private val canUseCloud: () -> Boolean
) {
    companion object {
        private const val TAG = "KnowledgeConversationEngine"
    }

    fun packetFrom(query: String, attachments: List<BaselineContextAttachment>): KnowledgeEvidencePacket? {
        val kb = attachments.filter {
            it.attributes["grounding_required"] == "true" ||
                it.attributes["knowledge_pack"].orEmpty().isNotBlank()
        }
        if (kb.isEmpty()) return null

        val evidence = kb.joinToString("\n\n") { it.text.trim() }.trim()
        if (evidence.isBlank()) return null

        val sourceIds = buildList {
            kb.forEach { a ->
                val refs = a.attributes["kb_source_refs"].orEmpty().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                if (refs.isNotEmpty()) refs.forEach { add("kbref:$it") }
                else add(a.sourceId)
            }
        }.distinct().take(20)

        val sourceDetails = kb.joinToString("\n") { a ->
            val refs = a.attributes["kb_source_refs"].orEmpty().trim()
            if (refs.isNotBlank()) refs else a.sourceId
        }.trim()

        val coverageComplete = kb.all { a ->
            a.attributes["kb_coverage_complete"]?.toBooleanStrictOrNull() != false &&
                a.attributes["kb_evidence_complete_packet"]?.toBooleanStrictOrNull() != false
        }
        val packName = kb.firstNotNullOfOrNull { it.attributes["knowledge_pack_name"]?.takeIf { name -> name.isNotBlank() } }
            ?: "Selected Knowledge Base"
        return KnowledgeEvidencePacket(
            query = query,
            evidence = evidence,
            sourceIds = sourceIds,
            sourceDetails = sourceDetails,
            coverageComplete = coverageComplete,
            packName = packName
        )
    }

    private fun outputBudget(query: String, packet: KnowledgeEvidencePacket): Int {
        val words = query.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return when {
            packet.evidence.length > 5_000 -> 1_024
            words > 18 -> 896
            else -> 768
        }
    }

    private fun localLooksComplete(answer: String?): Boolean {
        val text = answer?.trim().orEmpty()
        if (text.length < 32) return false
        if (Regex("(?s)<(?:analysis|reasoning|thinking|tool_call|function_call)\\b").containsMatchIn(text)) return false
        val tail = text.takeLast(24).trimEnd()
        if (tail.endsWith(":") || tail.endsWith(",") || tail.endsWith(";")) return false
        return true
    }

    suspend fun answer(
        packet: KnowledgeEvidencePacket,
        localHistory: List<AndroChatMessage>,
        cloudHistory: List<Pair<String, String>>
    ): KnowledgeEngineResult {
        if (!RagFlowMobileRanker.supportsQuery(packet.query, packet.evidence)) {
            Log.w(TAG, "KB_ENGINE_INSUFFICIENT reason=kb_evidence_query_mismatch")
            return KnowledgeEngineResult(
                "I couldn't find sufficiently direct evidence for that question in the selected knowledge base.",
                AndroBaselineRuntime.Provider.LOCAL,
                packet.sourceIds,
                localAttempted = false,
                cloudAttempted = false,
                escalationReason = "kb_evidence_query_mismatch"
            )
        }
        val budget = outputBudget(packet.query, packet)
        val localAnswer = runCatching { local.generate(packet, localHistory, budget) }
            .onFailure { Log.w(TAG, "KB_LOCAL_EXCEPTION ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val localComplete = localLooksComplete(localAnswer)
        val localGrounded = localComplete && RagFlowMobileRanker.supportsQuery(packet.query, localAnswer.orEmpty())
        val escalationReason = when {
            localAnswer.isNullOrBlank() -> "local_generation_failed"
            !localComplete -> "local_answer_incomplete"
            !localGrounded -> "local_answer_query_mismatch"
            !packet.coverageComplete -> "kb_evidence_coverage_incomplete"
            else -> ""
        }

        if (escalationReason.isBlank()) {
            Log.i(TAG, "KB_ENGINE_ACCEPT provider=LOCAL chars=${localAnswer!!.length} sources=${packet.sourceIds.size}")
            return KnowledgeEngineResult(localAnswer, AndroBaselineRuntime.Provider.LOCAL, packet.sourceIds, true, false)
        }

        if (canUseCloud()) {
            val cloudAnswer = runCatching {
                cloud.generate(packet, cloudHistory, localAnswer, escalationReason, budget)
            }.onFailure { Log.w(TAG, "KB_CLOUD_EXCEPTION ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
            if (!cloudAnswer.isNullOrBlank() &&
                localLooksComplete(cloudAnswer) &&
                RagFlowMobileRanker.supportsQuery(packet.query, cloudAnswer)
            ) {
                Log.i(TAG, "KB_ENGINE_ACCEPT provider=CLOUD reason=$escalationReason chars=${cloudAnswer.length} sources=${packet.sourceIds.size}")
                return KnowledgeEngineResult(cloudAnswer, AndroBaselineRuntime.Provider.CLOUD, packet.sourceIds, true, true, escalationReason)
            }
        }

        if (localGrounded && !localAnswer.isNullOrBlank()) {
            Log.w(TAG, "KB_ENGINE_LOCAL_DEGRADED_ACCEPT reason=$escalationReason cloudUnavailable=true")
            return KnowledgeEngineResult(localAnswer, AndroBaselineRuntime.Provider.LOCAL, packet.sourceIds, true, false, escalationReason)
        }

        val fallback = "I couldn't produce a reliable answer from the selected knowledge base with the available evidence."
        Log.w(TAG, "KB_ENGINE_INSUFFICIENT reason=$escalationReason")
        return KnowledgeEngineResult(fallback, AndroBaselineRuntime.Provider.LOCAL, packet.sourceIds, true, false, escalationReason)
    }
}
