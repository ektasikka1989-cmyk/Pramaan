package com.example.arfas

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V2 visit flow (offline): the same [ArogyaVisitSave.save] the UI uses.
 * Baby of Ritu Day 14 + Sunita Devi ANC: fill, save, save again, reopen from disk.
 * MUST FAIL before the Batch 1 fix (D1/D2 save bug, D3 double count) and pass after.
 * Sample village (demo=true) only; reloaded at the end. Real records untouched.
 */
@RunWith(AndroidJUnit4::class)
class V2VisitFlowTest {
    @Test
    fun visitFlow() = runBlocking {
        val c = ArogyaSelfTest.ctx()
        val checks = ArrayList<ArogyaSelfTest.Check>()
        fun add(area: String, name: String, ok: Boolean, detail: String) {
            checks += ArogyaSelfTest.Check(area, name, ok, 0, detail)
            ArogyaSelfTest.mark("${if (ok) "PASS" else "FAIL"} $area/$name $detail".take(220))
        }
        val store = ArogyaRegistryStore.get(c)
        val queue = ArogyaVisitStore.get(c).second
        val today = LocalDate.now()
        try {
            store.load()
            val qLoaded = withTimeoutOrNull(15_000) { while (!queue.state.value.loaded) delay(200); true } == true
            add("setup", "stores loaded", qLoaded && store.loaded, "registry=${store.loaded} queue=$qLoaded")
            if (store.state.value.beneficiaries.none { it.demo }) reloadSample(store, queue)
            val ritu0 = store.state.value.beneficiaries.first { it.name.startsWith("Baby of Ritu") }
            val sunita0 = store.state.value.beneficiaries.first { it.name.startsWith("Sunita Devi") }
            val ifaBefore = ArogyaRegistryLogic.stockBalance(store.state.value.stock)["IFA tablets (adult)"] ?: 0
            val stockN = store.state.value.stock.size

            // ── Baby of Ritu, Annexure 9, Day 14 (column 3) ──
            val spec = ArogyaOfficialForms.ANNEX9
            val rituVals = mapOf("weight" to "2.9", "temp" to "36.8", "warm" to "Yes", "fed" to "Yes", "cry_urine" to "No")
            val save1 = runCatching { ArogyaVisitSave.save(store, queue, ritu0, spec, 3, rituVals, emptyMap(), emptySet(), today) }
            add("ritu", "save day 14", save1.isSuccess, save1.fold({ "ok" }, { it.message ?: "failed" }))
            val save2 = runCatching { ArogyaVisitSave.save(store, queue, ritu0, spec, 3, rituVals, emptyMap(), emptySet(), today) }
            add("ritu", "save again (idempotent)", save2.isSuccess, save2.fold({ "ok" }, { it.message ?: "failed" }))
            withTimeoutOrNull(15_000) { while (queue.state.value.saving) delay(200) }
            val reg = store.state.value
            val ritu = reg.beneficiaries.first { it.id == ritu0.id }
            add("ritu", "one form entry", reg.forms.count { it.beneficiaryId == ritu.id && it.formId == "annex9" && it.column == 3 } == 1,
                "entries=${reg.forms.count { it.beneficiaryId == ritu.id && it.formId == "annex9" && it.column == 3 }}")
            add("ritu", "mcp weight card", ritu.mcp.weights.any { it.date == today.toString() && it.kg == 2.9 },
                "weights=${ritu.mcp.weights.size}")
            add("ritu", "one diary entry", reg.diary.count { it.beneficiaryId == ritu.id && it.date == today.toString() && it.kind == DiaryKind.HOME_VISIT } == 1,
                "diary=${reg.diary.count { it.beneficiaryId == ritu.id && it.date == today.toString() }}")
            val rituVisits = queue.state.value.visits.filter { it.personId == ritu.id && it.date == today.toString() }
            add("ritu", "one visit record", rituVisits.size == 1, "visits=${rituVisits.size}")
            // Day-1 rule: birth = Day 1, so the Day 21 visit is 20 days after the sample DOB (at least tomorrow).
            val wantNext = LocalDate.parse(ritu.dob).plusDays(20).let { if (it.isBefore(today.plusDays(1))) today.plusDays(1) else it }
            add("ritu", "next due = day 21", rituVisits.firstOrNull()?.nextDate == wantNext.toString(),
                "next=${rituVisits.firstOrNull()?.nextDate} want=$wantNext dob=${ritu.dob}")
            val diskVisits = runCatching { ArogyaVisitRepository(c).readVisits() }.getOrDefault(emptyList())
            add("ritu", "reopened from disk", diskVisits.any { v -> rituVisits.any { it.id == v.id } }, "disk=${diskVisits.size}")
            val pdf = runCatching { store.renderFormPdf(ArogyaOfficialForms.table(spec, ritu, reg.forms)) }.getOrNull()
            add("ritu", "form PDF renders", pdf != null && pdf.size > 1000 && pdf[0] == '%'.code.toByte(),
                "bytes=${pdf?.size ?: 0}")

            // ── Sunita Devi, MCP ANC, next empty column ──
            val sun = store.state.value.beneficiaries.first { it.id == sunita0.id }
            val (ancSpec, ancCol) = ArogyaOfficialForms.formFor(sun, store.state.value.forms, today)
            val ancVals = mapOf("weight" to "52", "bp" to "110/70", "hb" to "11.2", "ifa" to "30", "complaints" to "None")
            val asave1 = runCatching { ArogyaVisitSave.save(store, queue, sun, ancSpec, ancCol, ancVals, emptyMap(), emptySet(), today) }
            add("anc", "save anc column ${ancCol + 1}", asave1.isSuccess, asave1.fold({ "spec=${ancSpec.id}" }, { it.message ?: "failed" }))
            val asave2 = runCatching { ArogyaVisitSave.save(store, queue, sun, ancSpec, ancCol, ancVals, emptyMap(), emptySet(), today) }
            add("anc", "save again (idempotent)", asave2.isSuccess, asave2.fold({ "ok" }, { it.message ?: "failed" }))
            withTimeoutOrNull(15_000) { while (queue.state.value.saving) delay(200) }
            val reg2 = store.state.value
            val sun2 = reg2.beneficiaries.first { it.id == sun.id }
            add("anc", "anc card entry", sun2.mcp.ancDetails[ancCol + 1]?.bp == "110/70", "anc=${sun2.mcp.anc.keys}")
            val ifaAfter = ArogyaRegistryLogic.stockBalance(reg2.stock)["IFA tablets (adult)"] ?: 0
            add("anc", "ifa given once (no double count)", ifaAfter - ifaBefore == -30, "ifa $ifaBefore → $ifaAfter (want -30)")
            add("anc", "mcp ifa total once", sun2.mcp.ifaTablets - sun.mcp.ifaTablets == 30, "mcp ifa ${sun.mcp.ifaTablets} → ${sun2.mcp.ifaTablets}")
            add("anc", "one visit record", queue.state.value.visits.count { it.personId == sun.id && it.date == today.toString() } == 1,
                "visits=${queue.state.value.visits.count { it.personId == sun.id && it.date == today.toString() }}")
            val regFile = File(File(c.noBackupFilesDir, "arogyadoot_visits"), "registry.enc")
            add("persist", "registry file written", regFile.exists() && regFile.length() > 100, "bytes=${regFile.length()}")
        } finally {
            runCatching { reloadSample(store, queue) }
        }
        val fails = checks.filter { !it.ok }
        ArogyaSelfTest.writeResults(c, "v2",
            JSONObject().put("mode", "offline").put("checks", ArogyaSelfTest.checksJson(checks)),
            ArogyaSelfTest.mdReport("V2 visit flow (offline, same save as UI)",
                mapOf("records" to "sample village only; reloaded at end",
                    "limit" to "registry reopen is file-exists + host codec tests; full decrypt-reopen is a manual check"), checks))
        assertTrue("V2 failures: " + fails.joinToString("; ") { it.area + "/" + it.name + ": " + it.detail.take(120) }, fails.isEmpty())
    }

    /** Same reload as the app's "load sample village", plus demo-beneficiary cleanup of test writes. */
    private fun reloadSample(store: ArogyaRegistryStore, queue: ArogyaVisitQueue) {
        val sm = ArogyaSampleVillage.build()
        store.update { r -> r.copy(beneficiaries = r.beneficiaries.filterNot { it.demo } + sm.registry.beneficiaries,
            diary = r.diary.filterNot { it.id.startsWith("demo-") || it.beneficiaryId.startsWith("demo-") } + sm.registry.diary,
            stock = r.stock.filterNot { it.id.startsWith("demo-") || it.beneficiaryId.startsWith("demo-") } + sm.registry.stock,
            vitals = r.vitals.filterNot { it.id.startsWith("demo-") } + sm.registry.vitals,
            forms = r.forms.filterNot { f -> f.id.startsWith("demo-") || f.beneficiaryId.startsWith("demo-") } + sm.registry.forms) }
        queue.resetDemo(sm.visits)
    }
}
