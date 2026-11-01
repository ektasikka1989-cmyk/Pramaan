package com.example.arfas

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Visit Note: a home visit told in the health worker's own words (Hindi, English or Hinglish,
 * typed or spoken) becomes a checked visit record.
 *
 *  1. Numbers (BP, temperature, weight, Hb, pulse, breathing, SpO2, sugar, MUAC, age, pregnancy
 *     month) are read by rules from the words themselves: no model can invent them.
 *  2. The phone's model (Llama) adds what rules cannot read reliably — name, symptoms, medicines,
 *     advice, referral, next visit — as JSON. Any number it gives that is not in the words is dropped.
 *  3. Impossible values are flagged for re-checking, danger signs are raised (national guideline
 *     thresholds: MCP card, HBNC, IMNCI, NCD), and must-record items that are missing are listed.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM: [VisitNoteTest].
 */
object VisitNote {

    enum class Category { PREGNANT, POSTNATAL, NEWBORN, CHILD, ADULT, ELDERLY, UNKNOWN }
    enum class Level { URGENT, REFER, WATCH }

    data class Vitals(
        val bpSys: Int? = null, val bpDia: Int? = null,
        val tempC: Double? = null, val weightKg: Double? = null, val hb: Double? = null,
        val pulse: Int? = null, val respRate: Int? = null, val spo2: Int? = null,
        val sugar: Int? = null, val sugarFasting: Boolean = false, val muacCm: Double? = null
    )

    data class Record(
        val name: String? = null,
        val ageYears: Double? = null,
        val ageDays: Int? = null,
        val category: Category = Category.UNKNOWN,
        val pregnancyMonths: Double? = null,
        val vitals: Vitals = Vitals(),
        val symptoms: List<String> = emptyList(),
        val medicines: List<String> = emptyList(),
        val advice: List<String> = emptyList(),
        val referral: String? = null,
        val nextVisitText: String? = null,
        /** Days since 1970-01-01 (local calendar), or null when no next visit was said. */
        val nextVisitDay: Long? = null,
        val notes: String? = null
    )

    data class Alert(
        val level: Level,
        val code: String,
        val en: String,
        val hi: String,
        /** Words to look the sign up in the selected knowledge base (for a page citation). */
        val kbQuery: String,
        val citation: String? = null
    )

    data class Issue(val field: String, val value: String, val en: String, val hi: String)

    data class Result(
        val record: Record,
        val alerts: List<Alert>,
        val issues: List<Issue>,
        val missing: List<Pair<String, String>>,
        val hindi: Boolean,
        val usedModel: Boolean
    )

    // ── Request detection ────────────────────────────────────────────────────────────────

    const val PREFIX_EN = "Visit note: "
    private val TRIGGER = Regex(
        "(?i)^\\s*(?:visit\\s*(?:note|record|report)|home[\\s-]*visit\\s*(?:note|record|report)|" +
            "ghar\\s*(?:bhet|bhent|visit)\\s*(?:note|record)?|विज़िट\\s*नोट|विजिट\\s*नोट|" +
            "गृह\\s*भ्रमण(?:\\s*(?:नोट|रिकॉर्ड))?|भ्रमण\\s*(?:नोट|रिकॉर्ड))\\s*[:：\\-–—,।]?\\s*"
    )

    fun isRequest(text: String): Boolean = TRIGGER.find(text)?.let { m ->
        // "Visit note" alone (nothing told yet) is still a request: the reply says what to say.
        m.range.first == 0
    } == true

    fun narrativeOf(text: String): String = text.replaceFirst(TRIGGER, "").replace("[self-test]", "").trim()

    fun isHindi(text: String): Boolean {
        val dev = text.count { it.code in 0x0900..0x097F }
        val lat = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return dev > 0 && dev >= lat
    }

    // ── 1. Rules: numbers and category from the words ──────────────────────────────────────

    private const val GAP = "[^\\d\\n]{0,14}?"
    private val NUM = "(\\d{1,3}(?:[.,]\\d{1,2})?)"
    private val BP = Regex("(?i)(?:\\bb\\.?\\s?p\\.?\\b|blood\\s*pressure|बीपी|बी\\.पी\\.|रक्तचाप)$GAP(\\d{2,3})\\s*(?:/|\\\\|by|over|बटा|बाई|पर)\\s*(\\d{2,3})")
    private val TEMP = Regex("(?i)(?:\\btemp(?:erature)?\\b|\\bfever\\b|bukhar|bukhaar|बुखार|तापमान|tapman)$GAP$NUM\\s*(°\\s*[cf]\\b|°|\\bdegree[s]?\\b|डिग्री|\\b[cf]\\b)?")
    private val WEIGHT = Regex("(?i)(?:\\bweight\\b|\\bwt\\.?|\\bwajan\\b|\\bvajan\\b|\\bwazan\\b|वजन|वज़न)$GAP(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(kg|kgs|kilo|किलो|g\\b|gm|gram|grams|ग्राम)?")
    private val WEIGHT_UNIT = Regex("(?i)(\\d{1,3}(?:[.,]\\d{1,3})?)\\s*(kg|kgs|kilo|किलो)\\b")
    private val HB = Regex("(?i)(?:\\bhb\\b|\\bh\\.b\\.|ha?emoglobin|हीमोग्लोबिन|एचबी)$GAP$NUM")
    private val PULSE = Regex("(?i)(?:\\bpulse\\b|\\bnabz\\b|नाड़ी|नब्ज़?|heart\\s*rate)$GAP(\\d{2,3})")
    private val RESP = Regex("(?i)(?:resp(?:iratory)?\\s*rate|breathing\\s*rate|\\bbreath(?:s|ing)?\\b|\\bRR\\b|saans|सांस|साँस|श्वास)$GAP(\\d{2,3})\\s*(?:/\\s*min|per\\s*min|प्रति\\s*मिनट|times|बार)?")
    private val SPO2 = Regex("(?i)(?:spo2|sp02|\\bsat(?:uration)?\\b|oxygen|ऑक्सीजन)$GAP(\\d{2,3})\\s*%?")
    private val SUGAR = Regex("(?i)(?:\\bsugar\\b|glucose|\\brbs\\b|\\bfbs\\b|शुगर|ग्लूकोज)$GAP(\\d{2,3})")
    private val FASTING = Regex("(?i)fasting|\\bfbs\\b|khali\\s*pet|खाली\\s*पेट")
    private val MUAC = Regex("(?i)(?:muac|मुआक|एमयूएसी)$GAP$NUM\\s*(cm|mm)?")
    private val AGE_YEARS = Regex("(?i)(?:\\bage\\b|umar|उम्र|आयु)?\\s*(\\d{1,3})\\s*(?:years?|yrs?|y/o|saal|sal|वर्ष|साल)\\b")
    private val AGE_DAYS = Regex("(?i)(\\d{1,2})\\s*(?:days?|din|दिन)\\s*(?:old|ka|ki|का|की|के)")
    private val AGE_MONTHS_CHILD = Regex("(?i)(\\d{1,2})\\s*(?:months?|mahine|mahina|महीने|महीना|माह)\\s*(?:old|ka|ki|का|की|के)\\s*(?:baby|child|bachcha|bachchi|बच्चा|बच्ची|शिशु)?")
    private val PREG_MONTHS = Regex("(?i)(\\d{1,2}(?:[.,]5)?)\\s*(?:months?|mahine|mahina|महीने|महीना|माह)\\s*(?:pregnant|ki\\s*pregnant|ka\\s*garbh|garbhvati|garbhwati|गर्भवती|का\\s*गर्भ|की\\s*गर्भवती|pregnancy)")
    private val PREG_WEEKS = Regex("(?i)(\\d{1,2})\\s*(?:weeks?|wks?|hafte|haftey|सप्ताह|हफ्ते)\\s*(?:pregnant|ki\\s*pregnant|garbhvati|गर्भवती|का\\s*गर्भ|pregnancy|of\\s*pregnancy)")
    private val PREGNANT_WORDS = Regex("(?i)pregnan|garbh|गर्भ|\\banc\\b|antenatal|prasav\\s*purv|प्रसव\\s*पूर्व")
    private val POSTNATAL_WORDS = Regex("(?i)\\bpnc\\b|postnatal|post[\\s-]?partum|after\\s*delivery|delivered|prasav\\s*ke\\s*baad|प्रसव\\s*के\\s*बाद|डिलीवरी\\s*के\\s*बाद|delivery\\s*ke\\s*baad")
    private val NEWBORN_WORDS = Regex("(?i)new[\\s-]?born|neonat|navjat|नवजात|\\bhbnc\\b|शिशु")
    private val CHILD_WORDS = Regex("(?i)\\bchild\\b|\\bbaby\\b|\\binfant\\b|bachch|बच्च|\\bhbyc\\b")

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    fun ruleRecord(narrative: String, today: Long = todayEpochDay()): Record {
        val t = narrative
        var v = Vitals()
        BP.find(t)?.let { m -> v = v.copy(bpSys = m.groupValues[1].toIntOrNull(), bpDia = m.groupValues[2].toIntOrNull()) }
        TEMP.find(t)?.let { m ->
            num(m.groupValues[1])?.let { raw ->
                val unit = m.groupValues[2].lowercase()
                if (unit.isBlank() && raw < 30) return@let
                val c = when {
                    unit.contains('f') -> (raw - 32) * 5 / 9
                    unit.contains('c') -> raw
                    raw > 45 -> (raw - 32) * 5 / 9   // spoken Fahrenheit ("bukhar 102")
                    else -> raw
                }
                v = v.copy(tempC = Math.round(c * 10) / 10.0)
            }
        }
        val ageDays = AGE_DAYS.find(t)?.groupValues?.get(1)?.toIntOrNull()
        val newbornHint = ageDays != null || NEWBORN_WORDS.containsMatchIn(t)
        (WEIGHT.find(t) ?: WEIGHT_UNIT.find(t))?.let { m ->
            num(m.groupValues[1])?.let { raw ->
                val unit = m.groupValues.getOrNull(2).orEmpty().lowercase()
                val kg = when {
                    unit.startsWith("g") || unit == "ग्राम" -> raw / 1000
                    unit.isBlank() && newbornHint && raw >= 300 -> raw / 1000   // "weight 2100" for a baby = grams
                    else -> raw
                }
                v = v.copy(weightKg = Math.round(kg * 1000) / 1000.0)
            }
        }
        HB.find(t)?.let { m -> v = v.copy(hb = num(m.groupValues[1])) }
        // A small number after these words is a count of days or litres, not a reading ("saans 3 din se").
        PULSE.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 30 }?.let { v = v.copy(pulse = it) }
        RESP.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 10 }?.let { v = v.copy(respRate = it) }
        SPO2.find(t)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 50 }?.let { v = v.copy(spo2 = it) }
        SUGAR.find(t)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it >= 20 }?.let { v = v.copy(sugar = it, sugarFasting = FASTING.containsMatchIn(t)) } }
        MUAC.find(t)?.let { m ->
            num(m.groupValues[1])?.let { raw ->
                v = v.copy(muacCm = if (m.groupValues[2].equals("mm", true) || raw > 40) raw / 10 else raw)
            }
        }

        val pregMonths = PREG_MONTHS.find(t)?.let { num(it.groupValues[1]) }
            ?: PREG_WEEKS.find(t)?.takeIf { PREGNANT_WORDS.containsMatchIn(t) }?.let { m -> num(m.groupValues[1])?.let { Math.round(it / 4.345 * 10) / 10.0 } }
        val ageYears = AGE_YEARS.findAll(t).mapNotNull { it.groupValues[1].toDoubleOrNull() }.firstOrNull { it in 1.0..120.0 }
        val childMonths = if (pregMonths == null) AGE_MONTHS_CHILD.find(t)?.groupValues?.get(1)?.toDoubleOrNull() else null
        val category = when {
            POSTNATAL_WORDS.containsMatchIn(t) && !newbornHint -> Category.POSTNATAL
            pregMonths != null || (PREGNANT_WORDS.containsMatchIn(t) && !POSTNATAL_WORDS.containsMatchIn(t)) -> Category.PREGNANT
            (ageDays != null && ageDays <= 42) || NEWBORN_WORDS.containsMatchIn(t) -> Category.NEWBORN
            childMonths != null || (ageYears != null && ageYears < 18 && CHILD_WORDS.containsMatchIn(t)) || (ageYears == null && CHILD_WORDS.containsMatchIn(t)) -> Category.CHILD
            ageYears != null && ageYears >= 60 -> Category.ELDERLY
            ageYears != null -> Category.ADULT
            POSTNATAL_WORDS.containsMatchIn(t) -> Category.POSTNATAL
            else -> Category.UNKNOWN
        }
        val (nextText, nextDay) = nextVisit(t, today) ?: (null to null)
        return Record(
            name = nameOf(t),
            ageYears = ageYears ?: childMonths?.let { Math.round(it / 12.0 * 100) / 100.0 },
            ageDays = ageDays,
            category = category,
            pregnancyMonths = pregMonths,
            vitals = v,
            nextVisitText = nextText,
            nextVisitDay = nextDay
        )
    }

    private val BABY_OF = Regex("(?i)^(?:baby\\s+of|बेबी\\s+ऑफ़?|शिशु)\\s+([\\p{L}\\p{M}]{2,20})")
    private val NAME_STOP = Regex(
        "(?i)^(?:she|he|patient|mother|woman|lady|baby|child|today|aaj|मरीज|महिला|माँ|बच्चा|आज|the|a|an|मैं|i|we)\\b"
    )

    /** "Sunita Devi, 7 months pregnant…" → "Sunita Devi": a short name-like first phrase. */
    fun nameOf(text: String): String? {
        BABY_OF.find(text.trim())?.let { return "Baby of " + it.groupValues[1] }
        val first = text.trim().split(Regex("[,，।:;\\n]|\\s-\\s|\\bis\\b|\\bhai\\b|\\bहै\\b|\\bwho\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty()
        val words = first.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { w -> w.any { it.isDigit() } }) return null
        if (NAME_STOP.containsMatchIn(first)) return null
        if (!words.all { w -> w.all { it.isLetter() || it == '.' || it.code in 0x0900..0x097F } }) return null
        if (words.any { it.length < 2 }) return null
        if (PREGNANT_WORDS.containsMatchIn(first) || NEWBORN_WORDS.containsMatchIn(first) || CHILD_WORDS.containsMatchIn(first)) return null
        val latin = words.first().first().isLetter() && words.first().first().code < 0x0900
        if (latin && !words.first().first().isUpperCase()) return null
        return first
    }

    // ── next visit ────────────────────────────────────────────────────────────────────────

    private val NEXT_CONTEXT = Regex("(?i)next\\s*visit|follow[\\s-]?up|come\\s*again|visit\\s*again|\\bdobara\\b|\\bphir\\s*(?:aa|visit|milenge)|फिर\\s*(?:से|आ|आऊँ|आउं|मिल)|दोबारा|अगली\\s*(?:विज़िट|विजिट|मुलाकात)|agli\\s*visit|\\bagain\\b")
    private val IN_DAYS = Regex("(?i)(?:in|after|within|baad|बाद)?\\s*(\\d{1,3})\\s*(days?|din|दिन|weeks?|hafte|haftey|हफ्ते|सप्ताह|months?|mahine|महीने)(?!\\s*(?:old\\b|ka\\b|ki\\b|ke\\b|का|की|के|से|se\\b|since|pregnant|ki\\s*pregnant|गर्भ))\\s*(?:later|baad|बाद|me|में|mein)?")
    private val DATE_ISO = Regex("\\b(20\\d{2})-(\\d{1,2})-(\\d{1,2})\\b")
    private val DATE_DMY = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](20\\d{2}|\\d{2})\\b")
    private val DATE_D_MON = Regex("(?i)\\b(\\d{1,2})\\s*(?:st|nd|rd|th)?\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b")
    private val TOMORROW = Regex("(?i)\\btomorrow\\b|\\bkal\\b|कल")
    private val NEXT_WEEK = Regex("(?i)next\\s*week|agle\\s*hafte|अगले\\s*(?:हफ्ते|सप्ताह)")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** The next visit the worker said, as (words, local epoch day). Never invented: null when not said. */
    fun nextVisit(text: String, today: Long): Pair<String, Long>? {
        val ctx = NEXT_CONTEXT.find(text) ?: return null
        val tail = text.substring(maxOf(0, ctx.range.first - 30), minOf(text.length, ctx.range.last + 81))
        DATE_ISO.find(tail)?.let { m -> return m.value to epochDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        DATE_DMY.find(tail)?.let { m ->
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            return m.value to epochDay(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        DATE_D_MON.find(tail)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            val (y, _, _) = civil(today)
            var day = epochDay(y, month, m.groupValues[1].toInt())
            if (day < today - 31) day = epochDay(y + 1, month, m.groupValues[1].toInt())
            return m.value to day
        }
        IN_DAYS.find(tail)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            val days = when {
                unit.startsWith("w") || unit.startsWith("h") || unit.contains("हफ्त") || unit.contains("सप्ताह") -> n * 7
                unit.startsWith("m") || unit.contains("महीन") -> n * 30
                else -> n
            }
            if (days in 1..400) return m.value.trim() to today + days
        }
        if (NEXT_WEEK.containsMatchIn(tail)) return "next week" to today + 7
        if (TOMORROW.containsMatchIn(tail)) return "tomorrow" to today + 1
        return null
    }

    // ── 1b. Rules for the lists (used when the phone's model is not loaded or gives nothing) ─

    private val MEDICINE_WORDS = Regex("(?i)\\bifa\\b|\\biron\\b|folic|calcium|\\bors\\b|\\bzinc\\b|paracetamol|crocin|albendazole|amoxic|cotrimox|vitamin\\s*a|\\btd\\b|\\btt\\b|vaccine|tika|teeka|metformin|amlodipine|tablet|syrup|\\bdawa|\\bdawai|आयरन|आईएफए|फोलिक|कैल्शियम|ओआरएस|ज़िंक|जिंक|पैरासिटामोल|गोली|सिरप|दवा|दवाई|टीका")
    private val SYMPTOM_WORDS = Regex("(?i)swell|sujan|सूजन|pain|dard|दर्द|cough|khansi|खांसी|खाँसी|vomit|ulti|उल्टी|headache|sir\\s*dard|सिरदर्द|weak|kamzor|कमज़ोर|कमजोर|dizz|chakkar|चक्कर|bleed|खून|fever|bukhar|बुखार|diarrh|dast|दस्त|rash|daane|दाने|breathless|saans|सांस|itch|khujli|खुजली|burning|jalan|जलन|yellow|piliya|पीलिया|not\\s*feeding|दूध\\s*नहीं|tired|thakan|थकान")
    private val ADVICE_WORDS = Regex("(?i)advis|counsel|told\\s*(?:her|him|them|to)|explained|asked\\s*(?:her|him|them)\\s*to|salah|samjha|सलाह|समझाया|बताया|कहा")
    private val REFER_WORDS = Regex("(?i)refer|रेफर|bheja|भेजा|sent\\s*to")
    private val MEASURE_WORDS = Regex("(?i)\\bbp\\b|बीपी|\\bhb\\b|एचबी|weight|वजन|temp|तापमान|muac|spo2|sugar|शुगर|pulse|नब्ज़")

    /** Clauses of the note that name symptoms, medicines, advice or a referral (the note's own words). */
    fun ruleLists(narrative: String): Record {
        val clauses = narrative.split(Regex("[,;।\\n]|\\.(?=\\s)|\\band\\b|\\baur\\b|\\bऔर\\b", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', '-', ':') }.filter { it.length >= 3 }
        fun pick(rx: Regex, excludeMeasures: Boolean = true) = clauses.filter { c ->
            rx.containsMatchIn(c) && !(excludeMeasures && MEASURE_WORDS.containsMatchIn(c) && c.any { it.isDigit() }) &&
                !NEXT_CONTEXT.containsMatchIn(c) && !VisitNote.isRequest(c)
        }.map { it.take(80) }.distinct().take(8)
        val advice = pick(ADVICE_WORDS)
        val referral = pick(REFER_WORDS).firstOrNull()
        val medicines = pick(MEDICINE_WORDS).filter { it !in advice && it != referral }
        val symptoms = pick(SYMPTOM_WORDS).filter { it !in medicines && it !in advice && it != referral }
            .filter { c -> !Regex("(?i)^(?:no|not|without|koi\\s*nahi)\\b").containsMatchIn(c) && !Regex("(?i)\\b(?:nahi|नहीं)\\s*(?:hai|है)?\\s*$").containsMatchIn(c) || Regex("(?i)not\\s*feeding|दूध\\s*नहीं").containsMatchIn(c) }
        return Record(symptoms = symptoms, medicines = medicines, advice = advice, referral = referral)
    }

    // ── 2. The model: what rules cannot read ───────────────────────────────────────────────

    fun modelPrompt(narrative: String): String = buildString {
        appendLine("Read this home-visit note written by a community health worker and return ONE JSON object only, no other text.")
        appendLine("Use only what the note says. If something is not said, use null or an empty list. Never guess numbers.")
        appendLine("Keys:")
        appendLine("\"name\": person's name or null; \"age_years\": number or null;")
        appendLine("\"category\": one of \"pregnant\", \"postnatal\", \"newborn\", \"child\", \"adult\", \"elderly\", \"unknown\";")
        appendLine("\"symptoms\": list of short phrases (problems the person has, as said);")
        appendLine("\"medicines\": list of medicines given or advised, each with dose if said;")
        appendLine("\"advice\": list of advice or counselling the worker gave;")
        appendLine("\"referral\": where and why the person was referred, or null;")
        appendLine("\"next_visit\": the next visit as said (for example \"in 7 days\"), or null;")
        appendLine("\"notes\": anything else important in one short sentence, or null.")
        appendLine("Keep each phrase in the language of the note.")
        appendLine()
        appendLine("Note: ${narrative.trim().take(2_400)}")
    }

    /** The model's JSON, tolerant of text around it. Null when there is no usable object. */
    fun parseModel(output: String?): JSONObject? {
        val text = output?.trim().orEmpty()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.strOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("none", true) }

    private fun JSONObject.list(key: String): List<String> {
        val arr = optJSONArray(key) ?: return strOrNull(key)?.let { listOf(it) }.orEmpty()
        return (0 until arr.length()).mapNotNull { i -> arr.opt(i)?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" } }
            .distinct().take(12)
    }

    /** Rules first; the model fills only what rules left empty, and only with words the note contains. */
    fun merge(rules: Record, model: JSONObject?, narrative: String, today: Long = todayEpochDay()): Record {
        val lists = ruleLists(narrative)
        if (model == null) return rules.copy(symptoms = lists.symptoms, medicines = lists.medicines, advice = lists.advice, referral = lists.referral)
        val lower = narrative.lowercase(Locale.ROOT)
        fun grounded(phrase: String): Boolean {
            // A phrase is kept when most of its words (or, for a Hindi phrase, its letters) appear in the note.
            val words = phrase.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 }
            if (words.isEmpty()) return phrase.isNotBlank() && lower.contains(phrase.lowercase(Locale.ROOT).trim())
            return words.count { lower.contains(it) } * 2 >= words.size
        }
        val modelCategory = when (model.strOrNull("category")?.lowercase()) {
            "pregnant" -> Category.PREGNANT; "postnatal" -> Category.POSTNATAL; "newborn" -> Category.NEWBORN
            "child" -> Category.CHILD; "adult" -> Category.ADULT; "elderly" -> Category.ELDERLY; else -> null
        }
        // An age only when the note says it as an age ("24 years", "24 saal"): the model took
        // "6 mahine ki pregnant" for 6 years.
        val modelAge = model.optDouble("age_years").takeIf { !it.isNaN() && it in 0.0..120.0 }
            ?.takeIf { age -> Regex("(?i)\\b${age.toInt()}\\s*(?:years?|yrs?|y/o|saal|sal|वर्ष|साल)").containsMatchIn(narrative) }
        val modelName = model.strOrNull("name")?.takeIf { n -> n.split(Regex("\\s+")).all { lower.contains(it.lowercase(Locale.ROOT)) } && n.length <= 40 }
        val next = rules.nextVisitText ?: model.strOrNull("next_visit")?.takeIf { grounded(it) }
        val nextDay = rules.nextVisitDay ?: next?.let { nextVisit("next visit $it", today)?.second }
        val advice = model.list("advice").filter(::grounded).ifEmpty { lists.advice }
        // Readings belong in the table, not in the symptoms ("BP 142/94", "bukhar 101", "वजन 50 किलो").
        fun isReading(p: String) = MEASURE_WORDS.containsMatchIn(p) ||
            (p.any { it.isDigit() } && Regex("(?i)fever|bukhar|बुखार|temp|तापमान").containsMatchIn(p)) ||
            Regex("(?i)\\bnormal\\b|सामान्य|theek|ठीक").containsMatchIn(p)
        val symptoms = model.list("symptoms").filter(::grounded).filterNot(::isReading).ifEmpty { lists.symptoms }
        // A medicine is not advice ("stop smoking" was given as both).
        val medicines = model.list("medicines").filter(::grounded)
            .filterNot { m -> advice.any { it.equals(m, ignoreCase = true) } || ADVICE_WORDS.containsMatchIn(m) || Regex("(?i)^stop\\b").containsMatchIn(m) }
            .ifEmpty { lists.medicines }
        // A note only when its words are the worker's own and it adds something new
        // (the model wrote "weight likely low" for a note that never said so).
        val notes = model.strOrNull("notes")?.takeIf { n ->
            val words = n.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 }
            words.isNotEmpty() && words.all { lower.contains(it) } &&
                (symptoms + medicines + advice).none { it.equals(n, ignoreCase = true) || n.contains(it, ignoreCase = true) || it.contains(n, ignoreCase = true) }
        }?.take(200)
        return rules.copy(
            name = rules.name ?: modelName,
            ageYears = rules.ageYears ?: modelAge,
            category = if (rules.category == Category.UNKNOWN) modelCategory ?: Category.UNKNOWN else rules.category,
            symptoms = symptoms,
            medicines = medicines,
            advice = advice,
            referral = model.strOrNull("referral")?.takeIf(::grounded) ?: lists.referral,
            nextVisitText = next,
            nextVisitDay = nextDay,
            notes = notes
        )
    }

    // ── 3. Checks ─────────────────────────────────────────────────────────────────────────

    /** Values that cannot be right (a mis-typed or mis-heard number): flagged and left out of the checks. */
    fun plausibility(record: Record): Pair<Record, List<Issue>> {
        val v = record.vitals
        val issues = ArrayList<Issue>()
        fun bad(field: String, value: String, en: String, hi: String) { issues += Issue(field, value, en, hi) }
        var out = v
        if (v.bpSys != null || v.bpDia != null) {
            val s = v.bpSys ?: 0; val d = v.bpDia ?: 0
            if (s !in 60..260 || d !in 30..160 || s <= d) {
                bad("BP", "$s/$d", "BP $s/$d looks wrong — please measure again.", "बीपी $s/$d सही नहीं लगता — कृपया दोबारा नापें।")
                out = out.copy(bpSys = null, bpDia = null)
            }
        }
        v.tempC?.let { if (it !in 30.0..43.5) { bad("Temperature", fmt(it), "Temperature ${fmt(it)} °C looks wrong — please measure again.", "तापमान ${fmt(it)} °C सही नहीं लगता — दोबारा नापें।"); out = out.copy(tempC = null) } }
        v.weightKg?.let { if (it !in 0.4..250.0) { bad("Weight", fmt(it), "Weight ${fmt(it)} kg looks wrong — please weigh again.", "वजन ${fmt(it)} किलो सही नहीं लगता — दोबारा तौलें।"); out = out.copy(weightKg = null) } }
        v.hb?.let { if (it !in 2.0..22.0) { bad("Hb", fmt(it), "Hb ${fmt(it)} g/dL looks wrong — please test again.", "एचबी ${fmt(it)} सही नहीं लगता — दोबारा जाँचें।"); out = out.copy(hb = null) } }
        v.pulse?.let { if (it !in 30..220) { bad("Pulse", "$it", "Pulse $it looks wrong — please count again.", "नब्ज़ $it सही नहीं लगती — दोबारा गिनें।"); out = out.copy(pulse = null) } }
        v.respRate?.let { if (it !in 8..120) { bad("Breathing", "$it", "Breathing rate $it looks wrong — count for one full minute.", "सांस की दर $it सही नहीं लगती — पूरे एक मिनट गिनें।"); out = out.copy(respRate = null) } }
        v.spo2?.let { if (it !in 50..100) { bad("SpO2", "$it", "SpO2 $it% looks wrong — please measure again.", "ऑक्सीजन $it% सही नहीं लगता — दोबारा नापें।"); out = out.copy(spo2 = null) } }
        v.sugar?.let { if (it !in 20..700) { bad("Sugar", "$it", "Blood sugar $it looks wrong — please test again.", "शुगर $it सही नहीं लगती — दोबारा जाँचें।"); out = out.copy(sugar = null) } }
        v.muacCm?.let { if (it !in 5.0..40.0) { bad("MUAC", fmt(it), "MUAC ${fmt(it)} cm looks wrong — please measure again.", "MUAC ${fmt(it)} सेमी सही नहीं लगता — दोबारा नापें।"); out = out.copy(muacCm = null) } }
        record.pregnancyMonths?.let { if (it !in 0.5..10.0) { bad("Pregnancy", fmt(it), "Pregnancy month ${fmt(it)} looks wrong.", "गर्भ का महीना ${fmt(it)} सही नहीं लगता।") } }
        if (record.category == Category.NEWBORN) v.weightKg?.let { if (it > 7) { bad("Weight", fmt(it), "Newborn weight ${fmt(it)} kg looks wrong — please weigh again.", "नवजात का वजन ${fmt(it)} किलो सही नहीं लगता — दोबारा तौलें।"); out = out.copy(weightKg = null) } }
        return record.copy(vitals = out) to issues
    }

    private data class Sign(
        val code: String, val level: Level, val rx: Regex, val en: String, val hi: String, val kb: String,
        val only: Set<Category> = emptySet(), val negatable: Boolean = true
    )

    private val P = setOf(Category.PREGNANT)
    private val PP = setOf(Category.PREGNANT, Category.POSTNATAL)
    private val NB = setOf(Category.NEWBORN)
    private val KID = setOf(Category.NEWBORN, Category.CHILD)

    /** Danger signs told in words (MCP card, HBNC, IMNCI). English, Hinglish and Hindi. */
    private val SIGNS = listOf(
        Sign("bleeding", Level.URGENT, Regex("(?i)bleed|khoon\\s*(?:aa|beh|ja)|खून\\s*(?:आ|बह|जा)|रक्तस्राव|spotting"),
            "Bleeding in pregnancy or after delivery — call 108 (or 102) and take her to the nearest facility now.",
            "गर्भावस्था में या प्रसव के बाद खून आना — तुरंत 108 (या 102) बुलाएँ और नज़दीकी अस्पताल ले जाएँ।", "bleeding in pregnancy danger sign referral", PP),
        Sign("convulsions", Level.URGENT, Regex("(?i)convuls|\\bfits?\\b|seizure|\\bdaura\\b|दौरा|दौरे|झटके|mirgi|मिर्गी"),
            "Convulsions (fits) — call 108 now; keep the person on their side and do not put anything in the mouth.",
            "दौरे (झटके) — तुरंत 108 बुलाएँ; करवट लिटाएँ, मुँह में कुछ न डालें।", "convulsions danger sign referral"),
        Sign("unconscious", Level.URGENT, Regex("(?i)unconscious|not\\s*responding|behosh|बेहोश"),
            "Unconscious or not responding — call 108 now.", "बेहोश या जवाब नहीं दे रहा — तुरंत 108 बुलाएँ।", "unconscious danger sign emergency referral"),
        Sign("breathing", Level.URGENT, Regex("(?i)difficult(?:y)?\\s*(?:in\\s*)?breath|breathless|short(?:ness)?\\s*of\\s*breath|chest\\s*in-?drawing|saans\\s*(?:lene\\s*me|phool|ful)|सांस\\s*(?:लेने\\s*में|फूल)|साँस\\s*(?:लेने\\s*में|फूल)|पसली\\s*चल"),
            "Difficulty in breathing — refer to the nearest facility now (call 108 if severe).",
            "सांस लेने में तकलीफ़ — तुरंत नज़दीकी अस्पताल भेजें (गंभीर हो तो 108)।", "difficulty breathing danger sign referral"),
        Sign("not_feeding", Level.URGENT, Regex("(?i)not\\s*(?:feeding|sucking|breast\\s*feeding|drinking)|unable\\s*to\\s*(?:feed|suck|drink)|doodh\\s*nahi\\s*pee|दूध\\s*नहीं\\s*पी|स्तनपान\\s*नहीं"),
            "Baby not feeding well — a danger sign; refer to the facility today.", "बच्चा दूध ठीक से नहीं पी रहा — खतरे का लक्षण; आज ही अस्पताल भेजें।",
            "newborn not feeding well danger sign", KID, negatable = false),
        Sign("headache_vision", Level.REFER, Regex("(?i)severe\\s*headache|tez\\s*sir\\s*dard|सिर\\s*में\\s*तेज़?\\s*दर्द|blurr?ed\\s*vision|dhundhla|धुंधला"),
            "Severe headache or blurred vision in pregnancy — check BP and refer today.", "गर्भावस्था में तेज़ सिरदर्द या धुंधला दिखना — बीपी देखें और आज ही रेफर करें।",
            "severe headache blurred vision pregnancy danger sign", P),
        Sign("swelling", Level.REFER, Regex("(?i)swelling\\s*(?:of|in|on)?\\s*(?:the\\s*)?(?:face|hands?|whole\\s*body)|face\\s*swelling|chehre\\s*par\\s*sujan|चेहरे\\s*(?:पर|की)\\s*सूजन|हाथ.{0,6}सूजन"),
            "Swelling of face or hands in pregnancy — check BP and refer.", "गर्भावस्था में चेहरे या हाथों पर सूजन — बीपी देखें और रेफर करें।",
            "swelling face hands pregnancy danger sign", P),
        Sign("fetal_movement", Level.REFER, Regex("(?i)(?:baby|fetal|foetal)\\s*(?:movements?)?\\s*(?:is\\s*)?(?:less|reduced|not\\s*moving|stopped)|less\\s*(?:fetal\\s*)?movement|bachcha\\s*(?:hil|hilna)\\s*(?:nahi|kam)|हलचल\\s*(?:कम|नहीं|बंद)"),
            "Baby moving less in the womb — refer today.", "पेट में बच्चे की हलचल कम — आज ही रेफर करें।", "reduced fetal movement danger sign", P, negatable = false),
        Sign("water_broke", Level.REFER, Regex("(?i)water\\s*(?:broke|break|leak)|leaking\\s*(?:of\\s*)?(?:water|fluid)|paani\\s*(?:gir|aa|nikal)|पानी\\s*(?:गिर|आ|निकल)"),
            "Water leaking before labour — refer to the facility now.", "प्रसव से पहले पानी आना — तुरंत अस्पताल भेजें।", "leaking of water before labour danger sign", P),
        Sign("abdominal_pain", Level.REFER, Regex("(?i)severe\\s*(?:abdominal|stomach|belly)\\s*pain|pet\\s*(?:me|mein)\\s*tez\\s*dard|पेट\\s*में\\s*तेज़?\\s*दर्द"),
            "Severe abdominal pain — refer today.", "पेट में तेज़ दर्द — आज ही रेफर करें।", "severe abdominal pain danger sign", PP),
        Sign("jaundice", Level.REFER, Regex("(?i)yellow\\s*(?:palms|soles|skin|eyes|body)|jaundice|piliya|पीलिया|पीली\\s*(?:त्वचा|आँख|हथेली)"),
            "Yellow skin or eyes (jaundice) in a newborn — refer today, urgently if palms or soles are yellow.",
            "नवजात में पीलिया — आज ही रेफर करें; हथेली/तलवे पीले हों तो तुरंत।", "newborn jaundice yellow palms soles referral", NB),
        Sign("cold", Level.REFER, Regex("(?i)cold\\s*to\\s*(?:the\\s*)?touch|baby\\s*(?:is\\s*)?cold|thanda\\s*(?:hai|pad)|ठंडा\\s*(?:है|पड़)"),
            "Baby cold to touch — warm with skin-to-skin contact and refer.", "बच्चा छूने पर ठंडा — माँ की त्वचा से सटाकर गर्म रखें और रेफर करें।", "newborn hypothermia cold to touch", NB),
        Sign("lethargic", Level.REFER, Regex("(?i)letharg|very\\s*sleepy|not\\s*moving|\\bsust\\b|सुस्त|हिल\\s*नहीं\\s*रहा"),
            "Baby lethargic or not moving — a danger sign; refer today.", "बच्चा सुस्त या हिल नहीं रहा — खतरे का लक्षण; आज ही रेफर करें।", "lethargic newborn danger sign", KID, negatable = false),
        Sign("blood_stool", Level.REFER, Regex("(?i)blood\\s*in\\s*(?:the\\s*)?stool|bloody\\s*(?:stool|diarrh)|khooni\\s*dast|खूनी\\s*दस्त|मल\\s*में\\s*खून"),
            "Blood in stool — refer today.", "मल में खून — आज ही रेफर करें।", "blood in stool dysentery referral"),
        Sign("diarrhoea", Level.WATCH, Regex("(?i)diarrh|loose\\s*motion|\\bdast\\b|दस्त"),
            "Diarrhoea — give ORS and zinc as per guideline; refer if the child cannot drink or has blood in stool.",
            "दस्त — दिशानिर्देश के अनुसार ओआरएस और ज़िंक दें; बच्चा पी न सके या मल में खून हो तो रेफर करें।", "diarrhoea ORS zinc treatment"),
    )

    private val NEG_BEFORE = Regex("(?i)(?:\\bno\\b|\\bnot\\b|\\bwithout\\b|\\bnever\\b|\\bdenies\\b|\\bकोई\\b|बिना)\\s*(?:\\w+\\s*){0,2}$")
    private val NEG_AFTER = Regex("(?i)^\\s*(?:\\w+\\s*){0,2}(?:nahi|nahin|nhi|नहीं|ना\\b|nil\\b|absent)")

    private fun mentioned(sign: Sign, text: String): Boolean = sign.rx.findAll(text).any { m ->
        if (!sign.negatable) return@any true
        val before = text.substring(maxOf(0, m.range.first - 24), m.range.first)
        val after = text.substring(m.range.last + 1, minOf(text.length, m.range.last + 1 + 18))
        !NEG_BEFORE.containsMatchIn(before) && !NEG_AFTER.containsMatchIn(after)
    }

    /** Danger signs from the numbers and the words; most urgent first. */
    fun alerts(record: Record, narrative: String): List<Alert> {
        val out = ArrayList<Alert>()
        val c = record.category
        val v = record.vitals
        fun add(level: Level, code: String, en: String, hi: String, kb: String) {
            if (out.none { it.code == code }) out += Alert(level, code, en, hi, kb)
        }
        // Blood pressure
        if (v.bpSys != null && v.bpDia != null) {
            val s = v.bpSys; val d = v.bpDia
            if (c in PP) {
                if (s >= 160 || d >= 110) add(Level.URGENT, "bp_severe", "BP $s/$d is severely high in pregnancy — refer to the facility now (call 102/108).", "गर्भावस्था में बीपी $s/$d बहुत ज़्यादा — तुरंत अस्पताल भेजें (102/108)।", "high blood pressure pregnancy pre-eclampsia referral")
                else if (s >= 140 || d >= 90) add(Level.REFER, "bp_high", "BP $s/$d is high in pregnancy (140/90 or more) — refer to the PHC today.", "गर्भावस्था में बीपी $s/$d ज़्यादा (140/90 या अधिक) — आज ही PHC भेजें।", "high blood pressure pregnancy referral")
            } else {
                if (s >= 180 || d >= 120) add(Level.URGENT, "bp_severe", "BP $s/$d is very high — refer to the facility today.", "बीपी $s/$d बहुत ज़्यादा — आज ही अस्पताल भेजें।", "very high blood pressure hypertension referral")
                else if (s >= 140 || d >= 90) add(Level.WATCH, "bp_high", "BP $s/$d is raised (140/90 or more) — refer for hypertension confirmation at the PHC/HWC.", "बीपी $s/$d बढ़ा हुआ (140/90 या अधिक) — पुष्टि के लिए PHC/HWC भेजें।", "hypertension screening raised blood pressure referral")
            }
            if (s < 90) add(Level.REFER, "bp_low", "BP $s/$d is low — refer today.", "बीपी $s/$d कम — आज ही रेफर करें।", "low blood pressure referral")
        }
        // Haemoglobin
        v.hb?.let { hb ->
            if (hb < 7) add(Level.REFER, "hb_severe", "Hb ${fmt(hb)} g/dL is severe anaemia — refer to the PHC/FRU.", "एचबी ${fmt(hb)} — गंभीर एनीमिया; PHC/FRU भेजें।", "severe anaemia haemoglobin below 7 referral")
            else if (c == Category.PREGNANT && hb < 11) add(Level.WATCH, "hb_low", "Hb ${fmt(hb)} g/dL is anaemia in pregnancy (below 11) — check IFA as per guideline and follow up.", "एचबी ${fmt(hb)} — गर्भावस्था में एनीमिया (11 से कम); दिशानिर्देश अनुसार आयरन गोली देखें।", "anaemia in pregnancy IFA tablets")
        }
        // Temperature
        v.tempC?.let { t ->
            when {
                c == Category.NEWBORN && t < 35.5 -> add(Level.REFER, "hypothermia", "Temperature ${fmt(t)} °C — newborn is too cold; warm skin-to-skin and refer.", "तापमान ${fmt(t)} °C — नवजात बहुत ठंडा; त्वचा से सटाकर गर्म करें और रेफर करें।", "newborn hypothermia temperature")
                c == Category.NEWBORN && t >= 37.5 -> add(Level.REFER, "fever_newborn", "Temperature ${fmt(t)} °C — fever in a newborn is a danger sign; refer today.", "तापमान ${fmt(t)} °C — नवजात में बुखार खतरे का लक्षण; आज ही रेफर करें।", "newborn fever danger sign")
                c in PP && t >= 38.0 -> add(Level.REFER, "fever_maternal", "Temperature ${fmt(t)} °C — fever in pregnancy or after delivery; refer today.", "तापमान ${fmt(t)} °C — गर्भावस्था/प्रसव के बाद बुखार; आज ही रेफर करें।", "fever pregnancy postpartum infection")
                t >= 38.0 -> add(Level.WATCH, "fever", "Temperature ${fmt(t)} °C — fever; test for malaria where advised and follow up.", "तापमान ${fmt(t)} °C — बुखार; जहाँ लागू हो मलेरिया जाँच करें और फ़ॉलो-अप करें।", "fever management malaria test")
            }
        }
        // Breathing (IMNCI fast breathing)
        v.respRate?.let { rr ->
            val ageMonths = record.ageYears?.let { it * 12 } ?: record.ageDays?.let { it / 30.0 }
            val limit = when {
                c == Category.NEWBORN || (ageMonths != null && ageMonths < 2) -> 60
                ageMonths != null && ageMonths < 12 -> 50
                c == Category.CHILD && (ageMonths == null || ageMonths < 60) -> 40
                else -> null
            }
            if (limit != null && rr >= limit) add(Level.REFER, "fast_breathing", "Breathing $rr per minute is fast breathing (limit $limit for this age) — possible pneumonia; refer.", "सांस $rr प्रति मिनट — तेज़ सांस (इस उम्र की सीमा $limit); निमोनिया हो सकता है, रेफर करें।", "fast breathing pneumonia child referral")
        }
        v.spo2?.let { s ->
            if (s < 90) add(Level.URGENT, "spo2_low", "SpO2 $s% is dangerously low — call 108.", "ऑक्सीजन $s% बहुत कम — 108 बुलाएँ।", "low oxygen saturation emergency")
            else if (s < 94) add(Level.REFER, "spo2_low", "SpO2 $s% is low — refer today.", "ऑक्सीजन $s% कम — आज ही रेफर करें।", "low oxygen saturation referral")
        }
        v.sugar?.let { g ->
            if ((v.sugarFasting && g >= 126) || (!v.sugarFasting && g >= 200)) add(Level.WATCH, "sugar_high", "Blood sugar $g mg/dL is high — refer for diabetes confirmation at the PHC/HWC.", "शुगर $g — ज़्यादा; डायबिटीज़ की पुष्टि के लिए PHC/HWC भेजें।", "diabetes screening blood sugar referral")
            if (g < 70) add(Level.REFER, "sugar_low", "Blood sugar $g mg/dL is low — give something sweet if awake and refer.", "शुगर $g — कम; होश में हो तो मीठा दें और रेफर करें।", "low blood sugar hypoglycaemia")
        }
        if (c == Category.NEWBORN) v.weightKg?.let { w ->
            if (w < 2.5) add(Level.REFER, "low_birth_weight", "Weight ${fmt(w)} kg is low birth weight (below 2.5 kg) — extra HBNC visits and care as per guideline; refer if very small.", "वजन ${fmt(w)} किलो — कम वजन (2.5 किलो से कम); HBNC के अनुसार अतिरिक्त विज़िट और देखभाल; बहुत छोटा हो तो रेफर करें।", "low birth weight newborn care HBNC")
        }
        if (c in KID) v.muacCm?.let { m ->
            if (m < 11.5) add(Level.REFER, "sam", "MUAC ${fmt(m)} cm is severe acute malnutrition (below 11.5 cm) — refer to the NRC.", "MUAC ${fmt(m)} सेमी — गंभीर कुपोषण (11.5 से कम); NRC भेजें।", "severe acute malnutrition MUAC NRC referral")
            else if (m < 12.5) add(Level.WATCH, "mam", "MUAC ${fmt(m)} cm is moderate malnutrition (11.5–12.5 cm) — nutrition counselling and follow-up.", "MUAC ${fmt(m)} सेमी — मध्यम कुपोषण; पोषण परामर्श और फ़ॉलो-अप।", "moderate acute malnutrition MUAC")
        }
        // Signs told in words
        val words = narrative + " " + record.symptoms.joinToString(" ")
        for (s in SIGNS) {
            if (s.only.isNotEmpty() && c !in s.only && c != Category.UNKNOWN) continue
            if (mentioned(s, words)) add(s.level, s.code, s.en, s.hi, s.kb)
        }
        return out.sortedBy { it.level.ordinal }
    }

    /** What a visit of this kind should record but this note does not. */
    fun missing(record: Record, narrative: String): List<Pair<String, String>> {
        val v = record.vitals
        // "temperature normal" / "bukhar nahi" is a recorded temperature even without a number.
        val tempSaid = v.tempC != null || Regex("(?i)(?:temp(?:erature)?|tapman|तापमान)\\s*(?:is\\s*)?(?:normal|theek|ठीक|सामान्य)|no\\s*fever|bukhar\\s*nahi|बुखार\\s*नहीं").containsMatchIn(narrative)
        val t = narrative.lowercase(Locale.ROOT) + " " + record.medicines.joinToString(" ").lowercase(Locale.ROOT)
        val out = ArrayList<Pair<String, String>>()
        fun need(ok: Boolean, en: String, hi: String) { if (!ok) out += en to hi }
        when (record.category) {
            Category.PREGNANT -> {
                need(v.bpSys != null, "BP", "बीपी")
                need(v.weightKg != null, "weight", "वजन")
                need(v.hb != null, "Hb", "एचबी")
                need(Regex("ifa|iron|आयरन|आईएफए|folic|फोलिक").containsMatchIn(t), "IFA tablets given or taken", "आयरन (IFA) गोली")
            }
            Category.POSTNATAL -> {
                need(v.bpSys != null, "BP", "बीपी")
                need(tempSaid, "temperature", "तापमान")
                need(Regex("bleed|खून|रक्तस्राव").containsMatchIn(t), "bleeding checked", "खून आना जाँचा")
            }
            Category.NEWBORN -> {
                need(v.weightKg != null, "weight", "वजन")
                need(tempSaid, "temperature", "तापमान")
                need(Regex("feed|doodh|दूध|स्तनपान|breast").containsMatchIn(t), "breastfeeding", "स्तनपान")
                need(Regex("cord|naal|नाल").containsMatchIn(t), "cord checked", "नाल जाँची")
            }
            Category.CHILD -> {
                need(v.weightKg != null, "weight", "वजन")
                need(v.muacCm != null || (record.ageYears ?: 0.0) < 0.5, "MUAC", "MUAC")
            }
            Category.ADULT, Category.ELDERLY -> need(v.bpSys != null, "BP", "बीपी")
            Category.UNKNOWN -> need(false, "who the visit was for (pregnant woman, newborn, child, adult)", "विज़िट किसके लिए थी (गर्भवती, नवजात, बच्चा, वयस्क)")
        }
        if (record.nextVisitDay == null) out += "next visit date" to "अगली विज़िट की तारीख़"
        return out
    }

    /** Rules, then the model (when [model] gives JSON), then the checks. */
    fun build(narrative: String, modelOutput: String?, today: Long = todayEpochDay()): Result {
        val rules = ruleRecord(narrative, today)
        val json = parseModel(modelOutput)
        val merged = merge(rules, json, narrative, today)
        val (checked, issues) = plausibility(merged)
        return Result(
            record = checked,
            alerts = alerts(checked, narrative),
            issues = issues,
            missing = missing(checked, narrative),
            hindi = isHindi(narrative),
            usedModel = json != null
        )
    }

    // ── Output ─────────────────────────────────────────────────────────────────────────────

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    private fun categoryLabel(c: Category, hi: Boolean): String = when (c) {
        Category.PREGNANT -> if (hi) "गर्भवती" else "pregnant"
        Category.POSTNATAL -> if (hi) "प्रसव के बाद" else "after delivery"
        Category.NEWBORN -> if (hi) "नवजात" else "newborn"
        Category.CHILD -> if (hi) "बच्चा" else "child"
        Category.ADULT -> if (hi) "वयस्क" else "adult"
        Category.ELDERLY -> if (hi) "बुज़ुर्ग" else "older adult"
        Category.UNKNOWN -> if (hi) "श्रेणी नहीं बताई" else "category not stated"
    }

    fun dateLabel(day: Long): String {
        val (y, m, d) = civil(day)
        return "$d ${listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[m - 1]} $y"
    }

    /** The record as the chat shows it (a table for the numbers, short lines for the rest). */
    fun render(result: Result, today: Long = todayEpochDay()): String {
        val r = result.record; val v = r.vitals; val hi = result.hindi
        fun L(en: String, h: String) = if (hi) h else en
        val flagged = result.alerts.map { it.code }.toSet()
        fun mark(vararg codes: String) = if (codes.any { it in flagged }) " ⚠" else ""
        return buildString {
            append("**").append(L("Visit note", "विज़िट नोट")).append(" — ").append(dateLabel(today)).append("**\n")
            val who = listOfNotNull(
                r.name,
                categoryLabel(r.category, hi) + (r.pregnancyMonths?.let { L(", ${fmt(it)} months", ", ${fmt(it)} महीने") } ?: ""),
                r.ageDays?.let { L("$it days old", "$it दिन") } ?: r.ageYears?.let { y ->
                    if (y < 2) Math.round(y * 12).let { m -> L("$m months", "$m महीने") } else L("${fmt(y)} years", "${fmt(y)} वर्ष")
                }
            )
            append(who.joinToString(" · ")).append("\n\n")
            if (result.alerts.isNotEmpty()) {
                result.alerts.forEach { a ->
                    val tag = when (a.level) { Level.URGENT -> L("URGENT", "तुरंत"); Level.REFER -> L("Refer", "रेफर"); Level.WATCH -> L("Watch", "ध्यान दें") }
                    append(if (a.level == Level.URGENT) "🔴 " else if (a.level == Level.REFER) "🟠 " else "🟡 ")
                    append(tag).append(": ").append(if (hi) a.hi else a.en)
                    a.citation?.let { append("\n   ").append(L("Source: ", "स्रोत: ")).append(it) }
                    append("\n")
                }
                append("\n")
            }
            val rows = ArrayList<Pair<String, String>>()
            if (v.bpSys != null && v.bpDia != null) rows += L("BP", "बीपी") to "${v.bpSys}/${v.bpDia} mmHg${mark("bp_severe", "bp_high", "bp_low")}"
            v.tempC?.let { rows += L("Temperature", "तापमान") to "${fmt(it)} °C${mark("hypothermia", "fever_newborn", "fever_maternal", "fever")}" }
            v.weightKg?.let { rows += L("Weight", "वजन") to "${fmt(it)} kg${mark("low_birth_weight")}" }
            v.hb?.let { rows += L("Hb", "एचबी") to "${fmt(it)} g/dL${mark("hb_severe", "hb_low")}" }
            v.pulse?.let { rows += L("Pulse", "नब्ज़") to "$it /min" }
            v.respRate?.let { rows += L("Breathing", "सांस") to "$it /min${mark("fast_breathing")}" }
            v.spo2?.let { rows += "SpO2" to "$it %${mark("spo2_low")}" }
            v.sugar?.let { rows += L(if (v.sugarFasting) "Sugar (fasting)" else "Sugar", if (v.sugarFasting) "शुगर (खाली पेट)" else "शुगर") to "$it mg/dL${mark("sugar_high", "sugar_low")}" }
            v.muacCm?.let { rows += "MUAC" to "${fmt(it)} cm${mark("sam", "mam")}" }
            if (rows.isNotEmpty()) {
                append("| ").append(L("Measured", "जाँच")).append(" | ").append(L("Value", "मान")).append(" |\n| --- | --- |\n")
                rows.forEach { (k, value) -> append("| ").append(k).append(" | ").append(value).append(" |\n") }
                append("\n")
            }
            fun line(label: String, items: List<String>) { if (items.isNotEmpty()) append(label).append(": ").append(items.joinToString("; ")).append("\n") }
            line(L("Symptoms", "लक्षण"), r.symptoms)
            line(L("Medicines", "दवाइयाँ"), r.medicines)
            line(L("Advice given", "दी गई सलाह"), r.advice)
            r.referral?.let { append(L("Referral", "रेफरल")).append(": ").append(it).append("\n") }
            r.nextVisitDay?.let { d ->
                val inDays = d - today
                append(L("Next visit", "अगली विज़िट")).append(": ").append(dateLabel(d))
                if (inDays in 0..60) append(L(" (in $inDays days)", " ($inDays दिन में)"))
                append("\n")
            }
            r.notes?.let { append(L("Note", "नोट")).append(": ").append(it).append("\n") }
            if (result.issues.isNotEmpty()) {
                append("\n").append(L("Re-check", "दोबारा जाँचें")).append(":\n")
                result.issues.forEach { append("• ").append(if (hi) it.hi else it.en).append("\n") }
            }
            if (result.missing.isNotEmpty()) {
                append("\n").append(L("Not recorded", "दर्ज नहीं")).append(": ")
                append(result.missing.joinToString(", ") { if (hi) it.second else it.first }).append("\n")
            }
            append("\n").append(
                L("Danger-sign checks follow national guideline thresholds (MCP card, HBNC, IMNCI, NCD); confirm with the ANM or medical officer.",
                    "खतरे के लक्षणों की जाँच राष्ट्रीय दिशानिर्देशों (MCP कार्ड, HBNC, IMNCI, NCD) के अनुसार है; ANM या चिकित्सा अधिकारी से पुष्टि करें।")
            )
        }.trim()
    }

    /** What to say when "Visit note" came with nothing told yet. */
    fun howTo(hindi: Boolean): String = if (hindi)
        "विज़िट के बारे में बताइए — किसके घर गए, बीपी, वजन, एचबी, तापमान, लक्षण, क्या दवा या सलाह दी, कहाँ रेफर किया और अगली विज़िट कब है। " +
            "उदाहरण: \"विज़िट नोट: सुनीता, 7 महीने की गर्भवती, बीपी 150/100, पैरों में सूजन, आयरन की गोली दी, 7 दिन बाद फिर आऊँगी।\""
    else "Tell me about the visit: who you saw, BP, weight, Hb, temperature, symptoms, medicines or advice given, any referral, and when the next visit is. " +
        "Example: \"Visit note: Sunita, 7 months pregnant, BP 150/100, swelling in feet, gave IFA, next visit in 7 days.\""

    // ── Saved record (for the due list) ────────────────────────────────────────────────────

    fun toJson(result: Result, savedDay: Long): JSONObject {
        val r = result.record; val v = r.vitals
        return JSONObject()
            .put("saved_day", savedDay)
            .put("name", r.name ?: JSONObject.NULL)
            .put("category", r.category.name)
            .put("pregnancy_months", r.pregnancyMonths ?: JSONObject.NULL)
            .put("age_years", r.ageYears ?: JSONObject.NULL)
            .put("bp", if (v.bpSys != null && v.bpDia != null) "${v.bpSys}/${v.bpDia}" else JSONObject.NULL)
            .put("hb", v.hb ?: JSONObject.NULL)
            .put("weight_kg", v.weightKg ?: JSONObject.NULL)
            .put("temp_c", v.tempC ?: JSONObject.NULL)
            .put("symptoms", JSONArray(r.symptoms))
            .put("referral", r.referral ?: JSONObject.NULL)
            .put("next_visit_day", r.nextVisitDay ?: JSONObject.NULL)
            .put("alerts", JSONArray(result.alerts.map { "${it.level.name}:${it.code}" }))
            .put("alert_text", JSONArray(result.alerts.map { if (result.hindi) it.hi else it.en }))
            .put("hindi", result.hindi)
    }

    // ── Calendar (days since 1970-01-01; no java.time: minSdk 24) ──────────────────────────

    fun todayEpochDay(nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(nowMs + tz.getOffset(nowMs), 86_400_000L)

    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
}
