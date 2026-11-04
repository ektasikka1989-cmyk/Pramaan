package com.example.arfas

/**
 * Register page photo → people, without AI.
 *
 * ML Kit returns text blocks in reading order, so a ruled register comes back column by column and
 * nothing separates one person from the next. Here each OCR line keeps its position: lines are
 * grouped into table rows by their vertical centre, the header row is found by its column names
 * (the same synonyms the CSV import uses: Name, Husband, Age, House, Mobile, LMP…), and every cell
 * below is put in the column whose header is closest horizontally. Each row becomes one person.
 * Pure Kotlin (host-tested). Values are copied, never guessed; the ASHA reviews them before saving.
 */
object ArogyaTableOcr {
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val h get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * The words of one OCR line merged into cells: words closer than about one letter-height stay together
     * ("Sarita" + "Yadav", "House" + "No."); a wider gap starts a new cell (the next table column).
     */
    fun cells(words: List<Line>): List<Line> {
        val ws = words.filter { it.text.isNotBlank() }.sortedBy { it.left }
        if (ws.isEmpty()) return emptyList()
        val h = ws.map { it.h }.sorted()[ws.size / 2]
        val out = ArrayList<Line>()
        var cur = ws[0]
        for (w in ws.drop(1)) {
            cur = if (w.left - cur.right <= h * 0.9) Line(cur.text + " " + w.text, cur.left, minOf(cur.top, w.top), w.right, maxOf(cur.bottom, w.bottom))
            else { out += cur; w }
        }
        out += cur
        return out
    }

    /** Lines grouped into rows (top to bottom), each row sorted left to right. */
    fun rows(lines: List<Line>): List<List<Line>> {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return emptyList()
        val medianH = clean.map { it.h }.sorted()[clean.size / 2]
        val out = ArrayList<MutableList<Line>>()
        clean.sortedBy { it.cy }.forEach { l ->
            val row = out.lastOrNull()
            if (row != null && kotlin.math.abs(l.cy - row.map { it.cy }.average()) <= medianH * 0.6) row += l else out += mutableListOf(l)
        }
        return out.map { r -> r.sortedBy { it.left } }
    }

    /** The page as text, one table row per line ("Birth  OPV-0  31/10/2026"), so a value and its date stay together. */
    fun rowText(lines: List<Line>): String = rows(lines).joinToString("\n") { r -> r.joinToString("  ") { it.text.trim() } }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    /** Generic words that must not decide a heading on their own ("Family consent taken" is not the household). */
    private val WORD_STOP = setOf("family", "method", "type", "group", "taken", "consent", "seen", "details", "detail", "of", "the", "and")
    /** Serial-number columns carry no person detail. */
    private val SERIAL = setOf("sno", "srno", "slno", "serialno", "serial", "sn", "no", "क्रम", "क्रमसं", "क्रमांक")

    /**
     * Canonical key for a heading cell, tolerant of wording: exact synonym of the whole cell or a part
     * ("Husband/Father" → relative), else any word of it ("Contact details" → phone, "Mobile No." → phone,
     * "Husband's name" → relative, "Aadhaar seen" → aadhaar). A specific word beats the generic "name".
     * Returns "" when the heading is not one the app has a field for (the value is then kept in notes).
     */
    fun headerKey(cell: String): String {
        val parts = listOf(cell) + cell.split('/', '(', ')', '|', ',', '-').map { it.trim() }.filter { it.isNotBlank() }
        for (p in parts) {
            val n = norm(p)
            if (n.isBlank()) continue
            ArogyaPersonCsv.SYNONYMS.entries.firstOrNull { (_, syns) -> syns.any { norm(it) == n } }?.let { return it.key }
        }
        val words = Regex("[\\p{L}\\p{N}]+").findAll(cell.lowercase().replace("'s", " ").replace("’s", " ")).map { it.value }
            .filter { it.length >= 2 && it !in WORD_STOP }.toList()
        val hits = words.mapNotNull { w -> ArogyaPersonCsv.SYNONYMS.entries.firstOrNull { (_, syns) -> syns.any { norm(it) == w } }?.key }.distinct()
        return hits.firstOrNull { it != "name" } ?: hits.firstOrNull().orEmpty()
    }

    private fun isSerial(cell: String) = norm(cell) in SERIAL

    /**
     * People from a register photo: one map (canonical CSV keys) per data row that has a name. Columns the
     * app has no field for are kept as "notes" ("Blood group: B+"), so nothing printed is lost.
     * Empty when no header row with a Name column and at least one other known column is found.
     */
    fun people(lines: List<Line>): List<Map<String, String>> {
        val rs = rows(lines)
        val headerIdx = rs.indexOfFirst { r -> val keys = r.map { headerKey(it.text) }.filter { it.isNotBlank() }; "name" in keys && keys.distinct().size >= 2 }
        if (headerIdx < 0) return emptyList()
        // Every header cell is a column; unknown ones keep their heading so their values go to notes.
        val seen = HashSet<String>()
        data class Col(val key: String, val heading: String, val cx: Int)
        val cols = rs[headerIdx].map { c -> Col(headerKey(c.text).let { k -> if (k.isNotBlank() && seen.add(k)) k else "" }, c.text.trim(), c.cx) }
        val medianH = lines.filter { it.text.isNotBlank() }.map { it.h }.sorted().let { it[it.size / 2] }
        fun colOf(cell: Line) = cols.minByOrNull { kotlin.math.abs(it.cx - cell.cx) }
        data class Person(val m: LinkedHashMap<String, String> = LinkedHashMap(), val extra: LinkedHashMap<String, String> = LinkedHashMap(), var bottom: Int = 0)
        val out = ArrayList<Person>()
        for (r in rs.drop(headerIdx + 1)) {
            // A footer such as "Family consent taken: Yes" or "Signature:" is a label line, not a person.
            if (r.any { it.text.contains(':') }) continue
            // A cell written on two lines ("Sarita" / "Yadav") gives an extra OCR row just above or below. It belongs
            // to the same person when it touches the previous row and either has no name cell (the values row of a
            // name written above it) or is a short words-only row (the second line of the name and husband cells).
            val prev = out.lastOrNull()
            val touches = prev != null && r.minOf { it.top } - prev.bottom <= medianH * 0.8
            val hasName = r.any { colOf(it)?.key == "name" }
            val shortWords = r.none { c -> c.text.any(Char::isDigit) } && r.size <= maxOf(1, cols.size / 2)
            val p = if (touches && (!hasName || shortWords)) prev!! else Person().also { out += it }
            r.forEach { cell ->
                val col = colOf(cell) ?: return@forEach
                when {
                    col.key.isNotBlank() -> p.m[col.key] = listOfNotNull(p.m[col.key], cell.text.trim()).joinToString(" ")
                    !isSerial(col.heading) && col.heading.isNotBlank() -> p.extra[col.heading] = listOfNotNull(p.extra[col.heading], cell.text.trim()).joinToString(" ")
                }
            }
            p.bottom = maxOf(p.bottom, r.maxOf { it.bottom })
        }
        return out.mapNotNull { p ->
            if (p.extra.isNotEmpty()) p.m["notes"] = p.extra.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            p.m["name"]?.takeIf { it.any(Char::isLetter) }?.let { p.m }
        }
    }

    // ── A table row with no heading row: each value is recognised by its form ──
    private val DATE = Regex("""^\d{1,2}\s*[/.\-]\s*\d{1,2}\s*[/.\-]\s*\d{2,4}$""")
    private val HOUSE = Regex("""^(?:[A-Za-z]{1,5}\.?\s*(?:No\.?)?\s*[-/]?\s*\d{1,4}[A-Za-z]?|[A-Za-z]\d{1,4})$""")
    private val AGE = Regex("""^(\d{1,3})\s*(?:yrs?\.?|years?|y|वर्ष|साल)?$""", RegexOption.IGNORE_CASE)
    private val SEX = Regex("""^(?:m|f|male|female|पुरुष|महिला|स्त्री|पु|म)$""", RegexOption.IGNORE_CASE)
    private val CATEGORY = Regex("""pregnan|garbh|गर्भवती|mother|postnatal|माँ|child|infant|baby|बच्चा|शिशु|eligible|couple|दंपति""", RegexOption.IGNORE_CASE)
    private fun digitsOnly(s: String) = s.all { it.isDigit() || it == ' ' || it == '-' || it == '+' }

    /** What a cell's value is, from its form alone ("" when it is a word such as a name or a village). */
    private fun formOf(v: String): String {
        val t = v.trim(); val d = t.filter(Char::isDigit)
        return when {
            t.isEmpty() -> "?"
            digitsOnly(t) && (d.length == 10 || (d.length == 12 && d.startsWith("91") && t.startsWith("+"))) -> "phone"
            digitsOnly(t) && d.length == 12 -> "aadhaar"
            DATE.matches(t) -> "date"
            AGE.matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 0..110 } == true -> "age"
            HOUSE.matches(t) && t.any(Char::isLetter) -> "household"
            SEX.matches(t) -> "sex"
            CATEGORY.containsMatchIn(t) && t.split(Regex("\\s+")).size <= 3 -> "category"
            t.any(Char::isDigit) -> "?"
            else -> ""
        }
    }

    /**
     * One person from a slip whose table has no heading row ("Sarita Yadav | Ramesh Yadav | 23 | 9000000301 |
     * H-051 | Rampur | Pregnant | 26/08/2026"). The row with the most recognisable values (mobile, date, house
     * number, category) is the person; cells written on two lines are joined by column. Values are copied as
     * written: a 10-digit number is the mobile, a date the LMP (DOB for a child), a short number the age, and the
     * word cells in order are the name, the husband / relative and the village. The ASHA checks each one.
     */
    fun headerlessRow(lines: List<Line>): Map<String, String>? {
        val rs = rows(lines)
        if (rs.isEmpty()) return null
        val medianH = lines.filter { it.text.isNotBlank() }.map { it.h }.sorted().let { it[it.size / 2] }
        fun hits(r: List<Line>) = if (r.any { ':' in it.text }) 0 else r.map { formOf(it.text) }.count { it in setOf("phone", "date", "household", "category") }
        val ai = rs.indices.maxByOrNull { hits(rs[it]) } ?: return null
        if (hits(rs[ai]) < 2 || rs[ai].size < 3) return null
        // Join the OCR rows just above / below that belong to the same table row (a cell written on two lines).
        var lo = ai; var hi = ai
        fun near(a: List<Line>, b: List<Line>) = maxOf(a.minOf { it.top }, b.minOf { it.top }) - minOf(a.maxOf { it.bottom }, b.maxOf { it.bottom }) <= medianH * 0.8
        while (lo > 0 && ai - lo < 2 && rs[lo - 1].none { ':' in it.text } && hits(rs[lo - 1]) == 0 && near(rs[lo - 1], rs[lo])) lo--
        while (hi < rs.size - 1 && hi - ai < 2 && rs[hi + 1].none { ':' in it.text } && hits(rs[hi + 1]) == 0 && near(rs[hi + 1], rs[hi])) hi++
        // Columns: cells that overlap horizontally are one cell of the table.
        data class Col(var left: Int, var right: Int, val cells: MutableList<Line>)
        val cols = ArrayList<Col>()
        (lo..hi).flatMap { rs[it] }.sortedBy { it.left }.forEach { c ->
            val col = cols.firstOrNull { c.left < it.right && c.right > it.left }
            if (col != null) { col.cells += c; col.left = minOf(col.left, c.left); col.right = maxOf(col.right, c.right) }
            else cols += Col(c.left, c.right, mutableListOf(c))
        }
        val values = cols.sortedBy { it.left }.map { col -> col.cells.sortedBy { it.top }.joinToString(" ") { it.text.trim() } }
        val out = LinkedHashMap<String, String>()
        val words = ArrayList<String>()
        var date: String? = null
        values.forEach { v ->
            when (val f = formOf(v)) {
                "" -> if (!isSerial(v)) words += v
                "date" -> if (date == null) date = v
                "age" -> out["age"] = v     // the last short number: a serial number comes first
                "phone", "aadhaar", "household", "sex", "category" -> if (f !in out) out[f] = v
            }
        }
        words.getOrNull(0)?.let { out["name"] = it }
        words.getOrNull(1)?.let { out["relative"] = it }
        words.getOrNull(2)?.let { out["village"] = it }
        date?.let { d ->
            val cat = out["category"].orEmpty().lowercase()
            out[when { Regex("child|infant|baby|बच्चा|शिशु").containsMatchIn(cat) -> "dob"; Regex("mother|postnatal|माँ").containsMatchIn(cat) -> "deliverydate"; else -> "lmp" }] = d
        }
        return out.takeIf { !it["name"].isNullOrBlank() && it.size >= 3 }
    }

    /**
     * "Label: value" details anywhere on the page (slips, forms, a line above a table), split also at " | ".
     * Known labels map to fields by [headerKey]; others are returned separately so they can go to notes.
     */
    fun keyValues(lines: List<Line>): Pair<Map<String, String>, Map<String, String>> {
        val known = LinkedHashMap<String, String>(); val other = LinkedHashMap<String, String>()
        rows(lines).forEach { r ->
            r.joinToString("  |  ") { it.text }.split("|").forEach { piece ->
                val m = Regex("^\\s*([^:]{2,40}?)\\s*[:：]\\s*(.+?)\\s*$").find(piece) ?: return@forEach
                val label = m.groupValues[1].trim(); val value = m.groupValues[2].trim().trim('_', ' ')
                if (value.isBlank() || value.all { it == '_' }) return@forEach
                val k = headerKey(label)
                if (k.isNotBlank()) { if (k !in known) known[k] = value } else other[label] = value
            }
        }
        return known to other
    }

    /**
     * One person from a slip / card / register photo by fixed rules: the first row of a table (headings in any
     * wording), plus "Label: value" details on the page (e.g. an Aadhaar line above the table). For a page that
     * is not a table, the "Label: value" pairs are the person and unknown labels go to notes. The caller asks
     * the phone AI (with [rowText], the page with its layout kept) only for fields still missing.
     */
    fun readPerson(lines: List<Line>): Map<String, String> {
        val table = people(lines).firstOrNull()
        val (kv, other) = keyValues(lines)
        if (table != null) return kv + table
        // A slip whose table has no heading row: values are recognised by their form.
        // Printed "Label: value" details (e.g. the Aadhaar line) win over values recognised by their form.
        headerlessRow(lines)?.let { return it + kv }
        if (kv.isEmpty()) return emptyMap()
        val out = LinkedHashMap(kv)
        if (other.isNotEmpty()) out["notes"] = other.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return out
    }

    /** The details an ASHA needs for a new registration; the AI is asked only for those still missing. */
    val CORE = listOf("name", "relative", "age", "phone", "household", "village", "category", "lmp")
}
