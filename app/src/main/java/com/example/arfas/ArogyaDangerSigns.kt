package com.example.arfas

import java.time.temporal.ChronoUnit

/**
 * Deterministic danger-sign rules for the ASHA visit. Runs before (and independently of) any model.
 *
 * Rule sources (all present in the supplied ASHA knowledge base; cited in every alert):
 *  - Young infant (0–59 days), ASHA/ANM/CHO classification: not able to feed; convulsions; fast
 *    breathing (60/min or more); severe chest indrawing; axillary temperature 37.5 °C or above (or
 *    feels hot); below 35.5 °C (or feels cold); movement only when stimulated or no movement →
 *    refer urgently. SAANS 2025 Guidance Note, MoHFW, p.15.
 *  - Newborn temperature: normal 36.5–37.5 °C (97.7–99.5 °F); fever ≥37.5 °C (99.5 °F); cold
 *    <36 °C. Integrated ASHA Training Module Part B, NHSRC, p.28.
 *  - Postnatal mother: heavy bleeding, foul-smelling discharge, fever after delivery, convulsions →
 *    immediate referral. Integrated ASHA Module Part A p.76; Part B pp.21–22, 57.
 *
 * What it does: per clause, works out WHO the sign belongs to (baby / mother / someone else),
 * whether it is NEGATED, UNCERTAIN or only in the PAST, reads numeric temperatures (°C/°F, unit
 * inferred from range when missing) and breathing rates, and works out the baby's age from the
 * text ("7 din ka", "one week old"), distinguishing age from symptom duration ("fever for 7 days").
 *
 * What it is not: a triage system or a diagnosis. No match NEVER means the patient is safe. It
 * prescribes nothing (the source's antibiotic first-dose instruction is deliberately not turned
 * into app advice). THE THRESHOLDS AND WORDING REQUIRE REVIEW BY A QUALIFIED CLINICIAN BEFORE
 * FIELD USE.
 */
object ArogyaDangerSigns {

    enum class Subject { INFANT, MOTHER, OTHER, UNKNOWN }
    enum class Level { URGENT, CHECK }
    enum class Sign(val en: String, val hi: String) {
        FEVER("fever / feels hot (temperature 37.5 °C or above)", "बुखार / छूने पर गर्म (37.5 °C या अधिक)"),
        HYPOTHERMIA("too cold (below 35.5 °C) or cold to touch", "बहुत ठंडा (35.5 °C से कम) या छूने पर ठंडा"),
        COLD_STRESS("temperature below normal (below 36.5 °C)", "तापमान सामान्य से कम (36.5 °C से कम)"),
        NOT_FEEDING("not able to feed / not sucking", "दूध नहीं पी पा रहा / चूस नहीं रहा"),
        CONVULSIONS("convulsions / fits", "दौरे / झटके"),
        FAST_BREATHING("fast breathing (60 per minute or more)", "तेज़ सांस (60 प्रति मिनट या अधिक)"),
        CHEST_INDRAWING("severe chest indrawing", "छाती का अंदर धँसना"),
        LETHARGY("moves only when stimulated / no movement / unconscious", "छूने पर ही हिलता है / हिलता नहीं / बेहोश"),
        CYANOSIS("blue lips or tongue", "होंठ या जीभ नीली"),
        VOMITS_EVERYTHING("vomits everything", "सब कुछ उल्टी कर देता है"),
        SEVERE_JAUNDICE("yellow palms or soles / jaundice in first 24 hours", "हथेली या तलवे पीले / पहले 24 घंटे में पीलिया"),
        JAUNDICE("jaundice (check palms and soles)", "पीलिया (हथेली और तलवे देखें)"),
        HEAVY_BLEEDING("heavy bleeding after delivery", "प्रसव के बाद अधिक रक्तस्राव"),
        FOUL_DISCHARGE("foul-smelling discharge", "बदबूदार स्राव"),
        MATERNAL_FEVER("fever after delivery", "प्रसव के बाद बुखार"),
        MATERNAL_CONVULSIONS("convulsions / fits", "दौरे / झटके"),
        MATERNAL_UNCONSCIOUS("unconscious or very drowsy", "बेहोश या बहुत सुस्त"),
    }

    data class Finding(
        val subject: Subject, val sign: Sign, val level: Level,
        /** The words in the worker's text that triggered it. */
        val evidence: String,
        /** Why CHECK rather than URGENT (uncertain, past, subject unclear …), empty for URGENT. */
        val qualifier: String = "",
        val measuredCelsius: Double? = null
    )

    data class Assessment(
        val findings: List<Finding>,
        val infantAgeDays: Long?,
        val ageSource: String,
        /** Explicitly negated signs ("no fever"): shown to the worker, never treated as clearance. */
        val negated: List<String>
    ) {
        val urgent: Boolean get() = findings.any { it.level == Level.URGENT }
        val any: Boolean get() = findings.isNotEmpty()
    }

    const val RULESET = "asha-danger-signs-v1 (NOT CLINICALLY REVIEWED)"
    private const val SOURCE_INFANT = "SAANS 2025 Guidance Note (MoHFW) p.15; Integrated ASHA Module Part B (NHSRC) p.28"
    private const val SOURCE_MOTHER = "Integrated ASHA Module Part A p.76; Part B pp.21–22, 57 (NHSRC)"
    private const val YOUNG_INFANT_MAX_DAYS = 59L

    // ---------- subject vocabulary ----------
    private val INFANT_WORDS = Regex(
        "^(?:baby|babies|baby's|newborn|newborns|newborn's|new-born|neonate|infant|infant's|child|child's|son|daughter|" +
            "bachcha|bachche|bachchi|baccha|bacche|bacchi|bachha|bachhe|bacha|bache|shishu|navjat|navjaat|munna|munni|beta|beti|" +
            "बच्चा|बच्चे|बच्ची|बच्चों|शिशु|नवजात|बेटा|बेटी|मुन्ना|मुन्नी)$")
    private val MOTHER_WORDS = Regex(
        "^(?:mother|mother's|mom|mum|mummy|maa|ma|mata|wife|woman|lady|patni|bahu|prasuta|" +
            "माँ|मां|माता|मम्मी|पत्नी|महिला|बहू|प्रसूता)$")
    private val OTHER_WORDS = Regex(
        "^(?:father|father's|dad|papa|husband|grandmother|grandma|grandfather|dadi|nani|dada|nana|sister|brother|bhai|behen|" +
            "pati|sasur|saas|neighbour|neighbor|पिता|पापा|पति|दादी|नानी|दादा|नाना|भाई|बहन|सास|ससुर)$")
    private val ELDER_MODIFIER = Regex("^(?:elder|older|big|bada|badi|bade|बड़ा|बड़ी|बड़े|other|dusra|dusri|दूसरा|दूसरी)$")
    private val REPORTING = Regex("^(?:says|said|say|reports|reported|told|tells|informs|informed|complains|complained|mentions|mentioned|" +
        "bata|batati|batayi|bataya|batate|kehti|kaha|keh|बताया|बताती|बताती|कहती|कहा|कह)$")
    private val GENITIVE = Regex("^(?:ka|ki|ke|का|की|के)$")

    // ---------- clause splitting ----------
    private val CLAUSE_SPLIT = Regex(
        "(?<!\\d)\\.|\\.(?!\\d)|[!?।;\\n]+|,(?!\\d)|\\s(?:and|but|while|whereas|although|though|however|also|aur|lekin|par|magar|jabki|tatha|और|लेकिन|पर|मगर|जबकि|तथा)\\s")

    // ---------- negation / uncertainty / time ----------
    private val UNCERTAIN = Regex("\\b(?:maybe|may be|might|perhaps|possibly|probably|not sure|unsure|don't know|do not know|doubt|suspect(?:ed)?|" +
        "shayad|lagta hai|lag raha|pata nahi|pata nahin|malum nahi)\\b|शायद|पता नहीं|लगता है|लग रहा")
    private val PAST = Regex("\\b(?:yesterday|last (?:week|night|month)|ago|previously|earlier|used to|pehle|pichhle|kal raat)\\b|\\bhad\\b(?! been)|\\b(?:tha|thi)\\b|था|थी|पहले|पिछले|कल रात")
    private val ONGOING = Regex("\\b(?:has had|have had|since|still|abhi bhi|ab bhi|continues|ongoing|now)\\b|अभी भी|अब भी|से है")
    private val NEG_CUES = setOf("no", "not", "never", "without", "denies", "denied", "deny", "doesn't", "dont", "don't", "isn't", "wasn't",
        "hasn't", "free", "negative", "nahi", "nahin", "nhi", "na", "bina", "नहीं", "न", "बिना", "absent", "gone", "resolved", "normal",
        "theek", "thik", "ठीक", "utar", "उतर", "nil")
    private val TAG_PARTICLES = setOf("na", "न", "naa", "ना")
    private val NEG_FILLER = setOf("have", "has", "had", "having", "any", "a", "an", "the", "got", "get", "show", "shows", "showing", "signs",
        "sign", "of", "from", "symptoms", "symptom", "is", "are", "was", "does", "did", "do", "koi", "bhi", "ko", "ka", "ki", "ke", "को", "कोई",
        "भी", "का", "की", "के", "hai", "है", "to", "toh", "abhi", "ab", "bilkul", "now", "completely", "fully", "become", "ho", "gaya", "gayi",
        "गया", "गई", "हो", "baby", "baby's", "newborn", "bachche", "bacche", "बच्चे", "for")

    // ---------- sign patterns (positive phrases; self-negated phrases carry their own polarity) ----------
    private data class Pattern(val sign: Sign, val rx: Regex, val selfNegated: Boolean = false)
    private fun p(sign: Sign, r: String, selfNeg: Boolean = false) = Pattern(sign, Regex(r, RegexOption.IGNORE_CASE), selfNeg)

    private val INFANT_PATTERNS = listOf(
        p(Sign.FEVER, "\\bhigh fever\\b|\\bfever(?:ish)?\\b|\\bpyrexia\\b|\\bhot to (?:the )?touch|\\b(?:feels?|is|seems|looks) (?:very |too |so )?hot\\b|\\bbody (?:is )?(?:very )?hot|" +
            "\\bbu?khaa?r\\b|बुखार|ज्वर|\\btap rah[aie]|तप रह|गरम है|गर्म है|गरम लग|गर्म लग|\\bgaram (?:hai|lag)"),
        p(Sign.HYPOTHERMIA, "\\bcold to (?:the )?touch|\\bfeels? (?:very )?cold|\\bbody (?:is )?(?:very )?cold|hypotherm|\\bthanda (?:hai|lag|pad)|ठंडा (?:है|लग|पड़)"),
        p(Sign.NOT_FEEDING, "\\bnot (?:taking |able to )?(?:breast ?)?feed(?:ing|s)?\\b|\\bunable to (?:breast ?)?(?:feed|suck|drink)|\\bnot (?:able to )?suck(?:ing)?\\b|" +
            "\\bcan(?:no|')t (?:breast ?)?(?:feed|suck)|\\brefus(?:es|ing|ed) (?:to )?(?:breast ?)?feed|\\bstopped (?:breast ?)?feeding|" +
            "\\b(?:doodh|dudh) (?:nahi|nahin|nhi|na) (?:pi|pee|le|kh)|(?:doodh|dudh|stanpan) (?:nahi|nahin) kar|दूध (?:नहीं|न) (?:पी|ले|पि)|स्तनपान नहीं|चूस नहीं|\\bchoos (?:nahi|nahin)", selfNeg = true),
        p(Sign.NOT_FEEDING, "\\bpoor feeding\\b|\\bfeeding (?:problem|difficulty|issue)|\\bdifficulty (?:in )?(?:breast ?)?feeding"),
        p(Sign.CONVULSIONS, "convuls|\\bfits?\\b|seizure|\\bjhatk[ae]|झटक|\\bdaur[ae] (?:aa|pad|padt)|दौर[ाे] (?:आ|पड़)|\\bdaure\\b|दौरे|\\bmirgi|मिर्गी|\\bbody (?:goes )?stiff|akad"),
        p(Sign.FAST_BREATHING, "\\bfast breath|\\bbreathing (?:very )?fast|\\brapid breath|\\bbreathing (?:is )?rapid|\\btez saa?ns|\\bsaa?ns tez|तेज़? सा[ंँ]स|सा[ंँ]स तेज़?|हाँफ|\\bhaanf"),
        p(Sign.CHEST_INDRAWING, "chest in-?drawing|chest (?:is )?(?:sinking|pulling in|going in)|\\bpasli chal|पसली चल|chhati (?:andar )?dhans|छाती (?:अंदर )?धँस|छाती धंस"),
        p(Sign.LETHARGY, "letharg|unconscious|\\blimp\\b|floppy|\\bnot moving\\b|\\bno movement|\\bmoves? only when (?:touched|stimulated|disturbed)|" +
            "\\bnot (?:waking|responding)|\\bsust (?:hai|pad|ho)|सुस्त|\\bbehosh|बेहोश|हिल(?:-डुल)? नहीं रह|\\bhil (?:nahi|nahin) rah", selfNeg = true),
        p(Sign.CYANOSIS, "\\bblue (?:lips|tongue)|(?:lips|tongue) (?:are |is |look )?blue|cyanos|\\bneel[ae] (?:honth|hont|jeebh)|नीले? (?:होंठ|जीभ)"),
        p(Sign.VOMITS_EVERYTHING, "\\bvomit(?:s|ing)? everything|\\bsab (?:kuch )?ult|सब (?:कुछ )?उल्टी"),
        p(Sign.SEVERE_JAUNDICE, "yellow (?:palms|soles)|(?:palms|soles)[a-z ]{0,20} yellow|\\bjaundice (?:in|within) (?:the )?first (?:24 hours|day)|" +
            "(?:hatheli|talve|talwe)[a-z ]{0,12}peel|हथेली[^.]{0,15}पील|तलव[ेों][^.]{0,15}पील"),
        p(Sign.JAUNDICE, "jaundice|\\bpeeli?ya\\b|\\bpiliya\\b|पीलिया|\\byellow (?:skin|eyes|body)|(?:skin|eyes) (?:are |is |look )?yellow"),
    )
    private val MOTHER_PATTERNS = listOf(
        p(Sign.HEAVY_BLEEDING, "\\bheavy bleed|\\bbleeding (?:a lot|heavily|too much|excessive)|excessive bleed|\\bsoak(?:s|ing|ed)? [a-z ]{0,12}pads?|" +
            "\\bmore than (?:2|two|5|five) pads|\\blarge clots|haemorrhage|hemorrhage|\\b(?:bahut|zyada|jyada) khoo?n|अधिक रक्तस्राव|(?:बहुत|ज़्यादा|ज्यादा) खून"),
        p(Sign.FOUL_DISCHARGE, "foul[- ]?smell|smelly discharge|bad smell|\\bbadbu|बदबू"),
        p(Sign.MATERNAL_FEVER, "\\bhigh fever\\b|\\bfever(?:ish)?\\b|\\bchills\\b|\\bbu?khaa?r\\b|बुखार|ज्वर"),
        p(Sign.MATERNAL_CONVULSIONS, "convuls|\\bfits?\\b|seizure|\\bjhatk[ae]|झटक|\\bdaure\\b|दौरे|\\bdaur[ae] (?:aa|pad)|दौर[ाे] (?:आ|पड़)"),
        p(Sign.MATERNAL_UNCONSCIOUS, "unconscious|\\bbehosh|बेहोश|not responding|\\bfainted|\\bfaint(?:ing)?\\b"),
    )

    // ---------- age ----------
    private val AGE_PATTERNS = listOf(
        Regex("(\\d{1,3})\\s*[- ]?\\s*(day|days|week|weeks|month|months)[- ]?old"),
        Regex("(\\d{1,3})\\s*[- ]?\\s*(day|days|week|weeks|month|months)\\s+(?:new ?born|newborn|baby|infant|child|neonate)"),
        Regex("(?:age|aged|umar|umr|उम्र|आयु)\\s*(?:is|:|-)?\\s*(\\d{1,3})\\s*(day|days|week|weeks|month|months|din|दिन|hafte|हफ्ते|mahine|महीने)"),
        Regex("(\\d{1,3})\\s*(din|दिन|hafte|hafta|हफ्ते|हफ़्ते|सप्ताह|mahine|mahina|महीने|महीना)\\s*(?:ka|ki|ke|का|की|के|ki umar|पुराना)(?![\\p{L}])"),
        Regex("(?:baby|bachch?[ae]|bacch?[ae]|बच्च[ाेी]|शिशु|navjaa?t|नवजात)\\s+(?:is\\s+)?(\\d{1,3})\\s*(day|days|week|weeks|month|months|din|दिन)(?:\\s+old|\\s+(?:ka|ki|ke|का|की|के))"),
    )
    private val DURATION_BEFORE = Regex("(?:for|since|past|last|from|over)\\s*$")

    private fun toDays(n: Long, unit: String): Long = when {
        unit.startsWith("week") || unit.startsWith("haft") || unit.startsWith("हफ") || unit.startsWith("सप्ताह") -> n * 7
        unit.startsWith("month") || unit.startsWith("mahin") || unit.startsWith("महीन") -> n * 30
        else -> n
    }

    fun textAgeDays(t: String): Long? {
        for (rx in AGE_PATTERNS) for (m in rx.findAll(t)) {
            if (DURATION_BEFORE.containsMatchIn(t.substring(0, m.range.first))) continue
            val after = t.substring(m.range.last + 1).trimStart()
            if (after.startsWith("se") || after.startsWith("से")) continue // "7 din se" = for 7 days
            val n = m.groupValues[1].toLongOrNull() ?: continue
            return toDays(n, m.groupValues[2])
        }
        return null
    }

    // ---------- measurements ----------
    private val TEMP = Regex("(\\d{2,3}(?:\\.\\d{1,2})?)\\s*(?:°|º|degrees?|deg|डिग्री)?\\s*(c|celsius|centigrade|f|fahrenheit|सेल्सियस|फ़ारेनहाइट|फारेनहाइट)?(?![\\p{L}\\d])")
    private val TEMP_CONTEXT = Regex("temp|thermometer|tapmaan|तापमान|fever|bukhar|बुखार|°|degree|डिग्री")
    private val BREATH_RATE = Regex("(\\d{2,3})\\s*(?:breaths?|/\\s*min|per minute|bpm|saa?ns? prati minute|सांस प्रति मिनट)")
    private val BREATH_CONTEXT = Regex("breath|respirat|saa?ns|सांस|साँस|rr\\b")

    private fun celsius(value: Double, unit: String?): Double? = when {
        unit != null && (unit.startsWith("f") || unit.startsWith("फ")) -> (value - 32) * 5 / 9
        unit != null -> value
        value in 30.0..45.0 -> value
        value in 86.0..113.0 -> (value - 32) * 5 / 9
        else -> null
    }

    // ---------- core ----------
    private data class Mention(val subject: Subject, val tokenIndex: Int)

    private fun tokens(clause: String) = clause.split(Regex("[\\s]+")).map { it.trim('(', ')', '"', ':', '-', '\'') }.filter { it.isNotEmpty() }

    private fun mentions(clause: String): List<Mention> {
        val tok = tokens(clause)
        val raw = tok.mapIndexedNotNull { i, w ->
            val s = when {
                INFANT_WORDS.matches(w) && !(i > 0 && ELDER_MODIFIER.matches(tok[i - 1])) -> Subject.INFANT
                INFANT_WORDS.matches(w) -> Subject.OTHER
                MOTHER_WORDS.matches(w) -> Subject.MOTHER
                OTHER_WORDS.matches(w) -> Subject.OTHER
                else -> null
            }
            s?.let { Mention(it, i) }
        }
        // Drop modifiers and reporters: "baby's mother" / "bachche ki maa" → mother;
        // "mother of the baby" → mother; "mother says baby …" → baby.
        return raw.filterIndexed { idx, m ->
            val next = raw.getOrNull(idx + 1)
            val prev = raw.getOrNull(idx - 1)
            val w = tok[m.tokenIndex]
            val possessiveHead = next != null && next.tokenIndex - m.tokenIndex <= 2 &&
                (w.endsWith("'s") || (m.tokenIndex + 1 < tok.size && GENITIVE.matches(tok[m.tokenIndex + 1])))
            val ofComplement = prev != null && m.tokenIndex - prev.tokenIndex <= 3 &&
                tok.subList(prev.tokenIndex + 1, m.tokenIndex).any { it == "of" }
            val reporter = (1..2).any { d -> tok.getOrNull(m.tokenIndex + d)?.let { REPORTING.matches(it) } == true }
            !possessiveHead && !ofComplement && !reporter
        }
    }

    /** Character offset → token index, for "nearest subject before the symptom". */
    private fun tokenAt(clause: String, offset: Int): Int = tokens(clause.substring(0, offset.coerceIn(0, clause.length))).size

    /** Token walk: a negation cue governs the symptom only if nothing but filler words lies between them. */
    private fun negated(clause: String, start: Int, end: Int): Boolean {
        val before = tokens(UNCERTAIN.replace(clause.substring(0, start), " ")).map { it.trim(',', '.') }
        val after = tokens(UNCERTAIN.replace(clause.substring(end), " ")).map { it.trim(',', '.') }
        for (w in before.asReversed()) { if (w in NEG_CUES) return true; if (w !in NEG_FILLER) break }
        // After the symptom, "na"/"न" is the Hinglish tag question ("bukhar hai na?" = "has fever, right?"), not negation.
        for (w in after) { if (w in NEG_CUES && w !in TAG_PARTICLES) return true; if (w !in NEG_FILLER) break }
        return false
    }

    fun evaluate(input: String, visit: ArogyaVisit): Assessment {
        val text = ArogyaClinicalText.normalise(input)
        val recorded = ArogyaWorkflow.parseDate(visit.birthDate)?.let { birth ->
            ArogyaWorkflow.parseDate(visit.date)?.let { ChronoUnit.DAYS.between(birth, it) }
        }?.takeIf { it >= 0 }
        val stated = textAgeDays(text)
        val (ageDays, ageSource) = when {
            stated != null -> stated to "stated in text"
            recorded != null -> recorded to "visit record"
            Regex("new ?born|neonat|navjaa?t|नवजात").containsMatchIn(text) -> 0L to "'newborn' in text"
            visit.kind == ArogyaKind.NEWBORN -> 0L to "mother & newborn visit (age not recorded)"
            else -> null to "unknown"
        }
        val youngInfant = ageDays != null && ageDays <= YOUNG_INFANT_MAX_DAYS
        val postnatalContext = visit.kind == ArogyaKind.NEWBORN || youngInfant ||
            Regex("after delivery|post-?partum|postnatal|delivered|prasav|प्रसव|delivery ke baad").containsMatchIn(text)

        val findings = ArrayList<Finding>()
        val negatedSigns = ArrayList<String>()
        var carried = Subject.UNKNOWN
        val clauses = text.split(CLAUSE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        for (clause in clauses) {
            val ms = mentions(clause)
            val uncertain = UNCERTAIN.containsMatchIn(clause)
            val past = PAST.containsMatchIn(clause) && !ONGOING.containsMatchIn(clause)

            fun subjectFor(offset: Int): Subject {
                if (ms.isEmpty()) return carried
                val at = tokenAt(clause, offset)
                return (ms.lastOrNull { it.tokenIndex <= at } ?: ms.first()).subject
            }

            fun add(sign: Sign, subject: Subject, evidence: String, celsius: Double? = null, forceCheck: String = "") {
                val qualifier = when {
                    forceCheck.isNotEmpty() -> forceCheck
                    uncertain -> "reported as uncertain — check now"
                    past -> "reported in the past — check whether it is present now"
                    subject == Subject.UNKNOWN -> "not stated whose sign this is — confirm"
                    else -> ""
                }
                val level = if (qualifier.isEmpty() || qualifier.startsWith("not stated")) Level.URGENT else Level.CHECK
                if (findings.none { it.sign == sign && it.subject == subject && it.level == level })
                    findings += Finding(subject, sign, level, evidence, qualifier, celsius)
            }

            // Infant signs (also applied when the subject is unknown in an infant context).
            for (pat in INFANT_PATTERNS) for (m in pat.rx.findAll(clause)) {
                val subject = subjectFor(m.range.first)
                val infantLike = subject == Subject.INFANT || (subject == Subject.UNKNOWN && youngInfant)
                if (!infantLike) continue
                if (!youngInfant) continue
                if (!pat.selfNegated && negated(clause, m.range.first, m.range.last + 1)) { negatedSigns += m.value; continue }
                if (pat.sign == Sign.JAUNDICE && findings.any { it.sign == Sign.SEVERE_JAUNDICE }) continue
                if (pat.sign == Sign.JAUNDICE) { add(pat.sign, subject, m.value, forceCheck = "check palms and soles; inform ANM"); continue }
                add(pat.sign, subject, m.value)
            }
            // Mother signs, only in a postnatal context.
            if (postnatalContext) for (pat in MOTHER_PATTERNS) for (m in pat.rx.findAll(clause)) {
                val subject = subjectFor(m.range.first)
                if (subject != Subject.MOTHER) continue
                if (negated(clause, m.range.first, m.range.last + 1)) { negatedSigns += m.value; continue }
                add(pat.sign, subject, m.value)
            }
            // Numeric temperature.
            if (TEMP_CONTEXT.containsMatchIn(clause)) for (m in TEMP.findAll(clause)) {
                val v = m.groupValues[1].toDoubleOrNull() ?: continue
                val unit = m.groupValues[2].ifBlank { null }
                // Skip ages/durations ("7 days", "39 din").
                val tail = clause.substring(m.range.last + 1).trimStart()
                if (Regex("^(?:days?|din|दिन|weeks?|months?|hours?|ghante|घंटे|breaths?|/min|kg|g\\b|gm|ml)").containsMatchIn(tail)) continue
                val c = celsius(v, unit) ?: continue
                val subject = subjectFor(m.range.first)
                val label = m.value.trim()
                when (subject) {
                    Subject.INFANT, Subject.UNKNOWN -> if (youngInfant) when {
                        c >= 37.5 -> add(Sign.FEVER, subject, label, c)
                        c < 35.5 -> add(Sign.HYPOTHERMIA, subject, label, c)
                        c < 36.5 -> add(Sign.COLD_STRESS, subject, label, c, forceCheck = "keep baby warm (skin-to-skin), re-measure; refer if not warmer")
                        else -> negatedSigns += "temperature $label (normal range)"
                    }
                    Subject.MOTHER -> if (postnatalContext && c >= 38.0) add(Sign.MATERNAL_FEVER, subject, label, c)
                    else -> {}
                }
            }
            // Breathing rate.
            if (BREATH_CONTEXT.containsMatchIn(clause)) for (m in BREATH_RATE.findAll(clause)) {
                val rate = m.groupValues[1].toIntOrNull() ?: continue
                val subject = subjectFor(m.range.first)
                if (youngInfant && (subject == Subject.INFANT || subject == Subject.UNKNOWN) && rate >= 60) add(Sign.FAST_BREATHING, subject, m.value.trim())
            }
            if (ms.isNotEmpty()) carried = ms.last().subject
        }
        // A sign stated positively in one clause and negated later ("had fever, now no fever") stays a CHECK, never cleared.
        return Assessment(findings, ageDays, ageSource, negatedSigns.distinct())
    }

    /** Worker-facing alert, or null when no rule matched (which is NOT clearance). */
    /** Short alert: at most 3 lines, said once. Rule sources stay in [SOURCE_INFANT] / [SOURCE_MOTHER] for the source sheet. */
    fun message(a: Assessment): String? {
        if (!a.any) return null
        val infant = a.findings.filter { it.subject != Subject.MOTHER }
        val mother = a.findings.filter { it.subject == Subject.MOTHER }
        val urgent = a.findings.filter { it.level == Level.URGENT }
        val check = a.findings.filter { it.level == Level.CHECK }
        return buildString {
            if (urgent.isNotEmpty()) {
                val who = if (urgent.all { it.subject == Subject.MOTHER }) "Mother" else "Baby"
                appendLine("⚠ $who: " + urgent.joinToString(", ") { it.sign.en + measured(it) } + " — danger sign.")
                appendLine(if (who == "Baby") "Refer now: call ANM / 108. Keep baby warm, keep breastfeeding." else "Refer now: call ANM / 108, take her to the facility.")
                append("तुरंत रेफ़र करें — ANM / 108 को फोन करें।")
            } else if (check.isNotEmpty()) {
                val c = check.first()
                appendLine("Check now: ${c.sign.en}${measured(c)} — ${c.qualifier}.")
                append("If it is present, refer: call ANM / 108.")
            }
        }.trim().ifBlank { null }
    }

    /** Rule sources for the alert, shown only when the worker taps "Why?". */
    fun ruleSources(a: Assessment): String = listOfNotNull(
        SOURCE_INFANT.takeIf { a.findings.any { it.subject != Subject.MOTHER } },
        SOURCE_MOTHER.takeIf { a.findings.any { it.subject == Subject.MOTHER } }).joinToString("; ")

    private fun measured(f: Finding) = f.measuredCelsius?.let { " — measured %.1f °C".format(java.util.Locale.ROOT, it) }.orEmpty()
}
