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

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** Canonical key for one header cell ("Husband/Father" → relative, "House No." → household), or "". */
    fun headerKey(cell: String): String {
        val parts = listOf(cell) + cell.split('/', '(', ')', '|', ',', '-').map { it.trim() }.filter { it.isNotBlank() }
        for (p in parts) {
            val n = norm(p)
            if (n.isBlank()) continue
            ArogyaPersonCsv.SYNONYMS.entries.firstOrNull { (_, syns) -> syns.any { norm(it) == n } }?.let { return it.key }
        }
        return ""
    }

    /**
     * People from a register photo: one map (canonical CSV keys) per data row that has a name.
     * Empty when no header row with a Name column and at least one other known column is found.
     */
    fun people(lines: List<Line>): List<Map<String, String>> {
        val rs = rows(lines)
        val headerIdx = rs.indexOfFirst { r -> val keys = r.map { headerKey(it.text) }.filter { it.isNotBlank() }; "name" in keys && keys.distinct().size >= 2 }
        if (headerIdx < 0) return emptyList()
        // Every header cell is a column; unknown ones (S.No, Remarks…) keep their cells out of the known columns.
        val seen = HashSet<String>()
        val cols = rs[headerIdx].map { c -> headerKey(c.text).let { k -> if (k.isNotBlank() && seen.add(k)) k else "" } to c.cx }
        return rs.drop(headerIdx + 1).mapNotNull { r ->
            val m = LinkedHashMap<String, String>()
            r.forEach { cell ->
                val (key, _) = cols.minByOrNull { kotlin.math.abs(it.second - cell.cx) } ?: return@forEach
                if (key.isBlank()) return@forEach
                m[key] = listOfNotNull(m[key], cell.text.trim()).joinToString(" ")
            }
            // A footer such as "Family consent taken: Yes" or "Signature:" is a label line, not a person.
            if (r.any { it.text.contains(':') }) return@mapNotNull null
            m["name"]?.takeIf { it.any(Char::isLetter) }?.let { m }
        }
    }
}
