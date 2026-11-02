import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth
var sf = 0; var sp = 0
fun s(c: Boolean, m: String) { if (c) { sp++; println("PASS $m") } else { sf++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.now()
    val v = ArogyaSampleVillage.build(today)
    val r = v.registry
    val vhr = ArogyaRegistryLogic.villageRegister(r.beneficiaries, today)
    println("   people=${vhr.people} households=${vhr.households} groups=${vhr.byGroup} highRisk=${vhr.highRisk} visits=${v.visits.size} drafts=${v.visits.count { !it.completed }}")
    s(r.beneficiaries.size == 50 && r.beneficiaries.all { it.demo }, "50 people, all marked demo")
    s(r.beneficiaries.map { it.id }.toSet().size == 50, "unique ids")
    s(vhr.households in 15..35, "households ${vhr.households}")
    s(vhr.highRisk >= 1, "at least one high-risk baby")
    val due = ArogyaSchedule.dueList(r.beneficiaries, v.visits, today, 7)
    println("   due this week: ${due.size}, overdue ${due.count { it.overdue(today) }}; first: " + due.take(4).map { it.name + " / " + it.label })
    s(due.isNotEmpty() && due.any { it.overdue(today) }, "due list has items and some overdue")
    val done = r.beneficiaries.flatMap { b -> ArogyaSchedule.forBeneficiary(b, v.visits, today) }.count { it.done && it.kind == "HBNC" }
    s(done >= 8, "HBNC visits already done in sample ($done)")
    s(v.visits.count { !it.completed } == 2, "two unfinished visits to continue")
    s(ArogyaRegistryLogic.immunizationDueList(r.beneficiaries, YearMonth.from(today).plusMonths(1), today).isNotEmpty(), "immunization due list non-empty")
    s(ArogyaRegistryLogic.ancList(r.beneficiaries, today).size == 8, "8 ANC beneficiaries")
    s(ArogyaRegistryLogic.eligibleCouples(r.beneficiaries).size == 10, "10 eligible couples")
    s(ArogyaRegistryLogic.stockBalance(r.stock)["ORS"] == 46, "ORS stock 46")
    s(ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(r)) == r, "sample round-trips")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
