package com.example.arfas

/**
 * Capability preflight for turns that may require capabilities outside the local model.
 * Implementations may combine semantic routing with narrow deterministic guards for
 * explicit capability requests (for example current/live or explicit web retrieval).
 * Deterministic guards must not replace source ownership, safety, visual or grounding
 * decisions. Additional checks are defaulted so existing implementations remain source compatible.
 */
fun interface BaselineCapabilityDecider {
    suspend fun requiresExternalCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean

    /** True when this semantic domain must not be answered without verified grounding. */
    suspend fun requiresVerifiedGrounding(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /** True when the requested procedure is semantically high-risk and should be escalated. */
    suspend fun requiresSafetyEscalation(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * True when a local answer may contain objectively checkable factual claims that
     * should be verified by the stronger cloud model before the answer is committed.
     * This is query-type admission only; semantic similarity is never treated as a
     * factual-confidence score.
     */
    suspend fun requiresFactualVerification(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * True when answering the current request depends on actually seeing the current
     * camera frame. This is semantic capability admission, not a keyword list.
     */
    suspend fun requiresVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * True when the current camera frame is relevant evidence for the user's present
     * request even if the wording does not explicitly ask to look/see/inspect. This is
     * intentionally separate from visualRequired so physical troubleshooting can use
     * an already-enabled camera without requiring magic words.
     */
    suspend fun isCurrentCameraEvidenceRelevant(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * True when a visual request depends on fine detail such as small text, tiny
     * markings, indicators or other details that benefit from a larger inference
     * image. This chooses image fidelity only; it does not decide whether vision
     * itself is required.
     */
    suspend fun requiresHighDetailVisualCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * True when the user is asking for broad scene coverage / an inventory of
     * multiple visible items rather than one focused object or one diagnostic clue.
     * This is semantic scope only; it does not authorize AR or Cloud.
     */
    suspend fun requiresBroadVisualInventoryCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /** True when the visual request requires spatial localization of a target.
     * This may support a verbal location answer and does NOT itself authorize AR rendering. */
    suspend fun requiresSpatialLocalizationCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * Per-turn permission for an AR/onscreen overlay side effect. This must represent
     * the user's requested output action (point/highlight/mark/anchor/overlay), not merely
     * the fact that the model can infer a target's position. Uncertain intent defaults false.
     */
    suspend fun requiresArOverlayCapability(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = false

    /**
     * False means semantic orchestration is unavailable and Audio-only must not silently
     * fall through to unrestricted local inference. The runtime may use cloud when available
     * or return a controlled degraded response.
     */
    suspend fun isOperational(): Boolean = true
}
