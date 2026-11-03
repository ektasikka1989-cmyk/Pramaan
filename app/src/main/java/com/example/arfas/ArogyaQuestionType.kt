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
     * Part B2 output ceiling per question part, routed through ArogyaBudget:
     * phone FACT 600, PROCEDURE/LIST/URGENT 800 (English); cloud 1200/1600;
     * Hindi x2.5, Hinglish x1.3. Procedures and lists must fit every step/item.
     */
    fun ceilingPerPart(kind: Kind): Int = ArogyaBudget.askSop(kind, "", cloud = false)

    /** Same, with the query's language and the engine that will write the answer. */
    fun ceilingPerPart(kind: Kind, query: String, cloud: Boolean, promptTokens: Int = 0,
                       contextWindow: Int = if (cloud) ArogyaBudget.CLOUD_CONTEXT else ArogyaBudget.PHONE_CONTEXT): Int =
        ArogyaBudget.askSop(kind, query, cloud, promptTokens, contextWindow)

    /**
     * The last line the model reads (replaces COMPLETE_ANSWER when r2LengthPrompt is on).
     * Matches the question type so it no longer contradicts the LENGTH tiers.
     * [query] adds the dose rule: a dose asked for a named age group must not list others (Q11).
     * Part B3: five labelled parts (Answer / Why it matters / What to do / When to tell the
     * ANM / Common mistakes to avoid), Hindi labels when the question is in Devanagari, and
     * per-engine sentence totals (six to ten on the phone, eight to fourteen from the cloud).
     */
    fun lengthLine(kind: Kind, query: String = "", cloud: Boolean = false): String {
        val head = "Answer completely in your own words: cover every point the material gives on this question "
        val doseForGroup = query.isNotBlank() && ArogyaDoseCheck.isDoseQuestion(query) &&
            ArogyaDoseCheck.askedGroup(query) != ArogyaDoseCheck.Group.UNKNOWN
        val core = when (kind) {
            Kind.PROCEDURE -> "(this asks HOW to do something: give every numbered step in order, each step " +
                "as its own numbered line with what to watch for while doing it; do not merge steps, do not add steps, do not stop early). "
            Kind.LIST -> "(this asks for ITEMS: give one line per item for every item the material gives, " +
                "each with a one-line explanation; do not stop after the first items). "
            Kind.FACT -> if (doseForGroup) "(this asks a DOSE for one age group: give only the dose for the asked age group; " +
                "do not list other age groups. Keep it short: the dose, how to give it, and for how long). "
            else "(for a \"what is\" question: what it is, its role or purpose, and its key details; " +
                "for a comparison or a \"difference between\" question: first what each one is, then how they " +
                "differ). "
            Kind.URGENT -> "(this is URGENT: start with the most useful action first, then the steps and the referral conditions). "
        }
        // Part B3 shape: five labelled parts; the card renders each label as a small heading.
        // A Hindi question gets the Hindi labels so the sections still parse.
        val hindi = ArogyaBudget.langOf(query) == "hi"
        val l = if (hindi) listOf("'उत्तर:'", "'क्यों ज़रूरी:'", "'क्या करें:'", "'ANM को कब बताएँ:'", "'आम गलतियाँ:'")
        else listOf("'Answer:'", "'Why it matters:'", "'What to do:'", "'When to tell the ANM:'", "'Common mistakes to avoid:'")
        val total = if (cloud) "eight to fourteen" else "six to ten"
        val shape = "Shape the answer in labelled parts, each part on its own lines starting with its label and a colon: " +
            "${l[0]} with the direct answer in two to three sentences; " +
            "${l[1]} with one to two sentences on why it matters; " +
            "${l[2]} with the numbered steps, or one line per item, only from the material " +
            "(skip this part when the material gives no steps or items); " +
            "${l[3]} with the referral conditions, only when the material gives them " +
            "(otherwise skip this part); " +
            "${l[4]} with the mistakes the material warns about (skip when it gives none). " +
            "Write $total sentences in total when the material supports it; " +
            "never a single sentence when the material has more. "
        return head + core + shape + "The app already shows one quoted line above your answer, so do not just repeat that line."
    }

    /**
     * Item 4 "More detail": a fuller answer from the SAME passages (no new search), up to
     * the model ceiling. PROCEDURE-style: every step, item, condition and limit, each on its
     * own line, in the same labelled parts the card renders (Part B3 adds the mistakes part).
     */
    fun detailLine(): String {
        return "Give a fuller answer from the same material: every step, item, condition and limit it gives " +
            "on this question, each on its own numbered or bulleted line; do not merge steps and do not stop early. " +
            "Shape it in labelled parts, each part on its own lines starting with its label and a colon: " +
            "'Answer:' with the full direct answer (five or more sentences when the material supports it); " +
            "'Why it matters:' with why it matters; " +
            "'What to do:' with the numbered steps, or one line per item, only from the material; " +
            "'When to tell the ANM:' with one line, only when the material gives referral conditions; " +
            "'Common mistakes to avoid:' with the mistakes the material warns about (skip when it gives none). " +
            "When the question is in Hindi, use the Hindi labels 'उत्तर:', 'क्यों ज़रूरी:', 'क्या करें:', " +
            "'ANM को कब बताएँ:', 'आम गलतियाँ:' instead. " +
            "The app already shows one quoted line above your answer, so do not just repeat that line."
    }
}
