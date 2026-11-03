package com.example.arfas

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Item 7: one document renderer for on-screen preview AND PDF. Templates live in
 * assets/doc_templates (table.html, mcp.html, answer.html + doc.css); this file fills
 * them from data. Pure Kotlin, host-tested (tests load the template files by path).
 * The phone renders the same HTML in a WebView for preview and for PDF/print.
 */
object ArogyaDocRender {
    /** A filled document: final HTML, orientation, and a file-safe title. */
    data class FilledDoc(val html: String, val landscape: Boolean, val title: String)

    /** Header printed on every document (blank fields become ruled lines). */
    data class DocHeader(val state: String, val district: String, val block: String, val subcentre: String, val asha: String)

    /** Wide tables (more than 6 columns) print landscape; the rest portrait. */
    fun landscapeFor(columns: Int): Boolean = columns > 6

    /** Light diagonal SAMPLE watermark for sample people only (demo- ids or the demo flag). */
    fun watermarkFor(ids: List<String>, demo: Boolean = false): Boolean =
        demo || ids.any { it.startsWith("demo-") }

    fun esc(t: String): String = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun blank(v: String): String = v.ifBlank { "________" }

    /**
     * Tiny template engine: {{key}} (escaped), {{{key}}} (trusted raw HTML),
     * {{#k}}…{{/k}} (a non-blank string renders once; a list of maps repeats).
     * Nested sections supported. Unknown keys render empty (tests assert none remain).
     */
    fun fill(template: String, values: Map<String, Any?>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf("{{", i)
            if (open < 0) {
                out.append(template.substring(i))
                break
            }
            out.append(template.substring(i, open))
            if (template.startsWith("{{{", open)) {
                val close = template.indexOf("}}}", open + 3)
                if (close < 0) {
                    out.append(template.substring(open))
                    break
                }
                out.append(values[template.substring(open + 3, close).trim()]?.toString().orEmpty())
                i = close + 3
            } else {
                val close = template.indexOf("}}", open + 2)
                if (close < 0) {
                    out.append(template.substring(open))
                    break
                }
                val tag = template.substring(open + 2, close).trim()
                if (tag.startsWith("#")) {
                    val key = tag.drop(1)
                    val endTag = "{{/$key}}"
                    var depth = 1
                    var j = close + 2
                    var end = -1
                    while (depth > 0) {
                        val nOpen = template.indexOf("{{#$key}}", j)
                        val nEnd = template.indexOf(endTag, j)
                        if (nEnd < 0) break
                        if (nOpen >= 0 && nOpen < nEnd) {
                            depth++
                            j = nOpen + 4 + key.length
                        } else {
                            depth--
                            if (depth == 0) end = nEnd
                            j = nEnd + endTag.length
                        }
                    }
                    if (end < 0) {
                        out.append(template.substring(open, close + 2))
                        i = close + 2
                        continue
                    }
                    val inner = template.substring(close + 2, end)
                    when (val v = values[key]) {
                        is List<*> -> v.filterIsInstance<Map<String, Any?>>().forEach { item -> out.append(fill(inner, values + item)) }
                        else -> if (v?.toString()?.isNotBlank() == true) out.append(fill(inner, values))
                    }
                    i = end + endTag.length
                } else if (!tag.startsWith("/")) {
                    out.append(esc(values[tag]?.toString().orEmpty()))
                    i = close + 2
                } else {
                    out.append(template.substring(open, close + 2))
                    i = close + 2
                }
            }
        }
        return out.toString()
    }

    private fun headerValues(h: DocHeader): Map<String, String> = mapOf(
        "state" to blank(h.state), "district" to blank(h.district), "block" to blank(h.block),
        "subcentre" to blank(h.subcentre), "asha" to blank(h.asha)
    )

    /** Status cells (EN + HI) become coloured text; everything else is escaped plain text. */
    private fun statusHtml(cell: String): String {
        val t = cell.trim()
        val cls = when {
            t.contains("Overdue", true) || t.contains("missed", true) || t.contains("MISSED") ||
                t.contains("निकल") || t.contains("छूट") || t.contains("Expired", true) ||
                t.contains("High risk", true) || t.contains("जोखिम") -> "overdue"
            t.equals("Done", true) || t.equals("Yes", true) || t.equals("OK", true) ||
                t.contains("हो गय") || t.contains("✓") -> "done"
            t.contains("Due", true) || t.contains("due", false) || t.contains("बाकी") ||
                t.contains("आने") || t.contains("Counsel", true) || t.contains("समझा") ||
                t.contains("Low", true) || t.contains("Expiring", true) -> "due"
            else -> ""
        }
        return if (cls.isEmpty()) esc(t) else "<span class=\"$cls\">${esc(t)}</span>"
    }

    private fun dd(s: String): String = runCatching {
        LocalDate.parse(s).format(DateTimeFormatter.ofPattern("dd/MM/yy"))
    }.getOrDefault(s)

    /**
     * Any ruled table: official visit forms, RI forms, stock card, registers, lists.
     * A row whose cells after the first are all blank renders as a section heading.
     */
    fun tableDoc(
        form: FormTable, header: DocHeader, dateLabel: String, dateVal: String,
        template: String, css: String, highlightCol: Int? = null
    ): FilledDoc {
        val n = form.headers.size.coerceAtLeast(1)
        val thead = form.headers.mapIndexed { i, h ->
            "<th${if (i == highlightCol) " class=\"hlcol\"" else ""}>${esc(h)}</th>"
        }.joinToString("")
        val tbody = form.rows.mapIndexed { r, row ->
            val cells = (row.take(n) + List((n - row.size).coerceAtLeast(0)) { "" })
            if (n > 1 && cells[0].isNotBlank() && cells.drop(1).all { it.isBlank() }) {
                "<tr class=\"section\"><td colspan=\"$n\">${esc(cells[0])}</td></tr>"
            } else {
                "<tr${if (r % 2 == 1) " class=\"zebra\"" else ""}>" +
                    cells.mapIndexed { i, c -> "<td${if (i == highlightCol) " class=\"hlcol\"" else ""}>${statusHtml(c)}</td>" }
                        .joinToString("") + "</tr>"
            }
        }.joinToString("")
        val cssFilled = fill(css, mapOf("pageSize" to if (landscapeFor(n)) "A4 landscape" else "A4"))
        val html = fill(
            template,
            headerValues(header) + mapOf(
                "css" to cssFilled,
                "title" to form.title, "source" to form.source,
                "dateLabel" to dateLabel, "dateVal" to dateVal,
                "thead" to thead, "tbody" to tbody,
                "note" to form.note, "sign" to if (form.sign) "1" else "",
                "wmText" to if (form.sample) "SAMPLE" else ""
            )
        )
        return FilledDoc(html, landscapeFor(n), form.title)
    }

    /**
     * Mother and Child Protection Card in the printed card's shape: pink header band,
     * boxed mother/child details, ANC table, immunization table, weight record.
     * Filled values render blue (handwriting-style); blanks stay ruled-empty.
     */
    fun mcpDoc(
        b: Beneficiary, all: List<Beneficiary>, today: LocalDate,
        header: DocHeader, dateVal: String, template: String, css: String,
        growthSvg: String = "", growthNote: String = "", ancTrend: String = ""
    ): FilledDoc {
        val mother = if (b.category == BenCategory.CHILD)
            all.firstOrNull { it.household == b.household && it.household.isNotBlank() && it.category != BenCategory.CHILD } else b
        val ancRows = if (b.category == BenCategory.PREGNANT) (1..5).map { n ->
            val d = b.mcp.ancDetails[n]
            mapOf("n" to "ANC $n", "date" to (b.mcp.anc[n]?.let(::dd) ?: ""),
                "weight" to (d?.weightKg.orEmpty()), "bp" to (d?.bp.orEmpty()), "hb" to (d?.hb.orEmpty()),
                "td" to (b.mcp.tdDates.getOrNull(n - 1)?.let(::dd) ?: ""),
                "ifa" to (if (n == 1 && b.mcp.ifaTablets > 0) "${b.mcp.ifaTablets}" else ""))
        } else emptyList()
        val dob = runCatching { LocalDate.parse(b.dob) }.getOrNull()
        val vaccines = ArogyaSchedule.VACCINE_CONTACTS.zip(ArogyaSchedule.VACCINE_AGES).flatMap { (c, age) ->
            c.codes.map { code ->
                mapOf("name" to (ArogyaSchedule.VACCINE_NAMES[code] ?: code), "age" to age,
                    "due" to (dob?.let { dd(c.due(it).toString()) } ?: ""),
                    "given" to (b.mcp.vaccines[code]?.let(::dd) ?: ""))
            }
        }
        val weights = b.mcp.weights.sortedBy { it.date }.map { mapOf("date" to dd(it.date), "kg" to "${it.kg} kg") }
        val html = fill(
            template,
            headerValues(header) + mapOf(
                "css" to fill(css, mapOf("pageSize" to "A4")),
                "source" to "MoHFW / MWCD, revised 2018",
                "dateVal" to dateVal,
                "motherName" to (mother?.name ?: b.relativeName), "motherRelative" to (mother?.relativeName.orEmpty()),
                "motherAge" to (mother?.age.orEmpty()), "motherPhone" to (mother?.phone ?: b.phone),
                "address" to "${b.household}, ${b.village}".trim(',', ' '), "rchId" to b.rchId,
                "lmp" to (if (b.lmp.isNotBlank()) dd(b.lmp) else ""),
                "edd" to (ArogyaRegistryLogic.edd(b)?.toString()?.let(::dd) ?: ""),
                "childName" to (if (b.category == BenCategory.CHILD) b.name else ""),
                "childSex" to (if (b.category == BenCategory.CHILD) b.sex else ""),
                "childDob" to (if (b.category == BenCategory.CHILD && b.dob.isNotBlank()) dd(b.dob) else ""),
                "birthWeight" to (b.birthWeightKg?.let { "$it kg" } ?: ""),
                "deliveryPlace" to (if (b.deliveryPlace == "HOME") "Home" else if (b.deliveryPlace.isBlank()) "" else "Institution"),
                "termPreterm" to (if (b.category == BenCategory.CHILD) (if (b.preterm) "Preterm" else "Term") else ""),
                "childRch" to (if (b.category == BenCategory.CHILD) b.rchId else ""),
                "hasAnc" to if (b.category == BenCategory.PREGNANT) "1" else "", "ancRows" to ancRows,
                "vaccines" to vaccines,
                "hasWeights" to if (weights.isNotEmpty()) "1" else "", "weights" to weights,
                "hasGrowth" to if (growthSvg.isNotBlank()) "1" else "", "growthSvg" to growthSvg,
                "growthNote" to growthNote, "ancTrend" to ancTrend,
                "wmText" to if (watermarkFor(listOf(b.id), b.demo)) "SAMPLE" else ""
            )
        )
        return FilledDoc(html, false, "MCP card — ${b.name}")
    }

    /**
     * An Ask SOP answer: question, cited answer, and the numbered Sources list
     * (guide passages with pages, official websites with domain/date/link).
     */
    fun answerDoc(
        question: String, segments: List<ArogyaOfficialAnswer.CitedSegment>, answerFallback: String,
        sources: List<ArogyaSource>, header: DocHeader, dateVal: String,
        template: String, css: String
    ): FilledDoc {
        val answerHtml = if (segments.isNotEmpty()) {
            segments.joinToString("") { s ->
                "<p>${esc(s.text)} " + s.sourceIds.joinToString("") { "<span class=\"cite\">[$it]</span>" } + "</p>"
            }
        } else {
            ArogyaMarkdown.strip(answerFallback).split("\n").filter { it.isNotBlank() }
                .joinToString("") { "<p>${esc(it.trim())}</p>" }
        }
        val srcs = sources.map { s ->
            mapOf("id" to s.id, "title" to s.title,
                "meta" to if (s.kind == "web")
                    "Official website · " + listOf(s.domain, s.locator, s.date).filter { it.isNotBlank() }.joinToString(" · ")
                else "Guide" + (if (s.locator.isNotBlank()) " · ${s.locator}" else ""),
                "excerpt" to s.highlights.firstOrNull()?.take(600).orEmpty(), "url" to s.url)
        }
        val html = fill(
            template,
            headerValues(header) + mapOf(
                "css" to fill(css, mapOf("pageSize" to "A4")),
                "title" to "Ask SOP — answer", "source" to "ArogyaDoot · grounded in the cited sources",
                "dateVal" to dateVal, "question" to question,
                "answerHtml" to answerHtml,
                "hasSources" to if (srcs.isNotEmpty()) "1" else "", "sources" to srcs,
                "wmText" to ""
            )
        )
        return FilledDoc(html, false, "SOP answer")
    }
}
