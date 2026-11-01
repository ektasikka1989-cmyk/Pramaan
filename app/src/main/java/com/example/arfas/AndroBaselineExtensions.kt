package com.example.arfas

/**
 * Stable extension seam around the single AndroLLM-style conversation.
 * A provider may cheaply decide whether it is relevant before doing expensive
 * retrieval. This is semantic capability admission, not a keyword/domain router.
 */
interface BaselineContextProvider {
    suspend fun shouldAttach(query: String, acceptedHistory: List<AndroChatMessage>): Boolean = true
    suspend fun contextFor(query: String, acceptedHistory: List<AndroChatMessage>): BaselineContextAttachment?
}

data class BaselineContextAttachment(
    val sourceId: String,
    val text: String,
    val priority: Int = 0,
    val attributes: Map<String, String> = emptyMap()
)

fun interface AcceptedTurnObserver {
    suspend fun onAcceptedTurn(userText: String, assistantText: String, provider: AndroBaselineRuntime.Provider)
}

fun interface BaselineResponseValidator {
    suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult
}

data class BaselineValidationResult(
    val accepted: Boolean,
    val reason: String = ""
) {
    companion object {
        val ACCEPT = BaselineValidationResult(true)
        fun reject(reason: String) = BaselineValidationResult(false, reason)
    }
}
