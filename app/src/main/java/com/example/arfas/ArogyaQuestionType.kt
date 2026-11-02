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
        "type of", "types of", "list", "suchi", "सूची", "kaun kaun",
        "कौन कौन", "names", "naam bata", "नाम बत", "symptoms", "lakshan", "लक्षण",
        "signs of", "causes", "karan", "कारण", "schedule", "anusoochi", "अनुसूची",
        "kab kab", "कब कब", "visits", "khorak", "phases", "stages", "charan",
        "चरण बत", "kinds of", "kismein"
    )
    /** "What are the danger signs" asks FOR the signs: a complete list, not action-first. */
    private val DANGER_SIGNS = listOf(
        "danger sign", "danger signs", "खतरे के लक्षण", "खतरे के संकेत",
        "khatre ke lakshan", "khatre ke sanket"
    )
    /** "How many/much" asks for one number, never steps ("how to" still means steps). */
    private val HOW_MANY = listOf(
        "how many", "how much", "kitna", "kitni", "kitne", "कितना", "कितनी", "कितने"
    )

    fun kindOf(query: String): Kind {
        val q = " ${query.lowercase(java.util.Locale.ROOT)} "
        // Review order. Three constraints force this exact sequence (dose > list >
        // procedure > dose would be a cycle): "which methods" is a LIST even though
        // "methods" contains a procedure word, while "ORS kaise banaye" is steps even
        // though ORS is a dose word. So list-shaped wins over procedure words, and
        // procedure verbs win over the generic dose test.
        if (DANGER_SIGNS.any { it in q }) return Kind.LIST
        if (URGENT.any { it in q }) return Kind.URGENT
        if (HOW_MANY.any { it in q }) return Kind.FACT
        if (" which " in q && " for " in q && ArogyaDoseCheck.askedGroup(query) != ArogyaDoseCheck.Group.UNKNOWN) return Kind.FACT
        if (LIST.any { it in q }) return Kind.LIST
        if (PROCEDURE.any { it in q }) return Kind.PROCEDURE
        if (ArogyaDoseCheck.isDoseQuestion(query)) return Kind.FACT
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
     * [query] adds the dose rule: a dose asked for a named age group must not list others (Q11).
     */
    fun lengthLine(kind: Kind, query: String = ""): String {
        val head = "Answer completely in your own words: cover every point the material gives on this question "
        val doseForGroup = query.isNotBlank() && ArogyaDoseCheck.isDoseQuestion(query) &&
            ArogyaDoseCheck.askedGroup(query) != ArogyaDoseCheck.Group.UNKNOWN
        val core = when (kind) {
            Kind.PROCEDURE -> "(this asks HOW to do something: give every numbered step in order, each step " +
                "as its own numbered line; do not merge steps, do not add steps, do not stop early). "
            Kind.LIST -> "(this asks for ITEMS: give one line per item for every item the material gives; " +
                "do not stop after the first items). "
            Kind.FACT -> if (doseForGroup) "(this asks a DOSE for one age group: give only the dose for the asked age group; " +
                "do not list other age groups. Keep it short: the dose, how to give it, and for how long). "
            else "(for a \"what is\" question: what it is, its role or purpose, and its key details; " +
                "for a comparison or a \"difference between\" question: first what each one is, then how they " +
                "differ). "
            Kind.URGENT -> "(this is URGENT: start with the most useful action first, then the details). "
        }
        // Item 4 shape: up to three labelled parts; the card renders each label as a small heading.
        val shape = "Shape the answer in up to three parts, each part on its own lines starting with its label and a colon: " +
            "'Answer:' with the direct answer in two to five sentences (three to five for a fact question; " +
            "never a single sentence when the material has more); " +
            "'What to do:' with the numbered steps, or one line per item, only from the material " +
            "(skip this part when the material gives no steps or items); " +
            "'When to tell the ANM:' with one line, only when the material gives referral conditions " +
            "(otherwise skip this part). "
        return head + core + shape + "The app already shows one quoted line above your answer, so do not just repeat that line."
    }

    /**
     * Item 4 "More detail": a fuller answer from the SAME passages (no new search), up to
     * the model ceiling. PROCEDURE-style: every step, item, condition and limit, each on its
     * own line, in the same three labelled parts the card renders.
     */
    fun detailLine(): String {
        return "Give a fuller answer from the same material: every step, item, condition and limit it gives " +
            "on this question, each on its own numbered or bulleted line; do not merge steps and do not stop early. " +
            "Shape it in up to three parts, each part on its own lines starting with its label and a colon: " +
            "'Answer:' with the full direct answer (five or more sentences when the material supports it); " +
            "'What to do:' with the numbered steps, or one line per item, only from the material; " +
            "'When to tell the ANM:' with one line, only when the material gives referral conditions. " +
            "The app already shows one quoted line above your answer, so do not just repeat that line."
    }
}
