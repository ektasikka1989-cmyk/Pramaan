package com.example.arfas

import java.time.LocalDate

/*
 * Add-a-person helpers: CSV parsing, header synonyms (EN+HI), date parsing,
 * duplicate rule, Aadhaar masking and extraction grounding. Pure Kotlin,
 * host-tested. No Android imports.
 */

/** CSV import: quote-aware parse, header matching, dates, template. */
object ArogyaPersonCsv {
    /** Canonical column keys. */
    val KEYS = listOf("category", "name", "relative", "age", "dob", "sex", "village", "household",
        "phone", "rch", "lmp", "anc", "deliverydate", "deliveryplace", "weight", "fp")

    val SYNONYMS = mapOf(
        "category" to listOf("category", "type", "group", "cat", "shreni", "prakar", "varg", "श्रेणी", "प्रकार", "वर्ग"),
        "name" to listOf("name", "fullname", "beneficiary", "beneficiaryname", "person", "naam", "pooraanaam", "labharthi", "नाम", "पूरानाम", "लाभार्थी", "व्यक्ति"),
        "relative" to listOf("relative", "husband", "mother", "father", "guardian", "husbandname", "mothername", "fathername", "husbandmother", "relativeguardian",
            "pati", "maan", "ma", "pita", "patikaanaam", "maankaanaam", "pitakaanaam", "abhhibhavak", "rishtedar", "पति", "माँ", "मा", "पिता", "अभिभावक", "रिश्तेदार",
            "पतिकानाम", "माँकानाम", "माकानाम", "पिताकानाम"),
        "age" to listOf("age", "years", "umr", "aayu", "उम्र", "आयु"),
        "dob" to listOf("dob", "dateofbirth", "birthdate", "birthday", "janmatithi", "janm", "जन्मतिथि", "जन्म"),
        "sex" to listOf("sex", "gender", "ling", "लिंग"),
        "village" to listOf("village", "hamlet", "gaon", "tola", "gram", "गाँव", "टोला", "ग्राम"),
        "household" to listOf("household", "house", "houseno", "householdno", "family", "familyhead", "makaan", "parivar", "mukhiya", "मकान", "परिवार", "मुखिया"),
        "phone" to listOf("phone", "mobile", "mobileno", "contact", "contactno", "phoneno", "phone", "मोबाइल", "संपर्क", "फ़ोन", "फोन"),
        "rch" to listOf("rch", "rchid", "rchno", "rch", "आरसीएच"),
        "lmp" to listOf("lmp", "lastmenstrualperiod", "lastperiod", "antimmahavari", "एलएमपी", "अंतिममाहवारी"),
        "anc" to listOf("anc", "ancno", "ancnumber", "एएनसी"),
        "deliverydate" to listOf("deliverydate", "dateofdelivery", "dod", "prasavtithi", "delivery", "प्रसवतिथि", "डिलीवरी"),
        "deliveryplace" to listOf("deliveryplace", "birthplace", "placeofbirth", "prasavsthan", "janmasthan", "प्रसवस्थान", "जन्मस्थान"),
        "weight" to listOf("weight", "birthweight", "wt", "vazan", "janmavazan", "वज़न", "वजन", "जन्मवज़न"),
        "fp" to listOf("fp", "familyplanning", "fpmethod", "contraceptive", "method", "saadhan", "parivarniyojan", "परिवारनियोजन", "साधन"),
        "aadhaar" to listOf("aadhaar", "aadhar", "adhaar", "adhar", "aadhaarno", "aadharno", "aadhaarnumber", "uid", "uidai", "आधार", "आधारनंबर")
    )

    fun normHeader(h: String): String = h.trim().lowercase().replace(Regex("[\\s_.\\-]+"), "")

    /** Column index → canonical key; unknown headers map to "". First header wins on duplicates. */
    fun matchHeaders(headers: List<String>): Map<Int, String> {
        val used = mutableSetOf<String>()
        return headers.mapIndexed { i, h ->
            val n = normHeader(h)
            val key = SYNONYMS.entries.firstOrNull { (_, syns) -> syns.any { normHeader(it) == n } }?.key.orEmpty()
            val take = key.isNotBlank() && key !in used
            if (take) used += key
            i to if (take) key else ""
        }.toMap().let { exact ->
            // Headings in other wordings ("Contact details", "Husband's name"): the same meaning-based match as photos.
            exact.mapValues { (i, k) -> if (k.isNotBlank()) k else ArogyaTableOcr.headerKey(headers[i]).takeIf { it.isNotBlank() && it !in used }?.also { used += it }.orEmpty() }
        }
    }

    /** Minimal CSV parser: commas, quotes ("" escape), CR/LF tolerant. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        fun endCell() { row += cell.toString(); cell.clear() }
        fun endRow() { endCell(); if (row.any { it.isNotBlank() }) rows += row.toList(); row.clear() }
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> endCell()
                !q && (c == '\n' || c == '\r') -> { endRow(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> cell.append(c)
            }
            i++
        }
        endRow()
        return rows
    }

    /** dd/MM/yyyy, dd-MM-yyyy or yyyy-MM-dd → ISO yyyy-MM-dd; null when invalid. */
    fun parseDate(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        fun iso(d: Int, m: Int, y: Int) = runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
        Regex("""^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$""").matchEntire(t)?.let { m ->
            return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").matchEntire(t)?.let { m ->
            return iso(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        return null
    }

    private fun catOf(s: String): BenCategory? {
        val n = normHeader(s)
        return when {
            n.isEmpty() -> null
            listOf("pregnant", "pregnancy", "pw", "garbhvati", "garbhvatimahila", "गर्भवती").any { n.contains(it) } -> BenCategory.PREGNANT
            listOf("mother", "mom", "postnatal", "maa", "ma", "माँ", "मा").any { n.contains(it) } -> BenCategory.MOTHER
            listOf("child", "children", "infant", "baby", "bachcha", "bacha", "shishu", "बच्चा").any { n.contains(it) } -> BenCategory.CHILD
            listOf("couple", "eligible", "ec", "dampati", "yogya", "दंपति").any { n.contains(it) } -> BenCategory.ELIGIBLE_COUPLE
            listOf("other", "anya", "अन्य").any { n.contains(it) } -> BenCategory.OTHER
            else -> null
        }
    }

    /** Row errors for the import preview: missing name, bad date, unknown category. */
    fun rowErrors(row: Map<String, String>): List<String> {
        val errs = ArrayList<String>()
        if (row["name"].isNullOrBlank()) errs += "missing name"
        listOf("dob" to row["dob"], "lmp" to row["lmp"], "deliverydate" to row["deliverydate"]).forEach { (k, v) ->
            if (!v.isNullOrBlank() && parseDate(v) == null) errs += "bad $k: $v"
        }
        if (!row["category"].isNullOrBlank() && catOf(row["category"]!!) == null) errs += "unknown category: ${row["category"]}"
        return errs
    }

    /** Template: one header + 2 fictional example rows per category (CSV; Excel opens CSV). */
    fun template(): String {
        val h = "Category,Name,Husband/Mother,Age,DOB,Sex,Village,Household,Phone,RCH ID,LMP,ANC No,Delivery date,Delivery place,Birth weight (kg),FP method"
        val rows = listOf(
            "Pregnant woman,Meera Devi,Ramesh Kumar,24,,,,Rampur,H-101 Meera family,9811100001,RCH1001,10/08/2026,2,,,,",
            "Pregnant woman,Geeta Devi,Suresh Kumar,27,,,,Sundarpur,H-102 Geeta family,9811100002,RCH1002,01/07/2026,3,,,,",
            "Mother (postnatal),Rekha Devi,Mohan Lal,26,,,,Rampur,H-103 Rekha family,9811100003,,,,15/10/2026,Hospital,,,",
            "Mother (postnatal),Usha Rani,Kishore Kumar,29,,,,Sundarpur,H-104 Usha family,9811100004,,,,20/09/2026,Home,,,",
            "Child,Baby of Rekha,Rekha Devi,,15/10/2026,M,Rampur,H-103 Rekha family,,,,,15/10/2026,Hospital,2.8,",
            "Child,Baby of Usha,Usha Rani,,20/09/2026,F,Sundarpur,H-104 Usha family,,,,,20/09/2026,Home,3.1,",
            "Eligible couple,Pooja Kumari,Rajesh Kumar,22,,,,Rampur,H-105 Pooja family,9811100005,,,,,,,Condom",
            "Eligible couple,Anita Devi,Vijay Kumar,25,,,,Sundarpur,H-106 Anita family,9811100006,,,,,,,OCP",
            "Other,Ram Lal,,60,,,,Rampur,H-107 Ram family,9811100007,,,,,,,,",
            "Other,Shanti Devi,,65,,,,Sundarpur,H-108 Shanti family,9811100008,,,,,,,,"
        )
        return (listOf(h) + rows).joinToString("\n")
    }

    /** Map row (canonical keys) → Beneficiary; unknown category falls back to OTHER. */
    fun toBeneficiary(row: Map<String, String>): Beneficiary {
        val cat = row["category"]?.let { catOf(it) } ?: BenCategory.OTHER
        val sexRaw = row["sex"].orEmpty().trim().lowercase()
        val sex = when {
            sexRaw.startsWith("m") || sexRaw.contains("boy") || sexRaw.contains("लड़का") -> "M"
            else -> "F"
        }
        val placeRaw = row["deliveryplace"].orEmpty().lowercase()
        return Beneficiary(
            name = row["name"].orEmpty().trim().take(80),
            relativeName = row["relative"].orEmpty().trim().take(80),
            village = row["village"].orEmpty().trim().take(80),
            phone = row["phone"].orEmpty().filter { it.isDigit() || it == '+' }.take(15),
            household = row["household"].orEmpty().trim().take(60),
            age = row["age"].orEmpty().filter { it.isDigit() }.take(3),
            sex = sex, category = cat,
            dob = row["dob"]?.let { parseDate(it) }.orEmpty(),
            lmp = row["lmp"]?.let { parseDate(it) }.orEmpty(),
            deliveryDate = row["deliverydate"]?.let { parseDate(it) }.orEmpty(),
            deliveryPlace = when {
                placeRaw.contains("home") || placeRaw.contains("घर") -> "HOME"
                placeRaw.isBlank() -> ""
                else -> "INSTITUTION"
            },
            birthWeightKg = row["weight"]?.toDoubleOrNull()?.takeIf { it in 0.3..8.0 },
            rchId = row["rch"].orEmpty().trim().take(30),
            ancNumber = row["anc"].orEmpty().trim().take(10),
            familyPlanning = row["fp"].orEmpty().trim().take(60),
            // Columns the app has no field for are kept, not dropped ("Blood group: B+").
            notes = row["notes"].orEmpty().trim().take(400),
            aadhaar = row["aadhaar"].orEmpty().filter { it.isDigit() }.takeIf { it.length == 12 }.orEmpty()
        )
    }
}

/** Duplicate rule: same phone, or same name + husband/mother + village (normalised). */
object ArogyaPersonDup {
    fun norm(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun phoneKey(p: String): String = p.filter { it.isDigit() }.takeLast(10)

    fun find(cand: Beneficiary, existing: List<Beneficiary>): Beneficiary? {
        val ph = phoneKey(cand.phone)
        if (ph.length >= 10) existing.firstOrNull { phoneKey(it.phone) == ph && it.id != cand.id }?.let { return it }
        if (cand.name.isBlank()) return null
        return existing.firstOrNull {
            it.id != cand.id && norm(it.name) == norm(cand.name) &&
                norm(it.relativeName) == norm(cand.relativeName) && norm(it.village) == norm(cand.village)
        }
    }

    /** Merge: keep the existing record, fill its blank fields from the new one. */
    fun merge(old: Beneficiary, new: Beneficiary): Beneficiary {
        fun s(a: String, b: String) = if (a.isBlank()) b else a
        return old.copy(
            name = s(old.name, new.name), relativeName = s(old.relativeName, new.relativeName),
            village = s(old.village, new.village), phone = s(old.phone, new.phone),
            household = s(old.household, new.household), age = s(old.age, new.age),
            dob = s(old.dob, new.dob), lmp = s(old.lmp, new.lmp),
            deliveryDate = s(old.deliveryDate, new.deliveryDate), deliveryPlace = s(old.deliveryPlace, new.deliveryPlace),
            birthWeightKg = old.birthWeightKg ?: new.birthWeightKg,
            sncuDischarge = s(old.sncuDischarge, new.sncuDischarge),
            rchId = s(old.rchId, new.rchId), ancNumber = s(old.ancNumber, new.ancNumber),
            familyPlanning = s(old.familyPlanning, new.familyPlanning),
            notes = (old.notes + " " + new.notes).trim(), updatedAt = System.currentTimeMillis()
        )
    }
}

/** Photo/PDF extraction: prompt, KEY: value parse, OCR grounding, Aadhaar masking. */
object ArogyaPersonExtract {
    fun prompt(ocr: String): String = """Read this card or page and copy the person's details EXACTLY as printed.
Only fill a line when the value is printed below; otherwise leave it blank. Never guess.
Reply with exactly these lines, nothing else:
NAME:
RELATIVE:
AGE:
DOB:
SEX:
VILLAGE:
HOUSEHOLD:
PHONE:
AADHAAR:
RCH:
LMP:
ANC:
DELIVERYDATE:
DELIVERYPLACE:
WEIGHT:
FP:
TEXT:
$ocr"""

    private val KEYMAP = mapOf("name" to "name", "relative" to "relative", "age" to "age", "dob" to "dob",
        "sex" to "sex", "village" to "village", "household" to "household", "phone" to "phone", "rch" to "rch",
        "lmp" to "lmp", "anc" to "anc", "deliverydate" to "deliverydate", "deliveryplace" to "deliveryplace",
        "weight" to "weight", "fp" to "fp", "aadhaar" to "aadhaar")

    fun parse(out: String): Map<String, String> {
        val m = mutableMapOf<String, String>()
        out.lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val k = KEYMAP[line.substring(0, i).trim().lowercase().replace(Regex("[\\s_]+"), "")]
                if (k != null) m[k] = line.substring(i + 1).trim()
            }
        }
        return m
    }

    private fun norm(s: String): String = s.trim().lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]+"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Grounding: keep a value only when its text appears in the source. Names need
     * 2+ matching tokens (or one token of 4+ letters); numbers/dates need the digits.
     */
    fun ground(fields: Map<String, String>, source: String): Map<String, String> {
        val hay = norm(source)
        val hayDigits = source.filter { it.isDigit() }
        return fields.filterValues { v ->
            val n = norm(v)
            if (n.isEmpty()) false
            else if (v.any { it.isDigit() }) {
                val vd = v.filter { it.isDigit() }
                vd.length >= 2 && hayDigits.contains(vd)
            } else {
                val toks = n.split(" ").filter { it.isNotBlank() }
                toks.count { hay.contains(it) } >= 2 || toks.any { it.length >= 4 && hay.contains(it) }
            }
        }
    }

    private val AADHAAR = Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""")
    fun hasAadhaar(text: String): Boolean = AADHAAR.containsMatchIn(text)
    fun maskAadhaar(text: String): String = text.replace(AADHAAR, "[Aadhaar removed]")
    /** How an Aadhaar number is shown on screen (UIDAI practice): only the last 4 digits. */
    fun masked(aadhaar: String): String = aadhaar.filter { it.isDigit() }.takeIf { it.length == 12 }?.let { "XXXX XXXX " + it.takeLast(4) }.orEmpty()
}
