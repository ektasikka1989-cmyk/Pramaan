package com.example.arfas

/**
 * Shared clinical text utilities for ArogyaDoot: normalisation (Devanagari digits, Hindi/Hinglish
 * number words), a small bilingual clinical concept lexicon, and retrieval expansion.
 *
 * The lexicon is used in three places:
 *  1. ArogyaDangerSigns  – recognising symptoms written in English, Hindi or Hinglish.
 *  2. ArogyaClinicalGrounding – checking a Hindi/Hinglish answer against English evidence at the
 *     concept level (lexical overlap cannot work across scripts).
 *  3. Retrieval – adding English clinical terms to a Hindi/Hinglish question, so the full-text
 *     lane (which runs when the multilingual embedder is not installed) can find English passages.
 *
 * It is a deliberately small, reviewable list, not a translation system. Additions should be
 * reviewed by someone fluent in field Hindi and the ASHA modules.
 */
object ArogyaClinicalText {

    private val HINDI_NUMBERS = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhah" to 6, "chhe" to 6, "che" to 6, "chah" to 6, "saat" to 7, "sat" to 7, "aath" to 8, "aat" to 8,
        "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12, "terah" to 13, "chaudah" to 14, "pandrah" to 15,
        "solah" to 16, "satrah" to 17, "atharah" to 18, "unnis" to 19, "bees" to 20, "bis" to 20,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "सात" to 7,
        "आठ" to 8, "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "तेरह" to 13, "चौदह" to 14, "पंद्रह" to 15,
        "बीस" to 20,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fourteen" to 14, "twenty" to 20, "a" to 1, "an" to 1
    )
    private const val UNIT_WORDS = "din|दिन|days?|day|hafte|hafta|haftey|हफ्ते|हफ़्ते|सप्ताह|weeks?|mahine|mahina|महीने|महीना|months?"

    /** Lower-case, Devanagari digits to ASCII, number words before a time unit to digits, spacing. */
    fun normalise(input: String): String {
        var t = input.lowercase()
            .map { if (it in '०'..'९') ('0'.code + (it.code - '०'.code)).toChar() else it }
            .joinToString("")
            .replace('’', '\'').replace('‘', '\'')
            .replace(Regex("[\\u200b-\\u200d]"), "")
        // Number words only when a time unit follows ("saat din", "one week"): "saath" (with) and
        // "do" (do/give) are never converted on their own.
        t = Regex("(?<![\\p{L}\\p{M}])(\\p{L}[\\p{L}\\p{M}]*)\\s+(?=(?:$UNIT_WORDS)(?![\\p{L}]))").replace(t) { m ->
            HINDI_NUMBERS[m.groupValues[1]]?.let { "$it " } ?: m.value
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun isDevanagari(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it in 'ऀ'..'ॿ' } * 2 >= letters.length
    }

    /**
     * Clinical concepts. Each concept has English retrieval terms (as the ASHA modules write them)
     * and recognition patterns in English, Devanagari Hindi and Romanised Hinglish.
     */
    data class Concept(val id: String, val english: List<String>, val pattern: Regex)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val concepts: List<Concept> = listOf(
        Concept("fever", listOf("fever", "temperature", "hot to touch"),
            rx("\\bfever(?:ish)?\\b|\\bpyrexia\\b|\\bhigh temp|\\bhot to (?:the )?touch|\\bfeels? (?:very )?hot|\\bbu?khaa?r\\b|बुखार|ज्वर|\\btap raha|तप रहा|गरम लग|गर्म लग|\\bgaram\\b")),
        Concept("hypothermia", listOf("hypothermia", "cold to touch", "keep the baby warm"),
            rx("hypotherm|\\bcold to (?:the )?touch|\\bfeels? cold|\\bthand[aie]\\b|ठंड[ाेी]|ठंडा")),
        Concept("temperature", listOf("temperature", "thermometer"),
            rx("\\btemp(?:erature)?\\b|thermometer|\\btapmaan\\b|तापमान|थर्मामीटर")),
        Concept("feeding", listOf("breastfeeding", "feeding", "sucking"),
            rx("\\bfeed|breast ?fe[de]|\\bfed\\b|\\bsuck|stanpan|स्तनपान|\\bdoodh\\b|\\bdudh\\b|दूध")),
        Concept("convulsion", listOf("convulsions", "fits"),
            rx("convuls|\\bfits?\\b|seizure|\\bjhatk|झटक|\\bdaur[ae]\\b|दौर[ाे]|\\bmirgi|मिर्गी")),
        Concept("breathing", listOf("breathing", "fast breathing", "chest indrawing"),
            rx("breath|\\bsaa?ns\\b|सांस|साँस|chest in-?drawing|pasli|पसली")),
        Concept("lethargy", listOf("lethargic", "unconscious", "movement only when stimulated"),
            rx("letharg|unconscious|\\blimp\\b|floppy|\\bsust\\b|सुस्त|behosh|बेहोश|no movement|not moving|हिल(?:-डुल)? नहीं")),
        Concept("jaundice", listOf("jaundice", "yellow palms and soles"),
            rx("jaundice|icter|\\bpeeli?ya\\b|\\bpiliya\\b|पीलिया|yellow (?:palms|soles|skin|eyes)|पील[ाी]")),
        Concept("cord", listOf("umbilical cord", "umbilicus"),
            rx("umbilic|\\bcord\\b|\\bnaal\\b|\\bnabhi\\b|नाल|नाभि")),
        Concept("bleeding", listOf("bleeding", "excessive bleeding", "heavy bleeding"),
            rx("bleed|haemorrhage|hemorrhage|\\bkhoo?n\\b|खून|रक्तस्राव|\\bpads?\\b")),
        Concept("discharge", listOf("foul smelling discharge", "discharge"),
            rx("discharge|\\bsraav\\b|स्राव|badbu|बदबू")),
        Concept("referral", listOf("refer", "referral", "health facility", "hospital"),
            rx("\\brefer|referral|hospital|health (?:facility|centre|center)|\\bphc\\b|\\bchc\\b|aspatal|अस्पताल|रेफर|रेफ़र|स्वास्थ्य केंद्र|\\banm\\b")),
        Concept("urgent", listOf("immediately", "urgently", "danger sign"),
            rx("immediate|urgent|at once|right away|without delay|danger sign|\\bturant\\b|तुरंत|तत्काल|फौरन|खतरे")),
        Concept("newborn", listOf("newborn", "baby", "young infant"),
            rx("new ?born|neonat|\\binfant\\b|\\bbaby\\b|\\bnavjaa?t\\b|नवजात|शिशु|\\bshishu\\b|\\bbach+[ae]|\\bbacc?h[ae]|बच्च")),
        Concept("mother", listOf("mother", "woman after delivery", "postnatal"),
            rx("\\bmother|\\bmaa\\b|\\bmaan\\b|माँ|मां|माता|प्रसूता|postnatal|post-?partum|after delivery|प्रसव के बाद")),
        Concept("pregnancy", listOf("pregnancy", "pregnant woman", "antenatal"),
            rx("pregnan|antenatal|\\banc\\b|garbh|गर्भ")),
        Concept("vaccine", listOf("immunisation", "vaccine", "vaccination"),
            rx("vaccin|immuni[sz]|\\bteeka|\\btika\\b|टीक")),
        Concept("diarrhoea", listOf("diarrhoea", "loose stools", "ORS"),
            rx("diarrh|loose (?:stool|motion)|\\bdast\\b|दस्त|\\bors\\b")),
        Concept("vomiting", listOf("vomiting", "vomits everything"),
            rx("vomit|\\bult[iy]\\b|उल्टी")),
        Concept("weight", listOf("weight", "low birth weight"),
            rx("\\bweight|\\bwazan|\\bvajan|वज़न|वजन")),
        Concept("nutrition", listOf("complementary feeding", "nutrition", "food"),
            rx("complementary|nutrition|\\bdiet\\b|\\bkhana\\b|खाना|पोषण|aahar|आहार")),
        Concept("cough", listOf("cough", "pneumonia"),
            rx("cough|pneumon|\\bkhaa?nsi\\b|खांसी|खाँसी")),
        Concept("warmth", listOf("keep warm", "skin to skin", "kangaroo mother care"),
            rx("skin[ -]to[ -]skin|kangaroo|\\bkmc\\b|keep (?:the |him |her )?(?:baby |young infant |newborn |infant |child )?warm|गर्म रख|garam rakh")),
        Concept("danger", listOf("danger signs", "refer urgently"),
            rx("danger sign|warning sign|\\bkhatr[ae]\\b|खतर[ेा]|गंभीर लक्षण")),
        Concept("exclusive", listOf("exclusive breastfeeding", "only breast milk", "no water"),
            rx("exclusive(?:ly)? breast|only breast ?milk|\\bsirf (?:maa ka )?(?:doodh|dudh|stanpan)|केवल (?:माँ का |मां का )?(?:दूध|स्तनपान)|सिर्फ (?:माँ का |मां का )?दूध")),
        Concept("technique", listOf("position and attachment"),
            rx("attach|position|latch|\\bsahi tarik|सही तरीक|kaise pila|kaise karaye|कैसे पिला")),
        Concept("water", listOf("water"),
            rx("\\bwater\\b|\\bpaani\\b|\\bpani\\b|पानी|जल ")),
        Concept("bathing", listOf("bathing", "bath"),
            rx("\\bbath|\\bsponge|\\bnahla|\\bnahana|नहला|नहाना|नहलाएं|स्नान")),
        Concept("iron", listOf("iron folic acid", "IFA tablets", "anaemia"),
            rx("\\bifa\\b|\\biron\\b|folic|anaemi|anemi|\\bkhoon ki kami|खून की कमी|आयरन")),
    )

    fun conceptsIn(text: String): Set<String> {
        val t = normalise(text)
        return concepts.filter { it.pattern.containsMatchIn(t) }.map { it.id }.toSet()
    }

    /**
     * Retrieval view: the question plus the English terms of every concept it mentions. Only
     * adds terms when the question is (partly) Hindi/Hinglish or the concept was written in a
     * non-English form; an English question is returned unchanged.
     */
    private val FUNCTION_WORDS = setOf("के", "को", "है", "हैं", "की", "का", "में", "से", "बाद", "आ", "रहा", "रही", "रहे", "कि", "और", "पर",
        "लिए", "क्या", "कैसे", "कब", "तो", "भी", "ही", "यह", "वह", "बहुत", "करें", "करना", "चाहिए", "हो", "मेरा", "मेरी",
        "ka", "ki", "ke", "ko", "hai", "hain", "mein", "me", "se", "kya", "kaise", "kab", "karein", "karen", "karna", "chahiye", "ho", "raha",
        "rahi", "rahe", "tak", "dena", "nahi", "nahin", "mera", "meri", "ab", "to", "bhi", "aur", "par", "lagega", "kitne", "kitna")

    /**
     * Retrieval view of a question:
     *  - Hindi/Hinglish: English terms of every recognised concept first, then the question's
     *    content words (function words such as के/को/है/ka/ko/hai removed, since they match every
     *    Hindi passage and drown the clinical terms in the full-text lane).
     *  - Any language: when the question is about a baby of 59 days or less, the age-group terms
     *    "newborn young infant" are added, so child (2 months – 5 years) protocols do not outrank
     *    young-infant guidance ("Seven days newborn has high fever" retrieved child fever/paracetamol
     *    passages before this).
     * The worker's original question is still what the answer model sees.
     */
    fun retrievalQuery(question: String): String {
        val t = normalise(question)
        val englishOnly = Regex("[\\p{L}]+").findAll(t).all { w -> w.value.all { it in 'a'..'z' } } &&
            !Regex("\\b(?:hai|ko|ka|ki|ke|kya|nahi|nahin|kaise|kab|kyon|bachch?[ae]|bacch?[ae]|bukhar|doodh|din|mein|karein)\\b").containsMatchIn(t)
        val age = ArogyaDangerSigns.textAgeDays(t)
        val infant = (age != null && age <= 59) || Regex("new ?born|neonat|navjaa?t|नवजात").containsMatchIn(t)
        val ageTerms = if (infant) listOf("newborn", "young infant") else emptyList()
        if (englishOnly) {
            val add = ageTerms.filterNot { t.contains(it) }
            return if (add.isEmpty()) question else question.trim() + " " + add.joinToString(" ")
        }
        val english = (concepts.filter { it.pattern.containsMatchIn(t) }.flatMap { it.english.take(2) } + ageTerms).distinct()
        val content = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(t).map { it.value }.filter { it !in FUNCTION_WORDS && it.length > 1 }.toList()
        return (english + content).distinct().joinToString(" ").ifBlank { question }
    }
}
