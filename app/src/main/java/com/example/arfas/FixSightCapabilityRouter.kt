package com.example.arfas

/**
 * Legacy compatibility shell.
 *
 * Provider routing is intentionally NOT implemented here anymore. FixSight now uses
 * the semantic freshness classifier plus the detached Gemma capability planner so
 * provider choice is based on meaning and the model knowledge horizon, not topic or
 * phrase tables.
 */
object FixSightCapabilityRouter {
    enum class Capability { LOCAL, EXTERNAL_INFORMATION }

    data class Route(
        val capability: Capability = Capability.LOCAL,
        val reason: String = "dynamic_semantic_routing",
        val requiresFactualVerification: Boolean = false
    ) {
        val requiresExternalInformation: Boolean
            get() = capability == Capability.EXTERNAL_INFORMATION
    }

    @Deprecated("Use SemanticCapabilityContextProvider + detached Gemma planner")
    fun route(query: String): Route = Route()

    @Deprecated("Contextual freshness is resolved semantically")
    fun isLikelyExternalContinuation(query: String): Boolean = false
}
