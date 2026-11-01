package com.example.arfas
object ArogyaShare {
    fun guideEntryText(e: ArogyaGuideEntry, n: Int = 0, withPassages: Boolean = true): String = e.question
    fun ageText(v: ArogyaVisit): String {   // copied from ArogyaShare.ageText (Android file not host-compilable)
        val b = ArogyaWorkflow.parseDate(v.birthDate) ?: return ""; val d = ArogyaWorkflow.parseDate(v.date) ?: return ""
        val days = java.time.temporal.ChronoUnit.DAYS.between(b, d)
        return when { days < 0 -> ""; days <= 59 -> "$days days old"; else -> "${days / 30} months old" }
    }
}
