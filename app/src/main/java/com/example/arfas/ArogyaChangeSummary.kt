package com.example.arfas

import java.time.LocalDate

/**
 * "What changed" after every save: visit, give, receive, add person. Pure diff of the
 * registry before/after, so the summary lists exactly the records touched, with old → new
 * values. The same text is appended to the diary line the save wrote ([attachDiary]).
 */
object ArogyaChangeSummary {
    private val EN_MONTH = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val HI_MONTH = listOf("जन", "फ़र", "मार्च", "अप्र", "मई", "जून", "जुल", "अग", "सित", "अक्त", "नव", "दिस")

    fun fmtDay(iso: String, hindi: Boolean): String = runCatching {
        val d = LocalDate.parse(iso)
        "${d.dayOfMonth} " + (if (hindi) HI_MONTH[d.monthValue - 1] else EN_MONTH[d.monthValue - 1])
    }.getOrDefault(iso)

    fun diff(before: RegistryData, after: RegistryData, personId: String, hindi: Boolean, nextVisitIso: String = ""): String {
        val parts = ArrayList<String>()
        val ab = after.beneficiaries.firstOrNull { it.id == personId }
        val bb = before.beneficiaries.firstOrNull { it.id == personId }
        if (ab != null && bb == null) parts += (if (hindi) "पंजीकृत: " else "Registered: ") + ab.name
        val bForms = before.forms.filter { it.beneficiaryId == personId }.associate { (it.formId to it.column) to it.values }
        after.forms.filter { it.beneficiaryId == personId }.filter { e -> bForms[e.formId to e.column] != e.values }.forEach { e ->
            val spec = ArogyaOfficialForms.byId(e.formId)
            val title = if (hindi) spec?.hi ?: e.formId else spec?.title?.substringBefore(" —") ?: e.formId
            val col = spec?.columns?.getOrElse(e.column) { "" }.orEmpty()
            parts += (if (hindi) "फ़ॉर्म: " else "Form: ") + title + (if (col.isNotBlank()) " · $col" else "")
        }
        if (ab != null && bb != null) {
            val bits = ArrayList<String>()
            val d = ab.mcp.ifaTablets - bb.mcp.ifaTablets
            if (d != 0) bits += "IFA ${if (d > 0) "+" else "−"}${Math.abs(d)} (total ${ab.mcp.ifaTablets})"
            val nv = ab.mcp.vaccines.keys - bb.mcp.vaccines.keys
            if (nv.isNotEmpty()) bits += (if (hindi) "टीके +" else "vaccines +") + nv.sorted().joinToString(",")
            if (ab.mcp.weights.size > bb.mcp.weights.size) bits += (if (hindi) "वज़न ✓" else "weight ✓")
            val na = ab.mcp.anc.keys - bb.mcp.anc.keys
            if (na.isNotEmpty()) bits += "ANC " + na.sorted().joinToString(",")
            if (ab.mcp.tdDates.size > bb.mcp.tdDates.size) bits += "Td ✓"
            if (ab.familyPlanning != bb.familyPlanning && ab.familyPlanning.isNotBlank()) bits += ab.familyPlanning
            if (bits.isNotEmpty()) parts += (if (hindi) "MCP कार्ड: " else "MCP card: ") + bits.joinToString("; ")
        }
        val bBal = ArogyaRegistryLogic.stockBalance(before.stock)
        val aBal = ArogyaRegistryLogic.stockBalance(after.stock)
        (bBal.keys + aBal.keys).distinct().sorted().mapNotNull { item ->
            val dd = (aBal[item] ?: 0) - (bBal[item] ?: 0)
            if (dd == 0) null else "$item ${if (dd > 0) "+" else "−"}${Math.abs(dd)} (${if (hindi) "बचा" else "left"} ${aBal[item] ?: 0})"
        }.takeIf { it.isNotEmpty() }?.let { parts += (if (hindi) "स्टॉक: " else "Stock: ") + it.joinToString("; ") }
        val bNotes = before.diary.filter { it.beneficiaryId == personId }.map { it.note }.toSet()
        if (after.diary.any { it.beneficiaryId == personId && it.note !in bNotes }) parts += (if (hindi) "डायरी ✓" else "Diary ✓")
        if (nextVisitIso.isNotBlank()) parts += (if (hindi) "अगली विज़िट: " else "Next visit: ") + fmtDay(nextVisitIso, hindi)
        val head = if (hindi) "सहेजा ✓" else "Saved ✓"
        return if (parts.isEmpty()) head else "$head — " + parts.joinToString(" · ")
    }

    /** Summary for the add-person review screen (names known before saving). */
    fun forNewPeople(names: List<String>, hindi: Boolean): String {
        val head = if (hindi) "सहेजा ✓" else "Saved ✓"
        val reg = (if (hindi) "पंजीकृत: " else "Registered: ") + names.joinToString(", ")
        return "$head — $reg · " + (if (hindi) "डायरी ✓" else "Diary ✓")
    }

    /** Append [summary] to the newest diary line this save wrote for [personId] on [date]. */
    fun attachDiary(after: RegistryData, personId: String, date: String, summary: String): RegistryData {
        val idx = after.diary.indexOfLast { it.beneficiaryId == personId && it.date == date }
        if (idx < 0 || summary.isBlank()) return after
        val e = after.diary[idx]
        if (e.note.contains(summary)) return after
        return after.copy(diary = after.diary.toMutableList().also { it[idx] = e.copy(note = e.note + "\n" + summary) })
    }
}
