package com.example.arfas

/**
 * Part B6: extraction is never truncated. When a model answer is cut (unclosed
 * JSON / an incomplete last item), the input is split and each part is read
 * separately, then merged. Pure Kotlin, host-testable.
 */
object ArogyaExtractSplit {
    /**
     * True when the JSON object in [raw] (fences skipped) has every { [ (
     * outside strings closed. Cut output leaves brackets open.
     */
    fun jsonComplete(raw: String): Boolean {
        val t = raw.replace("```json", "").replace("```", "")
        val a = t.indexOf('{')
        if (a < 0) return false
        var curly = 0
        var square = 0
        var inStr = false
        var esc = false
        for (c in t.substring(a)) {
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> curly++
                '}' -> { curly--; if (curly < 0) return false }
                '[' -> square++
                ']' -> { square--; if (square < 0) return false }
            }
        }
        return !inStr && curly == 0 && square == 0
    }

    /** True when the last non-blank line of a line-based answer looks cut mid-line (no colon). */
    fun lastLineCut(raw: String): Boolean {
        val last = raw.lines().map { it.trim() }.lastOrNull { it.isNotBlank() } ?: return false
        return !last.contains(':')
    }

    /** Split text near its middle at a line boundary; the second half is "" when too short to split. */
    fun halves(text: String, minChars: Int = 200): Pair<String, String> {
        if (text.length < minChars * 2) return text to ""
        val lines = text.lines()
        if (lines.size < 4) {
            val m = text.length / 2
            return text.take(m) to text.drop(m)
        }
        val mid = lines.size / 2
        return lines.take(mid).joinToString("\n").trim() to lines.drop(mid).joinToString("\n").trim()
    }

    /** Person blocks of a register page: blank-line separated, longest [max] (as the PDF import). */
    fun splitBlocks(text: String, max: Int = 10): List<String> =
        text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.length > 20 }.take(max)

    /** Card fields merged: every row kept, exact duplicates dropped. */
    fun mergeCard(a: List<ArogyaCardField>, b: List<ArogyaCardField>): List<ArogyaCardField> =
        (a + b).distinctBy { it.key + it.value }

    /** Person maps merged: the first non-blank value per key wins. */
    fun mergePerson(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        val out = a.toMutableMap()
        b.forEach { (k, v) -> if (out[k].isNullOrBlank() && v.isNotBlank()) out[k] = v }
        return out
    }

    /** Voice-stock lines merged: concatenated, identical lines dropped. */
    fun mergeVoice(a: List<VoiceStockLine>, b: List<VoiceStockLine>): List<VoiceStockLine> =
        (a + b).distinct()

    /** Medicine scans merged: the first non-blank value per field wins. */
    fun mergeMed(a: MedScan, b: MedScan): MedScan = MedScan(
        a.name.ifBlank { b.name }, a.batch.ifBlank { b.batch },
        a.expiry.ifBlank { b.expiry }, if (a.qty > 0) a.qty else b.qty)
}
