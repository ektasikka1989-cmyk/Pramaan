package com.example.arfas

import android.util.Log

/**
 * Part B2: one token budget for every ArogyaDoot generation.
 *
 * Each feature has a phone base and a cloud (Muse Spark) base. The output
 * language scales the base: Hindi/Devanagari x2.5, Hinglish x1.3, English x1.
 * The result is capped by the context window minus the prompt tokens, and every
 * call logs AROGYA_BUDGET so the manual checklist can read the real numbers.
 * Pure Kotlin except the log line (LogStub on the host); host-tested.
 */
object ArogyaBudget {
    private const val TAG = "ArogyaBudget"
    const val PHONE_CONTEXT = 4096
    const val CLOUD_CONTEXT = 32000
    const val MIN_OUTPUT = 256

    /** Feature to (phone base, cloud base). */
    private val BASES = mapOf(
        "ask_sop_fact" to (600 to 1200),
        "ask_sop_procedure" to (800 to 1600),
        "ask_sop_list" to (800 to 1600),
        "ask_sop_urgent" to (800 to 1600),
        "ask_sop_detail" to (1000 to 2400),
        "check_online" to (800 to 1600),
        "family_explain" to (450 to 900),
        "anm_message" to (450 to 900),
        "card_read" to (900 to 1500),
        "person_extract" to (900 to 1500),
        "voice_stock" to (400 to 900),
        "medicine_scan" to (400 to 900),
        "organise_notes" to (700 to 1500),
        "finish_visit" to (900 to 1500)
    )

    /** Base output tokens for a feature before the language multiplier. Unknown features keep the old 400/800. */
    fun baseFor(feature: String, cloud: Boolean): Int {
        val (phone, cloudBase) = BASES[feature] ?: (400 to 800)
        return if (cloud) cloudBase else phone
    }

    private val HINGLISH_MARKERS = listOf(
        "kaise", "kya", "kaun", "kitna", "kitni", "kitne", "nahi", "nahin",
        "chahiye", "karo", "karein", "kare", "hai", "doodh", "dudh", "bukhar",
        "tika", "dast", "garbhvati", "saans", "peeliya", "bahu", "baccha",
        "bachcha", "bache", "maa", "rok", "ilaaj", "dawai"
    )

    /** "hi" when Devanagari is present, "hinglish" for roman Hindi markers, else "en". */
    fun langOf(text: String): String {
        if (text.any { it in '\u0900'..'\u097F' }) return "hi"
        val words = text.lowercase(java.util.Locale.ROOT).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        if (HINGLISH_MARKERS.any { it in words }) return "hinglish"
        return "en"
    }

    fun multiplier(outputLanguage: String): Double = when (outputLanguage.lowercase(java.util.Locale.ROOT)) {
        "hi", "hindi" -> 2.5
        "hinglish", "hi-en", "hien" -> 1.3
        else -> 1.0
    }

    /** Rough prompt size: ~4 characters per token for the scripts in use. */
    fun estimatePromptTokens(chars: Int): Int = (chars / 4).coerceAtLeast(0)

    /**
     * Output tokens for one generation: base x language multiplier, capped by
     * the context window minus the prompt. Always logs AROGYA_BUDGET.
     */
    fun tokens(
        base: Int, outputLanguage: String, promptTokens: Int = 0,
        contextWindow: Int = PHONE_CONTEXT, feature: String = "", engine: String = ""
    ): Int {
        val desired = (base * multiplier(outputLanguage)).toInt()
        val available = (contextWindow - promptTokens).coerceAtLeast(MIN_OUTPUT)
        val out = desired.coerceAtMost(available).coerceAtLeast(MIN_OUTPUT)
        val capped = out < desired
        Log.i(TAG, "AROGYA_BUDGET engine=$engine feature=$feature lang=$outputLanguage tokens=$out capped=$capped")
        return out
    }

    /** Ask SOP ceiling for one question part: kind base x the query's language, capped by the context left. */
    fun askSop(
        kind: ArogyaQuestionType.Kind, query: String, cloud: Boolean,
        promptTokens: Int = 0, contextWindow: Int = if (cloud) CLOUD_CONTEXT else PHONE_CONTEXT
    ): Int {
        val feature = "ask_sop_" + kind.name.lowercase(java.util.Locale.ROOT)
        return tokens(baseFor(feature, cloud), langOf(query), promptTokens, contextWindow, feature, if (cloud) "cloud" else "phone")
    }

    /** "More detail" ceiling: PROCEDURE-style, up to the model ceiling. */
    fun askSopDetail(query: String, cloud: Boolean, promptTokens: Int = 0,
                     contextWindow: Int = if (cloud) CLOUD_CONTEXT else PHONE_CONTEXT): Int =
        tokens(baseFor("ask_sop_detail", cloud), langOf(query), promptTokens, contextWindow,
            "ask_sop_detail", if (cloud) "cloud" else "phone")
}
