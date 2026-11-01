import com.example.arfas.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

// Batch 1: save consistency (D1/D2) + re-save idempotency (D3).
// Queue orchestration across stores is covered by V2VisitFlowTest on the device.
fun main() = runBlocking {
    var n = 0; var fails = 0
    fun check(name: String, cond: Boolean) { n++; if (cond) println("PASS $name") else { fails++; println("FAIL $name") } }
    val today = LocalDate.of(2026, 11, 1)
    val baby = Beneficiary(id = "b1", name = "Baby of Ritu", category = BenCategory.CHILD, dob = "2026-10-22")

    // visitRecord: stable id + official marker.
    val rec = ArogyaOfficialForms.visitRecord(baby, ArogyaOfficialForms.ANNEX9, 3, mapOf("weight" to "2.9"), today)
    check("stable id", rec.id == "official:b1:annex9:3")
    check("official marker", rec.protocolVersion == ArogyaWorkflow.OFFICIAL_FORM)
    check("marker supported", rec.protocolVersion in ArogyaWorkflow.SUPPORTED_VERSIONS)
    check("same column same id", ArogyaOfficialForms.visitRecord(baby, ArogyaOfficialForms.ANNEX9, 3, mapOf("weight" to "3.0"), today).id == rec.id)
    check("other column other id", ArogyaOfficialForms.visitRecord(baby, ArogyaOfficialForms.ANNEX9, 4, mapOf(), today).id == "official:b1:annex9:4")

    // Queue: official completed records save without checklist answers; re-save replaces.
    val disk = object : ArogyaVisitPersistence { var rows: List<ArogyaVisit> = emptyList(); override fun readVisits() = rows; override fun writeVisits(v: List<ArogyaVisit>) { rows = v } }
    val q = ArogyaVisitQueue(disk)
    repeat(100) { if (q.state.value.loaded) return@repeat; kotlinx.coroutines.delay(50) }
    check("queue loaded", q.state.value.loaded)
    runCatching { q.check(rec) }.fold({ check("check accepts official", true) }, { check("check accepts official: ${it.message}", false) })
    runCatching { q.save(rec) }.fold({ check("save accepts official", true) }, { check("save accepts official: ${it.message}", false) })
    val rec2 = ArogyaOfficialForms.visitRecord(baby, ArogyaOfficialForms.ANNEX9, 3, mapOf("weight" to "3.0"), today)
    runCatching { q.save(rec2) }.fold({ check("re-save accepted", true) }, { check("re-save accepted: ${it.message}", false) })
    check("one visit after re-save", q.state.value.visits.count { it.id == rec.id } == 1)
    check("revision bumped", q.state.value.visits.first { it.id == rec.id }.revision == 2L)

    // No regression: ordinary completed drafts still need the checklist.
    val draft = ArogyaVisit(personId = "b1", name = "Baby of Ritu", kind = ArogyaKind.CHILD, date = today.toString(), completed = true, reviewed = true)
    runCatching { q.check(draft) }.fold({ check("draft still validated", false) }, { check("draft still validated", (it.message ?: "").contains("review")) })
    val before = q.state.value.visits.size
    runCatching { q.save(draft) }
    check("failed save writes nothing", q.state.value.visits.size == before)

    // apply(): IFA set-per-column, stock replace-per-column.
    val preg = Beneficiary(id = "p1", name = "Sunita", category = BenCategory.PREGNANT, lmp = "2026-09-05")
    val anc = ArogyaOfficialForms.MCP_ANC
    var r = RegistryData(beneficiaries = listOf(preg))
    r = ArogyaOfficialForms.apply(r, preg, anc, 0, mapOf("weight" to "52", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("ifa first save", r.beneficiaries.first().mcp.ifaTablets == 30)
    r = ArogyaOfficialForms.apply(r, preg, anc, 0, mapOf("weight" to "52", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("ifa re-save same column", r.beneficiaries.first().mcp.ifaTablets == 30)
    r = ArogyaOfficialForms.apply(r, preg, anc, 1, mapOf("weight" to "54", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("ifa accumulates across columns", r.beneficiaries.first().mcp.ifaTablets == 60)
    check("stock one txn per column", r.stock.count { it.item == "IFA tablets (adult)" } == 2)
    r = ArogyaOfficialForms.apply(r, preg, anc, 1, mapOf("weight" to "54", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("stock re-save replaces", r.stock.count { it.item == "IFA tablets (adult)" } == 2)
    check("ifa still 60", r.beneficiaries.first().mcp.ifaTablets == 60)

    println("Save: ${n - fails}/$n passed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
