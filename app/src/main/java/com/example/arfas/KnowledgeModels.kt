package com.example.arfas

/** Domain-neutral contract for a selected knowledge base turn. */
data class KnowledgeEvidencePacket(
    val query: String,
    val evidence: String,
    val sourceIds: List<String>,
    val sourceDetails: String,
    val coverageComplete: Boolean,
    val packName: String = "Selected Knowledge Base"
)

data class KnowledgeEngineResult(
    val text: String,
    val provider: AndroBaselineRuntime.Provider,
    val sourceIds: List<String>,
    val localAttempted: Boolean,
    val cloudAttempted: Boolean,
    val escalationReason: String = ""
)
