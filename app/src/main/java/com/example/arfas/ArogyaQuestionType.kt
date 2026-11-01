package com.example.arfas

/**
 * Question type for length instructions (R2). Pure Kotlin, host-testable.
 * Decides the shape of the answer the model is asked for: every step for a
 * procedure, every item for a list, a short conditional fact otherwise, and
 * action-first for urgent questions. English, Hindi and Hinglish keywords.
 */
object ArogyaQuestionType {
    enum class Kind { PROCEDURE, LIST, FACT, URGENT }

    private val URGENT = listOf(
        "emergency", "urgent", "danger", "severe", "khatra", "khatre", "turant", "anm ko turant",
        "तुरंत", "खतरा", "खतरे", "आपात", "गंभीर", "दौरा", "झटके", "नहीं रो रहा", "सांस न",
        "saans nahi", "nahi ro raha", "bleeding heavily", "bahut khoon", "बहुत खून",
        "no movement", "koi harkat", "हरकत न", "unconscious", "behosh", "बेहोश",
        "convulsion", "fits", "daura"
    )
    private val PROCEDURE = listOf(
        "how ", "how to", "kaise", "कैसे", "step", "method", "tarika", "तरीका",
        "vidhi", "विधि", "procedure", "process", "prepare", "banana", "banaye",
        "बनाएं", "बनायें", "count", "ginti", "गिनती", "ginna", "गिनना", "weigh",
        "taul", "तौल", "vajan", "वजन कैसे", "wash", "dhona", "धोना", "saaf",
        "साफ कैसे", "dilana", "दिलाना", "karna chahiye", "karen", "check karein",
        "जांच", "demonstrat", "samjhayen", "समझाएं"
    )
    private val LIST = listOf(
        " which ", " kaun ", "कौन", "methods", "vidhiyan", "विधियाँ",
        "type of", "types of", "list", "suchi", "सूची", "kitne prakar", "kaun kaun",
        "कौन कौन", "names", "naam bata", "नाम बत", "symptoms", "lakshan", "लक्षण",
        "signs of", "causes", "karan", "कारण", "schedule", "anusoochi", "अनुसूची",
        "kab kab", "कब कब", "visits", "khorak", "phases", "stages", "charan",
        "चरण बत", "kinds of", "kismein"
    )

    fun kindOf(query: String): Kind {
        val q = " ${query.lowercase(java.util.Locale.ROOT)} "
        if (URGENT.any { it in q }) return Kind.URGENT
        // The list check comes first: "which methods" asks for items, "use this method" for steps.
        if (LIST.any { it in q }) return Kind.LIST
        if (PROCEDURE.any { it in q }) return Kind.PROCEDURE
        return Kind.FACT
    }

    /**
     * R11 output ceiling per question part: procedures and lists must fit every step/item
     * (~320 words); facts and urgent answers keep the 400-token ceiling (~230 words).
     */
    fun ceilingPerPart(kind: Kind): Int = when (kind) {
        Kind.PROCEDURE, Kind.LIST -> 560
        Kind.FACT, Kind.URGENT -> 400
    }

    /**
     * The last line the model reads (replaces COMPLETE_ANSWER when r2LengthPrompt is on).
     * Matches the question type so it no longer contradicts the LENGTH tiers.
     */
    fun lengthLine(kind: Kind): String {
        val head = "Answer completely in your own words: cover every point the material gives on this question "
        val core = when (kind) {
            Kind.PROCEDURE -> "(this asks HOW to do something: give every numbered step in order, each step " +
                "as its own numbered line; do not merge steps, do not add steps, do not stop early). "
            Kind.LIST -> "(this asks for ITEMS: give one line per item for every item the material gives; " +
                "do not stop after the first items). "
            Kind.FACT -> "(for a \"what is\" question: what it is, its role or purpose, and its key details; " +
                "for a comparison or a \"difference between\" question: first what each one is, then how they " +
                "differ). Keep it short: three to five sentences with the conditions and limits the material gives. "
            Kind.URGENT -> "(this is URGENT: start with the most useful action first, then the details). "
        }
        return head + core + "The app already shows one quoted line above your answer, so do not just repeat that line."
    }
}
