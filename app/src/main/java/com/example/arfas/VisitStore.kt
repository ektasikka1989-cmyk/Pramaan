package com.example.arfas

import org.json.JSONObject
import java.io.File

/**
 * Saved visit records (one JSON line each, on this phone only) and the due list built from them:
 * "which visits are due / aaj kiske ghar jana hai" → overdue and due-today visits, plus people
 * with a danger sign and no next visit set. Pure Kotlin + org.json: unit-tested in [VisitNoteTest].
 */
class VisitStore(private val file: File) {

    data class DueItem(
        val name: String,
        val category: String,
        val dueDay: Long?,
        val daysLate: Long,
        val alerts: List<String>,
        val lastSeenDay: Long
    )

    @Synchronized
    fun save(result: VisitNote.Result, savedDay: Long = VisitNote.todayEpochDay()) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(VisitNote.toJson(result, savedDay).toString() + "\n")
        }
    }

    @Synchronized
    fun all(): List<JSONObject> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
            line.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    fun count(): Int = all().size

    /** Latest record per person; due when the next visit is today or past, or a danger sign has no next visit. */
    fun due(today: Long = VisitNote.todayEpochDay(), aheadDays: Long = 0): List<DueItem> {
        val latest = LinkedHashMap<String, JSONObject>()
        all().forEachIndexed { i, j ->
            val name = j.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val key = name?.lowercase() ?: "#unnamed-$i"
            latest[key] = j   // later lines win
        }
        val out = ArrayList<DueItem>()
        for ((_, j) in latest) {
            val saved = j.optLong("saved_day", today)
            val next = if (j.isNull("next_visit_day")) null else j.optLong("next_visit_day")
            val alerts = (0 until (j.optJSONArray("alert_text")?.length() ?: 0)).map { j.getJSONArray("alert_text").getString(it) }
            val serious = (0 until (j.optJSONArray("alerts")?.length() ?: 0)).any {
                val a = j.getJSONArray("alerts").getString(it); a.startsWith("URGENT") || a.startsWith("REFER")
            }
            val include = when {
                next != null -> next <= today + aheadDays
                else -> serious && saved >= today - 7
            }
            if (!include) continue
            out += DueItem(
                name = j.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "(name not given)",
                category = j.optString("category", "UNKNOWN"),
                dueDay = next,
                daysLate = if (next != null) (today - next).coerceAtLeast(0) else 0,
                alerts = alerts,
                lastSeenDay = saved
            )
        }
        return out.sortedWith(compareByDescending<DueItem> { it.daysLate }.thenBy { it.dueDay == null }.thenBy { it.dueDay ?: Long.MAX_VALUE })
    }

    companion object {
        private val DUE = Regex(
            "(?i)\\bdue\\s*(?:list|visits?)\\b|\\bvisits?\\s*(?:are\\s*)?due\\b|which\\s*visits|who\\s*(?:should|do|must)\\s*i\\s*visit|" +
                "follow[\\s-]?up\\s*list|\\bmy\\s*visits\\b|kiske\\s*ghar|kis\\s*ke\\s*ghar|aaj\\s*ki\\s*visit|" +
                "किसके\\s*घर|किस\\s*के\\s*घर|आज\\s*की\\s*विज़िट|बकाया\\s*विज़िट|ड्यू\\s*लिस्ट"
        )

        fun isDueRequest(text: String): Boolean = DUE.containsMatchIn(text) && !VisitNote.isRequest(text)

        private fun categoryLabel(c: String, hi: Boolean): String = when (c) {
            "PREGNANT" -> if (hi) "गर्भवती" else "pregnant"
            "POSTNATAL" -> if (hi) "प्रसव के बाद" else "after delivery"
            "NEWBORN" -> if (hi) "नवजात" else "newborn"
            "CHILD" -> if (hi) "बच्चा" else "child"
            "ELDERLY" -> if (hi) "बुज़ुर्ग" else "older adult"
            "ADULT" -> if (hi) "वयस्क" else "adult"
            else -> "—"
        }

        fun render(items: List<DueItem>, today: Long, hindi: Boolean, saved: Int = -1): String {
            fun L(en: String, hi: String) = if (hindi) hi else en
            if (items.isEmpty()) return if (saved == 0)
                L("No visit notes saved yet. Start one with \"Visit note:\" and tell me about the visit; the due list is built from them.",
                    "अभी कोई विज़िट नोट सहेजा नहीं गया। \"विज़िट नोट:\" से शुरू करके विज़िट के बारे में बताइए; ड्यू लिस्ट उन्हीं से बनती है।")
            else L("No visits are due today. Next visits from your notes are all later.", "आज कोई विज़िट बकाया नहीं है। आपके नोट्स की अगली विज़िट बाद में हैं।")
            return buildString {
                append("**").append(L("Visits due", "बकाया विज़िट")).append(" — ").append(VisitNote.dateLabel(today)).append("**\n\n")
                append("| ").append(L("Name", "नाम")).append(" | ").append(L("Who", "कौन")).append(" | ").append(L("Due", "कब")).append(" | ").append(L("Alert", "चेतावनी")).append(" |\n")
                append("| --- | --- | --- | --- |\n")
                for (i in items) {
                    val due = when {
                        i.dueDay == null -> L("follow up (danger sign, no date set)", "फ़ॉलो-अप (खतरे का लक्षण, तारीख़ नहीं)")
                        i.daysLate > 0 -> L("${i.daysLate} days late", "${i.daysLate} दिन देर")
                        else -> L("today", "आज")
                    }
                    val alert = i.alerts.firstOrNull()?.substringBefore(" — ")?.take(70) ?: "—"
                    append("| ").append(i.name.replace('|', ' ')).append(" | ").append(categoryLabel(i.category, hindi))
                        .append(" | ").append(due).append(" | ").append(alert.replace('|', ' ')).append(" |\n")
                }
                append("\n").append(L("From the visit notes saved on this phone.", "इस फ़ोन पर सहेजे गए विज़िट नोट्स से।"))
            }.trim()
        }
    }
}
