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
     * Demo UI pass: the MCP card as the demo shows it after the visit. Handwritten cells whose value
     * differs from [beforeHtml] (the card at the start of the demo) are highlighted; "×" (not
     * applicable at this column) reads "NA"; empty cells read "Not recorded" (grey). Nothing is
     * guessed: no Yes/No is written for a value that was never recorded. The real printout is
     * unchanged; this is applied only to the demo view. Returns the html and the changed count
     * (-1 when the cells can't be paired with [beforeHtml], so no change is claimed).
     */
    fun demoMark(beforeHtml: String, afterHtml: String, hindi: Boolean = false): Pair<String, Int> {
        val cell = Regex("<td class=\"hand\">(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
        val before = cell.findAll(beforeHtml).map { it.groupValues[1].trim() }.toList()
        val afterCount = cell.findAll(afterHtml).count()
        val compare = before.size == afterCount
        val na = if (hindi) "लागू नहीं" else "NA"
        val notRec = if (hindi) "दर्ज नहीं" else "Not recorded"
        var i = 0
        var changed = 0
        var html = cell.replace(afterHtml) { m ->
            val v = m.groupValues[1].trim()
            val was = if (compare) before[i] else v
            i++
            when {
                v == "×" -> "<td class=\"hand na\">$na</td>"
                v.isEmpty() -> "<td class=\"hand nr\">$notRec</td>"
                v != was -> { changed++; "<td class=\"hand chg\">$v</td>" }
                else -> m.value
            }
        }
        val style = "<style>.chg{background:#fff1a8;outline:2px solid #e0a800;font-weight:bold}" +
            ".na{color:#8a8a8a}.nr{color:#b0b0b0;font-style:italic;font-size:85%}" +
            ".demolegend{font:13px sans-serif;margin:6px 0 10px;padding:6px 8px;border:1px solid #e0a800;background:#fffbe6}</style>"
        // When the card's shape changed, cells can't be paired, so nothing is claimed as changed (-1).
        val yellow = if (!compare) "" else if (hindi) "<b>पीला</b> = आज बदला ($changed) · " else "<b>Yellow</b> = changed today ($changed) · "
        val legend = "<div class=\"demolegend\">" + yellow + (if (hindi)
            "<b>लागू नहीं</b> = इस चरण पर लागू नहीं · <b>दर्ज नहीं</b> = अभी भरना बाकी"
            else "<b>NA</b> = not applicable at this stage · <b>Not recorded</b> = still to be filled") + "</div>"
        html = if (html.contains("</head>")) html.replaceFirst("</head>", "$style</head>") else style + html
        html = Regex("<body[^>]*>").find(html)?.let { b -> html.replaceRange(b.range.last + 1, b.range.last + 1, legend) } ?: (legend + html)
        return html to (if (compare) changed else -1)
    }

    /**
     * Mother and Child Protection Card in the printed card's page order: p.3 Identification,
     * p.4 Checkup, p.5 ANC, p.6 Danger signs/Delivery prep, p.7 PNC, p.8 Newborn + HBYC,
     * p.27 IFA, pp.28–31 Growth chart, pp.36–37 Immunization. Pastel headers (pink ANC,
     * yellow PNC, purple immunization). Filled values render blue (handwriting-style);
     * blanks stay ruled-empty. Section tables come from the matching form entries,
     * falling back to the card's own ticks/totals.
     */
    fun mcpDoc(
        b: Beneficiary, all: List<Beneficiary>, today: LocalDate,
        header: DocHeader, dateVal: String, template: String, css: String,
        growthSvg: String = "", growthNote: String = "", ancTrend: String = "",
        forms: List<FormEntry> = emptyList()
    ): FilledDoc {
        val mother = if (b.category == BenCategory.CHILD)
            all.firstOrNull { it.household == b.household && it.household.isNotBlank() && it.category != BenCategory.CHILD } else b
        val child = if (b.category == BenCategory.CHILD) b
            else all.firstOrNull { it.category == BenCategory.CHILD && it.household == b.household && b.household.isNotBlank() }
        fun entriesOf(personId: String, formId: String) = forms.filter { it.beneficiaryId == personId && it.formId == formId }
        fun latest(personId: String, formId: String) = entriesOf(personId, formId).maxByOrNull { it.date }?.values.orEmpty()
        fun cellVal(personId: String, formId: String, col: Int, id: String, fallback: String = ""): String {
            if (id == "date") return entriesOf(personId, formId).filter { it.column == col }.maxByOrNull { it.date }
                ?.date?.let(::dd).orEmpty().ifBlank { fallback }
            val raw = entriesOf(personId, formId).filter { it.column == col }.maxByOrNull { it.date }
                ?.values?.get(id).orEmpty().ifBlank { fallback }
            if (raw.isBlank()) return ""
            return runCatching { dd(LocalDate.parse(raw).toString()) }.getOrDefault(raw)
        }
        fun gridHtml(spec: FormSpec, personId: String, fieldIds: List<Pair<String, String>>, cls: String,
            fallbacks: Map<Pair<Int, String>, String> = emptyMap()): String = buildString {
            append("<table class=\"grid $cls\"><thead><tr><th></th>")
            spec.columns.forEach { append("<th>${esc(it)}</th>") }
            append("</tr></thead><tbody>")
            fieldIds.forEach { (id, label) ->
                append("<tr><td>${esc(label)}</td>")
                spec.columns.indices.forEach { c ->
                    val f = spec.fields.firstOrNull { it.id == id }
                    val v = if (f != null && !ArogyaOfficialForms.appliesAt(f, c)) "×"
                        else cellVal(personId, spec.id, c, id, fallbacks[c to id].orEmpty())
                    append("<td class=\"hand\">${esc(v)}</td>")
                }
                append("</tr>")
            }
            append("</tbody></table>")
        }
        fun boxHtml(pairs: List<Pair<String, String>>): String {
            if (pairs.isEmpty()) return ""
            return buildString {
                append("<table class=\"grid\"><tbody>")
                pairs.forEach { (k, v) -> append("<tr><td>${esc(k)}</td><td class=\"hand\">${esc(v)}</td></tr>") }
                append("</tbody></table>")
            }
        }
        // ANC visit grid (card p.5 rows) with card-tick fallbacks.
        val ancSpec = ArogyaOfficialForms.MCP_ANC
        val ancFb = mutableMapOf<Pair<Int, String>, String>()
        (0..4).forEach { c ->
            val n = c + 1
            ancFb[c to "date"] = b.mcp.anc[n]?.let(::dd).orEmpty()
            val d = b.mcp.ancDetails[n]
            ancFb[c to "weight"] = d?.weightKg.orEmpty(); ancFb[c to "bp"] = d?.bp.orEmpty()
            ancFb[c to "hb"] = d?.hb.orEmpty()
            ancFb[c to "td"] = b.mcp.tdDates.getOrNull(n - 1)?.let(::dd).orEmpty()
            ancFb[c to "ifa"] = if (n == 1 && b.mcp.ifaTablets > 0) "${b.mcp.ifaTablets}" else ""
        }
        val ancGrid = gridHtml(ancSpec, b.id, listOf("date" to "Date", "pog" to "POG (Weeks)", "weight" to "Weight (Kg)",
            "pulse" to "Pulse Rate", "bp" to "Blood Pressure", "pallor" to "Pallor", "oedema" to "Oedema",
            "jaundice" to "Jaundice", "complaints" to "Any Complaints", "hb" to "Hemoglobin",
            "td" to "Td (TT on older cards)", "ifa" to "IFA tablets count"), "ph-anc", ancFb)
        val ancLatest = latest(b.id, "mcp_anc")
        fun once(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { id ->
            val v = ancLatest[id].orEmpty()
            if (v.isBlank()) null else (ancSpec.fields.firstOrNull { it.id == id }?.label ?: id) to v
        }
        val ancOnce = boxHtml(once(listOf("gravida", "prev_live_births", "last_delivery_at", "delivery_institution", "preg_outcome",
            "oc_aph", "oc_eclampsia", "oc_pih", "oc_anaemia", "oc_obstructed", "oc_pph", "oc_lscs", "oc_anomaly", "oc_abortion", "oc_other",
            "ph_tb", "ph_hyp", "ph_heart", "ph_diabetes", "ph_asthma", "ph_other", "height", "heart", "lungs", "breasts",
            "urine_albumin", "urine_sugar", "hiv", "syphilis", "usg", "gdm", "blood_group", "bg_date",
            "tsh", "hbsag", "blood_sugar", "opt_other", "calcium", "albendazole")))
        // Checkup (card p.4): latest ANC values, else card totals.
        val checkupPairs = listOf(
            "Td" to (ancLatest["td"].orEmpty().ifBlank { b.mcp.tdDates.joinToString(", ") { dd(it) } }),
            "IFA tablets (total)" to (ancLatest["ifa"].orEmpty().ifBlank { if (b.mcp.ifaTablets > 0) "${b.mcp.ifaTablets}" else "" }),
            "Calcium tablets" to ancLatest["calcium"].orEmpty(),
            "Albendazole" to ancLatest["albendazole"].orEmpty()).filter { it.second.isNotBlank() }
        val checkupBox = boxHtml(checkupPairs)
        // Delivery preparation (card p.6): recorded values.
        val prepBox = boxHtml(listOf(
            "Identified delivery institution" to ancLatest["delivery_institution"].orEmpty(),
            "Hospital identified, transport arranged" to ancLatest["bp_facility"].orEmpty(),
            "Registered under JSY" to ancLatest["jsy"].orEmpty()).filter { it.second.isNotBlank() })
        // PNC + baby (card p.7): the mother's PNC entries, the child's baby entries.
        val mid = mother?.id ?: b.id
        val pncSpec = ArogyaOfficialForms.MCP_PNC
        val pncLatest = latest(mid, "mcp_pnc")
        fun pncOnce(ids: List<String>): List<Pair<String, String>> = ids.mapNotNull { id ->
            val v = pncLatest[id].orEmpty()
            if (v.isBlank()) null else (pncSpec.fields.firstOrNull { it.id == id }?.label ?: id) to v
        }
        val pncOnce = boxHtml(pncOnce(listOf("del_place_detail", "pnc_outcome", "term", "stay_days", "del_complications", "cried", "bf_1hr", "vitk")))
        val pncGrid = gridHtml(pncSpec, mid, listOf("date" to "Date", "complaints" to "Any complaints", "pallor" to "Pallor", "pulse" to "Pulse Rate",
            "bp" to "Blood Pressure", "temp" to "Temperature", "breasts" to "Breasts (Soft/Engorged)",
            "nipples" to "Nipples (Cracked/Normal)", "uterus" to "Uterus Tenderness (Present/Absent)",
            "bleeding" to "Bleeding P/V (Excessive/Normal)", "lochia" to "Lochia (Healthy/Foul Smelling)",
            "episiotomy" to "Episiotomy/Tear (Healthy/Infected)", "fp_counsel" to "Family Planning Counselling (Y/N)",
            "complications_ref" to "Any other Complications and Referral Requirements (Y/N)"), "ph-pnc")
        val cid = child?.id ?: ""
        val babySpec = ArogyaOfficialForms.MCP_BABY
        val babyGrid = gridHtml(babySpec, cid, listOf("date" to "Date") + babySpec.fields.map { it.id to it.label }, "ph-pnc")
        // HBYC (card p.8), IFA (card p.27).
        val hbycSpec = ArogyaOfficialForms.MCP_HBYC
        val hbycGrid = gridHtml(hbycSpec, cid, listOf("date" to "Date") + hbycSpec.fields.map { it.id to it.label }, "")
        val ifaSpec = ArogyaOfficialForms.MCP_IFA
        val ifaGrid = gridHtml(ifaSpec, cid, listOf("date" to "Date") + ifaSpec.fields.map { it.id to it.label }, "")
        // Immunization (card pp.36–37): entry dates, else the card's own given-dates.
        val immSpec = ArogyaOfficialForms.MCP_IMM
        val immCode = mapOf("bcg" to "BCG", "opv0" to "OPV0", "hepb0" to "HEPB0", "opv1" to "OPV1", "penta1" to "PENTA1",
            "rota1" to "RVV1", "pcv1" to "PCV1", "ipv1" to "FIPV1", "opv2" to "OPV2", "penta2" to "PENTA2", "rota2" to "RVV2",
            "opv3" to "OPV3", "penta3" to "PENTA3", "rota3" to "RVV3", "pcv2" to "PCV2", "ipv2" to "FIPV2",
            "mr1" to "MR1", "vita1" to "VITA1", "pcvb" to "PCVB", "mr2" to "MR2", "dptb1" to "DPTB1", "opvb" to "OPVB", "dptb2" to "DPTB2")
        val immAgeRows = immSpec.fields.filter { it.onlyColumns.isNotEmpty() }.map { it.id to it.label } +
            listOf("next_vac_date" to "Next Vaccination Date")
        val immGrid = gridHtml(immSpec, cid, immAgeRows, "ph-imm",
            immSpec.fields.flatMap { f -> f.onlyColumns.map { c -> (c to f.id) to
                ((child ?: b).mcp.vaccines[immCode[f.id]]?.let(::dd).orEmpty()) } }.toMap())
        val immLatest = latest(cid, "mcp_imm")
        val immOnce = boxHtml(listOf("vita3", "vita4", "vita5", "vita6", "vita7", "vita8", "vita9",
            "sia_vaccine", "sia_date", "miss_name", "miss_date", "miss_reason", "miss_next", "miss_anm").mapNotNull { id ->
            val v = immLatest[id].orEmpty()
            if (v.isBlank()) null else (immSpec.fields.firstOrNull { it.id == id }?.label ?: id) to v
        })
        val weights = (child ?: b).mcp.weights.sortedBy { it.date }.map { mapOf("date" to dd(it.date), "kg" to "${it.kg} kg") }
        val html = fill(
            template,
            headerValues(header) + mapOf(
                "css" to fill(css, mapOf("pageSize" to "A4")),
                "source" to "MoHFW / MWCD, revised 2018",
                "dateVal" to dateVal,
                "motherName" to (mother?.name ?: b.relativeName), "motherRelative" to (mother?.relativeName.orEmpty()),
                "motherAge" to (mother?.age.orEmpty()), "motherPhone" to (mother?.phone ?: b.phone),
                "address" to "${b.household}, ${b.village}".trim(',', ' '), "rchId" to (mother?.rchId ?: b.rchId),
                "lmp" to ((mother?.lmp ?: b.lmp).takeIf { it.isNotBlank() }?.let(::dd) ?: ""),
                "edd" to ((mother ?: b).let { ArogyaRegistryLogic.edd(it) }?.toString()?.let(::dd) ?: ""),
                "childName" to (child?.name ?: ""), "childSex" to (child?.sex ?: ""),
                "childDob" to (child?.dob?.takeIf { it.isNotBlank() }?.let(::dd) ?: ""),
                "birthWeight" to ((child?.birthWeightKg ?: b.birthWeightKg)?.let { "$it kg" } ?: ""),
                "deliveryPlace" to ((child?.deliveryPlace ?: b.deliveryPlace).let { if (it == "HOME") "Home" else if (it.isBlank()) "" else "Institution" }),
                "termPreterm" to (child?.let { if (it.preterm) "Preterm" else "Term" } ?: ""),
                "childRch" to (child?.rchId ?: ""),
                "hasCheckup" to if (b.category == BenCategory.PREGNANT || checkupPairs.isNotEmpty()) "1" else "",
                "checkupBox" to checkupBox,
                "hasAnc" to if (b.category == BenCategory.PREGNANT) "1" else "",
                "ancTable" to ancGrid, "ancOnce" to ancOnce,
                "prepBox" to prepBox,
                "hasPnc" to if (b.category == BenCategory.MOTHER || b.category == BenCategory.CHILD) "1" else "",
                "pncOnce" to pncOnce, "pncTable" to pncGrid, "babyTable" to babyGrid,
                "hasHbyc" to if (child != null) "1" else "", "hbycTable" to hbycGrid,
                "hasIfa" to if (child != null) "1" else "", "ifaTable" to ifaGrid,
                "hasWeights" to if (weights.isNotEmpty()) "1" else "", "weights" to weights,
                "hasGrowth" to if (growthSvg.isNotBlank()) "1" else "", "growthSvg" to growthSvg,
                "growthNote" to growthNote, "ancTrend" to ancTrend,
                "immTable" to immGrid, "immOnce" to immOnce,
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
                "title" to "Ask SOP — answer", "source" to "Pramaan · grounded in the cited sources",
                "dateVal" to dateVal, "question" to question,
                "answerHtml" to answerHtml,
                "hasSources" to if (srcs.isNotEmpty()) "1" else "", "sources" to srcs,
                "wmText" to ""
            )
        )
        return FilledDoc(html, false, "SOP answer")
    }
}
