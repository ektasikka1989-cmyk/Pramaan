package com.example.arfas

/**
 * Pure text side of [ArogyaPhotoVision] (host-tested): the prompts, which OCR boxes hide Aadhaar before a photo
 * leaves the phone, and turning Muse Spark's transcript into the lines the phone's own readers already parse.
 */
object ArogyaPhotoText {
    enum class Kind { PERSON, CARD, MEDICINE }
    const val SYSTEM = "You transcribe photos of Indian health records and medicine packs. You copy only what is written, exactly. You never guess."
    private const val NO_ID = "Do not write any 12-digit ID number (Aadhaar); leave it out."

    fun prompt(kind: Kind): String = when (kind) {
        Kind.PERSON -> """Read this photo (a registration slip, register page or health card). Write ONLY the person's own details, exactly as written, one per line, in this form:
Heading: value
Use the printed column heading or label for each value. If a table has no headings, use the heading that fits the value: Name, Husband, Father, Mother, Age, Sex, Mobile, House No., Village, Category, LMP, Date of birth, RCH ID.
A value written on two lines inside one box is ONE value ("Sarita" above "Yadav" → Name: Sarita Yadav).
A register page with several people: one block of lines per person, with one empty line between people.
Leave out page titles, sub-centre / ASHA names, signatures and ticks. $NO_ID
If a value cannot be read, write: Heading: [unreadable]. Never add anything that is not written. If there are no person details, reply NO_TEXT."""
        Kind.CARD -> """Read this photo of a Mother and Child Protection card page. Write its text exactly as written, one table row per line, cells separated by " | ".
Keep each date on the same line as its vaccine or age (e.g. "Birth | BCG | 31/10/2026"). Copy handwritten dates and weights exactly. Leave empty cells empty.
Also write the details above the table as "Heading: value" lines (child's name, mother's name, date of birth, birth weight).
$NO_ID Write [unreadable] for anything you cannot read. Never add anything that is not written. If there is no text, reply NO_TEXT."""
        Kind.MEDICINE -> """Read this photo of a medicine strip, box or sachet. Write all the printed text exactly, one printed line per line.
Keep labels with their values: Batch No. / B. No. / Lot No., Mfg., Exp., and the printed quantity (e.g. "10 Tablets").
Never add anything that is not printed. If there is no text, reply NO_TEXT."""
    }

    private val AADHAAR = Regex("""(?<!\d)\d{4}[\s-]?\d{4}[\s-]?\d{4}(?!\d)""")
    private val AADHAAR_WORD = Regex("""aadh?aa?r|adh?aa?r|आधार|\buid\b""",RegexOption.IGNORE_CASE)

    /** OCR boxes to black out before a photo leaves the phone: 12-digit numbers, and cells right of an "Aadhaar" label. */
    fun hiddenBoxes(lines: List<ArogyaTableOcr.Line>): List<ArogyaTableOcr.Line> {
        val out = lines.filter { AADHAAR.containsMatchIn(it.text) }.toMutableList()
        ArogyaTableOcr.rows(lines).forEach { r ->
            val label = r.firstOrNull { AADHAAR_WORD.containsMatchIn(it.text) } ?: return@forEach
            out += label                                                                    // "Aadhaar No.: 0000 1111 2222" as one cell
            out += r.filter { it.left >= label.left && it !== label && it.text.any(Char::isDigit) }
        }
        return out.distinct()
    }

    /** Text lines → positioned lines (one row each), so the phone's own readers parse the transcript. */
    fun asLines(text: String): List<ArogyaTableOcr.Line> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() }.mapIndexed { i, l -> ArogyaTableOcr.Line(l, 0, i * 40, 20 * l.length.coerceAtLeast(1), i * 40 + 24) }

    /** The transcript without unreadable values (those show as "Not read from the photo"), code fences or NO_TEXT. */
    fun clean(raw: String?): String? {
        val t = raw.orEmpty().replace("```", "").lines()
            .filterNot { it.contains("[unreadable]", ignoreCase = true) }
            .map { AADHAAR.replace(it, "") }                                                 // never keep an ID from the cloud
            .joinToString("\n").trim()
        return t.takeIf { it.length >= 4 && !it.equals("NO_TEXT", ignoreCase = true) }
    }

    /** People from a PERSON transcript: one per block (blank line between people), each read by [ArogyaTableOcr.readPerson]. */
    fun people(text: String): List<Map<String, String>> =
        text.split(Regex("\\n\\s*\\n")).map { ArogyaTableOcr.readPerson(asLines(it)) }.filter { !it["name"].isNullOrBlank() }

    /** CARD transcript → the row text the card reader takes (one table row per line). */
    fun cardText(text: String): String = text.lines().joinToString("\n") { it.split("|").joinToString("  ") { c -> c.trim() }.trim() }.trim()
}
