package com.example.arfas

/**
 * Deterministic Hindi → English search terms for retrieval (Batch 2). The model's
 * English rewrite exists but costs a call and can be skipped or poor for short
 * Hindi questions (M1 Q14/Q17 misses); these dictionary terms are added as extra
 * search views so Devanagari and Hinglish questions always search English words
 * too. The question itself is untouched, so the answer stays in Hindi.
 * Pure Kotlin, host-testable. Gated by ArogyaFlags.hindiRewrite.
 */
object ArogyaHindiTerms {
    /** Devanagari or Hinglish key → English document terms. Order: longest match wins. */
    private val TERMS: List<Pair<String, String>> = listOf(
        "नवजात शिशु" to "newborn",
        "navjaat shishu" to "newborn",
        "navjat shishu" to "newborn",
        "नवजात" to "newborn",
        "navjat" to "newborn",
        "navjaat" to "newborn",
        "गर्भवती महिला" to "pregnant woman",
        "pregnant woman" to "pregnant woman",
        "गर्भवती" to "pregnant",
        "garbhvati" to "pregnant",
        "garbhavati" to "pregnant",
        "गर्भावस्था" to "pregnancy",
        "स्तनपान" to "breastfeeding",
        "stanpan" to "breastfeeding",
        "doodh pilana" to "breastfeeding",
        "दूध पिलाना" to "breastfeeding",
        "खतरे के लक्षण" to "danger signs referral",
        "khatre ke lakshan" to "danger signs referral",
        "खतरा" to "danger",
        "khatra" to "danger",
        "लक्षण" to "signs symptoms",
        "lakshan" to "signs symptoms",
        "सांस" to "breathing",
        "saans" to "breathing",
        "दस्त" to "diarrhoea",
        "dast" to "diarrhoea",
        "loose motion" to "diarrhoea",
        "बुखार" to "fever temperature",
        "bukhar" to "fever temperature",
        "खुराक" to "dose",
        "khurak" to "dose",
        "khuraq" to "dose",
        "जिंक" to "zinc",
        "jink" to "zinc",
        "zinc" to "zinc",
        "टीका" to "immunization vaccine",
        "tika" to "immunization vaccine",
        "teeka" to "immunization vaccine",
        "teekakaran" to "immunization",
        "जांच" to "checkup examination anc",
        "jaanch" to "checkup examination anc",
        "जाँच" to "checkup examination anc",
        "कितनी बार" to "how many schedule",
        "kitni baar" to "how many schedule",
        "कब कब" to "schedule when",
        "kab kab" to "schedule when",
        "कैसे बनाएं" to "how to prepare method",
        "kaise banaye" to "how to prepare method",
        "कैसे बनायें" to "how to prepare method",
        "कैसे दें" to "how to give",
        "kaise dena" to "how to give",
        "कैसे" to "how",
        "kaise" to "how",
        "क्या है" to "what is",
        "kya hai" to "what is",
        "क्या हैं" to "what are",
        "कौन कौन" to "which names list",
        "kaun kaun" to "which names list",
        "कितने" to "how many",
        "kitne" to "how many",
        "कितना" to "how much",
        "kitna" to "how much",
        "बच्चे को" to "child baby",
        "bachche ko" to "child baby",
        "bacche ko" to "child baby",
        "बच्चा" to "child",
        "bachcha" to "child",
        "शिशु" to "infant",
        "shishu" to "infant",
        "माँ" to "mother",
        "maa" to "mother",
        "रक्त" to "blood",
        "rakt" to "blood",
        "खून" to "blood",
        "khoon" to "blood",
        "एनीमिया" to "anaemia",
        "anemia" to "anaemia",
        "आयरन" to "iron ifa",
        "iron" to "iron ifa",
        "गोली" to "tablet",
        "goli" to "tablet",
        "सिरप" to "syrup",
        "sirap" to "syrup",
        "अस्पताल" to "hospital facility referral",
        "aspatal" to "hospital facility referral",
        "रेफर" to "referral",
        "refer" to "referral",
        "वजन" to "weight",
        "vajan" to "weight",
        "वज़न" to "weight",
        "टीकाकरण" to "immunization",
        "मलेरिया" to "malaria",
        "malaria" to "malaria",
        "तपेदिक" to "tuberculosis",
        "दाई" to "midwife dai",
        "आशा" to "asha",
        "एएनएम" to "anm",
        "सफाई" to "clean hygiene",
        "safai" to "clean hygiene",
        "पानी" to "water",
        "paani" to "water",
        "ओआरएस" to "ors",
        "ors" to "ors",
    ).sortedByDescending { it.first.length }

    /** True when the question has Devanagari or reads as Hinglish. */
    fun needsViews(question: String): Boolean =
        question.any { it.code in 0x0900..0x097F } || CoreHealthFacts.isHinglish(question)

    /**
     * English search views for a Hindi/Hinglish question (at most [maxWords] terms
     * in one view). Empty when nothing matched — the normal path is unchanged.
     */
    fun views(question: String, maxWords: Int = 12): List<String> {
        val q = " ${question.lowercase(java.util.Locale.ROOT)} "
        val found = LinkedHashSet<String>()
        for ((key, en) in TERMS) {
            if (key in q) en.split(' ').forEach { found += it }
            if (found.size >= maxWords) break
        }
        return if (found.isEmpty()) emptyList() else listOf(found.take(maxWords).joinToString(" "))
    }
}
