package com.example.arfas

/**
 * Topic-independent confirmation for requests that explicitly require a
 * post-2024/current answer.
 *
 * This is intentionally not a topic keyword list. It only recognizes temporal
 * scope (year/date/currentness) and leaves the semantic router responsible for
 * topic and capability interpretation.
 */
object FreshnessTemporalGuard {
    private const val KNOWLEDGE_HORIZON_YEAR = 2024

    private val explicitYear = Regex("\\b(19\\d{2}|20\\d{2}|21\\d{2})\\b")
    private val currentState = Regex(
        "(?i)\\b(?:current|currently|latest|recent|recently|today|tonight|this\\s+(?:week|month|year)|" +
            "right\\s+now|as\\s+of\\s+now|up[- ]?to[- ]?date|newest|presently)\\b"
    )
    private val futureState = Regex(
        "(?i)\\b(?:after|since|post)[ -]?(?:2024|20(?:2[5-9]|[3-9]\\d)|21\\d{2})\\b"
    )
    /**
     * Information that only exists live: it changes by the hour or day, so no offline model or
     * document can hold it (weather, news, market prices, scores, schedules in progress).
     * These are kinds of live data, not topics; "what is a forecast" style definitions are excluded.
     */
    private val liveData = Regex(
        "(?i)\\b(?:weather|forecast|temperature\\s+(?:in|at|of|today|now)|rain(?:fall)?\\s+(?:in|today|tomorrow)|" +
            "humidity|aqi|air\\s+quality|news|headlines?|breaking|stock\\s+price|share\\s+price|" +
            "(?:price|rate)\\s+of\\s+(?:gold|silver|petrol|diesel|bitcoin|crude)|(?:gold|silver|petrol|diesel|bitcoin)\\s+(?:price|rate)|" +
            "exchange\\s+rate|dollar\\s+rate|sensex|nifty|live\\s+score|score\\s+of|match\\s+(?:result|score|today)|" +
            "who\\s+won|election\\s+results?|traffic\\s+(?:in|on|at|now|today)|flight\\s+status|train\\s+status|pnr\\s+status|" +
            "tomorrow|yesterday|this\\s+weekend|next\\s+(?:week|month))\\b"
    )
    private val definitional = Regex("(?i)^\\s*(?:what\\s+(?:is|are|does)\\s+(?:a|an|the)?\\s*(?:weather|forecast|news|exchange\\s+rate|aqi)(?:\\s+(?:forecast|report|rate))?\\s*(?:mean)?\\??\\s*$|define\\b|meaning\\s+of\\b)")

    private val creativeOrTransform = Regex(
        "(?i)^\\s*(?:write|draft|compose|create|invent|imagine|roleplay|rewrite|rephrase|translate|" +
            "proofread|polish|generate|make\\s+up)\\b"
    )

    fun requiresFreshVerification(query: String): Boolean {
        val q = query.trim()
        if (q.isBlank() || creativeOrTransform.containsMatchIn(q)) return false
        if (currentState.containsMatchIn(q) || futureState.containsMatchIn(q)) return true
        if (liveData.containsMatchIn(q) && !definitional.containsMatchIn(q)) return true
        return explicitYear.findAll(q).any { it.value.toInt() > KNOWLEDGE_HORIZON_YEAR }
    }
}
