package com.example.arfas

/**
 * One semantically searchable knowledge-base chunk.
 *
 * The original ANM pack schema is intentionally preserved.  Optional provenance
 * fields are carried separately so retrieval can de-duplicate, reconstruct adjacent
 * chunks and expose audit metadata without injecting source labels into the LLM text.
 */
data class SopRecord(
    val id: String,
    val category: String,
    val conditionName: String,
    val emergencyTier: EmergencyTier,
    val sopText: String,
    val sourceInfo: String,
    val fileName: String = "",
    val pageNumber: Int = -1,
    val sourceTitle: String = "",
    val portal: String = ""
) {
    val isRedEmergency: Boolean get() = emergencyTier == EmergencyTier.RED_EMERGENCY
    val isYellowUrgent: Boolean get() = emergencyTier == EmergencyTier.YELLOW_URGENT
    val isGreenRoutine: Boolean get() = emergencyTier == EmergencyTier.GREEN_ROUTINE

    val displayLabel: String get() = "[${emergencyTier.label}] $conditionName"

    fun toPromptBlock(maxTextChars: Int = 400): String = buildString {
        appendLine("--- ${emergencyTier.label}: $conditionName ---")
        appendLine(sopText.take(maxTextChars))
        if (sopText.length > maxTextChars) appendLine("[...truncated]")
        appendLine("Source: $sourceInfo")
    }
}

enum class EmergencyTier(val label: String, val dbValue: String) {
    RED_EMERGENCY ("🔴 EMERGENCY",  "RED_EMERGENCY"),
    YELLOW_URGENT ("🟡 URGENT",     "YELLOW_URGENT"),
    GREEN_ROUTINE ("🟢 ROUTINE",    "GREEN_ROUTINE"),
    UNKNOWN       ("⚪ UNKNOWN",    "");

    companion object {
        fun fromDb(value: String): EmergencyTier =
            entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
