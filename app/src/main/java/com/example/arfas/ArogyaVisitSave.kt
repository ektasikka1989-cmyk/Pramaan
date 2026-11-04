package com.example.arfas

import java.time.LocalDate

/**
 * The guided-visit save both the UI (guided flow, demo) and the device tests use.
 * Validate first, then write once: the visit record is checked before the registry
 * is touched, so the visit list, schedule and registers update together or not at all.
 * Throws on failure with the message the caller shows.
 */
object ArogyaVisitSave {
    /**
     * Saves and returns the "what changed" summary, which is also appended to the diary
     * line this save wrote. Callers that do not show it can ignore the return value.
     */
    fun save(
        store: ArogyaRegistryStore, queue: ArogyaVisitQueue, b: Beneficiary, spec: FormSpec, column: Int,
        values: Map<String, String>, sources: Map<String, String>, vaccines: Set<String>,
        today: LocalDate = LocalDate.now(), hindi: Boolean = false
    ): String {
        val record = ArogyaOfficialForms.visitRecord(b, spec, column, values, today)
        queue.check(record)
        var summary = ""
        store.update { r ->
            val a0 = ArogyaOfficialForms.applyNoteMedicines(ArogyaOfficialForms.apply(r, b, spec, column, values, sources, vaccines, today), b, spec, column, values, today)
            val next = ArogyaOfficialForms.nextDue(spec, b, column, today)?.toString().orEmpty()
            summary = ArogyaChangeSummary.diff(r, a0, b.id, hindi, next)
            ArogyaChangeSummary.attachDiary(a0, b.id, today.toString(), summary)
        }
        queue.save(record)
        return summary
    }
}
