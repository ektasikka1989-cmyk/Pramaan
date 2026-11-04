package com.example.arfas

/** One stock change heard in a voice note. op: receive | writeoff | correction. */
data class VoiceStockLine(val item: String, val qty: Int, val op: String, val evidence: String)

/**
 * Voice stock entry: "received 100 ORS", "10 ORS packets damaged", "counted 45 IFA".
 * Deterministic parse first ([parseRules]); the model ([ArogyaVisitAi.voiceStock]) only
 * fills in when the rules find nothing. [ground] keeps a line only if its item and its
 * number both appear in the transcript.
 */
object ArogyaVoiceStock {
    const val RECEIVE = "receive"
    const val WRITEOFF = "writeoff"
    const val CORRECTION = "correction"

    private val DEV_DIGITS = mapOf('०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9')

    fun normDigits(s: String): String = buildString(s.length) { s.forEach { append(DEV_DIGITS[it] ?: it) } }

    val NUMBER_WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5, "छह" to 6, "छः" to 6,
        "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10, "बीस" to 20, "तीस" to 30, "चालीस" to 40,
        "पचास" to 50, "साठ" to 60, "सत्तर" to 70, "अस्सी" to 80, "नब्बे" to 90, "सौ" to 100)

    /** Spoken names per kit item (normalised catalogue name → aliases, longest match wins). */
    private val ALIASES = mapOf(
        "ors" to listOf("ors", "ओआरएस"),
        "zinc tablets" to listOf("zinc", "जिंक"),
        "ifa tablets (adult)" to listOf("ifa", "iron", "आईएफए", "आयरन"),
        "ifa syrup (child)" to listOf("ifa syrup", "syrup", "सिरप"),
        "paracetamol" to listOf("paracetamol", "fever tablets", "पैरासिटामोल", "बुखार की गोली"),
        "albendazole" to listOf("albendazole", "worm", "एल्बेंडाजोल", "पेट के कीड़े"),
        "condoms" to listOf("condom", "कंडोम"),
        "oral contraceptive pills" to listOf("contraceptive", "ocp", "pills", "pill", "गर्भ निरोधक", "गोली"),
        "emergency contraceptive pill" to listOf("emergency contraceptive", "emergency", "ecp"),
        "sanitary napkins" to listOf("sanitary napkin", "napkin", "sanitary", "नैपकिन", "पैड"),
        "pregnancy test kit" to listOf("pregnancy test", "test kit", "ptk", "प्रेगनेंसी"),
        "cotrimoxazole" to listOf("cotrimoxazole"),
        "chloroquine / act" to listOf("chloroquine", "malaria", "act", "मलेरिया"),
        "rapid diagnostic test (malaria)" to listOf("rapid test", "diagnostic test", "rdt"),
        "calcium" to listOf("calcium", "कैल्शियम"))

    private fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(needle) + "($|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(hay)
    }

    private val GENERIC_WORDS = setOf("tablet", "tablets", "capsule", "capsules", "bottle", "bottles", "packet", "packets",
        "strip", "strips", "piece", "pieces", "kit", "kits", "box", "sachet", "sachets", "vial", "vials", "child", "adult")

    /** Catalogue item mentioned in [clause], with the alias that matched, or "" when none. */
    fun matchItem(clause: String, catalog: List<String>): Pair<String, String> {
        val norm = normDigits(clause)
        val cands = ArrayList<Pair<String, String>>()
        catalog.forEach { item ->
            val aliases = ALIASES[item.lowercase()] ?: ALIASES[item.lowercase().substringBefore(" ")]
                ?: (listOf(item) + item.split(Regex("\\s+")).filter { it.length >= 4 && it.lowercase() !in GENERIC_WORDS })
            aliases.forEach { a -> cands += item to a }
        }
        return cands.sortedByDescending { it.second.length }.firstOrNull { containsWord(norm, it.second) } ?: ("" to "")
    }

    /** First quantity in the clause: digits (Latin or Devanagari) or a number word, with its token. */
    fun qtyOf(clause: String): Pair<Int, String>? {
        val norm = normDigits(clause)
        Regex("\\d{1,6}").find(norm)?.let { return it.value.toInt() to it.value }
        return NUMBER_WORDS.entries.firstOrNull { containsWord(norm, it.key) }?.let { it.value to it.key }
    }

    fun opOf(clause: String): String {
        val c = normDigits(clause.lowercase())
        val has = { words: List<String> -> words.any { containsWord(c, it) } }
        if (has(listOf("damaged", "damage", "expired", "expire", "expiry", "broken", "broke", "spoilt", "spoil", "waste",
                "खराब", "ख़राब", "एक्सपायर", "टूटा", "टूटे", "फटा", "फेंक", "सड़"))) return WRITEOFF
        if (has(listOf("received", "receive", "got", "refill", "supply", "supplied", "bought", "brought",
                "मिला", "मिली", "मिले", "लिया", "ली", "लाई", "आया", "आई", "आए"))) return RECEIVE
        if (has(listOf("counted", "count", "actually", "actual", "left", "balance", "remaining", "have",
                "बचा", "बचे", "गिना", "गिने", "मेरे पास"))) return CORRECTION
        return RECEIVE
    }

    /**
     * Spoken parts with a number that no saved line accounts for ("5 bandage rolls" when bandage is not a kit
     * item): shown to the ASHA as "not understood", never dropped silently.
     */
    fun unmatched(transcript: String, catalog: List<String>, lines: List<VoiceStockLine>): List<String> =
        normDigits(transcript).split(Regex(",| and | और |।|\\.|;|\\+")).map { it.trim() }.filter { clause ->
            val q = qtyOf(clause)?.first ?: return@filter false
            q > 0 && lines.none { it.qty == q }
        }

    fun parseRules(transcript: String, catalog: List<String>): List<VoiceStockLine> {
        if (catalog.isEmpty()) return emptyList()
        return normDigits(transcript).split(Regex(",| and | और |।|\\.|;|\\+")).mapNotNull { clause ->
            val (qty, tok) = qtyOf(clause) ?: return@mapNotNull null
            if (qty <= 0 || qty > 100000) return@mapNotNull null
            val (item, alias) = matchItem(clause, catalog)
            if (item.isBlank()) return@mapNotNull null
            VoiceStockLine(item, qty, opOf(clause), "$alias · $tok")
        }
    }

    fun prompt(transcript: String, catalog: List<String>): String = buildString {
        appendLine("Copy each stock change from these health-worker words as one line: OP | ITEM | QTY")
        appendLine("OP is RECEIVED (stock came in), DAMAGED (expired or spoilt) or COUNTED (she counted what is there).")
        appendLine("ITEM is one of: " + catalog.joinToString("; "))
        appendLine("QTY is digits only. Copy numbers exactly as spoken; invent nothing. Reply with the lines only.")
        appendLine("Words: $transcript")
    }

    private val MODEL_LINE = Regex("""(?i)(received|receive|damaged|expired|writeoff|counted|correction|count)\s*[|:]\s*([^|:]+?)\s*[|:]\s*(\d{1,6})""")

    fun parseModel(out: String): List<VoiceStockLine> = MODEL_LINE.findAll(normDigits(out)).mapNotNull { m ->
        val op = when (m.groupValues[1].lowercase()) {
            "damaged", "expired", "writeoff" -> WRITEOFF
            "counted", "correction", "count" -> CORRECTION
            else -> RECEIVE
        }
        val qty = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
        if (qty <= 0) return@mapNotNull null
        VoiceStockLine(m.groupValues[2].trim(), qty, op, "${m.groupValues[2].trim()} · $qty")
    }.toList()

    /**
     * Keep a line only if its item (by alias) and its number (digits or number word)
     * both appear in the transcript. Resolves model item text to the catalogue.
     */
    fun ground(lines: List<VoiceStockLine>, transcript: String, catalog: List<String>): List<VoiceStockLine> {
        val norm = normDigits(transcript.lowercase())
        return lines.mapNotNull { l ->
            val item = catalog.firstOrNull { it.equals(l.item, ignoreCase = true) } ?: matchItem(l.item, catalog).first
            if (item.isBlank()) return@mapNotNull null
            val aliases = ALIASES[item.lowercase()] ?: item.split(Regex("\\s+")).filter { it.length >= 4 }
            val alias = aliases.firstOrNull { containsWord(norm, it) } ?: return@mapNotNull null
            val qtyOk = containsWord(norm, l.qty.toString()) || NUMBER_WORDS.any { (w, n) -> n == l.qty && containsWord(norm, w) }
            if (!qtyOk || l.qty <= 0 || l.qty > 100000) return@mapNotNull null
            VoiceStockLine(item, l.qty, l.op, "$alias · ${l.qty}")
        }
    }

    /** A confirmed line becomes the matching stock transaction (correction stores the difference). */
    fun toTxn(line: VoiceStockLine, balance: Int, date: String): StockTxn = when (line.op) {
        WRITEOFF -> StockTxn(date = date, item = line.item, qty = -line.qty, note = "Voice: Written off", kind = "writeoff", reason = line.evidence)
        CORRECTION -> StockTxn(date = date, item = line.item, qty = line.qty - balance,
            note = "Voice: Correction: counted ${line.qty} (was $balance)", kind = "adjust", reason = line.evidence)
        else -> StockTxn(date = date, item = line.item, qty = line.qty, note = "Voice: Received", kind = "receive", reason = line.evidence)
    }

    fun opLabel(op: String, hindi: Boolean): String = when (op) {
        WRITEOFF -> if (hindi) "ख़राब" else "Damaged"
        CORRECTION -> if (hindi) "गिना" else "Counted"
        else -> if (hindi) "मिला" else "Received"
    }
}
