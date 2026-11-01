package com.example.arfas

import java.time.LocalDate

/**
 * The guided-visit save both the UI (guided flow, demo) and the device tests use.
 * Validate first, then write once: the visit record is checked before the registry
 * is touched, so the visit list, schedule and registers update together or not at all.
 * Throws on failure with the message the caller shows.
 */
object ArogyaVisitSave {
    fun save(
        store: ArogyaRegistryStore, queue: ArogyaVisitQueue, b: Beneficiary, spec: FormSpec, column: Int,
        values: Map<String, String>, sources: Map<String, String>, vaccines: Set<String>,
        today: LocalDate = LocalDate.now()
    ) {
        val record = ArogyaOfficialForms.visitRecord(b, spec, column, values, today)
        queue.check(record)
        store.update { r -> ArogyaOfficialForms.apply(r, b, spec, column, values, sources, vaccines, today) }
        queue.save(record)
    }
}
