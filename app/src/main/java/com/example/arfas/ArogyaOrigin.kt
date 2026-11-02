package com.example.arfas

/** Where one record came from, for the tap-to-history dialogs. */
data class OriginInfo(val title: String, val lines: List<String>)

/**
 * Entry history: every stock line, diary line, form cell and MCP card value can say
 * where it came from — visit, voice note, scan or typed — with its date and the fact
 * that the ASHA confirmed it. Derived from the keys and note conventions the save
 * paths write; no model change, so old lines still decode.
 */
object ArogyaOrigin {
    fun confirmed(hindi: Boolean) = if (hindi) "✓ ASHA ने सहेजने से पहले देखा" else "✓ Confirmed by the ASHA before saving"
    fun fixedRule(hindi: Boolean) = if (hindi) "⚙ तय सरकारी नियम (AI नहीं)" else "⚙ Fixed government rule (not AI)"
    fun sample(hindi: Boolean) = if (hindi) "नमूना डेटा (काल्पनिक)" else "Sample data (fictional)"

    private fun dateLine(date: String, hindi: Boolean) =
        (if (hindi) "तारीख़: " else "Date: ") + ArogyaChangeSummary.fmtDay(date, hindi)

    /** How one field value was produced (FormEntry.sources: ai | rule | asha | demo | manual …). */
    fun sourceLine(source: String, hindi: Boolean): String = when (source.lowercase()) {
        "ai" -> if (hindi) "✨ AI सुझाव (उसके शब्दों/फ़ोटो से)" else "✨ AI proposal (from her words/photo)"
        "rule" -> fixedRule(hindi)
        "asha", "manual" -> if (hindi) "ASHA ने लिखा" else "Typed by the ASHA"
        "voice" -> if (hindi) "🎤 आवाज़ नोट से" else "🎤 From a voice note"
        "scan" -> if (hindi) "📷 फ़ोटो स्कैन से" else "📷 From a photo scan"
        "demo" -> sample(hindi)
        "visit" -> if (hindi) "घर विज़िट में" else "At a home visit"
        else -> if (hindi) "ASHA ने लिखा" else "Typed by the ASHA"
    }

    fun stock(t: StockTxn, personName: String, hindi: Boolean): OriginInfo {
        val key = ArogyaOfficialForms.stockKey(t.note)
        if (key != null) {
            val spec = ArogyaOfficialForms.byId(key.first)
            val title = if (hindi) spec?.hi ?: key.first else spec?.title?.substringBefore(" —") ?: key.first
            val col = spec?.columns?.getOrElse(key.second) { "" }.orEmpty()
            return OriginInfo(if (hindi) "दवा किट प्रविष्टि" else "Drug kit entry", listOfNotNull(
                (if (hindi) "घर विज़िट में दिया" else "Given at a home visit") + if (personName.isNotBlank()) ": $personName" else "",
                ((if (hindi) "फ़ॉर्म: " else "Form: ") + title + (if (col.isNotBlank()) " · $col" else "")).takeIf { spec != null },
                dateLine(t.date, hindi), confirmed(hindi)))
        }
        val give = ArogyaOfficialForms.giveKey(t.note)
        if (give != null) {
            val via = if (t.source.isBlank() || t.source == "manual") sourceLine("manual", hindi) else sourceLine(t.source, hindi)
            return OriginInfo(if (hindi) "दवा किट प्रविष्टि" else "Drug kit entry", listOfNotNull(
                ((if (hindi) "दिया: " else "Given to ") + personName).takeIf { personName.isNotBlank() },
                via, dateLine(t.date, hindi), confirmed(hindi)))
        }
        if (t.note.startsWith("Voice:")) return OriginInfo(if (hindi) "दवा किट प्रविष्टि" else "Drug kit entry", listOfNotNull(
            sourceLine("voice", hindi),
            t.reason.takeIf { it.isNotBlank() }?.let { (if (hindi) "सुना: " else "Heard: ") + it },
            dateLine(t.date, hindi), confirmed(hindi)))
        if (t.note.startsWith("Scan:")) return OriginInfo(if (hindi) "दवा किट प्रविष्टि" else "Drug kit entry",
            listOf(sourceLine("scan", hindi), dateLine(t.date, hindi), confirmed(hindi)))
        val what = when (ArogyaRegistryLogic.txnKind(t)) {
            "receive" -> if (hindi) "स्टॉक मिला" else "Stock received"
            "give" -> (if (hindi) "दिया" else "Given") + if (personName.isNotBlank()) ": $personName" else ""
            "writeoff" -> if (hindi) "एक्सपायर/ख़राब" else "Expired/damaged"
            else -> if (hindi) "सुधार" else "Correction"
        }
        return OriginInfo(if (hindi) "दवा किट प्रविष्टि" else "Drug kit entry", listOfNotNull(
            what, t.source.takeIf { it.isNotBlank() && it != "manual" && it != "visit" && it != "voice" },
            t.reason.takeIf { it.isNotBlank() }, dateLine(t.date, hindi), confirmed(hindi)))
    }

    fun diary(e: DiaryEntry, stock: List<StockTxn>, hindi: Boolean): OriginInfo {
        val give = ArogyaOfficialForms.giveKey(e.note)
        if (e.kind == DiaryKind.MEDICINE && give != null) {
            val t = stock.firstOrNull { it.beneficiaryId == e.beneficiaryId && ArogyaOfficialForms.giveKey(it.note) == give }
            val via = if (t == null || t.source.isBlank() || t.source == "manual") sourceLine("manual", hindi) else sourceLine(t.source, hindi)
            return OriginInfo(if (hindi) "डायरी" else "Diary", listOf(e.note.substringBefore(" [give#"), via, dateLine(e.date, hindi), confirmed(hindi)))
        }
        if (e.kind == DiaryKind.HOME_VISIT && e.note.startsWith("Form:")) return OriginInfo(
            if (hindi) "डायरी" else "Diary",
            listOf(e.note.substringBefore("\n"), if (hindi) "विज़िट सहेजने पर अपने आप जुड़ा" else "Added automatically when the visit was saved",
                dateLine(e.date, hindi), confirmed(hindi)))
        if (e.note.startsWith("Registered:") || e.note.startsWith("पंजीकृत:")) {
            val first = e.note.substringBefore("\n")
            val via = first.substringAfter("·", "").trim().takeIf { it.isNotBlank() }?.let {
                if (hindi) "स्रोत: $it" else "Source: $it"
            }
            return OriginInfo(if (hindi) "डायरी" else "Diary", listOfNotNull(
                first.substringBefore(" · "), via, dateLine(e.date, hindi), confirmed(hindi)))
        }
        return OriginInfo(if (hindi) "डायरी" else "Diary", listOfNotNull(
            e.note.substringBefore("\n").takeIf { it.isNotBlank() },
            if (hindi) "ASHA ने जोड़ा" else "Added by the ASHA", dateLine(e.date, hindi), confirmed(hindi)))
    }

    fun visitRecord(v: ArogyaVisit, hindi: Boolean): OriginInfo = OriginInfo(
        if (hindi) "विज़िट" else "Visit",
        listOf(v.kind.title + (if (v.name.isNotBlank()) " · ${v.name}" else ""), dateLine(v.date, hindi), confirmed(hindi)))

    fun formCell(fieldLabel: String, value: String, source: String, entryDate: String, demo: Boolean, hindi: Boolean): OriginInfo {
        val via = if (demo || source == "demo") sample(hindi) else sourceLine(source.ifBlank { "asha" }, hindi)
        return OriginInfo(fieldLabel, listOfNotNull(
            value.takeIf { it.isNotBlank() },
            via, dateLine(entryDate, hindi).takeIf { entryDate.isNotBlank() },
            if (demo || source == "demo") null else confirmed(hindi)))
    }

    /** One tappable row per MCP card contribution: weights, vaccines, ANC, IFA. */
    data class McpOrigin(val label: String, val info: OriginInfo)
    fun mcp(b: Beneficiary, forms: List<FormEntry>, stock: List<StockTxn>, hindi: Boolean): List<McpOrigin> {
        val out = ArrayList<McpOrigin>()
        b.mcp.weights.sortedBy { it.date }.forEach { w ->
            out += McpOrigin((if (hindi) "वज़न " else "Weight ") + "${w.kg} kg · ${ArogyaChangeSummary.fmtDay(w.date, hindi)}",
                OriginInfo(if (hindi) "MCP कार्ड" else "MCP card",
                    listOf(if (hindi) "विज़िट फ़ॉर्म से" else "From a visit form", dateLine(w.date, hindi), confirmed(hindi))))
        }
        b.mcp.vaccines.toSortedMap().forEach { (code, d) ->
            out += McpOrigin("$code · ${ArogyaChangeSummary.fmtDay(d, hindi)}",
                OriginInfo(if (hindi) "MCP कार्ड" else "MCP card",
                    listOf(if (hindi) "विज़िट में टीका दर्ज" else "Vaccine recorded at a visit", dateLine(d, hindi), confirmed(hindi))))
        }
        b.mcp.anc.toSortedMap().forEach { (n, d) ->
            out += McpOrigin("ANC $n · ${ArogyaChangeSummary.fmtDay(d, hindi)}",
                OriginInfo(if (hindi) "MCP कार्ड" else "MCP card",
                    listOf(if (hindi) "ANC विज़िट" else "ANC visit", dateLine(d, hindi), confirmed(hindi))))
        }
        val ifaForms = forms.filter { it.beneficiaryId == b.id && it.formId == "mcp_anc" && (it.values["ifa"]?.toDoubleOrNull() ?: 0.0) > 0 }
        val ifaGives = stock.filter { it.beneficiaryId == b.id && it.item == ArogyaOfficialForms.IFA_ITEM && it.qty < 0 }
        if (b.mcp.ifaTablets > 0 || ifaForms.isNotEmpty() || ifaGives.isNotEmpty()) {
            val lines = ArrayList<String>()
            lines += (if (hindi) "कुल IFA: " else "IFA total: ") + b.mcp.ifaTablets
            ifaForms.forEach { lines += "ANC ${it.column + 1} · ${it.values["ifa"]} · ${ArogyaChangeSummary.fmtDay(it.date, hindi)}" }
            ifaGives.forEach { lines += "${-it.qty} · ${ArogyaChangeSummary.fmtDay(it.date, hindi)}" +
                (if (ArogyaOfficialForms.giveKey(it.note) != null) "" else " (" + (if (hindi) "पुरानी प्रविष्टि" else "older entry") + ")") }
            lines += confirmed(hindi)
            out += McpOrigin((if (hindi) "IFA कुल " else "IFA total ") + b.mcp.ifaTablets, OriginInfo(if (hindi) "MCP कार्ड" else "MCP card", lines))
        }
        return out
    }
}
