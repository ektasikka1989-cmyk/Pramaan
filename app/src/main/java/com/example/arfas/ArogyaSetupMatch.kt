package com.example.arfas

/**
 * Setup wizard file matching (pure, host-tested): which Downloads file installs what.
 * The wizard looks for these names; anything else is ignored.
 */
object ArogyaSetupMatch {
    enum class Kind { LLAMA, WHISPER, GUIDE_V2, GUIDE_V1 }

    const val GUIDE_V1_NAME = "ASHA_Health_Guide.db"
    const val GUIDE_V2_NAME = "ASHA_Health_Guide_v2.db"
    const val ASSET_GUIDE_V2 = "guide/ASHA_Health_Guide_v2.db"

    fun classify(displayName: String): Kind? {
        val n = displayName.lowercase()
        if (n == GUIDE_V2_NAME.lowercase()) return Kind.GUIDE_V2
        if (n == GUIDE_V1_NAME.lowercase()) return Kind.GUIDE_V1
        if (n.endsWith(".gguf")) return Kind.LLAMA
        if ((n.contains("whisper") || n.startsWith("ggml")) && (n.endsWith(".bin") || n.endsWith(".onnx"))) return Kind.WHISPER
        return null
    }

    /** Human fix when a file is missing (shown in the wizard; Hindi + English at the call site). */
    fun missingFix(kind: Kind): String = when (kind) {
        Kind.LLAMA -> "Put the Llama .gguf file in Downloads (e.g. Llama-3.2-3B-Instruct-Q4_K_M.gguf), then tap Retry."
        Kind.WHISPER -> "Put the Whisper model (ggml-small.bin) in Downloads, then tap Retry."
        Kind.GUIDE_V2 -> "Put ASHA_Health_Guide_v2.db in Downloads, then tap Retry — or use the copy bundled with the app."
        Kind.GUIDE_V1 -> "Put ASHA_Health_Guide.db in Downloads, then tap Retry."
    }
}
